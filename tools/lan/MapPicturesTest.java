package com.halo.decomp;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;

/** Hand-built short Xbox/CE files. No retail assets required. */
public final class MapPicturesTest {
    static final String[] names={"beavercreek","sidewinder","damnation","ratrace","prisoner","hangemhigh","chillout",
        "carousel","boardingaction","bloodgulch","wizard","putput","longest"};
    static final int xb=0x803a6000,ce=0x40440000,tags=4096;
    static ByteBuffer bytes(int n){return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);}
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    static ByteBuffer header(int version,int size){
        ByteBuffer b=bytes(size);b.putInt(0,0x68656164).putInt(4,version).putInt(8,size)
            .putInt(16,tags).putInt(20,size-tags).putInt(2044,0x666f6f74);
        return b;
    }
    static byte[] sheet(boolean compressed)throws IOException{
        ByteBuffer b=header(5,8192);
        b.putInt(tags,xb+64).putInt(tags+12,1);
        b.putInt(tags+64,0x6269746d).putInt(tags+80,xb+128).putInt(tags+84,xb+256);
        b.position(tags+128);b.put("ui\\shell\\bitmaps\\mp_map_grafix".getBytes("US-ASCII"));
        b.putInt(tags+256+0x60,14).putInt(tags+256+0x64,xb+448);
        for(int i=0;i<13;i++){
            int p=tags+448+48*i,off=2048+128*(12-i);
            int format=i==1?14:i==2?15:i==3?16:i==4?6:11;
            b.putShort(p+4,(short)4).putShort(p+6,(short)4).putShort(p+12,(short)format)
                .putShort(p+14,(short)(i==5?0:16)).putInt(p+24,off).putInt(p+28,64);
            if(format>=14){
                if(format==15)b.putLong(off,-1L);
                if(format==16)b.put(off,(byte)255);
                b.putShort(off+(format==14?0:8),(short)0xf800);
            }else if(format==6){for(int j=0;j<16;j++)b.putShort(off+j*2,(short)0x07e0);}
            else for(int j=0;j<16;j++)b.putInt(off+4*j,0xff000000|i<<8|j);
        }
        if(!compressed)return b.array();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(b.array(),0,2048);
        try(DeflaterOutputStream z=new DeflaterOutputStream(out)){z.write(b.array(),2048,b.capacity()-2048);}
        return out.toByteArray();
    }
    static byte[] custom(boolean geometry){
        ByteBuffer b=header(609,12000);
        b.putShort(0x60,(short)1).putInt(tags,ce+40).putInt(tags+12,1).putInt(tags+40,0x73636e72)
            .putInt(tags+60,ce+128);
        int scenario=tags+128;
        b.putInt(scenario+0x354,2).putInt(scenario+0x358,ce+1800);
        b.putFloat(tags+1800,3).putFloat(tags+1804,5).putFloat(tags+1852,8).putFloat(tags+1856,2);
        if(geometry){
            int f=8192,base=0x40300000;
            b.putInt(scenario+0x5a4,1).putInt(scenario+0x5a8,ce+2000);
            b.putInt(tags+2000,f).putInt(tags+2004,3000).putInt(tags+2008,base);
            b.putInt(f,base+32).putInt(f+20,0x73627370).putInt(f+32+0xb0,1).putInt(f+32+0xb4,base+384);
            b.putInt(f+384+0x48,3).putInt(f+384+0x4c,base+640);
            b.putInt(f+384+0x54,3).putInt(f+384+0x58,base+768);
            for(int i=0;i<3;i++){
                b.putInt(f+640+i*24,i).putInt(f+644+i*24,(i+1)%3);
                b.putFloat(f+768+i*16,i==0?0:10).putFloat(f+772+i*16,i==2?10:0);
            }
        }
        return b.array();
    }
    public static void main(String[] args)throws Exception{
        Path temp=Files.createTempFile("halo-map-picture-",".map");
        try{
            for(boolean compressed:new boolean[]{false,true}){
                Files.write(temp,sheet(compressed));Set<String> seen=new HashSet<>();
                StockMapPictures.read(temp.toFile(),(id,w,h,pixels)->{
                    int i=Arrays.asList(names).indexOf(id);check(i>=0&&seen.add(id)&&w==4&&h==4);
                    if(i>=1&&i<=3)check(pixels[15]==0xffff0000);
                    else if(i==4)check(pixels[15]==0xff00ff00);
                    else check(pixels[2]==(0xff000000|i<<8|(i==5?4:2)));
                });
                check(seen.size()==13);
            }
            for(boolean geometry:new boolean[]{true,false}){
                Files.write(temp,custom(geometry));int[] pic=CeMapOverview.read(temp.toFile());
                long marked=Arrays.stream(pic).filter(p->p!=0xff102338).count();
                check(geometry?marked>150:marked==18);
            }
            byte[] bad=sheet(false);ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(tags+84,-2);
            Files.write(temp,bad);
            try{StockMapPictures.read(temp.toFile(),(id,w,h,p)->{});throw new AssertionError();}catch(IOException expected){}
            Files.write(temp,Arrays.copyOf(custom(true),2500));
            try{CeMapOverview.read(temp.toFile());throw new AssertionError();}catch(IOException expected){}
        }finally{Files.delete(temp);}
        System.out.println("13 named stock frames in compressed and plain maps, byte order, DXT1/3/5, swizzles, CE outlines, starts and malformed files passed");
    }
}
