package com.halo.decomp;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Versioned match configuration, independent of Activity, networking and
 * rendering. Entries snapshot presets; changing a preset never silently
 * changes a playlist. The engine must acknowledge the final request before
 * any of these drafts can be called a live lobby. */
final class MatchWorkshop {
    static final int MAX_PLAYERS=128, MAX_LAN_BOTS=64, MAX_PRESETS=128, MAX_PLAYLISTS=64, MAX_ENTRIES=128;
    static final int MAX_BYTES=1024*1024;
    static final String[] DIFFICULTIES={"Recruit","Easy","Normal","Heroic","Veteran","Legendary"};
    static final String[] BASE_IDS={"slayer","team_slayer","slayer_pro","elimination","phantoms",
        "endurance","rockets","snipers","stalker","ctf","ironctf","ctf_pro","invasion",
        "oddball","team_oddball","reverse_tag","accumulation","juggernaut","king","team_king",
        "king_pro","crazy_king","race","team_race","rally","team_rally",
        "swat","infection","gun_game","hardcore","hardcore_ctf","hardcore_oddball",
        "hardcore_king","sticks_and_stones","vip"};
    static final String[] BASE_LABELS={"Slayer","Team Slayer","Slayer Pro","Elimination","Phantoms",
        "Endurance","Rockets","Snipers","Stalker","CTF","Iron CTF","CTF Pro","Invasion",
        "Oddball","Team Oddball","Reverse Tag","Accumulate","Juggernaut","King","Team King",
        "King Pro","Crazy King","Race","Team Race","Rally","Team Rally",
        "SWAT","Infection","Gun Game","Hardcore Slayer","Hardcore CTF","Hardcore Oddball",
        "Hardcore King","Sticks & Stones","VIP Escort"};
    private static final Set<String> TEAM_BASE=new HashSet<>(Arrays.asList(
        "team_slayer","ctf","ironctf","ctf_pro","invasion","team_oddball","team_king",
        "team_race","team_rally","swat","infection","hardcore",
        "hardcore_ctf","hardcore_oddball","hardcore_king","vip"));
    static final Set<String> STOCK=new HashSet<>(Arrays.asList("beavercreek","sidewinder",
        "damnation","ratrace","prisoner","hangemhigh","chillout","carousel",
        "boardingaction","bloodgulch","wizard","putput","longest"));

    enum Assignment { BALANCED, BOTS_VS_PLAYERS, MANUAL, FREE_FOR_ALL }
    enum Side { RED, BLUE }
    enum Family { SLAYER, CTF, KING, ODDBALL, RACE, INFECTION, GUN_GAME, VIP }

    static int baseIndex(String id) {
        for(int i=0;i<BASE_IDS.length;i++) if(BASE_IDS[i].equals(id)) return i;
        return -1;
    }
    static Family family(String base) {
        if(baseIndex(base)<0) throw new IllegalArgumentException("Unknown base mode");
        if(base.equals("infection")) return Family.INFECTION;
        if(base.equals("gun_game")) return Family.GUN_GAME;
        if(base.equals("vip")) return Family.VIP;
        if(base.contains("ctf")||base.equals("invasion")) return Family.CTF;
        if(base.contains("king")) return Family.KING;
        if(base.contains("oddball")||base.equals("reverse_tag")||base.equals("accumulation")||base.equals("juggernaut")||base.equals("stalker")) return Family.ODDBALL;
        if(base.contains("race")||base.contains("rally")) return Family.RACE;
        return Family.SLAYER;
    }
    static boolean teamDefault(String base) { return TEAM_BASE.contains(base); }
    static boolean fixedTeams(String base) {
        Family f=family(base);
        return f==Family.CTF||f==Family.INFECTION||f==Family.GUN_GAME||f==Family.VIP||
            base.equals("swat")||base.equals("sticks_and_stones");
    }

    /** Values are UI units or explicit native enum choices, not memory offsets.
     * Missing override means inherit the native preset. Native binding is a
     * separate milestone; this is a checked editable draft schema. */
    static final class Rule {
        final String key, group, label;
        final Family family;
        final int min,max;
        final int[] choices;
        final String[] labels;
        Rule(String key,String group,String label,Family family,int min,int max,int[] choices,String[] labels) {
            this.key=key;this.group=group;this.label=label;this.family=family;
            this.min=min;this.max=max;this.choices=choices;this.labels=labels;
        }
        boolean accepts(int v) {
            if(choices!=null) { for(int c:choices) if(c==v) return true; return false; }
            return v>=min&&v<=max;
        }
        boolean available(String base) {
            if(key.equals("teams")) return !fixedTeams(base);
            // The native VIP engine owns these values and sanitizes them.
            if(base.equals("vip")&&Arrays.asList("time_limit","hardcore","friendly_fire",
                "friendly_fire_penalty","auto_team_balance","force_team_spawns").contains(key))return false;
            if(key.equals("score_to_win")) return family(base)!=Family.GUN_GAME&&family(base)!=Family.INFECTION&&family(base)!=Family.VIP;
            return family==null||family(base)==family;
        }
        String display(int v) {
            if(choices!=null&&labels!=null) for(int i=0;i<choices.length;i++) if(choices[i]==v) return labels[i];
            return Integer.toString(v);
        }
    }
    private static Rule range(String key,String group,String label,Family f,int min,int max) {
        return new Rule(key,group,label,f,min,max,null,null);
    }
    private static Rule choice(String key,String group,String label,Family f,int[] values,String... labels) {
        return new Rule(key,group,label,f,0,0,values,labels.length==values.length?labels:null);
    }
    private static Rule toggle(String key,String group,String label,Family f) {
        return choice(key,group,label,f,new int[]{0,1},"Off","On");
    }
    private static Rule enumeration(String key,String group,String label,Family f,String... labels) {
        int[] values=new int[labels.length];for(int i=0;i<values.length;i++)values[i]=i;
        return choice(key,group,label,f,values,labels);
    }
    static final List<Rule> RULES;
    static {
        ArrayList<Rule> r=new ArrayList<>();
        r.add(toggle("teams","Match","Team play",null));
        r.add(range("score_to_win","Match","Score to win",null,1,500));
        r.add(range("time_limit","Match","Time limit (minutes; 0 unlimited)",null,0,120));
        r.add(range("lives","Match","Lives (0 unlimited)",null,0,50));
        r.add(toggle("odd_man_out","Match","Odd man out",null));
        r.add(range("respawn_time","Respawn","Respawn delay (seconds)",null,0,60));
        r.add(range("respawn_time_growth","Respawn","Respawn growth (seconds)",null,0,60));
        r.add(range("suicide_penalty","Respawn","Suicide penalty (seconds)",null,0,60));
        r.add(range("health","Players","Health (%)",null,25,400));
        r.add(toggle("no_shields","Players","No shields",null));
        r.add(toggle("invisible","Players","Always invisible",null));
        r.add(toggle("hardcore","Players","Hardcore",null));
        r.add(toggle("radar","Indicators","Motion sensor",null));
        r.add(enumeration("radar_players","Indicators","Players on radar",null,"All","Friends","None"));
        r.add(toggle("friend_indicators","Indicators","Friendly indicators",null));
        r.add(choice("goal_radar","Indicators","Objective indicator",null,new int[]{0,1,2},"Native option 0","Native option 1","Native option 2"));
        r.add(toggle("generic_equipment","Weapons","Generic starting equipment",null));
        r.add(range("weapon_set","Weapons","Weapon set (native option)",null,0,13));
        r.add(toggle("infinite_grenades","Weapons","Infinite grenades",null));
        r.add(toggle("no_map_weapons","Weapons","No map weapons",null));
        r.add(enumeration("loadout","Weapons","Starting loadout",null,"Weapon set","Custom"));
        String[] weapons={"None","Random","Assault rifle","Pistol","Shotgun","Sniper rifle","Rocket launcher","Plasma pistol","Plasma rifle","Needler"};
        r.add(enumeration("primary_weapon","Weapons","Primary weapon",null,weapons));
        r.add(enumeration("secondary_weapon","Weapons","Secondary weapon",null,weapons));
        r.add(enumeration("friendly_fire","Teams","Friendly fire",null,"On","Off","Shields only","Explosives only"));
        r.add(range("friendly_fire_penalty","Teams","Teamkill penalty (seconds)",null,0,60));
        r.add(toggle("auto_team_balance","Teams","Native auto balance",null));
        r.add(toggle("force_team_spawns","Teams","Force team spawns",null));
        r.add(range("vehicle_respawn_time","Vehicles","Respawn (seconds; 0 never)",null,0,300));
        for(String side:new String[]{"red","blue"}) {
            r.add(choice(side+"_vehicle_set","Vehicles",side+" vehicle selection",null,new int[]{254,255},"Map placed (PC)","Custom counts"));
            for(String v:new String[]{"warthogs","ghosts","scorpions","rocket_warthogs","banshees","gun_turrets"})
                r.add(range(side+"_"+v,"Vehicles",side+" "+v.replace('_',' '),null,0,4));
        }
        r.add(toggle("assault","CTF","Assault",Family.CTF));
        r.add(toggle("reset_on_capture","CTF","Reset on capture",Family.CTF));
        r.add(toggle("flag_must_reset","CTF","Flag must reset",Family.CTF));
        r.add(toggle("flag_at_home_to_score","CTF","Flag at home to score",Family.CTF));
        r.add(range("single_flag_time","CTF","Single flag time (seconds)",Family.CTF,0,600));
        r.add(toggle("kills_score","CTF","Kills also score",Family.CTF));
        r.add(range("points_per_capture","CTF","Points per capture",Family.CTF,5,100));
        r.add(toggle("no_death_bonus","Slayer","No death bonus",Family.SLAYER));
        r.add(toggle("no_kill_penalty","Slayer","No kill penalty",Family.SLAYER));
        r.add(toggle("kill_in_order","Slayer","Kill in order",Family.SLAYER));
        r.add(toggle("moving_hill","King","Moving hill",Family.KING));
        r.add(choice("multi_hill","King","Simultaneous hills",Family.KING,new int[]{0,2,3,4,5},"One","Two","Three","Four","Five"));
        r.add(toggle("random_start","Oddball","Random start",Family.ODDBALL));
        r.add(toggle("ball_spawn_delay","Oddball","Ball spawn delay",Family.ODDBALL));
        r.add(range("ball_spawn_count","Oddball","Ball count",Family.ODDBALL,1,16));
        for(String key:new String[]{"speed_with_ball","trait_with_ball","trait_without_ball","ball_type"})
            r.add(range(key,"Oddball",key.replace('_',' ')+" (native option)",Family.ODDBALL,0,key.startsWith("trait")?3:2));
        r.add(range("race_type","Race","Race type (native option)",Family.RACE,0,2));
        r.add(range("team_scoring","Race","Team scoring (native option)",Family.RACE,0,2));
        r.add(range("infection_initial_zombies","Infection","Initial zombies",Family.INFECTION,1,16));
        r.add(range("infection_zombie_speed","Infection","Zombie speed (%)",Family.INFECTION,100,200));
        r.add(range("infection_skull_damage","Infection","Skull damage (%)",Family.INFECTION,100,250));
        r.add(range("infection_respawn_time","Infection","Zombie respawn (seconds)",Family.INFECTION,1,30));
        r.add(range("infection_countdown","Infection","Infection countdown (seconds)",Family.INFECTION,0,30));
        r.add(toggle("infection_zombie_shields","Infection","Zombie shields",Family.INFECTION));
        r.add(enumeration("gun_game_order","Gun Game","Weapon order",Family.GUN_GAME,"Strongest first","Weakest first","Random"));
        r.add(toggle("gun_game_melee_demotes","Gun Game","Melee demotes victim",Family.GUN_GAME));
        r.add(toggle("killstreak_radio","Rewards","Killstreak announcements",null));
        RULES=Collections.unmodifiableList(r);
    }
    static Rule rule(String key) { for(Rule r:RULES) if(r.key.equals(key)) return r;return null; }
    static String checkedName(String name) {
        if(name==null) throw new IllegalArgumentException("Name required");
        String n=name.trim();
        if(n.length()==0||n.length()>64||n.chars().anyMatch(c->Character.isISOControl(c)))
            throw new IllegalArgumentException("Use a name of 1–64 printable characters");
        return n;
    }
    static String newId(){return UUID.randomUUID().toString();}
    static String checkedId(String id) {
        if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("Invalid preset identifier");
        return id;
    }
    static String checkedMap(String id) {
        if(id!=null&&STOCK.contains(id)) return id;
        if(id!=null&&id.startsWith("ce:")) {
            String n=id.substring(3);
            if(n.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,50}")&&!n.contains("..")&&
                !Arrays.asList("bitmaps","sounds","loc","con","nul","prn","aux").contains(n.toLowerCase(Locale.ROOT))) return id;
        }
        throw new IllegalArgumentException("Choose a valid multiplayer map");
    }
    static final class Preset {
        final String id,name,base;
        final int revision;
        final Map<String,Integer> overrides;
        Preset(String id,String name,String base,int revision,Map<String,Integer> rules) {
            if(baseIndex(base)<0||revision<0) throw new IllegalArgumentException("Invalid mode");
            if(id.isEmpty()) {
                if(revision!=0||!rules.isEmpty()||!BASE_LABELS[baseIndex(base)].equals(name))
                    throw new IllegalArgumentException("Standard modes cannot be modified");
            } else {checkedId(id);if(revision<1)throw new IllegalArgumentException("Invalid revision");}
            this.id=id;this.name=checkedName(name);this.base=base;this.revision=revision;
            LinkedHashMap<String,Integer> copy=new LinkedHashMap<>();
            for(Map.Entry<String,Integer> e:rules.entrySet()) {
                Rule r=rule(e.getKey());
                if(r==null||!r.available(base)||e.getValue()==null||!r.accepts(e.getValue()))
                    throw new IllegalArgumentException("Invalid rule: "+e.getKey());
                copy.put(r.key,e.getValue());
            }
            overrides=Collections.unmodifiableMap(copy);
        }
        static Preset standard(String base){int i=baseIndex(base);if(i<0)throw new IllegalArgumentException("Unknown mode");return new Preset("",BASE_LABELS[i],base,0,Collections.emptyMap());}
        boolean teams(){return overrides.containsKey("teams")?overrides.get("teams")!=0:teamDefault(base);}
        Preset saveAs(String name,Map<String,Integer> rules){return new Preset(newId(),name,base,1,rules);}
        Preset revise(String name,Map<String,Integer> rules){
            if(id.isEmpty())return saveAs(name,rules);
            if(revision==Integer.MAX_VALUE)throw new IllegalArgumentException("Revision limit");
            return new Preset(id,name,base,revision+1,rules);
        }
    }
    static final class Allocation {
        final int red,blue,ffa;
        final String warning;
        final boolean valid;
        Allocation(int r,int b,int f,String w){this(r,b,f,w,true);}
        Allocation(int r,int b,int f,String w,boolean valid){red=r;blue=b;ffa=f;warning=w;this.valid=valid;}
        int bots(){return red+blue+ffa;}
    }
    static final class Bots {
        final Assignment assignment;
        final int count,difficulty,red,blue,extra;
        final Side extraSide;
        Bots(Assignment a,int count,int difficulty,int red,int blue,int extra,Side side) {
            if(a==null||side==null||count<0||count>MAX_LAN_BOTS||difficulty<0||difficulty>=DIFFICULTIES.length||
                red<0||blue<0||red>MAX_LAN_BOTS||blue>MAX_LAN_BOTS||red+blue>MAX_LAN_BOTS||extra<0||
                extra>MAX_LAN_BOTS||count+extra>MAX_LAN_BOTS||
                (a!=Assignment.BALANCED&&extra!=0)) throw new IllegalArgumentException("Invalid bot settings");
            this.assignment=a;this.count=count;this.difficulty=difficulty;
            this.red=red;this.blue=blue;this.extra=extra;this.extraSide=side;
        }
        static Bots balanced(int count,int skill){return new Bots(Assignment.BALANCED,count,skill,0,0,0,Side.BLUE);}
        static Bots ffa(int count,int skill){return new Bots(Assignment.FREE_FOR_ALL,count,skill,0,0,0,Side.BLUE);}
        int requested(){return assignment==Assignment.MANUAL?red+blue:count+extra;}
        Bots forMode(boolean teams){
            if(!teams) return ffa(requested(),difficulty);
            if(assignment==Assignment.FREE_FOR_ALL)return balanced(count,difficulty);
            return this;
        }
        Allocation resolve(boolean teams,int humansRed,int humansBlue,int humansFfa,int maxBots,int maxPlayers) {
            if(humansRed<0||humansBlue<0||humansFfa<0||humansRed>MAX_PLAYERS||humansBlue>MAX_PLAYERS||humansFfa>MAX_PLAYERS||maxBots<0||maxBots>MAX_LAN_BOTS||
                maxPlayers<1||maxPlayers>MAX_PLAYERS)throw new IllegalArgumentException("Invalid roster");
            if(teams&&humansFfa!=0||!teams&&(humansRed!=0||humansBlue!=0))
                throw new IllegalArgumentException("Roster does not match game type");
            int human=humansRed+humansBlue+humansFfa;
            if(human>maxPlayers)throw new IllegalArgumentException("Too many humans");
            Bots p=forMode(teams);
            int requested=p.requested();
            if(requested>maxBots||human+requested>maxPlayers)
                return new Allocation(0,0,0,"Requested bots exceed session capacity",false);
            if(!teams)return new Allocation(0,0,requested,"");
            if(p.assignment==Assignment.MANUAL)return new Allocation(p.red,p.blue,0,"");
            if(p.assignment==Assignment.BOTS_VS_PLAYERS) {
                if(humansRed>0&&humansBlue>0)return new Allocation(0,0,0,"Put all humans on one team first",false);
                return humansBlue>0?new Allocation(p.count,0,0,""):new Allocation(0,p.count,0,"");
            }
            int r=0,b=0;
            for(int i=0;i<p.count;i++) {
                if(humansRed+r<=humansBlue+b)r++;else b++;
            }
            String warning=Math.abs(humansRed+r-humansBlue-b)>1?"Not enough bots to balance teams":"";
            if(p.extraSide==Side.RED)r+=p.extra;else b+=p.extra;
            return new Allocation(r,b,0,warning);
        }
        String description() {
            String mode=assignment==Assignment.MANUAL?"Red "+red+" / Blue "+blue:
                assignment==Assignment.BOTS_VS_PLAYERS?count+" bots vs players":
                assignment==Assignment.FREE_FOR_ALL?count+" bots":
                count+" balanced"+(extra>0?" +"+extra+" "+extraSide.name().toLowerCase(Locale.ROOT):"");
            return mode+" · "+DIFFICULTIES[difficulty];
        }
    }
    static final class Entry {
        final String map;
        final Preset preset;
        final Bots bots;
        final boolean killstreak;
        Entry(String map,Preset preset,Bots bots,boolean killstreak) {
            this.map=checkedMap(map);this.preset=Objects.requireNonNull(preset);
            this.bots=Objects.requireNonNull(bots).forMode(preset.teams());this.killstreak=killstreak;
        }
        Entry withPreset(Preset p){return new Entry(map,p,bots,killstreak);}
    }
    static final class Playlist {
        final String id,name;
        final List<Entry> entries;
        final boolean shuffle,loop;
        Playlist(String id,String name,List<Entry> entries,boolean shuffle,boolean loop) {
            this.id=checkedId(id);this.name=checkedName(name);
            if(entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("Playlist is full");
            ArrayList<Entry> copy=new ArrayList<>(entries);for(Entry e:copy)Objects.requireNonNull(e);
            this.entries=Collections.unmodifiableList(copy);this.shuffle=shuffle;this.loop=loop;
        }
        Playlist reorder(int from,int to) {
            if(from<0||from>=entries.size()||to<0||to>=entries.size())throw new IllegalArgumentException("Invalid playlist position");
            ArrayList<Entry> copy=new ArrayList<>(entries);Entry e=copy.remove(from);copy.add(to,e);
            return new Playlist(id,name,copy,shuffle,loop);
        }
    }
    /** A session cursor is independent of the editable playlist. Every entry
     * occurs once per pass, and a looping shuffle cannot immediately repeat
     * the previous entry when there is more than one entry. */
    static final class Rotation {
        private final Playlist list; private final Random random;
        private final ArrayList<Integer> order=new ArrayList<>();
        private int position,last=-1;
        Rotation(Playlist p,Random rng){list=p;random=Objects.requireNonNull(rng);begin();}
        private void begin(){
            order.clear();for(int i=0;i<list.entries.size();i++)order.add(i);
            if(list.shuffle){
                Collections.shuffle(order,random);
                if(order.size()>1&&order.get(0)==last)Collections.swap(order,0,1+random.nextInt(order.size()-1));
            }
            position=0;
        }
        Entry next(){
            if(order.isEmpty())return null;
            if(position==order.size()){if(!list.loop)return null;begin();}
            last=order.get(position++);return list.entries.get(last);
        }
    }
    static final class Library {
        final Entry quick;final List<Preset> presets;final List<Playlist> playlists;
        final boolean playlistMode;final String playlistId;
        Library(Entry quick,List<Preset> presets,List<Playlist> lists,boolean playlistMode,String playlistId){
            this.quick=Objects.requireNonNull(quick);
            if(presets.size()>MAX_PRESETS||lists.size()>MAX_PLAYLISTS)throw new IllegalArgumentException("Workshop library is full");
            Set<String> ids=new HashSet<>();for(Preset p:presets)if(p.id.isEmpty()||!ids.add(p.id))throw new IllegalArgumentException("Duplicate custom mode");
            ids.clear();for(Playlist p:lists)if(!ids.add(p.id))throw new IllegalArgumentException("Duplicate playlist");
            this.presets=Collections.unmodifiableList(new ArrayList<>(presets));
            this.playlists=Collections.unmodifiableList(new ArrayList<>(lists));
            this.playlistMode=playlistMode;
            if(!playlistId.isEmpty()&&!ids.contains(playlistId))throw new IllegalArgumentException("Unknown playlist");
            this.playlistId=playlistId;
        }
        static Library empty(){return new Library(new Entry("bloodgulch",Preset.standard("team_slayer"),Bots.balanced(8,2),true),Collections.emptyList(),Collections.emptyList(),false,"");}
        Library withQuick(Entry e){return new Library(e,presets,playlists,playlistMode,playlistId);}
        Library select(boolean mode,String id){return new Library(quick,presets,playlists,mode,id);}
        Library put(Preset p){
            if(p.id.isEmpty())throw new IllegalArgumentException("Standard modes cannot be modified");
            ArrayList<Preset> copy=new ArrayList<>(presets);
            for(int i=0;i<copy.size();i++){
                Preset old=copy.get(i);
                if(old.id.equals(p.id)){
                    if(!old.base.equals(p.base)||p.revision<=old.revision)throw new IllegalArgumentException("Preset revision is stale");
                    copy.set(i,p);return new Library(quick,copy,playlists,playlistMode,playlistId);
                }
            }
            copy.add(p);return new Library(quick,copy,playlists,playlistMode,playlistId);
        }
        Library put(Playlist p){ArrayList<Playlist> copy=new ArrayList<>(playlists);int i=0;while(i<copy.size()&&!copy.get(i).id.equals(p.id))i++;if(i==copy.size())copy.add(p);else copy.set(i,p);return new Library(quick,presets,copy,true,p.id);}
        Playlist selected(){for(Playlist p:playlists)if(p.id.equals(playlistId))return p;return null;}
    }
    private static final int MAGIC=0x48575331; // HWS1
    static Library read(File dir)throws IOException {
        Path file=new File(dir,"match-workshop-v1.bin").toPath();
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return Library.empty();
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_BYTES)throw new IOException("Invalid workshop file");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
            if(in.readInt()!=MAGIC)throw new IOException("Unknown workshop version");
            Entry quick=readEntry(in);
            int n=count(in,MAX_PRESETS);ArrayList<Preset> presets=new ArrayList<>();for(int i=0;i<n;i++)presets.add(readPreset(in));
            int m=count(in,MAX_PLAYLISTS);ArrayList<Playlist> lists=new ArrayList<>();
            for(int i=0;i<m;i++){
                String id=in.readUTF(),name=in.readUTF();boolean shuffle=in.readBoolean(),loop=in.readBoolean();
                int size=count(in,MAX_ENTRIES);ArrayList<Entry> entries=new ArrayList<>();
                for(int j=0;j<size;j++)entries.add(readEntry(in));
                lists.add(new Playlist(id,name,entries,shuffle,loop));
            }
            Library out=new Library(quick,presets,lists,in.readBoolean(),in.readUTF());
            if(in.read()!=-1)throw new IOException("Unexpected workshop data");
            return out;
        } catch(IllegalArgumentException|NullPointerException invalid){throw new IOException("Invalid saved workshop",invalid);}
    }
    static void write(File dir,Library library)throws IOException {
        if(!Files.isDirectory(dir.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Workshop folder unavailable");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeInt(MAGIC);writeEntry(out,library.quick);out.writeInt(library.presets.size());
            for(Preset p:library.presets)writePreset(out,p);
            out.writeInt(library.playlists.size());
            for(Playlist p:library.playlists){
                out.writeUTF(p.id);out.writeUTF(p.name);out.writeBoolean(p.shuffle);out.writeBoolean(p.loop);out.writeInt(p.entries.size());
                for(Entry e:p.entries){writeEntry(out,e);if(bytes.size()>MAX_BYTES)throw new IOException("Workshop storage limit reached");}
            }
            out.writeBoolean(library.playlistMode);out.writeUTF(library.playlistId);
        }
        if(bytes.size()>MAX_BYTES)throw new IOException("Workshop storage limit reached");
        Path target=new File(dir,"match-workshop-v1.bin").toPath(),temp=Files.createTempFile(dir.toPath(),"workshop-",".tmp");
        try{
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Unsafe workshop file");
            try(FileOutputStream out=new FileOutputStream(temp.toFile())){bytes.writeTo(out);out.getFD().sync();}
            try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
    private static int count(DataInputStream in,int max)throws IOException{int n=in.readInt();if(n<0||n>max)throw new IOException("Workshop count out of range");return n;}
    private static void writePreset(DataOutputStream out,Preset p)throws IOException{
        out.writeUTF(p.id);out.writeUTF(p.name);out.writeUTF(p.base);out.writeInt(p.revision);out.writeInt(p.overrides.size());
        for(Map.Entry<String,Integer> e:p.overrides.entrySet()){out.writeUTF(e.getKey());out.writeInt(e.getValue());}
    }
    private static Preset readPreset(DataInputStream in)throws IOException{
        String id=in.readUTF(),name=in.readUTF(),base=in.readUTF();int rev=in.readInt(),n=count(in,RULES.size());
        LinkedHashMap<String,Integer> rules=new LinkedHashMap<>();
        for(int i=0;i<n;i++){String key=in.readUTF();int value=in.readInt();if(rules.put(key,value)!=null)throw new IOException("Duplicate rule");}
        return new Preset(id,name,base,rev,rules);
    }
    static void writeEntry(DataOutputStream out,Entry e)throws IOException{
        out.writeUTF(e.map);writePreset(out,e.preset);out.writeUTF(e.bots.assignment.name());
        out.writeInt(e.bots.count);out.writeInt(e.bots.difficulty);out.writeInt(e.bots.red);out.writeInt(e.bots.blue);
        out.writeInt(e.bots.extra);out.writeUTF(e.bots.extraSide.name());out.writeBoolean(e.killstreak);
    }
    static Entry readEntry(DataInputStream in)throws IOException{
        String map=in.readUTF();Preset p=readPreset(in);
        Bots b=new Bots(Assignment.valueOf(in.readUTF()),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),Side.valueOf(in.readUTF()));
        return new Entry(map,p,b,in.readBoolean());
    }
}
