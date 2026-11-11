package com.halo.decomp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.io.IOException;

public final class CommanderConfigTest {
    private static int checks;
    private static void check(boolean pass, String message) {
        checks++;
        if (!pass) throw new AssertionError(message);
    }
    interface Operation { void run() throws Exception; }
    private static void rejects(Operation operation, String reason) throws Exception {
        try { operation.run(); }
        catch (IOException ex) {
            check(ex.getMessage().contains(reason),
                "Rejected for a different reason: " + ex.getMessage());
            return;
        }
        throw new AssertionError("Expected refusal: " + reason);
    }
    private static String text(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
    private static void cleanup(Path root) throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.walk(root)) {
            entries.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException ex) { throw new RuntimeException(ex); }
            });
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("halo-commander-opts");
        Path path=root.resolve("config.toml");
        try {
            CommanderConfig.Options defaults=CommanderConfig.read(path.toFile());
            check(!defaults.enabled, "Commander Mode must default OFF");
            for (String key: CommanderConfig.KEYS)
                if (!"enabled".equals(key))
                    check(defaults.get(key), "Subfeature default should be ON: "+key);
            check(!Files.exists(path), "Reading defaults must not create a file");
            CommanderConfig.disable(path.toFile());
            check(!Files.exists(path), "Disabling absent config should be a no-op");
            String original=
                "# User comment preserved\n"+
                "[network]\naddress = \"10.0.0.5\"\nonline = false\n"+
                "[bots]\ncount = 64\nsystem_link_count = 4\n"+
                "[display]\nshow_fps = true\n";
            Files.writeString(path,original);
            CommanderConfig.Options opts=new CommanderConfig.Options();
            opts.enabled=true; opts.uav=false; opts.orders=false;
            CommanderConfig.save(path.toFile(),opts);
            String saved=text(path);
            check(saved.contains("address = \"10.0.0.5\""),"Network settings changed");
            check(saved.contains("online = false"),"LAN mode changed");
            check(saved.contains("count = 64"),"Offline bot count changed");
            check(saved.contains("system_link_count = 4"),"LAN bot count changed");
            check(saved.contains("show_fps = true"),"Display changed");
            check(saved.contains("# User comment preserved"),"User comment lost");
            check(saved.contains("[commander]\n"),"Commander section not added");
            check(saved.contains("enabled = true\n"),"Master enabled missing");
            check(saved.contains("uav = false\n"),"UAV switch not saved");
            check(saved.contains("orders = false\n"),"Commander orders switch not saved");
            check(saved.contains("announcements = true\n"),"Radio switch missing");
            CommanderConfig.Options loaded=CommanderConfig.read(path.toFile());
            check(loaded.enabled && !loaded.uav && !loaded.orders,
                "Reload lost feature preferences");

            // Disable the master without erasing selected subfeature flags.
            CommanderConfig.disable(path.toFile());
            loaded=CommanderConfig.read(path.toFile());
            check(!loaded.enabled && !loaded.uav && !loaded.orders,
                "Master disable changed saved subfeature choices");
            check(text(path).contains("count = 64\n"),"Master disable damaged bot count");
            // Existing commander section: replace keys in place, preserve
            // unrecognized extensions and insert only missing known keys.
            Files.writeString(path, "[network]\nonline = false\n\n" +
                "[commander]\n# feature comment\n" +
                "enabled = false # old choice\n" +
                "orbital = true\ncustom_theme = \"2142\"\n" +
                "[display]\nshow_fps = true\n");
            opts.enabled=true;opts.uav=true;opts.orders=true;
            CommanderConfig.save(path.toFile(),opts);
            saved=text(path);
            check(saved.contains("enabled = true\n"),"Existing master setting unchanged");
            check(saved.contains("custom_theme = \"2142\""),"Unknown extension erased");
            check(saved.contains("# feature comment"),"Feature comment erased");
            check(saved.contains("[display]\nshow_fps = true"),"Subsequent section corrupted");
            check(saved.indexOf("[commander]")==saved.lastIndexOf("[commander]"),
                "Duplicate commander section created");
            for (String key: CommanderConfig.KEYS)
                check(saved.contains(key+" = "), "Missing Commander setting "+key);

            String duplicate="[commander]\nenabled = true\n"+
                "[commander]\nenabled = false\n";
            Files.writeString(path,duplicate);
            rejects(() -> CommanderConfig.save(path.toFile(),opts),
                "Duplicate [commander]");
            check(text(path).equals(duplicate),"Failure overwrote malformed file");

            String duplicateKey="[commander]\norders = true\norders = false\n";
            Files.writeString(path,duplicateKey);
            rejects(() -> CommanderConfig.save(path.toFile(),opts),
                "Duplicate commander.orders");
            check(text(path).equals(duplicateKey),"Duplicate key refusal changed file");

            Files.writeString(path,"[commander]\nuav = perhaps\n");
            rejects(() -> CommanderConfig.read(path.toFile()),"Invalid boolean");
            rejects(() -> CommanderConfig.disable(path.toFile()),"Invalid boolean");

            Files.delete(path);
            Files.createSymbolicLink(path,root.resolve("nonexistent"));
            rejects(() -> CommanderConfig.save(path.toFile(),opts),
                "not a safe regular file");
            Files.delete(path);
            Files.write(path,new byte[1024*1024+1]);
            rejects(() -> CommanderConfig.save(path.toFile(),opts),
                "unexpectedly large");

            try (java.util.stream.Stream<Path> files=Files.list(root)) {
                check(files.noneMatch(p -> p.toString().endsWith(".pending")),
                    "Temporary config left after failure");
            }
            System.out.println("CommanderConfigTest PASS ("+checks+" checks)");
        } finally { cleanup(root); }
    }
}
