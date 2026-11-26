package com.halo.decomp;

import java.util.*;

public final class LobbyStartTest {
    static int checks;
    static void check(boolean b,String what){if(!b)throw new AssertionError(what);checks++;}
    static void blocked(Runnable r,Class<? extends RuntimeException> type){
        try{r.run();throw new AssertionError("Unexpected authorization");}
        catch(RuntimeException e){if(!type.isInstance(e))throw e;checks++;}
    }
    static LobbyModel.StartReview review(LobbyModel m,long now){return m.reviewStart(now,new Random(12));}
    static void readiness(LobbyModel m,LobbyModel.Joined g,boolean ready,long now){
        LobbyModel.Snapshot s=m.poll(g.secret,now);
        LobbyModel.Member self=s.me();
        m.guestUpdate(g.secret,self.name,self.color,ready,self.requestedTeam,s.revision,now);
    }
    public static void main(String[] args){
        LobbyModel.Plan p=LobbyModel.Plan.from(MatchWorkshop.Library.empty());
        LobbyModel m=new LobbyModel("Host",0,p);
        LobbyModel.StartReview solo=review(m,0);
        check(!solo.needsOverride()&&solo.blocker.isEmpty(),"host ready implicit");
        check(m.authorizeStart(solo,false,0).members.size()==1,"solo start");
        LobbyModel.Joined a=m.join("Alpha",1,1),b=m.join("Bravo",2,2);
        LobbyModel.StartReview r=review(m,3);
        check(r.needsOverride()&&r.notReady.size()==2,"both guests not ready");
        check(r.blocker.isEmpty(),"readiness is not a hard blocker");
        blocked(()->m.authorizeStart(r,false,3),LobbyModel.StartNotReady.class);
        LobbyModel.StartTicket t=m.authorizeStart(r,true,3);
        check(t.readinessOverridden&&t.notReady.size()==2&&t.members.size()==3,"override includes every guest");
        check(t.members.stream().anyMatch(x->x.id.equals(a.snapshot.self)),"alpha retained");
        check(t.members.stream().anyMatch(x->x.id.equals(b.snapshot.self)),"bravo retained");
        readiness(m,a,true,4);readiness(m,b,true,5);
        LobbyModel.StartReview all=review(m,6);
        check(!all.needsOverride(),"all ready");
        check(!m.authorizeStart(all,false,6).readinessOverridden,"ordinary start");
        readiness(m,b,false,7);
        blocked(()->m.authorizeStart(all,false,8),LobbyModel.StartNotReady.class);
        LobbyModel.StartReview override=review(m,8);
        readiness(m,a,false,9);readiness(m,b,true,10);
        LobbyModel.StartTicket still=m.authorizeStart(override,true,10);
        check(still.members.size()==3&&still.notReady.size()==1&&still.notReady.get(0).name.equals("Alpha"),"ready toggles cannot veto confirmed override");
        m.setTeam(a.snapshot.self,LobbyModel.RED==override.members.get(1).team?LobbyModel.BLUE:LobbyModel.RED);
        blocked(()->m.authorizeStart(override,true,11),LobbyModel.StartChanged.class);
        LobbyModel.StartReview beforeLeave=review(m,12);m.leave(b.secret,13);
        blocked(()->m.authorizeStart(beforeLeave,true,13),LobbyModel.StartChanged.class);
        LobbyModel.StartReview beforePlan=review(m,14);m.setPlan(new LobbyModel.Plan("Changed",false,false,false,Collections.singletonList(p.first())));
        blocked(()->m.authorizeStart(beforePlan,true,14),LobbyModel.StartChanged.class);
        LobbyModel other=new LobbyModel("Another",0,p);
        blocked(()->other.authorizeStart(review(m,15),true,15),LobbyModel.StartChanged.class);
        LobbyModel.StartReview beforeExpiry=review(m,15);
        blocked(()->m.authorizeStart(beforeExpiry,true,30000),LobbyModel.StartChanged.class);
        LobbyModel empty=new LobbyModel("Host",0,new LobbyModel.Plan("Empty",true,false,false,Collections.emptyList()));
        LobbyModel.StartReview e=review(empty,0);
        check(!e.blocker.isEmpty(),"empty playlist blocks");
        blocked(()->empty.authorizeStart(e,true,0),IllegalStateException.class);
        MatchWorkshop.Entry nobody=new MatchWorkshop.Entry("bloodgulch",MatchWorkshop.Preset.standard("slayer"),MatchWorkshop.Bots.ffa(0,2),false);
        LobbyModel one=new LobbyModel("Host",0,new LobbyModel.Plan("Single",false,false,false,Collections.singletonList(nobody)));
        check(!review(one,0).blocker.isEmpty(),"minimum participants");
        blocked(()->one.authorizeStart(review(one,0),true,0),IllegalStateException.class);
        MatchWorkshop.Entry first=p.first(),second=new MatchWorkshop.Entry("sidewinder",MatchWorkshop.Preset.standard("gun_game"),MatchWorkshop.Bots.ffa(8,2),false);
        LobbyModel shuffle=new LobbyModel("Host",0,new LobbyModel.Plan("Shuffle",true,true,true,Arrays.asList(first,second)));
        shuffle.join("Guest",1,1);
        LobbyModel.StartReview chosen=shuffle.reviewStart(2,new Random(7));
        check(chosen.entryIndex==1&&chosen.entry==second,"shuffle selects actual entry");
        LobbyModel.StartTicket chosenTicket=shuffle.authorizeStart(chosen,true,3);
        check(chosenTicket.entry==second&&chosenTicket.members.stream().allMatch(x->x.team==LobbyModel.NO_TEAM),"same match and FFA allocation");
        check(chosenTicket.allocation.ffa==8&&chosenTicket.allocation.red==0,"FFA bots");
        System.out.println("LobbyStartTest PASS ("+checks+" checks)");
    }
}
