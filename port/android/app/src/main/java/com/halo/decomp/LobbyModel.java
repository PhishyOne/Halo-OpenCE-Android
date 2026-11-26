package com.halo.decomp;

import java.io.*;
import java.util.*;

/** Host-owned launcher pregame state. Session identifiers are ephemeral,
 * separate from the career identity. Guests can mutate only their own row.
 * A connected pregame lobby is not yet an engine/network-game session. */
final class LobbyModel {
    static final int MAX_HUMANS=16, NO_TEAM=-1, RED=0, BLUE=1;
    static final long GUEST_EXPIRY_MS=20000, RETURN_TIMEOUT_MS=60000;
    static final int INTERMISSION_SECONDS=5;
    static final String[] COLORS={"White","Black","Red","Blue","Gray","Yellow","Green","Orange","Purple","Cyan","Pink","Brown"};
    static final int[] RGB={0xffeeeeee,0xff303030,0xffd95757,0xff568fdf,0xffa3a3a3,0xffeacb47,0xff55bb6e,0xffde9345,0xffad7dde,0xff58c9d9,0xffdf81b6,0xff94734f};

    static String name(String raw){
        if(raw==null)throw new IllegalArgumentException("Player name required");
        String n=raw.trim();
        if(n.isEmpty()||n.length()>16||n.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Player name must be 1–16 printable characters");
        return n;
    }
    static int color(int value){if(value<0||value>=COLORS.length)throw new IllegalArgumentException("Invalid color");return value;}
    static int team(int value){if(value<NO_TEAM||value>BLUE)throw new IllegalArgumentException("Invalid team");return value;}
    static String token(){return UUID.randomUUID().toString();}
    static String validId(String id){return MatchWorkshop.checkedId(id);}
    static final class Member {
        final String id,name;final int color,team,requestedTeam;final boolean host,ready;
        Member(String id,String name,int color,int team,int request,boolean host,boolean ready){
            this.id=validId(id);this.name=name(name);this.color=color(color);
            this.team=team(team);requestedTeam=team(request);this.host=host;this.ready=ready;
        }
        Member appearance(String n,int c,boolean ready,int request){return new Member(id,n,c,team,request,host,ready);}
        Member assigned(int t){return new Member(id,name,color,t,NO_TEAM,host,false);}
        Member unready(){return appearance(name,color,false,NO_TEAM);}
    }
    static final class Plan {
        final String title;final boolean playlist,shuffle,loop;
        final List<MatchWorkshop.Entry> entries;
        Plan(String title,boolean playlist,boolean shuffle,boolean loop,List<MatchWorkshop.Entry> entries){
            this.title=MatchWorkshop.checkedName(title);
            if(entries.size()>MatchWorkshop.MAX_ENTRIES||!playlist&&entries.size()!=1)throw new IllegalArgumentException("Invalid match plan");
            this.playlist=playlist;this.shuffle=playlist&&shuffle;this.loop=playlist&&loop;
            this.entries=Collections.unmodifiableList(new ArrayList<>(entries));
            for(MatchWorkshop.Entry e:this.entries)Objects.requireNonNull(e);
        }
        static Plan from(MatchWorkshop.Library lib){
            if(!lib.playlistMode)return new Plan("Quick Match",false,false,false,Collections.singletonList(lib.quick));
            MatchWorkshop.Playlist p=lib.selected();
            return p==null?new Plan("No playlist selected",true,false,false,Collections.emptyList()):
                new Plan(p.name,true,p.shuffle,p.loop,p.entries);
        }
        MatchWorkshop.Entry first(){return entries.isEmpty()?null:entries.get(0);}
        MatchWorkshop.Entry entry(int index){return index<0?null:entries.get(index);}
    }
    static final class Snapshot {
        final String session,self;final long revision;final Plan plan;final List<Member> members;
        final String round, startMessage;
        final int startIndex;
        final boolean released;
        final boolean hostBack;
        final int nextIndex, secondsToNext;
        Snapshot(String session,String self,long revision,Plan plan,List<Member> members){
            this(session,self,revision,plan,members,"",-1,false,"",false,-1,-1);
        }
        Snapshot(String session,String self,long revision,Plan plan,List<Member> members,
                String round,int startIndex,boolean released,String message,boolean hostBack,int nextIndex,int secondsToNext){
            if(round.isEmpty() ? (startIndex!=-1||released) : (startIndex<0||startIndex>=plan.entries.size()))
                throw new IllegalArgumentException("Invalid launch phase");
            if(message.length()>180)throw new IllegalArgumentException("Invalid launch status");
            if((hostBack&&!released)||nextIndex < -1||nextIndex>=plan.entries.size()||
                    (nextIndex>=0&&(!round.isEmpty()||!plan.playlist))||secondsToNext < -1||
                    secondsToNext>INTERMISSION_SECONDS||(secondsToNext>=0&&nextIndex<0))
                throw new IllegalArgumentException("Invalid playlist status");
            this.hostBack=hostBack;this.nextIndex=nextIndex;this.secondsToNext=secondsToNext;
            this.round=round.isEmpty()?"":validId(round);
            this.startIndex=startIndex;this.released=released;startMessage=message;
            this.session=validId(session);this.self=validId(self);
            if(revision<0||members.isEmpty()||members.size()>MAX_HUMANS)throw new IllegalArgumentException("Invalid roster");
            this.revision=revision;this.plan=Objects.requireNonNull(plan);
            Set<String> ids=new HashSet<>();int hosts=0;boolean found=false;
            int humanSide=members.get(0).team;
            if(!members.get(0).host)throw new IllegalArgumentException("Missing host seat");
            for(Member m:members){
                if(!ids.add(m.id)||teams()!=(m.team!=NO_TEAM)||
                        (versus()&&m.team!=humanSide)||((m.host||!teams()||versus())&&m.requestedTeam!=NO_TEAM))
                    throw new IllegalArgumentException("Invalid member");
                if(m.host)hosts++;if(m.id.equals(self))found=true;
            }
            if(hosts!=1||!found)throw new IllegalArgumentException("Incomplete roster");
            this.members=Collections.unmodifiableList(new ArrayList<>(members));
        }
        int entryIndex(){
            if(!round.isEmpty())return startIndex;
            return nextIndex>=0?nextIndex:plan.entries.isEmpty()?-1:0;
        }
        MatchWorkshop.Entry entry(){return plan.entry(entryIndex());}
        boolean teams(){return entry()==null||entry().preset.teams();}
        boolean versus(){return entry()!=null&&entry().bots.assignment==MatchWorkshop.Assignment.BOTS_VS_PLAYERS;}
        Member me(){for(Member m:members)if(m.id.equals(self))return m;throw new IllegalStateException();}
        List<Member> notReady(){
            ArrayList<Member> result=new ArrayList<>();
            for(Member m:members)if(!m.host&&!m.ready)result.add(m);
            return Collections.unmodifiableList(result);
        }
        MatchWorkshop.Allocation allocation(){
            int red=0,blue=0,ffa=0;
            for(Member m:members){if(m.team==RED)red++;else if(m.team==BLUE)blue++;else ffa++;}
            MatchWorkshop.Entry e=entry();
            MatchWorkshop.Bots b=e==null?MatchWorkshop.Bots.balanced(0,2):e.bots;
            return b.resolve(teams(),red,blue,ffa,MatchWorkshop.MAX_LAN_BOTS,MatchWorkshop.MAX_PLAYERS);
        }
    }
    /** A host's start dialog is a review of one exact plan and actual
     * roster, not a promise that each guest will mark ready. */
    static final class StartReview {
        private final LobbyModel owner;
        private final long generation;
        final String session;
        final Plan plan;
        final int entryIndex;
        final MatchWorkshop.Entry entry;
        final List<Member> members, notReady;
        final MatchWorkshop.Allocation allocation;
        final String blocker;
        private StartReview(LobbyModel owner,Snapshot s,int entryIndex) {
            this.owner=owner;generation=owner==null?-1:owner.startGeneration;
            session=s.session;plan=s.plan;this.entryIndex=entryIndex;
            if(entryIndex!=s.entryIndex())throw new IllegalArgumentException("Roster belongs to a different match");
            entry=s.entry();
            notReady=s.notReady();
            members=s.members;allocation=s.allocation();
            int red=0,blue=0;
            for(Member member:members){
                if(member.team==RED)red++;
                else if(member.team==BLUE)blue++;
            }
            if(entry==null)blocker="Select a match or a nonempty playlist first.";
            else if(!allocation.valid)blocker=allocation.warning;
            else if(members.size()+allocation.bots()<2)blocker="At least two participants are needed.";
            else if(s.teams()&&(red+allocation.red==0||blue+allocation.blue==0))
                blocker="Both teams need at least one participant.";
            else blocker="";
        }
        static StartReview selected(Snapshot snapshot){
            if(snapshot.round.isEmpty())throw new IllegalStateException("No match is starting");
            return new StartReview(null,snapshot,snapshot.startIndex);
        }
        static StartReview upcoming(Snapshot snapshot){
            return new StartReview(null,snapshot,snapshot.nextIndex);
        }
        boolean needsOverride(){return !notReady.isEmpty();}
    }

    /** Immutable authorized handoff. The native adapter still has to
     * acknowledge actual map, player mapping, and roster before gameplay. */
    static final class StartTicket {
        final String id,session;
        final MatchWorkshop.Entry entry;
        final int entryIndex;
        final List<Member> members,notReady;
        final MatchWorkshop.Allocation allocation;
        final boolean readinessOverridden;
        private StartTicket(StartReview r,boolean override){
            id=token();session=r.session;entry=r.entry;entryIndex=r.entryIndex;
            members=r.members;notReady=r.notReady;allocation=r.allocation;
            readinessOverridden=override&&!r.notReady.isEmpty();
        }
    }
    static final class StartChanged extends IllegalStateException {
        StartChanged(){super("Match settings or players changed. Review Start again.");}
    }
    static final class StartNotReady extends IllegalStateException {
        final StartReview review;
        StartNotReady(StartReview review){super("Some players are not ready.");this.review=review;}
    }
    private static boolean sameStartRoster(List<Member> a,List<Member> b){
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++){
            Member x=a.get(i),y=b.get(i);
            if(!x.id.equals(y.id)||!x.name.equals(y.name)||x.color!=y.color||x.team!=y.team||x.host!=y.host)
                return false;
        }
        return true;
    }
    /** Reviewing or retrying never consumes a playlist entry. */
    synchronized StartReview reviewStart(long now,Random random){
        expire(now);
        if(plan.playlist&&rotation==null){
            rotation=new MatchWorkshop.Rotation(plan.entries,plan.shuffle,plan.loop,random);
            changed();
        }
        int index=rotation==null?0:rotation.peekIndex();
        return new StartReview(this,snapshot(host.id),index);
    }
    synchronized StartTicket authorizeStart(StartReview review,boolean override,long now){
        expire(now);
        if(review==null||review.owner!=this||review.generation!=startGeneration||review.plan!=plan||!review.session.equals(session)||
                (plan.playlist&&(rotation==null||review.entryIndex!=rotation.peekIndex())))
            throw new StartChanged();
        StartReview current=new StartReview(this,snapshot(host.id),review.entryIndex);
        if(!sameStartRoster(review.members,current.members))throw new StartChanged();
        if(!current.blocker.isEmpty())throw new IllegalStateException(current.blocker);
        // Ready changes are deliberately NOT a stale review: a guest cannot
        // veto a host who has already confirmed Start Anyway.
        if(current.needsOverride()&&!override)throw new StartNotReady(current);
        return new StartTicket(current,override);
    }

    private static final class Guest {
        final String secret;Member row;long seen;String localAddress="",peerAddress="";
        Guest(String secret,Member row,long now){this.secret=secret;this.row=row;seen=now;}
    }
    static final class Joined {
        final String secret;final Snapshot snapshot;
        Joined(String token,Snapshot snapshot){secret=validId(token);this.snapshot=snapshot;}
    }
    private final String session=token();
    private final LinkedHashMap<String,Guest> guests=new LinkedHashMap<>();
    private Member host;private Plan plan;private long revision=1;
    // Rows retain each player's ordinary team while FFA or Humans vs Bots is displayed.
    private int versusSide=RED;
    LobbyModel(String name,int color,Plan plan){
        this.plan=Objects.requireNonNull(plan);
        host=new Member(token(),name,color,RED,NO_TEAM,true,false);
    }
    private StartTicket launch;
    private final Set<String> prepared=new HashSet<>();
    private final Set<String> returned=new HashSet<>(), departed=new HashSet<>();
    private MatchWorkshop.Rotation rotation;
    private boolean released,hostBack;
    private long prepareBegan, hostReturnTime, nextAt, startGeneration;
    private int secondsToNext=-1;
    private String interruption="";
    private String startMessage="";
    synchronized Snapshot beginStart(StartReview review,boolean override,long now){
        if(launch!=null)throw new IllegalStateException("A match is already starting.");
        launch=authorizeStart(review,override,now);
        for(Guest g:guests.values()){
            Member m=g.row;
            if(m.requestedTeam!=NO_TEAM)g.row=m.appearance(m.name,m.color,m.ready,NO_TEAM);
        }
        startGeneration++;
        prepareBegan=now;prepared.clear();released=guests.isEmpty();startMessage="";secondsToNext=-1;
        changed();
        return snapshot(host.id);
    }
    synchronized Snapshot acknowledge(String secret,String round,boolean accepted,long now){
        Guest guest=require(secret,now);
        if(launch==null||!launch.id.equals(round)||released)return snapshot(guest.row.id);
        if(!accepted)resetStart(guest.row.name+" could not open the selected match. Check the map on that phone.",now);
        else if(prepared.add(guest.row.id)){
            released=prepared.size()==guests.size();changed();
        }
        return snapshot(guest.row.id);
    }
    private void clearRound(String message,long now){
        startGeneration++;
        launch=null;released=false;hostBack=false;prepared.clear();returned.clear();
        guests.values().removeIf(g->departed.contains(g.row.id));departed.clear();
        interruption="";secondsToNext=-1;
        startMessage=message==null?"Match interrupted.":message.substring(0,Math.min(message.length(),180));
        for(Guest g:guests.values())g.seen=now;
        changed();
    }
    synchronized void resetStart(String message,long now){
        clearRound(message,now);clearReady();
    }
    synchronized Snapshot hostReturned(String round,boolean completed,String message,long now){
        if(released&&launch.id.equals(round)&&!hostBack){
            hostBack=true;hostReturnTime=now;
            if(!completed)interruption=message==null||message.isEmpty()?"Match interrupted.":message;
            finishReturns(now);
        }
        return snapshot(host.id);
    }
    synchronized Snapshot guestReturned(String secret,String round,boolean completed,long now){
        Guest guest=require(secret,now);
        if(released&&launch.id.equals(round)&&returned.add(guest.row.id)){
            if(!completed)interruption=guest.row.name+" did not confirm the match result.";
            finishReturns(now);
        }
        return snapshot(guest.row.id);
    }
    private void finishReturns(long now){
        changed();
        if(!hostBack)return;
        if(!interruption.isEmpty()){resetStart(interruption,now);return;}
        if(returned.size()!=guests.size())return;
        if(rotation==null){resetStart("Match complete.",now);return;}
        rotation.next();
        int next=rotation.peekIndex();
        if(next<0){rotation=null;resetStart("Playlist complete.",now);return;}
        clearRound("",now); // Carry the original group's Ready choices into its next game.
        secondsToNext=INTERMISSION_SECONDS;nextAt=now+1000L*INTERMISSION_SECONDS;
    }
    synchronized StartReview dueStart(long now){
        expire(now);
        return secondsToNext==0?new StartReview(this,snapshot(host.id),rotation.peekIndex()):null;
    }
    private void changed(){if(revision<Long.MAX_VALUE)revision++;}
    private void clearReady(){
        host=host.unready();for(Guest g:guests.values())g.row=g.row.unready();
    }
    synchronized void expire(long now){
        if(released){
            if(hostBack&&now-hostReturnTime>=RETURN_TIMEOUT_MS){
                String waiting=waitingFor(returned);
                resetStart("Timed out waiting for "+waiting+" to return.",now);
            }
            return;
        }
        if(guests.values().removeIf(g->now-g.seen>GUEST_EXPIRY_MS)){
            if(launch!=null||secondsToNext>=0)resetStart("A player disconnected before launch.",now);
            else {clearReady();changed();}
        }else if(launch!=null&&now-prepareBegan>45000){
            resetStart("Timed out checking the match on all phones.",now);
        }else if(secondsToNext>=0){
            int remaining=(int)Math.max(0,(nextAt-now+999)/1000);
            if(remaining!=secondsToNext){secondsToNext=remaining;changed();}
        }
    }
    private int nextTeam(){
        int red=host.team==RED?1:0,blue=host.team==BLUE?1:0;
        for(Guest g:guests.values()){if(g.row.team==RED)red++;if(g.row.team==BLUE)blue++;}
        return red<=blue?RED:BLUE;
    }
    synchronized Joined join(String name,int color,long now){
        expire(now);
        if(launch!=null)throw new IllegalArgumentException("A match is already starting or in play");
        if(guests.size()>=MAX_HUMANS-1)throw new IllegalArgumentException("Lobby is full");
        String secret=token();
        Guest g=new Guest(secret,new Member(token(),name,color,nextTeam(),NO_TEAM,false,false),now);
        guests.put(secret,g);
        if(secondsToNext>=0)resetStart("A player joined. Review the roster before resuming.",now);
        else {clearReady();changed();}
        return new Joined(secret,snapshot(g.row.id));
    }
    synchronized Joined join(String name,int color,long now,String localAddress,String peerAddress){
        Joined joined=join(name,color,now);
        guests.get(joined.secret).localAddress=localAddress;
        guests.get(joined.secret).peerAddress=peerAddress;
        return joined;
    }
    synchronized String nativeInterface(String fallback){
        String chosen="";
        for(Guest g:guests.values()){
            if(g.localAddress.isEmpty())continue;
            if(!chosen.isEmpty()&&!chosen.equals(g.localAddress))
                throw new IllegalStateException("All players must use the same host Wi-Fi or hotspot interface.");
            chosen=g.localAddress;
        }
        return chosen.isEmpty()?fallback:chosen;
    }
    synchronized List<String> nativePeers(){
        List<String> peers=new ArrayList<>();
        for(Guest g:guests.values())peers.add(g.peerAddress);
        return peers;
    }
    private Guest require(String secret,long now){
        expire(now);Guest g=guests.get(secret);
        if(g==null)throw new IllegalArgumentException("Lobby membership expired");
        g.seen=now;return g;
    }
    synchronized Snapshot poll(String secret,long now){return snapshot(require(secret,now).row.id);}
    synchronized Snapshot guestUpdate(String secret,String name,int color,boolean ready,int request,long knownRevision,long now){
        Guest g=require(secret,now);
        if(launch!=null||secondsToNext>=0||knownRevision!=revision)return snapshot(g.row.id);
        MatchWorkshop.Entry entry=activeEntry();
        if(request!=NO_TEAM&&entry!=null&&(!entry.preset.teams()||entry.bots.assignment==MatchWorkshop.Assignment.BOTS_VS_PLAYERS))
            throw new IllegalArgumentException("This match has no individual team changes");
        Member old=g.row;
        if(request==old.team)request=NO_TEAM;
        if(old.name.equals(name(name))&&old.color==color(color)&&old.ready==ready&&old.requestedTeam==request)
            return snapshot(old.id);
        g.row=old.appearance(name,color,ready,request);changed();return snapshot(g.row.id);
    }
    synchronized void leave(String secret,long now){
        Guest g=require(secret,now);
        if(released){
            // Keep native seats stable until the host returns, even if a guest leaves early.
            departed.add(g.row.id);returned.add(g.row.id);
            interruption=g.row.name+" left the lobby.";finishReturns(now);return;
        }
        guests.remove(secret);
        if(launch!=null||secondsToNext>=0)resetStart(g.row.name+" left before launch.",now);
        else {clearReady();changed();}
    }
    synchronized Snapshot hostSnapshot(long now){expire(now);return snapshot(host.id);}
    synchronized void hostAppearance(String name,int color,boolean ready){
        if(launch!=null||secondsToNext>=0)return;
        host=host.appearance(name,color,ready,NO_TEAM);changed();
    }
    synchronized void setTeam(String id,int requested){
        if(launch!=null||secondsToNext>=0)return;
        MatchWorkshop.Entry e=activeEntry();
        if((e!=null&&!e.preset.teams())||requested==NO_TEAM)throw new IllegalArgumentException("No such team");
        team(requested);
        if(e!=null&&e.bots.assignment==MatchWorkshop.Assignment.BOTS_VS_PLAYERS){
            if(!host.id.equals(id))throw new IllegalArgumentException("Move all humans together in this mode");
            versusSide=requested;clearReady();changed();return;
        }
        if(host.id.equals(id)){host=host.assigned(requested);clearReady();changed();return;}
        for(Guest g:guests.values())if(g.row.id.equals(id)){g.row=g.row.assigned(requested);clearReady();changed();return;}
        throw new IllegalArgumentException("Player left");
    }
    synchronized void setPlan(Plan p){
        if(launch!=null||secondsToNext>=0)return;
        plan=Objects.requireNonNull(p);rotation=null;startMessage="";
        clearReady();changed();
    }
    private String waitingFor(Set<String> accounted){
        ArrayList<String> names=new ArrayList<>();
        for(Guest g:guests.values())if(!accounted.contains(g.row.id))names.add(g.row.name);
        return names.size()>3?names.size()+" phones":String.join(", ",names);
    }
    private int activeIndex(){
        if(launch!=null)return launch.entryIndex;
        return rotation!=null?rotation.peekIndex():plan.entries.isEmpty()?-1:0;
    }
    private MatchWorkshop.Entry activeEntry(){return plan.entry(activeIndex());}
    private Member displayed(Member row,MatchWorkshop.Entry entry){
        if(entry==null)return row;
        if(!entry.preset.teams())return new Member(row.id,row.name,row.color,NO_TEAM,NO_TEAM,row.host,row.ready);
        if(entry.bots.assignment==MatchWorkshop.Assignment.BOTS_VS_PLAYERS)
            return new Member(row.id,row.name,row.color,versusSide,NO_TEAM,row.host,row.ready);
        return row;
    }
    private Snapshot snapshot(String self){
        MatchWorkshop.Entry entry=activeEntry();
        List<Member> rows=new ArrayList<>();rows.add(displayed(host,entry));
        for(Guest g:guests.values())rows.add(displayed(g.row,entry));
        String message=startMessage;
        if(launch!=null&&!released)message="Checking match on "+waitingFor(prepared);
        if(hostBack)message="Waiting for "+waitingFor(returned)+" to return.";
        return new Snapshot(session,self,revision,plan,rows,launch==null?"":launch.id,
            launch==null?-1:launch.entryIndex,released,message,hostBack,
            launch==null&&rotation!=null?rotation.peekIndex():-1,secondsToNext);
    }
}
