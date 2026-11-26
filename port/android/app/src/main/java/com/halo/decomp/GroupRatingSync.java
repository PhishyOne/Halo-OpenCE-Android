package com.halo.decomp;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;

/** Sync only when the local master sharing setting permits it. Same
 * private IPv4 interfaces and bounded board listener as career cards. */
final class GroupRatingSync {
    private static final SecureRandom RNG=new SecureRandom();
    private GroupRatingSync() {}
    private static String nonce() {
        byte[] random=new byte[8]; RNG.nextBytes(random);
        StringBuilder hex=new StringBuilder(16);
        for(byte b:random) hex.append(Character.forDigit((b>>4)&15,16))
            .append(Character.forDigit(b&15,16));
        return hex.toString();
    }
    static void exchange(Context context,String ip,String expectedId) throws IOException {
        if(!LanShareSettings.career(context)||!LanBoardProtocol.idValid(expectedId)) return;
        byte[] address=LanUpdateProtocol.privateAddress(ip);
        Inet4Address local=address==null?null:LanBoardClient.localInterface(address);
        if(local==null) throw new IOException("Rating peer not on local Wi-Fi");
        GroupRatingLedger.Snapshot ours=GroupRatingLedger.read(context.getFilesDir());
        String challenge=nonce();
        try(Socket socket=new Socket()) {
            socket.bind(new InetSocketAddress(local,0));
            socket.connect(new InetSocketAddress(LanUpdateProtocol.asAddress(address),LanBoardProtocol.PORT),1600);
            OutputStream out=new BufferedOutputStream(socket.getOutputStream());
            InputStream in=boundedInput(socket,6_000_000_000L);
            if(!LanShareSettings.career(context)) return;
            GroupRatingWire.line(out,GroupRatingWire.query(challenge,expectedId,ours.digest));
            out.flush();
            String[] p=GroupRatingWire.line(in,256).split("\\|",-1);
            if(p.length!=5||!GroupRatingWire.HEADER.equals(p[0])||!challenge.equals(p[1])||
                !expectedId.equals(p[2])||!GroupRatingWire.validDigest(p[3]))
                throw new IOException("Unexpected group sync response");
            int count=GroupRatingWire.count(p[4]);
            if(ours.digest.equals(p[3])) {
                if(count!=0) throw new IOException("Unexpected identical-ledger payload");
                return;
            }
            GroupRatingLedger.Snapshot received=GroupRatingWire.readRows(in,count,p[3]);
            if(!LanShareSettings.career(context)) return;
            GroupRatingWire.line(out,GroupRatingWire.PUSH+"|"+challenge+"|"+ours.digest+"|"+ours.rows.size());
            GroupRatingWire.writeRows(out,ours);
            if(!(GroupRatingWire.ACK+"|"+challenge).equals(GroupRatingWire.line(in,256)))
                throw new IOException("Rating exchange not acknowledged");
            if(LanShareSettings.career(context)) GroupRatingLedger.merge(context.getFilesDir(),received);
        }
    }
    static void serve(Context context,String request,Socket socket) throws IOException {
        if(!LanShareSettings.career(context)) return;
        String id=LanBoardIdentity.id(context);
        String[] p=GroupRatingWire.readQuery(request,id);
        GroupRatingLedger.Snapshot ours=GroupRatingLedger.read(context.getFilesDir());
        boolean same=ours.digest.equals(p[3]);
        OutputStream out=new BufferedOutputStream(socket.getOutputStream());
        if(!LanShareSettings.career(context)) return;
        GroupRatingWire.line(out,GroupRatingWire.header(p[1],id,ours,same));
        if(same) { out.flush(); return; }
        GroupRatingWire.writeRows(out,ours);
        InputStream in=boundedInput(socket,5_000_000_000L);
        String[] h=GroupRatingWire.line(in,256).split("\\|",-1);
        if(h.length!=4||!GroupRatingWire.PUSH.equals(h[0])||!p[1].equals(h[1])||!p[3].equals(h[2]))
            throw new IOException("Unexpected group receipt push");
        GroupRatingLedger.Snapshot received=GroupRatingWire.readRows(in,GroupRatingWire.count(h[3]),h[2]);
        if(!LanShareSettings.career(context)) return;
        GroupRatingLedger.merge(context.getFilesDir(),received);
        GroupRatingWire.line(out,GroupRatingWire.ACK+"|"+p[1]); out.flush();
    }
    private static InputStream boundedInput(Socket socket,long nanos) throws IOException {
        final InputStream raw=socket.getInputStream();
        final long end=System.nanoTime()+nanos;
        return new BufferedInputStream(new InputStream() {
            private void check() throws IOException {
                long left=end-System.nanoTime();
                if(left<=0||Thread.currentThread().isInterrupted()) throw new IOException("Rating sync timed out");
                socket.setSoTimeout((int)Math.max(1,left/1_000_000L));
            }
            @Override public int read() throws IOException { check(); return raw.read(); }
            @Override public int read(byte[] b,int off,int n) throws IOException {
                check(); return raw.read(b,off,n);
            }
        },16384);
    }
}
