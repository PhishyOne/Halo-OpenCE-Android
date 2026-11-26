package com.halo.decomp;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** One import entry point, retaining the existing stock repair and CE
 * validators. No archive path is ever used as a destination. */
final class UnifiedMapImport {
    private static final long FILE_LIMIT = 256L * 1024 * 1024;
    private static final long METADATA_LIMIT = 1024L * 1024;
    interface Progress { void current(String message); }
    private UnifiedMapImport() { }

    static String install(InputStream source, File root, String name, Progress progress)
            throws IOException {
        if (source == null) throw new IOException("Android could not read the selected file.");
        PushbackInputStream input = new PushbackInputStream(source, 8);
        byte[] head = peek(input);
        long[] total = {0};
        if (head.length >= 4 && head[0]=='P' && head[1]=='K' &&
            ((head[2]==3 && head[3]==4) || (head[2]==5 && head[3]==6)))
            return archive(input, root, progress, total);
        return one(new CampaignRepairImport.Limit(input, FILE_LIMIT, total), root, name);
    }

    private static byte[] peek(PushbackInputStream input) throws IOException {
        byte[] bytes = new byte[8];
        int n=0, count;
        while(n<bytes.length && (count=input.read(bytes,n,bytes.length-n))!=-1) {
            if(count==0) continue;
            n+=count;
        }
        input.unread(bytes,0,n);
        return Arrays.copyOf(bytes,n);
    }

    private static String one(InputStream source, File root, String name) throws IOException {
        PushbackInputStream input=new PushbackInputStream(source,8);
        byte[] h=peek(input);
        if(h.length==8 && h[0]=='d' && h[1]=='a' && h[2]=='e' && h[3]=='h' &&
            h[4]==5 && h[5]==0 && h[6]==0 && h[7]==0)
            return SidewinderRepair.installDetected(input,root);
        if(!CustomMapImporter.isImportName(name))
            throw new IOException("Select a Halo .map, .bmp preview, or ZIP. Unsupported file: "+name);
        CustomMapImporter.Result result=CustomMapImporter.install(input,root,name);
        return "✓ PASS  "+result.name+" — imported ("+(result.size/1048576)+" MiB).";
    }

    private static String safeBasename(String path) throws IOException {
        if(path==null || path.isEmpty() || path.indexOf('\\')>=0 || path.indexOf(':')>=0 ||
            path.startsWith("/") || path.indexOf('\u0000')>=0)
            throw new IOException("Unsafe ZIP entry path.");
        String[] parts=path.split("/",-1);
        if(parts.length>8) throw new IOException("ZIP path is too deep.");
        for(int i=0;i<parts.length;i++) {
            if(parts[i].equals(".") || parts[i].equals("..") ||
                (parts[i].isEmpty() && i!=parts.length-1))
                throw new IOException("Unsafe ZIP entry path.");
        }
        return parts[parts.length-1];
    }

    private static void drain(InputStream source) throws IOException {
        byte[] buffer=new byte[65536];
        while(source.read(buffer)!=-1) { }
    }

    private static String archive(InputStream input, File root, Progress progress, long[] total)
            throws IOException {
        StringBuilder report=new StringBuilder();
        int entries=0, maps=0;
        Set<String> seen=new HashSet<>();
        try(ZipInputStream zip=new ZipInputStream(input)) {
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(++entries>64) throw new IOException("ZIP contains more than 64 entries.");
                String name=safeBasename(entry.getName());
                if(entry.isDirectory()) {
                    if(zip.read()!=-1) throw new IOException("ZIP directory contains data.");
                    zip.closeEntry(); continue;
                }
                String lower=name.toLowerCase(Locale.ROOT);
                boolean candidate=lower.endsWith(".map")||lower.endsWith(".bmp");
                CampaignRepairImport.Limit bounded=new CampaignRepairImport.Limit(zip,
                    candidate?FILE_LIMIT:METADATA_LIMIT,total);
                if(candidate) {
                    if(++maps>32) throw new IOException("ZIP contains more than 32 map/preview files.");
                    if(!seen.add(lower)) throw new IOException("Duplicate ZIP filename: "+name);
                    if(progress!=null)progress.current("Checking "+name+" ("+maps+")...");
                    try { report.append(one(bounded,root,name)).append('\n'); }
                    catch(IOException invalid) {
                        report.append("✕ FAIL  ").append(name).append(": ").append(invalid.getMessage()).append('\n');
                    }
                }
                drain(bounded); // stock healthy-map shortcut, bounded metadata, and CRC
                zip.closeEntry();
            }
            if(maps==0)throw new IOException("ZIP contains no Halo maps or BMP previews.");
        }catch(IOException stopped) {
            report.append("✕ FAIL  ZIP stopped: ").append(stopped.getMessage())
                .append(". Earlier successful imports are kept.\n");
        }
        return report.toString().trim();
    }
}
