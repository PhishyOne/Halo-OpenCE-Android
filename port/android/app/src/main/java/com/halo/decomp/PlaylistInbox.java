package com.halo.decomp;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;

/** Add-only playlist documents. UUID filenames let repeat visits skip imported files,
 * including playlists the host has since renamed, reordered or edited. */
final class PlaylistInbox {
    private static final int MAX_PACK_BYTES = 64 * 1024;
    static final String CROSSFIRE = "e73659b2-6113-4e6d-be40-dee85f36bbe9";
    static final String SUFFIX = ".haloplaylist";

    private final File store;
    private MatchWorkshop.Library library;
    private final Set<String> known = new HashSet<>();

    PlaylistInbox(File store) throws IOException {
        this.store = store;
        library = MatchWorkshop.read(store);
        for (MatchWorkshop.Playlist p : library.playlists) known.add(p.id);
    }

    MatchWorkshop.Library library() { return library; }

    boolean accepts(String id) {
        return !known.contains(id) && library.playlists.size() < MatchWorkshop.MAX_PLAYLISTS;
    }

    private static String required(Properties p, String key) throws IOException {
        String value = (String)p.remove(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("Missing " + key);
        return value.trim();
    }

    private static boolean flag(String value) throws IOException {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IOException("Expected true or false");
    }

    private static MatchWorkshop.Entry entry(String line) throws IOException {
        String[] fields = line.split("\\s+");
        if (fields.length < 6) throw new IOException("Incomplete playlist round");
        MatchWorkshop.Preset preset = MatchWorkshop.Preset.standard(fields[1]);
        MatchWorkshop.Assignment assignment = MatchWorkshop.Assignment.valueOf(fields[2]);
        int count = Integer.parseInt(fields[3]), skill = Integer.parseInt(fields[4]);
        if (assignment == MatchWorkshop.Assignment.MANUAL)
            throw new IOException("Use balanced teams, bots vs players, or free for all");
        MatchWorkshop.Bots bots = new MatchWorkshop.Bots(assignment, count, skill, 0, 0, 0, MatchWorkshop.Side.BLUE);
        Map<String,Integer> rules = new LinkedHashMap<>();
        for (int i = 6; i < fields.length; i++) {
            String[] pair = fields[i].split("=", -1);
            if (pair.length != 2 || rules.put(pair[0], Integer.parseInt(pair[1])) != null)
                throw new IOException("Invalid or repeated round setting");
        }
        if (!rules.isEmpty()) preset = preset.saveAs(preset.name, rules);
        return new MatchWorkshop.Entry(fields[0], preset, bots, flag(fields[5]));
    }

    void add(String id, InputStream input) throws IOException {
        if (!accepts(id)) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (bytes.size() + n > MAX_PACK_BYTES) throw new IOException("Playlist document too large");
            bytes.write(buffer, 0, n);
        }
        Properties p = new Properties();
        try {
            p.load(new StringReader(bytes.toString("UTF-8")));
            if (!"1".equals(required(p, "format")) || !id.equals(required(p, "id")))
                throw new IOException("Unexpected playlist identity or format");
            String title = required(p, "title");
            boolean shuffle = flag(required(p, "shuffle")), loop = flag(required(p, "loop"));
            int rounds = Integer.parseInt(required(p, "rounds"));
            if (rounds < 1 || rounds > MatchWorkshop.MAX_ENTRIES) throw new IOException("Invalid number of rounds");
            List<MatchWorkshop.Entry> entries = new ArrayList<>();
            for (int i = 1; i <= rounds; i++) entries.add(entry(required(p, "round." + i)));
            if (!p.isEmpty()) throw new IOException("Unrecognized playlist field");
            MatchWorkshop.Playlist incoming = new MatchWorkshop.Playlist(id, title, entries, shuffle, loop);
            MatchWorkshop.Library merged = library.put(incoming).select(library.playlistMode, library.playlistId);
            MatchWorkshop.write(store, merged);
            library = merged;
            known.add(id);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid playlist: " + invalid.getMessage(), invalid);
        }
    }

    void scan(File folder, java.util.function.Consumer<String> report) {
        if (!Files.isDirectory(folder.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(folder.toPath(), "*" + SUFFIX)) {
            for (Path path : paths) {
                if (library.playlists.size() >= MatchWorkshop.MAX_PLAYLISTS) break;
                String name = path.getFileName().toString();
                String id = name.substring(0, name.length() - SUFFIX.length());
                if (!accepts(id)) continue;
                try {
                    MatchWorkshop.checkedId(id);
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_PACK_BYTES)
                        continue;
                    try (InputStream in = Channels.newInputStream(Files.newByteChannel(path,
                            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                        add(id, in);
                    }
                } catch (IOException | IllegalArgumentException | SecurityException badFile) {
                    report.accept(name + ": " + badFile.getMessage());
                }
            }
        } catch (IOException | DirectoryIteratorException | SecurityException error) {
            report.accept("Playlist folder: " + error.getMessage());
        }
    }
}
