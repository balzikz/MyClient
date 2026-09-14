package com.balzikz.mathclient.foundation.contract;

import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.balzikz.mathclient.foundation.core.Inventory;
import java.io.*;
import java.util.*;

/** Parses declarations as data; does not use reflection, DexClassLoader, or class initialization. */
public final class DexContracts {
    private DexContracts() {}
    public static final String MAIN = "Lcom/mojang/minecraftpe/MainActivity;";
    public static final class Index {
        public final Map<String, String> parents = new LinkedHashMap<>();
        public final Set<String> nativeMethods = new TreeSet<>();
        public int dexFiles, classes, duplicateClasses;
        public List<String> hierarchy(String type) {
            List<String> chain = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            while (type != null && seen.add(type) && chain.size() < 64) {
                chain.add(type); type = parents.get(type);
            }
            return chain;
        }
    }
    public static String descriptor(MethodReference method) {
        StringBuilder out = new StringBuilder("(");
        for (CharSequence type : method.getParameterTypes()) out.append(type);
        return out.append(')').append(method.getReturnType()).toString();
    }
    private static boolean selected(String name, Set<String> roots) {
        return roots.contains(name) || name.startsWith("Lcom/mojang/minecraftpe/")
                || name.startsWith("Lcom/google/androidgamesdk/") || name.startsWith("Landroidx/games/");
    }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
    }
    public static void scan(byte[] bytes, String source, Set<String> roots, Index index, Inventory.Sink sink) throws IOException {
        checkInterrupted();
        try {
            DexBackedDexFile dex = new DexBackedDexFile(null, bytes);
            if (dex.getFileSize() != bytes.length) throw new IOException("DEX container size mismatch or unsupported combined DEX");
            int classes = 0, natives = 0;
            for (ClassDef type : dex.getClasses()) {
                checkInterrupted();
                if (++index.classes > 200_000) throw new IOException("DEX class count exceeds limit");
                classes++;
                String name = type.getType();
                if (index.parents.containsKey(name)) {
                    index.duplicateClasses++;
                    sink.item("duplicate_java_class", Inventory.fields("source", source, "name", name));
                } else index.parents.put(name, type.getSuperclass());
                boolean full = selected(name, roots), hasNative = false;
                for (Method method : type.getMethods()) if ((method.getAccessFlags() & 0x100) != 0) { hasNative = true; break; }
                if (!full && !hasNative) continue;
                sink.item("java_class", Inventory.fields("source", source, "name", name,
                        "superclass", type.getSuperclass(), "interfaces", type.getInterfaces(), "accessFlags", type.getAccessFlags()));
                if (full) for (Field field : type.getFields()) {
                    sink.item("java_field", Inventory.fields("owner", name, "name", field.getName(),
                            "type", field.getType(), "accessFlags", field.getAccessFlags()));
                }
                for (Method method : type.getMethods()) {
                    checkInterrupted();
                    boolean nativeMethod = (method.getAccessFlags() & 0x100) != 0;
                    if (!full && !nativeMethod) continue;
                    String signature = descriptor(method);
                    if (nativeMethod) { natives++; index.nativeMethods.add(name + "->" + method.getName() + signature); }
                    sink.item("java_method", Inventory.fields("owner", name, "name", method.getName(),
                            "descriptor", signature, "accessFlags", method.getAccessFlags(),
                            "native", nativeMethod, "static", (method.getAccessFlags() & 8) != 0));
                    // Record only method references, never implementation text, literal strings or assets.
                    boolean startupClass = roots.contains(name) || name.contains("/GameActivity;") || name.equals(MAIN);
                    if (!startupClass || nativeMethod) continue;
                    MethodImplementation implementation = method.getImplementation();
                    if (implementation == null) continue;
                    Set<String> calls = new LinkedHashSet<>();
                    int steps = 0;
                    for (Instruction instruction : implementation.getInstructions()) {
                        if (++steps > 1_000_000) throw new IOException("DEX method instruction limit exceeded");
                        if ((steps & 4095) == 0) checkInterrupted();
                        if (!(instruction instanceof ReferenceInstruction)) continue;
                        Reference ref = ((ReferenceInstruction) instruction).getReference();
                        if (!(ref instanceof MethodReference) || !instruction.getOpcode().name().startsWith("INVOKE_")) continue;
                        MethodReference call = (MethodReference) ref;
                        calls.add(call.getDefiningClass() + "->" + call.getName() + descriptor(call));
                    }
                    if (!calls.isEmpty()) sink.item("java_calls", Inventory.fields("owner", name,
                            "method", method.getName() + signature, "references", calls, "executed", false));
                }
            }
            index.dexFiles++;
            sink.item("dex_summary", Inventory.fields("source", source, "bytes", bytes.length,
                    "sha256", Inventory.digest(new ByteArrayInputStream(bytes)).hash,
                    "classes", classes, "nativeMethods", natives, "classInitialization", false));
        } catch (RuntimeException error) {
            throw new IOException("Cannot parse DEX " + source + ": " + error.getMessage(), error);
        }
    }
}
