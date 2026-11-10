package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the actual native Android [bots] configuration, not CLI flags:
 * the Android engine reads config.toml when its fresh :game process starts.
 *
 * Never touches [network], [game], player settings or LAN's bot count.
 * Only keys explicitly managed by this class are changed. Writes are staged
 * and atomically renamed beside the existing config, preserving a working
 * file if a validation or I/O check fails.
 */
final class OfflineBotConfig {
    private static final long MAX_CONFIG_BYTES = 1024L * 1024L;
    private static final Pattern SECTION =
        Pattern.compile("^\\s*\\[([^\\]\\r\\n]+)\\]\\s*(?:#.*)?$");
    private static final Pattern KEY =
        Pattern.compile("^\\s*(count|skill|teams|vehicle_seats|objective_modes)\\s*=.*$");
    private static final String[] SKILLS =
        { "recruit", "easy", "normal", "heroic", "veteran", "legendary" };

    private OfflineBotConfig() { }

    private static boolean validSkill(String skill) {
        for (String allowed : SKILLS)
            if (allowed.equals(skill))
                return true;
        return false;
    }

    static void enable(File file, int count, String skill, String teams,
            boolean vehicleSeats, boolean objectiveModes) throws IOException {
        if (count < 1 || count > 127)
            throw new IOException("Offline bot count must be 1 through 127.");
        if (!validSkill(skill))
            throw new IOException("Invalid offline bot difficulty.");
        if (!"even".equals(teams) && !"against".equals(teams))
            throw new IOException("Offline teams must be even or against.");

        LinkedHashMap<String, String> changed = new LinkedHashMap<>();
        changed.put("count", Integer.toString(count));
        changed.put("skill", "\"" + skill + "\"");
        changed.put("teams", "\"" + teams + "\"");
        changed.put("vehicle_seats", Boolean.toString(vehicleSeats));
        changed.put("objective_modes", Boolean.toString(objectiveModes));
        update(file, changed);
    }

    /** Stop offline-only bots leaking into standard or LAN games. The LAN
     * launcher writes its own skill, vehicle and objective settings next.
     * Reset the offline team choice so LAN bots do not unexpectedly inherit
     * "against" from an earlier solo session. Network settings are untouched. */
    static void disable(File file) throws IOException {
        if (file == null)
            throw new IOException("Halo configuration is unavailable.");
        if (!file.exists())
            return; // native defaults: zero bots and balanced teams
        LinkedHashMap<String, String> changed = new LinkedHashMap<>();
        changed.put("count", "0");
        changed.put("teams", "\"even\"");
        changed.put("objective_modes", "false");
        update(file, changed);
    }

    private static void insertMissing(List<String> result,
            LinkedHashMap<String, String> changes, Map<String, Boolean> seen) {
        for (Map.Entry<String, String> item : changes.entrySet())
            if (!seen.containsKey(item.getKey()))
                result.add(item.getKey() + " = " + item.getValue());
    }

    private static void update(File file, LinkedHashMap<String, String> changes)
            throws IOException {
        if (file == null || file.getParentFile() == null ||
            !file.getParentFile().isDirectory())
            throw new IOException("Halo's configuration folder is unavailable.");

        Path originalPath = file.toPath();
        if (Files.exists(originalPath) &&
            (!Files.isRegularFile(originalPath) || Files.isSymbolicLink(originalPath)))
            throw new IOException("Halo's configuration is not a regular file.");
        if (Files.exists(originalPath) && Files.size(originalPath) > MAX_CONFIG_BYTES)
            throw new IOException("Halo's configuration is unexpectedly large.");

        List<String> lines = Files.exists(originalPath)
            ? Files.readAllLines(originalPath, StandardCharsets.UTF_8)
            : new ArrayList<>();
        List<String> result = new ArrayList<>(lines.size() + changes.size() + 3);
        boolean bots = false;
        boolean hadBots = false;
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();

        for (String line : lines) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                if (bots)
                    insertMissing(result, changes, seen);
                bots = "bots".equals(section.group(1).trim());
                if (bots) {
                    if (hadBots)
                        throw new IOException("Duplicate [bots] sections in config.toml.");
                    hadBots = true;
                }
                result.add(line);
                continue;
            }
            if (bots) {
                Matcher setting = KEY.matcher(line);
                if (setting.matches()) {
                    String key = setting.group(1);
                    if (seen.containsKey(key))
                        throw new IOException("Duplicate bots." + key + " in config.toml.");
                    seen.put(key, true);
                    String value = changes.get(key);
                    if (value != null) {
                        result.add(key + " = " + value);
                        continue;
                    }
                }
            }
            result.add(line);
        }
        if (bots)
            insertMissing(result, changes, seen);
        if (!hadBots) {
            if (!result.isEmpty())
                result.add("");
            result.add("[bots]");
            insertMissing(result, changes, seen);
        }
        byte[] encoded = (String.join("\n", result) + "\n")
            .getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_CONFIG_BYTES)
            throw new IOException("Halo's configuration would exceed its size limit.");

        Path temp = Files.createTempFile(file.getParentFile().toPath(),
            "halo-offline-bots-", ".tmp");
        try {
            Files.write(temp, encoded);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, originalPath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, originalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
