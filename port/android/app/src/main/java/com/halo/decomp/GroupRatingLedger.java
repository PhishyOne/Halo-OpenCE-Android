package com.halo.decomp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Private, bounded, append-by-union match ledger. Each device derives
 * ratings from identical canonical receipts, not peer-reported rating totals.
 * Conflicting receipts for the same match are retained but never rated. */
final class GroupRatingLedger {
    static final int MAX_ROWS=2048, MAX_BYTES=MAX_ROWS*(GroupRatingReceipt.MAX_LINE+1);
    static final String FILE="group-rating-ledger-v1.txt", PENDING="group-host-pending-v1.txt";
    static final String NATIVE_PREFIX="group-native-v1-";
    static final int BASE=1500, MIN=800, MAX=2400, K=32;
    static final class Score {
        final int value, matches, wins, losses, draws, forfeits;
        Score(int value,int matches,int wins,int losses,int draws,int forfeits) {
            this.value=value; this.matches=matches; this.wins=wins; this.losses=losses;
            this.draws=draws; this.forfeits=forfeits;
        }
        String display() { return matches==0?"UNRATED":Integer.toString(value); }
    }
    private static final Score EMPTY=new Score(BASE,0,0,0,0,0);
    static final class Snapshot {
        final List<String> rows;
        final String digest;
        final int conflicts;
        private final Map<String,Score> scores;
        Snapshot(TreeSet<String> canonical) throws IOException {
            if(canonical.size()>MAX_ROWS) throw new IOException("Group rating ledger capacity reached");
            rows=Collections.unmodifiableList(new ArrayList<>(canonical));
            digest=digest(rows);
            Map<String,GroupRatingReceipt> byId=new TreeMap<>();
            java.util.HashSet<String> conflict=new java.util.HashSet<>();
            for(String row:rows) {
                GroupRatingReceipt r=GroupRatingReceipt.parse(row);
                if(byId.putIfAbsent(r.id,r)!=null) conflict.add(r.id);
            }
            for(String id:conflict) byId.remove(id);
            conflicts=conflict.size();
            List<GroupRatingReceipt> ordered=new ArrayList<>(byId.values());
            ordered.sort(Comparator.comparingLong((GroupRatingReceipt r)->r.epoch).thenComparing(r->r.id));
            scores=Collections.unmodifiableMap(calculate(ordered));
        }
        Score score(String id) { return scores.getOrDefault(id,EMPTY); }
    }
    private static final class Mutable {
        int rating=BASE,matches,wins,losses,draws,forfeits;
    }
    private GroupRatingLedger() {}
    static Snapshot empty() {
        try { return new Snapshot(new TreeSet<>()); }
        catch(IOException impossible) { throw new AssertionError(impossible); }
    }
    static Snapshot fromRows(List<String> rows) throws IOException {
        if(rows==null||rows.size()>MAX_ROWS) throw new IOException("Group receipt count out of bounds");
        TreeSet<String> canonical=new TreeSet<>();
        for(String row:rows) canonical.add(GroupRatingReceipt.parse(row).row());
        return new Snapshot(canonical);
    }
    static String digest(List<String> rows) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            for(String row:rows) {
                md.update(row.getBytes(StandardCharsets.US_ASCII));
                md.update((byte)'\n');
            }
            StringBuilder hex=new StringBuilder(64);
            for(byte b:md.digest()) hex.append(Character.forDigit((b>>4)&15,16))
                .append(Character.forDigit(b&15,16));
            return hex.toString();
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static Map<String,Score> calculate(List<GroupRatingReceipt> receipts) {
        Map<String,Mutable> players=new HashMap<>();
        for(GroupRatingReceipt r:receipts) {
            Map<String,Integer> changes=new HashMap<>();
            for(GroupRatingReceipt.Seat s:r.seats) players.computeIfAbsent(s.id,id->new Mutable());
            for(GroupRatingReceipt.Seat s:r.seats) {
                if(s.outcome==GroupRatingReceipt.NEUTRAL) continue;
                Mutable self=players.get(s.id);
                double sum=0; int count=0;
                for(GroupRatingReceipt.Seat other:r.seats) {
                    if(!s.opposes(other)) continue;
                    if(other.outcome==GroupRatingReceipt.NEUTRAL&&s.outcome!=GroupRatingReceipt.FORFEIT) continue;
                    int opponent=players.get(other.id).rating;
                    double expected=1.0/(1.0+StrictMath.pow(10.0,(opponent-self.rating)/400.0));
                    // F is always a loss for that player, even against
                    // another quitter. Non-forfeiting opponents see F as loss.
                    double actual=s.outcome==GroupRatingReceipt.FORFEIT?0.0:
                        other.outcome==GroupRatingReceipt.FORFEIT?1.0:
                        s.outcome>other.outcome?1.0:s.outcome<other.outcome?0.0:0.5;
                    sum+=actual-expected; count++;
                }
                int delta=count==0?0:(int)StrictMath.round(K*sum/count);
                // Quit cannot reduce the loss compared with playing out
                // a normal defeat. The lower rating bound still applies.
                if(s.outcome==GroupRatingReceipt.FORFEIT) delta=Math.min(delta,-K);
                changes.put(s.id,delta);
            }
            // Simultaneous updates from ratings BEFORE this match.
            for(GroupRatingReceipt.Seat s:r.seats) {
                Integer delta=changes.get(s.id);
                if(delta==null) continue;
                Mutable score=players.get(s.id);
                score.rating=Math.max(MIN,Math.min(MAX,score.rating+delta));
                score.matches++;
                if(s.outcome==GroupRatingReceipt.WIN) score.wins++;
                else if(s.outcome==GroupRatingReceipt.DRAW) score.draws++;
                else { score.losses++; if(s.outcome==GroupRatingReceipt.FORFEIT) score.forfeits++; }
            }
        }
        Map<String,Score> result=new HashMap<>();
        for(Map.Entry<String,Mutable> e:players.entrySet()) {
            Mutable s=e.getValue();
            result.put(e.getKey(),new Score(s.rating,s.matches,s.wins,s.losses,s.draws,s.forfeits));
        }
        return result;
    }
    private static String readFile(File file,int max) throws IOException {
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||file.length()>max)
            throw new IOException("Invalid group receipt file");
        byte[] bytes=Files.readAllBytes(file.toPath());
        if(bytes.length==0||bytes.length>max||bytes[bytes.length-1]!='\n')
            throw new IOException("Incomplete group receipt file");
        for(byte b:bytes) if(b!='\n'&&(b<32||b>126)) throw new IOException("Non-ASCII group receipt");
        return new String(bytes,StandardCharsets.US_ASCII);
    }
    static Snapshot read(File root) throws IOException {
        if(root==null||!root.isDirectory()) throw new IOException("Private rating storage unavailable");
        TreeSet<String> canonical=new TreeSet<>();
        File ledger=new File(root,FILE);
        if(Files.exists(ledger.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            for(String row:readFile(ledger,MAX_BYTES).split("\n"))
                canonical.add(GroupRatingReceipt.parse(row).row());
        }
        File[] nativeFiles=root.listFiles(f->f.getName().matches("group-native-v1-[0-9a-f]{32}\\.txt"));
        if(nativeFiles==null||nativeFiles.length>MAX_ROWS) throw new IOException("Native receipt limit reached");
        for(File f:nativeFiles) {
            String line=readFile(f,GroupRatingReceipt.MAX_LINE+1);
            GroupRatingReceipt receipt=GroupRatingReceipt.parse(line.substring(0,line.length()-1));
            if(!f.getName().equals(NATIVE_PREFIX+receipt.id+".txt")) throw new IOException("Wrong native match file");
            canonical.add(receipt.row());
            if(canonical.size()>MAX_ROWS) throw new IOException("Group receipt limit reached");
        }
        return new Snapshot(canonical);
    }
    static synchronized Snapshot merge(File root,Snapshot received) throws IOException {
        if(received==null||root==null||!root.isDirectory()) throw new IOException("No group receipt storage");
        File lockFile=new File(root,"group-rating-v1.lock");
        try(FileChannel channel=FileChannel.open(lockFile.toPath(),StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock=null; long deadline=System.nanoTime()+1_000_000_000L;
            while(lock==null&&System.nanoTime()<deadline) {
                lock=channel.tryLock();
                if(lock==null) try { Thread.sleep(20); }
                catch(InterruptedException cancelled) {
                    Thread.currentThread().interrupt(); throw new IOException("Rating sync cancelled",cancelled);
                }
            }
            if(lock==null) throw new IOException("Group ledger busy");
            try(FileLock acquired=lock) {
                Snapshot before=read(root);
                TreeSet<String> rows=new TreeSet<>(before.rows);
                if(!rows.addAll(received.rows)) return before;
                Snapshot after=new Snapshot(rows);
                File temporary=File.createTempFile("group-rating-", ".tmp", root);
                try {
                    try(FileOutputStream out=new FileOutputStream(temporary)) {
                        for(String row:after.rows) out.write((row+"\n").getBytes(StandardCharsets.US_ASCII));
                        out.getFD().sync();
                    }
                    move(temporary,new File(root,FILE),true);
                } finally { temporary.delete(); }
                return after;
            }
        }
    }
    private static void move(File from,File to,boolean replace) throws IOException {
        if(replace) {
            try { Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            // No-replace move protects an already finalized native result.
            Files.move(from.toPath(),to.toPath());
        }
    }
    /** Called only after the launcher has confirmed the :game process
     * is gone. A forced stop cannot turn a locked host match into no result. */
    static synchronized boolean recoverAbandonedHost(File root,String localId,boolean gameStopped) throws IOException {
        if(!gameStopped||root==null||!LanBoardProtocol.idValid(localId)) return false;
        File pending=new File(root,PENDING);
        if(!Files.exists(pending.toPath(),LinkOption.NOFOLLOW_LINKS)) return false;
        String row=readFile(pending,GroupRatingReceipt.MAX_LINE+1);
        GroupRatingReceipt r=GroupRatingReceipt.parse(row.substring(0,row.length()-1));
        if(!r.abandoned||!r.host.equals(localId)||r.seat(localId).outcome!=GroupRatingReceipt.FORFEIT)
            throw new IOException("Unusable interrupted host receipt");
        File finalFile=new File(root,NATIVE_PREFIX+r.id+".txt");
        if(Files.exists(finalFile.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            String existing=readFile(finalFile,GroupRatingReceipt.MAX_LINE+1);
            GroupRatingReceipt finished=GroupRatingReceipt.parse(existing.substring(0,existing.length()-1));
            if(!finished.id.equals(r.id)||!finished.host.equals(localId))
                throw new IOException("Conflicting finalized host receipt");
            Files.deleteIfExists(pending.toPath());
            return false;
        }
        move(pending,finalFile,false);
        return true;
    }
}
