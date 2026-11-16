package com.halo.decomp;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only audit of a Halo Custom Edition v609 map and the external tag
 * resources it needs. Follows cache_file_resource_maps_needed and the
 * resource name/index checks in cache_file_formats.c, without importing
 * assets, changing the map, or claiming the renderer can draw it.
 *
 * The real cache loader remains authoritative. This is a diagnostic,
 * with strict bounds for untrusted maps and resource index tables.
 */
final class CeResourceAudit {
    private static final long CE_BASE = 0x40440000L;
    private static final int HEADER_SIZE = 0x800;
    private static final int INSTANCE_SIZE = 0x20;
    private static final int MAX_TAGS = 65536;
    private static final int MAX_TAG_DATA = 0x1700000;
    private static final int MAX_RESOURCE_ITEMS = 100000;
    private static final int MAX_RESOURCE_NAMES_BYTES = 16 * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final String[] RESOURCES = {
        "bitmaps.map", "sounds.map", "loc.map"
    };
    private static final int[] RESOURCE_TYPES = {1, 2, 3};

    private static final class TagRef {
        final String name;
        final long index;
        TagRef(String name, long index) { this.name = name; this.index = index; }
    }

    private static final class TagInfo {
        final List<TagRef>[] refs;
        final int count;
        final int bsps;

        @SuppressWarnings("unchecked")
        TagInfo(int count, int bsps, List<TagRef>[] refs) {
            this.count = count;
            this.bsps = bsps;
            this.refs = refs;
        }
    }

    private CeResourceAudit() { }

    private static long u32(byte[] data, int at) {
        return ((long)data[at] & 255) |
            (((long)data[at+1] & 255) << 8) |
            (((long)data[at+2] & 255) << 16) |
            (((long)data[at+3] & 255) << 24);
    }

    private static int fourcc(String word) {
        return (word.charAt(0) << 24) | (word.charAt(1) << 16) |
            (word.charAt(2) << 8) | word.charAt(3);
    }

    private static String cString(byte[] data, long start, int max) throws IOException {
        if (start < 0 || start >= data.length)
            throw new IOException("Map contains an invalid external tag name.");
        int from = (int)start;
        int end = from;
        while (end < data.length && end - from <= max && data[end] != 0)
            end++;
        if (end == from || end >= data.length || end - from > max)
            throw new IOException("Map contains an unterminated external tag name.");
        return new String(data, from, end - from, StandardCharsets.US_ASCII);
    }

    private static File regular(File parent, String filename) throws IOException {
        if (!parent.isDirectory() || Files.isSymbolicLink(parent.toPath()))
            throw new IOException("Halo custom_maps directory is missing or unsafe.");
        File file = new File(parent, filename);
        if (Files.isSymbolicLink(file.toPath()))
            throw new IOException("Refusing linked custom map file.");
        return file;
    }

    @SuppressWarnings("unchecked")
    private static TagInfo mapInfo(File file, String filename) throws IOException {
        if (!file.isFile() || file.length() < HEADER_SIZE ||
            file.length() > MAX_FILE_BYTES)
            throw new IOException(filename + " is missing or invalid.");
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[HEADER_SIZE];
            input.readFully(header);
            if (u32(header, 0) != (fourcc("head") & 0xffffffffL) ||
                u32(header, 0x7fc) != (fourcc("foot") & 0xffffffffL) ||
                u32(header, 4) != 609 ||
                (u32(header, 8) != 0 && u32(header, 8) != input.length()))
                throw new IOException(filename + " is not a valid CE v609 cache.");
            String internal = cString(header, 0x20, 31);
            if (!internal.equalsIgnoreCase(filename.substring(0, filename.length()-4)))
                throw new IOException("The map's internal name doesn't match its filename.");
            long offset = u32(header, 0x10);
            long bytes = u32(header, 0x14);
            if (offset < HEADER_SIZE || bytes < 0x28 || bytes > MAX_TAG_DATA ||
                offset > input.length() || bytes > input.length() - offset)
                throw new IOException("Map tag-data range is invalid.");
            byte[] tags = new byte[(int)bytes];
            input.seek(offset);
            input.readFully(tags);
            long count = u32(tags, 0x0c);
            long instances = u32(tags, 0) - CE_BASE;
            if (u32(tags, 0x24) != (fourcc("tags") & 0xffffffffL) ||
                count == 0 || count > MAX_TAGS ||
                instances < 0x28 || instances > tags.length ||
                count > (tags.length - instances)/INSTANCE_SIZE)
                throw new IOException("Map tag index is invalid.");
            List<TagRef>[] refs = (List<TagRef>[]) new List<?>[3];
            for (int r=0;r<3;r++) refs[r] = new ArrayList<>();
            int bsps = 0;
            for (int i=0;i<count;i++) {
                int at = (int)instances + i * INSTANCE_SIZE;
                int group = (int)u32(tags, at);
                if (group == fourcc("sbsp")) bsps++;
                if (u32(tags, at + 0x18) == 0)
                    continue;
                int r = group == fourcc("bitm") ? 0 :
                    group == fourcc("snd!") ? 1 :
                    group == fourcc("font") || group == fourcc("ustr") ||
                    group == fourcc("hmt ") ? 2 : -1;
                if (r < 0) continue;
                long nameAt = u32(tags, at + 0x10) - CE_BASE;
                String name = cString(tags, nameAt, 255);
                refs[r].add(new TagRef(name, u32(tags, at + 0x14)));
            }
            return new TagInfo((int)count, bsps, refs);
        }
    }

    /** Read only resource names and indexes, not the megabytes of textures
     * and sound samples. For bitmaps and locale the tag's address indexes
     * the resource list; sound resources are selected by path name. */
    private static int countMatching(File file, int type, List<TagRef> refs)
            throws IOException {
        if (!file.isFile() || file.length() < 16 ||
            file.length() > MAX_FILE_BYTES)
            throw new IOException(file.getName() + " is missing or invalid.");
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] h = new byte[16];
            in.readFully(h);
            long namesOffset = u32(h, 4);
            long indexOffset = u32(h, 8);
            long count = u32(h, 12);
            if (u32(h, 0) != type ||
                namesOffset < 16 || indexOffset < namesOffset ||
                indexOffset > in.length() ||
                indexOffset - namesOffset > MAX_RESOURCE_NAMES_BYTES ||
                count > MAX_RESOURCE_ITEMS ||
                count > (in.length() - indexOffset)/12)
                throw new IOException(file.getName() + " has invalid resource metadata.");
            byte[] names = new byte[(int)(indexOffset - namesOffset)];
            byte[] entries = new byte[(int)count * 12];
            in.seek(namesOffset);in.readFully(names);
            in.seek(indexOffset);in.readFully(entries);
            String[] entryNames = new String[(int)count];
            java.util.HashSet<String> soundNames = type == 2 ?
                new java.util.HashSet<String>() : null;
            for (int i=0;i<count;i++) {
                long pos = u32(entries, i*12);
                long payloadSize = u32(entries, i*12 + 4);
                long payloadAt = u32(entries, i*12 + 8);
                if (pos >= names.length || payloadAt < 16 ||
                    payloadAt > namesOffset ||
                    payloadSize > namesOffset - payloadAt)
                    throw new IOException(file.getName() + " has an invalid resource item.");
                String name = cString(names, pos, 255);
                entryNames[i] = name;
                if (soundNames != null) soundNames.add(name);
            }
            int matches = 0;
            for (TagRef ref : refs) {
                if (type == 2 ? soundNames.contains(ref.name) :
                    ref.index < entryNames.length &&
                    entryNames[(int)ref.index].equals(ref.name))
                    matches++;
            }
            return matches;
        }
    }

    static String beachReport(File appRoot) {
        if (appRoot == null)
            return "Beach Assault: Halo app storage is unavailable.";
        try {
            File custom = new File(appRoot, "custom_maps");
            File beach = regular(custom, "beach_assault.map");
            TagInfo map = mapInfo(beach, "beach_assault.map");
            StringBuilder report = new StringBuilder();
            report.append("Beach Assault: CE v609, ")
                .append(map.count).append(" tags, ")
                .append(map.bsps).append(" structure BSP(s). ");
            boolean allMatch = true;
            for (int i=0;i<RESOURCES.length;i++) {
                List<TagRef> needed = map.refs[i];
                if (needed.isEmpty()) continue;
                File resource = regular(custom, RESOURCES[i]);
                try {
                    int matched = countMatching(resource, RESOURCE_TYPES[i], needed);
                    report.append(RESOURCES[i]).append(": ")
                        .append(matched).append("/").append(needed.size())
                        .append(" references matched. ");
                    if (matched != needed.size())
                        allMatch = false;
                } catch (IOException problem) {
                    allMatch = false;
                    report.append(RESOURCES[i]).append(": ")
                        .append(problem.getMessage()).append(" ");
                }
            }
            if (allMatch) {
                report.append("External resource references match installed files. ")
                    .append("A blue screen now points to map-loading/rendering ")
                    .append("compatibility; save Halo Diagnostics after reproducing. ")
                    .append("This does not prove the map renders correctly.");
            } else {
                report.append("The installed Custom Edition resources are missing or ")
                    .append("incompatible. Reimport matching resources without ")
                    .append("uninstalling Halo or deleting campaign saves.");
            }
            return report.toString();
        } catch (IOException issue) {
            return "Beach Assault audit: " + issue.getMessage();
        }
    }
}
