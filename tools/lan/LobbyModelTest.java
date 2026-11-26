package com.halo.decomp;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class LobbyModelTest {
    private static int checks;
    private static void check(boolean yes,String label){
        if(!yes)throw new AssertionError(label);
        checks++;
    }
    private static void rejected(Runnable run,String label){
        try{run.run();throw new AssertionError(label);}
        catch(IllegalArgumentException expected){checks++;}
    }
    static LobbyWire.Response exchange(LobbyModel model,LobbyWire.Request request,long now)throws Exception{
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<?> served=worker.submit(()->{
                try(Socket s=listener.accept()){s.setSoTimeout(2000);LobbyWire.serve(model,s.getInputStream(),s.getOutputStream(),now);}
                catch(IOException bad){throw new UncheckedIOException(bad);}
            });
            try(Socket client=new Socket()){
                client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),listener.getLocalPort()),2000);
                client.setSoTimeout(2000);
                LobbyWire.writeRequest(client.getOutputStream(),request);
                LobbyWire.Response result=LobbyWire.readResponse(client.getInputStream());
                served.get(3,TimeUnit.SECONDS);
                return result;
            }finally{listener.close();worker.shutdownNow();}
        }
    }
    public static void main(String[] args)throws Exception{
        LobbyModel.Plan plan=LobbyModel.Plan.from(MatchWorkshop.Library.empty());
        LobbyModel model=new LobbyModel("Host",0,plan);
        LobbyModel.Snapshot initial=model.hostSnapshot(0);
        check(initial.members.size()==1&&initial.me().host,"initial host");
        LobbyWire.Response a=exchange(model,LobbyWire.Request.join("Alpha",1),1);
        LobbyWire.Response b=exchange(model,LobbyWire.Request.join("Bravo",2),2);
        check(a.snapshot.members.size()==2&&!a.snapshot.me().host,"first join");
        check(b.snapshot.members.size()==3&&!b.snapshot.me().host,"second join");
        check(!a.secret.isEmpty()&&!a.secret.equals(b.secret),"unique membership secrets");
        check(!a.secret.equals(a.snapshot.self),"public identity distinct from secret");
        check(b.snapshot.members.stream().noneMatch(m->m.id.equals(a.secret)||m.id.equals(b.secret)),"no secret in roster");
        LobbyWire.Response current=exchange(model,LobbyWire.Request.poll(a.secret,-1),3);
        check(current.secret.isEmpty()&&current.snapshot.members.size()==3,"poll");
        long revision=current.snapshot.revision;
        LobbyWire.Response same=exchange(model,LobbyWire.Request.poll(a.secret,revision),4);
        check(same.error.isEmpty()&&same.snapshot==null,"unchanged heartbeat");
        LobbyWire.Response invalid=exchange(model,LobbyWire.Request.poll(LobbyModel.token(),-1),4);
        check(!invalid.error.isEmpty()&&invalid.snapshot==null,"unknown membership rejected");
        LobbyWire.Response changed=exchange(model,LobbyWire.Request.update(a.secret,"Alpha2",3,true,LobbyModel.NO_TEAM,revision),5);
        check(changed.snapshot.me().name.equals("Alpha2")&&changed.snapshot.me().ready&&changed.snapshot.me().color==3,"own update");
        check(changed.snapshot.members.stream().anyMatch(m->m.name.equals("Bravo")),"other member unchanged");
        LobbyWire.Response stale=exchange(model,LobbyWire.Request.update(b.secret,"Stale",1,true,LobbyModel.NO_TEAM,revision),6);
        check(stale.snapshot.me().name.equals("Bravo")&&!stale.snapshot.me().ready,"stale update not applied");
        LobbyModel.Snapshot host=model.hostSnapshot(7);
        if(host.teams()){
            LobbyModel.Member me=changed.snapshot.me();
            LobbyWire.Response requested=exchange(model,LobbyWire.Request.update(a.secret,me.name,me.color,false,1-me.team,host.revision),7);
            check(requested.snapshot.me().team==me.team&&requested.snapshot.me().requestedTeam==1-me.team,"guest only requests team");
            model.setTeam(me.id,1-me.team);
            LobbyModel.Snapshot assigned=model.hostSnapshot(8);
            check(assigned.members.stream().anyMatch(m->m.id.equals(me.id)&&m.team==1-me.team&&m.requestedTeam==LobbyModel.NO_TEAM),"host assigns team");
        }
        model.setPlan(plan);
        check(model.hostSnapshot(9).members.stream().noneMatch(m->m.ready),"settings reset readiness");
        LobbyWire.Response left=exchange(model,LobbyWire.Request.leave(b.secret),10);
        check(left.error.isEmpty()&&model.hostSnapshot(10).members.size()==2,"leave");
        model.expire(30000);
        check(model.hostSnapshot(30000).members.size()==1,"expiry");
        rejected(()->model.join("bad\nname",0,30001),"control chars");
        rejected(()->model.join("Test",999,30001),"invalid color");
        rejected(()->model.setTeam(LobbyModel.token(),0),"unknown member");
        ByteArrayOutputStream oversized=new ByteArrayOutputStream();
        new DataOutputStream(oversized).writeInt(1025);
        try{LobbyWire.readRequest(new ByteArrayInputStream(oversized.toByteArray()));throw new AssertionError("oversized request");}
        catch(IOException expected){checks++;}
        ByteArrayOutputStream oldFrame=new ByteArrayOutputStream(),answer=new ByteArrayOutputStream();
        DataOutputStream old=new DataOutputStream(oldFrame);old.writeInt(5);old.writeInt(0x484c5033);old.writeByte(LobbyWire.JOIN);
        LobbyWire.serve(model,new ByteArrayInputStream(oldFrame.toByteArray()),answer,31000);
        LobbyWire.Response incompatible=LobbyWire.readResponse(new ByteArrayInputStream(answer.toByteArray()));
        check(incompatible.snapshot==null&&incompatible.error.contains("same Halo build")&&model.hostSnapshot(31000).members.size()==1,
            "different lobby version receives an explicit mismatch without creating a seat");
        System.out.println("LobbyModelTest PASS ("+checks+" checks, real TCP loopback)");
    }
}
