package dev.jun.gyeot;

import android.content.Context;
import android.provider.DocumentsContract;
import android.system.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** The only direct old-folder body capability: exact Download/들퍼레 -> exact 들퍼레 verified copy. */
final class ManagedMigrationBackend implements MigrationCore.Backend,AutoCloseable {
    record Inventory(List<String> paths,int directories,long bytes,boolean complete,List<String> warnings,boolean sourceMissing){}
    private final ManagedSharedStorage.Access access;private final ManagedSharedStorage.Node root,source;
    private final String scope;private final Factory factory;
    private interface Factory{ManagedSharedStorage.Access open()throws IOException;}
    ManagedMigrationBackend(Context context)throws IOException{this(ManagedSharedStorage.access(context),()->ManagedSharedStorage.access(context.getApplicationContext()));}
    /** Synthetic filesystem seam; production callers always use the Context constructor. */
    ManagedMigrationBackend(ManagedSharedStorage.Access access)throws IOException {this(access,()->new ManagedSharedStorage.Access(access.volume,access.gate));}
    private ManagedMigrationBackend(ManagedSharedStorage.Access access,Factory factory)throws IOException {
        this.access=access;this.factory=factory;ManagedSharedStorage.Node r=null,s=null;
        try{
            r=access.directory(ManagedSharedStorage.ROOT_NAME,false);
            try{s=access.directory(ManagedSharedStorage.LEGACY,false);}catch(IOException e){if(!ManagedSharedStorage.missing(e))throw e;}
            root=r;source=s;scope=access.scope()+"|target:"+ManagedSharedStorage.key(root.stat())+"|source:"+(source==null?"absent":ManagedSharedStorage.key(source.stat()));requireAccess();
        }catch(IOException|RuntimeException e){if(s!=null)s.close();if(r!=null)r.close();access.close();throw e;}
    }
    @Override public String sourceRootId(){return MigrationCore.SOURCE_ROOT;}
    @Override public String targetRootId(){return MigrationCore.TARGET_ROOT;}
    @Override public String scopeIdentity(){return scope;}
    @Override public void requireAccess()throws IOException {
        access.check();root.check();if(source!=null)source.check();
        else {try(ManagedSharedStorage.Node unexpected=access.directory(ManagedSharedStorage.LEGACY,false)){throw new IOException("이전 폴더가 새로 생겼어요. 목록을 다시 확인해 주세요.");}catch(IOException e){if(!ManagedSharedStorage.missing(e))throw e;}}
    }
    @Override public void checkVerifiedTarget(String expectedScope,MigrationCore.Stamp expected)throws IOException{try(ManagedMigrationBackend fresh=new ManagedMigrationBackend(factory.open(),factory)){fresh.checkLiveVerifiedTarget(expectedScope,expected);}}
    private void checkLiveVerifiedTarget(String expectedScope,MigrationCore.Stamp expected)throws IOException{MigrationCore.Backend.super.checkVerifiedTarget(expectedScope,expected);}
    Inventory inventory(BooleanSupplier cancelled)throws IOException {
        requireAccess();if(source==null)return new Inventory(List.of(),0,0,true,List.of(),true);
        List<String> paths=new ArrayList<>(),warnings=new ArrayList<>();long[] bytes={0};int[] dirs={0};
        inventoryAt("",0,paths,dirs,bytes,warnings,cancelled,new InventoryBudget());requireAccess();return new Inventory(List.copyOf(paths),dirs[0],bytes[0],warnings.isEmpty(),List.copyOf(warnings),false);
    }
    private void inventoryAt(String relative,int depth,List<String> paths,int[] dirs,long[] bytes,List<String>warnings,BooleanSupplier cancel,InventoryBudget budget)throws IOException {
        stop(cancel);requireAccess();if(depth>20){warn(warnings,"20단계를 넘는 하위 폴더는 보류: "+relative);return;}
        try(ManagedSharedStorage.Node dir=access.directory(ManagedSharedStorage.LEGACY+(relative.isEmpty()?"":"/"+relative),false);var children=dir.children()){
            for(var childPath:children){if(!budget.next()){warn(warnings,"목록 확인 한도(항목 10,000개 또는 30초)에 도달했어요. 확인하지 않은 항목은 이전하지 않았어요.");return;}String name=childPath.getFileName().toString();ManagedSharedStorage.single(name);
                stop(cancel);requireAccess();String next=relative.isEmpty()?name:relative+"/"+name;
                if(paths.size()>=MigrationLedger.MAX_RECORDS){warn(warnings,"파일 목록 2,000개 한도에 도달했어요. 남은 항목은 아직 확인하지 않았어요.");return;}
                try(ManagedSharedStorage.Node child=access.node(ManagedSharedStorage.LEGACY+"/"+next,false)){
                    StructStat st=child.stat();if(OsConstants.S_ISDIR(st.st_mode)){dirs[0]++;inventoryAt(next,depth+1,paths,dirs,bytes,warnings,cancel,budget);if(budget.halted)return;}
                    else if(OsConstants.S_ISREG(st.st_mode)){paths.add(next);if(st.st_size<0||Long.MAX_VALUE-bytes[0]<st.st_size)throw new IOException("전체 파일 크기를 확인할 수 없어요.");bytes[0]+=st.st_size;}
                    else warn(warnings,"일반 파일이 아닌 항목 보류: "+next);
                }catch(InterruptedIOException cancelled){throw cancelled;}catch(IOException e){warn(warnings,next+" · "+e.getMessage());}
                dir.check();
            }
        }
    }
    private static final class InventoryBudget {int visited;boolean halted;final long deadline=System.nanoTime()+30_000_000_000L;boolean next(){if(halted||++visited>10000||System.nanoTime()>deadline){halted=true;return false;}return true;}}
    private static void warn(List<String> warnings,String warning){if(warnings.size()<64)warnings.add(warning);else if(warnings.size()==64)warnings.add("추가 경고가 있어요. 이전을 완료한 것으로 표시하지 않아요.");}
    @Override public MigrationCore.Stamp source(String relative)throws IOException {requireSource();return stamp(ManagedSharedStorage.LEGACY,relative,true);}
    @Override public MigrationCore.Stamp target(String relative)throws IOException {
        try{return stamp(ManagedSharedStorage.ROOT_NAME,relative,false);}catch(IOException e){if(ManagedSharedStorage.missing(e))return null;throw e;}
    }
    private MigrationCore.Stamp stamp(String base,String relative,boolean legacy)throws IOException {
        MigrationCore.safePath(relative);requireAccess();try(ManagedSharedStorage.Node n=access.node(base+"/"+relative,false)){n.requireFile();return stamp(relative,n.stat(),legacy);}
    }
    private MigrationCore.Stamp stamp(String relative,StructStat st,boolean legacy){
        String ref=legacy?DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",MigrationCore.SOURCE_ROOT+"/"+relative).toString():ManagedSharedStorage.document(relative).toString();
        return new MigrationCore.Stamp(relative,ref,ManagedSharedStorage.key(st),st.st_size,Math.max(0,st.st_mtime)*1000L);
    }
    @Override public InputStream readSource(MigrationCore.Stamp expected)throws IOException{requireSource();return read(expected,true);}
    @Override public InputStream readTarget(MigrationCore.Stamp expected)throws IOException{return read(expected,false);}
    private InputStream read(MigrationCore.Stamp expected,boolean legacy)throws IOException {
        MigrationCore.safePath(expected.relativePath());requireAccess();ManagedSharedStorage.Node node=access.node((legacy?ManagedSharedStorage.LEGACY:ManagedSharedStorage.ROOT_NAME)+"/"+expected.relativePath(),false);
        try{node.requireFile();same(expected,stamp(expected.relativePath(),node.stat(),legacy));}
        catch(IOException e){node.close();throw e;}
        return new InputStream(){long offset;boolean closed;
            private void check()throws IOException{if(closed)throw new IOException("읽기 핸들이 닫혔어요.");requireAccess();node.requireFile();same(expected,stamp(expected.relativePath(),node.stat(),legacy));}
            @Override public int read()throws IOException{byte[] b=new byte[1];int n=read(b,0,1);return n<0?-1:b[0]&255;}
            @Override public int read(byte[] b,int start,int length)throws IOException {Objects.checkFromIndexSize(start,length,b.length);check();if(length==0)return 0;try{int n=Os.pread(node.last().getFileDescriptor(),b,start,length,offset);check();if(n==0)return -1;offset+=n;return n;}catch(ErrnoException e){throw ManagedSharedStorage.io(e);}}
            @Override public void close(){if(!closed){closed=true;node.close();}}
        };
    }
    @Override public void syncTarget(MigrationCore.Stamp expected)throws IOException {
        requireAccess();try(ManagedSharedStorage.Node n=access.node(ManagedSharedStorage.ROOT_NAME+"/"+MigrationCore.safePath(expected.relativePath()),false)){n.requireFile();same(expected,stamp(expected.relativePath(),n.stat(),false));n.sync();same(expected,stamp(expected.relativePath(),n.stat(),false));requireAccess();}
    }
    @Override public MigrationCore.NewFile createExclusive(String relative)throws IOException {
        MigrationCore.safePath(relative);requireAccess();String requested=ManagedSharedStorage.ROOT_NAME+"/"+relative;
        try(ManagedSharedStorage.Node parent=access.directory(ManagedSharedStorage.parent(requested),true)){parent.check();}
        requireAccess();ManagedSharedStorage.Created made=access.create(requested);MigrationCore.Stamp initial=stamp(relative,ManagedSharedStorage.stat(made.fd),false);
        return new MigrationCore.NewFile(){boolean closed;long offset;
            private void check()throws IOException{if(closed)throw new IOException("새 파일 쓰기가 닫혔어요.");requireAccess();made.check();if(ManagedSharedStorage.stat(made.fd).st_size!=offset)throw new IOException("새 파일이 다른 작업에서 변경됐어요.");}
            final OutputStream stream=new OutputStream(){
                @Override public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
                @Override public void write(byte[] b,int start,int length)throws IOException {Objects.checkFromIndexSize(start,length,b.length);check();try{int n=0;while(n<length){check();int wrote=Os.pwrite(made.fd.getFileDescriptor(),b,start+n,length-n,offset);if(wrote<=0)throw new IOException("새 파일 쓰기가 진행되지 않아요.");offset+=wrote;n+=wrote;check();}}catch(ErrnoException e){throw ManagedSharedStorage.io(e);}}
                @Override public void flush()throws IOException{check();}
            };
            public MigrationCore.Stamp stamp(){return initial;}
            public OutputStream stream(){return stream;}
            public void sync()throws IOException{check();made.sync();check();}
            public void close(){if(!closed){closed=true;made.close();}}
        };
    }
    /** Exact numeric MediaStore alias proof for this migration only; metadata + descriptor identity, never body reads. */
    static boolean provesMediaAlias(Context context,android.net.Uri alias,String relative,long expectedSize) {
        try {
            MigrationCore.safePath(relative);if(expectedSize<0||!canonicalMedia(alias))return false;
            String display=null,folder=null,data=null;long size=-1;
            try(android.database.Cursor cursor=context.getContentResolver().query(alias,new String[]{"_display_name","_size","relative_path","_data"},null,null,null)){
                if(cursor==null||!cursor.moveToFirst())return false;
                int nameIndex=cursor.getColumnIndex("_display_name"),sizeIndex=cursor.getColumnIndex("_size"),folderIndex=cursor.getColumnIndex("relative_path"),dataIndex=cursor.getColumnIndex("_data");
                if(nameIndex<0||sizeIndex<0||folderIndex<0||cursor.isNull(sizeIndex))return false;
                display=cursor.getString(nameIndex);size=cursor.getLong(sizeIndex);folder=cursor.getString(folderIndex);if(dataIndex>=0)data=cursor.getString(dataIndex);if(cursor.moveToNext())return false;
            }
            String parent=ManagedSharedStorage.parent(relative),expectedFolder=ManagedSharedStorage.LEGACY+"/"+(parent.isEmpty()?"":parent+"/");
            if(!ManagedSharedStorage.name(relative).equals(display)||!expectedFolder.equals(folder)||size!=expectedSize)return false;
            try(ManagedSharedStorage.Access access=ManagedSharedStorage.access(context);ManagedSharedStorage.Node targetRoot=access.directory(ManagedSharedStorage.ROOT_NAME,false);ManagedSharedStorage.Node source=access.node(ManagedSharedStorage.LEGACY+"/"+relative,false)){
                source.requireFile();StructStat before=source.stat();if(before.st_size!=expectedSize)return false;
                if(data!=null&&!data.isBlank()&&!new File(data).getCanonicalFile().equals(new File(access.volume,ManagedSharedStorage.LEGACY+"/"+relative).getCanonicalFile()))return false;
                targetRoot.check();source.check();access.check();
                try(android.os.ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(alias,"r")){
                    if(descriptor==null)return false;StructStat actual=ManagedSharedStorage.stat(descriptor);
                    if(!OsConstants.S_ISREG(actual.st_mode)||actual.st_nlink!=1||!ManagedSharedStorage.key(before).equals(ManagedSharedStorage.key(actual))||actual.st_size!=before.st_size||actual.st_mtime!=before.st_mtime)return false;
                    targetRoot.check();source.check();access.check();StructStat after=source.stat();return after.st_nlink==1&&ManagedSharedStorage.key(before).equals(ManagedSharedStorage.key(after))&&after.st_size==before.st_size&&after.st_mtime==before.st_mtime;
                }
            }
        }catch(Exception unavailable){return false;}
    }
    private static boolean canonicalMedia(android.net.Uri uri){
        if(uri==null||!"content".equals(uri.getScheme())||!"media".equals(uri.getAuthority())||uri.getQuery()!=null||uri.getFragment()!=null)return false;
        List<String> p=uri.getPathSegments();if(p.size()<3||(!p.get(0).equals("external")&&!p.get(0).equals("external_primary")))return false;
        boolean collection=p.size()==3&&(p.get(1).equals("downloads")||p.get(1).equals("file"))||p.size()==4&&Set.of("images","video","audio").contains(p.get(1))&&p.get(2).equals("media");
        if(!collection||!p.get(p.size()-1).matches("[1-9][0-9]*"))return false;
        android.net.Uri.Builder builder=new android.net.Uri.Builder().scheme("content").authority("media");for(String part:p)builder.appendPath(part);return uri.equals(builder.build());
    }
    private void requireSource()throws IOException{requireAccess();if(source==null)throw new IOException("이전 Download/들퍼레 폴더가 없어요.");}
    private static void same(MigrationCore.Stamp a,MigrationCore.Stamp b)throws IOException{if(!a.equals(b))throw new IOException("파일 내용 위치나 상태가 바뀌어 이전을 보류했어요.");}
    private static void stop(BooleanSupplier cancel)throws InterruptedIOException{if(cancel.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("이전 목록 확인을 취소했어요.");}
    @Override public void close()throws IOException{if(source!=null)source.close();root.close();access.close();}
}
