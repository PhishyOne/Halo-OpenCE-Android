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
    private boolean inGame, checkingHost;
    private String checkingRound="";
    private final SharedPreferences prefs;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService work=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"Halo-lobby-client");t.setDaemon(true);return t;
    });
    private LanLobbyTransport.Host host;
    private LanLobbyTransport.Client guest;
    private LobbyModel.Snapshot snapshot;
    private MatchWorkshopUi hostEditor;
    private LinearLayout summary,roster;
    private TextView status;
    private android.widget.ProgressBar updateBar;
    private LanUpdateServer updateServer;
    private int generation;
    private boolean busy,closed,visible,connectionLost;
    private long lastContact;
    private Map<String,String> discovered=Collections.emptyMap();
    private int searchGeneration=-1;
    private boolean openFinder;
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
    private ScrollView scrollPane(LinearLayout contents){
        ScrollView scroll=new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(contents);
        return scroll;
    }
    private void msg(String value){notice=value;if(status!=null)status.setText(value);HaloNotice.makeText(activity,value,HaloNotice.LENGTH_LONG).show();}
    private String profileName(){
        String n=prefs.getString("name",LanBoardIdentity.name(activity));
        if(n==null||n.trim().isEmpty())return "Spartan";
        n=n.trim();if(n.length()>16)n=n.substring(0,16);
        try{return LobbyModel.name(n);}catch(IllegalArgumentException bad){return "Spartan";}
    }
    private int profileColor(){
        if(prefs.contains("armor_color"))return Math.max(0,Math.min(LobbyModel.COLORS.length-1,prefs.getInt("armor_color",0)));
        return LobbyModel.legacyColor(prefs.getInt("color",0));
    }
    private void saveAppearance(String name,int color){prefs.edit().putString("name",name).putInt("armor_color",color).apply();}
    private void persistAppearance(LobbyModel.Member m){saveAppearance(m.name,m.color);}
    private boolean allowed(){if(closed||busy)return false;if(!canOpen.getAsBoolean()){msg("Close the game or finish file operations before opening a lobby.");return false;}return true;}
    void setVisible(boolean value){
        visible=value;
        if(!value)openFinder=false;
        else if(!closed&&snapshot==null&&!busy&&discovered.isEmpty())search(false);
    }
    private void showSelection(){
        body.removeAllViews();hostEditor=null;summary=roster=null;
        body.addView(text("LOBBIES",14,HaloTheme.FOCUS_TEXT,true));
        status=text(notice.isEmpty()?"Choose an existing lobby or create one.":notice,11,HaloTheme.MUTED,false);body.addView(status);
        updateBar=new android.widget.ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
        updateBar.setVisibility(View.GONE);body.addView(updateBar,new LinearLayout.LayoutParams(-1,dp(5)));
        LinearLayout left=panel(),right=panel();
        left.addView(button("PLAYER NAME / COLOR",this::appearance));
        left.addView(button("MATCH RECORDING",this::recording));
        left.addView(new Space(activity),new LinearLayout.LayoutParams(1,0,1));
        left.addView(button("CLASSIC HALO",classic));
        right.addView(button("FIND LOBBY",()->search(true)));
        right.addView(button("JOIN BY IP",this::manualJoin));
        right.addView(button("CREATE LOBBY",this::nameLobby));
        right.addView(button("MATCH WORKSHOP",()->MatchWorkshopUi.show(activity,dataRoot)));
        LinearLayout columns=row();
        columns.addView(scrollPane(left),new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams rightSize=new LinearLayout.LayoutParams(0,-1,1);
        rightSize.leftMargin=dp(5);columns.addView(scrollPane(right),rightSize);
        body.addView(columns,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void showAvailable(){
        HaloDialog.Builder picker=new HaloDialog.Builder(activity).setTitle("FIND LOBBY")
            .setNegativeButton("CLOSE",null).setNeutralButton("REFRESH",(d,w)->search(true));
        if(discovered.isEmpty())picker.setMessage("No lobbies replied on this LAN.").setPositiveButton("JOIN BY IP",(d,w)->manualJoin());
        else{
            List<String> ips=new ArrayList<>(discovered.keySet());
            String[] items=new String[ips.size()];
            for(int i=0;i<items.length;i++){
                String ip=ips.get(i),name=discovered.get(ip);
                items[i]=(name.isEmpty()?"Older Halo lobby":name)+"  ·  "+ip;
            }
            picker.setItems(items,(d,i)->join(ips.get(i)));
        }
        picker.show();
    }
    private void search(boolean show){
        if(closed||busy||snapshot!=null)return;
        openFinder|=show;
        if(searchGeneration==generation)return;
        final int g=generation;searchGeneration=g;
        status.setText("Searching this LAN…");
        work.execute(()->{
            Map<String,String> found=LanDiscoveryClient.findLobbies(activity.getApplicationContext());
            ui.post(()->{
                if(closed||g!=generation)return;
                searchGeneration=-1;discovered=found;
                status.setText(found.isEmpty()?"No lobby found on this LAN.":found.size()+(found.size()==1?" lobby found":" lobbies found"));
                if(openFinder&&visible){openFinder=false;showAvailable();}
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
    private void nameLobby(){
        if(!allowed()||snapshot!=null)return;
        EditText field=new EditText(activity);field.setSingleLine(true);field.setSelectAllOnFocus(true);
        field.setText(prefs.getString("last_lobby_name",profileName()+"'s lobby"));
        HaloDialog dialog=new HaloDialog.Builder(activity).setTitle("LOBBY NAME").setView(field)
            .setNegativeButton("CANCEL",null).setPositiveButton("CREATE",null).show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            try{String name=LobbyName.checked(field.getText().toString());dialog.dismiss();create(name);}
            catch(IllegalArgumentException invalid){msg(invalid.getMessage());field.requestFocus();}
        });
    }
    private void create(String lobbyName){
        if(!allowed()||snapshot!=null)return;
        busy=true;final int g=++generation;final String name=profileName();final int color=profileColor();
        status.setText("Opening lobby on this LAN…");
        work.execute(()->{
            LanLobbyTransport.Host opened=null;
            try{
                MatchWorkshop.Library lib=MatchWorkshop.read(activity.getFilesDir());
                LobbyModel model=new LobbyModel(lobbyName,name,color,LobbyModel.Plan.from(lib));
                model.lateSeats=(round,peer,local)->NativeLobbyMatch.publishLateSeat(activity.getFilesDir(),round,peer,local);
                opened=new LanLobbyTransport.Host(model);opened.open();
                LobbyModel.Snapshot s=model.hostSnapshot(LanLobbyTransport.now());
                final LanLobbyTransport.Host result=opened;
                List<WorkshopMapCatalog.MapItem> maps=WorkshopMapCatalog.installed(dataRoot);
                ui.post(()->{if(closed||g!=generation){result.close();return;}host=result;snapshot=s;updateServer=LanUpdateServer.start(activity);sessionMaps=maps;prefs.edit().putString("last_lobby_name",s.lobbyName).apply();busy=false;connectionLost=false;lastContact=LanLobbyTransport.now();showSession();schedule();});
            }catch(IOException|RuntimeException error){
                if(opened!=null)opened.close();failedOpen(g,"Couldn't create lobby: "+error.getMessage());
            }
        });
    }
    private void join(String ip){
        if(!allowed()||snapshot!=null)return;
        busy=true;final int g=++generation;final String name=profileName();final int color=profileColor();
        status.setText("Checking the host’s Halo version…");
        updateBar.setIndeterminate(true);updateBar.setVisibility(View.VISIBLE);
        LanUpdateClient.check(activity,ip,false,()->{
            if(closed||g!=generation)return;
            busy=true;joinChecked(ip,g,name,color);
        },()->{
            if(closed||g!=generation)return;
            busy=false;updateBar.setVisibility(View.GONE);status.setText("Choose a lobby to join.");
        },(label,percent)->{
            if(closed||g!=generation)return;
            status.setText(label);updateBar.setIndeterminate(false);updateBar.setProgress(percent);
        });
    }
    private void joinChecked(String ip,int g,String name,int color){
        status.setText("Waiting for host acknowledgement…");
        work.execute(()->{
            LanLobbyTransport.Client opened=null;
            try{
                opened=new LanLobbyTransport.Client(ip);
                LobbyModel.Snapshot s=opened.join(name,color);
                final LanLobbyTransport.Client result=opened;
                List<WorkshopMapCatalog.MapItem> maps=WorkshopMapCatalog.installed(dataRoot);
                ui.post(()->{
                    if(closed||g!=generation){result.close();return;}
                    guest=result;snapshot=s;sessionMaps=maps;busy=false;connectionLost=false;lastContact=LanLobbyTransport.now();
                    prefs.edit().putString("last_host",ip).apply();showSession();applySnapshot(s);schedule();
                });
            }catch(IOException|RuntimeException error){if(opened!=null)opened.close();failedOpen(g,"Couldn't join lobby: "+error.getMessage());}
        });
    }
    private void failedOpen(int g,String error){
        ui.post(()->{if(closed||g!=generation)return;busy=false;msg(error);});
    }
    private void showSession(){
        body.removeAllViews();openFinder=false;hostEditor=null;
        boolean hosting=host!=null;
        body.addView(text(snapshot.lobbyName+"  /  "+(hosting?"HOST":"PLAYER"),14,HaloTheme.FOCUS_TEXT,true));
        status=text("",11,HaloTheme.MUTED,false);body.addView(status);
        summary=panel();roster=panel();
        LinearLayout panes=row();
        panes.addView(scrollPane(summary),new LinearLayout.LayoutParams(0,-1,1.15f));
        LinearLayout.LayoutParams rosterParams=new LinearLayout.LayoutParams(0,-1,1);
        rosterParams.leftMargin=dp(5);
        panes.addView(scrollPane(roster),rosterParams);
        body.addView(panes,new LinearLayout.LayoutParams(-1,0,1));
        if(snapshot.secondsToNext>=0||!snapshot.round.isEmpty()){
            MatchWorkshop.Entry entry=snapshot.entry();
            summary.addView(text(WorkshopMapCatalog.label(sessionMaps,entry.map)+" · "+entry.preset.name,14,HaloTheme.TEXT,true));
            if(snapshot.plan.playlist)label(summary,snapshot.plan.title+" · Entry "+(snapshot.entryIndex()+1)+" of "+snapshot.plan.entries.size());
            label(summary,snapshot.members.size()+" players · "+snapshot.allocation().bots()+" bots");
            if(!entry.preset.overrides.isEmpty())summary.addView(button("VIEW CUSTOM RULES",()->viewRules(entry.preset)));
            if(!hosting&&!inGame)body.addView(button("LEAVE LOBBY",()->leave(true,"Left lobby.")));
            else if(hosting&&snapshot.secondsToNext>=0)body.addView(button("PAUSE PLAYLIST",()->pauseStart("Playlist paused.")));
            else if(hosting&&!snapshot.released)body.addView(button("CANCEL START",()->pauseStart("Start canceled.")));
            else if(hosting&&snapshot.hostBack)body.addView(button("BACK TO SETUP",()->pauseStart("Back in setup.")));
            refreshStatus();refreshRoster(false);
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
            if(snapshot.entry()!=null){
                MatchWorkshop.Entry e=snapshot.entry();
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
        refreshRoster(true);
    }
    private void refreshRoster(boolean editable){
        roster.removeAllViews();
        roster.addView(text("PLAYERS",12,HaloTheme.FOCUS_TEXT,true));
        LobbyModel.Member me=snapshot.me();
        MatchWorkshop.Allocation bots=snapshot.allocation();
        boolean assignPlayers=editable&&host!=null&&snapshot.teams()&&!snapshot.versus();
        if(snapshot.teams()){
            teamRows(roster,LobbyModel.RED,"RED TEAM",0xffe78c8c,bots.red,assignPlayers);
            teamRows(roster,LobbyModel.BLUE,"BLUE TEAM",0xff8dbfff,bots.blue,assignPlayers);
        }else teamRows(roster,LobbyModel.NO_TEAM,"FREE-FOR-ALL",HaloTheme.FOCUS_TEXT,bots.ffa,false);
        if(!bots.warning.isEmpty())label(roster,bots.warning);
        if(!editable)return;
        if(host!=null&&snapshot.versus())
            roster.addView(button("SWAP HUMAN TEAM",()->assign(me.id,1-me.team)));
        Button profile=button("NAME / COLOR",this::appearance);
        Button ready=button(me.ready?"NOT READY":"READY",()->changeSelf(me.name,me.color,!me.ready,me.requestedTeam));
        profile.setEnabled(!connectionLost);ready.setEnabled(!connectionLost);
        pair(roster,profile,ready);
        if(guest!=null&&snapshot.teams()&&!snapshot.versus()){
            Button request=button(me.requestedTeam==LobbyModel.NO_TEAM?"REQUEST TEAM CHANGE":"CANCEL TEAM REQUEST",()->{
                LobbyModel.Member self=snapshot.me();
                int requestTeam=self.requestedTeam==LobbyModel.NO_TEAM?1-self.team:LobbyModel.NO_TEAM;
                changeSelf(self.name,self.color,false,requestTeam);
            });
            request.setEnabled(!connectionLost);roster.addView(request);
        }
        roster.addView(button("MATCH RECORDING",this::recording));
    }
    private void teamRows(LinearLayout parent,int side,String heading,int color,int botCount,boolean assignPlayers){
        LinearLayout group=panel();int people=0;for(LobbyModel.Member m:snapshot.members)if(m.team==side)people++;
        group.addView(text(heading+" · "+(people+botCount),12,color,true));
        for(LobbyModel.Member m:snapshot.members)if(m.team==side){
            LinearLayout r=row();View swatch=new View(activity);swatch.setBackgroundColor(LobbyModel.RGB[m.color]);r.addView(swatch,new LinearLayout.LayoutParams(dp(10),dp(10)));
            String title=m.name+(m.host?" · HOST":"")+(m.id.equals(snapshot.self)?" (You)":"")+(m.ready?" · Ready":" · Not ready");
            if(m.requestedTeam!=LobbyModel.NO_TEAM)title+=" · Team requested";
            r.addView(text(title,11,HaloTheme.TEXT,false),new LinearLayout.LayoutParams(0,-2,1));
            if(assignPlayers){
                Button swap=button("↔",()->assign(m.id,1-m.team));swap.setContentDescription("Move "+m.name+" to other team");r.addView(swap);
            }
            group.addView(r);
        }
        if(botCount>0)label(group,botCount+" bots · "+MatchWorkshop.DIFFICULTIES[snapshot.entry().bots.difficulty]);
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
        if(host==null||closed||checkingHost)return;
        if(!review.blocker.isEmpty()){startFailed(review.blocker);return;}
        if(!canOpen.getAsBoolean()){startFailed("Finish the current game or file operation first.");return;}
        final int g=generation;
        checkingHost=true;
        work.execute(()->{
            try{
                NativeLobbyMatch.checkMap(dataRoot,review.entry.map);
                NativeMatchRules.encode(review.entry.preset);
            }catch(IOException|RuntimeException failure){
                ui.post(()->{if(closed||g!=generation)return;checkingHost=false;startFailed(failure.getMessage());});
                return;
            }
            ui.post(()->{
                if(closed||g!=generation)return;
                checkingHost=false;
                try{
                    host.nativeInterface();
                    applySnapshot(host.model.beginStart(review,override,LanLobbyTransport.now()));
                }catch(LobbyModel.StartNotReady warning){showStartWarning(warning.review);}
                catch(IllegalArgumentException|IllegalStateException failed){startFailed(failed.getMessage());}
            });
        });
    }
    private void startFailed(String message){
        if(snapshot.secondsToNext>=0)pauseStart(message);
        else msg(message);
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
                if(snapshot==null)saveAppearance(n,c);
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
        if(guest!=null&&!next.round.equals(preparedRound)){
            if(next.round.equals(checkingRound))return;
            checkingRound=next.round;
            final int g=generation;
            work.execute(()->{
                String problem="";
                try{NativeLobbyMatch.checkMap(dataRoot,next.plan.entries.get(next.startIndex).map);}
                catch(IOException|RuntimeException failure){problem=String.valueOf(failure.getMessage());}
                final String error=problem;
                ui.post(()->{
                    if(closed||g!=generation||snapshot==null||!snapshot.round.equals(next.round))return;
                    checkingRound="";
                    prepareLaunch(snapshot,error);
                    if(snapshot.released&&launchArgs==null){
                        leave(true,localFailure);return;
                    }
                    applySnapshot(snapshot);
                });
            });
            return;
        }
        if(!next.round.equals(preparedRound))prepareLaunch(next,"");
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
    private void prepareLaunch(LobbyModel.Snapshot next,String error){
        preparedRound=next.round;launchArgs=null;localFailure=error;
        if(!error.isEmpty())return;
        try{
            if(!canOpen.getAsBoolean())throw new IllegalStateException("Finish the current game or file operation first.");
            launchArgs=NativeLobbyMatch.arguments(next,guest==null?host.nativeInterface():guest.localAddress,guest==null?"":guest.address,
                host==null?Collections.emptyList():host.model.nativePeers());
        }catch(IllegalArgumentException|IllegalStateException failure){localFailure=failure.getMessage();}
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
    void suspend(){openFinder=false;if(!inGame&&(snapshot!=null||busy))leave(false,"Lobby closed when launcher left foreground.");}
    private void leave(boolean notify,String message){
        generation++;checkingHost=false;checkingRound="";openFinder=false;discovered=Collections.emptyMap();ui.removeCallbacks(tick);busy=false;connectionLost=false;inGame=false;launchArgs=null;preparedRound=launchedRound=localFailure=returnRound="";
        if(updateServer!=null){updateServer.stop();updateServer=null;}
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
