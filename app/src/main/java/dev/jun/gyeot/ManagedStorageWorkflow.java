package dev.jun.gyeot;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** Explicit setup/copy workflow; no background source watcher and no source deletion path. */
final class ManagedStorageWorkflow {
    private ManagedStorageWorkflow(){}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("취소했어요.");}
    static String prepare(Context context,BooleanSupplier cancelled)throws Exception {
        check(cancelled);ManagedSharedStorage.ensureRoot(context);check(cancelled);SharedOutputFolder.activateManaged(context);check(cancelled);
        try(ManagedMigrationBackend backend=new ManagedMigrationBackend(context)){
            var inventory=backend.inventory(cancelled);check(cancelled);
            return "실제 내장 공유 저장소/들퍼레 폴더를 확인했어요. 새 파일은 이 위치에 저장해요.\n\n"+summary(inventory)+"\n아직 이전 파일 내용을 읽거나 복사하지 않았어요. 아래 검증 복사를 누르면 복사 후 앱 참조를 연결해요.";
        }
    }
    private static String summary(ManagedMigrationBackend.Inventory inventory){
        if(inventory.sourceMissing())return "기존 Download/들퍼레 폴더는 없어요. 이전할 원본이 발견되지 않았어요.";
        return "이전 목록: 파일 "+inventory.paths().size()+"개 · 폴더 "+inventory.directories()+"개 · "+inventory.bytes()+"바이트\n"+(inventory.complete()?"이번에 확인된 목록이에요.":"일부 목록만 확인했어요. 제한·오류 항목은 미완료로 남아요.")+warnings(inventory.warnings());
    }
    private static String warnings(List<String> warnings){if(warnings.isEmpty())return "";StringBuilder text=new StringBuilder("\n확인 필요:");for(int i=0;i<Math.min(12,warnings.size());i++)text.append("\n• ").append(warnings.get(i));if(warnings.size()>12)text.append("\n… ").append(warnings.size()-12).append("개 더 있음");return text.toString();}
    static String migrate(Context context,BooleanSupplier cancelled,Consumer<String> progress)throws Exception {
        check(cancelled);Uri target=ManagedSharedStorage.tree(context);SharedOutputFolder.activateManaged(context);
        try(ManagedMigrationBackend backend=new ManagedMigrationBackend(context)){
            var inventory=backend.inventory(cancelled);if(inventory.sourceMissing())return "새 들퍼레 폴더가 준비됐어요. 이전할 Download/들퍼레 폴더는 없어요. 원본 삭제는 실행하지 않았어요.";
            MigrationCore core=new MigrationCore(new MigrationLedger(context),backend);List<MigrationCore.VerifiedReceipt> receipts=new ArrayList<>();List<String> held=new ArrayList<>();int index=0;
            for(String path:inventory.paths()){
                check(cancelled);progress.accept("검증 복사 "+(++index)+" / "+inventory.paths().size()+"\n"+path+"\n원본 보존 · 불확실한 생성은 재작성하지 않아요.");
                try{receipts.add(core.runFile(path,cancelled));}catch(InterruptedIOException stop){throw stop;}catch(Exception failure){held.add(path+" · "+String.valueOf(failure.getMessage()));backend.requireAccess();}
            }
            check(cancelled);int changed=0,unresolved=0;boolean refPending=false;
            if(!receipts.isEmpty()){
                progress.accept("새 파일을 다시 검증하고 앱 참조를 연결 중…\n원본은 삭제하지 않아요.");
                List<String> ids=new ArrayList<>();for(var receipt:receipts)ids.add(receipt.recordId());Collections.sort(ids);
                String id="download-root-"+UUID.nameUUIDFromBytes(String.join("\n",ids).getBytes(StandardCharsets.UTF_8));
                var report=MigrationReferences.apply(context,((GyeotApp)context.getApplicationContext()).store(),id,core,receipts,cancelled);
                changed=report.changedReferences();unresolved=report.unresolved().size();refPending=report.pending();if(report.pending())held.add(report.message());
            }
            check(cancelled);String result="검증된 새 사본 "+receipts.size()+"개 / 이번 목록 "+inventory.paths().size()+"개\n앱 참조 변경 "+changed+"개 · 확인 못한 주소 "+unresolved+"개\n"+
                (held.isEmpty()&&inventory.complete()&&!refPending&&unresolved==0?"이번 목록의 파일 검증 복사·참조 연결을 마쳤어요.":"일부 미완료예요. 실패·제한·불확실한 항목은 기존 파일과 참조를 유지해요.")+"\n원본 Download/들퍼레는 그대로 보존했어요. 빈 폴더만 있는 항목은 파일 복사 대상이 아니에요. Pictures/Movies의 옛 파일은 건드리지 않았어요."+warnings(inventory.warnings())+warnings(held);
            if(!context.getSharedPreferences(StorageSetupActivity.PREFS,0).edit().putString("last-result",result).commit())throw new IOException("검증은 수행했지만 결과 요약 저장을 확인하지 못했어요. 파일별 이전 기록을 재확인해 주세요.");
            // Navigation preference is separate from content references: only a real accessible target wins.
            check(cancelled);SharedOutputFolder.requireGrant(context,target,true);
            context.getSharedPreferences("FileExplorerActivity",0).edit().putString("tree",target.toString()).putString("directoryTree",target.toString()).putString("directoryPath","[]").commit();
            return result;
        }
    }
}
