package dev.jun.gyeot;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;
import dev.jun.gyeot.core.FileOrganizationPlan;

/** All identifiers originate from a direct traversal of the granted tree, never from the model. */
final class FileExplorerRepository {
    static final String DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    record Entry(String id, String name, String mime, long size, long modified, Uri uri, String parent) { boolean directory() { return DIR.equals(mime); } }
    final Context context; final ContentResolver resolver; final Uri tree; final String root;
    final Map<String,Entry> known = new HashMap<>();
    private final PublicFolderMetadata publicSource;
    FileExplorerRepository(Context context, Uri tree) {
        this.context = context; this.resolver = context.getContentResolver(); this.tree = tree;
        root = DocumentsContract.getTreeDocumentId(tree); publicSource=null;
    }
    FileExplorerRepository(Context context, PublicFolderMetadata publicSource) {
        this.context=context;this.resolver=context.getContentResolver();this.publicSource=publicSource;this.tree=publicSource.tree;this.root=publicSource.root;
    }
    static List<FileExplorerRepository> publicLocations(Context context) throws IOException {
        android.os.storage.StorageManager manager=context.getSystemService(android.os.storage.StorageManager.class);
        if(manager==null)throw new IOException("Android 공유 저장소 목록을 가져오지 못했어요.");
        return publicLocations(context,manager,java.nio.file.Path.of("/storage"));
    }
    /** Synthetic-filesystem seam. Production always uses the fixed /storage namespace above. */
    static List<FileExplorerRepository> publicLocations(Context context,android.os.storage.StorageManager manager,java.nio.file.Path storage)throws IOException {
        storage=storage.toAbsolutePath().normalize();
        List<FileExplorerRepository> result=new ArrayList<>();Set<String> seen=new HashSet<>(),identities=new HashSet<>();
        for(android.os.storage.StorageVolume volume:manager.getStorageVolumes()) {
            String state=volume.getState();if(!android.os.Environment.MEDIA_MOUNTED.equals(state)&&!android.os.Environment.MEDIA_MOUNTED_READ_ONLY.equals(state))continue;
            File directory=volume.getDirectory();if(directory==null)continue;
            java.nio.file.Path actual;try{actual=directory.getCanonicalFile().toPath();}catch(IOException denied){continue;}
            if(!actual.startsWith(storage)||actual.equals(storage)||!seen.add(actual.toString()))continue;
            String id=publicVolumeId(volume,actual);if(!identities.add(id))continue;
            String label=volume.isPrimary()?"내장 공유 저장소":volume.getDescription(context);if(label==null||label.isBlank())label="공유 저장소";
            final String expectedId=id;final java.nio.file.Path expectedPath=actual;
            PublicFolderMetadata.RootCheck check=()->{
                for(android.os.storage.StorageVolume current:manager.getStorageVolumes()){
                    String mounted=current.getState();File currentDirectory=current.getDirectory();
                    if((android.os.Environment.MEDIA_MOUNTED.equals(mounted)||android.os.Environment.MEDIA_MOUNTED_READ_ONLY.equals(mounted))&&currentDirectory!=null){
                        java.nio.file.Path path=currentDirectory.getCanonicalFile().toPath();
                        if(expectedPath.equals(path)&&expectedId.equals(publicVolumeId(current,path)))return;
                    }
                }
                throw new IOException("저장소가 분리되거나 바뀌었어요. 위치 목록에서 다시 선택해 주세요.");
            };
            try{result.add(new FileExplorerRepository(context,new PublicFolderMetadata(actual,id,label,check)));}catch(IOException unavailable){/* OS listed a volume whose directory metadata is unavailable. No fabricated folder rows. */}
        }
        return List.copyOf(result);
    }
    private static String publicVolumeId(android.os.storage.StorageVolume volume,java.nio.file.Path path){
        String id=volume.isPrimary()?"primary":volume.getUuid();
        return id!=null&&id.matches("[A-Za-z0-9_-]+")?id:"volume-"+UUID.nameUUIDFromBytes(path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static boolean isPublicTree(Uri tree){return PublicFolderMetadata.isTree(tree);}
    boolean publicMetadata(){return publicSource!=null;}
    String locationLabel(){return publicSource!=null?publicSource.label:DocumentsContract.getTreeDocumentId(tree);}
    String metadataNotice(){return publicSource!=null?publicSource.notice():"";}
    boolean metadataPartial(){return publicSource!=null&&publicSource.partial();}
    void requireMetadataAccess()throws IOException{requireMetadataAccess(root);}
    void requireMetadataAccess(String directory)throws IOException{
        if(!root.equals(directory)&&(!known.containsKey(directory)||!known.get(directory).directory()))throw new IOException("확인하지 않은 폴더에는 접근하지 않아요.");
        if(publicSource!=null)publicSource.requireMetadataAccess(directory);else SharedOutputFolder.requireGrant(context,tree,false);
    }
    boolean contentAllowed() { return publicSource==null&&SharedOutputFolder.canAccess(context, document(root)); }
    void requireContentAccess(Uri uri) throws IOException {
        if(publicSource!=null)throw new IOException("공개 폴더 탐색은 목록 정보만 사용해요. 파일 내용 읽기·쓰기는 따로 허용한 들퍼레 폴더에서만 가능해요.");
        SharedOutputFolder.requireContentAccess(context,uri);
    }
    Uri document(String id) { return publicSource!=null?publicSource.document(id):DocumentsContract.buildDocumentUriUsingTree(tree, id); }
    List<Entry> list(String parent, BooleanSupplier cancelled) throws Exception {
        if (!root.equals(parent) && (!known.containsKey(parent) || !known.get(parent).directory())) throw new IOException("선택한 폴더 밖에는 접근하지 않아요.");
        if(publicSource!=null){List<Entry> entries=publicSource.list(parent,cancelled);for(Entry entry:entries)known.put(entry.id(),entry);return entries;}
        List<Entry> result = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        String[] columns = {"document_id", "_display_name", "mime_type", "_size", "last_modified"};
        try (Cursor c = resolver.query(children, columns, null, null, null)) {
            if (c == null) throw new IOException("폴더 목록을 읽지 못했어요.");
            while (c.moveToNext()) {
                check(cancelled); if (result.size() >= 2000) throw new IOException("폴더에 2,000개가 넘어요. 더 작은 폴더를 선택해 주세요.");
                Entry e=readEntry(c,parent);String id=e.id();
                known.put(id,e); result.add(e);
            }
        }
        result.sort(Comparator.comparing((Entry e)->!e.directory()).thenComparing(Entry::name,String.CASE_INSENSITIVE_ORDER)); return result;
    }
    private Entry readEntry(Cursor c,String parent)throws IOException {
        String id=c.getString(0);
        if(id==null||id.equals(root)||id.equals(parent))throw new IOException("잘못된 폴더 응답이에요.");
        if("com.android.externalstorage.documents".equals(tree.getAuthority()) || ManagedSharedStorage.AUTHORITY.equals(tree.getAuthority())){
            if(!(root.endsWith(":")?id.startsWith(root)&&id.length()>root.length():id.startsWith(root+"/")))throw new IOException("선택한 폴더 밖의 목록 응답이에요.");
            String relative=id.substring(root.length()+(root.endsWith(":")?0:1));
            for(String part:relative.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.indexOf('\\')>=0||part.chars().anyMatch(Character::isISOControl))throw new IOException("안전하지 않은 파일 식별자예요.");
            String prefix=parent.endsWith(":")?parent:parent+"/";
            if(!id.startsWith(prefix)||id.substring(prefix.length()).contains("/")||!id.substring(prefix.length()).equals(c.getString(1)))throw new IOException("실제 하위 경로와 표시 이름이 일치하지 않는 폴더 응답이에요.");
        }
        return new Entry(id,c.getString(1),c.getString(2),c.isNull(3)?-1:c.getLong(3),c.isNull(4)?0:c.getLong(4),document(id),parent);
    }
    record Page(List<Entry> entries,boolean more){}
    static Comparator<Entry> browseOrder(int sort){Comparator<Entry> order=switch(sort){case 1->Comparator.comparingLong(Entry::modified).reversed();case 2->Comparator.comparingLong(Entry::size).reversed();case 3->Comparator.comparing(Entry::mime);case 4->Comparator.comparingLong(e->e.modified()>0?e.modified():Long.MAX_VALUE);default->Comparator.comparing(Entry::name,String.CASE_INSENSITIVE_ORDER);};return Comparator.comparing((Entry e)->!e.directory()).thenComparing(order).thenComparing(Entry::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::id);}
    /** Browse only: bounded keyset page, independent of the 2,000-item AI/search scan cap.
     * Providers may ignore sort arguments, so scan metadata and retain only the smallest page+1
     * keys after the prior anchor. Memory and rendered Views stay bounded; no content is opened. */
    Page browsePage(String parent,Entry after,int limit,int sort,BooleanSupplier cancelled)throws Exception{
        if(limit<1||limit>200)throw new IOException("잘못된 페이지 크기예요.");
        if(!root.equals(parent)&&(!known.containsKey(parent)||!known.get(parent).directory()))throw new IOException("선택한 폴더 밖에는 접근하지 않아요.");
        if(after!=null&&!parent.equals(after.parent()))throw new IOException("다른 폴더의 페이지는 열지 않아요.");
        if(publicSource!=null){Page page=publicSource.browse(parent,after,limit,sort,cancelled);for(Entry entry:page.entries())known.put(entry.id(),entry);return page;}
        Comparator<Entry> order=browseOrder(sort);PriorityQueue<Entry> page=new PriorityQueue<>(limit+1,order.reversed());Set<String> retained=new HashSet<>();long deadline=System.nanoTime()+30_000_000_000L;
        try(Cursor c=resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent),new String[]{"document_id","_display_name","mime_type","_size","last_modified"},null,null,null)){
            if(c==null)throw new IOException("폴더 목록을 읽지 못했어요.");
            while(c.moveToNext()){check(cancelled);if(System.nanoTime()>deadline)throw new IOException("목록 응답이 오래 걸려 중단했어요. 현재 목록은 유지해요.");Entry e=readEntry(c,parent);if(after!=null&&order.compare(e,after)<=0||retained.contains(e.id()))continue;
                if(page.size()<limit+1){page.add(e);retained.add(e.id());}else if(order.compare(e,page.peek())<0){retained.remove(page.remove().id());page.add(e);retained.add(e.id());}
            }
        }
        List<Entry> result=new ArrayList<>(page);result.sort(order);boolean more=result.size()>limit;if(more)result.remove(result.size()-1);for(Entry e:result)known.put(e.id(),e);return new Page(List.copyOf(result),more);
    }
    /** Restored IDs are resolved through actual parent metadata, never arbitrary document URIs. */
    Entry findChildDirectory(String parent,String wanted,BooleanSupplier cancelled)throws Exception{
        if(!root.equals(parent)&&(!known.containsKey(parent)||!known.get(parent).directory()))throw new IOException("선택한 폴더 밖에는 접근하지 않아요.");
        if(publicSource!=null){Entry found=publicSource.findChild(parent,wanted,cancelled);if(found!=null)known.put(found.id(),found);return found;}
        try(Cursor c=resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent),new String[]{"document_id","_display_name","mime_type","_size","last_modified"},null,null,null)){
            if(c==null)throw new IOException("폴더 목록을 읽지 못했어요.");while(c.moveToNext()){check(cancelled);Entry e=readEntry(c,parent);if(e.id().equals(wanted)&&e.directory()){known.put(e.id(),e);return e;}}
        }return null;
    }
    Entry fresh(Entry expected, BooleanSupplier cancelled) throws Exception {
        for (Entry e : list(expected.parent(),cancelled)) if (e.id().equals(expected.id())) {
            if (!e.name().equals(expected.name()) || e.size()!=expected.size() || e.modified()!=expected.modified() || !e.mime().equals(expected.mime())) throw new IOException("파일이 변경되어 중단했어요: " + expected.name());
            return e;
        }
        throw new IOException("파일이 사라져 중단했어요: " + expected.name());
    }
    static void check(BooleanSupplier cancelled) throws InterruptedIOException { if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("취소했어요. 이미 만든 복사본은 보존했어요."); }
    synchronized void journal(JSONObject record) throws Exception {
        File target = new File(context.getFilesDir(),"file-explorer-journal.jsonl");
        try (FileOutputStream out = new FileOutputStream(target,true)) { out.write((record.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.getFD().sync(); }
    }
    String copy(Entry expected, String folder, BooleanSupplier cancelled) throws Exception {
        if (!FileOrganizationPlan.FOLDERS.contains(folder)) throw new IOException("허용되지 않은 폴더예요.");
        return copyInternal(expected,this,null,folder,cancelled);
    }
    /** Manual destination IDs originate only from the target's granted-tree traversal. */
    String copyTo(Entry expected, FileExplorerRepository target, String destinationId, BooleanSupplier cancelled) throws Exception {
        target.list(destinationId,cancelled); // Reject IDs not reached in this granted tree.
        return copyInternal(expected,target,destinationId,null,cancelled);
    }
    private String copyInternal(Entry expected, FileExplorerRepository target, String destinationId, String category, BooleanSupplier cancelled) throws Exception {
        String folder=category!=null?category:destinationId.equals(target.root)?target.root:target.known.get(destinationId).name();
        FileOrganizationPlan.safeName(expected.name()); if (expected.directory()) throw new IOException("파일만 복사할 수 있어요.");
        requireContentAccess(expected.uri()); target.requireContentAccess(target.document(target.root));
        fresh(expected,cancelled); check(cancelled);
        Entry destination = null;
        if(destinationId!=null)destination=new Entry(destinationId,folder,DIR,0,0,target.document(destinationId),"");
        else for (Entry e : target.list(target.root,cancelled)) if (e.name().equals(folder)) { if (!e.directory()) throw new IOException("같은 이름의 파일이 있어요: "+folder); destination=e; }
        String operation = UUID.randomUUID().toString();
        journal(new JSONObject().put("operation",operation).put("source",expected.uri().toString()).put("folder",folder).put("targetTree",target.tree.toString()).put("name",expected.name()).put("state","started"));
        Uri output = null;
        try {
            if (destination == null) {
                target.requireContentAccess(target.document(target.root));
                Uri made = DocumentsContract.createDocument(target.resolver,target.document(target.root),DIR,folder);
                if (made == null) throw new IOException("폴더를 만들지 못했어요.");
                if("com.android.externalstorage.documents".equals(target.tree.getAuthority())&&!DocumentsContract.getDocumentId(made).equals(target.root+"/"+folder))throw new IOException("새 폴더가 요청한 위치를 가리키지 않아 중단했어요.");
                for (Entry e : target.list(target.root,cancelled)) if (e.uri().equals(made) || e.id().equals(DocumentsContract.getDocumentId(made))) destination=e;
                if (destination == null || !destination.directory() || !destination.name().equals(folder)) throw new IOException("폴더 생성 결과를 확인하지 못했어요.");
            }
            Set<String> existingIds=new HashSet<>();
            for (Entry e : target.list(destination.id(),cancelled)) {
                existingIds.add(e.id());
                if (e.name().equals(expected.name())) throw new IOException("덮어쓰지 않고 건너뜀: "+expected.name());
            }
            check(cancelled); target.requireContentAccess(destination.uri()); output = DocumentsContract.createDocument(target.resolver,destination.uri(),expected.mime(),expected.name());
            if (output == null) throw new IOException("복사본을 만들지 못했어요.");
            journal(new JSONObject().put("operation",operation).put("output",output.toString()).put("state","created"));
            String outputId=DocumentsContract.getDocumentId(output);
            if("com.android.externalstorage.documents".equals(target.tree.getAuthority())&&!outputId.equals(destination.id()+"/"+expected.name()))throw new IOException("복사본이 요청한 위치를 가리키지 않아 쓰기를 중단했어요.");
            if(existingIds.contains(outputId)||(outputId.equals(expected.id())&&Objects.equals(output.getAuthority(),expected.uri().getAuthority())))throw new IOException("기존 파일을 가리키는 응답이라 쓰기를 중단했어요.");
            boolean exactOutput=false;
            for(Entry e:target.list(destination.id(),cancelled))if(e.id().equals(outputId)&&e.name().equals(expected.name())&&!e.directory())exactOutput=true;
            if(!exactOutput)throw new IOException("복사본 이름 또는 위치를 확인하지 못했어요. 쓰기를 중단했어요.");
            requireContentAccess(expected.uri()); target.requireContentAccess(output);
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); long count=0;
            try(InputStream in=resolver.openInputStream(expected.uri())) {
                if(in==null)throw new IOException("원본 파일을 열지 못했어요.");
                requireContentAccess(expected.uri());target.requireContentAccess(output);check(cancelled);
                try(OutputStream out=target.resolver.openOutputStream(output,"w")) {
                    if(out==null)throw new IOException("복사본을 열지 못했어요.");byte[] buffer=new byte[65536];
                    while(true) {
                        check(cancelled);requireContentAccess(expected.uri());target.requireContentAccess(output);
                        int n=in.read(buffer);
                        requireContentAccess(expected.uri());target.requireContentAccess(output);check(cancelled);
                        if(n==-1)break;if(n==0)continue;
                        out.write(buffer,0,n);
                        requireContentAccess(expected.uri());target.requireContentAccess(output);check(cancelled);
                        digest.update(buffer,0,n);count+=n;
                    }
                    requireContentAccess(expected.uri());target.requireContentAccess(output);check(cancelled);out.flush();
                    requireContentAccess(expected.uri());target.requireContentAccess(output);check(cancelled);
                }
            }
            requireContentAccess(expected.uri());target.requireContentAccess(output);check(cancelled);
            fresh(expected,cancelled);
            if(expected.size()>=0 && count!=expected.size()) throw new IOException("원본 크기가 달라요.");
            String original=hex(digest.digest());
            if(!original.equals(hash(expected.uri(),Long.MAX_VALUE,cancelled)) || !original.equals(target.hash(output,Long.MAX_VALUE,cancelled))) throw new IOException("복사 검증 실패. 원본과 미완성 복사본을 보존했어요.");
            requireContentAccess(expected.uri());target.requireContentAccess(output);check(cancelled);
            journal(new JSONObject().put("operation",operation).put("output",output.toString()).put("bytes",count).put("state","verified_source_retained"));
            requireContentAccess(expected.uri());target.requireContentAccess(output);check(cancelled);
            return expected.name()+" → "+folder+" · 검증 완료 (원본 보존)";
        } catch(Exception e) {
            journal(new JSONObject().put("operation",operation).put("state","failed_or_cancelled").put("output",output==null?"":output.toString()).put("message",String.valueOf(e.getMessage()))); throw e;
        }
    }
    static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(); for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b & 255)); return s.toString(); }
    static final class ReadBudget {
        private long remaining;
        ReadBudget(long bytes) { remaining=bytes; }
        long remaining() { return remaining; }
        void consume(int bytes) { remaining-=bytes; }
    }
    String hash(Uri uri,long limit,BooleanSupplier cancelled) throws Exception {
        return hash(uri,limit,cancelled,new ReadBudget(Long.MAX_VALUE));
    }
    String hash(Uri uri,long limit,BooleanSupplier cancelled,ReadBudget budget) throws Exception {
        requireContentAccess(uri);
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); long count=0;
        try(InputStream in=resolver.openInputStream(uri)) {
            if(in==null)throw new IOException("파일을 읽지 못했어요."); byte[] b=new byte[65536];
            while(true) {
                check(cancelled);
                if(budget.remaining()<=0)throw new IOException("전체 해시 읽기 한도에 도달했어요.");
                int requested=(int)Math.min(b.length,Math.min(budget.remaining(),count>=limit?1:limit-count));
                requireContentAccess(uri);
                int n=in.read(b,0,requested);
                requireContentAccess(uri);check(cancelled);
                if(n==-1)break; if(n==0)continue;
                budget.consume(n); count+=n;
                if(count>limit)throw new IOException("해시 검사 크기 한도 초과");
                digest.update(b,0,n);
            }
        }
        requireContentAccess(uri);check(cancelled);
        return hex(digest.digest());
    }
    void rename(Entry expected,String name,BooleanSupplier cancelled) throws Exception {
        requireContentAccess(expected.uri());
        FileOrganizationPlan.safeName(name); fresh(expected,cancelled);
        for(Entry e:list(expected.parent(),cancelled))if(e.name().equals(name))throw new IOException("같은 이름이 있어요. 덮어쓰지 않아요.");
        String operation=UUID.randomUUID().toString();journal(new JSONObject().put("operation",operation).put("state","rename_started").put("source",expected.uri()).put("name",name));
        check(cancelled);
        requireContentAccess(expected.uri());
        Uri renamed=DocumentsContract.renameDocument(resolver,expected.uri(),name);
        if(renamed==null)throw new IOException("이름 변경 결과를 확인하지 못했어요. 새로고침 후 확인해 주세요.");
        boolean verified=false;
        for(Entry e:list(expected.parent(),cancelled))if(e.id().equals(DocumentsContract.getDocumentId(renamed)) && e.name().equals(name) && e.directory()==expected.directory() && (expected.directory() || e.size()==expected.size()))verified=true;
        journal(new JSONObject().put("operation",operation).put("state",verified?"rename_verified":"rename_uncertain").put("output",renamed));
        if(!verified)throw new IOException("이름 변경 결과가 불확실해요. 다시 실행하지 말고 새로고침으로 확인해 주세요.");
    }
}
