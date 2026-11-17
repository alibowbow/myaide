package dev.jun.gyeot;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.*;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.*;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.*;

/** App-owned DocumentsProvider. Only the exact new root, never the legacy migration source. */
public final class ManagedDocumentsProvider extends DocumentsProvider {
    private static final String[] DOCS={"document_id","_display_name","mime_type","_size","last_modified","flags"};
    private static final String[] ROOTS={"root_id","document_id","title","flags","mime_types","available_bytes"};
    private final Map<String,Pending> fresh=new HashMap<>();
    private HandlerThread worker;private Handler handler;
    record Pending(ManagedSharedStorage.Access access,ManagedSharedStorage.Created file,long time) implements Closeable {
        public void close(){file.close();ManagedSharedStorage.quietClose(access);}
    }
    @Override public boolean onCreate(){worker=new HandlerThread("shared-root-files");worker.start();handler=new Handler(worker.getLooper());return true;}
    @Override public Cursor queryRoots(String[] projection)throws FileNotFoundException {
        MatrixCursor out=new MatrixCursor(projection==null?ROOTS:projection);
        try{ManagedSharedStorage.tree(getContext());MatrixCursor.RowBuilder row=out.newRow();put(row,out,"root_id","shared-root");put(row,out,"document_id",ManagedSharedStorage.ROOT_ID);put(row,out,"title","들퍼레");put(row,out,"flags",DocumentsContract.Root.FLAG_LOCAL_ONLY);put(row,out,"mime_types","*/*");put(row,out,"available_bytes",0L);}
        catch(IOException unavailable){/* Revoked or uncreated: no invented root. */}return out;
    }
    @Override public Cursor queryDocument(String id,String[] projection)throws FileNotFoundException {
        MatrixCursor out=new MatrixCursor(projection==null?DOCS:projection);
        try(ManagedSharedStorage.Access access=ManagedSharedStorage.access(getContext())){include(out,access,id);return out;}catch(IOException|IllegalArgumentException e){out.close();throw fileError(e);}
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sortOrder)throws FileNotFoundException {
        try{return new ChildrenCursor(getContext(),parent,projection==null?DOCS:projection);}catch(IOException|IllegalArgumentException e){throw fileError(e);}
    }

    @Override public boolean isChildDocument(String parent,String child){
        try{ManagedSharedStorage.relative(parent);ManagedSharedStorage.relative(child);if(!child.startsWith(parent+"/"))return false;
            try(ManagedSharedStorage.Access access=ManagedSharedStorage.access(getContext());ManagedSharedStorage.Node p=access.directory(path(parent),false);ManagedSharedStorage.Node c=access.node(path(child),false)){p.check();c.check();return true;}
        }catch(IOException|IllegalArgumentException e){return false;}
    }
    @Override public String createDocument(String parent,String mime,String name)throws FileNotFoundException {
        appOnly();try{ManagedSharedStorage.single(name);String id=parent+"/"+name;String requested=path(id);ManagedSharedStorage.Access access=ManagedSharedStorage.access(getContext());boolean retained=false;
            try{
                // Validate parent before any creation. A missing ancestor must never be synthesized.
                try(ManagedSharedStorage.Node p=access.directory(path(parent),false)){p.check();}
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){try(ManagedSharedStorage.Node made=access.mkdirExclusive(requested)){made.check();}return id;}
                synchronized(fresh){expire();if(fresh.size()>=128)throw new IOException("미완료 새 파일이 많아 생성하지 않았어요.");if(fresh.containsKey(id))throw new IOException("같은 이름의 생성이 이미 진행 중이에요.");
                    ManagedSharedStorage.Created made=access.create(requested);fresh.put(id,new Pending(access,made,SystemClock.elapsedRealtime()));retained=true;return id;}
            }finally{if(!retained)access.close();}
        }catch(IOException|IllegalArgumentException e){throw fileError(e);}
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal)throws FileNotFoundException {
        if(signal!=null)signal.throwIfCanceled();
        try{
            path(id);StorageManager manager=getContext().getSystemService(StorageManager.class);if(manager==null)throw new IOException("Android 파일 서비스를 사용할 수 없어요.");
            if("r".equals(mode)){
                ManagedSharedStorage.Access access=ManagedSharedStorage.access(getContext());ManagedSharedStorage.Node node=null;
                try{node=access.node(path(id),false);node.requireFile();ReadProxy callback=new ReadProxy(access,node,signal);ParcelFileDescriptor result=manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,callback,handler);if(result==null)throw new IOException("Android 파일 읽기 연결을 만들지 못했어요.");return result;}
                catch(IOException|RuntimeException e){if(node!=null)node.close();access.close();throw e;}
            }
            appOnly();if(!"w".equals(mode)&&!"wt".equals(mode))throw new IOException("새 파일의 한 번 쓰기만 지원해요. 기존 파일 덮어쓰기는 허용하지 않아요.");
            Pending pending;synchronized(fresh){expire();pending=fresh.remove(id);}if(pending==null)throw new IOException("이번 실행에서 새로 만든 파일만 쓸 수 있어요. 기존 파일은 보존했어요.");
            try{pending.file().check();if(ManagedSharedStorage.stat(pending.file().fd).st_size!=0)throw new IOException("새 파일이 이미 변경됐어요.");ParcelFileDescriptor result=manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_WRITE_ONLY,new WriteProxy(pending,signal),handler);if(result==null)throw new IOException("Android 새 파일 쓰기 연결을 만들지 못했어요.");return result;}
            catch(IOException|RuntimeException e){pending.close();throw e;}
        }catch(IOException|IllegalArgumentException e){throw fileError(e);}
    }
    @Override public String renameDocument(String id,String name)throws FileNotFoundException{throw fileError(new IOException("안전한 새 사본 저장만 지원해요. 이름 변경은 아직 지원하지 않아요."));}
    /** Only this app, only a file it made in 들퍼레/이미지·영상·오디오 (the gallery's "원본 파일까지 지우기"); never a folder or another app's request. */
    @Override public void deleteDocument(String id)throws FileNotFoundException{
        appOnly();try(ManagedSharedStorage.Access access=ManagedSharedStorage.access(getContext())){access.removeMedia(path(id));}catch(IOException|IllegalArgumentException e){throw fileError(e);}
    }
    @Override public void removeDocument(String id,String parent)throws FileNotFoundException{throw fileError(new IOException("원본 제거는 지원하지 않아요."));}
    @Override public void shutdown(){synchronized(fresh){for(Pending p:fresh.values())p.close();fresh.clear();}if(worker!=null)worker.quitSafely();super.shutdown();}
    private void expire(){long now=SystemClock.elapsedRealtime();Iterator<Pending> it=fresh.values().iterator();while(it.hasNext()){Pending p=it.next();if(now-p.time()>5*60_000L){p.close();it.remove();}}}
    private void include(MatrixCursor out,ManagedSharedStorage.Access access,String id)throws IOException {
        try(ManagedSharedStorage.Node n=access.node(path(id),false)){StructStat st=n.stat();boolean dir=OsConstants.S_ISDIR(st.st_mode);MatrixCursor.RowBuilder row=out.newRow();
            put(row,out,"document_id",id);put(row,out,"_display_name",id.equals(ManagedSharedStorage.ROOT_ID)?ManagedSharedStorage.ROOT_NAME:ManagedSharedStorage.name(id));put(row,out,"mime_type",dir?DocumentsContract.Document.MIME_TYPE_DIR:mime(ManagedSharedStorage.name(id)));put(row,out,"_size",dir?0L:st.st_size);put(row,out,"last_modified",Math.max(0,st.st_mtime)*1000L);put(row,out,"flags",dir&&ownUid()?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:0);n.check();}
    }
    private static void put(MatrixCursor.RowBuilder row,MatrixCursor out,String key,Object value){if(out.getColumnIndex(key)>=0)row.add(key,value);}
    static String mime(String name){int dot=name.lastIndexOf('.');String ext=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);String type=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);return type==null?"application/octet-stream":type;}
    private static String path(String id)throws IOException {String relative=ManagedSharedStorage.relative(id);return ManagedSharedStorage.ROOT_NAME+(relative.isEmpty()?"":"/"+relative);}
    private static boolean ownUid(){return Binder.getCallingUid()==android.os.Process.myUid();}
    private static void appOnly(){if(!ownUid())throw new SecurityException("다른 앱에는 파일 읽기만 허용해요.");}
    private static FileNotFoundException fileError(Exception e){FileNotFoundException f=new FileNotFoundException(e.getMessage());f.initCause(e);return f;}
    private static ErrnoException errno(Exception e){return e instanceof ErrnoException x?x:new ErrnoException("shared-root",OsConstants.EIO,e);}
    private static void bounds(long offset,int size,byte[] data)throws IOException {if(offset<0||size<0||size>data.length||offset>Long.MAX_VALUE-size)throw new IOException("잘못된 파일 범위예요.");}
    /** Streaming child metadata: no File.list array or unbounded MatrixCursor row allocation. */
    private static final class ChildrenCursor extends android.database.AbstractCursor {
        final ManagedSharedStorage.Access access;final ManagedSharedStorage.Node parent;final String parentId;final String[] columns;final boolean writable;
        java.nio.file.DirectoryStream<java.nio.file.Path> stream;Iterator<java.nio.file.Path> iterator;int index=-1;Integer count;Object[] row;
        ChildrenCursor(Context context,String id,String[] cols)throws IOException {
            columns=cols.clone();parentId=id;writable=ownUid();access=ManagedSharedStorage.access(context);ManagedSharedStorage.Node n=null;
            try{n=access.directory(path(id),false);parent=n;reset();}catch(IOException|RuntimeException e){if(n!=null)n.close();access.close();throw e;}
        }
        private void reset()throws IOException{if(stream!=null)stream.close();stream=parent.children();iterator=stream.iterator();index=-1;row=null;}
        @Override public int getCount(){if(count!=null)return count;try(var items=parent.children()){
            int n=0;long deadline=System.nanoTime()+30_000_000_000L;for(var ignored:items){parent.check();if(n==Integer.MAX_VALUE||System.nanoTime()>deadline)throw new IOException("폴더 목록 확인 시간이 길어 중단했어요.");n++;}parent.check();count=n;return n;
        }catch(IOException e){throw new IllegalStateException(e);}}
        @Override public boolean onMove(int oldPosition,int newPosition){try{
            parent.check();if(newPosition<=index)reset();while(index<newPosition){if(!iterator.hasNext())return false;String name=iterator.next().getFileName().toString();ManagedSharedStorage.single(name);index++;
                if(index==newPosition){String id=parentId+"/"+name;try(ManagedSharedStorage.Node child=access.node(path(id),false)){StructStat st=child.stat();boolean dir=OsConstants.S_ISDIR(st.st_mode);row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=switch(columns[i]){case "document_id"->id;case "_display_name"->name;case "mime_type"->dir?DocumentsContract.Document.MIME_TYPE_DIR:mime(name);case "_size"->dir?0L:st.st_size;case "last_modified"->Math.max(0,st.st_mtime)*1000L;case "flags"->dir&&writable?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:0;default->null;};}}
                parent.check();}return true;
        }catch(IOException e){throw new IllegalStateException(e);}}
        @Override public String[] getColumnNames(){return columns.clone();}
        private Object value(int column){checkPosition();return row[column];}
        @Override public String getString(int c){Object v=value(c);return v==null?null:String.valueOf(v);}
        @Override public short getShort(int c){return (short)getLong(c);}
        @Override public int getInt(int c){return (int)getLong(c);}
        @Override public long getLong(int c){Object v=value(c);return v==null?0:((Number)v).longValue();}
        @Override public float getFloat(int c){return getLong(c);}
        @Override public double getDouble(int c){return getLong(c);}
        @Override public boolean isNull(int c){return value(c)==null;}
        @Override public int getType(int c){Object v=value(c);return v==null?FIELD_TYPE_NULL:v instanceof Number?FIELD_TYPE_INTEGER:FIELD_TYPE_STRING;}
        @Override public void close(){if(isClosed())return;ManagedSharedStorage.quietClose(stream);parent.close();ManagedSharedStorage.quietClose(access);super.close();}
    }
    static final class ReadProxy extends ProxyFileDescriptorCallback {
        final ManagedSharedStorage.Access access;final ManagedSharedStorage.Node node;final CancellationSignal signal;final long size,modified;boolean released;
        ReadProxy(ManagedSharedStorage.Access a,ManagedSharedStorage.Node n,CancellationSignal s)throws IOException{access=a;node=n;signal=s;StructStat st=n.stat();size=st.st_size;modified=st.st_mtime;}
        void check()throws IOException{if(released)throw new IOException("파일이 닫혔어요.");if(signal!=null)signal.throwIfCanceled();node.requireFile();StructStat st=node.stat();if(st.st_size!=size||st.st_mtime!=modified)throw new IOException("읽는 중 파일이 바뀌었어요.");}
        @Override public synchronized long onGetSize()throws ErrnoException{try{check();return size;}catch(Exception e){throw errno(e);}}
        @Override public synchronized int onRead(long offset,int count,byte[] data)throws ErrnoException{try{check();bounds(offset,count,data);int total=0;while(total<count){check();int n=Os.pread(node.last().getFileDescriptor(),data,total,count-total,offset+total);check();if(n==0)break;total+=n;}return total;}catch(Exception e){throw errno(e);}}
        @Override public synchronized void onFsync()throws ErrnoException{try{check();}catch(Exception e){throw errno(e);}}
        @Override public synchronized void onRelease(){if(!released){released=true;node.close();ManagedSharedStorage.quietClose(access);}}
    }
    static final class WriteProxy extends ProxyFileDescriptorCallback {
        final Pending pending;final CancellationSignal signal;boolean released;long written;
        WriteProxy(Pending p,CancellationSignal s){pending=p;signal=s;}
        void check()throws IOException{if(released)throw new IOException("파일이 닫혔어요.");if(signal!=null)signal.throwIfCanceled();pending.file().check();if(ManagedSharedStorage.stat(pending.file().fd).st_size!=written)throw new IOException("새 파일이 다른 작업에서 변경됐어요.");}
        @Override public synchronized long onGetSize()throws ErrnoException{try{check();return ManagedSharedStorage.stat(pending.file().fd).st_size;}catch(Exception e){throw errno(e);}}
        @Override public synchronized int onWrite(long offset,int size,byte[] data)throws ErrnoException{try{check();bounds(offset,size,data);if(offset!=written)throw new IOException("새 파일은 앞에서부터 한 번만 쓸 수 있어요.");int n=Os.pwrite(pending.file().fd.getFileDescriptor(),data,0,size,offset);written+=n;pending.file().sync();check();return n;}catch(Exception e){throw errno(e);}}
        @Override public synchronized void onFsync()throws ErrnoException{try{check();pending.file().sync();check();}catch(Exception e){throw errno(e);}}
        @Override public synchronized void onRelease(){if(!released){released=true;pending.close();}}
    }
}
