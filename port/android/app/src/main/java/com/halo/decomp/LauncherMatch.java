package com.halo.decomp;

import java.io.File;
import java.util.ArrayList;

/** Validated one-shot match request; no arbitrary paths or persistent debug
 * commands. Future presets/playlists will use this same launch boundary. */
final class LauncherMatch {
    static final class MapChoice {
        final String id, label;
        MapChoice(String id, String label) { this.id = id; this.label = label; }
    }

    static final MapChoice[] STOCK = {
        new MapChoice("bloodgulch", "Blood Gulch"),
        new MapChoice("beavercreek", "Battle Creek"),
        new MapChoice("sidewinder", "Sidewinder"),
        new MapChoice("damnation", "Damnation"),
        new MapChoice("ratrace", "Rat Race"),
        new MapChoice("prisoner", "Prisoner"),
        new MapChoice("hangemhigh", "Hang 'Em High"),
        new MapChoice("chillout", "Chill Out"),
        new MapChoice("carousel", "Derelict"),
        new MapChoice("boardingaction", "Boarding Action"),
        new MapChoice("wizard", "Wizard"),
        new MapChoice("putput", "Chiron TL-34"),
        new MapChoice("longest", "Longest")
    };
    private static final String[] MODE_IDS = {
        "team_slayer", "slayer", "ctf", "king", "team_king", "oddball",
        "team_oddball", "race", "team_race", "swat", "gun_game",
        "infection", "sticks_and_stones", "vip", "hardcore"
    };
    private static final String[] MODE_LABELS = {
        "Team Slayer", "Slayer", "CTF", "King of the Hill", "Team King", "Oddball",
        "Team Oddball", "Race", "Team Race", "SWAT", "Gun Game",
        "Infection", "Sticks & Stones", "VIP Escort", "Hardcore Slayer"
    };
    private static final String[] SKILLS = {
        "recruit", "easy", "normal", "heroic", "veteran", "legendary"
    };
    final String map, mode, skill, teams, rules;
    final Boolean radio;
    final int botCount;
    final boolean vehicleCrews;

    private LauncherMatch(String map, String mode, int count, String skill,
            String teams, boolean crews, String rules, Boolean radio) {
        this.map = map; this.mode = mode; this.botCount = count;
        this.skill = skill; this.teams = teams; this.vehicleCrews = crews;
        this.rules=rules;this.radio=radio;
    }
    private static boolean contains(String[] list, String value) {
        for (String entry : list) if (entry.equals(value)) return true;
        return false;
    }
    static MapChoice[] installedMaps(File root) {
        ArrayList<MapChoice> choices = new ArrayList<>();
        choices.add(new MapChoice("", "Classic menus"));
        for (WorkshopMapCatalog.MapItem map : WorkshopMapCatalog.installed(root, false))
            choices.add(new MapChoice(map.id, map.name));
        return choices.toArray(new MapChoice[0]);
    }
    static String[] modeLabels() { return MODE_LABELS.clone(); }
    static String modeId(int index) {
        if (index < 0 || index >= MODE_IDS.length)
            throw new IllegalArgumentException("Invalid game mode.");
        return MODE_IDS[index];
    }
    static int modeIndex(String id) {
        for (int i = 0; i < MODE_IDS.length; i++)
            if (MODE_IDS[i].equals(id)) return i;
        return 0;
    }
    static LauncherMatch offline(File root, String map, String mode,
            int count, String skill, String teams, boolean crews) {
        if (!contains(MODE_IDS, mode))
            throw new IllegalArgumentException("Invalid game mode.");
        return checked(root,map,mode,count,skill,teams,crews,"",null);
    }
    static LauncherMatch offlinePreset(File root,String map,MatchWorkshop.Preset preset,
            int count,String skill,String teams,boolean crews) {
        if(map==null||preset==null||MatchWorkshop.baseIndex(preset.base)<0)
            throw new IllegalArgumentException("Invalid game mode.");
        return checked(root,map,preset.base,count,skill,teams,crews,
            map.isEmpty()?"":NativeMatchRules.encode(preset),
            map.isEmpty()?null:NativeMatchRules.radio(preset));
    }
    private static LauncherMatch checked(File root,String map,String mode,
            int count,String skill,String teams,boolean crews,String rules,Boolean radio){
        boolean installed = false;
        for (MapChoice choice : installedMaps(root))
            if (choice.id.equals(map)) installed = true;
        if (!installed)
            throw new IllegalArgumentException("The selected map is unavailable. Check My Maps.");
        if (count < 1 || count > 127 || !contains(SKILLS, skill) ||
                !("even".equals(teams) || "against".equals(teams)))
            throw new IllegalArgumentException("Invalid bot configuration.");
        return new LauncherMatch(map, mode, count, skill, teams, crews, rules, radio);
    }
    String[] arguments() {
        if (map.isEmpty())
            return new String[] {"--offline-bots", Integer.toString(botCount),
                skill, teams, Boolean.toString(vehicleCrews)};
        if (!rules.isEmpty())
            return new String[] {"--launcher-match-rules",map,mode,
                Integer.toString(botCount),skill,teams,Boolean.toString(vehicleCrews),rules};
        return new String[] {"--launcher-match", map, mode,
            Integer.toString(botCount), skill, teams, Boolean.toString(vehicleCrews)};
    }
}
