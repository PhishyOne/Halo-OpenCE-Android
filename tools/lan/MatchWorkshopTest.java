package com.halo.decomp;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class MatchWorkshopTest {
    static int checks;
    static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
    static void reject(Runnable r){try{r.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Invalid accepted");}
    public static void main(String[] args)throws Exception{
        check(MatchWorkshop.BASE_IDS.length==MatchWorkshop.BASE_LABELS.length,"mode labels");
        check(MatchWorkshop.BASE_IDS.length==35,"standard and OpenCE presets");
        check(!MatchWorkshop.Preset.standard("king_pro").teams(),"King Pro FFA");
        check(MatchWorkshop.family("stalker")==MatchWorkshop.Family.ODDBALL,"Stalker oddball");
        Set<String> keys=new HashSet<>();for(MatchWorkshop.Rule r:MatchWorkshop.RULES)check(keys.add(r.key),"unique rule "+r.key);
        MatchWorkshop.Preset slayer=MatchWorkshop.Preset.standard("team_slayer");
        Map<String,Integer> rules=new LinkedHashMap<>();rules.put("score_to_win",75);rules.put("friendly_fire",1);
        MatchWorkshop.Preset custom=slayer.saveAs("Heavy Slayer",rules);
        rules.put("score_to_win",100);
        check(custom.overrides.get("score_to_win")==75,"immutable rule snapshot");
        MatchWorkshop.Preset revised=custom.revise("Heavy Slayer",rules);
        check(revised.id.equals(custom.id)&&revised.revision==2,"stable ID revision");
        reject(()->slayer.saveAs("invalid",Collections.singletonMap("gravity",100)));
        reject(()->slayer.saveAs("invalid",Collections.singletonMap("ball_spawn_count",3)));
        reject(()->slayer.saveAs("invalid",Collections.singletonMap("friendly_fire",4)));
        reject(()->MatchWorkshop.Preset.standard("gun_game").saveAs("invalid",Collections.singletonMap("teams",1)));
        reject(()->MatchWorkshop.Preset.standard("infection").saveAs("invalid",Collections.singletonMap("infection_zombie_speed",201)));
        reject(()->MatchWorkshop.Preset.standard("king").saveAs("invalid",Collections.singletonMap("multi_hill",1)));
        check(MatchWorkshop.Preset.standard("king").saveAs("Multi-Hill",Collections.singletonMap("multi_hill",3)).overrides.get("multi_hill")==3,"native hill option");
        // Every normal balanced allocation is mathematically optimal given
        // fixed human teams, a finite number of bots, and the count cap.
        for(int r=0;r<=16;r++)for(int b=0;b<=16;b++)for(int n=0;n<=64;n++){
            MatchWorkshop.Allocation a=MatchWorkshop.Bots.balanced(n,2).resolve(true,r,b,0,64,128);
            int optimal=Integer.MAX_VALUE;
            for(int x=0;x<=n;x++)optimal=Math.min(optimal,Math.abs(r+x-b-(n-x)));
            check(a.valid&&a.red+a.blue==n&&a.ffa==0&&Math.abs(r+a.red-b-a.blue)==optimal,"optimal fixed-team balance");
        }
        MatchWorkshop.Bots handicap=new MatchWorkshop.Bots(MatchWorkshop.Assignment.BALANCED,12,3,0,0,2,MatchWorkshop.Side.BLUE);
        MatchWorkshop.Allocation a=handicap.resolve(true,3,1,0,64,128);
        check(a.red==5&&a.blue==9&&a.bots()==14,"additive blue advantage");
        MatchWorkshop.Bots manual=new MatchWorkshop.Bots(MatchWorkshop.Assignment.MANUAL,0,4,2,8,0,MatchWorkshop.Side.RED);
        check(manual.resolve(true,3,1,0,64,128).red==2,"manual counts retained");
        check(manual.forMode(false).assignment==MatchWorkshop.Assignment.FREE_FOR_ALL&&manual.forMode(false).count==10,"manual FFA conversion");
        check(!handicap.resolve(true,3,1,0,13,128).valid,"capacity rejects rather than silently clamps");
        MatchWorkshop.Bots versus=new MatchWorkshop.Bots(MatchWorkshop.Assignment.BOTS_VS_PLAYERS,8,2,0,0,0,MatchWorkshop.Side.BLUE);
        check(!versus.resolve(true,1,1,0,64,128).valid,"split humans incompatible");
        check(versus.resolve(true,0,2,0,64,128).red==8,"bots oppose humans");
        reject(()->MatchWorkshop.Bots.balanced(65,2));
        reject(()->new MatchWorkshop.Bots(MatchWorkshop.Assignment.BALANCED,64,2,0,0,1,MatchWorkshop.Side.BLUE));
        reject(()->handicap.resolve(true,Integer.MAX_VALUE,0,0,64,128));
        reject(()->MatchWorkshop.checkedMap("ce:../unsafe"));
        reject(()->MatchWorkshop.checkedMap("ce:bitmaps"));
        MatchWorkshop.Entry one=new MatchWorkshop.Entry("bloodgulch",custom,handicap,true);
        MatchWorkshop.Entry two=new MatchWorkshop.Entry("sidewinder",MatchWorkshop.Preset.standard("ctf"),manual,false);
        MatchWorkshop.Entry three=new MatchWorkshop.Entry("ce:community",MatchWorkshop.Preset.standard("gun_game"),manual,true);
        check(three.bots.assignment==MatchWorkshop.Assignment.FREE_FOR_ALL&&three.bots.count==10,"mode policy normalized");
        MatchWorkshop.Playlist ordered=new MatchWorkshop.Playlist(MatchWorkshop.newId(),"Friday",Arrays.asList(one,two,three),false,false);
        check(ordered.reorder(0,2).entries.get(2)==one,"reorder stable");
        MatchWorkshop.Rotation fixed=new MatchWorkshop.Rotation(ordered,new Random(9));
        check(fixed.next()==one&&fixed.next()==two&&fixed.next()==three&&fixed.next()==null,"ordered no loop");
        MatchWorkshop.Playlist shuffled=new MatchWorkshop.Playlist(MatchWorkshop.newId(),"Shuffle",ordered.entries,true,true);
        MatchWorkshop.Rotation rotation=new MatchWorkshop.Rotation(shuffled,new Random(12));
        MatchWorkshop.Entry last=null;
        for(int pass=0;pass<20;pass++){
            Set<MatchWorkshop.Entry> seen=new HashSet<>();
            for(int i=0;i<3;i++){MatchWorkshop.Entry e=rotation.next();if(i==0&&last!=null)check(e!=last,"no immediate repeat");check(seen.add(e),"no repeats in shuffle pass");last=e;}
        }
        MatchWorkshop.Library lib=MatchWorkshop.Library.empty().put(custom).put(ordered).withQuick(one).put(revised);
        check(lib.selected().entries.get(0).preset.revision==1,"playlist holds original revision");
        check(lib.presets.get(0).revision==2,"library latest revision");
        MatchWorkshop.Library finalLib=lib;
        reject(()->finalLib.put(custom));
        reject(()->finalLib.put(slayer));
        Path dir=Files.createTempDirectory("halo-workshop-tests");
        Path file=dir.resolve("match-workshop-v1.bin");
        try{
            check(MatchWorkshop.read(dir.toFile()).presets.isEmpty(),"new library");
            MatchWorkshop.write(dir.toFile(),lib);
            MatchWorkshop.Library read=MatchWorkshop.read(dir.toFile());
            check(read.playlistMode&&read.selected().entries.size()==3,"playlist saved");
            check(read.selected().entries.get(0).preset.overrides.get("score_to_win")==75,"snapshot persisted");
            check(read.presets.get(0).overrides.get("score_to_win")==100,"latest revision persisted");
            check(read.quick.bots.extra==2&&read.quick.bots.extraSide==MatchWorkshop.Side.BLUE,"handicap persisted");
            check(!read.selected().entries.get(1).killstreak,"per-entry killstreak persisted");
            check(read.selected().entries.get(2).map.equals("ce:community"),"custom map ID persisted");
            byte[] previous=Files.readAllBytes(file);Files.write(file,new byte[]{1,2,3});
            try{MatchWorkshop.read(dir.toFile());throw new AssertionError("corrupt file accepted");}
            catch(IOException expected){checks++;}
            Files.write(file,previous);check(MatchWorkshop.read(dir.toFile()).selected().entries.size()==3,"read failure didn't alter file");
            Files.delete(file);Files.createDirectory(file);
            try{MatchWorkshop.write(dir.toFile(),lib);throw new AssertionError("directory replaced");}
            catch(IOException expected){checks++;}
            check(Files.isDirectory(file),"unsafe destination preserved");
        }finally{Files.deleteIfExists(file);Files.deleteIfExists(dir);}
        System.out.println("PASS MatchWorkshopTest: "+checks+" checks");
    }
}
