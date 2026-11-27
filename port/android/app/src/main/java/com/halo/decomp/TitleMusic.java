package com.halo.decomp;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Render the installed Xbox title tag into a seekable PCM file. A map may
 * store sound chunks in a different order from the musical sequence. */
final class TitleMusic {
    private static final int MAX_PCM = 96 << 20;
    private static final byte[] TITLE = "sound\\music\\title1\\title1".getBytes(StandardCharsets.US_ASCII);
    private static final int[] INDEX = {-1,-1,-1,-1,2,4,6,8};
    private static final int[] STEP = {
        7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,
        97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,
        724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,
        3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,
        15289,16818,18500,20350,22385,24623,27086,29794,32767};
    private final List<Chunk> chunks = new ArrayList<>();
    private int pcmBytes, rate, channels;

    private static final class Chunk {
        final int source, bytes, codec, target;
        Chunk(int source, int bytes, int codec, int target) {
            this.source=source; this.bytes=bytes; this.codec=codec; this.target=target;
        }
    }
    private static void require(boolean condition) throws IOException {
        if (!condition) throw new IOException("Unsupported or incomplete Xbox title music");
    }
    private TitleMusic(StockMapPictures.XboxMap map) throws IOException {
        ByteBuffer t=map.tags;
        int count=StockMapPictures.bounded(t.getInt(12),65536);
        int rows=StockMapPictures.tag(t,t.getInt(0),count*32);
        for (int i=0;i<count;i++) {
            int row=rows+32*i;
            if (t.getInt(row)!=0x6c736e64) continue; // lsnd
            int name=StockMapPictures.tag(t,t.getInt(row+16),1);
            if (name+TITLE.length>=t.limit() || t.get(name+TITLE.length)!=0) continue;
            int k=0;
            while (k<TITLE.length && t.get(name+k)==TITLE[k]) k++;
            if (k!=TITLE.length) continue;
            int definition=StockMapPictures.tag(t,t.getInt(row+20),0x54);
            require(t.getInt(definition+0x3c)==1);
            int track=StockMapPictures.tag(t,t.getInt(definition+0x40),0xa0);
            int intro=t.getInt(track+0x30+12), loop=t.getInt(track+0x40+12);
            if (intro!=-1) appendSound(map,rows,count,intro);
            if (loop!=-1 && loop!=intro) appendSound(map,rows,count,loop);
            require(!chunks.isEmpty());
            return;
        }
        throw new IOException("Title music tag missing from ui.map");
    }

    private void appendSound(StockMapPictures.XboxMap map,int rows,int count,int id) throws IOException {
        ByteBuffer t=map.tags;
        int index=id&65535;
        require(index<count);
        int row=rows+index*32;
        require(t.getInt(row)==0x736e6421 && t.getInt(row+12)==id); // snd!
        int sound=StockMapPictures.tag(t,t.getInt(row+20),0xa4);
        int sampleRate=t.getShort(sound+6), encoding=t.getShort(sound+0x6c), codec=t.getShort(sound+0x6e);
        require((sampleRate==0 || sampleRate==1) && (encoding==0 || encoding==1) && (codec==0 || codec==1));
        int hz=sampleRate==0?22050:44100;
        require(chunks.isEmpty() || (hz==rate && channels==encoding+1));
        rate=hz; channels=encoding+1;
        require(t.getInt(sound+0x98)==1);
        int range=StockMapPictures.tag(t,t.getInt(sound+0x9c),0x48);
        int parts=StockMapPictures.bounded(t.getInt(range+0x3c),4096);
        int table=StockMapPictures.tag(t,t.getInt(range+0x40),parts*0x7c);
        int heads=t.getShort(range+0x2c);
        require(heads>0 && heads<=parts);
        boolean linked=(t.getInt(sound)&2)!=0;
        boolean[] used=new boolean[parts];
        for (int head=0;head<heads;head++) {
            int part=head;
            while (part!=-1) {
                require(part>=0 && part<parts && !used[part]);
                used[part]=true;
                int p=table+part*0x7c;
                int length=t.getInt(p+0x40), source=t.getInt(p+0x48);
                int unit=(codec==0?2:36)*channels;
                require(length>0 && length%unit==0 && source>=2048 && (long)source+length<=map.end);
                long expanded=codec==0?length:(long)length/unit*64*channels*2;
                require(expanded<=MAX_PCM-pcmBytes);
                chunks.add(new Chunk(source,length,codec,pcmBytes+44));
                pcmBytes+=(int)expanded;
                part=linked?t.getShort(p+0x2a):-1;
            }
        }
    }

    // Match the game mixer: the first sample is the header predictor and the
    // last nibble is padding, including for interleaved stereo blocks.
    static void decodeBlock(byte[] data,int pos,int channels,byte[] output,int at) {
        for (int ch=0;ch<channels;ch++) {
            int header=pos+ch*4;
            int sample=(short)((data[header]&255)|(data[header+1]<<8));
            int index=Math.min(88,data[header+2]&255);
            for (int frame=0;frame<64;frame++) {
                if (frame!=0) {
                    int n=frame-1;
                    int b=data[pos+channels*4+((n/8)*channels+ch)*4+(n%8)/2];
                    int nibble=(b>>((n&1)*4))&15, step=STEP[index];
                    int delta=(step>>3)+((nibble&1)!=0?step>>2:0)+((nibble&2)!=0?step>>1:0)+((nibble&4)!=0?step:0);
                    sample=Math.max(-32768,Math.min(32767,sample+((nibble&8)!=0?-delta:delta)));
                    index=Math.max(0,Math.min(88,index+INDEX[nibble&7]));
                }
                int offset=at+(frame*channels+ch)*2;
                output[offset]=(byte)sample; output[offset+1]=(byte)(sample>>8);
            }
        }
    }
    private void write(File source,boolean compressed,RandomAccessFile out) throws IOException {
        ByteBuffer h=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0x46464952).putInt(pcmBytes+36).putInt(0x45564157).putInt(0x20746d66).putInt(16);
        h.putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*2)
            .putShort((short)(channels*2)).putShort((short)16).putInt(0x61746164).putInt(pcmBytes);
        out.write(h.array());
        chunks.sort(Comparator.comparingInt(c->c.source));
        byte[] input=new byte[36*channels*128], decoded=new byte[128*channels*128];
        try (DataInputStream in=StockMapPictures.body(source,compressed)) {
            int at=2048;
            for (Chunk c:chunks) {
                require(c.source>=at);
                StockMapPictures.advance(in,c.source-at);
                out.seek(c.target);
                for (int left=c.bytes;left>0;) {
                    int n=Math.min(left,input.length);
                    in.readFully(input,0,n);
                    if (c.codec==0) out.write(input,0,n);
                    else {
                        int blocks=n/(36*channels);
                        for (int b=0;b<blocks;b++) decodeBlock(input,b*36*channels,channels,decoded,b*128*channels);
                        out.write(decoded,0,blocks*128*channels);
                    }
                    left-=n;
                }
                at=c.source+c.bytes;
            }
        }
        require(out.length()==44L+pcmBytes);
    }

    static synchronized File prepare(File root,File source) throws IOException {
        File file=new File(root,"title-music-full.wav");
        long modified=source.lastModified(), size=source.length();
        require(modified>0 && size>2048);
        if (file.isFile() && file.lastModified()==modified && file.length()>44) return file;
        StockMapPictures.XboxMap map=new StockMapPictures.XboxMap(source);
        TitleMusic music=new TitleMusic(map);
        File temp=File.createTempFile("title-music-",".tmp",root);
        try {
            try (RandomAccessFile out=new RandomAccessFile(temp,"rw")) {
                music.write(source,map.compressed,out);
                out.getFD().sync();
            }
            require(source.lastModified()==modified && source.length()==size && temp.setLastModified(modified));
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            return file;
        } finally { Files.deleteIfExists(temp.toPath()); }
    }
}
