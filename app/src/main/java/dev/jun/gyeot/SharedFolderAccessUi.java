package dev.jun.gyeot;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Bundle;
import java.util.IdentityHashMap;
import java.util.Map;

/** Explains the one-time Android grant before leaving the app; never creates or migrates files. */
final class SharedFolderAccessUi {
    private SharedFolderAccessUi() {}
    private static final Map<Activity,Consent> sessions=new IdentityHashMap<>();
    static void chooseOutput(Activity activity,int requestCode) {
        boolean connected;
        try { SharedOutputFolder.selectedTree(activity); connected=true; }
        catch (Exception unavailable) { connected=false; }
        String text=(connected?"들퍼레 폴더는 이미 연결되어 있어요. 평소 폴더 탐색은 앱 안에서 해요. 아래 버튼은 Android 권한을 다시 설정할 때만 사용해요.\n\n":"처음 한 번만 Android에서 저장 폴더를 허용해 주세요. 허용 뒤에는 이 앱의 목록에서 계속 탐색해요.\n\n")
            +"1. 다음 화면에서 ‘내장 저장공간’ 또는 휴대폰 이름을 열어요. Download 안으로 들어가지 않아요.\n"
            +"2. 들퍼레가 없다면 ‘새 폴더 만들기’(폴더＋)를 눌러 이름을 들퍼레로 만들어요. 이미 있다면 다시 만들지 않아요.\n"
            +"3. 들퍼레 폴더 안으로 들어간 뒤 ‘이 폴더 사용’ → ‘허용’을 눌러요.\n\n"
            +"‘이 폴더를 사용할 수 없음’은 저장공간 전체를 선택할 수 없다는 뜻이에요. 들퍼레 안으로 들어가 선택해 주세요.\n\n"
            +"아직 앱이 폴더를 만들거나 파일을 옮긴 것은 아니에요. 기존 Download/들퍼레는 그대로 남아요.";
        show(activity,new AlertDialog.Builder(activity).setTitle(connected?"저장 폴더 권한 다시 설정":"처음 한 번 · 들퍼레 폴더 연결")
            .setMessage(text),"Android 권한 화면",()->SharedOutputFolder.launchPicker(activity,requestCode));
    }
    static void chooseMetadata(Activity activity,String scope,Runnable picker) {
        show(activity,new AlertDialog.Builder(activity).setTitle("처음 한 번 · 폴더 목록 허용")
            .setMessage(scope+"\n\n다음 화면은 외부 파일을 여는 링크가 아니라 Android 권한 선택 화면이에요. 볼 폴더를 한 번 허용하면 이후에는 이 앱 안에서 폴더·파일 목록을 탐색해요.\n\n다른 폴더에서는 이름·종류·크기·날짜만 보고 내용 열기·복사·이동·삭제는 하지 않아요. 저장공간 전체나 Download 자체가 아닌 필요한 하위 폴더를 골라 주세요.")
            ,"Android 권한 화면",picker);
    }
    private static void show(Activity activity,AlertDialog.Builder builder,String positive,Runnable picker) {
        if(activity.isFinishing()||activity.isDestroyed())return;
        Consent prior=sessions.get(activity);if(prior!=null)prior.close();
        Consent consent=new Consent(activity,picker);sessions.put(activity,consent);
        activity.getApplication().registerActivityLifecycleCallbacks(consent);
        consent.dialog=builder.setNegativeButton("취소",(d,w)->consent.close())
            .setPositiveButton(positive,(d,w)->consent.confirm()).create();
        consent.dialog.setOnCancelListener(d->consent.close());
        consent.dialog.setOnDismissListener(d->consent.close());
        consent.dialog.show();
    }
    /** A live, one-shot explicit confirmation; stale/dismissed/stopped dialogs cannot launch. */
    private static final class Consent implements Application.ActivityLifecycleCallbacks {
        final Activity activity;final Runnable picker;AlertDialog dialog;boolean active=true;
        Consent(Activity activity,Runnable picker){this.activity=activity;this.picker=picker;}
        void close(){if(!active)return;active=false;activity.getApplication().unregisterActivityLifecycleCallbacks(this);if(sessions.get(activity)==this)sessions.remove(activity);if(dialog!=null&&dialog.isShowing())dialog.dismiss();}
        void confirm(){if(!active)return;boolean live=!activity.isFinishing()&&!activity.isDestroyed();close();if(!live)return;
            try{picker.run();}catch(RuntimeException error){new AlertDialog.Builder(activity).setTitle("폴더 허용 화면을 열지 못했어요").setMessage("Android 파일 선택기를 사용할 수 없어요. 파일이나 기존 연결은 변경하지 않았어요.").setPositiveButton("확인",null).show();}
        }
        @Override public void onActivityStopped(Activity owner){if(owner==activity)close();}
        @Override public void onActivityDestroyed(Activity owner){if(owner==activity)close();}
        @Override public void onActivityCreated(Activity owner,Bundle state){}
        @Override public void onActivityStarted(Activity owner){}
        @Override public void onActivityResumed(Activity owner){}
        @Override public void onActivityPaused(Activity owner){}
        @Override public void onActivitySaveInstanceState(Activity owner,Bundle state){}
    }
}
