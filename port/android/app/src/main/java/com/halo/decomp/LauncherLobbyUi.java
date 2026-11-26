package com.halo.decomp;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** One Lobby tab, with real host/join acknowledgement and a shared roster.
 * Start distributes a frozen match to the joined phones before handing off to Halo. */
final class LauncherLobbyUi implements AutoCloseable {
    private final Activity activity;
    private final File dataRoot;
    private final LinearLayout body;
    private final Runnable classic;
    interface MatchLauncher { void start(String[] args,boolean host); }
    private final MatchLauncher launcher;
    private final BooleanSupplier canOpen;
    private String preparedRound="", launchedRound="", localFailure="";
    private String returnRound="";
    private boolean returnCompleted;
    private String[] launchArgs;
    private boolean inGame;
    private final SharedPreferences prefs;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"Halo-lobby-client");t.setDaemon(true);return t;
    });
    private LanLobbyTransport.Host host;
    private LanLobbyTransport.Client guest;
    private LobbyModel.Snapshot snapshot;
    private MatchWorkshopUi hostEditor;
    private LinearLayout summary,roster,available;
    private TextView status;
    private int generation;
    private boolean busy,closed,visible,connectionLost;
    private long lastContact;
    private List<String> discovered=Collections.emptyList();
    private List<WorkshopMapCatalog.MapItem> sessionMaps=Collections.emptyList();
    private String notice="";
    private final Runnable tick=this::tick;

    LauncherLobbyUi(Activity a,File root,LinearLayout parent,Runnable classic,BooleanSupplier canOpen,MatchLauncher launcher){
        activity=a;dataRoot=root;body=parent;this.classic=classic;this.launcher=launcher;this.canOpen=canOpen;
        prefs=a.getSharedPreferences("lobby_profile_v1",Activity.MODE_PRIVATE);
        showSelection();
    }
    private int dp(int x){return Math.round(x*activity.getResources().getDisplayMetrics().density);}
    private LinearLayout col(){LinearLayout c=new LinearLayout(activity);c.setOrientation(LinearLayout.VERTICAL);return c;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(activity);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private LinearLayout panel(){LinearLayout p=col();p.setPadding(dp(5),dp(3),dp(5),dp(3));p.setBackground(HaloTheme.surface(0x20233344,0x20101d2c,HaloTheme.BORDER,3,activity.getResources().getDisplayMetrics().density));return p;}
    private TextView text(String s,int size,int color,boolean strong){TextView t=new TextView(activity);t.setText(s);t.setTextSize(size);t.setTextColor(color);HaloTheme.font(t,strong);t.setPadding(dp(2),dp(2),dp(2),dp(2));return t;}
    private void label(LinearLayout p,String s){p.addView(text(s,11,HaloTheme.MUTED,false));}
    private Button button(String label,Runnable action){Button b=new Button(activity);b.setText(label);b.setAllCaps(false);b.setTextSize(11);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(34));b.setPadding(dp(4),dp(2),dp(4),dp(2));HaloTheme.menuButtonColors(b);HaloTheme.font(b,true);b.setOnClickListener(v->action.run());return b;}
    private void pair(LinearLayout p,View a,View b){LinearLayout r=row();r.addView(a,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,1);params.leftMargin=dp(5);r.addView(b,params);p.addView(r);}
    private void msg(String value){notice=value;if(status!=null)status.setText(value);HaloNotice.makeText(activity,value,HaloNotice.LENGTH_LONG).show();}
    private String profileName(){
        String n=prefs.getString("name",LanBoardIdentity.name(activity));
        if(n==null||n.trim().isEmpty())return "Spartan";
        n=n.trim();if(n.length()>16)n=n.substring(0,16);
        try{return LobbyModel.name(n);}catch(IllegalArgumentException bad){return "Spartan";}
    }
    private int profileColor(){return Math.max(0,Math.min(LobbyModel.COLORS.length-1,prefs.getInt("color",0)));}
    private void persistAppearance(LobbyModel.Member m){prefs.edit().putString("name",m.name).putInt("color",m.color).apply();}
    private boolean allowed(){if(closed||busy)return false;if(!canOpen.getAsBoolean()){msg("Close the game or finish file operations before opening a lobby.");return false;}return true;}
    void setVisible(boolean value){
        visible=value;
        if(value&&!closed&&snapshot==null&&!busy&&discovered.isEmpty())search();
    }
    private void showSelection(){
        body.removeAllViews();hostEditor=null;summary=roster=null;
        body.addView(text("LOBBIES",14,HaloTheme.FOCUS_TEXT,true));
        status=text(notice.isEmpty()?"Choose an existing lobby or create one.":notice,11,HaloTheme.MUTED,false);body.addView(status);
        LinearLayout left=panel(),right=panel();
        left.addView(text("AVAILABLE LOBBIES",12,HaloTheme.FOCUS_TEXT,true));
        left.addView(button("FIND LOBBIES",this::search));
        available=col();left.addView(available);showAvailable();
        left.addView(button("JOIN BY IP",this::manualJoin));
        right.addView(button("CREATE LOBBY",this::create));
        right.addView(button("MATCH WORKSHOP",()->MatchWorkshopUi.show(activity,dataRoot)));
        right.addView(button("PLAYER NAME / COLOR",this::appearance));
        right.addView(button("MATCH RECORDING",this::recording));
        label(right,"Classic Halo still uses the original hosting/joining menus.");
        right.addView(button("CLASSIC HALO",classic));
        pair(body,left,right);
    }
    private void showAvailable(){
        if(available==null)return;available.removeAllViews();
        if(discovered.isEmpty()){label(available,"No new lobby discovered.");return;}
        for(String ip:discovered)available.addView(button("Join "+ip,()->join(ip)));
    }
    private void search(){
        if(closed||busy||snapshot!=null)return;
        busy=true;int g=generation;
        if(status!=null)status.setText("Searching this LAN…");
        work.execute(()->{
            List<String> found=LanDiscoveryClient.scan(activity.getApplicationContext(),LanLobbyTransport.PORT);
            ui.post(()->{
                if(closed||g!=generation)return;busy=false;discovered=found;
                showAvailable();if(status!=null)status.setText(found.isEmpty()?"No pregame lobby found. Manual IP is available.":"Select a lobby to join.");
            });
        });
    }
    private void manualJoin(){
        EditText ip=new EditText(activity);ip.setSingleLine(true);ip.setText(prefs.getString("last_host",""));
        ip.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        HaloDialog dialog=new HaloDialog.Builder(activity).setTitle("HOST IP").setView(ip)
            .setNegativeButton("CANCEL",null).setPositiveButton("JOIN",null).show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            String address=ip.getText().toString().trim();
            if(LanUpdateProtocol.privateAddress(address)==null){msg("Enter the host's private IPv4 address");return;}
            dialog.dismiss();join(address);
        });
    }
    private void create(){
        if(!allowed()||snapshot!=null)return;
        busy=true;final int g=++generation;final String name=profileName();final int color=profileColor();
        status.setText("Opening lobby on this LAN…");
        work.execute(()->{
            LanLobbyTransport.Host opened=null;
            try{
                MatchWorkshop.Library lib=MatchWorkshop.read(activity.getFilesDir());
                LobbyModel model=new LobbyModel(name,color,LobbyModel.Plan.from(lib));
                opened=new LanLobbyTransport.Host(model);opened.open();
                LobbyModel.Snapshot s=model.hostSnapshot(LanLobbyTransport.now());
                final LanLobbyTransport.Host result=opened;
                ui.post(()->{if(closed||g!=generation){result.close();return;}host=result;snapshot=s;busy=false;connectionLost=false;lastContact=LanLobbyTransport.now();showSession();schedule();});
            }catch(IOException|RuntimeException error){
                if(opened!=null)opened.close();failedOpen(g,"Couldn't create lobby: "+error.getMessage());
            }
        });
    }
    private void join(String ip){
        if(!allowed()||snapshot!=null)return;
        busy=true;final int g=++generation;final String name=profileName();final int color=profileColor();
        status.setText("Waiting for host acknowledgement…");
        work.execute(()->{
            LanLobbyTransport.Client opened=null;
            try{
                opened=new LanLobbyTransport.Client(ip);
                LobbyModel.Snapshot s=opened.join(name,color);
                final LanLobbyTransport.Client result=opened;
                ui.post(()->{
                    if(closed||g!=generation){result.close();return;}
                    guest=result;snapshot=s;busy=false;connectionLost=false;lastContact=LanLobbyTransport.now();
                    prefs.edit().putString("last_host",ip).apply();showSession();schedule();
                });
            }catch(IOException|RuntimeException error){if(opened!=null)opened.close();failedOpen(g,"Couldn't join lobby: "+error.getMessage());}
        });
    }
    private void failedOpen(int g,String error){
        ui.post(()->{if(closed||g!=generation)return;busy=false;msg(error);});
    }
    private void showSession(){
        body.removeAllViews();available=null;
        boolean hosting=host!=null;
        sessionMaps=WorkshopMapCatalog.installed(dataRoot);
        body.addView(text("LOBBY  /  "+(hosting?"HOST":"PLAYER"),14,HaloTheme.FOCUS_TEXT,true));
        status=text("",11,HaloTheme.MUTED,false);body.addView(status);
        summary=panel();roster=panel();
        if(snapshot.secondsToNext>=0){
            LobbyModel.StartReview preview=LobbyModel.StartReview.upcoming(snapshot);
            MatchWorkshop.Entry next=preview.entry;
            body.addView(text(WorkshopMapCatalog.label(sessionMaps,next.map)+" · "+next.preset.name,14,HaloTheme.TEXT,true));
            label(body,snapshot.plan.title+" · Entry "+(snapshot.nextIndex+1)+" of "+snapshot.plan.entries.size());
            label(body,preview.members.size()+" players · "+preview.allocation.bots()+" bots");
            if(hosting)body.addView(button("PAUSE PLAYLIST",()->pauseStart("Playlist paused.")));
            else body.addView(button("LEAVE LOBBY",()->leave(true,"Left lobby.")));
            refreshStatus();return;
        }
        if(!snapshot.round.isEmpty()){
            LobbyModel.StartReview selected=LobbyModel.StartReview.selected(snapshot);
            body.addView(text(WorkshopMapCatalog.label(sessionMaps,selected.entry.map)+" · "+selected.entry.preset.name,14,HaloTheme.TEXT,true));
            label(body,selected.members.size()+" players · "+selected.allocation.bots()+" bots");
            if(hosting&&!snapshot.released)body.addView(button("CANCEL START",()->pauseStart("Start canceled.")));
            if(hosting&&snapshot.hostBack)body.addView(button("BACK TO SETUP",()->pauseStart("Back in setup.")));
            if(!hosting&&!inGame)body.addView(button("LEAVE LOBBY",()->leave(true,"Left lobby.")));
            refreshStatus();
            return;
        }
        if(hosting){
            try{
                hostEditor=MatchWorkshopUi.attach(activity,dataRoot,lib->{
                    if(host==null)return;
                    host.model.setPlan(LobbyModel.Plan.from(lib));snapshot=host.model.hostSnapshot(LanLobbyTransport.now());refreshShared();
                });
                summary.addView(hostEditor.view());
            }catch(IOException|RuntimeException error){label(summary,"Workshop unavailable: "+error.getMessage());}
        }
        pair(body,summary,roster);
        if(hosting){
            Button start=button("START",this::reviewStart);
            pair(body,button("CANCEL LOBBY",()->leave(true,"Lobby closed.")),start);
        }else body.addView(button("LEAVE LOBBY",()->leave(true,"Left lobby.")));
        refreshShared();
    }
    private void refreshStatus(){
        if(status==null||snapshot==null)return;
        if(connectionLost){status.setText("Connection interrupted · waiting for host");return;}
        if(snapshot.secondsToNext>=0){
            status.setText("Next match in "+snapshot.secondsToNext+" s");return;
        }
        if(!snapshot.round.isEmpty()){
            if(snapshot.hostBack||!snapshot.released)status.setText(snapshot.startMessage);
            else if(!inGame&&snapshot.round.equals(launchedRound))status.setText("Waiting for the host to return.");
            else status.setText(inGame?"Match in progress":"Starting Halo…");
            return;
        }
        int guests=snapshot.members.size()-1;
        int ready=guests-snapshot.notReady().size();
        status.setText((host!=null?"Your IP: "+host.address():"Host: "+guest.address)+" · "+
            snapshot.members.size()+" connected · "+ready+"/"+guests+" guests ready · "+snapshot.plan.title+
            (snapshot.startMessage.isEmpty()?"":" · "+snapshot.startMessage)+
            (snapshot.nextIndex<0?"":" · Up next: "+WorkshopMapCatalog.label(sessionMaps,snapshot.plan.entries.get(snapshot.nextIndex).map)));
    }
    private void refreshShared(){
        if(snapshot==null)return;
        refreshStatus();
        if(!snapshot.round.isEmpty()||snapshot.secondsToNext>=0)return;
        if(guest!=null){
            summary.removeAllViews();
            LobbyModel.Plan p=snapshot.plan;
            summary.addView(text(p.title,13,HaloTheme.FOCUS_TEXT,true));
            label(summary,p.playlist?"Playlist · Shuffle "+(p.shuffle?"On":"Off")+" · Loop "+(p.loop?"On":"Off"):"Quick match");
            if(p.first()!=null){
                MatchWorkshop.Entry e=snapshot.nextIndex<0?p.first():p.entries.get(snapshot.nextIndex);
                List<WorkshopMapCatalog.MapItem> maps=sessionMaps;
                summary.addView(text(WorkshopMapCatalog.label(maps,e.map),13,HaloTheme.TEXT,true));
                label(summary,e.preset.name+" · "+e.bots.description());
                label(summary,"Killstreak rewards "+(e.killstreak?"On":"Off"));
                if(p.shuffle&&snapshot.nextIndex<0)label(summary,"The host draws the first match at Start.");
                if(!e.preset.overrides.isEmpty())summary.addView(button("VIEW CUSTOM RULES",()->viewRules(e.preset)));
                if(p.playlist)for(int i=0;i<p.entries.size();i++){
                    MatchWorkshop.Entry next=p.entries.get(i);
                    label(summary,(i+1)+". "+WorkshopMapCatalog.label(maps,next.map)+" · "+next.preset.name+" · "+next.bots.description());
                }
            }else label(summary,"Host has not selected a match.");
        }
        roster.removeAllViews();
        roster.addView(text("PLAYERS",12,HaloTheme.FOCUS_TEXT,true));
        LobbyModel.Member me=snapshot.me();
        MatchWorkshop.Allocation bots=snapshot.allocation();
        if(snapshot.plan.teams()){
            teamRows(roster,LobbyModel.RED,"RED TEAM",0xffe78c8c,bots.red);
            teamRows(roster,LobbyModel.BLUE,"BLUE TEAM",0xff8dbfff,bots.blue);
        }else teamRows(roster,LobbyModel.NO_TEAM,"FREE-FOR-ALL",HaloTheme.FOCUS_TEXT,bots.ffa);
        if(!bots.warning.isEmpty())label(roster,bots.warning);
        label(roster,"Bots are planned until native game starts.");
        Button profile=button("NAME / COLOR",this::appearance);
        Button ready=button(me.ready?"NOT READY":"READY",()->changeSelf(me.name,me.color,!me.ready,me.requestedTeam));
        profile.setEnabled(!connectionLost);ready.setEnabled(!connectionLost);
        pair(roster,profile,ready);
        if(guest!=null&&snapshot.plan.teams()){
            Button request=button(me.requestedTeam==LobbyModel.NO_TEAM?"REQUEST TEAM CHANGE":"CANCEL TEAM REQUEST",()->{
                LobbyModel.Member self=snapshot.me();
                int requestTeam=self.requestedTeam==LobbyModel.NO_TEAM?1-self.team:LobbyModel.NO_TEAM;
                changeSelf(self.name,self.color,false,requestTeam);
            });
            request.setEnabled(!connectionLost);roster.addView(request);
        }
        roster.addView(button("MATCH RECORDING",this::recording));
    }
    private void teamRows(LinearLayout parent,int side,String heading,int color,int botCount){
        LinearLayout group=panel();int people=0;for(LobbyModel.Member m:snapshot.members)if(m.team==side)people++;
        group.addView(text(heading+" · "+(people+botCount),12,color,true));
        for(LobbyModel.Member m:snapshot.members)if(m.team==side){
            LinearLayout r=row();View swatch=new View(activity);swatch.setBackgroundColor(LobbyModel.RGB[m.color]);r.addView(swatch,new LinearLayout.LayoutParams(dp(10),dp(10)));
            String title=m.name+(m.host?" · HOST":"")+(m.id.equals(snapshot.self)?" (You)":"")+(m.ready?" · Ready":" · Not ready");
            if(m.requestedTeam!=LobbyModel.NO_TEAM)title+=" · Team requested";
            r.addView(text(title,11,HaloTheme.TEXT,false),new LinearLayout.LayoutParams(0,-2,1));
            if(host!=null&&side!=LobbyModel.NO_TEAM){
                Button swap=button("↔",()->assign(m.id,1-m.team));swap.setContentDescription("Move "+m.name+" to other team");r.addView(swap);
            }
            group.addView(r);
        }
        if(botCount>0){MatchWorkshop.Entry e=snapshot.plan.first();label(group,botCount+" bots · "+MatchWorkshop.DIFFICULTIES[e==null?2:e.bots.difficulty]);}
        parent.addView(group);
    }
    private void reviewStart(){
        if(host==null||snapshot==null||closed||!snapshot.round.isEmpty())return;
        LobbyModel.StartReview review=host.model.reviewStart(LanLobbyTransport.now(),new java.security.SecureRandom());
        if(!review.blocker.isEmpty()){msg(review.blocker);return;}
        if(!review.needsOverride()){confirmStart(review,false);return;}
        showStartWarning(review);
    }
    private void showStartWarning(LobbyModel.StartReview review){
        StringBuilder warning=new StringBuilder("The following players are not ready:\n");
        for(LobbyModel.Member m:review.notReady)warning.append("• ").append(m.name).append('\n');
        warning.append("\nStart anyway? Ready status does not exclude anyone.");
        new HaloDialog.Builder(activity).setTitle("START MATCH?")
            .setMessage(warning.toString()).setNegativeButton("CANCEL",null)
            .setPositiveButton("START ANYWAY",(d,w)->confirmStart(review,true)).show();
    }
    private void confirmStart(LobbyModel.StartReview review,boolean override){
        if(host==null||closed)return;
        try{
            if(!review.blocker.isEmpty())throw new IOException(review.blocker);
            if(!canOpen.getAsBoolean())throw new IOException("Finish the current game or file operation first.");
            host.nativeInterface();
            NativeLobbyMatch.checkMap(dataRoot,review.entry.map);
            NativeMatchRules.encode(review.entry.preset);
            applySnapshot(host.model.beginStart(review,override,LanLobbyTransport.now()));
        }catch(LobbyModel.StartNotReady warning){showStartWarning(warning.review);}
        catch(IOException|IllegalArgumentException|IllegalStateException failed){
            if(snapshot.secondsToNext>=0)pauseStart(failed.getMessage());
            else msg(failed.getMessage());
        }
    }
    private void pauseStart(String reason){
        long now=LanLobbyTransport.now();
        host.model.resetStart(reason,now);applySnapshot(host.model.hostSnapshot(now));
    }

    private void assign(String id,int side){
        if(host==null)return;
        try{host.model.setTeam(id,side);snapshot=host.model.hostSnapshot(LanLobbyTransport.now());refreshShared();}
        catch(IllegalArgumentException invalid){msg(invalid.getMessage());}
    }
    private void viewRules(MatchWorkshop.Preset preset){
        StringBuilder b=new StringBuilder();
        for(Map.Entry<String,Integer> e:preset.overrides.entrySet()){
            MatchWorkshop.Rule r=MatchWorkshop.rule(e.getKey());
            b.append(r.label).append(": ").append(r.display(e.getValue())).append('\n');
        }
        new HaloDialog.Builder(activity).setTitle(preset.name).setMessage(b.toString()).setNegativeButton("CLOSE",null).show();
    }
    private void appearance(){
        if(snapshot!=null&&connectionLost)return;
        LobbyModel.Member m=snapshot==null?null:snapshot.me();
        LinearLayout form=col();label(form,"Halo displays up to 11 characters in a match.");
        EditText name=new EditText(activity);name.setSingleLine(true);name.setText(m==null?profileName():m.name);form.addView(name);
        Spinner color=new Spinner(activity);ArrayAdapter<String> a=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,LobbyModel.COLORS);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);color.setAdapter(a);color.setSelection(m==null?profileColor():m.color);form.addView(color);
        HaloDialog dialog=new HaloDialog.Builder(activity).setTitle("PLAYER APPEARANCE").setView(form).setNegativeButton("CANCEL",null).setPositiveButton("APPLY",null).show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                String n=LobbyModel.name(name.getText().toString());int c=LobbyModel.color(color.getSelectedItemPosition());
                if(snapshot==null)prefs.edit().putString("name",n).putInt("color",c).apply();
                else {LobbyModel.Member self=snapshot.me();changeSelf(n,c,self.ready,self.requestedTeam);}
                dialog.dismiss();
            }catch(IllegalArgumentException invalid){msg(invalid.getMessage());}
        });
    }
    private void recording(){
        SharedPreferences recording=activity.getSharedPreferences("reel_settings",Activity.MODE_PRIVATE);
        LinearLayout form=col();CheckBox record=new CheckBox(activity),keep=new CheckBox(activity);
        record.setText("RECORD MATCHES");keep.setText("KEEP FULL RECORDING");
        for(CheckBox c:new CheckBox[]{record,keep}){c.setButtonTintList(HaloTheme.CHECK_TINT);c.setTextColor(HaloTheme.TEXT);form.addView(c);}
        record.setChecked(recording.getBoolean("record_opt_in",false));keep.setChecked(recording.getBoolean("keep_full_match",false));keep.setEnabled(record.isChecked());
        record.setOnCheckedChangeListener((v,checked)->keep.setEnabled(checked));
        new HaloDialog.Builder(activity).setTitle("MATCH RECORDING").setView(form)
            .setNegativeButton("CANCEL",null).setPositiveButton("SAVE",(d,w)->recording.edit().putBoolean("record_opt_in",record.isChecked()).putBoolean("keep_full_match",keep.isChecked()).apply()).show();
    }
    private void changeSelf(String name,int color,boolean ready,int request){
        if(snapshot==null||closed||connectionLost||!snapshot.round.isEmpty())return;
        if(host!=null){
            try{host.model.hostAppearance(name,color,ready);snapshot=host.model.hostSnapshot(LanLobbyTransport.now());persistAppearance(snapshot.me());refreshShared();}
            catch(IllegalArgumentException invalid){msg(invalid.getMessage());}
            return;
        }
        if(busy){msg("Lobby is synchronizing. Try again.");return;}
        busy=true;int g=generation;LanLobbyTransport.Client client=guest;long revision=snapshot.revision;
        work.execute(()->{
            try{LobbyModel.Snapshot result=client.update(name,color,ready,request,revision);
                ui.post(()->{if(closed||g!=generation)return;busy=false;lastContact=LanLobbyTransport.now();connectionLost=false;
                    applySnapshot(result);persistAppearance(result.me());
                    if(!result.me().name.equals(name)||result.me().color!=color||result.me().ready!=ready||result.me().requestedTeam!=request)
                        msg("Lobby changed while editing. Review the updated roster.");
                });
            }catch(IOException|RuntimeException error){ui.post(()->{if(closed||g!=generation)return;busy=false;connectionLost=true;refreshShared();});}
        });
    }
    /** A committed snapshot can arrive again after a dropped response. Launch once per ticket. */
    private void applySnapshot(LobbyModel.Snapshot next){
        boolean phase=snapshot==null||!snapshot.round.equals(next.round)||snapshot.released!=next.released||
            snapshot.hostBack!=next.hostBack||(snapshot.secondsToNext>=0)!=(next.secondsToNext>=0);
        snapshot=next;
        if(phase)showSession();else refreshShared();
        if(next.round.isEmpty()){
            preparedRound="";launchArgs=null;
            if(!localFailure.isEmpty()){msg(localFailure);localFailure="";}
            return;
        }
        if(inGame||next.hostBack||next.round.equals(launchedRound))return;
        if(!next.round.equals(preparedRound)){
            preparedRound=next.round;launchArgs=null;localFailure="";
            try{
                if(!canOpen.getAsBoolean())throw new IOException("Finish the current game or file operation first.");
                if(host==null)NativeLobbyMatch.checkMap(dataRoot,next.plan.entries.get(next.startIndex).map);
                launchArgs=NativeLobbyMatch.arguments(next,guest==null?host.nativeInterface():guest.localAddress,guest==null?"":guest.address,
                    host==null?Collections.emptyList():host.model.nativePeers());
            }catch(IOException|IllegalArgumentException|IllegalStateException failure){localFailure=failure.getMessage();}
        }
        if(next.released&&launchArgs!=null){
            launchedRound=next.round;inGame=true;ui.removeCallbacks(tick);
            try{launcher.start(launchArgs,host!=null);}
            catch(RuntimeException failure){
                returned();msg("Could not open Halo: "+failure.getMessage());
            }
        }else if(host!=null&&launchArgs==null){
            host.model.resetStart(localFailure,LanLobbyTransport.now());
            applySnapshot(host.model.hostSnapshot(LanLobbyTransport.now()));
        }
    }
    void returned(){
        if(!inGame||snapshot==null)return;
        inGame=false;busy=false;lastContact=LanLobbyTransport.now();
        NativeLobbyMatch.Result result=NativeLobbyMatch.result(activity.getFilesDir(),launchedRound);
        if(host!=null){
            applySnapshot(host.model.hostReturned(launchedRound,result.completed,result.message,lastContact));
        }else {
            returnRound=launchedRound;returnCompleted=result.completed;refreshStatus();
            if(!result.completed)msg(result.message);
        }
        schedule();
    }
    private void schedule(){
        ui.removeCallbacks(tick);
        if(!closed&&!inGame&&snapshot!=null)ui.postDelayed(tick,
            snapshot.round.isEmpty()&&snapshot.secondsToNext<0?1500:500);
    }
    private void tick(){
        if(closed||snapshot==null||inGame)return;
        if(host!=null){
            long now=LanLobbyTransport.now();
            LobbyModel.Snapshot next=host.model.hostSnapshot(now);
            if(next.revision!=snapshot.revision)applySnapshot(next);
            LobbyModel.StartReview due=host.model.dueStart(now);
            if(due!=null)confirmStart(due,true);
        }else if(guest!=null&&!busy){
            busy=true;int g=generation;LanLobbyTransport.Client client=guest;long rev=snapshot.revision;
            String ack=(!snapshot.released&&snapshot.round.equals(preparedRound))?preparedRound:"";
            boolean accepted=launchArgs!=null;
            String returning=returnRound;boolean completed=returnCompleted;
            work.execute(()->{
                try{
                    LobbyModel.Snapshot next;
                    if(!returning.isEmpty())next=client.returned(returning,completed);
                    else if(!ack.isEmpty())next=client.acknowledge(ack,accepted);
                    else next=client.poll(rev);
                    ui.post(()->{
                        if(closed||g!=generation)return;
                        if(returning.equals(returnRound))returnRound="";
                        boolean recovered=connectionLost;busy=false;connectionLost=false;lastContact=LanLobbyTransport.now();
                        if(next!=null)applySnapshot(next);else if(recovered)refreshShared();
                    });
                }catch(IOException|RuntimeException error){
                    ui.post(()->{
                        if(closed||g!=generation)return;busy=false;
                        if(LanLobbyTransport.now()-lastContact>LobbyModel.GUEST_EXPIRY_MS){leave(false,"Host disconnected.");return;}
                        if(!connectionLost){connectionLost=true;refreshShared();}
                    });
                }
            });
        }
        schedule();
    }
    /** Selection and current session share a tab; moving to another tab
     * doesn't leave. Leaving foreground closes this first-phase lobby. */
    void suspend(){if(!inGame&&(snapshot!=null||busy))leave(false,"Lobby closed when launcher left foreground.");}
    private void leave(boolean notify,String message){
        generation++;ui.removeCallbacks(tick);busy=false;connectionLost=false;inGame=false;launchArgs=null;preparedRound=launchedRound=localFailure=returnRound="";
        if(host!=null){host.close();host=null;}
        LanLobbyTransport.Client old=guest;guest=null;
        if(old!=null){
            if(notify&&!closed)work.execute(()->{try{old.leave();}catch(IOException ignored){}finally{old.close();}});
            else old.close();
        }
        snapshot=null;notice=message;hostEditor=null;if(!closed)showSelection();
    }
    boolean active(){return snapshot!=null||busy;}
    boolean hasSession(){return snapshot!=null;}
    @Override public void close(){if(closed)return;closed=true;leave(false,"");work.shutdownNow();}
}
