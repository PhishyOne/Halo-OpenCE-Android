package com.halo.decomp;

import java.util.*;

/** Multi-round host and guest exchanges over the launcher's real TCP framing. */
public final class LobbyPlaylistTest {
    private static int checks;
    private static void check(boolean yes,String reason){
        if(!yes)throw new AssertionError(reason);
        checks++;
    }
    private static MatchWorkshop.Entry entry(String map,String mode) {
        return new MatchWorkshop.Entry(map,MatchWorkshop.Preset.standard(mode),MatchWorkshop.Bots.balanced(6,2),true);
    }
    private static LobbyModel lobby(boolean shuffle,boolean loop,MatchWorkshop.Entry... entries){
        return new LobbyModel("Host",0,new LobbyModel.Plan("Game night",true,shuffle,loop,Arrays.asList(entries)));
    }
    private static LobbyModel.Snapshot send(LobbyModel m,LobbyWire.Request r,long now)throws Exception{
        LobbyWire.Response reply=LobbyModelTest.exchange(m,r,now);
        check(reply.error.isEmpty()&&reply.snapshot!=null,"expected snapshot for operation "+r.op);
        return reply.snapshot;
    }
    private static LobbyModel.Snapshot prepare(LobbyModel m,long now){
        return m.beginStart(m.reviewStart(now,new Random(7)),true,now);
    }
    private static LobbyModel.Snapshot back(LobbyModel m,String secret,String round,boolean complete,long now)throws Exception{
        return send(m,LobbyWire.Request.returned(secret,round,complete),now);
    }
    private static void ordered()throws Exception{
        LobbyModel m=lobby(false,false,entry("bloodgulch","team_slayer"),entry("sidewinder","gun_game"));
        String alpha=LobbyModelTest.exchange(m,LobbyWire.Request.join("Alpha",1),1).secret;
        String bravo=LobbyModelTest.exchange(m,LobbyWire.Request.join("Bravo",2),2).secret;
        LobbyModel.Snapshot joined=m.poll(alpha,2);
        send(m,LobbyWire.Request.update(alpha,"Alpha",1,true,LobbyModel.NO_TEAM,joined.revision),2);
        LobbyModel.Snapshot first=prepare(m,3);
        String round=first.round;
        send(m,LobbyWire.Request.acknowledge(alpha,round,true),4);
        send(m,LobbyWire.Request.acknowledge(bravo,round,true),5);
        back(m,alpha,round,true,100000); // Alpha closes the scoreboard before the host.
        back(m,alpha,round,true,100001);
        LobbyModel.Snapshot wait=m.hostReturned(round,true,"Match complete.",100002);
        check(wait.hostBack&&wait.startMessage.contains("Bravo")&&!wait.startMessage.contains("Alpha"),"only outstanding phone is named");
        check(m.dueStart(100003)==null,"cannot advance while a guest is in native Halo");
        check(back(m,bravo,LobbyModel.token(),true,100004).hostBack,"wrong round cannot release the barrier");
        LobbyModel.Snapshot between=back(m,bravo,round,true,100005);
        check(between.secondsToNext==5&&between.nextIndex==1&&between.round.isEmpty(),"both launchers see next entry and intermission");
        check(m.hostReturned(round,true,"duplicate",100006).secondsToNext==5,"duplicate host callback cannot advance twice");
        check(back(m,bravo,round,true,100007).nextIndex==1,"lost guest return reply is safe to retry");
        check(m.dueStart(105004)==null,"five full seconds for pause");
        LobbyModel.StartReview due=m.dueStart(105005);
        check(due.notReady.size()==1&&due.notReady.get(0).name.equals("Bravo"),"continuation retains the approved group's Ready choices");
        check(due.entryIndex==1&&due.entry.map.equals("sidewinder")&&due.allocation.ffa==6,"resolved FFA rules and bots belong to second entry");
        check(due.members.stream().allMatch(p->p.team==LobbyModel.NO_TEAM),"team to FFA remaps each human");
        LobbyModel.Snapshot second=m.beginStart(due,true,105005);
        check(!second.round.equals(round)&&!second.released&&second.members.size()==3,"new round requires fresh map checks for everyone");
        check(!send(m,LobbyWire.Request.acknowledge(bravo,round,true),105006).released,"old ACK cannot release new game");
        check(!back(m,alpha,round,false,105007).released,"delayed old return cannot interrupt or launch this entry");
        send(m,LobbyWire.Request.acknowledge(alpha,second.round,true),105008);
        LobbyModel.Snapshot failed=send(m,LobbyWire.Request.acknowledge(bravo,second.round,false),105009);
        check(failed.round.isEmpty()&&failed.secondsToNext<0&&failed.nextIndex==1&&failed.startMessage.contains("Bravo"),"missing map pauses on exact entry");
        LobbyModel.StartReview retry=m.reviewStart(105010,new Random(100));
        check(retry.entryIndex==1&&retry.needsOverride(),"manual resume checks advisory readiness again");
        LobbyModel.Snapshot offered=m.beginStart(retry,true,105010);
        send(m,LobbyWire.Request.acknowledge(alpha,offered.round,true),105011);
        send(m,LobbyWire.Request.acknowledge(bravo,offered.round,true),105012);
        back(m,bravo,offered.round,true,200000);back(m,alpha,offered.round,true,200001);
        LobbyModel.Snapshot end=m.hostReturned(offered.round,true,"Match complete.",200002);
        check(end.round.isEmpty()&&end.secondsToNext<0&&end.nextIndex<0&&end.startMessage.equals("Playlist complete."),"non-loop stops after last match");
        check(end.session.equals(first.session)&&end.members.size()==3&&m.dueStart(200003)==null,"all players stay in same idle lobby");
        check(m.reviewStart(200004,new Random(4)).entryIndex==0,"explicit restart starts a new pass");
    }
    private static void interruption()throws Exception{
        LobbyModel m=lobby(false,true,entry("bloodgulch","slayer"),entry("sidewinder","ctf"));
        String g=m.join("Guest",4,0).secret;
        String round=prepare(m,1).round;send(m,LobbyWire.Request.acknowledge(g,round,true),2);
        back(m,g,round,false,3);
        check(m.hostSnapshot(30000).released,"guest leaving gameplay cannot reset a still-playing host");
        LobbyModel.Snapshot stopped=m.hostReturned(round,true,"Match complete.",30001);
        check(stopped.nextIndex==0&&stopped.secondsToNext<0&&stopped.startMessage.contains("Guest"),"early guest return pauses the current entry");
        round=prepare(m,30002).round;send(m,LobbyWire.Request.acknowledge(g,round,true),30003);
        m.hostReturned(round,true,"Match complete.",30004);
        LobbyModel.Snapshot timeout=m.hostSnapshot(90004);
        check(timeout.round.isEmpty()&&timeout.nextIndex==0&&timeout.startMessage.contains("Guest"),"unreturned phone pauses playlist on timeout");
        check(back(m,g,round,true,90005).secondsToNext<0,"late return cannot restart canceled round");
        round=prepare(m,90006).round;send(m,LobbyWire.Request.acknowledge(g,round,true),90007);
        m.hostReturned(round,true,"Match complete.",90008);back(m,g,round,true,90009);
        LobbyModel.StartReview due=m.dueStart(95009);
        m.resetStart("Playlist paused.",95009);
        try{m.beginStart(due,true,95009);throw new AssertionError("stale automatic start");}catch(LobbyModel.StartChanged expected){checks++;}
        check(m.dueStart(95010)==null&&m.reviewStart(95010,new Random()).entryIndex==1,"pause keeps next entry");
        round=prepare(m,95011).round;send(m,LobbyWire.Request.acknowledge(g,round,true),95012);
        m.hostReturned(round,true,"Match complete.",95013);back(m,g,round,true,95014);
        LobbyModel.Joined newcomer=m.join("New player",0,95015);
        check(newcomer.snapshot.secondsToNext<0&&newcomer.snapshot.nextIndex==0,"roster change interrupts auto-start");
        check(m.reviewStart(95016,new Random()).notReady.size()==2,"host reviews both unready players after roster change");
        m.leave(newcomer.secret,95017);
        round=prepare(m,95018).round;send(m,LobbyWire.Request.acknowledge(g,round,true),95019);
        m.leave(g,95020);
        check(m.hostSnapshot(95021).members.size()==2,"leaving while host plays preserves frozen native seats");
        LobbyModel.Snapshot departed=m.hostReturned(round,true,"Match complete.",95022);
        check(departed.members.size()==1&&departed.secondsToNext<0&&departed.startMessage.contains("Guest"),"guest removed after host returns");
    }
    private static void shuffle(){
        MatchWorkshop.Entry duplicate=entry("bloodgulch","slayer");
        LobbyModel m=lobby(true,true,duplicate,duplicate,entry("sidewinder","ctf"));
        Set<Integer> seen=new HashSet<>();int previous=-1;long now=0;
        LobbyModel.StartReview review=m.reviewStart(now,new Random(7));
        for(int i=0;i<12;i++){
            if(i%3==0){seen.clear();check(previous!=review.entryIndex,"no repeat across shuffled passes");}
            check(seen.add(review.entryIndex),"each entry index once even for duplicate map and mode");
            previous=review.entryIndex;LobbyModel.Snapshot match=m.beginStart(review,true,++now);
            check(match.released,"solo host with bots can play a playlist");
            LobbyModel.Snapshot next=m.hostReturned(match.round,true,"Match complete.",++now);
            check(next.nextIndex>=0&&next.secondsToNext==5,"loop continues");
            now+=5000;review=m.dueStart(now);
        }
        LobbyModel quick=new LobbyModel("Host",1,LobbyModel.Plan.from(MatchWorkshop.Library.empty()));
        LobbyModel.Snapshot round=prepare(quick,0);
        check(quick.hostReturned(round.round,true,"Done",1).secondsToNext<0&&quick.dueStart(20000)==null,"quick match stays single");
        LobbyModel single=lobby(true,true,duplicate);
        LobbyModel.Snapshot one=prepare(single,0);
        check(single.hostReturned(one.round,true,"Done",1).nextIndex==0,"single-entry loop repeats after completion");
        LobbyModel.Snapshot two=single.beginStart(single.dueStart(5001),true,5001);
        check(!one.round.equals(two.round)&&single.hostReturned(two.round,false,"Halo closed.",5002).secondsToNext<0,"host crash pauses even a single-entry loop");
    }
    public static void main(String[] args)throws Exception{
        ordered();interruption();shuffle();
        System.out.println("LobbyPlaylistTest PASS ("+checks+" checks, real TCP loopback)");
    }
}
