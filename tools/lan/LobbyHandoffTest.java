package com.halo.decomp;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public final class LobbyHandoffTest {
    static int checks;
    static void check(boolean yes,String message) {
        if(!yes)throw new AssertionError(message);
        checks++;
    }
    static LobbyModel.Snapshot ack(LobbyModel m,String secret,String round,boolean ok,long now)throws Exception {
        // Use the same TCP request/response codec as a phone, including both frame boundaries.
        ByteArrayOutputStream request=new ByteArrayOutputStream(),response=new ByteArrayOutputStream();
        LobbyWire.writeRequest(request,LobbyWire.Request.acknowledge(secret,round,ok));
        LobbyWire.serve(m,new ByteArrayInputStream(request.toByteArray()),response,now);
        return LobbyWire.readResponse(new ByteArrayInputStream(response.toByteArray())).snapshot;
    }
    public static void main(String[] args)throws Exception {
        LobbyModel.Plan p=LobbyModel.Plan.from(MatchWorkshop.Library.empty());
        LobbyModel host=new LobbyModel("12345678901ABC",7,p);
        LobbyModel.Joined a=host.join("12345678901XYZ",10,1,"192.168.43.1","192.168.43.2");
        LobbyModel.Joined b=host.join("Guest",11,1,"192.168.43.1","192.168.43.3");
        check(host.nativeInterface("10.0.0.1").equals("192.168.43.1"),"uses the interface guests joined");
        LobbyModel.StartReview review=host.reviewStart(2,new Random(0));
        check(review.notReady.size()==2,"named unready guests");
        LobbyModel.Snapshot offer=host.beginStart(review,true,3);
        check(!offer.released&&offer.startIndex==0,"override still waits for technical checks");
        check(host.guestUpdate(a.secret,"Different",4,true,-1,offer.revision,4).me().name.equals("12345678901XYZ"),"offer freezes identity and roster");
        check(!ack(host,a.secret,LobbyModel.token(),true,4).released,"stale ack ignored");
        check(!ack(host,a.secret,offer.round,true,5).released,"one guest missing");
        check(!ack(host,a.secret,offer.round,true,6).released,"retry does not count twice");
        LobbyModel.Snapshot release=ack(host,b.secret,offer.round,true,7);
        check(release.released&&release.members.size()==3,"all guests included despite unready");
        check(ack(host,b.secret,offer.round,true,8).round.equals(offer.round),"lost release can be retried");
        check(ack(host,a.secret,offer.round,false,9).released,"late decline does not retract dispatched launch");
        String[] h=NativeLobbyMatch.arguments(host.hostSnapshot(10),"192.168.43.1","",host.nativePeers());
        String[] g=NativeLobbyMatch.arguments(release,"192.168.43.3","192.168.43.1",Collections.emptyList());
        check(h.length==15&&Arrays.equals(Arrays.copyOf(h,5),Arrays.copyOf(g,5)),"same round, map and native rules");
        check(h[5].equals("0")&&g[5].equals("2")&&h[6].equals(g[6]),"distinct seats sharing actual roster");
        String[] rows=h[6].split(",");
        check(rows.length==3&&!rows[0].substring(3).equals(rows[1].substring(3))&&rows[1].endsWith("007e0032"),"truncated names remain distinct");
        check(rows[0].substring(1,3).equals("0b")&&rows[1].substring(1,3).equals("07")&&rows[2].substring(1,3).equals("0e"),"actual Halo armor indices");
        check(h[14].equals("00000000c0a82b02c0a82b03")&&g[14].isEmpty(),"only host gets observed native seat addresses");
        check(g[10].equals("192.168.43.1")&&h[10].isEmpty()&&g[13].equals("192.168.43.3"),"guest binds LAN and targets host");
        check(host.hostSnapshot(100000).members.size()==3,"OS activity stop during match does not expire seats");
        host.resetStart("Match ended.",100001);
        check(host.poll(a.secret,100002).round.isEmpty()&&!host.poll(a.secret,100003).me().ready,"same lobby accepts next review");
        LobbyModel.Snapshot second=host.beginStart(host.reviewStart(100004,new Random(1)),true,100004);
        check(!second.round.equals(offer.round),"new match has new identity");
        check(!ack(host,b.secret,offer.round,true,100005).released,"previous match cannot acknowledge next");
        LobbyModel.Snapshot declined=ack(host,b.secret,second.round,false,100006);
        check(declined.round.isEmpty()&&declined.startMessage.contains("Guest")&&declined.members.size()==3,"missing map returns all phones to lobby");
        LobbyModel.Snapshot third=host.beginStart(host.reviewStart(100007,new Random(0)),true,100007);
        host.leave(a.secret,100008);
        check(host.hostSnapshot(100009).round.isEmpty()&&!third.round.isEmpty(),"disconnect cancels prepare");
        LobbyModel.Joined c=host.join("Other",0,100010,"10.1.2.3","10.1.2.4");
        try{host.nativeInterface("10.0.0.1");throw new AssertionError("two host interfaces");}
        catch(IllegalStateException expected){checks++;}
        host.leave(c.secret,100011);
        host.beginStart(host.reviewStart(100012,new Random()),true,100012);
        check(host.hostSnapshot(140000).round.isEmpty(),"unresponsive phone can't leave start stuck");

        Path root=Files.createTempDirectory("halo-lan-map-");
        try{
            Path maps=Files.createDirectory(root.resolve("maps")),map=maps.resolve("bloodgulch.map");
            byte[] data=new byte[2050];
            ByteBuffer header=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0,0x68656164).putInt(4,5).putShort(96,(short)1).putInt(2044,0x666f6f74);
            System.arraycopy("bloodgulch".getBytes("US-ASCII"),0,data,32,10);Files.write(map,data);
            NativeLobbyMatch.checkMap(root.toFile(),"bloodgulch");checks++;
            header.putShort(96,(short)0);Files.write(map,data);
            try{NativeLobbyMatch.checkMap(root.toFile(),"bloodgulch");throw new AssertionError("campaign header accepted");}
            catch(IOException expected){checks++;}
            Files.delete(map);
            try{NativeLobbyMatch.checkMap(root.toFile(),"bloodgulch");throw new AssertionError("missing map accepted");}
            catch(IOException expected){checks++;}
            Files.delete(maps);
        }finally{Files.delete(root);}
        System.out.println("LobbyHandoffTest PASS ("+checks+" checks)");
    }
}
