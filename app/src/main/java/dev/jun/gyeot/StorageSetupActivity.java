package dev.jun.gyeot;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.widget.*;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** User-controlled first-run setup. An Android Settings return is never itself a grant. */
public class StorageSetupActivity extends Activity {
    static final String PREFS="storage-setup-v1";
    static final int PICK_SAF=6940;
    interface Operations {
        boolean manager(); boolean saf();
        String prepare(BooleanSupplier cancelled)throws Exception;
        String migrate(BooleanSupplier cancelled,Consumer<String> progress)throws Exception;
        default String exportPending(BooleanSupplier cancelled)throws Exception{throw new IOException("대기 파일 내보내기를 사용할 수 없어요.");}
    }
    Operations operations(){return new Operations(){
        public boolean manager(){return android.os.Environment.isExternalStorageManager();}
        public boolean saf(){try{SharedOutputFolder.selectedSafTree(StorageSetupActivity.this);return true;}catch(IOException denied){return false;}}
        public String prepare(BooleanSupplier cancelled)throws Exception{return ManagedStorageWorkflow.prepare(StorageSetupActivity.this,cancelled);}
        public String migrate(BooleanSupplier cancelled,Consumer<String> progress)throws Exception{return ManagedStorageWorkflow.migrate(StorageSetupActivity.this,cancelled,progress);}
        public String exportPending(BooleanSupplier cancelled)throws Exception{return SharedWorkFiles.exportPending(StorageSetupActivity.this,cancelled)+"\n"+SharedMediaOutput.exportPending(StorageSetupActivity.this,()->{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("취소했어요.");});}
    };}
    private Ui ui; private Operations operations; private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean(); private boolean busy,destroyed,resumed,awaitingSettings,prepared,autoTried;
    private long generation; private TextView status;private Button permission,create,migrate,cancel,saf,exports;
    static void open(Activity activity){activity.startActivity(new Intent(activity,StorageSetupActivity.class));}
    static boolean maybeOffer(Activity activity){
        if(activity.isFinishing()||activity.isDestroyed())return false;
        boolean pending=activity.getSharedPreferences(PREFS,0).getBoolean("awaiting-manager",false)&&android.os.Environment.isExternalStorageManager();
        if(activity.getSharedPreferences(PREFS,0).getBoolean("offered",false)&&!pending)return false;
        try{SharedOutputFolder.selectedTree(activity);return false;}catch(IOException unavailable){}
        if(activity.getSharedPreferences(PREFS,0).edit().putBoolean("offered",true).commit()){open(activity);return true;}
        return false;
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);operations=operations();awaitingSettings=(state!=null&&state.getBoolean("awaiting"))||getSharedPreferences(PREFS,0).getBoolean("awaiting-manager",false);autoTried=state!=null&&state.getBoolean("autoTried");
        getSharedPreferences(PREFS,0).edit().putBoolean("offered",true).commit();ui=new Ui(this);LinearLayout page=ui.column();page.setPadding(ui.dp(18),ui.dp(14),ui.dp(18),ui.dp(14));page.setBackgroundColor(Ui.BG);
        ui.add(page,ui.title("들퍼레 저장소 설정",24),8);
        ScrollView scroll=new ScrollView(this);LinearLayout body=ui.column();scroll.addView(body);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        ui.add(body,ui.label("내장 공유 저장소 바로 아래 들퍼레 폴더를 만들고 새 파일을 한곳에 저장해요.",15,Ui.TEXT),10);
        ui.add(body,ui.label("Android ‘모든 파일 접근’은 넓은 읽기·쓰기 권한이에요. 휴대폰 설정에서 직접 허용해야 해요. 앱은 파일 읽기·쓰기를 들퍼레로 제한하고, 기존 Download/들퍼레만 이전 용도로 읽어요. 다른 폴더의 AI 탐색은 이름·종류·크기·날짜만 사용해요.",13,Ui.MUTED),10);
        status=ui.label(getSharedPreferences(PREFS,0).getString("last-result","권한과 저장 폴더 상태를 확인해 주세요. 아직 파일을 옮기지 않았어요."),14,Ui.TEXT);status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);ui.add(body,status,12);
        permission=ui.chip("Android 권한 설정",this::permission);ui.add(body,permission,4);
        create=ui.chip("폴더 만들기·상태 확인",this::prepare);ui.add(body,create,4);
        migrate=ui.chip("기존 파일 검증 복사·참조 연결",this::migrate);ui.add(body,migrate,4);
        ui.add(body,ui.label("이전 범위: Download/들퍼레의 파일만. 같은 이름은 덮어쓰지 않고 검증된 새 사본에만 앱 참조를 연결해요. 원본은 삭제하지 않아요. Pictures/Movies의 옛 파일은 이전하지 않아요.\n한 번에 최대 2,000개·폴더 깊이 20·파일당 30분. 제한·실패·중단 항목은 미완료로 남고, 불확실한 복사는 자동 재시도하지 않아요.",12,Ui.MUTED),12);
        saf=ui.chip("기존 폴더만 선택 (SAF)",()->{if(!busy&&resumed)SharedOutputFolder.chooseFolder(this,PICK_SAF);});ui.add(body,saf,4);
        exports=ui.chip("앱에 보관한 대기 파일 저장",()->{if(!busy&&resumed)run(2);});ui.add(body,exports,4);
        cancel=ui.chip("진행 작업 취소",this::cancel);ui.add(body,cancel,4);
        Button close=ui.chip("앱으로 돌아가기",()->{cancel();finish();});ui.add(page,close,8);
        page.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(ui.dp(18)+bars.left,ui.dp(14)+bars.top,ui.dp(18)+bars.right,ui.dp(14)+bars.bottom);return insets;});setContentView(page);controls();
    }
    @Override protected void onResume(){super.onResume();resumed=true;boolean granted=operations.manager();
        if(awaitingSettings){awaitingSettings=false;if(!granted)getSharedPreferences(PREFS,0).edit().putBoolean("awaiting-manager",false).commit();if(granted){autoTried=true;prepare();}else status.setText("Android 권한이 허용되지 않았어요. 폴더 생성·이전은 실행하지 않았어요. 기존 SAF 연결과 공개 폴더 탐색은 계속 사용할 수 있어요.");}
        else if(!autoTried&&granted&&!operations.saf()){autoTried=true;prepare();}
        else if(!busy&&!granted&&operations.saf())status.setText("기존 들퍼레 폴더 연결을 계속 사용해요. 새 폴더 생성에 모든 파일 접근을 다시 요구하지 않아요. 이전 기능은 별도로 시작할 수 있어요.");controls();
    }
    private void permission(){if(busy||awaitingSettings||!resumed||destroyed)return;if(!getSharedPreferences(PREFS,0).edit().putBoolean("awaiting-manager",true).commit()){status.setText("권한 요청 상태를 저장하지 못했어요. 설정 화면을 열지 않았어요.");return;}awaitingSettings=true;controls();
        try{startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));}
        catch(ActivityNotFoundException missing){awaitingSettings=false;getSharedPreferences(PREFS,0).edit().putBoolean("awaiting-manager",false).commit();status.setText("이 기기에서 앱별 모든 파일 접근 설정 화면을 열지 못했어요. Android 설정에서 이 앱 권한을 직접 확인해 주세요. 파일은 변경하지 않았어요.");controls();}
        catch(SecurityException denied){awaitingSettings=false;getSharedPreferences(PREFS,0).edit().putBoolean("awaiting-manager",false).commit();status.setText("Android가 권한 설정 화면을 차단했어요. 파일은 변경하지 않았어요.");controls();}
    }
    private void prepare(){if(busy||!resumed||destroyed)return;if(!operations.manager()){status.setText("실제 Android 모든 파일 접근 승인이 필요해요. 설정 화면에서 직접 허용해 주세요.");return;}run(0);}
    private void migrate(){if(busy||!resumed||destroyed)return;if(!prepared){status.setText("먼저 ‘폴더 만들기·상태 확인’으로 새 위치와 이전 목록을 확인해 주세요.");return;}run(1);}
    private void run(int operation){boolean copy=operation==1;cancelled.set(false);busy=true;long run=++generation;status.setText(operation==2?"앱 내부 대기 파일을 저장·검증 중…":copy?"검증 복사 준비 중… 원본은 보존해요.":"실제 저장 폴더를 만들고 이전 목록을 확인 중…");controls();
        worker.execute(()->{String result;boolean success=false;try{result=operation==2?operations.exportPending(cancelled::get):copy?operations.migrate(cancelled::get,progress->runOnUiThread(()->{if(live(run))status.setText(progress);})):operations.prepare(cancelled::get);success=true;}catch(Exception error){result="미완료 · "+String.valueOf(error.getMessage())+"\n원본은 삭제하지 않았어요. 이미 만든 사본과 이전 기록은 보존해요.";}
            String text=result;boolean ok=success;runOnUiThread(()->{if(destroyed||run!=generation)return;busy=false;if(operation==0)getSharedPreferences(PREFS,0).edit().putBoolean("awaiting-manager",false).commit();if(operation==0&&ok&&!cancelled.get())prepared=true;status.setText(cancelled.get()?"취소했어요. 이미 만든 폴더·사본과 검증 기록은 보존해요. 다시 확인한 뒤 명시적으로 재개할 수 있어요.":text);controls();});});
    }
    private boolean live(long run){return !destroyed&&resumed&&!cancelled.get()&&run==generation;}
    private void controls(){if(permission==null)return;permission.setEnabled(!busy&&!awaitingSettings);create.setEnabled(!busy);migrate.setEnabled(!busy&&prepared);saf.setEnabled(!busy);exports.setEnabled(!busy);cancel.setEnabled(busy);cancel.setVisibility(busy?android.view.View.VISIBLE:android.view.View.GONE);
        Button primary=busy?cancel:prepared?migrate:operations.manager()?create:operations.saf()?exports:permission;
        for(Button button:new Button[]{permission,create,migrate,exports,saf,cancel}){boolean selected=button==primary;button.setBackground(ui.shape(selected?Ui.ACCENT:Ui.FILL,99));button.setTextColor(selected?Ui.ON_ACCENT:button.isEnabled()?Ui.TEXT:Ui.MUTED);}
    }
    private void cancel(){if(busy){getSharedPreferences(PREFS,0).edit().putBoolean("awaiting-manager",false).commit();cancelled.set(true);status.setText("취소 요청 중… 현재 검증을 안전하게 멈춰요.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK_SAF){SharedOutputFolder.Selection choice=SharedOutputFolder.acceptSelection(this,data,result);status.setText(choice.message());}}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("awaiting",awaitingSettings);state.putBoolean("autoTried",autoTried);super.onSaveInstanceState(state);}
    @Override protected void onStop(){resumed=false;cancel();super.onStop();}
    @Override public void onBackPressed(){cancel();super.onBackPressed();}
    @Override protected void onDestroy(){destroyed=true;cancelled.set(true);generation++;worker.shutdownNow();super.onDestroy();}
}
