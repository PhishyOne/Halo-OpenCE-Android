package com.halo.decomp;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.IdentityHashMap;

/** The lifted match stays under the user's finger even as the surrounding panel scrolls. */
final class PlaylistCards extends LinearLayout {
    interface Reorder { void move(int from,int to); }
    private final Reorder reorder;
    private final int slop;
    private final int[] location=new int[2];
    private View active;
    private ScrollView scroll;
    private int pointer,from,target;
    private float downY,fingerY,grabY;
    private boolean dragging;
    private IdentityHashMap<View,Float> settling;

    PlaylistCards(Context context,Reorder reorder) {
        super(context);
        this.reorder=reorder;
        slop=ViewConfiguration.get(context).getScaledTouchSlop();
        setOrientation(VERTICAL);
    }
    void addCard(View card,View handle) {
        addView(card,new LayoutParams(-1,-2));
        handle.setOnTouchListener((view,event)->touch(card,view,event));
    }
    private boolean touch(View card,View handle,MotionEvent event) {
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN) {
            if(active!=null)end(false);
            clearAnimations();
            card.getLocationOnScreen(location);
            active=card;
            from=target=indexOfChild(card);
            pointer=event.getPointerId(0);
            downY=fingerY=event.getRawY();
            grabY=fingerY-location[1];
            scroll=null;
            for(ViewParent p=getParent();p!=null;p=p.getParent())
                if(p instanceof ScrollView){scroll=(ScrollView)p;break;}
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if(active!=card)return false;
        if(action==MotionEvent.ACTION_CANCEL ||
                (action==MotionEvent.ACTION_POINTER_UP&&event.getPointerId(event.getActionIndex())==pointer)) {
            end(false);
            return true;
        }
        int i=event.findPointerIndex(pointer);
        if(i<0){end(false);return true;}
        fingerY=event.getRawY()+event.getY(i)-event.getY(0);
        if(action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP) {
            if(!dragging && Math.abs(fingerY-downY)>slop) {
                dragging=true;
                active.setTranslationZ(dp(8));
                postOnAnimation(edgeScroll);
            }
            if(dragging)position();
            if(action==MotionEvent.ACTION_UP) {
                boolean tap=!dragging;
                end(true);
                if(tap)handle.performClick();
            }
        }
        return true;
    }
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    private void position() {
        getLocationOnScreen(location);
        float top=Math.max(0,Math.min(fingerY-location[1]-grabY,getHeight()-active.getHeight()));
        active.setTranslationY(top-active.getTop());
        int next=from;
        if(top>active.getTop()) {
            while(next+1<getChildCount()) {
                View below=getChildAt(next+1);
                if(top+active.getHeight()<=below.getTop()+below.getHeight()/2f)break;
                next++;
            }
        } else {
            while(next>0) {
                View above=getChildAt(next-1);
                if(top>=above.getTop()+above.getHeight()/2f)break;
                next--;
            }
        }
        if(next==target)return;
        target=next;
        for(int i=0;i<getChildCount();i++) {
            if(i==from)continue;
            int shift=i>from&&i<=target?-active.getHeight():i<from&&i>=target?active.getHeight():0;
            getChildAt(i).animate().translationY(shift).setDuration(135).start();
        }
    }
    private final Runnable edgeScroll=new Runnable(){
        public void run(){
            if(!dragging||scroll==null)return;
            scroll.getLocationOnScreen(location);
            float edge=Math.min(dp(40),scroll.getHeight()/4f);
            if(edge>0){
                float y=fingerY-location[1];
                float pressure=y<edge?(y-edge)/edge:y>scroll.getHeight()-edge?(y-scroll.getHeight()+edge)/edge:0;
                int distance=Math.round(dp(9)*Math.max(-1,Math.min(1,pressure)));
                if(distance!=0&&scroll.canScrollVertically(distance>0?1:-1)){
                    scroll.scrollBy(0,distance);
                    position();
                }
            }
            postOnAnimation(this);
        }
    };
    private void end(boolean commit) {
        if(active==null)return;
        View card=active;
        active=null;
        removeCallbacks(edgeScroll);
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
        card.setTranslationZ(0);
        if(dragging && commit && from!=target)moveCard(card,target);
        else if(dragging)for(int i=0;i<getChildCount();i++)
            getChildAt(i).animate().translationY(0).setDuration(135).start();
        dragging=false;
        scroll=null;
    }
    void moveCard(View card,int to) {
        int old=indexOfChild(card);
        if(old<0||to<0||to>=getChildCount()||old==to)return;
        settling=new IdentityHashMap<>();
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);
            child.animate().cancel();
            settling.put(child,child.getY());
        }
        removeViewAt(old);
        addView(card,to);
        reorder.move(old,to);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        super.onLayout(changed,l,t,r,b);
        if(settling==null)return;
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);
            Float old=settling.get(child);
            if(old!=null)child.setTranslationY(old-child.getTop());
            child.animate().translationY(0).setDuration(135).start();
        }
        settling=null;
    }
    private void clearAnimations(){
        settling=null;
        for(int i=0;i<getChildCount();i++){
            View child=getChildAt(i);
            child.animate().cancel();
            child.setTranslationY(0);
        }
    }
    @Override protected void onDetachedFromWindow(){
        end(false);
        clearAnimations();
        super.onDetachedFromWindow();
    }
}
