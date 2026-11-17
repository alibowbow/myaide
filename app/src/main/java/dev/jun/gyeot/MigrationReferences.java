package dev.jun.gyeot;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Verified-copy reference WAL only. Never copies/deletes user files or edits prose/audit history. */
final class MigrationReferences {
    static final String DIRECTORY = "migration-references-v1";
    private static final List<String> COLLECTIONS = List.of("works", "gallery", "videos");
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final String LEGACY = "Download/들퍼레/";
    private record TargetGuard(MigrationCore core,MigrationCore.VerifiedReceipt receipt) {}
    private static final java.util.concurrent.ConcurrentMap<String,java.util.concurrent.ConcurrentMap<String,TargetGuard>> TARGET_GUARDS = new java.util.concurrent.ConcurrentHashMap<>();
    record Report(int changedReferences, List<String> unresolved, boolean pending, String message) {
        Report { unresolved = List.copyOf(unresolved); }
    }
    record MediaMetadata(String relativePath, String name, long size) {}
    interface MediaLookup {
        MediaMetadata metadata(Uri uri) throws Exception;
        Uri documentUri(Uri media) throws Exception;
        Uri mediaUri(Uri document) throws Exception;
        /** Optional exact OS descriptor identity proof; never a name/size-only alias guess. */
        default boolean matchesManagedSource(Uri alias,String relativePath,long size)throws Exception{return false;}
    }
    interface CommitCheck { void beforeCollection(String collection) throws Exception; }
    private MigrationReferences() {}

    static Report apply(Context context, AppStore store, String migrationId, MigrationCore core,
            List<MigrationCore.VerifiedReceipt> receipts, BooleanSupplier cancelled) throws Exception {
        return apply(context,store,migrationId,core,receipts,cancelled,platform(context),collection -> {});
    }
    /** Test seams change neither receipt construction nor the real AppStore transaction owner. */
    static synchronized Report apply(Context context, AppStore store, String migrationId, MigrationCore core,
            List<MigrationCore.VerifiedReceipt> receipts, BooleanSupplier cancelled, MediaLookup lookup, CommitCheck check) throws Exception {
        File journal = file(context,migrationId); List<MigrationCore.VerifiedReceipt> fresh = verified(core,receipts,cancelled);
        JSONObject transaction;
        if (journal.exists() || new File(journal+".bak").exists()) {
            transaction = read(journal); validate(transaction,migrationId);
            if (!receiptJson(fresh).toString().equals(transaction.getJSONArray("receipts").toString())) throw new IOException("같은 이전 작업의 검증 영수증이 달라 참조 변경을 보류했어요.");
        } else {
            SortedMap<String,String> aliases = new TreeMap<>(); List<String> unresolved = new ArrayList<>(); Map<String,MigrationCore.VerifiedReceipt> byPath = new HashMap<>();
            for (var receipt : fresh) {
                if (byPath.put(receipt.sourceRelativePath(),receipt) != null) throw new IOException("같은 원본 경로의 검증 기록이 겹쳐요.");
                putAlias(aliases,receipt.sourceReference(),receipt.targetReference());
                // Both canonical document spellings identify the exact verified legacy relative path.
                String sourceId = MigrationCore.SOURCE_ROOT+"/"+receipt.sourceRelativePath();
                putAlias(aliases,DocumentsContract.buildDocumentUri(SharedOutputFolder.AUTHORITY,sourceId).toString(),receipt.targetReference());
                putAlias(aliases,DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(SharedOutputFolder.AUTHORITY,MigrationCore.SOURCE_ROOT),sourceId).toString(),receipt.targetReference());
            }
            for (String reference : store.migrationFileReferences()) {
                stopped(cancelled); if (aliases.containsKey(reference)) continue;
                Uri uri = Uri.parse(reference); if (!media(uri)) {
                    if (legacyLocalCandidate(uri)) unresolved.add(reference+" · 지원하지 않는 이전 위치 후보 주소: 원래 참조 유지");
                    continue;
                }
                try {
                    MediaMetadata metadata = lookup.metadata(uri);
                    if (metadata == null) { unresolved.add(reference+" · 원래 위치 확인 불가"); continue; }
                    if (!metadata.relativePath().startsWith(LEGACY)) continue; // Pictures/Movies and every other location are untouched.
                    String path = metadata.relativePath().substring(LEGACY.length())+metadata.name();
                    MigrationCore.VerifiedReceipt receipt = byPath.get(path);
                    if (receipt == null || metadata.size()!=receipt.size() || !(provesDocument(lookup,uri,path) || lookup.matchesManagedSource(uri,path,receipt.size()))) {
                        unresolved.add(reference+" · 실제 원본 문서와의 관계 미확인"); continue;
                    }
                    putAlias(aliases,reference,receipt.targetReference());
                } catch (Exception unavailable) { unresolved.add(reference+" · 실제 원본 주소 확인 불가"); }
            }
            JSONObject evidence = new JSONObject().put("receipts",receiptJson(fresh)).put("aliases",aliasesJson(aliases));
            transaction = new JSONObject().put("version",1).put("migrationId",migrationId).put("receipts",evidence.getJSONArray("receipts"))
                .put("aliases",evidence.getJSONObject("aliases")).put("evidenceSha256",digest(evidence.toString())).put("unresolved",new JSONArray(unresolved))
                .put("done",new JSONArray()).put("state","prepared");
            write(journal,transaction); // Durable exact mapping precedes the first AppStore change.
        }
        Map<String,String> aliases = aliases(transaction); List<String> unresolved = strings(transaction.getJSONArray("unresolved")); int changed = 0; Set<String> installed = new HashSet<>();
        try {
            for (String collection : COLLECTIONS) {
                stopped(cancelled); check.beforeCollection(collection);
                // WAL is not a new verification receipt: source, target, scope and hashes are checked again.
                verified(core,fresh,cancelled);
                for (String target : aliases.values()) requireTarget(context,target);
                registerTargets(context,core,fresh);
                stopped(cancelled); installed.addAll(store.installMigrationAliases(aliases));
                changed += store.applyMigrationReferences(collection,aliases,cancelled);
                stopped(cancelled);
                JSONArray done = transaction.getJSONArray("done"); if (!strings(done).contains(collection)) done.put(collection);
                transaction.put("state","partial"); write(journal,transaction);
            }
            stopped(cancelled);
            for (String target : aliases.values()) if (!targetAvailable(context,target)) throw new IOException("새 파일 식별이 바뀌어 완료 표시를 보류했어요.");
            stopped(cancelled); transaction.put("state","committed"); write(journal,transaction);
            store.installMigrationAliases(aliases);
            return new Report(changed,unresolved,false,"검증된 새 파일로 앱 참조를 연결했어요. 확인하지 못한 주소 "+unresolved.size()+"개는 그대로예요. 기존 파일은 삭제하지 않았어요.");
        } catch (Exception held) {
            store.suspendMigrationAliases(aliases,installed);
            return new Report(changed,unresolved,true,"참조 연결 일부가 보류됐어요: "+String.valueOf(held.getMessage())+" 기존 파일과 이미 연결한 새 파일은 보존돼요. 재개 전 다시 검증해야 해요.");
        }
    }

    /** Explicit retry only. Reloading an incomplete WAL cannot mint a verified receipt or replay a copy. */
    static Report resume(Context context, AppStore store, String migrationId, MigrationCore core, BooleanSupplier cancelled) throws Exception {
        JSONObject transaction=read(file(context,migrationId));validate(transaction,migrationId);List<MigrationCore.VerifiedReceipt> receipts=new ArrayList<>();
        JSONArray rows=transaction.getJSONArray("receipts");for(int i=0;i<rows.length();i++)receipts.add(core.requireVerified(rows.getJSONObject(i).getString("recordId"),cancelled));
        return apply(context,store,migrationId,core,receipts,cancelled);
    }

    /** Committed aliases activate only with a live receipt-bound metadata guard. After process restart, explicit resume is required; persisted new references are not rolled back. */
    static Map<String,String> loadCommittedAliases(Context context) throws Exception {
        File directory = new File(context.getFilesDir(),DIRECTORY); if (!directory.exists()) return Map.of();
        File[] files=directory.listFiles((dir,name)->name.endsWith(".json"));if(files==null||files.length>2000)throw new IOException("이전 참조 기록 목록을 확인하지 못했어요.");
        Arrays.sort(files,Comparator.comparing(File::getName));Map<String,String> result=new TreeMap<>();Map<String,TargetGuard> guards=TARGET_GUARDS.get(context.getFilesDir().getCanonicalPath());
        for(File file:files){String id=file.getName().substring(0,file.getName().length()-5);JSONObject transaction=read(file);validate(transaction,id);
            if(!"committed".equals(transaction.getString("state")))continue;
            // A progress flag cannot mint proof. Require a private core receipt already verified off-thread.
            // Do not read MigrationLedger here: a live copy can hold its lock for a long time.
            JSONArray receipts=transaction.getJSONArray("receipts");for(int i=0;i<receipts.length();i++){JSONObject recorded=receipts.getJSONObject(i);TargetGuard guard=guards==null?null:guards.get(recorded.getString("target"));if(guard==null||!recorded.toString().equals(proof(guard.receipt()).toString()))throw new IOException("참조 연결의 독립 검증 영수증을 다시 확인하기 전까지 자동 주소 연결을 보류해요.");}
            for(var entry:aliases(transaction).entrySet()){if(!targetAvailable(context,entry.getValue()))throw new IOException("새 위치의 파일 식별을 다시 확인하기 전까지 자동 주소 연결은 보류해요.");putAlias(result,entry.getKey(),entry.getValue());}
        }
        return Map.copyOf(result);
    }
    static boolean targetAvailable(Context context,String reference) {try{requireTarget(context,reference);Map<String,TargetGuard> guards=TARGET_GUARDS.get(context.getFilesDir().getCanonicalPath());TargetGuard guard=guards==null?null:guards.get(reference);if(guard==null)return false;guard.core().requireTargetIdentity(guard.receipt());return true;}catch(Exception denied){return false;}}
    private static void registerTargets(Context context,MigrationCore core,List<MigrationCore.VerifiedReceipt> receipts)throws IOException{var guards=TARGET_GUARDS.computeIfAbsent(context.getFilesDir().getCanonicalPath(),key->new java.util.concurrent.ConcurrentHashMap<>());for(var receipt:receipts)guards.put(receipt.targetReference(),new TargetGuard(core,receipt));}
    /** Process-restart seam; no production code clears or invents target-identity guards. */
    static void forgetRuntimeTargets(Context context)throws IOException{TARGET_GUARDS.remove(context.getFilesDir().getCanonicalPath());}
    private static void requireTarget(Context context,String reference)throws IOException{SharedOutputFolder.requireContentAccess(context,Uri.parse(reference));}

    static Set<String> references(String collection,JSONArray array) {
        Set<String> result=new LinkedHashSet<>();visit(collection,array,(object,key)->{Object value=object.opt(key);if(value instanceof String&&!((String)value).isEmpty())result.add((String)value);});return result;
    }
    static int rewrite(String collection,JSONArray array,Map<String,String> aliases) {
        int[] changed={0};visit(collection,array,(object,key)->{Object old=object.opt(key);if(!(old instanceof String)||!aliases.containsKey(old))return;String next=aliases.get(old);if(old.equals(next))return;try{object.put(key,next);changed[0]++;}catch(JSONException invalid){throw new IllegalArgumentException(invalid);}});return changed[0];
    }
    private interface Field {void visit(JSONObject object,String key);}
    private static void visit(String collection,JSONArray rows,Field field) {
        if(!COLLECTIONS.contains(collection))throw new IllegalArgumentException("Unknown reference collection");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            if(collection.equals("works")){JSONArray messages=row.optJSONArray("messages");for(int m=0;messages!=null&&m<messages.length();m++){JSONObject message=messages.optJSONObject(m);JSONArray files=message==null?null:message.optJSONArray("files");for(int f=0;files!=null&&f<files.length();f++){JSONObject item=files.optJSONObject(f);if(item!=null)field.visit(item,"uri");}}}
            else {field.visit(row,"uri");if(collection.equals("videos"))field.visit(row,"sourceUri");}
        }
    }
    private static List<MigrationCore.VerifiedReceipt> verified(MigrationCore core,List<MigrationCore.VerifiedReceipt> receipts,BooleanSupplier cancelled)throws Exception {
        if(receipts==null||receipts.isEmpty()||receipts.size()>2000)throw new IOException("검증된 이전 파일이 1~2,000개 필요해요.");
        List<MigrationCore.VerifiedReceipt> result=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(var receipt:receipts){stopped(cancelled);if(receipt==null||!ids.add(receipt.recordId()))throw new IOException("검증 영수증이 없거나 겹쳐요.");var fresh=core.requireVerified(receipt.recordId(),cancelled);
            if(!proof(receipt).toString().equals(proof(fresh).toString()))throw new IOException("검증 뒤 파일 또는 저장소가 바뀌었어요.");result.add(fresh);}
        result.sort(Comparator.comparing(MigrationCore.VerifiedReceipt::recordId));return List.copyOf(result);
    }
    private static JSONArray receiptJson(List<MigrationCore.VerifiedReceipt> receipts)throws JSONException{JSONArray rows=new JSONArray();for(var receipt:receipts)rows.put(proof(receipt));return rows;}
    private static JSONObject proof(MigrationCore.VerifiedReceipt receipt)throws JSONException{return new JSONObject().put("recordId",receipt.recordId()).put("source",receipt.sourceReference()).put("target",receipt.targetReference()).put("sourcePath",receipt.sourceRelativePath()).put("targetPath",receipt.targetRelativePath()).put("bytes",receipt.size()).put("sha256",receipt.sha256()).put("scope",receipt.scopeIdentity());}
    private static JSONObject aliasesJson(Map<String,String> aliases)throws JSONException{JSONObject object=new JSONObject();for(var entry:new TreeMap<>(aliases).entrySet())object.put(entry.getKey(),entry.getValue());return object;}
    private static Map<String,String> aliases(JSONObject transaction)throws Exception{Map<String,String> result=new TreeMap<>();JSONObject rows=transaction.getJSONObject("aliases");for(Iterator<String> keys=rows.keys();keys.hasNext();){String key=keys.next();Object value=rows.get(key);if(!(value instanceof String))throw new IOException("Invalid alias");putAlias(result,key,(String)value);}return result;}
    private static void putAlias(Map<String,String> aliases,String source,String target)throws IOException{
        if(source==null||target==null||source.isEmpty()||target.isEmpty()||source.length()>8192||target.length()>8192||source.equals(target))throw new IOException("올바르지 않은 이전 주소예요.");
        if(aliases.containsKey(source)&&!aliases.get(source).equals(target))throw new IOException("한 원본 주소에 여러 새 위치가 있어 참조를 바꾸지 않았어요.");aliases.put(source,target);
    }
    /** Reporting only: a recognizable legacy alias is not proof of identity and is never rewritten here. */
    private static boolean legacyLocalCandidate(Uri uri) {
        if ("content".equals(uri.getScheme()) && SharedOutputFolder.AUTHORITY.equals(uri.getAuthority())) {
            try { return DocumentsContract.getDocumentId(uri).startsWith(MigrationCore.SOURCE_ROOT+"/"); }
            catch (RuntimeException malformed) { return false; }
        }
        if (uri.getScheme()==null || "file".equals(uri.getScheme())) {
            String path=uri.getPath(); if(path==null)return false;
            for(String root:List.of("/storage/emulated/0/","/storage/self/primary/","/sdcard/")) if(path.startsWith(root+LEGACY))return true;
        }
        return false;
    }
    private static boolean media(Uri uri){return "content".equals(uri.getScheme())&&"media".equals(uri.getAuthority())&&uri.getQuery()==null&&uri.getFragment()==null;}
    private static boolean provesDocument(MediaLookup lookup,Uri alias,String path)throws Exception {
        String expected=MigrationCore.SOURCE_ROOT+"/"+path;Uri canonical=DocumentsContract.buildDocumentUri(SharedOutputFolder.AUTHORITY,expected);
        Uri treeDocument=DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(SharedOutputFolder.AUTHORITY,MigrationCore.SOURCE_ROOT),expected);
        try{Uri document=lookup.documentUri(alias);if(canonical.equals(document)||treeDocument.equals(document))return true;}catch(Exception unavailable){}
        try{Uri converted=lookup.mediaUri(canonical);return converted!=null&&converted.equals(alias);}catch(Exception unavailable){return false;}
    }
    private static MediaLookup platform(Context context){return new MediaLookup(){
        public MediaMetadata metadata(Uri uri)throws Exception{try(Cursor cursor=context.getContentResolver().query(uri,new String[]{MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.SIZE},null,null,null)){if(cursor==null||!cursor.moveToFirst()||cursor.isNull(0)||cursor.isNull(1)||cursor.isNull(2))return null;MediaMetadata value=new MediaMetadata(cursor.getString(0),cursor.getString(1),cursor.getLong(2));if(cursor.moveToNext())return null;return value;}}
        public Uri documentUri(Uri uri){return MediaStore.getDocumentUri(context,uri);}
        public Uri mediaUri(Uri uri){return MediaStore.getMediaUri(context,uri);}
        public boolean matchesManagedSource(Uri alias,String relativePath,long size)throws Exception{return ManagedMigrationBackend.provesMediaAlias(context,alias,relativePath,size);}
    };}
    private static File file(Context context,String id)throws IOException {if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("잘못된 이전 작업 식별자예요.");File directory=new File(context.getFilesDir().getCanonicalFile(),DIRECTORY);if(java.nio.file.Files.isSymbolicLink(directory.toPath()))throw new IOException("이전 참조 기록의 링크는 사용할 수 없어요.");boolean created=!directory.isDirectory();if(created&&!directory.mkdir())throw new IOException("이전 참조 기록 폴더를 만들지 못했어요.");MigrationLedger.syncDirectory(directory);if(created)MigrationLedger.syncDirectory(directory.getParentFile());File file=new File(directory,id+".json");privateFile(file);return file;}
    private static List<String> strings(JSONArray values)throws Exception{List<String> result=new ArrayList<>();for(int i=0;i<values.length();i++){Object value=values.get(i);if(!(value instanceof String))throw new IOException("Invalid string list");result.add((String)value);}return result;}
    private static void validate(JSONObject value,String id)throws Exception{
        Set<String> keys=new HashSet<>();value.keys().forEachRemaining(keys::add);if(!keys.equals(Set.of("version","migrationId","receipts","aliases","evidenceSha256","unresolved","done","state"))||value.getInt("version")!=1||!id.equals(value.getString("migrationId")))throw new IOException("참조 연결 기록 형식을 확인하지 못했어요.");
        JSONArray receipts=value.getJSONArray("receipts");if(receipts.length()<1||receipts.length()>2000)throw new IOException("Invalid receipt count");Set<String> targets=new HashSet<>();
        for(int i=0;i<receipts.length();i++){JSONObject receipt=receipts.getJSONObject(i);if(receipt.length()!=8||!receipt.getString("recordId").matches("[0-9a-f-]{36}")||!receipt.getString("sha256").matches("[0-9a-f]{64}")||receipt.getLong("bytes")<0)throw new IOException("Invalid recorded receipt");MigrationCore.safePath(receipt.getString("sourcePath"));MigrationCore.safePath(receipt.getString("targetPath"));targets.add(receipt.getString("target"));}
        Map<String,String> aliases=aliases(value);if(aliases.size()>10000||!targets.containsAll(aliases.values()))throw new IOException("Unbound aliases");
        String evidence=new JSONObject().put("receipts",receipts).put("aliases",aliasesJson(aliases)).toString();if(!digest(evidence).equals(value.getString("evidenceSha256")))throw new IOException("참조 연결 증거가 바뀌었어요.");
        List<String> done=strings(value.getJSONArray("done"));if(!COLLECTIONS.containsAll(done)||done.size()!=new HashSet<>(done).size()||!Set.of("prepared","partial","committed").contains(value.getString("state"))||"committed".equals(value.getString("state"))&&!done.containsAll(COLLECTIONS))throw new IOException("Invalid mapping progress");
        if(strings(value.getJSONArray("unresolved")).size()>10000)throw new IOException("Too many unresolved references");
    }
    private static void privateFile(File file)throws IOException{File parent=file.getParentFile();if(java.nio.file.Files.isSymbolicLink(parent.toPath())||!parent.equals(parent.getCanonicalFile()))throw new IOException("참조 기록 폴더가 바뀌었어요.");for(String suffix:List.of("",".new",".bak"))if(java.nio.file.Files.isSymbolicLink(new File(file+suffix).toPath()))throw new IOException("참조 기록 파일의 링크는 사용할 수 없어요.");}
    private static JSONObject read(File file)throws Exception{privateFile(file);try(InputStream in=new AtomicFile(file).openRead()){byte[] bytes=in.readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new IOException("참조 기록이 너무 커요.");JSONTokener token=new JSONTokener(new String(bytes,StandardCharsets.UTF_8));JSONObject value=new JSONObject(token);if(token.nextClean()!=0)throw new IOException("Trailing reference data");return value;}}
    private static void write(File file,JSONObject value)throws Exception{privateFile(file);byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IOException("참조 기록 저장 한도에 도달했어요.");AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;try{out=atomic.startWrite();out.write(bytes);out.flush();out.getFD().sync();atomic.finishWrite(out);out=null;MigrationLedger.syncDirectory(file.getParentFile());if(!value.toString().equals(read(file).toString()))throw new IOException("참조 기록 저장을 확인하지 못했어요.");}catch(Exception failure){if(out!=null)atomic.failWrite(out);throw failure;}}
    private static String digest(String value)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder text=new StringBuilder();for(byte b:hash)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    private static void stopped(BooleanSupplier cancelled)throws InterruptedIOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("참조 연결을 취소했어요. 원본은 보존해요.");}
}
