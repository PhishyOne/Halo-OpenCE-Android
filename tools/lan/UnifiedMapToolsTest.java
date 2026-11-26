package com.halo.decomp;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Production unified importer and custom map catalog/range transport. */
public final class UnifiedMapToolsTest {
    static int checks;
    static void check(boolean value,String message) {
        checks++;if(!value)throw new AssertionError(message);
    }
    interface Io {void run()throws Exception;}
    static void reject(Io action,String message)throws Exception {
        checks++;
        try{action.run();}catch(IOException expected){return;}
        throw new AssertionError(message);
    }
    static byte[] ce(String name,int size) {
        byte[] b=new byte[size];ByteBuffer v=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        System.arraycopy("daeh".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,b,0,4);
        v.putInt(4,609);v.putInt(8,size);v.putInt(16,2048);v.putInt(20,128);
        System.arraycopy(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,b,32,name.length());
        System.arraycopy("toof".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,b,2044,4);
        return b;
    }
    static byte[] preview() {
        byte[] b=new byte[58];ByteBuffer v=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        b[0]='B';b[1]='M';v.putInt(2,b.length);v.putInt(10,54);v.putInt(14,40);
        v.putInt(18,1);v.putInt(22,1);v.putShort(26,(short)1);v.putShort(28,(short)24);
        return b;
    }
    static byte[] resource() {
        byte[] b=new byte[36];ByteBuffer v=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        v.putInt(0,1);v.putInt(4,20);v.putInt(8,24);v.putInt(12,1);
        b[20]='x';v.putInt(24,0);v.putInt(28,4);v.putInt(32,16);return b;
    }
    static byte[] zip(Map<String,byte[]> files)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(bytes)){
            for(Map.Entry<String,byte[]> f:files.entrySet()){
                z.putNextEntry(new ZipEntry(f.getKey()));z.write(f.getValue());z.closeEntry();
            }
        }return bytes.toByteArray();
    }
    static String imp(File root,String name,byte[] bytes)throws IOException {
        return UnifiedMapImport.install(new ByteArrayInputStream(bytes),root,name,s->{});
    }
    static byte[] request(String name,byte[] hash,long offset,int length)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(b);
        out.writeInt(LanCustomMapTransfer.MAGIC);out.writeByte(2);out.writeUTF(name);
        out.write(hash);out.writeLong(offset);out.writeInt(length);return b.toByteArray();
    }
    static int response(byte[] request,List<LanCustomMapTransfer.Source> sources)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        LanCustomMapTransfer.serve(new ByteArrayInputStream(request),out,sources);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(out.toByteArray()));
        check(in.readInt()==LanCustomMapTransfer.MAGIC,"custom response magic");return in.readUnsignedByte();
    }
    static final class Peer implements AutoCloseable {
        final ServerSocket listener;
        final Thread thread;
        volatile boolean closed,cut;
        final List<LanCustomMapTransfer.Source> sources;
        Peer(List<LanCustomMapTransfer.Source> sources)throws IOException {
            this.sources=sources;listener=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
            thread=new Thread(()->{
                while(!closed)try(Socket socket=listener.accept()){
                    socket.setSoTimeout(2000);
                    DataInputStream in=new DataInputStream(socket.getInputStream());
                    byte[] header=new byte[5];in.readFully(header);
                    OutputStream out=socket.getOutputStream();
                    if(cut&&header[4]==2){
                        cut=false;final OutputStream target=out;
                        out=new OutputStream(){
                            int remaining=100057;
                            public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
                            public void write(byte[] b,int off,int n)throws IOException{
                                int take=Math.min(n,remaining);target.write(b,off,take);target.flush();remaining-=take;
                                if(take<n||remaining==0)throw new IOException("Injected disconnect");
                            }
                        };
                    }
                    LanCustomMapTransfer.serve(new SequenceInputStream(new ByteArrayInputStream(header),in),out,this.sources);
                }catch(IOException ignored){}
            },"test-custom-peer");thread.start();
        }
        LanMapTransfer.Connect connection(){return ()->{Socket s=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort());s.setSoTimeout(3000);return s;};}
        public void close()throws Exception{closed=true;listener.close();thread.join(3000);check(!thread.isAlive(),"server stopped");}
    }
    public static void main(String[] args)throws Exception {
        Path host=Files.createTempDirectory("halo-unified-host"),client=Files.createTempDirectory("halo-unified-client");
        try {
            Files.createDirectory(host.resolve("maps"));Files.createDirectory(client.resolve("maps"));
            byte[] stock=LanMapTransferTest.map("b30",8192);
            byte[] map=ce("arena",LanMapTransfer.CHUNK+65536);
            check(imp(host.toFile(),"renamed.bin",stock).contains("PASS"),"stock detected by actual header");
            check(Files.isRegularFile(host.resolve("maps/b30.map")),"stock repair destination");
            check(imp(host.toFile(),"arena.map",map).contains("PASS"),"CE direct import");
            Map<String,byte[]> mixed=new LinkedHashMap<>();
            mixed.put("maps/b30.map",stock);mixed.put("custom/arena.bmp",preview());
            mixed.put("custom/bitmaps.map",resource());mixed.put("custom/other.map",ce("other",2304));
            mixed.put("CAMPAIGN-MAP-INVENTORY.txt",new byte[]{65});
            String report=imp(host.toFile(),"mixed.zip",zip(mixed));
            check(!report.contains("FAIL"),"mixed archive "+report);
            check(Files.exists(host.resolve("custom_maps/arena.bmp")),"preview imported");
            check(Files.exists(host.resolve("custom_maps/bitmaps.map")),"resource imported");
            check(Files.exists(host.resolve("custom_maps/other.map")),"second custom map");
            check(imp(host.toFile(),"bad.zip",zip(Collections.singletonMap("../evil.map",ce("evil",2304)))).contains("FAIL"),"ZIP traversal rejected");
            check(!Files.exists(host.resolve("evil.map")),"no archive path traversal");
            reject(()->imp(host.toFile(),"arena.map",map),"existing CE file untouched");
            reject(()->imp(host.toFile(),"fake.map",new byte[100]),"unknown cache rejected");
            check(LanCustomMapTransfer.catalog(host.toFile(),Collections.emptyList(),s->{},()->false).isEmpty(),"original-only selection stays independent");
            List<LanCustomMapTransfer.Source> sources=LanCustomMapTransfer.catalog(host.toFile(),
                List.of("arena.map"),s->{},()->false);
            check(sources.size()==3,"selected map and dependencies only");
            check(sources.get(0).offer.name.equals("bitmaps.map"),"resources first");
            LanCustomMapTransfer.Offer offer=sources.stream().filter(s->s.offer.name.equals("arena.map")).findFirst().get().offer;
            check(response(request("../save/u",offer.digest,0,16),sources)==2,"arbitrary path rejected");
            check(response(request("other.map",offer.digest,0,16),sources)==2,"unselected map rejected");
            check(response(request(offer.name,new byte[32],0,16),sources)==2,"wrong digest rejected");
            check(response(request(offer.name,offer.digest,-1,16),sources)==2,"negative offset rejected");
            check(response(request(offer.name,offer.digest,0,LanMapTransfer.CHUNK+1),sources)==2,"oversize range rejected");
            check(response(request(offer.name,offer.digest,offer.size-1,2),sources)==2,"range beyond end rejected");
            reject(()->new LanCustomMapTransfer.Offer("../x.map",10,new byte[32]),"bad metadata");
            try(Peer peer=new Peer(sources)){
                List<LanCustomMapTransfer.Offer> offered=LanCustomMapTransfer.list(peer.connection());
                check(offered.size()==3,"actual TCP catalog");
                check(!LanCustomMapTransfer.installed(client.toFile(),offer,()->false),"missing map");
                peer.cut=true;
                reject(()->LanCustomMapTransfer.download(peer.connection(),offer,client.toFile(),s->{},()->false),"injected disconnect");
                Path partial=client.resolve("lan-custom-map-download/current.part");
                check(Files.size(partial)>0&&Files.size(partial)<offer.size,"partial retained");
                File completed=LanCustomMapTransfer.download(peer.connection(),offer,client.toFile(),s->{},()->false);
                check(Arrays.equals(map,Files.readAllBytes(completed.toPath())),"resumed exact map bytes");
                LanCustomMapTransfer.install(completed,client.toFile(),offer,()->false);
                check(!Files.exists(partial),"partial removed after verified install");
                check(LanCustomMapTransfer.installed(client.toFile(),offer,()->false),"identical installed map skipped");
                byte[] changed=map.clone();changed[changed.length-1]=1;
                Path target=client.resolve("custom_maps/arena.map");Files.write(target,changed);
                reject(()->LanCustomMapTransfer.installed(client.toFile(),offer,()->false),"different installed map not overwritten");
                check(Arrays.equals(changed,Files.readAllBytes(target)),"existing different data retained");
            }
            check(Arrays.equals(map,Files.readAllBytes(host.resolve("custom_maps/arena.map"))),"sharing read-only");
        } finally {LanMapTransferTest.erase(host);LanMapTransferTest.erase(client);}
        System.out.println("PASS UnifiedMapToolsTest: "+checks+" checks");
    }
}
