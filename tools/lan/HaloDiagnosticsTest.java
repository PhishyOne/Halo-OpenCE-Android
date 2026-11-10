package com.halo.decomp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class HaloDiagnosticsTest {
    private static int passed;
    private static void check(boolean condition,String message) {
        passed++;
        if (!condition) throw new AssertionError(message);
    }
    private static void clean(Path dir) throws Exception {
        try(java.util.stream.Stream<Path> walk=Files.walk(dir)){
            walk.sorted(Comparator.reverseOrder()).forEach(p->{
                try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}
            });
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("halo-diagnostic-test");
        try {
            Path maps=Files.createDirectory(root.resolve("maps"));
            Path custom=Files.createDirectory(root.resolve("custom_maps"));
            Files.write(maps.resolve("sidewinder.map"),new byte[17]);
            Files.write(custom.resolve("gephyrophobia.map"),new byte[43]);
            Files.writeString(root.resolve("config.toml"),
                "[network]\npassword=\"SECRET_DONT_EXPORT\"\n");
            Files.writeString(root.resolve("debug_previous.txt"),
                "co-op: missing object on client at 192.168.1.22\n" +
                "user password=PASSWORD_DONT_EXPORT co-op: secret\n" +
                "ordinary chat message that should not be in report\n" +
                "distributed: user@example.org error 123\n");
            StringBuilder recent=new StringBuilder();
            for(int i=0;i<225;i++)
                recent.append(i).append(" co-op: client spectating while host alive\n");
            Files.writeString(root.resolve("debug.txt"),recent.toString());

            ByteArrayOutputStream out=new ByteArrayOutputStream();
            HaloDiagnostics.write(root.toFile(),out);
            String report=out.toString(StandardCharsets.UTF_8);
            check(report.contains("sidewinder.map"),"Missing Xbox map inventory");
            check(report.contains("gephyrophobia.map"),"Missing custom map inventory");
            check(report.contains("co-op: missing object"),"Missing relevant older co-op log");
            check(report.contains("[IP]"),"IP address not redacted");
            check(report.contains("[email]"),"Email not redacted");
            check(!report.contains("192.168.1.22"),"Private IP leaked");
            check(!report.contains("user@example.org"),"Email leaked");
            check(!report.contains("PASSWORD_DONT_EXPORT"),"Sensitive log line not filtered");
            check(!report.contains("SECRET_DONT_EXPORT"),"config.toml content leaked");
            check(!report.contains("ordinary chat message"),"Irrelevant chat leaked");
            check(report.length() < 128*1024,"Report exceeds size cap");
            check(report.contains("Check it for private information"),"Missing privacy review warning");
            check(report.split("spectating while host alive",-1).length-1<=160,
                "Too many repeated co-op log lines");

            // Never touch or rewrite input game data.
            check(Files.size(maps.resolve("sidewinder.map"))==17,"Altered Xbox map");
            check(Files.size(custom.resolve("gephyrophobia.map"))==43,"Altered CE map");
            System.out.println("HaloDiagnosticsTest PASS (" + passed + " checks)");
        } finally {
            clean(root);
        }
    }
}
