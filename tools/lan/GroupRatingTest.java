package com.halo.decomp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Tests the exact native result files, rating replay, recovery and wire framing. */
public final class GroupRatingTest {
    static final String HOST="11111111111111111111111111111111",
        GUEST="22222222222222222222222222222222", THIRD="33333333333333333333333333333333";
    static int checks;
    static void check(boolean pass,String what) {
        checks++;
        if(!pass) throw new AssertionError(what);
    }
    interface Attempt { void run() throws Exception; }
    static void rejects(Attempt a,String what) throws Exception {
        try { a.run(); throw new AssertionError("Accepted "+what); }
        catch(IOException expected) { checks++; }
    }
    static File dir(File root,String name) { return new File(root,name); }
    static GroupRatingLedger.Snapshot read(File root,String name) throws Exception {
        return GroupRatingLedger.read(dir(root,name));
    }
    static String row(int match,long epoch,int first,int second) {
        return "GR1|"+String.format(java.util.Locale.ROOT,"%032x",match)+"|"+HOST+"|"+epoch+
            "|2|900|0|2|"+HOST+":-1:"+first+"|"+GUEST+":-1:"+second;
    }
    public static void main(String[] args) throws Exception {
        File root=new File(args[0]);
        GroupRatingLedger.Snapshot normal=read(root,"normal");
        check(normal.rows.size()==1,"native normal receipt");
        check(normal.score(HOST).value==1516&&normal.score(GUEST).value==1484,"equal rating win/loss");
        check(normal.score(GUEST).losses==1&&normal.score(GUEST).forfeits==0,"ordinary loss");
        GroupRatingLedger.Snapshot draw=read(root,"draw");
        check(draw.score(HOST).value==1500&&draw.score(GUEST).draws==1,"equal draw");
        GroupRatingLedger.Snapshot quit=read(root,"quit");
        check(quit.score(HOST).value==1516&&quit.score(GUEST).value==1468,"quit costs 32");
        check(quit.score(GUEST).losses==1&&quit.score(GUEST).forfeits==1,"quit is loss and forfeit");
        check(read(root,"quit_rejoin").score(GUEST).forfeits==1,"same seat reconnect cannot undo quit");
        check(read(root,"team_quit").score(THIRD).wins==1,"surviving teammate wins");
        check(read(root,"team_quit").score(GUEST).value==1468,"team quitter pays penalty");
        check(read(root,"manual_end").score(HOST).value==1468,"early host end loses 32");
        check(read(root,"manual_end").score(GUEST).matches==0,"no invented win from host abandonment");
        check(read(root,"host_abandon").score(HOST).forfeits==1,"host graceful abandonment");
        check(read(root,"late_join").score(GUEST).forfeits==1&&read(root,"late_join").score(HOST).matches==0,
            "late join does not erase observed forfeit");
        for(String name:Arrays.asList("bots","configured_bots","online","optout","unknown","prelock_leave","duplicate"))
            check(read(root,name).rows.isEmpty(),"excluded "+name);
        check(read(root,"guest").digest.equals(GroupRatingLedger.read(new File(dir(root,"guest"),"client")).digest),
            "host/client identical result");
        File crash=dir(root,"host_crash_observed");
        check(!GroupRatingLedger.recoverAbandonedHost(crash,HOST,false),"never recover live game");
        check(new File(crash,GroupRatingLedger.PENDING).isFile(),"pending preserved while alive");
        rejects(()->GroupRatingLedger.recoverAbandonedHost(crash,GUEST,true),"wrong pending owner");
        check(GroupRatingLedger.recoverAbandonedHost(crash,HOST,true),"host crash recovered");
        check(!GroupRatingLedger.recoverAbandonedHost(crash,HOST,true),"recovery idempotent");
        GroupRatingLedger.Snapshot recovered=GroupRatingLedger.read(crash);
        check(recovered.score(HOST).forfeits==1&&recovered.score(GUEST).forfeits==1&&
            recovered.score(THIRD).matches==0,"recovered actual forfeits only");
        String a=row(1,1700000000L,3,1), b=row(2,1700000010L,3,1);
        GroupRatingLedger.Snapshot two=GroupRatingLedger.fromRows(Arrays.asList(b,a,a));
        check(two.rows.size()==2&&two.score(HOST).matches==2,"canonical deduplication");
        check(two.score(HOST).value==1531&&two.score(GUEST).value==1469,"actual pre-match opponent ratings");
        check(GroupRatingLedger.fromRows(Arrays.asList(a,b)).digest.equals(two.digest),"arrival order convergence");
        GroupRatingLedger.Snapshot upset=GroupRatingLedger.fromRows(Arrays.asList(a,row(2,1700000010L,1,3)));
        check(upset.score(GUEST).value>1500&&upset.score(HOST).value<1500,"upset adjusts actual ratings");
        String reversed=a.substring(0,a.indexOf("|"+HOST+":"))+"|"+GUEST+":-1:1|"+HOST+":-1:3";
        check(GroupRatingLedger.fromRows(Arrays.asList(a,reversed)).rows.size()==1,"seat order dedup");
        GroupRatingLedger.Snapshot conflict=GroupRatingLedger.fromRows(Arrays.asList(a,row(1,1700000000L,1,3),b));
        check(conflict.conflicts==1&&conflict.score(HOST).matches==1,"conflicting receipt quarantined");
        rejects(()->GroupRatingReceipt.parse(a.replace(GUEST+":-1:1",HOST+":-1:1")),"duplicate seat");
        rejects(()->GroupRatingReceipt.parse(a.replace(HOST+":-1:3",HOST+":0:3")),"mixed team/FFA");
        rejects(()->GroupRatingReceipt.parse(a.replace(GUEST+":-1:1",GUEST+":-1:0")),"neutral completed result");
        rejects(()->GroupRatingReceipt.parse(a.replace(HOST+":-1:3",HOST+":-1:1")),"no winner or draw");
        File peer=new File(root,"sync-peer"); check(peer.mkdir(),"test peer created");
        check(GroupRatingLedger.merge(peer,two).digest.equals(two.digest),"durable merge");
        check(GroupRatingLedger.merge(peer,two).digest.equals(two.digest),"repeat merge no extra SR");
        check(GroupRatingLedger.read(peer).score(HOST).matches==2,"persisted replay");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        GroupRatingWire.writeRows(out,two);
        check(GroupRatingWire.readRows(new ByteArrayInputStream(out.toByteArray()),two.rows.size(),two.digest)
            .digest.equals(two.digest),"real wire round trip");
        rejects(()->GroupRatingWire.readRows(new ByteArrayInputStream(out.toByteArray()),two.rows.size(),
            String.join("",Collections.nCopies(64,"0"))),"wrong digest");
        rejects(()->GroupRatingWire.readRows(new ByteArrayInputStream(Arrays.copyOf(out.toByteArray(),5)),2,two.digest),
            "truncated exchange");
        String nonce="0123456789abcdef";
        String query=GroupRatingWire.query(nonce,HOST,two.digest);
        check(GroupRatingWire.readQuery(query,HOST)[1].equals(nonce),"bound owner query");
        rejects(()->GroupRatingWire.readQuery(query,GUEST),"wrong target owner");
        System.out.println("PASS GroupRatingTest: "+checks+" checks");
    }
}
