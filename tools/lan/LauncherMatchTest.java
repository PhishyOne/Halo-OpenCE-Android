package com.halo.decomp;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public final class LauncherMatchTest {
    static int checks;
    private static void check(boolean b,String label) {
        if (!b) throw new AssertionError(label); checks++;
    }
    private static void reject(File root,String map,String mode,int count,String skill,String teams) {
        try { LauncherMatch.offline(root,map,mode,count,skill,teams,true); }
        catch (IllegalArgumentException expected) { checks++;return; }
        throw new AssertionError("invalid request accepted");
    }
    public static void main(String[] args) throws Exception {
        File root=Files.createTempDirectory("halo-match-test").toFile();
        File maps=new File(root,"maps"); maps.mkdir();
        File map=new File(maps,"bloodgulch.map");
        try {
            check(LauncherMatch.installedMaps(root).length==1,"classic only");
            try (RandomAccessFile f=new RandomAccessFile(map,"rw")) { f.setLength(4096); }
            check(LauncherMatch.installedMaps(root).length==2,"one installed stock map");
            check(LauncherMatch.installedMaps(root)[1].id.equals("bloodgulch"),"stable map ID");
            check(LauncherMatch.modeId(LauncherMatch.modeIndex("gun_game")).equals("gun_game"),"mode roundtrip");
            check(LauncherMatch.modeIndex("unknown")==0,"safe default");
            LauncherMatch m=LauncherMatch.offline(root,"bloodgulch","gun_game",16,"heroic","even",true);
            check(Arrays.equals(m.arguments(),new String[]{"--launcher-match","bloodgulch","gun_game","16","heroic","even","true"}),"direct args");
            m=LauncherMatch.offline(root,"","team_slayer",1,"normal","against",false);
            check(Arrays.equals(m.arguments(),new String[]{"--offline-bots","1","normal","against","false"}),"classic unchanged");
            reject(root,"../secret","team_slayer",1,"normal","even");
            reject(root,"sidewinder","team_slayer",1,"normal","even");
            reject(root,"bloodgulch","unknown",1,"normal","even");
            reject(root,"bloodgulch","slayer",0,"normal","even");
            reject(root,"bloodgulch","slayer",128,"normal","even");
            reject(root,"bloodgulch","slayer",3,"bad","even");
            reject(root,"bloodgulch","slayer",3,"normal","bad");
            check(map.delete(),"remove test map");
            reject(root,"bloodgulch","slayer",3,"normal","even");
        } finally { map.delete(); maps.delete(); root.delete(); }
        System.out.println("PASS LauncherMatchTest: "+checks+" checks");
    }
}
