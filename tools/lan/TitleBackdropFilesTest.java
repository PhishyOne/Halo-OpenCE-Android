package com.halo.decomp;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.Comparator;

/** Bounded cache format, complete-only publication, and disposable staging. */
public final class TitleBackdropFilesTest {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    interface Io{void run()throws Exception;}
    static void reject(Io fn,String why)throws Exception{
        checks++;try{fn.run();}catch(IOException expected){return;}throw new AssertionError(why);
    }
    static void audio(File target,int seconds)throws IOException{
        int bytes=seconds*192000;
        byte[] h=new byte[44];ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        System.arraycopy("RIFF".getBytes(),0,h,0,4);b.putInt(4,bytes+36);
        System.arraycopy("WAVEfmt ".getBytes(),0,h,8,8);b.putInt(16,16);
        b.putShort(20,(short)1);b.putShort(22,(short)2);b.putInt(24,48000);
        b.putInt(28,192000);b.putShort(32,(short)4);b.putShort(34,(short)16);
        System.arraycopy("data".getBytes(),0,h,36,4);b.putInt(40,bytes);
        try(RandomAccessFile f=new RandomAccessFile(target,"rw")){f.setLength(44L+bytes);f.write(h);}
    }
    static void erase(Path root)throws IOException{
        try(java.util.stream.Stream<Path> p=Files.walk(root)){
            p.sorted(Comparator.reverseOrder()).forEach(f->{try{Files.delete(f);}catch(IOException e){throw new RuntimeException(e);}});
        }
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("halo-title-test");
        try{
            File r=root.toFile();
            Files.write(root.resolve("keep-game-data"),new byte[]{42});
            check(!TitleBackdropFiles.ready(r),"missing cache does not appear ready");
            check(TitleBackdropFiles.shouldPrepare(false,-1,69),"first startup prepares");
            check(!TitleBackdropFiles.shouldPrepare(false,69,69),"cancel/crash cannot loop");
            check(TitleBackdropFiles.shouldPrepare(false,69,70),"updated build can retry");
            check(!TitleBackdropFiles.shouldPrepare(true,69,70),"valid cache survives update");
            check(!TitleBackdropFiles.shouldPrepare(false,-1,-1),"unknown version fails closed");
            TitleBackdropFiles.prepare(r);
            File pending=TitleBackdropFiles.pending(r);
            check(pending.isDirectory(),"staging created");
            File video=new File(pending,"frames.hbt"), sound=new File(pending,"audio.wav");
            reject(()->new TitleBackdropFiles.Writer(video,641,240),"invalid dimensions");
            try(TitleBackdropFiles.Writer w=new TitleBackdropFiles.Writer(video,480,214)){
                reject(()->w.frame(new byte[TitleBackdropFiles.MAX_FRAME+1]),"oversize JPEG");
                reject(()->w.frame(new byte[]{0,0,0,0}),"invalid JPEG signature");
                w.frame(new byte[]{(byte)255,(byte)216,1,(byte)217});
                reject(w::complete,"partial cache cannot complete");
            }
            audio(sound,55);
            check(TitleBackdropFiles.validAudio(sound),"canonical WAV accepted");
            check(!TitleBackdropFiles.ready(r),"staging invisible");
            reject(()->TitleBackdropFiles.commit(r),"partial frame cache cannot publish");
            TitleBackdropFiles.clearPending(r);
            check(!pending.exists(),"only staging removed");
            check(Files.readAllBytes(root.resolve("keep-game-data"))[0]==42,"unrelated file preserved");
            TitleBackdropFiles.prepare(r);
            try(TitleBackdropFiles.Writer w=new TitleBackdropFiles.Writer(video,480,214)){
                for(int i=0;i<TitleBackdropFiles.FRAMES;i++)
                    w.frame(new byte[]{(byte)255,(byte)216,(byte)i,(byte)217});
                reject(()->w.frame(new byte[]{(byte)255,(byte)216,1,(byte)217}),"frame count cap");
                w.complete();
            }
            audio(sound,54);
            check(!TitleBackdropFiles.validAudio(sound),"short audio rejected");
            reject(()->TitleBackdropFiles.commit(r),"short audio cannot publish");
            audio(sound,60);
            TitleBackdropFiles.commit(r);
            check(!pending.exists(),"atomic publication");
            check(TitleBackdropFiles.ready(r),"complete cache ready");
            try(TitleBackdropFiles.Reader read=new TitleBackdropFiles.Reader(new File(TitleBackdropFiles.directory(r),"frames.hbt"))){
                check(read.width==480&&read.height==214&&read.count==900&&read.fps==15,"dimensions and timing");
                check((read.read(511)[2]&255)==255,"indexed random frame");
                reject(()->read.read(900),"invalid index");
            }
            reject(()->TitleBackdropFiles.prepare(r),"existing valid cache protected");
            File committedAudio=new File(TitleBackdropFiles.directory(r),"audio.wav");
            try(RandomAccessFile f=new RandomAccessFile(committedAudio,"rw")){f.seek(22);f.writeShort(0);}
            check(!TitleBackdropFiles.ready(r),"corrupted audio rejected");
            TitleBackdropFiles.prepare(r);
            check(!TitleBackdropFiles.directory(r).exists(),"invalid disposable cache can regenerate");
            Files.write(pending.toPath().resolve("unexpected"),new byte[]{99});
            reject(()->TitleBackdropFiles.clearPending(r),"unexpected file protected");
            check(Files.exists(pending.toPath().resolve("unexpected")),"no recursive deletion");
            Files.delete(pending.toPath().resolve("unexpected"));
            TitleBackdropFiles.clearPending(r);
        }finally{erase(root);}
        System.out.println("PASS TitleBackdropFilesTest: "+checks+" checks");
    }
}
