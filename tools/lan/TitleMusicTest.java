package com.halo.decomp;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.zip.DeflaterOutputStream;

public final class TitleMusicTest {
    static final int BASE=0x803a6000, ROWS=64, PARTS=1024, BYTES=22050*2*31;
    static final int TAGS=2048+2*BYTES, SIZE=TAGS+2048;
    static byte[] fixture(boolean cyclic, boolean compressed) throws Exception {
        ByteBuffer b=ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,0x68656164).putInt(4,5).putInt(8,SIZE).putInt(16,TAGS)
            .putInt(20,2048).putInt(2044,0x666f6f74);
        b.putInt(TAGS,BASE+ROWS).putInt(TAGS+12,2);
        b.putInt(TAGS+ROWS,0x6c736e64).putInt(TAGS+ROWS+16,BASE+144).putInt(TAGS+ROWS+20,BASE+192);
        b.position(TAGS+144);b.put("sound\\music\\title1\\title1".getBytes("US-ASCII"));
        int row=TAGS+ROWS+32, id=0x17420001;
        b.putInt(row,0x736e6421).putInt(row+12,id).putInt(row+20,BASE+640);
        b.putInt(TAGS+192+0x3c,1).putInt(TAGS+192+0x40,BASE+320);
        b.putInt(TAGS+320+0x3c,-1).putInt(TAGS+320+0x4c,id);
        int sound=TAGS+640, range=TAGS+840;
        b.putInt(sound,2).putInt(sound+0x98,1).putInt(sound+0x9c,BASE+840);
        b.putShort(range+0x2c,(short)1).putInt(range+0x3c,2).putInt(range+0x40,BASE+PARTS);
        for(int i=0;i<2;i++){
            int p=TAGS+PARTS+i*0x7c, source=2048+(1-i)*BYTES;
            b.putShort(p+0x2a,(short)(i==0?1:cyclic?0:-1)).putInt(p+0x40,BYTES).putInt(p+0x48,source);
            b.putShort(source,(short)(i+42));b.putShort(source+BYTES-2,(short)(i+142));
        }
        if(!compressed)return b.array();
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(b.array(),0,2048);
        try(DeflaterOutputStream z=new DeflaterOutputStream(out)){z.write(b.array(),2048,SIZE-2048);}
        return out.toByteArray();
    }
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("title-music");
        Path map=root.resolve("ui.map"), wave=root.resolve("title-music-full.wav");
        try{
            for(boolean compressed:new boolean[]{false,true}){
                Files.write(map,fixture(false,compressed));
                File result=TitleMusic.prepare(root.toFile(),map.toFile());
                ByteBuffer b=ByteBuffer.wrap(Files.readAllBytes(result.toPath())).order(ByteOrder.LITTLE_ENDIAN);
                check(b.getInt(40)==2*BYTES && b.getInt(24)==22050 && b.getShort(44)==42);
                check(b.getShort(44+BYTES)==43 && b.getShort(b.capacity()-2)==143);
                check((b.capacity()-44)/b.getInt(28)==62);
                Files.delete(result.toPath());
            }
            Files.write(map,fixture(true,false));
            try{TitleMusic.prepare(root.toFile(),map.toFile());throw new AssertionError("cycle accepted");}
            catch(IOException expected){check(!Files.exists(wave));}
            byte[] block=new byte[72], pcm=new byte[256];
            block[0]=11;block[4]=22;block[8]=7;block[71]=(byte)0xf0;
            TitleMusic.decodeBlock(block,0,2,pcm,0);
            check(pcm[0]==11 && pcm[2]==22 && pcm[4]==22 && pcm[254]==22);
        }finally{Files.deleteIfExists(wave);Files.deleteIfExists(map);Files.delete(root);}
        System.out.println("PASS original title music: 62 seconds, both map encodings, linked order, final sample, invalid chain, stereo Xbox ADPCM");
    }
}
