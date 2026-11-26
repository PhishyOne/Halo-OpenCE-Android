package com.halo.decomp;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class NativeMatchRulesTest {
    static int checks;
    static void check(boolean b,String what){if(!b)throw new AssertionError(what);checks++;}
    static void rejected(Runnable r){try{r.run();throw new AssertionError("Invalid accepted");}catch(IllegalArgumentException expected){checks++;}}
    static MatchWorkshop.Preset preset(String base,Map<String,Integer> rules){return MatchWorkshop.Preset.standard(base).saveAs("Test",rules);}
    public static void main(String[] args)throws Exception{
        Map<String,Integer> values=new HashMap<>();
        values.put("score_to_win",75);values.put("health",200);values.put("killstreak_radio",0);values.put("respawn_time",7);
        MatchWorkshop.Preset p=preset("team_slayer",values);
        check(NativeMatchRules.encode(p).equals("health=200;respawn_time=7;score_to_win=75"),"canonical rules");
        check(Boolean.FALSE.equals(NativeMatchRules.radio(p)),"radio separate");
        check(NativeMatchRules.encode(MatchWorkshop.Preset.standard("ctf")).isEmpty(),"inherit stock rules");
        rejected(()->NativeMatchRules.encode(preset("slayer",Map.of("radar",0,"radar_players",0))));
        rejected(()->NativeMatchRules.encode(preset("slayer",Map.of("loadout",0,"primary_weapon",2))));
        rejected(()->NativeMatchRules.encode(preset("slayer",Map.of("red_vehicle_set",254,"red_warthogs",2))));
        rejected(()->preset("vip",Map.of("time_limit",5)));
        rejected(()->preset("gun_game",Map.of("score_to_win",25)));
        Set<String> keys=new TreeSet<>();
        for(MatchWorkshop.Rule r:MatchWorkshop.RULES){
            String base=null;for(String b:MatchWorkshop.BASE_IDS)if(r.available(b)){base=b;break;}
            check(base!=null,"rule has mode: "+r.key);
            int sample=r.choices==null?r.min:r.choices[0];
            String encoded=NativeMatchRules.encode(preset(base,Collections.singletonMap(r.key,sample)));
            if(r.key.equals("killstreak_radio"))check(encoded.isEmpty(),"separate config");
            else{check(encoded.equals(r.key+"="+sample),"encodes "+r.key);keys.add(r.key);}
        }
        if(args.length>0&&args[0].equals("keys")){System.out.println("KEYS "+String.join(",",keys));return;}
        Path root=Files.createTempDirectory("halo-native-match");
        Path maps=Files.createDirectory(root.resolve("maps"));
        Path map=maps.resolve("bloodgulch.map");
        try{
            try(RandomAccessFile f=new RandomAccessFile(map.toFile(),"rw")){f.setLength(4096);}
            LauncherMatch m=LauncherMatch.offlinePreset(root.toFile(),"bloodgulch",p,8,"normal","even",true);
            String[] args1=m.arguments();
            check(args1.length==8&&args1[0].equals("--launcher-match-rules")&&args1[7].equals(NativeMatchRules.encode(p)),"rules argument");
            check(Boolean.FALSE.equals(m.radio),"per-match radio");
            check(LauncherMatch.offlinePreset(root.toFile(),"",p,8,"normal","even",true).radio==null,"classic no override");
            check(LauncherMatch.offlinePreset(root.toFile(),"bloodgulch",MatchWorkshop.Preset.standard("ctf_pro"),8,"normal","even",true).arguments()[2].equals("ctf_pro"),"expanded standard mode");
        }finally{Files.deleteIfExists(map);Files.deleteIfExists(maps);Files.deleteIfExists(root);}
        System.out.println("NativeMatchRulesTest PASS ("+checks+" checks)");
    }
}
