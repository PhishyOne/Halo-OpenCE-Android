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

            // The launcher must set one host's LAN bots without changing
            // the separate offline bots.count, difficulty, or network mode.
            File botsConfig = folder.resolve("lan-bots.toml").toFile();
            LanConfig.save(botsConfig, "", "", true, 1);
            check(LanConfig.load(botsConfig).systemLinkBotCount == 1,
                "one experimental host bot saved in existing TOML");
            check(Files.readString(botsConfig.toPath()).contains("[bots]"),
                "a missing [bots] section was added");
            Files.writeString(botsConfig.toPath(),
                "[bots]\ncount = 15\nskill = \"veteran\"\n"
                + "system_link_count = 3 # previous session\nteams = \"against\"\n"
                + "\n[network]\naddress = \"\"\nbroadcast = \"\"\nonline = false\n"
                + "\n[display]\nshow_fps = true\n", StandardCharsets.UTF_8);
            check(LanConfig.load(botsConfig).systemLinkBotCount == 3,
                "bot count read with inline comment");
            LanConfig.save(botsConfig, "", "", true, 4);
            String botSaved = Files.readString(botsConfig.toPath());
            check(botSaved.contains("system_link_count = 4"), "bot count was updated");
            check(botSaved.contains("count = 15"), "offline bot count was preserved");
            check(botSaved.contains("skill = \"veteran\""), "offline bot skill was preserved");
            check(botSaved.contains("teams = \"against\""), "offline team setup was preserved");
            check(botSaved.contains("show_fps = true"), "display config preserved");
            check(botSaved.split("system_link_count", -1).length == 2,
                "LAN bot count was not duplicated");
            LanConfig.save(botsConfig, "", "", true);
            check(Files.readString(botsConfig.toPath()).contains("system_link_count = 4"),
                "existing callers preserve experimental bot setting");
            // A joining phone or internet invite turns the host-only bots off.
            LanConfig.save(botsConfig, "", "10.28.111.42", true, 0);
            check(LanConfig.load(botsConfig).systemLinkBotCount == 0,
                "joining phone cleared host's prior LAN bot count");
            check(LanConfig.load(botsConfig).peer.equals("10.28.111.42"),
                "the joiner's target IP remains intact");
            LanConfig.save(botsConfig, "", "", false, 0);
            check(!LanConfig.load(botsConfig).lanOnly
                && LanConfig.load(botsConfig).systemLinkBotCount == 0,
                "internet mode disables host-only bots and LAN-only mode");
            for (int count : new int[] { -1, 17, 99 }) {
                String previous = Files.readString(botsConfig.toPath());
                boolean rejected = false;
                try {
                    LanConfig.save(botsConfig, "", "", true, count);
                } catch (IOException expected) {
                    rejected = true;
                }
                check(rejected && Files.readString(botsConfig.toPath()).equals(previous),
                    "out-of-range LAN count rejected without modifying data");
            }
            for (String bad : new String[] {
                    "[bots]\nsystem_link_count = 1\n[bots]\ncount = 4\n",
                    "[bots]\nsystem_link_count = 1\nsystem_link_count = 2\n"}) {
                Files.writeString(botsConfig.toPath(), bad, StandardCharsets.UTF_8);
                boolean rejected = false;
                try {
                    LanConfig.save(botsConfig, "", "", true, 1);
                } catch (IOException expected) {
                    rejected = true;
                }
                check(rejected && Files.readString(botsConfig.toPath()).equals(bad),
                    "duplicate bot config refused atomically");
            }

            // Higher LAN bot counts and host-selected difficulty must be
            // written atomically without changing offline count/teams.
            File skillConfig = folder.resolve("lan-skill.toml").toFile();
            Files.writeString(skillConfig.toPath(),
                "[bots]\ncount = 31\nteams = \"against\"\nskill = \"veteran\"\n"
                + "system_link_count = 4\n[network]\nonline = false\n",
                StandardCharsets.UTF_8);
            check(LanConfig.load(skillConfig).botSkill.equals("veteran"),
                "existing bot skill was loaded");
            LanConfig.save(skillConfig, "", "", true, 16, "legendary");
            String skillSaved = Files.readString(skillConfig.toPath());
            check(LanConfig.load(skillConfig).systemLinkBotCount == 16 &&
                LanConfig.load(skillConfig).botSkill.equals("legendary"),
                "16 LAN bots and legendary skill were saved");
            check(skillSaved.contains("count = 31") && skillSaved.contains("teams = \"against\""),
                "offline count and team option preserved");
            LanConfig.save(skillConfig, "", "", true, 8, "recruit");
            check(LanConfig.load(skillConfig).systemLinkBotCount == 8 &&
                LanConfig.load(skillConfig).botSkill.equals("recruit"),
                "lower LAN count and recruit skill roundtrip");
            LanConfig.save(skillConfig, "", "", true, 0);
            check(LanConfig.load(skillConfig).botSkill.equals("recruit"),
                "legacy LAN save must not change difficulty");
            LanConfig.save(skillConfig, "", "", true, 12, "heroic");
            check(LanConfig.load(skillConfig).systemLinkBotCount == 12 &&
                LanConfig.load(skillConfig).botSkill.equals("heroic"),
                "12 LAN bots with heroic difficulty");
            for (String skill : new String[] { "legendary\\n[network]", "Impossible", "easy\"bad" }) {
                String before = Files.readString(skillConfig.toPath());
                boolean rejected = false;
                try {
                    LanConfig.save(skillConfig, "", "", true, 4, skill);
                } catch (IOException expected) {
                    rejected = true;
                }
                check(rejected && Files.readString(skillConfig.toPath()).equals(before),
                    "bad LAN bot skill rejected without changing config");
            }
            String duplicateSkills =
                "[bots]\nskill = \"normal\"\nskill = \"legendary\"\n";
            Files.writeString(skillConfig.toPath(), duplicateSkills, StandardCharsets.UTF_8);
            boolean duplicateSkillRejected = false;
            try {
                LanConfig.save(skillConfig, "", "", true, 2, "veteran");
            } catch (IOException expected) {
                duplicateSkillRejected = true;
            }
            check(duplicateSkillRejected && Files.readString(skillConfig.toPath()).equals(duplicateSkills),
                "duplicate skill refused atomically");

            System.out.println("LanConfigSmokeTest PASS");
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(folder)) {
                files.sorted(Comparator.reverseOrder()).forEach(item -> item.toFile().delete());
            }
        }
    }
}
