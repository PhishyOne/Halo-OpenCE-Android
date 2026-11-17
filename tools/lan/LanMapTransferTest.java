package com.halo.decomp;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class LanMapTransferTest {
    static int checks;
    static void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
    interface Io { void run() throws Exception; }
    static void reject(Io run, String reason) throws Exception {
        checks++; try { run.run(); } catch (IOException expected) { return; } throw new AssertionError(reason);
    }
    static byte[] map(String name, int size) {
        byte[] b = new byte[size]; ByteBuffer v = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        System.arraycopy(new byte[]{'d','a','e','h'},0,b,0,4);v.putInt(4,5);v.putInt(8,size);v.putInt(16,2304);v.putInt(20,128);
        System.arraycopy(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,b,32,name.length());
        System.arraycopy(new byte[]{'t','o','o','f'},0,b,2044,4);v.putInt(2316,17);
        System.arraycopy(new byte[]{'s','g','a','t'},0,b,2336,4); return b;
    }
    static byte[] request(int id, byte[] digest, long offset, int length) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(LanMapTransfer.MAGIC);out.writeByte(LanMapTransfer.GET);out.writeByte(id);out.write(digest);out.writeLong(offset);out.writeInt(length);return bytes.toByteArray();
    }
    static int response(byte[] request, List<LanMapTransfer.Source> sources) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();LanMapTransfer.serve(new ByteArrayInputStream(request),bytes,sources);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));check(in.readInt()==LanMapTransfer.MAGIC,"Response magic");return in.readUnsignedByte();
    }
    static class Peer implements AutoCloseable {
        final ServerSocket listener;
        final Thread thread;
        volatile List<LanMapTransfer.Source> sources;
        volatile boolean cut, wrongRange, closed;
        final List<Long> offsets = Collections.synchronizedList(new ArrayList<>());
        Peer(List<LanMapTransfer.Source> sources) throws IOException {
            this.sources=sources;listener=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
            thread=new Thread(()->{
                while(!closed) try(Socket socket=listener.accept()) {
                    socket.setSoTimeout(2000);
                    DataInputStream in=new DataInputStream(socket.getInputStream());byte[] header=new byte[5];in.readFully(header);
                    byte[] request=header;
                    if(header[4]==LanMapTransfer.GET) {
                        request=new byte[50];System.arraycopy(header,0,request,0,5);in.readFully(request,5,45);
                        long offset=ByteBuffer.wrap(request).getLong(38);offsets.add(offset);
                        if(wrongRange) {
                            wrongRange=false;DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                            out.writeInt(LanMapTransfer.MAGIC);out.writeByte(0);out.writeLong(this.sources.get(0).offer.size);
                            out.writeLong(offset+1);out.writeInt(ByteBuffer.wrap(request).getInt(46));out.write(this.sources.get(0).offer.digest);out.flush();continue;
                        }
                    }
                    OutputStream out=socket.getOutputStream();
                    if(cut && header[4]==LanMapTransfer.GET) {
                        cut=false;final OutputStream target=out;
                        out=new OutputStream(){int left=100057;
                            public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
                            public void write(byte[] b,int off,int n)throws IOException{
                                int take=Math.min(left,n);target.write(b,off,take);target.flush();left-=take;
                                if(take<n || left==0) throw new IOException("Injected disconnect");
                            }};
                    }
                    LanMapTransfer.serve(new SequenceInputStream(new ByteArrayInputStream(request),in),out,this.sources);
                }catch(IOException ignored){ }
            },"test-map-peer");thread.start();
        }
        LanMapTransfer.Connect connection(){return ()->{Socket s=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort());s.setSoTimeout(2500);return s;};}
        public void close()throws Exception{closed=true;listener.close();thread.join(3000);check(!thread.isAlive(),"Peer thread leaked");}
    }
    static void manifest(byte[] response) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread writer = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000); new DataInputStream(socket.getInputStream()).readFully(new byte[5]);
                    socket.getOutputStream().write(response); socket.getOutputStream().flush();
                } catch (IOException ignored) { }
            });
            writer.start();
            try { LanMapTransfer.list(() -> {
                Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
                socket.setSoTimeout(2000); return socket;
            }); } finally { writer.join(3000); }
        }
    }
    static void erase(Path root)throws IOException{try(java.util.stream.Stream<Path> p=Files.walk(root)){p.sorted(Comparator.reverseOrder()).forEach(f->{try{Files.delete(f);}catch(IOException e){throw new RuntimeException(e);}});}}
    public static void main(String[] args)throws Exception{
        Path host=Files.createTempDirectory("halo-share-host-"),client=Files.createTempDirectory("halo-share-client-");
        try {
            Path maps=Files.createDirectory(host.resolve("maps"));Files.createDirectory(client.resolve("maps"));
            byte[] good=map("b30",LanMapTransfer.CHUNK+65536);Files.write(maps.resolve("b30.map"),good);
            Files.write(maps.resolve("c10.map"),new byte[2048]);
            List<LanMapTransfer.Source> sources=LanMapTransfer.catalog(host.toFile(),s->{},()->false);
            check(sources.size()==1 && sources.get(0).offer.id==3,"Only valid maps advertised");
            LanMapTransfer.Offer offer=sources.get(0).offer;
            check(response(request(255,offer.digest,0,100),sources)==LanMapTransfer.DENIED,"Unknown ID");
            check(response(request(3,offer.digest,-1,100),sources)==LanMapTransfer.DENIED,"Negative offset");
            check(response(request(3,offer.digest,0,LanMapTransfer.CHUNK+1),sources)==LanMapTransfer.DENIED,"Oversized chunk");
            check(response(request(3,offer.digest,offer.size-1,2),sources)==LanMapTransfer.DENIED,"Range exceeds file");
            check(response(request(3,new byte[32],0,100),sources)==LanMapTransfer.DENIED,"Wrong snapshot digest");
            reject(()->new LanMapTransfer.Offer(11,5000,new byte[32]),"ID outside allowlist");
            reject(()->new LanMapTransfer.Offer(0,LanMapTransfer.MAX_MAP+1,new byte[32]),"Unbounded map size");
            reject(()->LanMapTransfer.connect("example.org"),"DNS accepted");
            reject(()->LanMapTransfer.connect("8.8.8.8"),"Public network accepted");
            check(!LanMapTransfer.healthy(client.toFile(),offer),"Missing map is healthy");
            try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                 LanMapTransfer.DeadlineSocket timed = new LanMapTransfer.DeadlineSocket(150)) {
                timed.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort()));
                try (Socket stalled = listener.accept()) {
                    long began = System.nanoTime();
                    reject(() -> timed.getInputStream().read(), "Client absolute deadline ignored");
                    check((System.nanoTime() - began) / 1000000 < 2000, "Client deadline did not bound read");
                }
            }

            ByteArrayOutputStream encoded = new ByteArrayOutputStream(); DataOutputStream fields = new DataOutputStream(encoded);
            fields.writeInt(LanMapTransfer.MAGIC); fields.writeByte(0); fields.writeByte(12);
            reject(() -> manifest(encoded.toByteArray()), "Oversized manifest count accepted");
            encoded.reset(); fields.writeInt(LanMapTransfer.MAGIC); fields.writeByte(0); fields.writeByte(2);
            for (int i=0;i<2;i++) { fields.writeByte(3); fields.writeLong(offer.size); fields.write(offer.digest); }
            reject(() -> manifest(encoded.toByteArray()), "Duplicate manifest ID accepted");
            encoded.reset(); fields.writeInt(LanMapTransfer.MAGIC); fields.writeByte(0); fields.writeByte(1);
            fields.writeByte(3); fields.writeLong(-1); fields.write(offer.digest);
            reject(() -> manifest(encoded.toByteArray()), "Negative manifest length accepted");
            encoded.reset(); fields.writeInt(LanMapTransfer.MAGIC); fields.writeByte(0); fields.writeByte(1);
            reject(() -> manifest(encoded.toByteArray()), "Truncated manifest accepted");

            try(Peer peer=new Peer(sources)){
                List<LanMapTransfer.Offer> offered=LanMapTransfer.list(peer.connection());
                check(offered.size()==1 && offered.get(0).key().equals(offer.key()),"Manifest changed in transit");
                peer.cut=true;
                reject(()->LanMapTransfer.download(peer.connection(),offer,client.toFile(),s->{},()->false),"Disconnect accepted");
                Path part=client.resolve("lan-map-download/current.part");long held=Files.size(part);
                check(held==100000,"Partial bytes not preserved");
                peer.offsets.clear();
                File result=LanMapTransfer.download(peer.connection(),offer,client.toFile(),s->{},()->false);
                check(peer.offsets.get(0)==held,"Resume restarted instead of appending");
                check(Arrays.equals(Files.readAllBytes(result.toPath()),good),"Resumed map differs");
                Files.write(client.resolve("maps/b30.map"),new byte[]{1,2,3});
                check(LanMapTransfer.install(result,client.toFile(),()->false).contains("repaired"),"Verified install failed");
                check(Arrays.equals(Files.readAllBytes(client.resolve("maps/b30.map.previous")),new byte[]{1,2,3}),"Original backup lost");
                check(!Files.exists(part) && LanMapTransfer.healthy(client.toFile(),offer),"Install not finalized");
                peer.wrongRange=true;
                reject(()->LanMapTransfer.download(peer.connection(),offer,client.toFile(),s->{},()->false),"Wrong range accepted");
                check(!Files.exists(part),"Wrong range wrote data");
                AtomicBoolean stop=new AtomicBoolean();
                reject(()->LanMapTransfer.download(peer.connection(),offer,client.toFile(),s->stop.set(true),stop::get),"Cancel ignored");
                check(Files.size(part)>0 && Files.size(part)<offer.size,"Cancel lost partial");
                good[good.length-1]=17;Files.write(maps.resolve("b30.map"),good);
                check(response(request(3,offer.digest,0,10),sources)==LanMapTransfer.CHANGED,"Changed host source served");
                List<LanMapTransfer.Source> newer=LanMapTransfer.catalog(host.toFile(),s->{},()->false);peer.sources=newer;peer.offsets.clear();
                File fresh=LanMapTransfer.download(peer.connection(),newer.get(0).offer,client.toFile(),s->{},()->false);
                check(peer.offsets.get(0)==0,"Different checksum reused stale partial");
                check(Arrays.equals(Files.readAllBytes(fresh.toPath()),good),"New snapshot bytes wrong");
                byte[] damaged=good.clone();damaged[damaged.length-1]^=1;Files.write(fresh.toPath(),damaged);
                reject(()->LanMapTransfer.download(peer.connection(),newer.get(0).offer,client.toFile(),s->{},()->false),"Corrupt completed partial accepted");
                check(!Files.exists(part),"Corrupt partial not cleared");
            }
            Path outside=Files.createTempDirectory("halo-share-outside-");
            try {Files.delete(client.resolve("lan-map-download/current.key"));Files.delete(client.resolve("lan-map-download"));
                Files.createSymbolicLink(client.resolve("lan-map-download"),outside);
                reject(()->LanMapTransfer.download(()->{throw new IOException("No connection expected");},offer,client.toFile(),s->{},()->false),"Symlink staging accepted");
                check(Files.list(outside).count()==0,"Symlink target modified");Files.delete(client.resolve("lan-map-download"));
            }finally{erase(outside);}
            check(Arrays.equals(Files.readAllBytes(client.resolve("maps/b30.map.previous")),new byte[]{1,2,3}),"Rejected data changed backup");
        }finally{erase(host);erase(client);}
        System.out.println("LanMapTransferTest PASS ("+checks+" checks, real sockets, disconnect and resume)");
    }
}
