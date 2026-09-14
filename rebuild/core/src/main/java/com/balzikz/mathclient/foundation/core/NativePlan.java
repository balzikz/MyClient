package com.balzikz.mathclient.foundation.core;

import java.io.IOException;
import java.util.*;

/** Dependency metadata only. Does not dlopen, assume platform availability, or choose JNI load owners. */
public final class NativePlan {
    public final List<String> order = new ArrayList<>();
    public final Set<String> external = new TreeSet<>();
    public final Set<String> cycles = new TreeSet<>();
    private NativePlan() {}
    public static NativePlan inspect(Map<String, ElfFile.Result> libraries) throws IOException {
        NativePlan plan = new NativePlan();
        Map<String, String> owners = new TreeMap<>();
        for (String name : new TreeSet<>(libraries.keySet())) alias(owners, name, name);
        for (String name : new TreeSet<>(libraries.keySet())) {
            String soname = libraries.get(name).soname;
            if (soname != null && !soname.isEmpty()) alias(owners, soname, name);
        }
        Map<String, Integer> states = new HashMap<>();
        for (String name : new TreeSet<>(libraries.keySet())) plan.visit(name, libraries, owners, states);
        // A partial topological order must never look like an executable loading plan.
        if (!plan.cycles.isEmpty()) plan.order.clear();
        return plan;
    }
    private static void alias(Map<String, String> owners, String alias, String name) throws IOException {
        String previous = owners.putIfAbsent(alias, name);
        if (previous != null && !previous.equals(name))
            throw new IOException("Conflicting native SONAME: " + alias);
    }
    private void visit(String name, Map<String, ElfFile.Result> libs, Map<String, String> owners,
                       Map<String, Integer> states) {
        int state = states.getOrDefault(name, 0);
        if (state == 2) return;
        if (state == 1) { cycles.add(name); return; }
        states.put(name, 1);
        for (String dependency : libs.get(name).needed) {
            String owner = owners.get(dependency);
            if (owner == null) external.add(dependency); else visit(owner, libs, owners, states);
        }
        states.put(name, 2); order.add(name);
    }
}
