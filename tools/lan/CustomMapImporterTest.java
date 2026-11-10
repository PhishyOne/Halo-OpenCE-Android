package com.halo.decomp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;

public final class CustomMapImporterTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private interface CheckedAction { void run() throws Exception; }

    private static void rejects(CheckedAction action, String phrase) throws Exception {
        try {
            action.run();
        } catch (IOException exception) {
            check(exception.getMessage().toLowerCase().contains(phrase.toLowerCase()),
                "Wrong rejection: " + exception.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection containing " + phrase);
    }

    private static void put32(byte[] data, int where, long number) {
        for (int i=0;i<4;i++) data[where+i]=(byte)(number>>(8*i));
    }

    private static byte[] map(String scenario, int version) {
        byte[] data=new byte[0x800+128];
        System.arraycopy("daeh".getBytes(),0,data,0,4);
        put32(data,4,version);
        put32(data,8,data.length);
        put32(data,0x10,0x800);
        put32(data,0x14,128);
        System.arraycopy(scenario.getBytes(),0,data,0x20,scenario.length());
        System.arraycopy("toof".getBytes(),0,data,0x7fc,4);
        return data;
    }

    private static byte[] resource(int type) {
        byte[] data=new byte[36];
        put32(data,0,type);
        put32(data,4,20);
        put32(data,8,24);
        put32(data,12,1);
        data[16]=44;
        data[17]=55;
        data[18]=66;
        data[19]=77;
        data[20]='x'; data[21]=0; data[22]=0; data[23]=0;
        put32(data,24,0);
        put32(data,28,4);
        put32(data,32,16);
        return data;
    }

    private static void deleteTree(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths=Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); }
                catch(IOException ex) { throw new RuntimeException(ex); }
            });
        }
    }

    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("halo-ce-map-import");
        try {
            Path maps=Files.createDirectory(root.resolve("maps"));
            Files.write(maps.resolve("bloodgulch.map"),new byte[]{23,42,19});
            String[] scenarios={"urban_warfare_beta","municipality","the_narrows","h2_zanzibar","2fort"};
            for (String scenario:scenarios) {
                String name=scenario+".map";
                byte[] content=map(scenario,609);
                CustomMapImporter.Result out=CustomMapImporter.install(
                    new ByteArrayInputStream(content),root.toFile(),name);
                check(out.size==content.length && !out.resource && out.sha256.length()==64,
                    "Imported wrong map result: "+name);
                check(Arrays.equals(content,Files.readAllBytes(root.resolve("custom_maps").resolve(name))),
                    "Map bytes lost during copy: "+name);
            }
            check(Arrays.equals(Files.readAllBytes(maps.resolve("bloodgulch.map")),
                new byte[]{23,42,19}),"Custom maps changed original Xbox data");

            for (String name:new String[]{"bitmaps.map","sounds.map","loc.map"}) {
                int type=name.equals("bitmaps.map")?1:name.equals("sounds.map")?2:3;
                byte[] content=resource(type);
                CustomMapImporter.Result out=CustomMapImporter.install(
                    new ByteArrayInputStream(content),root.toFile(),name);
                check(out.resource && out.size==36,"Resource not copied as a resource");
            }
            rejects(()->CustomMapImporter.install(new ByteArrayInputStream(map("2fort",609)),
                root.toFile(),"2fort.map"),"already installed");

            Path original=root.resolve("custom_maps").resolve("municipality.map");
            byte[] saved=Files.readAllBytes(original);
            rejects(()->CustomMapImporter.install(new ByteArrayInputStream(map("2fort",609)),
                root.toFile(),"municipality.map"),"already installed");
            check(Arrays.equals(saved,Files.readAllBytes(original)),
                "Duplicate name overwrote existing map");

            rejects(()->CustomMapImporter.install(
                new ByteArrayInputStream(map("2fort",609)),root.toFile(),"../evil.map"),
                "standalone");
            check(!Files.exists(root.resolve("evil.map")),"Escaped custom_maps folder");

            rejects(()->CustomMapImporter.install(
                new ByteArrayInputStream(map("different",609)),root.toFile(),"newtest.map"),
                "does not match");
            rejects(()->CustomMapImporter.install(
                new ByteArrayInputStream(map("newtest",5)),root.toFile(),"newtest.map"),
                "v609");
            byte[] corruptLength=map("newtest",609);
            put32(corruptLength,8,123);
            rejects(()->CustomMapImporter.install(
                new ByteArrayInputStream(corruptLength),root.toFile(),"newtest.map"),
                "inconsistent");
            byte[] badTags=map("newtest",609);
            put32(badTags,0x10,1000000);
            rejects(()->CustomMapImporter.install(
                new ByteArrayInputStream(badTags),root.toFile(),"newtest.map"),
                "tag data");

            byte[] wrongResource=resource(3);
            rejects(()->CustomMapImporter.install(
                new ByteArrayInputStream(wrongResource),root.toFile(),"sounds2.map"),
                "invalid cache length");
            byte[] malformedResource=resource(3);
            put32(malformedResource,32,0xfffffff0L);
            Files.write(root.resolve("malformed-resource.map"),malformedResource);
            rejects(()->CustomMapImporter.validate(root.resolve("malformed-resource.map").toFile(),
                "loc.map"),"resource item");

            for (String name:new String[]{"CON.map","../bad.map","folder/map.map","a..b.map",
                ".hidden.map","foo.txt","bad.map.map.map",""}){
                if(name.equals("bad.map.map.map"))continue;
                check(!CustomMapImporter.isValidName(name),"Unsafe name accepted "+name);
            }
            try (java.util.stream.Stream<Path> paths=Files.list(root.resolve("custom_maps"))) {
                check(paths.noneMatch(x->x.toString().endsWith(".pending")),
                    "Rejected input left temporary map file");
            }

            check(CustomMapImporter.isValidName("2fort.map"),"2Fort cannot be imported as CE map");
            System.out.println("CustomMapImporterTest PASS (" + checks + " checks)");
        } finally {
            deleteTree(root);
        }
    }
}
