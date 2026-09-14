package com.balzikz.mathclient.foundation.contract;

import com.balzikz.mathclient.foundation.core.Inventory;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ContractCli {
    private ContractCli() {}
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Pass base APK and optional split APK paths");
        List<File> apks = new ArrayList<>(); for (String arg : args) apks.add(new File(arg));
        Path scratch = Files.createTempDirectory("math-contract-cli-");
        try {
            ContractScanner.scan(apks, Arrays.asList("arm64-v8a", "x86_64", "armeabi-v7a", "x86"), null,
                    scratch.toFile(), Collections.singleton(DexContracts.MAIN), (kind, fields) -> {
                        Map<String, Object> record = Inventory.fields("kind", kind); record.putAll(fields);
                        System.out.println(Inventory.json(record));
                    }, message -> System.err.println(message));
        } finally { Files.delete(scratch); }
    }
}
