package com.halo.decomp;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Top-down image of an installed CE map's collision edges and player starts.
 * Addresses are checked relative to their owning tag or BSP before every read. */
final class CeMapOverview {
    static final int W=256,H=176;
    private static final class Region {
        final RandomAccessFile in;
        final long start,length,base;
        Region(RandomAccessFile in,long start,long length,long base)throws IOException{
            if(start<0||length<0||start>in.length()-length)throw new IOException("Map region outside file");
            this.in=in;this.start=start;this.length=length;this.base=base;
        }
        ByteBuffer read(long addr,int len)throws IOException{
            long offset=addr-base;
            if(len<0||offset<0||offset>length-len)throw new IOException("Map block outside region");
            byte[] buf=new byte[len];in.seek(start+offset);in.readFully(buf);
            return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        }
        ByteBuffer block(ByteBuffer parent,int field,int stride,int max)throws IOException{
            int count=parent.getInt(field);
            if(count<0||count>max)throw new IOException("Map block count out of range");
            if(count==0)return ByteBuffer.allocate(0);
            return read(ptr(parent,field+4),count*stride);
        }
    }
    private static long ptr(ByteBuffer b,int p){return Integer.toUnsignedLong(b.getInt(p));}
    private static boolean valid(float n){return Float.isFinite(n)&&Math.abs(n)<1000000;}
    private static final class Bounds {
        float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=-minX,maxY=-minX,maxZ=-minX;
        void add(float x,float y,float z){minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);
            maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);}
    }
    static int[] read(File file)throws IOException{
        try(RandomAccessFile in=new RandomAccessFile(file,"r")){
            Region fileRegion=new Region(in,0,in.length(),0);
            ByteBuffer header=fileRegion.read(0,2048);
            if(header.getInt(0)!=0x68656164||header.getInt(4)!=609||header.getShort(0x60)!=1||
                    header.getInt(0x7fc)!=0x666f6f74)throw new IOException("Expected CE multiplayer map");
            long base=0x40440000L;
            Region tags=new Region(in,ptr(header,16),ptr(header,20),base);
            ByteBuffer index=tags.read(base,40);
            int slot=index.getInt(4)&65535;
            if(slot>=index.getInt(12))throw new IOException("Missing scenario");
            ByteBuffer row=tags.read(ptr(index,0)+32L*slot,32);
            if(row.getInt(0)!=0x73636e72)throw new IOException("Missing scenario tag");
            ByteBuffer scenario=tags.read(ptr(row,20),0x5b0);
            ByteBuffer spawns=tags.block(scenario,0x354,52,4096);
            Bounds bounds=new Bounds();
            ArrayList<float[]> segments=new ArrayList<>();
            ArrayList<float[]> starts=new ArrayList<>();
            for(int i=0;i<spawns.capacity();i+=52){
                float x=spawns.getFloat(i),y=spawns.getFloat(i+4),z=spawns.getFloat(i+8);
                if(valid(x)&&valid(y)&&valid(z)){starts.add(new float[]{x,y});bounds.add(x,y,z);}
            }
            ByteBuffer refs=tags.block(scenario,0x5a4,32,32);
            for(int i=0;i<refs.capacity();i+=32){
                try{
                    Region bsp=new Region(in,ptr(refs,i),ptr(refs,i+4),ptr(refs,i+8));
                    ByteBuffer root=bsp.read(bsp.base,24);
                    if(root.getInt(20)!=0x73627370)continue;
                    ByteBuffer structure=bsp.read(ptr(root,0),0xbc);
                    ByteBuffer collision=bsp.block(structure,0xb0,96,1);
                    if(collision.capacity()==0)continue;
                    ByteBuffer edges=bsp.block(collision,0x48,24,262144);
                    ByteBuffer vertices=bsp.block(collision,0x54,16,131072);
                    int count=edges.capacity()/24;
                    int budget=Math.max(128,12000/(refs.capacity()/32));
                    int step=Math.max(1,(count+budget-1)/budget);
                    for(int j=0;j<count;j+=step){
                        int a=edges.getInt(j*24),b=edges.getInt(j*24+4);
                        if(a<0||b<0||a>=vertices.capacity()/16||b>=vertices.capacity()/16)continue;
                        a*=16;b*=16;
                        float x=vertices.getFloat(a),y=vertices.getFloat(a+4),z=vertices.getFloat(a+8);
                        float u=vertices.getFloat(b),v=vertices.getFloat(b+4),w=vertices.getFloat(b+8);
                        if(valid(x)&&valid(y)&&valid(z)&&valid(u)&&valid(v)&&valid(w)){
                            segments.add(new float[]{x,y,u,v,(z+w)/2});
                            bounds.add(x,y,z);bounds.add(u,v,w);
                        }
                    }
                }catch(IOException unsupported){/* Other BSPs and scenario starts can still form a preview. */}
            }
            if(segments.isEmpty()&&starts.isEmpty())throw new IOException("Map has no geometry or player starts");
            return draw(segments,starts,bounds);
        }
    }
    private static int[] draw(List<float[]> edges,List<float[]> starts,Bounds b){
        float scale=Math.min((W-14)/Math.max(1,b.maxX-b.minX),(H-14)/Math.max(1,b.maxY-b.minY));
        float cx=(b.minX+b.maxX)/2,cy=(b.minY+b.maxY)/2;
        int[] image=new int[W*H];Arrays.fill(image,0xff102338);
        edges.sort(Comparator.comparingDouble(e->e[4]));
        for(float[] e:edges){
            int shade=Math.round(100*(e[4]-b.minZ)/Math.max(1,b.maxZ-b.minZ));
            int color=0xff000000|(42+shade)<<16|(89+shade)<<8|115+shade;
            int x=Math.round(W/2f+(e[0]-cx)*scale),y=Math.round(H/2f-(e[1]-cy)*scale);
            int u=Math.round(W/2f+(e[2]-cx)*scale),v=Math.round(H/2f-(e[3]-cy)*scale);
            int steps=Math.max(1,Math.max(Math.abs(x-u),Math.abs(y-v)));
            for(int i=0;i<=steps;i++)image[(y+(v-y)*i/steps)*W+x+(u-x)*i/steps]=color;
        }
        for(float[] p:starts){
            int x=Math.round(W/2f+(p[0]-cx)*scale),y=Math.round(H/2f-(p[1]-cy)*scale);
            for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)image[(y+dy)*W+x+dx]=0xffffc879;
        }
        return image;
    }
}
