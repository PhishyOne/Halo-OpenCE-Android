package com.halo.decomp;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import java.util.ArrayList;
import java.util.List;

/** Run on Android with the release APK and this test DEX on the class path. */
public final class RoyaleWaypointTest {
    private static final class Frame extends Canvas {
        final List<String> labels = new ArrayList<>();
        Frame(Bitmap bitmap) { super(bitmap); }
        @Override public void drawText(String text, float x, float y, Paint paint) {
            labels.add(text);
            super.drawText(text,x,y,paint);
        }
        boolean has(String text) { return labels.contains(text); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Frame draw(RoyaleMinimap hud, Bitmap bitmap) {
        bitmap.eraseColor(0);
        Frame frame = new Frame(bitmap);
        hud.layout(bitmap.getWidth(),bitmap.getHeight(),0,0,0);
        hud.draw(frame);
        return frame;
    }
    private static boolean pinkNear(Bitmap bitmap, int x, int y) {
        for (int i=x-3;i<=x+3;i++) for (int j=y-3;j<=y+3;j++)
            if (bitmap.getPixel(i,j)==0xffff8bd0) return true;
        return false;
    }
    public static void main(String[] args) throws Exception {
        AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
        int cookie = (Integer)AssetManager.class.getMethod("addAssetPath",String.class).invoke(assets,args[0]);
        check(cookie!=0,"could not open release APK");
        try (java.io.InputStream in=assets.open("royale/highlands_island.png")) {
            check(in.read()==137,"missing Highlands atlas");
        }
        RoyaleMinimap hud=new RoyaleMinimap(assets);
        Bitmap image=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888);
        float[] s=hud.data;
        s[0]=RoyaleMinimap.INSERTION; s[5]=1;
        s[7]=s[8]=-1000; s[9]=s[10]=1000;
        s[13]=900; s[16]=500; s[17]=1; s[22]=s[23]=1; s[24]=1;
        draw(hud,image);
        check(!hud.mark(900,70),"compact map accepted a pin");
        hud.toggle();
        Frame atlas=draw(hud,image);
        check(atlas.has("Hold to mark a destination • Tap to close"),"missing map gesture hint");
        // The expanded square is centred at (480,243.5) and covers 2080 world units.
        float scale=540*.67f/2080f, x=480+300*scale, y=243.5f-400*scale;
        check(hud.mark(x,y),"did not accept drop pin");
        Frame flight=draw(hud,image);
        check(flight.has("◆  Pin  1524 m") && pinkNear(image,Math.round(x),Math.round(y)),"flight pin range or position");
        check(!hud.mark(299.8f,243.5f),"padded west margin accepted");
        hud.close();s[17]=0;s[0]=RoyaleMinimap.EXPLORE;
        Frame ground=draw(hud,image);
        check(ground.has("◆  Pin  1524 m")&&pinkNear(image,924,20),"wrong northeast edge bearing");
        s[3]=300;s[4]=390;
        check(draw(hud,image).has("◆  Pin  30 m")&&pinkNear(image,886,64),"local pin failed to track player movement");
        hud.toggle();draw(hud,image);
        check(hud.mark(480,243.5f),"could not move pin");
        check(draw(hud,image).has("◆  Pin  1500 m"),"wrong distance after move");
        check(hud.mark(480,243.5f),"could not clear pin");
        check(draw(hud,image).has("Hold to mark a destination • Tap to close"),"pin not cleared");
        hud.mark(x,y);hud.clearPin();hud.close();s[24]=0;
        check(!draw(hud,image).has("◆  Pin  30 m"),"pin leaked into next map");
        System.out.println("PASS RoyaleWaypointTest: Pelican and on-foot HUD, map bounds, bearings, distance, move, clear and reset");
    }
}
