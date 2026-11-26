package com.halo.decomp;

import java.io.*;
import java.util.*;

/** Host-owned launcher pregame state. Session identifiers are ephemeral,
 * separate from the career identity. Guests can mutate only their own row.
 * A connected pregame lobby is not yet an engine/network-game session. */
final class LobbyModel {
    static final int MAX_HUMANS=16, NO_TEAM=-1, RED=0, BLUE=1;
    static final long GUEST_EXPIRY_MS=20000;
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
        boolean teams(){return first()==null||first().preset.teams();}
        boolean versus(){return first()!=null&&first().bots.assignment==MatchWorkshop.Assignment.BOTS_VS_PLAYERS;}
    }
    static final class Snapshot {
        final String session,self;final long revision;final Plan plan;final List<Member> members;
        final String round, startMessage;
        final int startIndex;
        final boolean released;
        Snapshot(String session,String self,long revision,Plan plan,List<Member> members){
            this(session,self,revision,plan,members,"",-1,false,"");
        }
        Snapshot(String session,String self,long revision,Plan plan,List<Member> members,
                String round,int startIndex,boolean released,String message){
            if(round.isEmpty() ? (startIndex!=-1||released) : (startIndex<0||startIndex>=plan.entries.size()))
                throw new IllegalArgumentException("Invalid launch phase");
            if(message.length()>180)throw new IllegalArgumentException("Invalid launch status");
            this.round=round.isEmpty()?"":validId(round);
            this.startIndex=startIndex;this.released=released;startMessage=message;
            this.session=validId(session);this.self=validId(self);
            if(revision<0||members.isEmpty()||members.size()>MAX_HUMANS)throw new IllegalArgumentException("Invalid roster");
            this.revision=revision;this.plan=Objects.requireNonNull(plan);
            Set<String> ids=new HashSet<>();int hosts=0;boolean found=false;
            for(Member m:members){
                if(!ids.add(m.id)||plan.teams()!=(m.team!=NO_TEAM))throw new IllegalArgumentException("Invalid member");
                if(m.host)hosts++;if(m.id.equals(self))found=true;
            }
            if(hosts!=1||!found)throw new IllegalArgumentException("Incomplete roster");
            this.members=Collections.unmodifiableList(new ArrayList<>(members));
        }
        Member me(){for(Member m:members)if(m.id.equals(self))return m;throw new IllegalStateException();}
        List<Member> notReady(){
            ArrayList<Member> result=new ArrayList<>();
            for(Member m:members)if(!m.host&&!m.ready)result.add(m);
            return Collections.unmodifiableList(result);
        }
        MatchWorkshop.Allocation allocation(){
            int red=0,blue=0,ffa=0;
            for(Member m:members){if(m.team==RED)red++;else if(m.team==BLUE)blue++;else ffa++;}
            MatchWorkshop.Entry e=plan.first();
            MatchWorkshop.Bots b=e==null?MatchWorkshop.Bots.balanced(0,2):e.bots;
            return b.resolve(plan.teams(),red,blue,ffa,MatchWorkshop.MAX_LAN_BOTS,MatchWorkshop.MAX_PLAYERS);
        }
    }
    /** A host's start dialog is a review of one exact plan and actual
     * roster, not a promise that each guest will mark ready. */
    static final class StartReview {
        private final LobbyModel owner;
        final String session;
        final Plan plan;
        final int entryIndex;
        final MatchWorkshop.Entry entry;
        final List<Member> members, notReady;
        final MatchWorkshop.Allocation allocation;
        final String blocker;
        private StartReview(LobbyModel owner,Snapshot s,int entryIndex) {
            this.owner=owner;session=s.session;plan=s.plan;this.entryIndex=entryIndex;
            entry=entryIndex<0?null:plan.entries.get(entryIndex);
            notReady=s.notReady();
            ArrayList<Member> rows=new ArrayList<>();
            int red=0,blue=0,ffa=0;
            boolean teams=entry!=null&&entry.preset.teams();
            boolean versus=entry!=null&&entry.bots.assignment==MatchWorkshop.Assignment.BOTS_VS_PLAYERS;
            int hostSide=RED;
            for(Member m:s.members)if(m.host&&m.team!=NO_TEAM)hostSide=m.team;
            for(Member m:s.members){
                int side;
                if(!teams)side=NO_TEAM;
                else if(versus&&(!s.plan.teams()||!s.plan.versus()))side=hostSide;
                else if(m.team!=NO_TEAM)side=m.team;
                else side=red<=blue?RED:BLUE;
                Member chosen=new Member(m.id,m.name,m.color,side,NO_TEAM,m.host,m.ready);
                rows.add(chosen);
                if(side==RED)red++;else if(side==BLUE)blue++;else ffa++;
            }
            members=Collections.unmodifiableList(rows);
            allocation=entry==null?new MatchWorkshop.Allocation(0,0,0,"No match selected",false):
                entry.bots.resolve(teams,red,blue,ffa,MatchWorkshop.MAX_LAN_BOTS,MatchWorkshop.MAX_PLAYERS);
            if(entry==null)blocker="Select a match or a nonempty playlist first.";
            else if(!allocation.valid)blocker=allocation.warning;
            else if(rows.size()+allocation.bots()<2)blocker="At least two participants are needed.";
            else if(teams&&(red+allocation.red==0||blue+allocation.blue==0))
                blocker="Both teams need at least one participant.";
            else blocker="";
        }
        static StartReview selected(Snapshot snapshot){
            if(snapshot.round.isEmpty())throw new IllegalStateException("No match is starting");
            return new StartReview(null,snapshot,snapshot.startIndex);
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
    /** Select the actual first match once. On confirmation use this same
     * entry, even for shuffle; never silently pick another map. */
    synchronized StartReview reviewStart(long now,Random random){
        expire(now);
        int index=plan.entries.isEmpty()?-1:plan.shuffle?Objects.requireNonNull(random).nextInt(plan.entries.size()):0;
        return new StartReview(this,snapshot(host.id),index);
    }
    synchronized StartTicket authorizeStart(StartReview review,boolean override,long now){
        expire(now);
        if(review==null||review.owner!=this||review.plan!=plan||!review.session.equals(session))
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
    LobbyModel(String name,int color,Plan plan){
        this.plan=Objects.requireNonNull(plan);
        host=new Member(token(),name,color,plan.teams()?RED:NO_TEAM,NO_TEAM,true,false);
    }
    private StartTicket launch;
    private final Set<String> prepared=new HashSet<>();
    private boolean released;
    private long prepareBegan;
    private String startMessage="";
    synchronized Snapshot beginStart(StartReview review,boolean override,long now){
        if(launch!=null)throw new IllegalStateException("A match is already starting.");
        launch=authorizeStart(review,override,now);
        prepareBegan=now;prepared.clear();released=guests.isEmpty();startMessage="";
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
    synchronized void resetStart(String message,long now){
        launch=null;released=false;prepared.clear();startMessage=message;
        for(Guest g:guests.values())g.seen=now;
        clearReady();changed();
    }
    private void changed(){if(revision<Long.MAX_VALUE)revision++;}
    private void clearReady(){
        host=host.unready();for(Guest g:guests.values())g.row=g.row.unready();
    }
    synchronized void expire(long now){
        if(released)return; // Native gameplay owns the phones until the host returns.
        if(guests.values().removeIf(g->now-g.seen>GUEST_EXPIRY_MS)){
            if(launch!=null)resetStart("A player disconnected before launch.",now);
            else {clearReady();changed();}
        }else if(launch!=null&&now-prepareBegan>45000){
            resetStart("Timed out checking the match on all phones.",now);
        }
    }
    private int nextTeam(){
        if(!plan.teams())return NO_TEAM;
        if(plan.versus())return host.team;
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
        guests.put(secret,g);clearReady();changed();
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
        if(launch!=null||knownRevision!=revision)return snapshot(g.row.id);
        if(!plan.teams()&&request!=NO_TEAM)throw new IllegalArgumentException("Free-for-all has no teams");
        Member old=g.row;
        if(request==old.team)request=NO_TEAM;
        if(old.name.equals(name(name))&&old.color==color(color)&&old.ready==ready&&old.requestedTeam==request)
            return snapshot(old.id);
        g.row=old.appearance(name,color,ready,request);changed();return snapshot(g.row.id);
    }
    synchronized void leave(String secret,long now){
        Guest g=require(secret,now);
        if(launch!=null&&released)return; // The frozen roster also serves retried release responses.
        guests.remove(secret);
        if(launch!=null)resetStart(g.row.name+" left before launch.",now);
        else {clearReady();changed();}
    }
    synchronized Snapshot hostSnapshot(long now){expire(now);return snapshot(host.id);}
    synchronized void hostAppearance(String name,int color,boolean ready){
        if(launch!=null)return;
        host=host.appearance(name,color,ready,NO_TEAM);changed();
    }
    synchronized void setTeam(String id,int requested){
        if(launch!=null)return;
        if(!plan.teams()||requested==NO_TEAM)throw new IllegalArgumentException("No such team");
        team(requested);
        if(host.id.equals(id)){host=host.assigned(requested);clearReady();changed();return;}
        for(Guest g:guests.values())if(g.row.id.equals(id)){g.row=g.row.assigned(requested);clearReady();changed();return;}
        throw new IllegalArgumentException("Player left");
    }
    synchronized void setPlan(Plan p){
        if(launch!=null)return;
        boolean was=plan.teams();plan=Objects.requireNonNull(p);
        if(!p.teams()){
            host=host.assigned(NO_TEAM);
            for(Guest g:guests.values())g.row=g.row.assigned(NO_TEAM);
        }else if(!was){
            host=host.assigned(RED);
            int n=1;
            for(Guest g:guests.values())g.row=g.row.assigned(p.versus()?RED:n++%2);
        }
        clearReady();changed();
    }
    private Snapshot snapshot(String self){
        List<Member> rows=new ArrayList<>();rows.add(host);for(Guest g:guests.values())rows.add(g.row);
        String message=startMessage;
        if(launch!=null&&!released){
            ArrayList<String> waiting=new ArrayList<>();
            for(Guest g:guests.values())if(!prepared.contains(g.row.id))waiting.add(g.row.name);
            message="Checking match on "+(waiting.size()>3?waiting.size()+" phones":String.join(", ",waiting));
        }
        return new Snapshot(session,self,revision,plan,rows,launch==null?"":launch.id,
            launch==null?-1:launch.entryIndex,released,message);
    }
}
