package com.balzikz.mathclient.foundation.core;

import java.io.File;

/** Desktop counterpart of the Android archive scanner, with the same records. */
public final class InventoryCli {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Usage: InventoryCli base.apk [split.apk ...]");
        for (String path : args) Inventory.scanApk(new File(path), (kind, fields) -> {
            fields.put("kind", kind); System.out.println(Inventory.json(fields));
        });
    }
}
