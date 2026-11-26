package com.halo.decomp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Disk reads, decompression and overview generation stay off the launcher thread. */
final class WorkshopPictures {
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final LruCache<String,Bitmap> images=new LruCache<String,Bitmap>(5*1024*1024){
        @Override protected int sizeOf(String key,Bitmap value){return value.getByteCount();}
    };
    private static String attemptedSheet="";
    private static String version(File f){return f.getAbsolutePath()+"/"+f.length()+"/"+f.lastModified();}
    static void show(ImageView view,WorkshopMapCatalog.MapItem map){
        WeakReference<ImageView> target=new WeakReference<>(view);
        File cache=view.getContext().getCacheDir();
        worker.execute(()->{
            if(target.get()==null)return;
            boolean stock=!map.id.startsWith("ce:");
            File sheet=new File(map.file.getParentFile(),"ui.map");
            String key=map.id+"|"+version(map.file)+(stock?"|"+version(sheet):"")+
                (map.preview==null?"":"|"+version(map.preview));
            Bitmap result=images.get(key);
            if(result==null){
                if(map.preview!=null)result=decode(map.preview);
                if(result==null)try{
                    if(stock)result=stock(cache,sheet,map.id);
                    else result=Bitmap.createBitmap(CeMapOverview.read(map.file),
                        CeMapOverview.W,CeMapOverview.H,Bitmap.Config.ARGB_8888);
                }catch(IOException|RuntimeException unavailable){/* The underlying map stays selectable. */}
                if(result!=null)images.put(key,result);
            }
            final Bitmap bitmap=result;
            if(bitmap!=null)main.post(()->{
                ImageView image=target.get();
                if(image!=null){image.setImageBitmap(bitmap);image.setVisibility(android.view.View.VISIBLE);}
            });
        });
    }
    private static Bitmap decode(File file){
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getPath(),o);
        if(o.outWidth<1||o.outHeight<1||o.outWidth>2048||o.outHeight>2048)return null;
        o.inJustDecodeBounds=false;o.inSampleSize=1;
        while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>384)o.inSampleSize*=2;
        return BitmapFactory.decodeFile(file.getPath(),o);
    }
    private static Bitmap stock(File cache,File sheet,String id)throws IOException{
        if(Files.isSymbolicLink(sheet.toPath())||!sheet.isFile())return null;
        String stamp=version(sheet);
        File dir=new File(cache,"workshop-map-pictures");
        String prefix=Long.toHexString(sheet.length())+"-"+Long.toHexString(sheet.lastModified())+"-";
        File picture=new File(dir,prefix+id+".png");
        Bitmap ready=images.get(stamp+"|"+id);
        if(ready!=null)return ready;
        if(picture.isFile()){ready=decode(picture);if(ready!=null)return ready;}
        if(!attemptedSheet.equals(stamp)){
            attemptedSheet=stamp;
            boolean disk=dir.isDirectory()||dir.mkdirs();
            if(disk){
                File[] old=dir.listFiles(f->!f.getName().startsWith(prefix));
                if(old!=null)for(File f:old)if(f.isFile())f.delete();
            }
            StockMapPictures.read(sheet,(map,w,h,pixels)->{
                Bitmap original=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
                images.put(stamp+"|"+map,original);
                if(disk){
                    File dst=new File(dir,prefix+map+".png"),tmp=new File(dir,prefix+map+".tmp");
                    try{
                        try(FileOutputStream out=new FileOutputStream(tmp)){
                            if(!original.compress(Bitmap.CompressFormat.PNG,100,out))return;
                        }
                        tmp.renameTo(dst);
                    }catch(IOException full){/* In-memory pictures are still available. */}
                    finally{tmp.delete();}
                }
            });
        }
        ready=images.get(stamp+"|"+id);
        return ready!=null?ready:picture.isFile()?decode(picture):null;
    }
}
