package com.halo.decomp;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.InflaterInputStream;

/** Reads only the multiplayer picture sheet from the player's Xbox ui.map.
 * Tags and pixels live in different parts of a zlib stream. Two bounded passes
 * avoid creating a second copy of the expanded map on the phone. */
final class StockMapPictures {
    static final String[] IDS={"beavercreek","sidewinder","damnation","ratrace","prisoner","hangemhigh",
        "chillout","carousel","boardingaction","bloodgulch","wizard","putput","longest"};
    private static final long TAG_BASE=0x803a6000L;
    private static final byte[] NAME="ui\\shell\\bitmaps\\mp_map_grafix".getBytes(StandardCharsets.US_ASCII);
    interface Sink { void picture(String id,int width,int height,int[] argb) throws IOException; }

    private static int bounded(long value,long limit)throws IOException{
        if(value<0||value>limit)throw new IOException("Map picture offset or size is out of range");
        return (int)value;
    }
    private static int tag(ByteBuffer tags,int pointer,int bytes)throws IOException{
        return bounded(Integer.toUnsignedLong(pointer)-TAG_BASE,tags.capacity()-(long)bytes);
    }
    private static DataInputStream body(File file,boolean compressed)throws IOException{
        FileInputStream f=new FileInputStream(file);
        try{
            f.getChannel().position(2048);
            InputStream buffered=new BufferedInputStream(f,65536);
            return new DataInputStream(compressed?new InflaterInputStream(buffered):buffered);
        }catch(IOException e){f.close();throw e;}
    }
    private static void advance(DataInputStream in,int distance)throws IOException{
        while(distance>0){
            int skipped=in.skipBytes(distance);
            if(skipped==0){in.readByte();skipped=1;}
            distance-=skipped;
        }
    }
    static void read(File file,Sink sink)throws IOException{
        byte[] head=new byte[2048];
        boolean compressed;
        try(DataInputStream in=new DataInputStream(new FileInputStream(file))){
            in.readFully(head);
            int cmf=in.readUnsignedByte(),flg=in.readUnsignedByte();
            compressed=(cmf&15)==8&&(cmf>>>4)<=7&&((cmf*256+flg)%31)==0;
        }
        ByteBuffer h=little(head);
        if(h.getInt(0)!=0x68656164||h.getInt(4)!=5||h.getInt(0x7fc)!=0x666f6f74)
            throw new IOException("Expected an Xbox ui.map");
        int end=bounded(h.getInt(8),256<<20),size=bounded(h.getInt(20),32<<20);
        int start=bounded(h.getInt(16),end-(long)size);
        if(start<2048||size<36)throw new IOException("Missing map picture tags");
        List<Frame> frames;
        try(DataInputStream in=body(file,compressed)){
            advance(in,start-2048);
            byte[] tags=new byte[size];
            in.readFully(tags);
            frames=findFrames(little(tags),end);
        }
        Collections.sort(frames,Comparator.comparingInt(f->f.offset));
        try(DataInputStream in=body(file,compressed)){
            int at=2048;
            for(Frame frame:frames){
                if(frame.offset<at)throw new IOException("Overlapping map pictures");
                advance(in,frame.offset-at);
                byte[] pixels=new byte[frame.size];
                in.readFully(pixels);
                sink.picture(IDS[frame.index],frame.width,frame.height,frame.decode(pixels));
                at=frame.offset+pixels.length;
            }
        }
    }
    private static ByteBuffer little(byte[] bytes){return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static List<Frame> findFrames(ByteBuffer t,int end)throws IOException{
        int count=bounded(t.getInt(12),65536);
        int rows=tag(t,t.getInt(0),32*count);
        for(int i=0;i<count;i++){
            int row=rows+32*i;
            if(t.getInt(row)!=0x6269746d)continue;
            int name=tag(t,t.getInt(row+16),1);
            if(name+NAME.length>=t.limit()||t.get(name+NAME.length)!=0)continue;
            int n=0;while(n<NAME.length&&t.get(name+n)==NAME[n])n++;
            if(n!=NAME.length)continue;
            int group=tag(t,t.getInt(row+20),0x6c);
            if(t.getInt(group+0x60)<IDS.length)throw new IOException("Incomplete multiplayer picture sheet");
            int entries=tag(t,t.getInt(group+0x64),IDS.length*48);
            List<Frame> frames=new ArrayList<>();
            for(i=0;i<IDS.length;i++)frames.add(new Frame(t,entries+i*48,i,end));
            return frames;
        }
        throw new IOException("Multiplayer picture sheet not found");
    }
    private static final class Frame {
        final int index,width,height,format,flags,offset,size,unit;
        Frame(ByteBuffer t,int p,int index,int end)throws IOException{
            this.index=index;
            width=bounded(t.getShort(p+4),512);height=bounded(t.getShort(p+6),512);
            if(width==0||height==0||t.getShort(p+10)!=0)throw new IOException("Invalid picture dimensions");
            format=t.getShort(p+12);flags=t.getShort(p+14);
            switch(format){
            case 0:case 1:case 2:unit=1;break;
            case 3:case 6:case 8:case 9:unit=2;break;
            case 10:case 11:unit=4;break;
            case 14:unit=8;break;
            case 15:case 16:unit=16;break;
            default:throw new IOException("Unknown picture format "+format);
            }
            size=format>=14?((width+3)/4)*((height+3)/4)*unit:width*height*unit;
            if(size>t.getInt(p+28))throw new IOException("Truncated picture");
            offset=bounded(t.getInt(p+24),end-(long)size);
        }
        int[] decode(byte[] pixels)throws IOException{
            int[] out=new int[width*height];
            ByteBuffer data=little(pixels);
            if(format>=14){decodeBlocks(data,out);return out;}
            boolean linear=(flags&16)!=0;
            if(!linear&&(Integer.bitCount(width)!=1||Integer.bitCount(height)!=1))
                throw new IOException("Invalid swizzled dimensions");
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                int i=y*width+x,p=(linear?i:swizzled(x,y,width,height))*unit;
                int v=data.get(p)&255;
                switch(format){
                case 0:out[i]=v<<24|0xffffff;break;
                case 1:out[i]=0xff000000|v*0x10101;break;
                case 2:out[i]=v*0x1010101;break;
                case 3:out[i]=data.get(p+1)<<24|v*0x10101;break;
                case 6:out[i]=rgb565(data.getShort(p)&65535);break;
                case 8:
                    v=data.getShort(p)&65535;
                    out[i]=(v>>>15)*255<<24|(v>>>10&31)*255/31<<16|(v>>>5&31)*255/31<<8|(v&31)*255/31;
                    break;
                case 9:
                    v=data.getShort(p)&65535;
                    out[i]=(v>>>12)*17<<24|(v>>>8&15)*17<<16|(v>>>4&15)*17<<8|(v&15)*17;
                    break;
                default:out[i]=data.getInt(p)|(format==10?0xff000000:0);
                }
            }
            return out;
        }
        private void decodeBlocks(ByteBuffer data,int[] out){
            int[] colors=new int[4],alpha=new int[8];
            int block=0;
            for(int y=0;y<height;y+=4)for(int x=0;x<width;x+=4,block+=unit){
                int c=block+unit-8,a=data.getShort(c)&65535,b=data.getShort(c+2)&65535;
                colors[0]=rgb565(a);colors[1]=rgb565(b);
                boolean four=format!=14||a>b;
                colors[2]=mix(colors[0],colors[1],four?2:1,1);
                colors[3]=four?mix(colors[0],colors[1],1,2):0;
                int codes=data.getInt(c+4);
                long alphas=format==14?0:data.getLong(block);
                if(format==16){
                    alpha[0]=(int)alphas&255;alpha[1]=(int)(alphas>>>8)&255;
                    int divisor=alpha[0]>alpha[1]?7:5;
                    for(int k=1;k<divisor;k++)alpha[1+k]=((divisor-k)*alpha[0]+k*alpha[1])/divisor;
                    if(divisor==5){alpha[6]=0;alpha[7]=255;}
                    alphas>>>=16;
                }
                for(int j=0;j<16;j++){
                    int px=x+j%4,py=y+j/4;
                    if(px>=width||py>=height)continue;
                    int color=colors[codes>>>(2*j)&3];
                    if(format==15)color=(color&0xffffff)|((int)(alphas>>>(j*4))&15)*17<<24;
                    if(format==16)color=(color&0xffffff)|alpha[(int)(alphas>>>(j*3))&7]<<24;
                    out[py*width+px]=color;
                }
            }
        }
    }
    private static int swizzled(int x,int y,int w,int h){
        int i=0,bit=1;
        for(int mask=1;mask<w||mask<h;mask<<=1){
            if(mask<w){if((x&mask)!=0)i|=bit;bit<<=1;}
            if(mask<h){if((y&mask)!=0)i|=bit;bit<<=1;}
        }
        return i;
    }
    private static int rgb565(int x){
        return 0xff000000|(x>>>11)*255/31<<16|(x>>>5&63)*255/63<<8|(x&31)*255/31;
    }
    private static int mix(int a,int b,int wa,int wb){
        int color=0xff000000;
        for(int shift=0;shift<=16;shift+=8)
            color|=(((a>>>shift&255)*wa+(b>>>shift&255)*wb)/(wa+wb))<<shift;
        return color;
    }
}
