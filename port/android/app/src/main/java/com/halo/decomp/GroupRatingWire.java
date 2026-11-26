package com.halo.decomp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounded, digest-negotiated receipt exchange on the existing private LAN
 * board port. No identity/name secrets, arbitrary paths or rating totals. */
final class GroupRatingWire {
    static final String QUERY="HGQ1", HEADER="HGH1", PUSH="HGP1", ACK="HGA1";
    private GroupRatingWire() {}
    static boolean isQuery(String request) { return request!=null&&request.startsWith(QUERY+"|"); }
    static boolean validDigest(String d) { return d!=null&&d.matches("[0-9a-f]{64}"); }
    static String query(String nonce,String owner,String digest) {
        if(!LanBoardProtocol.nonceValid(nonce)||!LanBoardProtocol.idValid(owner)||!validDigest(digest))
            throw new IllegalArgumentException("Invalid group sync query");
        return QUERY+"|"+nonce+"|"+owner+"|"+digest;
    }
    static String[] readQuery(String line,String owner) throws IOException {
        String[] p=line.split("\\|",-1);
        if(p.length!=4||!QUERY.equals(p[0])||!LanBoardProtocol.nonceValid(p[1])||
            !owner.equals(p[2])||!validDigest(p[3])) throw new IOException("Wrong rating sync target");
        return p;
    }
    static String header(String nonce,String owner,GroupRatingLedger.Snapshot data,boolean same) {
        return HEADER+"|"+nonce+"|"+owner+"|"+data.digest+"|"+(same?0:data.rows.size());
    }
    static int count(String v) throws IOException { return (int)GroupRatingReceipt.number(v,GroupRatingLedger.MAX_ROWS); }
    static void line(OutputStream out,String line) throws IOException {
        out.write((line+"\n").getBytes(StandardCharsets.US_ASCII));
    }
    static String line(InputStream in,int max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(128);
        for(int n=0;n<=max;n++) {
            int c=in.read();
            if(c=='\n') return new String(out.toByteArray(),StandardCharsets.US_ASCII);
            if(c<32||c>126||n==max) throw new IOException("Invalid group sync frame");
            out.write(c);
        }
        throw new IOException("Group sync frame too large");
    }
    static GroupRatingLedger.Snapshot readRows(InputStream in,int count,String digest) throws IOException {
        if(count<0||count>GroupRatingLedger.MAX_ROWS||!validDigest(digest)) throw new IOException("Bad group sync count");
        List<String> rows=new ArrayList<>();
        for(int i=0;i<count;i++) rows.add(line(in,GroupRatingReceipt.MAX_LINE));
        GroupRatingLedger.Snapshot snapshot=GroupRatingLedger.fromRows(rows);
        if(snapshot.rows.size()!=count||!snapshot.rows.equals(rows)||!snapshot.digest.equals(digest))
            throw new IOException("Rating receipt digest/order mismatch");
        return snapshot;
    }
    static void writeRows(OutputStream out,GroupRatingLedger.Snapshot snapshot) throws IOException {
        for(String row:snapshot.rows) line(out,row);
        out.flush();
    }
}
