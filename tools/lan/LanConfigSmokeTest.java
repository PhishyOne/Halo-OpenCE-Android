package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Asset-free LAN config test, runnable with a plain JDK (no Android needed). */
public final class LanConfigSmokeTest {
    private static void check(boolean condition, String reason) {
        if (!condition)
            throw new AssertionError(reason);
    }

    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("opence-lan-settings");
        try {
            File config = folder.resolve("config.toml").toFile();
            LanConfig.save(config, "10.18.179.126", "10.18.179.225", true);
            LanConfig.Settings initial = LanConfig.load(config);
            check(initial.lanOnly, "LAN-only default");
            check(initial.local.equals("10.18.179.126"), "local IP");
            check(initial.peer.equals("10.18.179.225"), "peer IP");
            String first = Files.readString(config.toPath(), StandardCharsets.UTF_8);
            check(first.contains("[network]"), "network section must exist");

            Files.writeString(config.toPath(),
                "# Halo file\n[display]\nshow_fps = true\n\n[network]\n"
                + "address = \"\"\nbroadcast = \"\"\nonline = true\n"
                + "client_timeout_seconds = 15\n\n[audio]\nchannels = \"stereo\"\n",
                StandardCharsets.UTF_8);
            LanConfig.save(config, "", "192.168.43.1", true);
            String after = Files.readString(config.toPath(), StandardCharsets.UTF_8);
            check(after.contains("show_fps = true"), "display settings retained");
            check(after.contains("client_timeout_seconds = 15"), "unrelated network settings retained");
            check(after.contains("[audio]"), "following sections retained");
            check(after.contains("online = false"), "internet play disabled");
            check(LanConfig.load(config).peer.equals("192.168.43.1"), "peer readback");
            LanConfig.save(config, "", "", false);
            check(Files.readString(config.toPath()).contains("online = true"),
                "internet-mode setting restored");

            boolean invalidCaught = false;
            try {
                LanConfig.save(config, "", "10.18.179.999", true);
            } catch (IOException expected) {
                invalidCaught = true;
            }
            check(invalidCaught, "invalid IPv4 refused");
            invalidCaught = false;
            try {
                LanConfig.save(config, "", "10.18.179.225\n[other]", true);
            } catch (IOException expected) {
                invalidCaught = true;
            }
            check(invalidCaught, "TOML injection refused");

            Files.writeString(config.toPath(),
                "[network]\naddress = \"\"\n[network]\nbroadcast = \"\"\n",
                StandardCharsets.UTF_8);
            invalidCaught = false;
            try {
                LanConfig.save(config, "", "", true);
            } catch (IOException expected) {
                invalidCaught = true;
            }
            check(invalidCaught, "duplicate sections refused");
            // All joining phones point to one host. The host does not list guests.
            check(LanConfig.peerForRole(true, "10.28.111.99", true).isEmpty(),
                "host clears an old peer setting");
            String guest2 = LanConfig.peerForRole(false, "10.28.111.42", true);
            String guest3 = LanConfig.peerForRole(false, " 10.28.111.42 ", true);
            check(guest2.equals(guest3), "multiple joiners share same host address");
            check(LanConfig.peerForRole(false, "10.28.111.42", false).isEmpty(),
                "internet mode clears LAN target");
            boolean missingHostCaught = false;
            try {
                LanConfig.peerForRole(false, "", true);
            } catch (IOException expected) {
                missingHostCaught = true;
            }
            check(missingHostCaught, "LAN join requires game host IP");
            boolean badHostCaught = false;
            try {
                LanConfig.peerForRole(false, "192.168.0.1\n[network]", true);
            } catch (IOException expected) {
                badHostCaught = true;
            }
            check(badHostCaught, "injected host IP refused");
            File multiConfig = folder.resolve("multi.toml").toFile();
            LanConfig.save(multiConfig, "", guest2, true);
            check(LanConfig.load(multiConfig).peer.equals("10.28.111.42"),
                "joiner host address roundtrip");
            LanConfig.save(multiConfig, "", LanConfig.peerForRole(true, guest2, true), true);
            check(LanConfig.load(multiConfig).peer.isEmpty(),
                "changing to host clears the client-only discovery target");

            System.out.println("LanConfigSmokeTest PASS");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(folder)) {
                files.sorted(Comparator.reverseOrder()).forEach(item -> item.toFile().delete());
            }
        }
    }
}
