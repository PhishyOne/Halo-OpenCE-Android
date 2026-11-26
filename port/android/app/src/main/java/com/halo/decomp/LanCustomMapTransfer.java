package com.halo.decomp;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.util.*;

/** Separate custom-content catalog on the map-only listener. Explicit
 * selection, safe basenames, SHA-256 and bounded resumable ranges.
 * Never exposes original maps, saves, APKs, or other app storage. */
final class LanCustomMapTransfer {
    static final int MAGIC=0x484d4331; // HMC1
    static final int MAX_FILES=128;
    private static final int LIST=1, GET=2, OK=0, DENIED=2;
    private static final long MAX_PREVIEW=16L*1024*1024;
    private LanCustomMapTransfer() { }

    static final class Offer {
        final String name;
        final long size;
        final byte[] digest;
        Offer(String name,long size,byte[] digest)throws IOException {
            if(!CustomMapImporter.isImportName(name) || size<(name.endsWith(".bmp")?54:16) ||
                size>(name.endsWith(".bmp")?MAX_PREVIEW:LanMapTransfer.MAX_MAP) ||
                digest==null || digest.length!=32) throw new IOException("Invalid shared map metadata");
            this.name=name;this.size=size;this.digest=digest.clone();
        }
        String key(){return name+":"+size+":"+LanMapTransfer.hex(digest);}
    }
    static final class Source {
        final Offer offer;final File file;final long modified;
        Source(Offer offer,File file,long modified){this.offer=offer;this.file=file;this.modified=modified;}
    }
    static boolean resource(String name) {
        return "bitmaps.map".equals(name)||"sounds.map".equals(name)||"loc.map".equals(name);
    }
    private static File customFolder(File root)throws IOException {
        File dir=new File(root,"custom_maps");
        if(Files.isSymbolicLink(dir.toPath()) || (dir.exists()&&!dir.isDirectory()))
            throw new IOException("Unsafe custom map folder");
        return dir;
    }
    static List<String> names(File root)throws IOException {
        File dir=customFolder(root);
        File[] files=dir.listFiles();
        if(files==null){if(dir.exists())throw new IOException("Cannot read custom maps");return Collections.emptyList();}
        List<String> result=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(File f:files) {
            String name=f.getName();
            if(!CustomMapImporter.isImportName(name)||!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS))continue;
            if(!seen.add(name.toLowerCase(Locale.ROOT)))throw new IOException("Ambiguous custom map filename: "+name);
            result.add(name);
        }
        Collections.sort(result,String.CASE_INSENSITIVE_ORDER);
        if(result.size()>MAX_FILES)throw new IOException("More than "+MAX_FILES+" custom files; import or transfer smaller sets.");
        return result;
    }
    /** Selecting a level includes its matching preview and available
     * shared CE resources. No unrelated custom levels are added. */
    static List<String> dependencies(Collection<String> available,Collection<String> selected) {
        Set<String> result=new LinkedHashSet<>();
        boolean level=false;
        for(String name:selected) {
            if(!available.contains(name))continue;
            result.add(name);
            if(name.endsWith(".map")&&!resource(name)) {
                level=true;
                String preview=name.substring(0,name.length()-4)+".bmp";
                if(available.contains(preview))result.add(preview);
            }
        }
        if(level)for(String name:available)if(resource(name))result.add(name);
        // Install resource files before dependent levels.
        List<String> ordered=new ArrayList<>(result);
        ordered.sort(Comparator.comparingInt((String name)->resource(name)?0:name.endsWith(".map")?1:2)
            .thenComparing(String.CASE_INSENSITIVE_ORDER));
        return ordered;
    }
    static List<Source> catalog(File root,Collection<String> selected,
            LanMapTransfer.Progress progress,LanMapTransfer.Stop stop)throws IOException {
        if(selected.isEmpty())return Collections.emptyList();
        File dir=customFolder(root);
        List<String> chosen=dependencies(names(root),selected);
        List<Source> result=new ArrayList<>();
        for(String name:chosen) {
            LanMapTransfer.canceled(stop);
            File file=new File(dir,name);
            progress.show("Verifying "+name+"...");
            long size=file.length(), modified=file.lastModified();
            CustomMapImporter.validateImport(file,name);
            Offer offer=new Offer(name,size,LanMapTransfer.hash(file,stop));
            if(size!=file.length()||modified!=file.lastModified())throw new IOException(name+" changed while checking");
            result.add(new Source(offer,file,modified));
        }
        return Collections.unmodifiableList(result);
    }
    private static void status(DataOutputStream out,int code)throws IOException {
        out.writeInt(MAGIC);out.writeByte(code);
    }
    private static void response(DataInputStream in)throws IOException {
        if(in.readInt()!=MAGIC||in.readUnsignedByte()!=OK)
            throw new IOException("Phone is not sharing the requested custom map");
    }
    static List<Offer> list(LanMapTransfer.Connect connect)throws IOException {
        try(Socket socket=connect.open()) {
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(MAGIC);out.writeByte(LIST);out.flush();
            DataInputStream in=new DataInputStream(socket.getInputStream());response(in);
            int count=in.readUnsignedShort();if(count>MAX_FILES)throw new IOException("Too many shared maps");
            List<Offer> result=new ArrayList<>();Set<String> seen=new HashSet<>();
            for(int i=0;i<count;i++) {
                String name=in.readUTF();long size=in.readLong();byte[] digest=new byte[32];in.readFully(digest);
                Offer offer=new Offer(name,size,digest);
                if(!seen.add(name.toLowerCase(Locale.ROOT)))throw new IOException("Duplicate shared map");
                result.add(offer);
            }
            return result;
        }
    }
    static void serve(InputStream raw,OutputStream output,List<Source> sources)throws IOException {
        DataInputStream in=new DataInputStream(raw);
        DataOutputStream out=new DataOutputStream(new BufferedOutputStream(output,65536));
        if(in.readInt()!=MAGIC){status(out,DENIED);out.flush();return;}
        int op=in.readUnsignedByte();
        if(op==LIST) {
            if(sources.size()>MAX_FILES)throw new IOException("Too many shared files");
            status(out,OK);out.writeShort(sources.size());
            for(Source s:sources){out.writeUTF(s.offer.name);out.writeLong(s.offer.size);out.write(s.offer.digest);}
        }else if(op==GET) {
            String name=in.readUTF();byte[] digest=new byte[32];in.readFully(digest);
            long offset=in.readLong();int length=in.readInt();
            Source found=null;
            for(Source s:sources)if(s.offer.name.equals(name))found=s;
            if(found==null||offset<0||length<1||length>LanMapTransfer.CHUNK||
                offset>found.offer.size-length||!Arrays.equals(digest,found.offer.digest)||
                !Files.isRegularFile(found.file.toPath(),LinkOption.NOFOLLOW_LINKS)||
                found.file.length()!=found.offer.size||found.file.lastModified()!=found.modified) {
                status(out,DENIED);out.flush();return;
            }
            try(SeekableByteChannel map=Files.newByteChannel(found.file.toPath(),
                    StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                if(map.size()!=found.offer.size){status(out,DENIED);out.flush();return;}
                map.position(offset);status(out,OK);out.writeLong(found.offer.size);
                out.writeLong(offset);out.writeInt(length);out.write(found.offer.digest);
                byte[] bytes=new byte[65536];int left=length;
                while(left>0) {
                    ByteBuffer buffer=ByteBuffer.wrap(bytes,0,Math.min(bytes.length,left));
                    int n=map.read(buffer);
                    if(n<=0)throw new IOException("Shared file changed during transfer");
                    out.write(bytes,0,n);left-=n;
                }
            }
        }else status(out,DENIED);
        out.flush();
    }
    private static void safe(File f,boolean directory)throws IOException {
        if(Files.isSymbolicLink(f.toPath())||(f.exists()&&(directory?!f.isDirectory():!f.isFile())))
            throw new IOException("Unsafe custom map download storage");
    }
    static boolean installed(File root,Offer offer,LanMapTransfer.Stop stop)throws IOException {
        File target=new File(customFolder(root),offer.name);
        if(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            for(String name:names(root)) if(name.equalsIgnoreCase(offer.name)) {
                target=new File(customFolder(root),name);break;
            }
        }
        if(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))return false;
        CustomMapImporter.validateImport(target,offer.name);
        if(target.length()!=offer.size||!Arrays.equals(LanMapTransfer.hash(target,stop),offer.digest))
            throw new IOException(offer.name+" is already installed with different contents. No files overwritten.");
        return true;
    }
    static File download(LanMapTransfer.Connect connect,Offer offer,File root,
            LanMapTransfer.Progress progress,LanMapTransfer.Stop stop)throws IOException {
        File dir=new File(root,"lan-custom-map-download");safe(dir,true);
        if(!dir.isDirectory()&&!dir.mkdir())throw new IOException("Cannot create custom download folder");
        File part=new File(dir,"current.part"),meta=new File(dir,"current.key");safe(part,false);safe(meta,false);
        String old=meta.isFile()&&meta.length()<=256?
            new String(Files.readAllBytes(meta.toPath()),StandardCharsets.US_ASCII):"";
        if(!offer.key().equals(old)||part.length()>offer.size) {
            Files.deleteIfExists(part.toPath());
            try(FileOutputStream out=new FileOutputStream(meta)){
                out.write(offer.key().getBytes(StandardCharsets.US_ASCII));out.getFD().sync();
            }
        }
        long offset=part.length();
        if(root.getUsableSpace()<offer.size+(offer.size-offset)+16L*1024*1024)
            throw new IOException("Not enough storage to receive and safely install "+offer.name);
        while(offset<offer.size) {
            LanMapTransfer.canceled(stop);
            try(Socket socket=connect.open()) {
                DataOutputStream out=new DataOutputStream(socket.getOutputStream());
                int requested=(int)Math.min(LanMapTransfer.CHUNK,offer.size-offset);
                out.writeInt(MAGIC);out.writeByte(GET);out.writeUTF(offer.name);out.write(offer.digest);
                out.writeLong(offset);out.writeInt(requested);out.flush();
                DataInputStream in=new DataInputStream(new BufferedInputStream(socket.getInputStream(),65536));
                response(in);
                long size=in.readLong(),start=in.readLong();int length=in.readInt();
                byte[] digest=new byte[32];in.readFully(digest);
                if(size!=offer.size||start!=offset||length!=requested||!Arrays.equals(digest,offer.digest))
                    throw new IOException("Unexpected custom map range");
                try(FileOutputStream file=new FileOutputStream(part,true)) {
                    try {
                        byte[] buffer=new byte[65536];int left=length;
                        while(left>0) {
                            LanMapTransfer.canceled(stop);
                            int n=in.read(buffer,0,Math.min(left,buffer.length));
                            if(n<0)throw new IOException("Connection lost; receive the same map again to resume.");
                            if(n==0)continue;
                            file.write(buffer,0,n);offset+=n;left-=n;
                            progress.show(offer.name+": "+(offset*100/offer.size)+"%");
                        }
                    }finally{file.getFD().sync();}
                }
            }
        }
        LanMapTransfer.canceled(stop);
        if(!Arrays.equals(LanMapTransfer.hash(part,stop),offer.digest)) {
            Files.deleteIfExists(part.toPath());throw new IOException("Custom map checksum failed; retry.");
        }
        try{CustomMapImporter.validateImport(part,offer.name);}
        catch(IOException invalid){Files.deleteIfExists(part.toPath());throw invalid;}
        return part;
    }
    static void install(File downloaded,File root,Offer offer,LanMapTransfer.Stop stop)throws IOException {
        LanMapTransfer.canceled(stop);
        try(InputStream in=new FileInputStream(downloaded)) {
            CustomMapImporter.install(in,root,offer.name);
        }
        Files.delete(downloaded.toPath());
    }
}
