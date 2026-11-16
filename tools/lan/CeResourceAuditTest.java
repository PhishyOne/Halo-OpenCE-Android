package com.halo.decomp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Pure JDK tests for the exact production CE external resource audit. */
public final class CeResourceAuditTest {
    private static int checks;

    private static void check(boolean passed, String why) {
        checks++;
        if (!passed) throw new AssertionError(why);
    }

    private static void put32(byte[] d, int at, long value) {
        for (int i=0;i<4;i++) d[at+i]=(byte)(value >>> (i*8));
    }

    private static long fourcc(String name) {
        long value=0;
        for (int i=0;i<4;i++) value=(value<<8) | name.charAt(i);
        return value;
    }

    private static byte[] map() {
        String[] paths={"levels\\beach\\rock","sound\\ambient","ui\\button"};
        String[] groups={"bitm","snd!","ustr"};
        int count=paths.length;
        int namesStart=0x28+count*0x20;
        int namesLen=0;
        for (String path:paths) namesLen+=path.length()+1;
        int tagsLen=namesStart+namesLen;
        byte[] map=new byte[0x800+tagsLen];
        put32(map,0,fourcc("head"));
        put32(map,4,609);
        put32(map,8,map.length);
        put32(map,0x10,0x800);
        put32(map,0x14,tagsLen);
        System.arraycopy("beach_assault".getBytes(StandardCharsets.US_ASCII),
            0,map,0x20,"beach_assault".length());
        put32(map,0x7fc,fourcc("foot"));
        put32(map,0x800,0x40440028L);
        put32(map,0x800+0x0c,count);
        put32(map,0x800+0x24,fourcc("tags"));
        int nameAt=namesStart;
        for (int i=0;i<count;i++) {
            int tagAt=0x800+0x28+i*0x20;
            put32(map,tagAt,fourcc(groups[i]));
            put32(map,tagAt+0x0c,0xE1000000L+i);
            put32(map,tagAt+0x10,0x40440000L+nameAt);
            put32(map,tagAt+0x14,0); // first item in bitmap/loc; sound matches by name
            put32(map,tagAt+0x18,1); // stored in external resource map
            byte[] bytes=paths[i].getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(bytes,0,map,0x800+nameAt,bytes.length);
            nameAt+=bytes.length+1;
        }
        return map;
    }

    private static byte[] resource(int type, String name) {
        byte[] path=name.getBytes(StandardCharsets.US_ASCII);
        int namesStart=20, indexStart=namesStart+path.length+1;
        byte[] d=new byte[indexStart+12];
        put32(d,0,type);
        put32(d,4,namesStart);
        put32(d,8,indexStart);
        put32(d,12,1);
        System.arraycopy(path,0,d,namesStart,path.length);
        put32(d,indexStart,0); // offset in names
        put32(d,indexStart+4,4); // resource payload bytes
        put32(d,indexStart+8,16); // resource payload offset
        return d;
    }

    private static void clean(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> paths=Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException problem) { throw new RuntimeException(problem); }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("halo-ce-audit-");
        try {
            Path custom=Files.createDirectory(root.resolve("custom_maps"));
            Path beach=custom.resolve("beach_assault.map");
            byte[] bytes=map();
            Files.write(beach,bytes);
            Files.write(custom.resolve("bitmaps.map"),
                resource(1,"levels\\beach\\rock"));
            Files.write(custom.resolve("sounds.map"),
                resource(2,"sound\\ambient"));
            Files.write(custom.resolve("loc.map"),
                resource(3,"ui\\button"));
            String clean=CeResourceAudit.beachReport(root.toFile());
            for (String expected:new String[]{
                "Beach Assault: CE v609", "3 tags",
                "bitmaps.map: 1/1 references matched",
                "sounds.map: 1/1 references matched",
                "loc.map: 1/1 references matched",
                "External resource references match installed files",
                "does not prove the map renders correctly",
            }) check(clean.contains(expected),"Missing from valid report: "+expected);

            Files.write(custom.resolve("bitmaps.map"),resource(1,"wrong\\bitmap"));
            String badBitmap=CeResourceAudit.beachReport(root.toFile());
            check(badBitmap.contains("bitmaps.map: 0/1"),"Failed bitmap index-name check");
            check(badBitmap.contains("missing or incompatible"),"Wrong bitmap disposition");
            Files.write(custom.resolve("bitmaps.map"),
                resource(1,"levels\\beach\\rock"));

            Files.delete(custom.resolve("sounds.map"));
            String missingSound=CeResourceAudit.beachReport(root.toFile());
            check(missingSound.contains("sounds.map: sounds.map is missing or invalid"),
                "Failed missing sound resource diagnosis");
            Files.write(custom.resolve("sounds.map"),
                resource(2,"sound\\ambient"));

            Files.write(custom.resolve("loc.map"),resource(3,"wrong\\text"));
            String wrongLoc=CeResourceAudit.beachReport(root.toFile());
            check(wrongLoc.contains("loc.map: 0/1"),"Failed locale index-name check");
            Files.write(custom.resolve("loc.map"),resource(3,"ui\\button"));
            byte[] brokenPayload=resource(1,"levels\\beach\\rock");
            int resourceItemAt=brokenPayload.length-12;
            put32(brokenPayload,resourceItemAt+8,0x7fffffffL);
            Files.write(custom.resolve("bitmaps.map"),brokenPayload);
            check(CeResourceAudit.beachReport(root.toFile())
                .contains("invalid resource item"),"Out-of-bounds payload accepted");
            Files.write(custom.resolve("bitmaps.map"),
                resource(1,"levels\\beach\\rock"));

            byte[] bad=bytes.clone();
            bad[4]=0;
            Files.write(beach,bad);
            check(CeResourceAudit.beachReport(root.toFile())
                .contains("not a valid CE v609 cache"),"Bad version accepted");
            Files.write(beach,bytes);

            byte[] badIndex=bytes.clone();
            put32(badIndex,0x800,0x99999999L);
            Files.write(beach,badIndex);
            check(CeResourceAudit.beachReport(root.toFile())
                .contains("Map tag index is invalid"),"Bad tag table pointer accepted");
            Files.write(beach,bytes);

            check(Arrays.equals(bytes,Files.readAllBytes(beach)),
                "Audit altered the installed map");
            check(CeResourceAudit.beachReport(root.toFile()).equals(clean),
                "Audit modified the installed resource maps");

            Files.delete(beach);
            check(CeResourceAudit.beachReport(root.toFile())
                .contains("beach_assault.map is missing or invalid"),
                "Missing map treated as valid");

            System.out.println("CeResourceAuditTest PASS ("+checks+" checks)");
        } finally { clean(root); }

        if (args.length==1)
            System.out.println("EXTERNAL MAP AUDIT: "+
                CeResourceAudit.beachReport(new java.io.File(args[0])));
    }
}
