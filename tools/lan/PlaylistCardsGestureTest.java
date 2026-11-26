package com.halo.decomp;

import android.app.Activity;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlaylistCardsGestureTest {
    Activity a;
    PlaylistCards list;
    ScrollView scroll;
    List<String> order;
    List<View> rows=new ArrayList<>(),grips=new ArrayList<>();
    int moves,taps;
    long time=100;
    @Before public void setup(){
        a=Robolectric.buildActivity(Activity.class).setup().get();
        order=new ArrayList<>(Arrays.asList("A","B","C","D","E","F"));
        list=new PlaylistCards(a,(from,to)->{order.add(to,order.remove(from));moves++;});
        for(int i=0;i<order.size();i++){
            LinearLayout row=new LinearLayout(a);
            Button grip=new Button(a);
            row.addView(grip,65,60+(i%3)*20);
            grip.setOnClickListener(v->taps++);
            list.addCard(row,grip);rows.add(row);grips.add(grip);
        }
        scroll=new ScrollView(a);scroll.addView(list);
        a.setContentView(scroll);layout(600);
    }
    void layout(int h){
        scroll.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
        scroll.layout(0,0,400,h);
    }
    float y(View card){int[] xy=new int[2];card.getLocationOnScreen(xy);return xy[1]+20;}
    void send(int i,int action,float y){
        MotionEvent e=MotionEvent.obtain(100,++time,action,12,y,0);
        try{grips.get(i).dispatchTouchEvent(e);}finally{e.recycle();}
    }
    void advance(int ms){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    @Test public void fullRowFollowsFingerAndNeighborsMoveBeforeRelease(){
        View card=rows.get(0);float start=y(card);
        send(0,MotionEvent.ACTION_DOWN,start);send(0,MotionEvent.ACTION_MOVE,start+160);
        assertEquals(160,card.getTranslationY(),1);
        assertTrue(card.getTranslationZ()>0);
        assertEquals(0,moves);
        advance(200);
        assertEquals(-card.getHeight(),rows.get(1).getTranslationY(),1);
        assertEquals(-card.getHeight(),rows.get(2).getTranslationY(),1);
        send(0,MotionEvent.ACTION_UP,start+160);
        layout(600);advance(180);
        assertSame(card,list.getChildAt(2));
        assertEquals(Arrays.asList("B","C","A","D","E","F"),order);assertEquals(1,moves);
        start=y(card);send(0,MotionEvent.ACTION_DOWN,start);send(0,MotionEvent.ACTION_MOVE,start-300);
        send(0,MotionEvent.ACTION_UP,start-300);layout(600);advance(200);
        assertSame(card,list.getChildAt(0));assertEquals(Arrays.asList("A","B","C","D","E","F"),order);
    }
    @Test public void cancelAndTapLeaveOrderAlone(){
        float start=y(rows.get(2));send(2,0,start);send(2,2,start-95);
        assertTrue(rows.get(2).getTranslationY()<0);send(2,MotionEvent.ACTION_CANCEL,start-95);advance(200);
        assertEquals(0,moves);assertEquals(0,rows.get(2).getTranslationY(),1);
        send(2,0,start);send(2,MotionEvent.ACTION_UP,start+1);assertEquals(1,taps);assertEquals(0,moves);
    }
    @Test public void scrollEdgeReachesOffscreenCard(){
        layout(180);float start=y(rows.get(0));
        int[] xy=new int[2];scroll.getLocationOnScreen(xy);float edge=xy[1]+177;
        send(0,0,start);send(0,2,edge);advance(1200);
        assertTrue(scroll.getScrollY()>100);
        send(0,1,edge);layout(180);advance(200);
        assertSame(rows.get(0),list.getChildAt(5));assertEquals("A",order.get(5));
    }
}
