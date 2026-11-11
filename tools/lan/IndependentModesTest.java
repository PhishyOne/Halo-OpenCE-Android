package com.halo.decomp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

/** Both native feature masters live in one config.toml but are independent. */
public final class IndependentModesTest {
    private static int checks;
    private static void check(boolean good, String reason) {
        checks++;
        if (!good) throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("halo-independent-modes");
        Path file = root.resolve("config.toml");
        try {
            CommanderConfig.Options commander = new CommanderConfig.Options();
            KillstreakConfig.Options streaks = new KillstreakConfig.Options();
            CommanderConfig.save(file.toFile(), commander);
            KillstreakConfig.save(file.toFile(), streaks);
            check(!CommanderConfig.read(file.toFile()).enabled,
                "Commander must be off by default");
            check(!KillstreakConfig.read(file.toFile()).enabled,
                "Killstreaks must be off by default");

            // Killstreaks ON while Commander OFF: no hidden dependency.
            streaks.enabled = true;
            KillstreakConfig.save(file.toFile(), streaks);
            check(KillstreakConfig.read(file.toFile()).enabled,
                "Personal rewards cannot run by themselves");
            check(!CommanderConfig.read(file.toFile()).enabled,
                "Enabling individual killstreaks accidentally turned on Commander");

            // Commander ON while killstreak OFF: no hidden dependency.
            KillstreakConfig.disable(file.toFile());
            commander.enabled = true;
            CommanderConfig.save(file.toFile(), commander);
            check(CommanderConfig.read(file.toFile()).enabled,
                "Commander cannot run independently");
            check(!KillstreakConfig.read(file.toFile()).enabled,
                "Enabling Commander accidentally granted individual rewards");

            // Both ON: both still present and isolated from unrelated config.
            Files.writeString(file, "\n[network]\naddress = \"10.1.2.3\"\n" +
                "[bots]\ncount = 64\n", StandardOpenOption.APPEND);
            KillstreakConfig.save(file.toFile(), streaks);
            check(CommanderConfig.read(file.toFile()).enabled,
                "Killstreak setting erased a valid Commander master");
            check(KillstreakConfig.read(file.toFile()).enabled,
                "Enabling both did not persist");
            check(Files.readString(file).contains("count = 64"),
                "Independent switches changed bot count");

            // Disable only Commander, then only Killstreak: each master
            // remains independent and preserves selected child options.
            CommanderConfig.disable(file.toFile());
            check(!CommanderConfig.read(file.toFile()).enabled,
                "Commander did not turn off");
            check(KillstreakConfig.read(file.toFile()).enabled,
                "Turning off Commander disabled personal rewards");
            CommanderConfig.save(file.toFile(), commander);
            KillstreakConfig.disable(file.toFile());
            check(CommanderConfig.read(file.toFile()).enabled,
                "Turning off Killstreaks disabled tactical Commander");
            check(!KillstreakConfig.read(file.toFile()).enabled,
                "Killstreak master did not turn off");
            check(KillstreakConfig.read(file.toFile()).orbital,
                "Disabling master erased orbital preference");

            check(Files.readString(file).contains("address = \"10.1.2.3\""),
                "Saved config lost LAN settings");
            check(Files.readString(file).contains("[commander]"),
                "Commander section missing");
            check(Files.readString(file).contains("[killstreak]"),
                "Killstreak section missing");
            System.out.println("IndependentModesTest PASS (" + checks + " checks)");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); }
                    catch (Exception ex) { throw new RuntimeException(ex); }
                });
            }
        }
    }
}
