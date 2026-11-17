package dev.jun.gyeot;

import android.content.Context;
import android.system.Os;
import android.system.ErrnoException;
import android.system.StructStat;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Explicit, one-file-at-a-time reversible trash, limited to the managed shared root.
 * No permanent deletion or purge. Native rename must be atomic NOREPLACE. */
final class DuplicateTrash {
    static final String FOLDER=".들퍼레-휴지통";
    static final long LIMIT=32L*1024*1024;
    private static final Object LOCK=new Object();
    interface AccessFactory { ManagedSharedStorage.Access open() throws IOException; }
    interface Move { void run(int sourceParent,String source,int destinationParent,String destination)throws IOException; }
    record Snapshot(String path,String identity,long bytes,long modified,String hash) {}
    record Pair(Snapshot remove,Snapshot keep) {}
    record Receipt(String id,String source,String payload,String state,String message) {}
    final AccessFactory factory;final Move move;final AtomicFile ledger;
    DuplicateTrash(Context context,FileExplorerRepository repo)throws IOException {
        if(!supported(repo))throw new IOException("휴지통은 앱에 연결된 내장 공유 저장소/들퍼레에서만 지원해요. 다른 위치는 검색만 가능해요.");
        repo.requireContentAccess(repo.document(repo.root));
        Context app=context.getApplicationContext();
        factory=()->{SharedOutputFolder.requireContentAccess(app,ManagedSharedStorage.document(""));return ManagedSharedStorage.access(app);};
        move=TrashNative::moveNoReplace;
        ledger=new AtomicFile(new File(app.getFilesDir(),"duplicate-trash.json"));
    }
    DuplicateTrash(AccessFactory factory,Move move,File ledger){this.factory=factory;this.move=move;this.ledger=new AtomicFile(ledger);}
    static boolean supported(FileExplorerRepository repo){return repo!=null&&!repo.publicMetadata()&&ManagedSharedStorage.treeUri().equals(repo.tree)&&ManagedSharedStorage.ROOT_ID.equals(repo.root);}
    static boolean excluded(String id){return id!=null&&(id.equals(ManagedSharedStorage.ROOT_ID+"/"+FOLDER)||id.startsWith(ManagedSharedStorage.ROOT_ID+"/"+FOLDER+"/"));}
    static String path(String id)throws IOException {String rel=ManagedSharedStorage.relative(id);if(rel.isEmpty()||excluded(id))throw new IOException("루트나 휴지통 항목은 중복 삭제 대상으로 사용할 수 없어요.");return ManagedSharedStorage.ROOT_NAME+"/"+rel;}
    Pair prepare(FileExplorerRepository.Entry remove,FileExplorerRepository.Entry keep,BooleanSupplier cancelled)throws Exception {
        if(remove.directory()||keep.directory())throw new IOException("일반 파일만 비교할 수 있어요.");
        return preparePaths(path(remove.id()),path(keep.id()),cancelled);
    }
    Pair prepareCandidates(FileExplorerRepository.Entry remove,List<FileExplorerRepository.Entry> candidates,BooleanSupplier cancelled)throws Exception {
        synchronized(LOCK){if(remove.directory())throw new IOException("일반 파일만 처리해요.");long deadline=System.nanoTime()+60_000_000_000L;BooleanSupplier stop=()->cancelled.getAsBoolean()||System.nanoTime()>=deadline;
            try(var a=factory.open()){FileExplorerRepository.ReadBudget budget=new FileExplorerRepository.ReadBudget(256L*1024*1024);Snapshot x=snapshot(a,path(remove.id()),stop,budget);
                for(var keep:candidates){check(stop);if(keep.directory()||keep.id().equals(remove.id())||keep.size()!=x.bytes()||keep.size()<0||budget.remaining()<=0)continue;try{Snapshot y=snapshot(a,path(keep.id()),stop,budget);pair(x,y);return new Pair(x,y);}catch(IOException changed){check(stop);}}
            }throw new IOException("현재 남길 동일 파일을 확인하지 못했어요. 최소 한 개의 별도 원본이 필요하며 검증은 전체 256MB·60초로 제한돼요. 다시 검색해 주세요.");}
    }
    Pair preparePaths(String remove,String keep,BooleanSupplier cancelled)throws Exception {
        synchronized(LOCK){try(var a=factory.open()){Snapshot x=snapshot(a,remove,cancelled),y=snapshot(a,keep,cancelled);pair(x,y);return new Pair(x,y);}}
    }
    private static void pair(Snapshot x,Snapshot y)throws IOException {
        if(x.path().equals(y.path())||x.identity().equals(y.identity()))throw new IOException("별도 위치의 다른 원본을 하나 이상 남겨야 해요.");
        if(x.bytes()!=y.bytes()||!x.hash().equals(y.hash()))throw new IOException("내용이 같지 않아요. 삭제하지 않았어요.");
    }
    static void allowed(String path)throws IOException {
        ManagedSharedStorage.safe(path);
        if(!path.startsWith(ManagedSharedStorage.ROOT_NAME+"/")||path.equals(ManagedSharedStorage.ROOT_NAME+"/"+FOLDER)||path.startsWith(ManagedSharedStorage.ROOT_NAME+"/"+FOLDER+"/"))throw new IOException("들퍼레의 일반 파일만 처리해요.");
    }
    private Snapshot snapshot(ManagedSharedStorage.Access a,String path,BooleanSupplier cancelled)throws Exception {
        return snapshot(a,path,cancelled,new FileExplorerRepository.ReadBudget(Long.MAX_VALUE));
    }
    private Snapshot snapshot(ManagedSharedStorage.Access a,String path,BooleanSupplier cancelled,FileExplorerRepository.ReadBudget budget)throws Exception {
        allowed(path);try(var n=a.node(path,false)){return inspect(n,path,cancelled,budget);}
    }
    private Snapshot inspect(ManagedSharedStorage.Node n,String path,BooleanSupplier cancelled)throws Exception {
        return inspect(n,path,cancelled,new FileExplorerRepository.ReadBudget(Long.MAX_VALUE));
    }
    private Snapshot inspect(ManagedSharedStorage.Node n,String path,BooleanSupplier cancelled,FileExplorerRepository.ReadBudget budget)throws Exception {
        n.requireFile();StructStat before=n.stat();if(before.st_size<0||before.st_size>LIMIT)throw new IOException("휴지통 검증은 파일당 32MB 이하만 지원해요.");
        String hash=hash(n,cancelled,budget);StructStat after=n.stat();same(before,after);return new Snapshot(path,ManagedSharedStorage.key(after),after.st_size,after.st_mtime,hash);
    }
    private static void same(StructStat a,StructStat b)throws IOException {ManagedSharedStorage.sameIdentity(a,b);if(a.st_size!=b.st_size||a.st_mtime!=b.st_mtime||b.st_nlink!=1)throw new IOException("검증 중 파일이 변경됐어요.");}
    private static void unchanged(Snapshot before,Snapshot now)throws IOException {if(!before.equals(now))throw new IOException("검토 후 파일이나 내용이 바뀌었어요. 다시 비교해 주세요.");}
    private static void check(BooleanSupplier cancelled)throws IOException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("취소했어요. 완료된 이동은 휴지통 기록에서 확인해 주세요.");}
    private static String hash(ManagedSharedStorage.Node n,BooleanSupplier cancelled,FileExplorerRepository.ReadBudget budget)throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");long at=0;byte[] b=new byte[65536];long deadline=System.nanoTime()+30_000_000_000L;
        for(;;){check(cancelled);n.requireFile();if(System.nanoTime()>deadline)throw new IOException("내용 검증 시간이 초과됐어요.");if(budget.remaining()<=0)throw new IOException("전체 내용 검증 한도 256MB를 넘었어요.");int count;try{count=Os.pread(n.last().getFileDescriptor(),b,0,(int)Math.min(budget.remaining(),Math.min(b.length,LIMIT-at+1)),at);}catch(ErrnoException e){throw ManagedSharedStorage.io(e);}n.requireFile();if(count==0)break;budget.consume(count);at+=count;if(at>LIMIT)throw new IOException("파일당 32MB 한도를 넘었어요.");d.update(b,0,count);}return FileExplorerRepository.hex(d.digest());
    }
    Receipt trash(Pair approved,BooleanSupplier cancelled)throws Exception {
        synchronized(LOCK){allowed(approved.remove().path());allowed(approved.keep().path());JSONArray rows=read();for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(r.optString("source").equals(approved.remove().path())&&(r.optString("state").contains("uncertain")||r.optString("state").endsWith("pending")))throw new IOException("이전 이동 결과가 불확실해요. 휴지통 기록을 확인하고 자동 재시도하지 마세요.");}
            if(rows.length()>=1000)throw new IOException("휴지통 기록이 1,000개에 도달했어요. 자동 삭제하지 않아요.");
            try(var a=factory.open();var source=a.node(approved.remove().path(),false);var keeper=a.node(approved.keep().path(),false)){
                Snapshot current=inspect(source,approved.remove().path(),cancelled),kept=inspect(keeper,approved.keep().path(),cancelled);unchanged(approved.remove(),current);unchanged(approved.keep(),kept);pair(current,kept);
                String id=UUID.randomUUID().toString(),folder=ManagedSharedStorage.ROOT_NAME+"/"+FOLDER,slot=folder+"/"+id,payload=slot+"/"+ManagedSharedStorage.name(current.path());
                try(var trash=a.directory(folder,true);var destination=a.mkdirExclusive(slot);var parent=a.directory(ManagedSharedStorage.parent(current.path()),false)){
                    check(cancelled);source.requireFile();keeper.requireFile();parent.check();destination.check();
                    JSONObject record=new JSONObject().put("id",id).put("source",current.path()).put("payload",payload).put("identity",current.identity()).put("bytes",current.bytes()).put("hash",current.hash()).put("keeper",kept.path()).put("state","move_pending").put("created",System.currentTimeMillis());rows.put(record);write(rows);
                    boolean attempted=false;
                    try {
                        check(cancelled);source.requireFile();keeper.requireFile();parent.check();destination.check();
                        attempted=true;move.run(parent.last().getFd(),ManagedSharedStorage.name(current.path()),destination.last().getFd(),ManagedSharedStorage.name(payload));
                        // Reconcile even after UI cancellation. Never retry an uncertain mutation.
                        parent.sync();destination.sync();
                        try(var moved=a.node(payload,false)){Snapshot verified=inspect(moved,payload,()->false);if(!verified.identity().equals(current.identity())||verified.bytes()!=current.bytes()||!verified.hash().equals(current.hash()))throw new IOException("이동 순간 파일이 바뀌었어요. 이동된 항목은 보존했어요.");}
                        unchanged(kept,inspect(keeper,kept.path(),()->false));
                        record.put("state","trashed");write(rows);
                        return receipt(record,"휴지통으로 옮겼어요. 같은 내용의 원본 한 개 이상은 남겼어요. 자동 비우기는 없으며 휴지통도 저장 공간을 사용해요.");
                    }catch(Exception error){record.put("state",attempted?"move_uncertain":"cancelled_before_move");try{write(rows);}catch(Exception ignored){}throw new IOException((attempted?"이동 결과 확인이 필요해요. 다시 누르지 말고 휴지통 기록을 확인해 주세요. 보존 위치: ":"이동 전에 중단했어요. 원본은 그대로예요. 예약 위치: ")+payload+"\n"+error.getMessage(),error);}
                }
            }
        }
    }
    List<Receipt> list()throws Exception {synchronized(LOCK){try(var a=factory.open()){a.check();}JSONArray rows=read();List<Receipt> out=new ArrayList<>();for(int i=rows.length()-1;i>=0;i--){JSONObject r=rows.getJSONObject(i);out.add(receipt(r,r.optString("state").endsWith("pending")?"중단된 이동 · 상태 확인 필요":""));}return List.copyOf(out);}}
    Receipt restore(String id,BooleanSupplier cancelled)throws Exception {
        synchronized(LOCK){JSONArray rows=read();JSONObject row=null;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getString("id").equals(id))row=rows.getJSONObject(i);if(row==null||!row.optString("state").equals("trashed"))throw new IOException("확인된 휴지통 항목만 복원할 수 있어요. 불확실한 기록은 자동 재시도하지 않아요.");
            String original=row.getString("source"),payload=row.getString("payload");allowed(original);if(!payload.equals(ManagedSharedStorage.ROOT_NAME+"/"+FOLDER+"/"+id+"/"+ManagedSharedStorage.name(original)))throw new IOException("휴지통 기록 경로가 올바르지 않아요.");
            try(var a=factory.open();var source=a.node(payload,false)){
                Snapshot existing=inspect(source,payload,cancelled);if(!existing.identity().equals(row.getString("identity"))||!existing.hash().equals(row.getString("hash"))||existing.bytes()!=row.getLong("bytes"))throw new IOException("휴지통 내용이 바뀌었어요. 자동 복원하지 않아요.");
                check(cancelled);
                // Exclusive creation is atomic: an existing or raced-in destination is never replaced.
                try(var output=a.create(original)){
                    try{row.put("state","restore_pending");write(rows);byte[] b=new byte[65536];long at=0;while(at<existing.bytes()){check(cancelled);source.requireFile();output.check();int count;try{count=Os.pread(source.last().getFileDescriptor(),b,0,(int)Math.min(b.length,existing.bytes()-at),at);}catch(ErrnoException e){throw ManagedSharedStorage.io(e);}if(count<=0)throw new IOException("휴지통 파일이 짧아졌어요.");int written=0;while(written<count){check(cancelled);source.requireFile();output.check();int n;try{n=Os.pwrite(output.fd.getFileDescriptor(),b,written,count-written,at+written);}catch(ErrnoException e){throw ManagedSharedStorage.io(e);}if(n<=0)throw new IOException("복원 쓰기를 완료하지 못했어요.");written+=n;}at+=count;}output.sync();unchanged(existing,inspect(source,payload,cancelled));try(var restored=a.node(original,false)){Snapshot verified=inspect(restored,original,cancelled);if(verified.bytes()!=existing.bytes()||!verified.hash().equals(existing.hash()))throw new IOException("복원 검증에 실패했어요.");}row.put("state","restored_copy");write(rows);return receipt(row,"원래 위치에 복원 사본을 만들었어요. 휴지통 원본은 계속 보존하며 자동 삭제하지 않아요.");}
                    catch(Exception e){row.put("state","restore_uncertain");try{write(rows);}catch(Exception ignored){}throw new IOException("복원 완료를 확인하지 못했어요. 휴지통 원본은 보존했고 원래 위치에 미완성 사본이 있을 수 있어요. 덮어쓰거나 재시도하지 말고 확인해 주세요.",e);}
                }
            }
        }
    }
    private Receipt receipt(JSONObject r,String message){return new Receipt(r.optString("id"),r.optString("source"),r.optString("payload"),r.optString("state"),message);}
    private JSONArray read()throws Exception {if(!ledger.getBaseFile().exists()&&!new File(ledger.getBaseFile().getPath()+".bak").exists())return new JSONArray();try(InputStream in=ledger.openRead()){byte[] b=in.readNBytes(1024*1024+1);if(b.length>1024*1024)throw new IOException("휴지통 기록 한도를 넘었어요.");return new JSONArray(new String(b,StandardCharsets.UTF_8));}}
    private void write(JSONArray rows)throws Exception {byte[] b=rows.toString().getBytes(StandardCharsets.UTF_8);if(b.length>1024*1024)throw new IOException("휴지통 기록 한도를 넘었어요.");FileOutputStream out=null;try{out=ledger.startWrite();out.write(b);ledger.finishWrite(out);out=null;try(InputStream in=ledger.openRead()){if(!java.util.Arrays.equals(b,in.readNBytes(b.length+1)))throw new IOException("휴지통 기록 저장을 확인하지 못했어요.");}}catch(Exception e){if(out!=null)ledger.failWrite(out);throw e;}}
}
