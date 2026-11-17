package dev.jun.gyeot;

import android.app.Activity;
import android.app.AlertDialog;
import dev.jun.gyeot.core.*;
import java.util.*;
import java.util.function.*;
import org.json.*;

/** Catalog-backed, explorer-only selection. Opening/cancelling never changes a saved choice. */
final class FileExplorerModelUi {
    private FileExplorerModelUi() {}
    static void show(Activity activity,AppStore store,BooleanSupplier valid,Consumer<AlertDialog> present,Runnable applied) {
        if(!valid.getAsBoolean())return;
        java.util.concurrent.atomic.AtomicBoolean alive=new java.util.concurrent.atomic.AtomicBoolean(true);
        BooleanSupplier original=valid;BooleanSupplier active=()->alive.get()&&original.getAsBoolean();
        Consumer<AlertDialog> tracked=dialog->{dialog.setOnCancelListener(d->alive.set(false));present.accept(dialog);android.widget.Button cancel=dialog.getButton(AlertDialog.BUTTON_NEGATIVE);if(cancel!=null)cancel.setOnClickListener(v->{alive.set(false);dialog.cancel();});};
        open(activity,store,active,tracked,()->{if(active.getAsBoolean()){alive.set(false);applied.run();}});
    }
    private static void open(Activity activity,AppStore store,BooleanSupplier valid,Consumer<AlertDialog> present,Runnable applied) {
        TeamRoster roster=new TeamRoster(store,ScopedModels.explorer(store));
        List<String> engines=TeamPlan.ENGINES;
        String[] labels=engines.stream().map(e->WorkBook.author(e)+(store.ready(e)?" · "+roster.label(e,roster.selected(e)):" · 연결 필요")).toArray(String[]::new);
        present.accept(new AlertDialog.Builder(activity).setTitle("탐색 AI · 제공자 선택").setItems(labels,(d,index)->{
            if(!valid.getAsBoolean())return;String engine=engines.get(index);
            if(!store.ready(engine)){message(activity,present,"연결 필요","설정에서 선택한 AI를 연결해 주세요. 다른 AI로 자동 전환하지 않아요.");return;}
            try{FileAiOperationsUi.Connection connection=FileAiOperationsUi.Connection.capture(store,engine);models(activity,store,engine,connection,valid,present,applied);}
            catch(Exception error){message(activity,present,"연결 확인 필요","AI 연결이 바뀌었어요. 다시 선택해 주세요.");}
        }).setNegativeButton("닫기",null).create());
    }
    private static void models(Activity a,AppStore s,String engine,FileAiOperationsUi.Connection connection,BooleanSupplier valid,Consumer<AlertDialog> present,Runnable applied){
        TeamRoster roster=new TeamRoster(s,ScopedModels.explorer(s));JSONArray catalog=roster.catalog(engine);List<String> ids=new ArrayList<>(),labels=new ArrayList<>();
        for(int i=0;i<catalog.length();i++){JSONObject item=catalog.optJSONObject(i);if(item==null)continue;String id=item.optString("id");if(id.isBlank()||id.length()>200||id.chars().anyMatch(Character::isISOControl)||ids.contains(id))continue;ids.add(id);labels.add(roster.label(engine,id)+"\n"+id+(id.equals(roster.selected(engine))?" · 선택됨":""));}
        if(ids.isEmpty()){message(a,present,"모델 목록 없음","연결 설정에서 모델 목록을 새로고침해 주세요. 임의 모델이나 다른 제공자로 대체하지 않아요.");return;}
        present.accept(new AlertDialog.Builder(a).setTitle(WorkBook.author(engine)+" · 세부 모델").setItems(labels.toArray(new String[0]),(d,index)->{
            if(!valid.getAsBoolean())return;depth(a,s,engine,ids.get(index),connection,valid,present,applied);
        }).setNegativeButton("취소",null).create());
    }
    private static void depth(Activity a,AppStore s,String engine,String model,FileAiOperationsUi.Connection connection,BooleanSupplier valid,Consumer<AlertDialog> present,Runnable applied){
        ScopedModels selection=ScopedModels.explorer(s);Reasoning.Profile profile=new TeamRoster(s).profile(engine,model);String[] values=profile.values();String[] labels=WorkBook.CHATGPT.equals(engine)?profile.gptLabels():profile.labels();String[] effort={selection.effort(engine,model)};
        int checked=Math.max(0,Arrays.asList(values).indexOf(effort[0]));
        present.accept(new AlertDialog.Builder(a).setTitle("생각 깊이 · "+model).setSingleChoiceItems(labels,checked,(d,index)->effort[0]=values[index])
            .setNegativeButton("취소",null).setPositiveButton("탐색기에 적용",(d,w)->{
                if(!valid.getAsBoolean())return;
                try{connection.check(s);boolean found=false;JSONArray current=new TeamRoster(s).catalog(engine);for(int i=0;i<current.length();i++){JSONObject item=current.optJSONObject(i);if(item!=null&&model.equals(item.optString("id")))found=true;}if(!found)throw new IllegalStateException();
                    Reasoning.Profile latest=new TeamRoster(s).profile(engine,model);if(!effort[0].isEmpty()&&!latest.efforts.contains(effort[0]))throw new IllegalStateException();
                    selection.chooseProfile(engine,model,effort[0]);applied.run();
                }catch(Exception error){message(a,present,"적용하지 못했어요","연결·모델 목록이 바뀌었거나 설정을 저장하지 못했어요. 다시 열어 선택해 주세요.");}
            }).create());
    }
    private static void message(Activity a,Consumer<AlertDialog> present,String title,String text){present.accept(new AlertDialog.Builder(a).setTitle(title).setMessage(text).setPositiveButton("닫기",null).create());}
}
