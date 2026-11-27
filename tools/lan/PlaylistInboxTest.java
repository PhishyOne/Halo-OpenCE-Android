package com.halo.decomp;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class PlaylistInboxTest {
    static void check(boolean v) { if (!v) throw new AssertionError(); }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("halo-inbox"), folder = Files.createDirectory(root.resolve("Playlists"));
        File store = root.toFile();
        MatchWorkshop.Library before = MatchWorkshop.Library.empty();
        MatchWorkshop.Playlist original = new MatchWorkshop.Playlist(MatchWorkshop.newId(), "Original",
            Collections.singletonList(before.quick), true, true);
        before = before.put(original);
        MatchWorkshop.write(store, before);
        byte[] pack = Files.readAllBytes(Paths.get(args[0]));
        String id = PlaylistInbox.CROSSFIRE;
        Path file = folder.resolve(id + PlaylistInbox.SUFFIX);
        Files.write(file, pack);
        String broken = MatchWorkshop.newId();
        Files.write(folder.resolve(broken + PlaylistInbox.SUFFIX), new byte[]{1,2,3});
        List<String> errors = new ArrayList<>();
        PlaylistInbox inbox = new PlaylistInbox(store);
        inbox.scan(folder.toFile(), errors::add);
        check(errors.size()==1);
        MatchWorkshop.Library loaded = MatchWorkshop.read(store);
        check(loaded.playlists.size()==2 && loaded.selected().id.equals(original.id));
        check(loaded.playlistMode && loaded.selected().shuffle && loaded.selected().loop);
        MatchWorkshop.Playlist added = loaded.playlists.get(1);
        check(added.id.equals(id) && added.name.equals("Halo Night: Crossfire") && !added.shuffle && !added.loop);
        String[] maps = {"beavercreek","ratrace","hangemhigh","damnation","longest","bloodgulch"};
        String[] modes = {"team_slayer","gun_game","swat","team_king","rockets","ctf"};
        int[] counts = {4,2,4,4,2,6}, minutes = {8,8,8,8,6,12};
        check(added.entries.size()==6);
        for (int i=0;i<6;i++) {
            MatchWorkshop.Entry e=added.entries.get(i);
            check(e.map.equals(maps[i]) && e.preset.base.equals(modes[i]) && e.bots.count==counts[i]);
            check(e.bots.difficulty==2 && e.killstreak==(i==0) && e.preset.overrides.get("time_limit")==minutes[i]);
        }
        check(added.entries.get(1).preset.overrides.get("gun_game_melee_demotes")==1);
        check(added.entries.get(1).preset.overrides.get("gun_game_order")==1);
        check(added.entries.get(3).preset.overrides.get("moving_hill")==1);
        check(added.entries.get(5).preset.overrides.get("score_to_win")==3);
        MatchWorkshop.write(store, loaded.put(added.reorder(5,0)).select(false,original.id));
        Path saved = root.resolve("match-workshop-v1.bin");
        byte[] edited = Files.readAllBytes(saved);
        new PlaylistInbox(store).scan(folder.toFile(), s -> {});
        check(Arrays.equals(edited, Files.readAllBytes(saved)));
        String asText=new String(pack, java.nio.charset.StandardCharsets.UTF_8);
        String other=MatchWorkshop.newId();
        Path another=folder.resolve(other+PlaylistInbox.SUFFIX);
        Files.write(another,asText.replace(id,other).replace("score_to_win=3\n","score_to_win=9999\n").getBytes("UTF-8"));
        new PlaylistInbox(store).scan(folder.toFile(), s -> {});
        check(Arrays.equals(edited, Files.readAllBytes(saved)));
        Files.write(another,asText.replace(id,other).replace("Halo Night: Crossfire","Next time").getBytes("UTF-8"));
        new PlaylistInbox(store).scan(folder.toFile(), s -> {});
        loaded=MatchWorkshop.read(store);
        check(loaded.playlists.size()==3 && !loaded.playlistMode && loaded.playlistId.equals(original.id));
        check(loaded.playlists.get(1).entries.get(0).map.equals("bloodgulch"));
        System.out.println("Six-round Crossfire, add-only imports, preserved selection and edits, invalid packs and retries passed.");
    }
}
