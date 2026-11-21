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
    private final BroadcastReceiver firewallState=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent intent) {
            if ("app".equals(page)) app(selected); else if ("home".equals(page)) home();
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui=new Ui(this);
        if (state!=null) {
            selected=state.getString("selected",""); pending=state.getString("pending","");
        }
        registerReceiver(firewallState,new IntentFilter(AppFirewall.CHANGED),Context.RECEIVER_NOT_EXPORTED);
        if (state!=null && "app".equals(state.getString("page")) && !selected.isEmpty()) app(selected);
        else if (state!=null && "apps".equals(state.getString("page"))) apps();
        else if (state!=null && "settings".equals(state.getString("page"))) settings();
        else if (state!=null && "history".equals(state.getString("page"))) history();
        else if (state!=null && "recovery".equals(state.getString("page"))) recovery();
        else if (getIntent().getBooleanExtra("changes",false)) history(); else home();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("selected",selected); state.putString("pending",pending); state.putString("page",page);
    }
    @Override protected void onDestroy() {
        generation++; worker.shutdownNow(); unregisterReceiver(firewallState); super.onDestroy();
    }
    @Override public void onBackPressed() { if ("home".equals(page)) super.onBackPressed(); else home(); }
    private void screen(String id,String title) {
        generation++; page=id;
        ScrollView scroll=new ScrollView(this); body=ui.column();
        body.setPadding(ui.dp(18),ui.dp(12),ui.dp(18),ui.dp(24)); scroll.addView(body); display(scroll);
        ui.add(body,ui.chip("‹ "+("home".equals(id)?"내 휴대폰":"휴대폰 보안"),this::onBackPressed),10);
        ui.add(body,ui.title(title,26),12);
    }
    private void display(View view) {
        view.setBackgroundColor(Ui.BG);
        view.setOnApplyWindowInsetsListener((v,insets)->{
            var bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            return insets;
        });
        setContentView(view);
        view.requestApplyInsets();
    }
    private void text(String s) { ui.add(body,ui.label(s,15,Ui.TEXT),8); }
    private void action(String title,Runnable go) { ui.add(body,ui.button(title,false,go),6); }
    private void message(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private boolean current(int token) { return token==generation && !isDestroyed() && !isFinishing(); }
    private void ask(String title,String detail,String yes,Runnable run) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setNegativeButton("취소",null)
                .setPositiveButton(yes,(d,w)->run.run()).show();
    }
    private void home() {
        screen("home","휴대폰 보안");
        long at=SecurityWatch.prefs(this).getLong("last",0);
        text(at==0?"첫 점검을 하면 변경 비교를 시작해요.":"마지막 변경 비교 · "+date(at));
        if (!notice.isEmpty()) text(notice);
        ui.add(body,ui.button("보안 설정 점검",true,this::settings),8);
        action("최근 변경 · "+SecurityWatch.history(this).length()+"건",this::history);
        action("앱 권한 및 인터넷 차단 · 차단 "+AppFirewall.blocked().size()+"개",this::apps);
        action("링크 검사",()->linkDialog(this,""));
        action("설치 파일(APK) 확인",this::pickApk);
        action("전체 검사 및 AI 설명",()->PhoneCheckUi.show(this,()->startActivity(new Intent(this,FileExplorerActivity.class).putExtra(FileExplorerActivity.EXPLORER,true)),null));
        boolean watch=SecurityWatch.enabled(this);
        text("변경 알림을 켜면 설치 앱과 민감 권한을 비교해요. 기기에서 약 6시간 간격으로 점검하며 배터리가 부족하면 미뤄요.");
        action("변경 알림 · "+(watch?"켜짐":"꺼짐"),()-> {
            if (watch) { SecurityWatch.setEnabled(this,false); home(); }
            else if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFY);
            else { SecurityWatch.setEnabled(this,true); home(); }
        });
        if (watch && !getSystemService(NotificationManager.class).areNotificationsEnabled())
            action("시스템 알림이 꺼져 있어요 · 설정 열기",()->open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));
        boolean paused=SecurityWatch.paused(this);
        action(paused?"긴급 보호 해제":"긴급 보호",()->ask(paused?"자동화를 다시 사용할까요?":"들퍼레 자동화를 중지할까요?",
                paused?"다음 폰 조작과 예약 작업을 다시 허용해요.":"현재 음성 비서·브라우저 작업과 폰 조작을 중지하고 예약 실행을 일시 정지해요. 의심 앱의 인터넷은 앱 화면에서 따로 차단할 수 있어요.",
                paused?"해제":"중지",()->{ SecurityWatch.pause(this,!paused); home(); }));
        action("금융정보를 입력했거나 송금했어요",this::recovery);
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
                    for (PhoneRisk.Finding f:PhoneRisk.sorted(PhoneRisk.device(device))) {
                        text(f.title()+(f.detail().isEmpty()?"":"\n"+f.detail()));
                        if(f.action().startsWith("settings:")) action("관련 설정 열기",()->open(new Intent(f.action().substring(9))));
                    }
                    action("최근 변경 보기"+(alerts>0?" · 새 주의 "+alerts+"건":""),this::history);
                    text("아래 설정은 시스템 화면에서 직접 확인해 주세요.");
                    action("보안 위험 자동 차단 · 삼성 보안 설정",()->open(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
                    action("Play 프로텍트 · Play 스토어에서 직접 확인",()->{
                        Intent launch=getPackageManager().getLaunchIntentForPackage("com.android.vending");
                        if(launch==null) message("Play 스토어를 직접 열어 프로필 → Play 프로텍트를 확인하세요.");
                        else { open(launch); message("프로필 → Play 프로텍트에서 확인하세요."); }
                    });
                    action("출처를 알 수 없는 앱 설치 허용",()->open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)));
                    action("다른 앱 위에 표시 권한",()->open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)));
                });
            } catch(Exception e) { runOnUiThread(()->{ if(current(token)) text("설정 점검을 완료하지 못했어요. 다시 검사해 주세요."); }); }
        });
    }
    private void history() {
        screen("history","최근 변경"); JSONArray items=SecurityWatch.history(this);
        if(items.length()==0) text("기록된 변경이 없어요. 첫 점검은 비교 기준을 저장해요.");
        for(int i=items.length()-1;i>=0;i--) {
            JSONObject row=items.optJSONObject(i); if(row==null) continue;
            String label=date(row.optLong("at"))+" · "+row.optString("message");
            String pkg=row.optString("pkg"); if(pkg.isEmpty()) text(label); else action(label,()->app(pkg));
        }
        action("지금 변경 확인",this::settings);
    }
    private void apps() {
        screen("apps","앱 권한 및 인터넷 차단"); text("이 프로필에서 조회 가능한 사용자 앱을 확인하고 있어요…");
        int token=generation;
        worker.execute(()->{
            List<PhoneRisk.App> installed;
            try { installed=PhoneCheck.userApps(this); }
            catch(RuntimeException error) { runOnUiThread(()->{ if(current(token)) text("앱 목록을 가져올 수 없어요."); }); return; }
            Set<String> blocked=AppFirewall.blocked();
            var list=installed.stream().filter(a->!a.system()&&!a.self()).sorted(Comparator
                    .comparing((PhoneRisk.App a)->!blocked.contains(a.id()))
                    .thenComparing(a->SecurityWatch.powers(a).isEmpty()).thenComparing(PhoneRisk.App::label)).collect(java.util.stream.Collectors.toList());
            runOnUiThread(()->{ if(!current(token)) return;
                screen("apps","앱 권한 및 인터넷 차단");
                text("Android가 보여주는 앱 "+list.size()+"개 · 민감 권한과 차단 앱 우선");
                // ListView recycles rows even on a phone with hundreds of packages.
                ListView rows=new ListView(this);
                ArrayList<String> labels=new ArrayList<>();
                for(var a:list) labels.add(a.label()+(AppFirewall.blocked().contains(a.id())?" · 차단 중":"")+
                        (SecurityWatch.powers(a).isEmpty()?"":"\n"+String.join(", ",SecurityWatch.powers(a))));
                rows.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels));
                rows.setOnItemClickListener((p,v,i,id)->app(list.get(i).id()));
                ScrollView original = (ScrollView) body.getParent();
                original.removeView(body);
                LinearLayout container=ui.column();
                container.addView(body,new LinearLayout.LayoutParams(-1,-2));
                container.addView(rows,new LinearLayout.LayoutParams(-1,0,1));
                display(container);
            });
        });
    }
    private void app(String id) {
        selected=id; screen("app","앱 보호 및 복구");
        String name=id;
        try { var pm=getPackageManager(); name=pm.getApplicationLabel(pm.getApplicationInfo(id,0)).toString(); }
        catch(PackageManager.NameNotFoundException e) { text("이 앱을 현재 조회할 수 없어요. 시스템 앱 목록에서 삭제 여부를 확인해 주세요."); action("설치된 앱 열기",()->open(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS))); return; }
        text(name+"\n"+id);
        boolean blocked=AppFirewall.blocked().contains(id);
        text(blocked?"인터넷 차단 중 · Wi-Fi 및 모바일 데이터":"들퍼레 인터넷 차단 꺼짐");
        if (!id.equals(getPackageName())) action("Play 스토어에서 같은 패키지 확인",()->open(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details").buildUpon().appendQueryParameter("id",id).build()).setPackage("com.android.vending")));
        if(AppFirewall.eligible(this,id)) action(blocked?"인터넷 차단 해제":"이 앱 인터넷 차단",()->{
            if(blocked) command(AppFirewall.REMOVE,id);
            else ask("이 앱의 인터넷을 차단할까요?","이 앱의 Wi-Fi와 모바일 데이터를 기기 안에서 차단해요. 같은 프로필의 다른 VPN은 연결이 종료될 수 있어요. VPN이 중단되면 차단도 풀려요.","차단",()->prepareVpn(id));
        });
        action("접근성 권한 확인",()->open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        action("알림 접근 확인",()->open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        action("기기 관리자 확인",()->open(new Intent(Settings.ACTION_SECURITY_SETTINGS)));
        action("앱 권한 및 정보",()->open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",id,null))));
        action("시스템에서 앱 삭제",()->{
            pending=id;
            try { startActivityForResult(new Intent(Intent.ACTION_UNINSTALL_PACKAGE,Uri.fromParts("package",id,null))
                        .putExtra(Intent.EXTRA_RETURN_RESULT,true),UNINSTALL); }
            catch(RuntimeException e) { open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",id,null))); }
        });
        text("삭제가 막히면 이 앱의 기기 관리자 등록을 먼저 확인하세요. 삭제 후 남은 설치 파일도 따로 확인할 수 있어요.");
        action("삭제 후 다시 점검",this::settings);
        action("남은 APK 확인",this::pickApk);
    }
    private void prepareVpn(String id) {
        pending=id;
        try { Intent consent=VpnService.prepare(this); if(consent==null) command(AppFirewall.ADD,id); else startActivityForResult(consent,VPN); }
        catch(RuntimeException e) { message("이 프로필에서 VPN을 시작할 수 없어요."); }
    }
    private void command(String action,String id) {
        try { startForegroundService(new Intent(this,AppFirewall.class).setAction(action).putExtra("package",id)); }
        catch(RuntimeException e) { message("인터넷 차단 상태를 변경하지 못했어요."); }
    }
    private void pickApk() {
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION),PICK); }
        catch(RuntimeException e) { message("파일 선택기를 열 수 없어요."); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==VPN) {
            if(result==RESULT_OK && !pending.isEmpty()) command(AppFirewall.ADD,pending); else message("VPN 승인이 취소됐어요.");
        } else if(request==UNINSTALL) {
            notice=result==RESULT_OK?"Android에서 앱 삭제를 확인했어요 · "+pending:"삭제가 완료되지 않았어요 · "+pending;
            if(result==RESULT_OK) { message(notice); settings(); } else home();
        } else if(request==PICK && result==RESULT_OK && data!=null && data.getData()!=null) inspectFile(data.getData());
    }
    private void inspectFile(Uri uri) {
        screen("apk","설치 파일 확인"); text("선택한 파일의 패키지와 서명을 확인하고 있어요…");
        int token=generation;
        worker.execute(()->{
            try {
                var report=SecurityApk.read(this,uri);
                runOnUiThread(()->{ if(!current(token)) return; screen("apk","설치 파일 확인");
                    text(report.id()+"\n"+report.detail());
                    action("설치 앱 확인",()->app(report.id()));
                    action("이 설치 파일 삭제",()->ask("선택한 APK 파일을 지울까요?","파일 선택기로 고른 문서를 삭제해요. 설치된 앱은 ‘시스템에서 앱 삭제’로 관리하세요.","파일 삭제",()->{
                        try { if(!DocumentsContract.deleteDocument(getContentResolver(),uri)) throw new IllegalStateException(); notice="선택한 APK 파일을 삭제했어요."; home(); }
                        catch(Exception e) { message("이 저장 위치에서는 삭제할 수 없어요. 파일 관리자에서 확인하세요."); }
                    }));
                });
            } catch(Exception e) { runOnUiThread(()->{ if(current(token)) text("설치 파일 검사 실패 · "+e.getMessage()); }); }
        });
    }
    static void linkDialog(Activity activity,String shared) {
        if(shared.isEmpty()) {
            EditText edit=new EditText(activity); edit.setSingleLine(true); edit.setHint("https://…");
            new AlertDialog.Builder(activity).setTitle("링크 검사").setView(edit).setNegativeButton("취소",null)
                    .setPositiveButton("검사",(d,w)->{String input=edit.getText().toString().trim(); if(!input.isEmpty()) linkDialog(activity,input);}).show(); return;
        }
        var report=SecurityLink.inspect(SecurityLink.first(shared),shared);
        var dialog=new AlertDialog.Builder(activity).setTitle("링크 검사").setMessage(report.host()+"\n"+report.reason()).setNegativeButton("닫기",null);
        if(!report.official().isEmpty()) dialog.setPositiveButton("공식 사이트 열기",(d,w)->{
            try { activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://"+report.official()+"/"))); }
            catch(RuntimeException e){ Toast.makeText(activity,"브라우저를 열 수 없어요.",Toast.LENGTH_SHORT).show(); }
        });
        dialog.show();
    }
    private void recovery() {
        screen("recovery","금융 피해 대응");
        text("의심 앱이 화면이나 통화를 조작한다면 다른 안전한 기기에서 대응하세요.");
        text("비밀번호나 인증번호를 입력했다면 공식 사이트에서 비밀번호를 변경하고 로그인된 기기와 세션을 해제하세요.");
        text("송금 피해가 있으면 다른 전화로 금융회사 또는 112에 즉시 지급정지를 요청하세요. 악성앱 상담은 KISA 118입니다.");
        text("의심 앱을 지운 뒤 Play 프로텍트로 재검사하세요. 이상이 지속되면 서비스센터나 118에 기기 초기화와 백업 복구를 상담하세요.");
        action("설치된 앱 확인",this::apps);
    }
    private void open(Intent intent) {
        try { startActivity(intent); } catch(RuntimeException e) { message("이 기기에서 해당 화면을 직접 열 수 없어요. 설정에서 확인하세요."); }
    }
}
