package com.halo.decomp;

import java.io.File;
import java.security.MessageDigest;
import java.io.FileInputStream;

/** Read-only CLI for validating private map repair candidates on Forge.
 * Uses the exact decompression/header code packaged into the Android APK.
 * Never writes, installs, or alters the source map. */
public final class XboxMapValidationCli {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
            "Usage: XboxMapValidationCli /path/candidate.map b40.map");
        File source = new File(args[0]);
        String name = args[1];
        long decoded = SidewinderRepair.validate(source, name);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream in = new FileInputStream(source)) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n > 0) digest.update(buffer, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest())
            hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        System.out.println("PASS_XBOX_MAP " + name +
            " source_bytes=" + source.length() +
            " decoded_bytes=" + decoded + " sha256=" + hex);
    }
}
