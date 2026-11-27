package com.halo.decomp;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Functional local planning workshop. No socket, engine launch or fake
 * lobby is created here. The classic Host path remains authoritative until
 * the native lobby command/acknowledgement bridge is tested. */
final class MatchWorkshopUi {
    private final Activity activity;
    private final File store;
    private final LinearLayout body;
    private final List<WorkshopMapCatalog.MapItem> maps;
    private MatchWorkshop.Library library;
    private final boolean embedded;
    private final Consumer<MatchWorkshop.Library> onSaved;
    private static final int RED=0xffe78c8c, BLUE=0xff8dbfff;

    static void show(Activity activity,File dataRoot){
        try{
            MatchWorkshopUi ui=new MatchWorkshopUi(activity,dataRoot,false,null);
            ui.home();
            new HaloDialog.Builder(activity).setTitle("MATCH WORKSHOP")
                .setView(ui.body).setNegativeButton("CLOSE",null).show();
        }catch(IOException|RuntimeException error){
            HaloNotice.makeText(activity,"Workshop unavailable: "+error.getMessage(),HaloNotice.LENGTH_LONG).show();
        }
    }
    static MatchWorkshopUi attach(Activity activity,File dataRoot,Consumer<MatchWorkshop.Library> onSaved)throws IOException{
        MatchWorkshopUi ui=new MatchWorkshopUi(activity,dataRoot,true,onSaved);
        ui.home();return ui;
    }
    View view(){return body;}
    private MatchWorkshopUi(Activity a,File root,boolean embedded,Consumer<MatchWorkshop.Library> onSaved)throws IOException{
        activity=a;store=a.getFilesDir();library=MatchWorkshop.read(store);
        this.embedded=embedded;this.onSaved=onSaved;
        maps=WorkshopMapCatalog.installed(root);body=column();
    }
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout panel(){LinearLayout l=column();l.setPadding(dp(5),dp(3),dp(5),dp(3));l.setBackground(HaloTheme.surface(0x20233344,0x20101d2c,HaloTheme.BORDER,3,activity.getResources().getDisplayMetrics().density));return l;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(color);HaloTheme.font(t,bold);t.setPadding(dp(2),dp(2),dp(2),dp(2));return t;}
    private void label(LinearLayout p,String value){p.addView(text(value,12,HaloTheme.MUTED,false));}
    private Button button(String value,Runnable action){Button b=new Button(activity);b.setText(value);b.setTextSize(11);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(34));b.setPadding(dp(4),dp(2),dp(4),dp(2));HaloTheme.menuButtonColors(b);HaloTheme.font(b,true);b.setOnClickListener(v->action.run());return b;}
    private void pair(LinearLayout p,View left,View right){LinearLayout r=row();r.addView(left,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(0,-2,1);q.leftMargin=dp(5);r.addView(right,q);p.addView(r);}
    private void action(LinearLayout p,String name,Runnable run){p.addView(button(name,run),new LinearLayout.LayoutParams(-1,-2));}
    private void message(String value){HaloNotice.makeText(activity,value,HaloNotice.LENGTH_LONG).show();}
    private boolean save(MatchWorkshop.Library next){
        try{MatchWorkshop.write(store,next);library=next;if(onSaved!=null)onSaved.accept(next);return true;}
        catch(IOException|IllegalArgumentException error){message("Couldn't save workshop: "+error.getMessage());return false;}
    }
    private void choose(String title,String[] labels,int selected,Consumer<Integer> onPick){
        new HaloDialog.Builder(activity).setTitle(title)
            .setSingleChoiceItems(labels,selected,(dialog,index)->{dialog.dismiss();onPick.accept(index);})
            .setNegativeButton("CANCEL",null).show();
    }
    private CheckBox check(String name,boolean value,Consumer<Boolean> change){
        CheckBox c=new CheckBox(activity);c.setText(name);c.setTextSize(11);c.setTextColor(HaloTheme.TEXT);
        c.setButtonTintList(HaloTheme.CHECK_TINT);HaloTheme.font(c,false);c.setChecked(value);
        c.setOnCheckedChangeListener((v,b)->change.accept(b));return c;
    }
    private EditText input(String value,boolean numeric){
        EditText e=new EditText(activity);e.setText(value);e.setSingleLine(true);e.setTextSize(14);
        e.setTextColor(HaloTheme.TEXT);e.setHintTextColor(HaloTheme.MUTED);
        if(numeric)e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        return e;
    }
    private void watch(EditText input,Consumer<String> cb){
        input.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){cb.accept(s.toString());}
            public void afterTextChanged(Editable e){}
        });
    }
    private String mapName(String id){return WorkshopMapCatalog.label(maps,id);}
    private WorkshopMapCatalog.MapItem findMap(String id){for(WorkshopMapCatalog.MapItem m:maps)if(m.id.equals(id))return m;return null;}
    private View picture(String id,int width,int height){
        WorkshopMapCatalog.MapItem map=findMap(id);
        FrameLayout frame=new FrameLayout(activity);
        frame.setBackgroundColor(0xff172b40);
        TextView label=text(map==null?"MISSING MAP":"NO PREVIEW",10,HaloTheme.MUTED,true);
        label.setGravity(Gravity.CENTER);
        frame.addView(label,new FrameLayout.LayoutParams(-1,-1));
        if(map!=null){
            ImageView image=new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setVisibility(View.INVISIBLE);
            frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
            frame.setContentDescription(map.name+" map preview");
            WorkshopPictures.show(image,map);
        }
        frame.setLayoutParams(new LinearLayout.LayoutParams(dp(width),dp(height)));
        return frame;
    }
    private void carousel(LinearLayout p,String current,Consumer<String> select){
        if(maps.isEmpty()){label(p,"No installed multiplayer maps. Use Tools / Check My Maps.");return;}
        int at=-1;for(int i=0;i<maps.size();i++)if(maps.get(i).id.equals(current)){at=i;break;}
        final int index=at;
        LinearLayout images=row();images.setGravity(Gravity.CENTER);
        int prev=index<0?maps.size()-1:(index+maps.size()-1)%maps.size(),next=index<0?0:(index+1)%maps.size();
        Button left=button("‹",()->select.accept(maps.get(prev).id));left.setContentDescription("Previous map");
        images.addView(left,new LinearLayout.LayoutParams(dp(26),dp(52)));
        View before=picture(maps.get(prev).id,45,45);before.setAlpha(.55f);before.setOnClickListener(v->select.accept(maps.get(prev).id));images.addView(before,new LinearLayout.LayoutParams(0,dp(45),1));
        View center=picture(current,120,70);images.addView(center,new LinearLayout.LayoutParams(0,dp(70),2.6f));
        View after=picture(maps.get(next).id,45,45);after.setAlpha(.55f);after.setOnClickListener(v->select.accept(maps.get(next).id));images.addView(after,new LinearLayout.LayoutParams(0,dp(45),1));
        Button right=button("›",()->select.accept(maps.get(next).id));right.setContentDescription("Next map");images.addView(right,new LinearLayout.LayoutParams(dp(26),dp(52)));
        center.setContentDescription("Choose map: "+mapName(current)+". Swipe left or right, or tap for list.");
        center.setOnClickListener(v->{
            String[] names=new String[maps.size()];for(int i=0;i<names.length;i++)names[i]=maps.get(i).name;
            choose("SELECT MAP",names,index,i->select.accept(maps.get(i).id));
        });
        final float[] down={0};
        center.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){down[0]=event.getX();return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP){float delta=event.getX()-down[0];
                if(Math.abs(delta)>dp(24))select.accept(maps.get(delta<0?next:prev).id);
                else v.performClick();
                return true;}
            return true;
        });
        p.addView(images);
        TextView name=text(mapName(current),13,HaloTheme.FOCUS_TEXT,true);name.setGravity(Gravity.CENTER);p.addView(name);
    }
    private List<MatchWorkshop.Preset> modes(){
        ArrayList<MatchWorkshop.Preset> list=new ArrayList<>();
        for(String id:MatchWorkshop.BASE_IDS)list.add(MatchWorkshop.Preset.standard(id));list.addAll(library.presets);return list;
    }
    private void pickMode(MatchWorkshop.Preset current,Consumer<MatchWorkshop.Preset> choose){
        List<MatchWorkshop.Preset> list=modes();String[] names=new String[list.size()];int chosen=-1;
        for(int i=0;i<names.length;i++){MatchWorkshop.Preset p=list.get(i);names[i]=p.name+(p.id.isEmpty()?"":" • custom r"+p.revision);
            if(p.id.equals(current.id)&&p.base.equals(current.base)&&p.revision==current.revision)chosen=i;}
        choose("GAME MODE",names,chosen,i->choose.accept(list.get(i)));
    }
    private void entryFields(LinearLayout p,MatchWorkshop.Entry e,Consumer<MatchWorkshop.Entry> change,Runnable editRules){
        carousel(p,e.map,id->change.accept(new MatchWorkshop.Entry(id,e.preset,e.bots,e.killstreak)));
        action(p,"MODE: "+e.preset.name,()->pickMode(e.preset,m->change.accept(e.withPreset(m))));
        action(p,"GAME MODE WORKSHOP",editRules);
        action(p,"BOTS: "+e.bots.description(),()->editBots(e.preset.teams(),e.bots,b->change.accept(new MatchWorkshop.Entry(e.map,e.preset,b,e.killstreak))));
        p.addView(check("Killstreak rewards",e.killstreak,b->change.accept(new MatchWorkshop.Entry(e.map,e.preset,e.bots,b))));
    }
    private void plannedRoster(LinearLayout p,MatchWorkshop.Entry e,boolean first){
        label(p,first?"FIRST LISTED MATCH · PLANNED ROSTER":"PLANNED ROSTER");
        label(p,"No live lobby. Host and bots below are a preview.");
        boolean teams=e.preset.teams();
        MatchWorkshop.Allocation a=e.bots.resolve(teams,teams?1:0,0,teams?0:1,MatchWorkshop.MAX_LAN_BOTS,MatchWorkshop.MAX_PLAYERS);
        LinearLayout red=panel();red.addView(text(teams?"RED TEAM":"FREE-FOR-ALL",12,teams?RED:HaloTheme.FOCUS_TEXT,true));
        label(red,LanBoardIdentity.name(activity)+" (planned host)");
        label(red,(teams?a.red:a.ffa)+" bots · "+MatchWorkshop.DIFFICULTIES[e.bots.difficulty]);
        if(teams){LinearLayout blue=panel();blue.addView(text("BLUE TEAM",12,BLUE,true));label(blue,a.blue+" bots · "+MatchWorkshop.DIFFICULTIES[e.bots.difficulty]);pair(p,red,blue);}
        else p.addView(red);
        if(!a.warning.isEmpty())p.addView(text(a.warning,11,HaloTheme.MUTED,false));
    }
    private void home(){
        body.removeAllViews();
        if(!embedded)label(body,"Start your saved match or playlist from the Lobby.");
        Button q=button("QUICK MATCH",()->{if(save(library.select(false,library.playlistId)))home();});
        Button r=button("PLAYLIST",()->{if(save(library.select(true,library.playlistId)))home();});
        q.setSelected(!library.playlistMode);r.setSelected(library.playlistMode);pair(body,q,r);
        LinearLayout left=panel(),right=panel();
        if(!library.playlistMode){
            MatchWorkshop.Entry e=library.quick;
            entryFields(left,e,changed->{if(save(library.withQuick(changed)))home();},
                ()->ruleEditor(e.preset,chosen->{if(save(library.put(chosen).withQuick(e.withPreset(chosen))))home();},this::home));
            if(!embedded)plannedRoster(right,e,false);
        }else{
            MatchWorkshop.Playlist list=library.selected();
            action(left,list==null?"SELECT PLAYLIST":"PLAYLIST: "+list.name,()->{
                if(library.playlists.isEmpty()){editPlaylist(null);return;}
                String[] names=new String[library.playlists.size()];int selected=-1;
                for(int i=0;i<names.length;i++){MatchWorkshop.Playlist p=library.playlists.get(i);names[i]=p.name;if(p.id.equals(library.playlistId))selected=i;}
                choose("SAVED PLAYLISTS",names,selected,i->{if(save(library.select(true,library.playlists.get(i).id)))home();});
            });
            Button edit=button("EDIT PLAYLIST",()->editPlaylist(list));edit.setEnabled(list!=null);
            pair(left,button("NEW PLAYLIST",()->editPlaylist(null)),edit);
            if(list!=null){
                label(left,list.entries.size()+" matches · Shuffle "+(list.shuffle?"On":"Off")+" · Loop "+(list.loop?"On":"Off"));
                for(MatchWorkshop.Entry e:list.entries)left.addView(entryRow(e,null));
                if(!embedded&&!list.entries.isEmpty())plannedRoster(right,list.entries.get(0),true);
                else if(list.entries.isEmpty())label(right,"Add matches to the playlist.");
            }else label(right,"No playlist selected.");
        }
        if(embedded)body.addView(left);else pair(body,left,right);
        action(body,"CUSTOM MODES",this::modeLibrary);
    }
    private void modeLibrary(){
        body.removeAllViews();label(body,"Create and save a custom mode from any standard mode.");
        action(body,"NEW CUSTOM MODE",()->pickMode(MatchWorkshop.Preset.standard("team_slayer"),
            p->ruleEditor(p,saved->{if(save(library.put(saved)))modeLibrary();},this::modeLibrary)));
        for(MatchWorkshop.Preset p:library.presets)
            action(body,p.name+" • r"+p.revision+" / "+p.base,()->ruleEditor(p,saved->{if(save(library.put(saved)))modeLibrary();},this::modeLibrary));
        action(body,"BACK",this::home);
    }
    private void ruleEditor(MatchWorkshop.Preset base,Consumer<MatchWorkshop.Preset> saved,Runnable back){
        body.removeAllViews();
        label(body,"GAME MODE WORKSHOP · Base: "+MatchWorkshop.BASE_LABELS[MatchWorkshop.baseIndex(base.base)]);
        label(body,"Unset rules inherit the original mode. Saved modes are available in Lobby and Offline Match.");
        EditText name=input(base.id.isEmpty()?base.name+" Custom":base.name,false);body.addView(name);
        LinkedHashMap<String,Integer> rules=new LinkedHashMap<>(base.overrides);
        LinkedHashMap<String,List<MatchWorkshop.Rule>> sections=new LinkedHashMap<>();
        for(MatchWorkshop.Rule rule:MatchWorkshop.RULES)if(rule.available(base.base))
            sections.computeIfAbsent(rule.group,k->new ArrayList<>()).add(rule);
        for(Map.Entry<String,List<MatchWorkshop.Rule>> section:sections.entrySet()){
            LinearLayout group=panel(),rows=column();
            Button header=button(section.getKey()+"  ▸",()->{});
            header.setOnClickListener(v->{
                boolean expanded=rows.getVisibility()!=View.VISIBLE;
                rows.setVisibility(expanded?View.VISIBLE:View.GONE);
                header.setText(section.getKey()+(expanded?"  ▾":"  ▸"));
            });
            group.addView(header);group.addView(rows);rows.setVisibility(View.GONE);
            for(MatchWorkshop.Rule rule:section.getValue()){
                Button value=button(rule.label+": "+(rules.containsKey(rule.key)?rule.display(rules.get(rule.key)):"Default"),()->{});
                value.setOnClickListener(v->editRule(rule,rules,()->value.setText(rule.label+": "+
                    (rules.containsKey(rule.key)?rule.display(rules.get(rule.key)):"Default"))));
                rows.addView(value);
                rows.addView(text(rule.description,11,HaloTheme.MUTED,false));
            }
            body.addView(group);
        }
        Runnable saveNew=()->{try{saved.accept(base.saveAs(name.getText().toString(),rules));}catch(IllegalArgumentException invalid){message(invalid.getMessage());}};
        boolean current=false;
        for(MatchWorkshop.Preset p:library.presets)if(p.id.equals(base.id)&&p.revision==base.revision)current=true;
        if(current){
            pair(body,button("SAVE AS NEW",saveNew),button("SAVE CHANGES",()->{
                try{saved.accept(base.revise(name.getText().toString(),rules));}catch(IllegalArgumentException invalid){message(invalid.getMessage());}
            }));
        }else action(body,"SAVE AS NEW",saveNew);
        action(body,"CANCEL",back);
    }
    private void editRule(MatchWorkshop.Rule rule,Map<String,Integer> values,Runnable changed){
        if(rule.choices!=null){
            String[] labels=new String[rule.choices.length+1];labels[0]="Default (inherit)";int selected=0;
            for(int i=0;i<rule.choices.length;i++){labels[i+1]=rule.display(rule.choices[i]);if(Objects.equals(values.get(rule.key),rule.choices[i]))selected=i+1;}
            choose(rule.label,labels,selected,i->{if(i==0)values.remove(rule.key);else values.put(rule.key,rule.choices[i-1]);changed.run();});
            return;
        }
        EditText field=input(values.containsKey(rule.key)?Integer.toString(values.get(rule.key)):"",true);
        field.setHint("Default");
        HaloDialog d=new HaloDialog.Builder(activity).setTitle(rule.label).setMessage(rule.description+"\n\nAllowed range: "+rule.min+"–"+rule.max+". Blank inherits the preset.")
            .setView(field).setNegativeButton("CANCEL",null).setPositiveButton("APPLY",null).show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            try{String raw=field.getText().toString().trim();if(raw.isEmpty())values.remove(rule.key);
                else {int n=Integer.parseInt(raw);if(!rule.accepts(n))throw new IllegalArgumentException("Value outside allowed range");values.put(rule.key,n);}
                d.dismiss();changed.run();
            }catch(IllegalArgumentException invalid){message("Enter "+rule.min+"–"+rule.max+" or leave blank");}
        });
    }
    private void editBots(boolean teamMode,MatchWorkshop.Bots original,Consumer<MatchWorkshop.Bots> selected){
        MatchWorkshop.Bots start=original.forMode(teamMode);
        LinearLayout form=column();
        String[] policies=teamMode?new String[]{"Balanced","Bots vs players","Manual"}:new String[]{"Free-for-all"};
        Spinner mode=new Spinner(activity),skill=new Spinner(activity),side=new Spinner(activity);
        fill(mode,policies,teamMode?start.assignment.ordinal():0);fill(skill,MatchWorkshop.DIFFICULTIES,start.difficulty);
        fill(side,new String[]{"Red","Blue"},start.extraSide.ordinal());
        form.addView(text("Assignment",12,HaloTheme.TEXT,true));form.addView(mode);
        LinearLayout countRow=row(),manualRow=row(),advRow=row();
        EditText count=input(Integer.toString(start.count),true),red=input(Integer.toString(start.red),true),blue=input(Integer.toString(start.blue),true),extra=input(Integer.toString(start.extra),true);
        countRow.addView(text("Base bots",12,HaloTheme.TEXT,false));countRow.addView(count,new LinearLayout.LayoutParams(0,-2,1));
        manualRow.addView(text("Red",12,RED,true));manualRow.addView(red,new LinearLayout.LayoutParams(0,-2,1));manualRow.addView(text("Blue",12,BLUE,true));manualRow.addView(blue,new LinearLayout.LayoutParams(0,-2,1));
        advRow.addView(text("Extra bots",12,HaloTheme.TEXT,false));advRow.addView(extra,new LinearLayout.LayoutParams(0,-2,1));advRow.addView(side,new LinearLayout.LayoutParams(0,-2,1));
        form.addView(countRow);form.addView(manualRow);form.addView(advRow);
        form.addView(text("Difficulty",12,HaloTheme.TEXT,true));form.addView(skill);
        Runnable visibility=()->{int m=mode.getSelectedItemPosition();countRow.setVisibility(teamMode&&m==2?View.GONE:View.VISIBLE);manualRow.setVisibility(teamMode&&m==2?View.VISIBLE:View.GONE);advRow.setVisibility(teamMode&&m==0?View.VISIBLE:View.GONE);};
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int position,long id){visibility.run();}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });visibility.run();
        label(form,"Balanced +N adds N bots after balancing. LAN draft cap: "+MatchWorkshop.MAX_LAN_BOTS+" bots.");
        HaloDialog d=new HaloDialog.Builder(activity).setTitle("BOT ASSIGNMENT").setView(form)
            .setNegativeButton("CANCEL",null).setPositiveButton("APPLY",null).show();
        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                MatchWorkshop.Assignment a=teamMode?MatchWorkshop.Assignment.values()[mode.getSelectedItemPosition()]:MatchWorkshop.Assignment.FREE_FOR_ALL;
                int total=a==MatchWorkshop.Assignment.MANUAL?0:Integer.parseInt(count.getText().toString());
                int rr=a==MatchWorkshop.Assignment.MANUAL?Integer.parseInt(red.getText().toString()):0;
                int bb=a==MatchWorkshop.Assignment.MANUAL?Integer.parseInt(blue.getText().toString()):0;
                int x=a==MatchWorkshop.Assignment.BALANCED?Integer.parseInt(extra.getText().toString()):0;
                MatchWorkshop.Bots b=new MatchWorkshop.Bots(a,total,skill.getSelectedItemPosition(),rr,bb,x,MatchWorkshop.Side.values()[side.getSelectedItemPosition()]);
                d.dismiss();selected.accept(b);
            }catch(IllegalArgumentException invalid){message("Enter valid bot counts; total including advantage must be 0–"+MatchWorkshop.MAX_LAN_BOTS);}
        });
    }
    private void fill(Spinner s,String[] values,int selected){
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(adapter);s.setSelection(selected);
    }
    private View entryRow(MatchWorkshop.Entry e,View handle){
        LinearLayout r=row();if(handle!=null)r.addView(handle);
        r.addView(picture(e.map,48,36));
        LinearLayout desc=column();desc.addView(text(mapName(e.map),12,HaloTheme.FOCUS_TEXT,true));desc.addView(text(e.preset.name+(e.preset.id.isEmpty()?"":" · r"+e.preset.revision),11,HaloTheme.TEXT,false));desc.addView(text(e.bots.description(),10,HaloTheme.MUTED,false));
        r.addView(desc,new LinearLayout.LayoutParams(0,-2,1));return r;
    }
    private static final class PlaylistDraft {
        final String id;String name;boolean shuffle,loop;
        final ArrayList<MatchWorkshop.Entry> entries=new ArrayList<>();
        PlaylistDraft(MatchWorkshop.Playlist source){id=source==null?MatchWorkshop.newId():source.id;name=source==null?"New playlist":source.name;
            if(source!=null){entries.addAll(source.entries);shuffle=source.shuffle;loop=source.loop;}}
    }
    private void editPlaylist(MatchWorkshop.Playlist source){renderPlaylist(new PlaylistDraft(source));}
    private void renderPlaylist(PlaylistDraft draft){
        body.removeAllViews();label(body,"PLAYLIST MAKER");
        EditText name=input(draft.name,false);watch(name,v->draft.name=v);body.addView(name);
        pair(body,check("Shuffle",draft.shuffle,b->draft.shuffle=b),check("Loop",draft.loop,b->draft.loop=b));
        label(body,"Drag the dotted handle to reorder. Tap a match to edit.");
        PlaylistCards cards=new PlaylistCards(activity,(from,to)->draft.entries.add(to,draft.entries.remove(from)));
        for(MatchWorkshop.Entry entry:draft.entries){
            Button handle=button("⠿",()->{});
            View card=entryRow(entry,handle);
            card.setBackground(HaloTheme.surface(0xff20384b,0xff102332,HaloTheme.BORDER,3,activity.getResources().getDisplayMetrics().density));
            handle.setContentDescription("Reorder "+mapName(entry.map));
            handle.setOnClickListener(v->choose("REORDER MATCH",new String[]{"Move up","Move down"},-1,
                direction->cards.moveCard(card,cards.indexOfChild(card)+(direction==0?-1:1))));
            card.setOnClickListener(v->editEntry(draft,cards.indexOfChild(card),entry));
            cards.addCard(card,handle);
        }
        body.addView(cards);
        action(body,"+ ADD MATCH",()->{
            if(draft.entries.size()>=MatchWorkshop.MAX_ENTRIES){message("Playlist is full");return;}
            editEntry(draft,-1,library.quick);
        });
        action(body,"SAVE PLAYLIST",()->{
            try{MatchWorkshop.Playlist list=new MatchWorkshop.Playlist(draft.id,draft.name,draft.entries,draft.shuffle,draft.loop);
                if(save(library.put(list)))home();}
            catch(IllegalArgumentException invalid){message(invalid.getMessage());}
        });
        action(body,"CANCEL",this::home);
    }
    private void editEntry(PlaylistDraft draft,int index,MatchWorkshop.Entry initial){
        final MatchWorkshop.Entry[] entry={initial};
        renderEntry(draft,index,entry);
    }
    private void renderEntry(PlaylistDraft draft,int index,MatchWorkshop.Entry[] entry){
        body.removeAllViews();label(body,index<0?"ADD MATCH":"EDIT MATCH");
        entryFields(body,entry[0],changed->{entry[0]=changed;renderEntry(draft,index,entry);},
            ()->ruleEditor(entry[0].preset,saved->{if(save(library.put(saved))){entry[0]=entry[0].withPreset(saved);renderEntry(draft,index,entry);}},()->renderEntry(draft,index,entry)));
        action(body,"SAVE MATCH",()->{
            if(findMap(entry[0].map)==null){message("Selected map is not installed.");return;}
            if(index<0)draft.entries.add(entry[0]);else draft.entries.set(index,entry[0]);renderPlaylist(draft);
        });
        if(index>=0)action(body,"REMOVE MATCH",()->{draft.entries.remove(index);renderPlaylist(draft);});
        action(body,"CANCEL",()->renderPlaylist(draft));
    }
}
