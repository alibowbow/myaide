package dev.jun.gyeot;

import android.app.*;
import android.os.Bundle;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local per-file review only. Never gives the AI a deletion tool or a batch-delete button. */
final class DuplicateTrashUi {
    private DuplicateTrashUi(){}
    static void review(Activity a,FileExplorerRepository repo,List<FileExplorerRepository.Entry> candidates,Runnable changed){
        if(!DuplicateTrash.supported(repo)){notice(a,"삭제 가능 위치 안내","외부 폴더는 목록 검색만 가능해요. 휴지통은 앱에 연결된 내장 공유 저장소/들퍼레에서만 지원해요. 폴더 선택(SAF)만 연결된 경우 저장 위치에서 앱 저장 폴더 연결을 확인해 주세요.");return;}
        try{new Session(a,new DuplicateTrash(a,repo),candidates,changed).show();}catch(Exception e){notice(a,"휴지통을 열지 못했어요",e.getMessage());}
    }
    static void records(Activity a,FileExplorerRepository repo,Runnable changed){review(a,repo,List.of(),changed);}
    static void notice(Activity a,String title,String message){new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("닫기",null).show();}
    interface Work<T>{T run()throws Exception;}
    static final class Session implements Application.ActivityLifecycleCallbacks {
        final Activity activity;final DuplicateTrash trash;final List<FileExplorerRepository.Entry> candidates;final Runnable changed;
        final ExecutorService worker=Executors.newSingleThreadExecutor();final AtomicBoolean cancelled=new AtomicBoolean();final Set<AlertDialog> dialogs=new HashSet<>();
        volatile boolean closed;boolean busy;long epoch;
        Session(Activity a,DuplicateTrash t,List<FileExplorerRepository.Entry> c,Runnable changed){activity=a;trash=t;candidates=List.copyOf(c);this.changed=changed;a.getApplication().registerActivityLifecycleCallbacks(this);}
        void show(){if(closed)return;epoch++;if(candidates.isEmpty()){records();return;}List<FileExplorerRepository.Entry> shown=candidates;String[] names=shown.stream().map(e->e.name()+" · "+FileExplorerActivity.size(e.size())).toArray(String[]::new);
            AlertDialog d=new AlertDialog.Builder(activity).setTitle("중복 파일 · 한 개씩 검토").setItems(names,(x,i)->prepare(shown.get(i))).setNeutralButton("휴지통 기록",(x,i)->records()).setNegativeButton("닫기",(x,i)->close()).create();d.setOnCancelListener(x->close());track(d);}
        void prepare(FileExplorerRepository.Entry target){run(()->trash.prepareCandidates(target,candidates,cancelled::get),pair->{long stamp=epoch;String body="옮길 파일: "+target.name()+"\n"+pair.remove().path()+"\n\n남길 동일 원본: "+pair.keep().path()+"\n크기: "+FileExplorerActivity.size(pair.remove().bytes())+"\nSHA-256: "+pair.remove().hash().substring(0,16)+"…\n\n이 파일 한 개만 들퍼레 안의 휴지통으로 옮겨요. 실행 직전 다시 검증하고, 최소 한 개의 동일 원본은 남겨요. 자동 비우기·영구 삭제는 없고 휴지통도 저장 공간을 사용해요. 이동 후 다른 앱이 파일을 바꿨다면 완료로 표시하지 않고 복구 위치를 알려드려요.";
                AlertDialog d=new AlertDialog.Builder(activity).setTitle("이 파일을 휴지통으로 옮길까요?").setMessage(body).setNegativeButton("취소",(x,i)->show()).setPositiveButton("이 파일만 이동",(x,i)->{if(live(stamp))run(()->trash.trash(pair,cancelled::get),receipt->{changed.run();notice("휴지통 이동 결과",receipt.message()+"\n"+receipt.payload(),this::records);});}).create();d.setOnCancelListener(x->show());track(d);});}
        void records(){run(trash::list,items->{if(items.isEmpty()){notice("휴지통 기록","아직 옮긴 파일이 없어요. 자동 비우기는 하지 않아요.",this::close);return;}
            List<DuplicateTrash.Receipt> shown=items;String[] labels=shown.stream().map(r->ManagedSharedStorage.name(r.source())+" · "+state(r.state())).toArray(String[]::new);
            AlertDialog d=new AlertDialog.Builder(activity).setTitle("휴지통 기록 · 최근 "+shown.size()+"개").setItems(labels,(x,i)->record(shown.get(i))).setNegativeButton("닫기",(x,i)->close()).create();d.setOnCancelListener(x->close());track(d);});}
        static String state(String value){return switch(value){case "trashed"->"보존 중";case "restored_copy"->"복원 사본 확인 · 휴지통도 보존";case "cancelled_before_move"->"이동 전 취소";default->"확인 필요 · 자동 재시도 안 함";};}
        void record(DuplicateTrash.Receipt r){long stamp=epoch;AlertDialog.Builder b=new AlertDialog.Builder(activity).setTitle(state(r.state())).setMessage("원래 위치: "+r.source()+"\n보존/확인 위치: "+r.payload()+"\n\n복원은 원래 위치에 새 사본을 만들어요. 같은 이름이 있으면 덮어쓰지 않아요. 복원 후에도 휴지통 항목은 보존해요. 확인이 필요한 항목은 자동 재시도하지 마세요.").setNegativeButton("목록",(d,w)->records());
            if(r.state().equals("trashed"))b.setPositiveButton("이 파일 복원",(d,w)->{if(live(stamp))run(()->trash.restore(r.id(),cancelled::get),receipt->{changed.run();notice("복원 결과",receipt.message(),this::records);});});AlertDialog detail=b.create();detail.setOnCancelListener(d->close());track(detail);}
        <T> void run(Work<T> work,java.util.function.Consumer<T> success){if(closed||busy)return;busy=true;long stamp=++epoch;AlertDialog progress=new AlertDialog.Builder(activity).setTitle("파일 상태 확인 중…").setMessage("기기 안에서 검증해요. 완료 전에는 다시 실행하지 마세요.").setNegativeButton("취소",(d,w)->close()).create();progress.setOnCancelListener(d->close());track(progress);worker.execute(()->{try{T result=work.run();activity.runOnUiThread(()->{if(live(stamp)){progress.dismiss();busy=false;success.accept(result);}});}catch(Exception e){activity.runOnUiThread(()->{if(live(stamp)){progress.dismiss();busy=false;notice("완료되지 않았어요",String.valueOf(e.getMessage()),this::records);}});}});}
        boolean live(long stamp){return !closed&&!cancelled.get()&&stamp==epoch&&!activity.isFinishing()&&!activity.isDestroyed();}
        void notice(String title,String text,Runnable after){AlertDialog d=new AlertDialog.Builder(activity).setTitle(title).setMessage(text).setPositiveButton("확인",(x,i)->after.run()).create();d.setOnCancelListener(x->close());track(d);}
        void track(AlertDialog d){if(closed)return;dialogs.add(d);d.setOnDismissListener(x->dialogs.remove(d));d.show();}
        void close(){if(closed)return;closed=true;epoch++;cancelled.set(true);worker.shutdownNow();for(AlertDialog d:new ArrayList<>(dialogs))d.dismiss();activity.getApplication().unregisterActivityLifecycleCallbacks(this);}
        public void onActivityStopped(Activity a){if(a==activity)close();}public void onActivityDestroyed(Activity a){if(a==activity)close();}
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){if(a==activity)close();}public void onActivitySaveInstanceState(Activity a,Bundle b){}
    }
}
