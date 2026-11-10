package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Host's saved Commander Mode policy.
 *
 * This edits ONLY [commander] in config.toml and never touches network
 * addresses, bot counts, game data or player saves. A guest launch forces
 * commander.enabled=false locally; the native client independently ignores
 * all local settings and accepts only a reliable host policy.
 */
final class CommanderConfig {
    private static final long MAX_CONFIG_BYTES = 1024L * 1024L;
    private static final Pattern SECTION = Pattern.compile(
        "^\\s*\\[([^\\]\\r\\n]+)\\]\\s*(?:#.*)?$");
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*([^#\\r\\n]*?)(?:\\s*#.*)?$");
    static final String[] KEYS = {
        "enabled", "satellite", "uav", "spotting", "orders",
        "killstreaks", "orbital", "announcements"
    };

    static final class Options {
        boolean enabled;               // OFF by default: classic Halo is unchanged
        boolean satellite = true;      // commander-only scan
        boolean uav = true;            // localized shared scan
        boolean spotting = true;       // a shot/spot marks a target
        boolean orders = true;         // attack and defend objectives
        boolean killstreaks = true;    // human-only earned rewards
        boolean orbital = true;        // requires killstreaks
        boolean announcements = true;  // radio audio, with a cooldown

        boolean get(String key) {
            switch (key) {
                case "enabled": return enabled;
                case "satellite": return satellite;
                case "uav": return uav;
                case "spotting": return spotting;
                case "orders": return orders;
                case "killstreaks": return killstreaks;
                case "orbital": return orbital;
                case "announcements": return announcements;
                default: throw new IllegalArgumentException("Unknown Commander option");
            }
        }
        void set(String key, boolean value) {
            switch (key) {
                case "enabled": enabled = value; break;
                case "satellite": satellite = value; break;
                case "uav": uav = value; break;
                case "spotting": spotting = value; break;
                case "orders": orders = value; break;
                case "killstreaks": killstreaks = value; break;
                case "orbital": orbital = value; break;
                case "announcements": announcements = value; break;
                default: throw new IllegalArgumentException("Unknown Commander option");
            }
        }
    }

    private CommanderConfig() { }

    private static boolean known(String key) {
        for (String known : KEYS)
            if (known.equals(key)) return true;
        return false;
    }

    private static List<String> lines(File file) throws IOException {
        if (file == null || file.getParentFile() == null ||
            !file.getParentFile().isDirectory())
            throw new IOException("Halo config folder is unavailable.");
        Path path = file.toPath();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            return new ArrayList<>();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path))
            throw new IOException("Halo config is not a safe regular file.");
        if (Files.size(path) > MAX_CONFIG_BYTES)
            throw new IOException("Halo config is unexpectedly large.");
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    static Options read(File file) throws IOException {
        Options options = new Options();
        boolean inside = false, seenSection = false;
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        for (String line : lines(file)) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                inside = section.group(1).trim().equals("commander");
                if (inside) {
                    if (seenSection)
                        throw new IOException("Duplicate [commander] section.");
                    seenSection = true;
                }
                continue;
            }
            if (!inside) continue;
            Matcher m = ASSIGNMENT.matcher(line);
            if (!m.matches() || !known(m.group(1))) continue;
            String key = m.group(1), val = m.group(2).trim();
            if (seen.containsKey(key))
                throw new IOException("Duplicate commander." + key + ".");
            if (!val.equals("true") && !val.equals("false"))
                throw new IOException("Invalid boolean commander." + key + ".");
            options.set(key, val.equals("true"));
            seen.put(key, true);
        }
        return options;
    }

    private static void insertMissing(List<String> result, Options options,
            Map<String, Boolean> seen) {
        for (String key : KEYS)
            if (!seen.containsKey(key))
                result.add(key + " = " + options.get(key));
    }

    static void save(File file, Options options) throws IOException {
        if (options == null) throw new IOException("No Commander settings supplied.");
        List<String> old = lines(file);
        List<String> output = new ArrayList<>(old.size() + KEYS.length + 3);
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
        boolean inside = false, seenSection = false;
        for (String line : old) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                if (inside) insertMissing(output, options, seen);
                inside = section.group(1).trim().equals("commander");
                if (inside) {
                    if (seenSection)
                        throw new IOException("Duplicate [commander] section.");
                    seenSection = true;
                }
                output.add(line);
                continue;
            }
            if (inside) {
                Matcher m = ASSIGNMENT.matcher(line);
                if (m.matches() && known(m.group(1))) {
                    String key = m.group(1), val = m.group(2).trim();
                    if (seen.containsKey(key))
                        throw new IOException("Duplicate commander." + key + ".");
                    if (!val.equals("true") && !val.equals("false"))
                        throw new IOException("Invalid boolean commander." + key + ".");
                    seen.put(key, true);
                    output.add(key + " = " + options.get(key));
                    continue;
                }
            }
            output.add(line);
        }
        if (inside) insertMissing(output, options, seen);
        if (!seenSection) {
            if (!output.isEmpty()) output.add("");
            output.add("[commander]");
            insertMissing(output, options, seen);
        }

        byte[] data = (String.join("\n", output) + "\n")
            .getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_CONFIG_BYTES)
            throw new IOException("Commander settings exceed the config limit.");

        Path temp = Files.createTempFile(file.getParentFile().toPath(),
            "halo-commander-", ".pending");
        try {
            Files.write(temp, data);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void disable(File file) throws IOException {
        if (file == null) throw new IOException("Halo config is unavailable.");
        // Keep native defaults if the app has never created its config.
        if (!file.exists()) return;
        Options opts = read(file);
        if (!opts.enabled) return;
        opts.enabled = false;
        save(file, opts);
    }
}
