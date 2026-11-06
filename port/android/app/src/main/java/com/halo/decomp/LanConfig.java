package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Edits only the three System Link options, preserving the rest of config.toml. */
final class LanConfig {
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([^]\\r\\n]+)\\]\\s*(?:#.*)?$");
    private static final Pattern KEY = Pattern.compile("^\\s*(address|broadcast|online)\\s*=.*$");
    private static final long MAX_BYTES = 1024 * 1024;

    static final class Settings {
        String local = "";
        String peer = "";
        boolean lanOnly = true;
    }

    private LanConfig() { }

    private static boolean validAddress(String value) {
        if (value.isEmpty())
            return true;
        String[] fields = value.split("\\.", -1);
        if (fields.length != 4)
            return false;
        int first = -1;
        for (int i = 0; i < fields.length; i++) {
            String part = fields[i];
            if (part.length() < 1 || part.length() > 3)
                return false;
            for (int j = 0; j < part.length(); j++) {
                if (part.charAt(j) < '0' || part.charAt(j) > '9')
                    return false;
            }
            int number = Integer.parseInt(part);
            if (number > 255)
                return false;
            if (i == 0)
                first = number;
        }
        return first != 0 && first != 127 && first < 224 && !value.equals("255.255.255.255");
    }

    private static String[] lines(File file) throws IOException {
        if (!file.isFile())
            return new String[0];
        if (Files.size(file.toPath()) > MAX_BYTES)
            throw new IOException("The Halo configuration is unexpectedly large.");
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).toArray(new String[0]);
    }

    static Settings load(File file) throws IOException {
        Settings settings = new Settings();
        boolean network = false;
        for (String line : lines(file)) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                network = section.group(1).trim().equals("network");
                continue;
            }
            if (!network)
                continue;
            int equal = line.indexOf('=');
            if (equal < 0)
                continue;
            String key = line.substring(0, equal).trim();
            String value = line.substring(equal + 1).trim();
            if (value.startsWith("\"")) {
                int end = value.indexOf('"', 1);
                if (end >= 0)
                    value = value.substring(1, end);
            } else {
                int comment = value.indexOf('#');
                if (comment >= 0)
                    value = value.substring(0, comment).trim();
            }
            if (key.equals("address"))
                settings.local = validAddress(value) ? value : "";
            else if (key.equals("broadcast"))
                settings.peer = validAddress(value) ? value : "";
            else if (key.equals("online"))
                settings.lanOnly = !value.equalsIgnoreCase("true");
        }
        return settings;
    }

    private static String entry(String key, String local, String peer, boolean lanOnly) {
        if (key.equals("address"))
            return "address = \"" + local + "\"";
        if (key.equals("broadcast"))
            return "broadcast = \"" + peer + "\"";
        return "online = " + (!lanOnly);
    }

    private static void insertMissing(List<String> result, boolean[] found,
            String local, String peer, boolean lanOnly) {
        String[] keys = { "address", "broadcast", "online" };
        for (int i = 0; i < keys.length; i++) {
            if (!found[i])
                result.add(entry(keys[i], local, peer, lanOnly));
        }
    }

    static void save(File file, String local, String peer, boolean lanOnly) throws IOException {
        local = local.trim();
        peer = peer.trim();
        if (!validAddress(local) || !validAddress(peer))
            throw new IOException("Enter complete IPv4 addresses such as 10.18.179.225, or leave a field blank.");

        String[] original = lines(file);
        List<String> result = new ArrayList<>();
        boolean network = false;
        boolean seenNetwork = false;
        boolean[] found = new boolean[3];
        for (String line : original) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                if (network) {
                    insertMissing(result, found, local, peer, lanOnly);
                    network = false;
                }
                if (section.group(1).trim().equals("network")) {
                    if (seenNetwork)
                        throw new IOException("Duplicate [network] sections in config.toml.");
                    seenNetwork = network = true;
                }
                result.add(line);
                continue;
            }
            Matcher key = KEY.matcher(line);
            if (network && key.matches()) {
                String name = key.group(1);
                int index = name.equals("address") ? 0 : name.equals("broadcast") ? 1 : 2;
                if (found[index])
                    throw new IOException("Duplicate " + name + " setting in [network].");
                found[index] = true;
                result.add(entry(name, local, peer, lanOnly));
            } else {
                result.add(line);
            }
        }
        if (network)
            insertMissing(result, found, local, peer, lanOnly);
        if (!seenNetwork) {
            if (!result.isEmpty())
                result.add("");
            result.add("[network]");
            insertMissing(result, new boolean[3], local, peer, lanOnly);
        }
        File parent = file.getParentFile();
        if (parent == null || !parent.isDirectory())
            throw new IOException("Halo's data folder is unavailable.");
        File temporary = new File(parent, "config.toml.lan-tmp");
        try {
            Files.write(temporary.toPath(), (String.join("\n", result) + "\n").getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }
}
