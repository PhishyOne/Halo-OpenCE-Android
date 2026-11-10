package com.halo.decomp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class OfflineBotConfigTest {
    private static int passed;

    private static void check(boolean good, String what) {
        passed++;
        if (!good) throw new AssertionError(what);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void rejected(Checked op, String message) throws Exception {
        try {
            op.apply();
        } catch (IOException error) {
            check(error.getMessage().contains(message),
                "Wrong failure reason: " + error.getMessage());
            return;
        }
        throw new AssertionError("Expected IOException containing " + message);
    }

    private interface Checked {
        void apply() throws Exception;
    }

    private static void clean(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.walk(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(file -> {
                try { Files.delete(file); }
                catch (IOException error) { throw new RuntimeException(error); }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("halo-offline-objectives-test");
        Path config = root.resolve("config.toml");
        try {
            // Game config is created before native engine reads it.
            OfflineBotConfig.enable(config.toFile(), 8, "heroic", "against", true, true);
            String fresh = read(config);
            check(fresh.contains("[bots]\n"), "bots section not created");
            check(fresh.contains("count = 8\n"), "offline bot count incorrect");
            check(fresh.contains("skill = \"heroic\"\n"), "difficulty incorrect");
            check(fresh.contains("teams = \"against\"\n"), "team assignment incorrect");
            check(fresh.contains("vehicle_seats = true\n"), "vehicle preference missing");
            check(fresh.contains("objective_modes = true\n"), "CTF and King objectives disabled");
            check(fresh.indexOf("[bots]") == fresh.lastIndexOf("[bots]"),
                "Duplicate bots section created");

            OfflineBotConfig.disable(config.toFile());
            String disabled = read(config);
            check(disabled.contains("count = 0\n"), "normal Halo did not disable bots");
            check(disabled.contains("objective_modes = false"),
                "disabling offline mode retained objective bot behavior");
            check(disabled.contains("teams = \"even\""),
                "offline team preference leaked into standard or LAN mode");

            // Never overwrite network address, LAN bot settings, or custom
            // settings to enable objective modes offline.
            String original = """
                # user-provided comment
                [network]
                address = "10.10.1.2"
                broadcast = "10.10.1.9"
                online = false

                [bots]
                count = 12 # old
                system_link_count = 4
                skill = "normal"
                teams = "even"
                vehicle_seats = false
                objective_modes = false
                extra_bot_option = "preserved"

                [display]
                resolution_scaling = "original"
                """;
            Files.writeString(config, original);
            OfflineBotConfig.enable(config.toFile(), 6, "normal", "even", false, true);
            String updated = read(config);
            check(updated.contains("address = \"10.10.1.2\""),
                "changed LAN host address");
            check(updated.contains("broadcast = \"10.10.1.9\""),
                "changed LAN peer address");
            check(updated.contains("online = false"), "changed LAN-only setting");
            check(updated.contains("system_link_count = 4"), "changed LAN bot count");
            check(updated.contains("extra_bot_option = \"preserved\""),
                "lost unrelated bot config key");
            check(updated.contains("[display]\nresolution_scaling = \"original\""),
                "lost display settings");
            check(updated.contains("count = 6\n"), "new bot count missing");
            check(updated.contains("objective_modes = true\n"),
                "objective bot setting was not enabled");
            check(updated.contains("teams = \"even\"\n"), "team assignment wasn't applied");

            // Switching to LAN disables only offline count. LAN itself still
            // uses [bots] system_link_count and its own objective preference.
            OfflineBotConfig.disable(config.toFile());
            String lan = read(config);
            check(lan.contains("count = 0\n"), "LAN launch left offline bots enabled");
            check(lan.contains("system_link_count = 4"),
                "LAN count changed by offline-disable");
            check(lan.contains("objective_modes = false"),
                "LAN objective setting should be explicitly restored by LAN setup");
            check(lan.contains("address = \"10.10.1.2\""),
                "LAN IP rewritten by offline-disable");

            // Updating a config with a [bots] section lacking some keys
            // writes only missing ones, without moving other sections.
            Files.writeString(config, """
                [network]
                address = "10.0.0.9"
                [bots]
                count = 2
                system_link_count = 16
                [display]
                show_fps = true
                """);
            OfflineBotConfig.enable(config.toFile(), 127, "legendary", "against", true, true);
            String highest = read(config);
            check(highest.contains("count = 127"), "127 bot maximum rejected");
            check(highest.contains("system_link_count = 16"),
                "LAN count deleted when adding missing settings");
            check(highest.contains("skill = \"legendary\"\n"),
                "missing skill setting not added");
            check(highest.contains("teams = \"against\"\n"),
                "missing team setting not added");
            check(highest.contains("[display]\nshow_fps = true"),
                "trailing non-bot section changed");

            String before = read(config);
            rejected(() -> OfflineBotConfig.enable(config.toFile(), 128,
                "normal", "even", false, true), "1 through 127");
            rejected(() -> OfflineBotConfig.enable(config.toFile(), 8,
                "not-a-skill", "even", false, true), "difficulty");
            rejected(() -> OfflineBotConfig.enable(config.toFile(), 8,
                "normal", "wrong", false, true), "even or against");
            check(read(config).equals(before), "Invalid inputs modified file");

            Files.writeString(config, """
                [bots]
                count = 2
                [network]
                address = ""
                [bots]
                count = 3
                """);
            String duplicateSections = read(config);
            rejected(() -> OfflineBotConfig.enable(config.toFile(), 4,
                "normal", "even", false, true), "Duplicate [bots]");
            check(read(config).equals(duplicateSections),
                "Duplicate section error modified file");

            Files.writeString(config, "[bots]\ncount = 2\ncount = 3\n");
            String duplicateCount = read(config);
            rejected(() -> OfflineBotConfig.disable(config.toFile()), "Duplicate bots.count");
            check(read(config).equals(duplicateCount),
                "Duplicate key error modified original file");

            Files.delete(config);
            OfflineBotConfig.disable(config.toFile());
            check(!Files.exists(config),
                "Disabled mode created unnecessary configuration file");

            check(Files.list(root).noneMatch(
                file -> file.getFileName().toString().endsWith(".tmp")),
                "Temporary config file left behind after operations");

            System.out.println("OfflineBotConfigTest PASS (" + passed + " checks)");
        } finally {
            clean(root);
        }
    }
}
