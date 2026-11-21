package dev.jun.gyeot;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.*;
import android.view.View;
import android.view.WindowInsets;
import dev.jun.gyeot.core.PhoneRisk;
import dev.jun.gyeot.core.SecurityLink;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class PhoneSecurityActivity extends Activity {
    private static final int VPN=901, PICK=902, UNINSTALL=903, NOTIFY=905;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Ui ui;
    private LinearLayout body;
    private String page="home", pending="", selected="", notice="";
    private int generation;
    private final ArrayList<String> path=new ArrayList<>();
    private String filter="priority";
    private int listPosition, listOffset;
    private ListView listView;
    private Uri apkUri;
    private String linkDraft="";
    private boolean linkChecked;
    private boolean manageOpen;
    private final Map<String,PhoneRisk.App> details=new HashMap<>();
    private String waitingFor="", waitingAction="", lastNetworkApp="", lastNetworkAction="";
    private final BroadcastReceiver firewallState=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent intent) {
            if (!waitingFor.isEmpty() && (waitingFor.equals(intent.getStringExtra("package")) ||
                    AppFirewall.blocked().contains(waitingFor)==AppFirewall.ADD.equals(waitingAction))) {
                boolean applied=AppFirewall.blocked().contains(waitingFor)==AppFirewall.ADD.equals(waitingAction);
                if(applied) { lastNetworkApp=waitingFor; lastNetworkAction=waitingAction; }
                message(applied ? (AppFirewall.ADD.equals(waitingAction) ? "선택한 앱의 인터넷을 차단했어요." : "인터넷 차단을 해제했어요.") : "요청을 적용하지 못했어요. 현재 상태를 확인하세요.");
                waitingFor=""; waitingAction="";
            }
            if ("app".equals(page)) app(selected);
            else if ("home".equals(page)) home();
            else if ("apps".equals(page)) { rememberList(); apps(); }
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui=new Ui(this);
        if (state!=null) {
            selected=state.getString("selected",""); pending=state.getString("pending","");
            ArrayList<String> saved=state.getStringArrayList("path");
            if(saved!=null) path.addAll(saved);
            filter=state.getString("filter","priority");
            listPosition=state.getInt("position"); listOffset=state.getInt("offset");
            lastNetworkApp=state.getString("lastNetworkApp",""); lastNetworkAction=state.getString("lastNetworkAction","");
            manageOpen=state.getBoolean("manage");
            linkDraft=state.getString("link",""); linkChecked=state.getBoolean("linkChecked");
            String uri=state.getString("apk"); if(uri!=null) apkUri=Uri.parse(uri);
        }
        registerReceiver(firewallState,new IntentFilter(AppFirewall.CHANGED),Context.RECEIVER_NOT_EXPORTED);
        if(path.isEmpty()) {
            path.add("home");
            if(getIntent().hasExtra("linkToInspect")) {
                linkDraft=getIntent().getStringExtra("linkToInspect"); if(linkDraft==null) linkDraft="";
                path.add("link"); linkChecked=!linkDraft.isBlank();
            } else if(getIntent().getBooleanExtra("changes",false)) path.add("history");
        }
        showPage();
    }
    private void rememberList() {
        if(listView==null) return;
        listPosition=listView.getFirstVisiblePosition();
        listOffset=listView.getChildCount()==0 ? 0 : listView.getChildAt(0).getTop()-listView.getPaddingTop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); rememberList();
        state.putStringArrayList("path",new ArrayList<>(path));
        state.putString("selected",selected); state.putString("pending",pending);
        state.putString("filter",filter); state.putInt("position",listPosition); state.putInt("offset",listOffset);
        state.putString("lastNetworkApp",lastNetworkApp); state.putString("lastNetworkAction",lastNetworkAction);
        state.putBoolean("manage",manageOpen); state.putString("link",linkDraft); state.putBoolean("linkChecked",linkChecked);
        if(apkUri!=null) state.putString("apk",apkUri.toString());
    }
    @Override protected void onDestroy() {
        generation++; worker.shutdownNow(); unregisterReceiver(firewallState);
        if(android.os.Build.VERSION.SDK_INT>=33 && backRegistered) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);
        super.onDestroy();
    }
    private final android.window.OnBackInvokedCallback systemBack=this::onBackPressed;
    private boolean backRegistered;
    private void updateBack() {
        boolean inside=path.size()>1;
        if(inside==backRegistered) return;
        if(inside) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,systemBack);
        else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);
        backRegistered=inside;
    }
    @Override public void onBackPressed() {
        if(path.size()<2) { finish(); return; }
        path.remove(path.size()-1); showPage();
    }
    private void go(String destination) {
        rememberList();
        int earlier=path.indexOf(destination);
        if(earlier<0) path.add(destination); else path.subList(earlier+1,path.size()).clear();
        if(destination.equals("apps")) { filter="priority"; listPosition=0; listOffset=0; }
        if(destination.equals("app")) manageOpen=false;
        showPage();
    }
    private void showPage() {
        updateBack();
        switch(path.get(path.size()-1)) {
            case "apps" -> apps();
            case "app" -> app(selected);
            case "history" -> history();
            case "settings" -> settings();
            case "recovery" -> recovery();
            case "link" -> links();
            case "files" -> apkFiles();
            case "apk" -> { if(apkUri!=null) inspectFile(apkUri); else onBackPressed(); }
            default -> home();
        }
    }
    private String previousTitle() {
        if(path.size()<2) return "내 휴대폰";
        return switch(path.get(path.size()-2)) {
            case "apps" -> "앱 점검"; case "app" -> "앱 상세"; case "settings" -> "보안 설정 점검";
            case "files" -> "APK 파일"; case "recovery" -> "금융 피해 대응 안내"; case "history" -> "최근 변경"; case "apk" -> "설치 파일 확인";
            default -> "휴대폰 보안";
        };
    }
    private void screen(String id,String title) {
        generation++; page=id; listView=null;
        ScrollView scroll=new ScrollView(this); body=ui.column();
        body.setPadding(ui.dp(16),ui.dp(4),ui.dp(16),ui.dp(24)); scroll.addView(body); display(scroll);
        var back=ui.backButton(previousTitle(),this::onBackPressed);
        back.setMinimumHeight(ui.dp(48)); back.setFocusable(true);
        body.addView(back,new LinearLayout.LayoutParams(-2,-2));
        TextView heading=ui.title(title,id.equals("home") ? 26 : 22); heading.setAccessibilityHeading(true);
        ui.add(body,heading,id.equals("home") ? 12 : 4);
    }
    private void display(View view) {
        view.setBackgroundColor(Ui.BG);
        view.setOnApplyWindowInsetsListener((v,insets)->{
            var bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom));
            return insets;
        });
        setContentView(view);
        view.requestApplyInsets();
    }
    private void text(String s) { ui.add(body,ui.label(s,15,Ui.TEXT),9); }
    private void action(String title,Runnable go) { row(body,0,title,"",go); }
    private void message(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private boolean current(int token) { return token==generation && !isDestroyed() && !isFinishing(); }
    private void ask(String title,String detail,String yes,Runnable run) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setNegativeButton("취소",null)
                .setPositiveButton(yes,(d,w)->run.run()).show();
    }
    private LinearLayout section(LinearLayout parent,String title) {
        TextView heading=ui.sectionHeader(title);
        if(!page.equals("home")) heading.setPadding(ui.dp(14),ui.dp(16),ui.dp(14),ui.dp(5));
        parent.addView(heading);
        LinearLayout group=ui.group(); parent.addView(group); return group;
    }
    private void row(LinearLayout group,int icon,String title,String sub,Runnable run) {
        View trailing=run==null ? null : ui.chevron();
        Ui.ListRow line=(Ui.ListRow)ui.listRow(icon,Ui.MUTED,title,sub,trailing,run);
        if(line.parts.subtitle()!=null) {
            line.parts.subtitle().setSingleLine(false); line.parts.subtitle().setEllipsize(null);
            line.parts.subtitle().setTextSize(14);
        }
        if(!page.equals("home")) compactRow(line);
        line.setFocusable(run!=null);
        if(ui.listGroups.contains(group)) ui.addRow(group,line,icon!=0); else group.addView(line);
    }
    private void compactRow(Ui.ListRow line) {
        line.setMinimumHeight(ui.dp(48)); line.setPadding(ui.dp(14),ui.dp(5),ui.dp(14),ui.dp(5));
        line.parts.title().setTextSize(15);
        if(line.parts.subtitle()!=null) {
            line.parts.subtitle().setTextSize(13);
            line.parts.subtitle().setPadding(0,ui.dp(2),0,0);
        }
        if(line.parts.tile()!=null) {
            LinearLayout.LayoutParams tile=new LinearLayout.LayoutParams(ui.dp(28),ui.dp(28));
            tile.setMarginEnd(ui.dp(10)); line.parts.tile().setLayoutParams(tile);
        }
    }
    private LinearLayout status(String title,String detail,int icon,int tint) {
        LinearLayout group=ui.group(); body.addView(group);
        Ui.ListRow line=(Ui.ListRow)ui.listRow(icon,tint,title,detail,null,null);
        line.parts.title().setTextSize(18);
        if(line.parts.subtitle()!=null) {
            line.parts.subtitle().setSingleLine(false); line.parts.subtitle().setEllipsize(null);
            line.parts.subtitle().setTextSize(14);
        }
        if(!page.equals("home")) { compactRow(line); line.parts.title().setTextSize(16); }
        ui.addRow(group,line,true); return group;
    }
    private void home() {
        screen("home","휴대폰 보안");
        long at=SecurityWatch.prefs(this).getLong("last",0);
        int blocked=AppFirewall.blocked().size();
        LinearLayout summary=status(blocked==0?"차단 중인 앱 없음":"인터넷 차단 중인 앱 "+blocked+"개",
                at==0?"아직 보안 설정을 점검하지 않았어요.":"마지막 설정 점검 · "+date(at),R.drawable.ic_lock,Ui.ACCENT);
        if(blocked>0) row(summary,R.drawable.ic_undo,"차단한 앱 관리","앱을 선택해 인터넷 차단을 해제할 수 있어요.",()->go("apps"));
        row(summary,R.drawable.ic_tune,"보안 설정 점검","화면 잠금과 보안 업데이트 상태 확인",()->go("settings"));
        if(!notice.isEmpty()) text(notice);
        LinearLayout left=body, right=body;
        android.content.res.Configuration config=getResources().getConfiguration();
        if(config.screenWidthDp>=600 && config.fontScale<=1.35f) {
            LinearLayout columns=ui.row(); columns.setGravity(android.view.Gravity.TOP); body.addView(columns);
            left=ui.column(); right=ui.column();
            columns.addView(left,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams side=new LinearLayout.LayoutParams(0,-2,1); side.leftMargin=ui.dp(16); columns.addView(right,side);
        }
        LinearLayout checks=section(left,"점검 도구");
        row(checks,R.drawable.ic_globe,"링크 검사","주소를 열기 전에 실제 도메인 확인",()->go("link"));
        row(checks,R.drawable.ic_file,"APK 파일 확인","다운로드한 설치 파일과 서명 확인",()->go("files"));
        row(checks,R.drawable.ic_search,"전체 검사","휴대폰 점검 보고서와 AI 설명",()->PhoneCheckUi.show(this,
                ()->startActivity(new Intent(this,FileExplorerActivity.class).putExtra(FileExplorerActivity.EXPLORER,true)),null));
        LinearLayout apps=section(right,"앱 관리");
        row(apps,R.drawable.ic_layers,"앱 점검","먼저 확인할 앱과 그 이유 보기",()->go("apps"));
        row(apps,R.drawable.ic_history,"최근 변경","기록된 변경 "+SecurityWatch.history(this).length()+"건",()->go("history"));
        boolean watch=SecurityWatch.enabled(this);
        NotificationManager notifications=getSystemService(NotificationManager.class);
        var channel=notifications.getNotificationChannel(SecurityWatch.CHANNEL);
        boolean muted=watch && (!notifications.areNotificationsEnabled() || (channel!=null && channel.getImportance()==NotificationManager.IMPORTANCE_NONE));
        Switch toggle=new Switch(this);
        toggle.setChecked(watch); toggle.setText(watch?"켜짐  ":"꺼짐  "); toggle.setTextColor(watch?Ui.SUCCESS:Ui.MUTED);
        toggle.setContentDescription("변경 알림, "+(watch?"켜짐":"꺼짐"));
        toggle.setOnCheckedChangeListener((button,checked)->{
            button.setOnCheckedChangeListener(null);
            button.setChecked(watch); // Keep the displayed state tied to the saved setting while Android requests consent.
            toggleWatch(watch);
        });
        toggle.setFocusable(false); toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Ui.ListRow toggleRow=(Ui.ListRow)ui.listRow(R.drawable.ic_notification,watch?Ui.SUCCESS:Ui.MUTED,"변경 알림",
                muted?"시스템 알림 차단됨":"새 앱과 민감 권한 변경 감지",toggle,()->toggleWatch(watch));
        toggleRow.parts.subtitle().setSingleLine(false); toggleRow.setFocusable(true);
        toggleRow.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host,info); info.setClassName(Switch.class.getName());
                info.setCheckable(true); info.setChecked(watch);
            }
        });
        ui.addRow(apps,toggleRow,true);
        if(muted) row(apps,0,"시스템 알림 설정 열기","Android에서 알림을 허용하세요.",()->open(
                channel!=null && channel.getImportance()==NotificationManager.IMPORTANCE_NONE
                ? new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID,SecurityWatch.CHANNEL)
                : new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));
        LinearLayout help=section(body,"도움말 및 긴급 조치");
        row(help,R.drawable.ic_book,"금융 피해 대응 안내","송금 또는 정보 입력 후 할 일 읽기",()->go("recovery"));
        boolean paused=SecurityWatch.paused(this);
        row(help,paused?R.drawable.ic_play:R.drawable.ic_pause,paused?"들퍼레 자동화 재개":"들퍼레 자동화 일시 중지",
            paused?"현재 일시 중지 중":"음성 비서·폰 조작·예약 실행",()->ask(paused?"자동화를 다시 사용할까요?":"들퍼레 자동화를 중지할까요?",
            paused?"다음 폰 조작과 예약 실행을 다시 허용해요.":"진행 중인 음성 비서와 브라우저 작업을 종료하고 폰 조작과 예약 실행을 멈춰요. 이 메뉴에서 재개할 수 있어요.",
            paused?"재개":"일시 중지",()->{SecurityWatch.pause(this,!paused); home();}));
    }
    private void toggleWatch(boolean on) {
        if(!on && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFY); return;
        }
        SecurityWatch.setEnabled(this,!on); home();
    }
    private static String date(long ms) { return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(ms)); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==NOTIFY) {
            if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) SecurityWatch.setEnabled(this,true);
            else message("알림을 허용하지 않았어요. 수동 점검은 계속 사용할 수 있어요.");
            home();
        }
    }
    private void settings() {
        screen("settings","보안 설정 점검"); text("설정과 앱 권한을 확인하고 있어요…");
        int token=generation;
        worker.execute(()->{
            try {
                var scan=SecurityWatch.refresh(this,()->Thread.currentThread().isInterrupted());
                if(scan==null) return;
                var device=scan.device();
                int alerts=scan.alerts();
                runOnUiThread(()->{ if(!current(token)) return;
                    screen("settings","보안 설정 점검");
                    var findings=PhoneRisk.sorted(PhoneRisk.device(device));
                    int[] counts=PhoneRisk.counts(findings);
                    int review=counts[0]+counts[1];
                    status(review>0?"확인할 설정 "+review+"개":"점검한 설정에서 이상 징후 없음","시스템에서 직접 확인할 항목도 아래에 표시해요.",R.drawable.ic_tune,review>0?Ui.WARNING:Ui.MUTED);
                    LinearLayout results=section(body,"설정별 결과");
                    for (PhoneRisk.Finding f:findings) {
                        boolean canOpen=f.action().startsWith("settings:");
                        String label=switch(f.level()) { case OK -> "점검 범위 정상"; case INFO -> "참고"; case CAUTION -> "확인 권장"; case DANGER -> "우선 확인"; };
                        row(results,0,f.title(),label,canOpen?()->open(new Intent(f.action().substring(9))):null);
                        if(!f.detail().isBlank()) {
                            TextView detail=ui.label(f.detail(),13,Ui.MUTED);
                            detail.setPadding(ui.dp(14),0,ui.dp(14),ui.dp(7)); results.addView(detail);
                        }
                    }
                    action("최근 변경 보기"+(alerts>0?" · 새 주의 "+alerts+"건":""),()->go("history"));
                    LinearLayout manual=section(body,"시스템에서 직접 확인");
                    row(manual,0,"보안 위험 자동 차단","삼성 보안 설정",()->open(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
                    row(manual,0,"Play 프로텍트","Play 스토어",()->{
                        Intent launch=getPackageManager().getLaunchIntentForPackage("com.android.vending");
                        if(launch==null) message("Play 스토어를 직접 열어 프로필 → Play 프로텍트를 확인하세요.");
                        else { open(launch); message("프로필 → Play 프로텍트에서 확인하세요."); }
                    });
                    row(manual,0,"출처를 알 수 없는 앱 설치 허용","",()->open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)));
                    row(manual,0,"다른 앱 위에 표시 권한","",()->open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)));
                });
            } catch(Exception e) { runOnUiThread(()->{ if(current(token)) text("설정 점검을 완료하지 못했어요. 다시 검사해 주세요."); }); }
        });
    }
    private void history() {
        screen("history","최근 변경"); JSONArray items=SecurityWatch.history(this);
        if(items.length()==0) text("기록된 변경이 없어요. 첫 점검은 비교 기준을 저장해요.");
        LinearLayout log=items.length()==0 ? null : section(body,"변경 기록");
        for(int i=items.length()-1;i>=0;i--) {
            JSONObject row=items.optJSONObject(i); if(row==null) continue;
            String pkg=row.optString("pkg");
            row(log,0,row.optString("message"),date(row.optLong("at")),pkg.isEmpty()?null:()->openApp(pkg));
        }
        action("지금 변경 확인",()->go("settings"));
    }
    private record AppItem(PhoneRisk.App app,PhoneRisk.Finding finding,boolean blocked) {
        boolean review() { return finding.level()==PhoneRisk.Level.DANGER || finding.level()==PhoneRisk.Level.CAUTION; }
        boolean priority() { return blocked || review(); }
    }
    private void apps() {
        screen("apps","앱 점검"); text("설치 경로와 민감 권한을 확인하고 있어요…");
        int token=generation;
        worker.execute(()->{
            try {
                var installed=PhoneCheck.userApps(this);
                if(installed.isEmpty()) throw new IllegalStateException();
                Set<String> blocked=AppFirewall.blocked();
                List<AppItem> items=new ArrayList<>();
                for(var app:installed) if(!app.self()&&!app.system()) items.add(new AppItem(app,PhoneRisk.assess(app),blocked.contains(app.id())));
                items.sort(Comparator.comparing((AppItem item)->!item.blocked()).thenComparing(item->item.finding().level())
                        .thenComparing(item->item.app().label(),java.text.Collator.getInstance(Locale.KOREAN)));
                runOnUiThread(()->{ if(current(token)) showApps(items); });
            } catch(RuntimeException error) {
                runOnUiThread(()->{ if(current(token)) { screen("apps","앱 점검"); text("앱 목록을 읽지 못해 점검 결과를 알 수 없어요."); action("다시 점검",this::apps); } });
            }
        });
    }
    private void showApps(List<AppItem> items) {
        screen("apps","앱 점검");
        int review=0, info=0, ok=0, blocked=0;
        for(var item:items) {
            if(item.review()) review++; else if(item.finding().level()==PhoneRisk.Level.OK) ok++; else info++;
            if(item.blocked()) blocked++;
        }
        String title=items.isEmpty()?"조회 가능한 사용자 앱 없음":review>0?"먼저 확인할 앱 "+review+"개"
                :info>0?"확인된 민감 권한 없음":"점검한 항목에서 이상 징후 없음";
        String detail="점검 범위 정상 "+ok+"개 · 추가 정보 확인 "+info+"개 · 인터넷 차단 "+blocked+"개";
        status(title,detail,review>0?R.drawable.ic_eye:R.drawable.ic_info,review>0?Ui.WARNING:Ui.MUTED);
        text("앱을 누르면 점검 이유를 볼 수 있어요. 차단과 삭제는 직접 선택할 때만 진행해요.");
        ArrayList<String> names=new ArrayList<>(List.of("먼저 확인","전체 앱"));
        ArrayList<String> filters=new ArrayList<>(List.of("priority","all"));
        if(info>0) { names.add("추가 정보"); filters.add("info"); }
        if(!filters.contains(filter)) filter="priority";
        Button[] tabs=new Button[names.size()];
        for(int i=0;i<names.size();i++) {
            String mode=filters.get(i); boolean on=mode.equals(filter);
            Button b=SecurityControls.button(ui,names.get(i),on,()->{ filter=mode; listPosition=0; listOffset=0; showApps(items); });
            b.setSelected(on);
            b.setContentDescription(names.get(i)+(on?", 선택됨":""));
            tabs[i]=b;
        }
        body.addView(SecurityControls.bar(ui,tabs));
        List<AppItem> shown=items.stream().filter(i->filter.equals("all") || (filter.equals("info") ? i.finding().level()==PhoneRisk.Level.INFO : i.priority())).collect(java.util.stream.Collectors.toList());
        if(shown.isEmpty()) {
            text(items.isEmpty()?"현재 프로필에서 점검할 앱을 찾지 못했어요.":"지금 먼저 확인할 권한이나 차단한 앱이 없어요. 특정 앱은 ‘전체 앱’에서 볼 수 있어요.");
            return;
        }
        ScrollView scroll=(ScrollView)body.getParent(); scroll.removeView(body);
        ListView rows=new ListView(this); rows.setClipToPadding(false);
        rows.addHeaderView(body,null,false); rows.setDividerHeight(1);
        rows.setAdapter(new BaseAdapter(){
            public int getCount(){return shown.size();}
            public AppItem getItem(int i){return shown.get(i);}
            public long getItemId(int i){return i;}
            public View getView(int i,View recycled,android.view.ViewGroup parent){
                LinearLayout line=(LinearLayout)recycled;
                if(line==null) {
                    line=ui.column(); line.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    line.setPadding(ui.dp(30),ui.dp(5),ui.dp(30),ui.dp(5)); line.setMinimumHeight(ui.dp(48));
                    line.addView(ui.text("",15,600,Ui.TEXT)); line.addView(ui.label("",13,Ui.MUTED));
                }
                var item=getItem(i); TextView label=(TextView)line.getChildAt(0); TextView why=(TextView)line.getChildAt(1);
                label.setText(item.app().label()+(item.blocked()?" · 인터넷 차단 중":""));
                String verdict=item.finding().title();
                if(item.review()) verdict+=" · "+String.join(", ",PhoneRisk.reach(item.app()));
                else if(item.finding().level()==PhoneRisk.Level.INFO && !PhoneRisk.fromStore(item.app()))
                    verdict+=item.app().installer().isBlank()?" · 출처 미확인":" · 스토어 외 설치";
                why.setText(verdict);
                why.setTextColor(item.review()?Ui.WARNING:Ui.MUTED);
                return line;
            }
        });
        rows.setOnItemClickListener((p,v,index,id)->{
            AppItem item=(AppItem) p.getItemAtPosition(index);
            if(item!=null) { details.put(item.app().id(),item.app()); openApp(item.app().id()); }
        });
        listView=rows; display(rows);
        rows.setSelectionFromTop(Math.min(listPosition,shown.size()),listOffset);
    }
    private void openApp(String id) { selected=id; go("app"); }
    private void app(String id) {
        var cached=details.get(id);
        if(cached!=null) { appDetail(cached); return; }
        screen("app","앱 상세"); text("앱 정보를 확인하고 있어요…");
        int token=generation;
        worker.execute(()->{
            PhoneRisk.App info;
            try { info=PhoneCheck.appDetails(this,id); } catch(RuntimeException denied) { info=null; }
            PhoneRisk.App found=info;
            runOnUiThread(()->{ if(!current(token)) return;
                if(found==null) { screen("app","앱 상세"); text("현재 이 앱의 설치 정보나 권한을 확인할 수 없어요."); action("시스템 앱 목록 열기",()->open(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))); }
                else { details.put(id,found); appDetail(found); }
            });
        });
    }
    private void appDetail(PhoneRisk.App app) {
        String id=app.id();
        screen("app",app.label());
        var assessment=PhoneRisk.assess(app);
        boolean blocked=AppFirewall.blocked().contains(id);
        boolean review=assessment.level()==PhoneRisk.Level.DANGER || assessment.level()==PhoneRisk.Level.CAUTION;
        status(assessment.title(),assessment.detail(),review?R.drawable.ic_eye:R.drawable.ic_info,review?Ui.WARNING:Ui.MUTED);
        // Android lets no app take another app's permission away: this opens that app's own page, where 권한 turns it off.
        java.util.List<String> held=PhoneRisk.messagePermissions(app);
        if(!held.isEmpty() && !id.equals(getPackageName())) {
            LinearLayout perms=section(body,"권한 끄기");
            row(perms,R.drawable.ic_chat,"문자·통화 기록 권한 끄기","켜져 있음: "+String.join(" · ",held)+"\n앱 정보 → 권한 → SMS·통화 기록 → ‘허용 안 함’",()->{
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",id,null)));
                message("권한 → SMS·통화 기록을 눌러 ‘허용 안 함’으로 바꿔 주세요. 돌아오면 다시 확인해요.");
            });
        }
        LinearLayout net=section(body,"들퍼레 인터넷 차단");
        String netTitle=blocked?"현재 차단 중":"현재 차단 안 함";
        if(id.equals(lastNetworkApp) && blocked==AppFirewall.ADD.equals(lastNetworkAction))
            netTitle=blocked?"인터넷 차단 적용됨":"인터넷 차단 해제됨";
        row(net,R.drawable.ic_globe,netTitle,blocked?
                "이 앱의 Wi-Fi와 모바일 데이터를 들퍼레가 차단하고 있어요.":"들퍼레에 이 앱의 차단 규칙이 없어요.",null);
        if(id.equals(waitingFor)) row(net,0,"변경 적용 중…","VPN의 응답을 기다리고 있어요.",null);
        else if(AppFirewall.eligible(this,id)) {
            if(blocked) row(net,R.drawable.ic_undo,"인터넷 차단 해제","이 앱이 다시 인터넷을 사용하도록 허용",()->command(AppFirewall.REMOVE,id));
            else row(net,R.drawable.ic_lock,"이 앱의 인터넷 차단…","확인 후 적용 · 이곳에서 언제든 해제",()->ask(app.label()+"의 인터넷을 차단할까요?",
                    "Wi-Fi와 모바일 데이터를 차단해요. 다른 VPN 연결이 종료될 수 있어요. 이 화면의 ‘인터넷 차단 해제’로 되돌릴 수 있어요.","인터넷 차단",()->prepareVpn(id)));
        }
        LinearLayout options=section(body,"앱 관리");
        row(options,R.drawable.ic_settings,manageOpen?"관리 옵션 접기":"권한·설치 정보 보기",manageOpen?id:"Android 권한 설정, 스토어 정보 및 삭제",()->{manageOpen=!manageOpen; appDetail(app);});
        if(!manageOpen) return;
        row(options,0,"앱 권한 및 정보",id,()->open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",id,null))));
        row(options,0,"접근성 설정 열기","",()->open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        row(options,0,"알림 접근 설정 열기","",()->open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        row(options,0,"기기 관리자 확인","Android 보안 설정",()->open(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
        if(!id.equals(getPackageName())) row(options,0,"Play 스토어에서 확인","같은 패키지의 등록 정보",()->open(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details").buildUpon().appendQueryParameter("id",id).build()).setPackage("com.android.vending")));
        LinearLayout removal=section(body,"삭제 및 재점검");
        row(removal,0,"앱 삭제…","Android에서 확인하면 앱과 앱 데이터가 삭제돼요.",()->{
            pending=id;
            try { startActivityForResult(new Intent(Intent.ACTION_UNINSTALL_PACKAGE,Uri.fromParts("package",id,null)).putExtra(Intent.EXTRA_RETURN_RESULT,true),UNINSTALL); }
            catch(RuntimeException e) { open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",id,null))); }
        });
        row(removal,0,"다시 점검","휴대폰 설정과 앱 권한 비교",()->go("settings"));
        row(removal,0,"남은 APK 확인","다운로드한 설치 파일 보기",()->go("files"));
    }
    @Override protected void onRestart() {
        super.onRestart();
        if("app".equals(page)) { details.remove(selected); app(selected); }
        else if("home".equals(page)) home();
    }
    private void prepareVpn(String id) {
        pending=id;
        try { Intent consent=VpnService.prepare(this); if(consent==null) command(AppFirewall.ADD,id); else startActivityForResult(consent,VPN); }
        catch(RuntimeException e) { message("이 프로필에서 VPN을 시작할 수 없어요."); }
    }
    private void command(String action,String id) {
        waitingFor=id; waitingAction=action;
        if ("app".equals(page)) app(selected);
        try { startForegroundService(new Intent(this,AppFirewall.class).setAction(action).putExtra("package",id)); }
        catch(RuntimeException e) { waitingFor=""; waitingAction=""; app(selected); message("인터넷 차단 상태를 변경하지 못했어요."); }
    }
    private void apkFiles() {
        screen("files","APK 파일");
        text("다운로드 폴더에서 설치 파일을 찾고 있어요…");
        int token=generation;
        worker.execute(()->{
            SecurityDownloads.Listing listing;
            try { listing=SecurityDownloads.list(this); } catch(Exception restricted) { listing=null; }
            var result=listing;
            runOnUiThread(()->{
                if(!current(token)) return;
                screen("files","APK 파일");
                String title=result==null?"다운로드 폴더 조회 제한":result.files().isEmpty()?
                        (result.partial()?"조회 가능한 APK 없음":"다운로드 폴더에 APK 없음"):"다운로드 폴더 · APK "+result.files().size()+"개";
                status(title,result!=null && !result.partial()?"폴더 바로 아래의 .apk 파일만 표시해요. 파일을 눌러 서명과 권한을 살펴보세요.":
                        "직접 읽을 수 있는 파일만 표시해요. 보이지 않는 APK는 파일 선택기로 찾을 수 있어요.",R.drawable.ic_folder,Ui.MUTED);
                row(body,R.drawable.ic_folder,"다른 위치에서 APK 선택","Android 파일 선택기에서 설치 파일 찾기",this::pickApk);
                if(result!=null && !result.files().isEmpty()) {
                    LinearLayout files=section(body,"최근 파일");
                    for(var file:result.files()) row(files,R.drawable.ic_file,file.name(),
                            android.text.format.Formatter.formatShortFileSize(this,file.bytes())+" · "+date(file.modified()),()->inspect(file.uri()));
                }
            });
        });
    }
    static Intent apkPicker() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(SecurityDownloads.MIME).addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"));
    }
    private void pickApk() {
        try { startActivityForResult(apkPicker(),PICK); }
        catch(RuntimeException e) { message("파일 선택기를 열 수 없어요."); }
    }
    private void inspect(Uri uri) { apkUri=uri; go("apk"); }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==VPN) {
            if(result==RESULT_OK && !pending.isEmpty()) command(AppFirewall.ADD,pending); else message("VPN 승인이 취소됐어요.");
        } else if(request==UNINSTALL) {
            notice=result==RESULT_OK?"Android에서 앱 삭제를 확인했어요 · "+pending:"삭제가 완료되지 않았어요 · "+pending;
            if(result==RESULT_OK) { details.remove(pending); message(notice); go("settings"); } else { message("앱 삭제를 취소했어요."); showPage(); }
        } else if(request==PICK && result==RESULT_OK && data!=null && data.getData()!=null) inspect(data.getData());
    }
    private void inspectFile(Uri uri) {
        screen("apk","설치 파일 확인"); text("선택한 파일의 패키지와 서명을 확인하고 있어요…");
        int token=generation;
        worker.execute(()->{
            try {
                var report=SecurityApk.read(this,uri);
                runOnUiThread(()->{ if(!current(token)) return; screen("apk","설치 파일 확인");
                    text(report.id()+"\n"+report.detail());
                    action("설치 앱 확인",()->openApp(report.id()));
                    if(!DocumentsContract.isDocumentUri(this,uri)) {
                        row(body,0,"파일 삭제용 APK 선택","시스템 파일 선택기에서 원본을 선택한 뒤 삭제할 수 있어요.",this::pickApk); return;
                    }
                    action("이 설치 파일 삭제",()->ask("선택한 APK 파일을 지울까요?","파일 선택기로 고른 문서를 삭제해요. 설치된 앱은 ‘시스템에서 앱 삭제’로 관리하세요.","파일 삭제",()->{
                        try { if(!DocumentsContract.deleteDocument(getContentResolver(),uri)) throw new IllegalStateException(); notice="선택한 APK 파일을 삭제했어요."; apkUri=null; message(notice); go("files"); }
                        catch(Exception e) { message("이 저장 위치에서는 삭제할 수 없어요. 파일 관리자에서 확인하세요."); }
                    }));
                });
            } catch(Exception e) { runOnUiThread(()->{ if(current(token)) text("설치 파일 검사 실패 · "+e.getMessage()); }); }
        });
    }
    static void sharedLink(Activity activity,String shared) {
        activity.startActivity(new Intent(activity,PhoneSecurityActivity.class).putExtra("linkToInspect",shared));
    }
    private void links() {
        screen("link","링크 검사");
        text("주소를 열지 않고 기기 안에서 검사해요. 공유 문구가 함께 있으면 첫 번째 주소를 확인해요.");
        body.setFocusableInTouchMode(true); body.requestFocus();
        EditText field=ui.input("웹 주소 또는 공유 문구",2,20000);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMinLines(2); field.setMaxLines(5); field.setText(linkDraft); field.setSelection(field.length());
        field.setContentDescription("검사할 링크"); ui.add(body,field,8);
        Button paste=SecurityControls.button(ui,"붙여넣기",false,()->{
            android.content.ClipboardManager clipboard=getSystemService(android.content.ClipboardManager.class);
            ClipData clip=clipboard.getPrimaryClip();
            if(clip==null || clip.getItemCount()==0) { message("복사한 주소가 없어요."); return; }
            ClipData.Item item=clip.getItemAt(0); CharSequence value=item.getText();
            if(value==null && item.getUri()!=null) value=item.getUri().toString();
            if(value==null) { message("복사한 내용에서 주소를 찾지 못했어요."); return; }
            field.setText(value); field.setSelection(field.length());
        });
        LinearLayout result=ui.column();
        Button check=SecurityControls.button(ui,"검사하기",true,()->{
            ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(),0);
            linkChecked=true; linkResult(result);
        });
        body.addView(SecurityControls.bar(ui,paste,check));
        body.addView(result); check.setEnabled(!linkDraft.isBlank());
        field.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void afterTextChanged(android.text.Editable s) {}
            public void onTextChanged(CharSequence words,int start,int before,int count) {
                linkDraft=words.toString(); linkChecked=false; result.removeAllViews(); check.setEnabled(!linkDraft.isBlank());
            }
        });
        if(linkChecked) linkResult(result);
    }
    private void linkResult(LinearLayout result) {
        result.removeAllViews();
        String raw=SecurityLink.first(linkDraft);
        String trimmed=linkDraft.trim();
        if(raw.isEmpty() && trimmed.matches("[^\\s/:@]+\\.[^\\s/:@]+(?:/[^\\s]*)?")) raw="https://"+trimmed;
        var report=SecurityLink.inspect(raw,linkDraft);
        LinearLayout group=section(result,"검사 결과");
        group.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row(group,R.drawable.ic_info,report.verdict().title,report.reason(),null);
        if(!report.host().isBlank()) row(group,0,"실제 도메인",report.host(),null);
        if(!report.official().isEmpty()) row(group,R.drawable.ic_globe,"공식 사이트 열기",report.official(),()->open(
                new Intent(Intent.ACTION_VIEW,Uri.parse("https://"+report.official()+"/"))));
    }
    private void recovery() {
        screen("recovery","금융 피해 대응 안내");
        status("대응 방법 안내","이 안내를 읽거나 앱 목록을 열어도 신고나 차단이 실행되지 않아요.",R.drawable.ic_book,Ui.MUTED);
        text("의심 앱이 화면이나 통화를 조작한다면 다른 안전한 기기에서 대응하세요.");
        text("비밀번호나 인증번호를 입력했다면 공식 사이트에서 비밀번호를 변경하고 로그인된 기기와 세션을 해제하세요.");
        text("송금 피해가 있으면 다른 전화로 금융회사 또는 112에 즉시 지급정지를 요청하세요. 악성앱 상담은 KISA 118입니다.");
        text("의심 앱을 지운 뒤 Play 프로텍트로 재검사하세요. 이상이 지속되면 서비스센터나 118에 기기 초기화와 백업 복구를 상담하세요.");
        row(section(body,"내 휴대폰에서 확인"),R.drawable.ic_search,"앱 점검 결과 보기","앱을 선택해 권한과 차단 상태 확인",()->go("apps"));
    }
    private void open(Intent intent) {
        try { startActivity(intent); } catch(RuntimeException e) { message("이 기기에서 해당 화면을 직접 열 수 없어요. 설정에서 확인하세요."); }
    }
}
