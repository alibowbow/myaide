package dev.jun.gyeot;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.util.*;

/** Real OS-authorized primary storage. Never a fabricated external-storage SAF permission. */
final class ManagedSharedStorage {
    static final String AUTHORITY="dev.jun.gyeot.shared-documents", ROOT_ID="primary:들퍼레";
    static final String ROOT_NAME="들퍼레", LEGACY="Download/들퍼레", PROJECTS=ROOT_NAME+"/프로젝트";
    /** Folders whose files the app made itself (pictures, videos, speech); only these files may ever be removed. */
    static final Set<String> MEDIA_FOLDERS=Set.of(ROOT_NAME+"/이미지",ROOT_NAME+"/영상",ROOT_NAME+"/오디오");
    private ManagedSharedStorage() {}
    static boolean available(Context context) { try(Access access=access(context)) { access.check();return true; } catch(IOException|RuntimeException unavailable) { return false; } }
    static Uri tree(Context context)throws IOException { try(Access access=access(context);Node root=access.directory(ROOT_NAME,false)){root.check();return treeUri();} }
    static Uri ensureRoot(Context context)throws IOException { try(Access access=access(context);Node root=access.directory(ROOT_NAME,true)){root.sync();root.check();return treeUri();} }
    static Uri treeUri(){return DocumentsContract.buildTreeDocumentUri(AUTHORITY,ROOT_ID);}
    static Uri document(String relative){String id=relative.isEmpty()?ROOT_ID:ROOT_ID+"/"+safe(relative);return DocumentsContract.buildDocumentUriUsingTree(treeUri(),id);}
    static String relative(String id)throws IOException {
        if(ROOT_ID.equals(id))return "";
        if(id==null||!id.startsWith(ROOT_ID+"/"))throw new IOException("들퍼레 밖의 문서 주소예요.");
        try{return safe(id.substring(ROOT_ID.length()+1));}catch(IllegalArgumentException error){throw new IOException("안전하지 않은 문서 주소예요.",error);}
    }
    static boolean isTree(Uri uri){return treeUri().equals(uri);}
    /** Whether a document is a file in one of the app's media folders, so its original may be deleted. */
    static boolean removable(Uri uri){
        try{if(uri==null||!AUTHORITY.equals(uri.getAuthority()))return false;String relative=relative(DocumentsContract.getDocumentId(uri));String path=ROOT_NAME+"/"+relative;return !relative.isEmpty()&&MEDIA_FOLDERS.contains(parent(path));}
        catch(IOException|RuntimeException notOurs){return false;}
    }
    static void guardUri(Context context,Uri uri)throws IOException {
        if(uri==null||!"content".equals(uri.getScheme())||!AUTHORITY.equals(uri.getAuthority())||uri.getQuery()!=null||uri.getFragment()!=null)throw new IOException("들퍼레의 문서 주소가 아니에요.");
        String id;try{id=DocumentsContract.getDocumentId(uri);}catch(RuntimeException error){throw new IOException("문서 주소를 확인하지 못했어요.",error);}
        String relative=relative(id);
        if(!document(relative).equals(uri))throw new IOException("표준 들퍼레 문서 주소가 아니에요.");
        try(Access access=access(context);Node node=access.node(ROOT_NAME+(relative.isEmpty()?"":"/"+relative),false)){node.check();}
    }
    static Access access(Context context)throws IOException {
        Context app=context.getApplicationContext();StorageManager manager=app.getSystemService(StorageManager.class);
        if(manager==null)throw new IOException("Android 저장소 서비스를 사용할 수 없어요.");
        StorageVolume primary=primary(manager);File directory=primary==null?null:primary.getDirectory();
        if(directory==null||!primary.isPrimary()||!Environment.MEDIA_MOUNTED.equals(primary.getState()))throw new IOException("내장 공유 저장소가 연결되지 않았어요.");
        File canonical=directory.getCanonicalFile();
        return new Access(canonical,()->{
            if(!Environment.isExternalStorageManager())throw new IOException("Android의 모든 파일 접근 허용이 필요해요. 기존 폴더 선택 권한은 별도로 유지돼요.");
            StorageVolume current=primary(manager);File now=current==null?null:current.getDirectory();
            if(current==null||!current.isPrimary()||!Environment.MEDIA_MOUNTED.equals(current.getState())||now==null||!canonical.equals(now.getCanonicalFile()))throw new IOException("내장 저장소가 분리되거나 바뀌었어요.");
        });
    }
    private static StorageVolume primary(StorageManager manager)throws IOException {try{return manager.getPrimaryStorageVolume();}catch(RuntimeException unavailable){throw new IOException("내장 공유 저장소를 확인하지 못했어요. 기존 폴더 선택 권한은 유지해요.",unavailable);}}
    interface Gate {void check()throws IOException;}
    private static void requireGate(Gate gate)throws IOException {try{gate.check();}catch(RuntimeException unavailable){throw new IOException("저장소 접근 상태를 확인하지 못했어요.",unavailable);}}
    /** Injectable package-local filesystem seam. Production accepts only the OS primary volume above. */
    static final class Access implements Closeable {
        final File volume;final Gate gate;final ParcelFileDescriptor pinned;final StructStat identity;
        Access(File volume,Gate gate)throws IOException {
            this.volume=volume.getCanonicalFile();this.gate=gate;requireGate(gate);
            if(!this.volume.equals(volume.getAbsoluteFile()))throw new IOException("저장소 경로가 별칭이에요.");
            pinned=open(this.volume.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);
            try{identity=stat(pinned);if(!OsConstants.S_ISDIR(identity.st_mode))throw new IOException("저장소가 폴더가 아니에요.");check();}catch(IOException error){close();throw error;}
        }
        void check()throws IOException {requireGate(gate);sameIdentity(identity,lstat(volume.getPath()));sameIdentity(identity,stat(pinned));}
        String scope()throws IOException {check();return volume.getPath()+":"+key(identity);}
        Node node(String path,boolean createParents)throws IOException {
            safe(path);requireAllowedPath(path);if(createParents)requireTargetPath(path);check();List<ParcelFileDescriptor> fds=new ArrayList<>();List<StructStat> states=new ArrayList<>();List<String> paths=new ArrayList<>();
            ParcelFileDescriptor parent=pinned;String actual=volume.getPath();String[] parts=path.split("/");
            try {
                for(int i=0;i<parts.length;i++){
                    checkChain(fds,states,paths);String part=parts[i];actual+="/"+part;String anchored=anchor(parent)+"/"+part;
                    if(createParents&&i<parts.length-1)ensureDirectory(anchored,parent);
                    ParcelFileDescriptor fd=open(anchored,OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);fds.add(fd);StructStat st=stat(fd);
                    states.add(st);paths.add(actual);
                    if(i<parts.length-1&&!OsConstants.S_ISDIR(st.st_mode))throw new IOException("상위 경로가 폴더가 아니에요.");
                    if(!OsConstants.S_ISDIR(st.st_mode)&&!OsConstants.S_ISREG(st.st_mode))throw new IOException("일반 파일·폴더만 사용할 수 있어요.");
                    checkChain(fds,states,paths);parent=fd;
                }
                return new Node(this,path,fds,states,paths);
            }catch(IOException|RuntimeException error){for(ParcelFileDescriptor fd:fds)quietClose(fd);throw error;}
        }
        Node directory(String path,boolean create)throws IOException {
            safe(path);requireAllowedPath(path);if(create){requireTargetPath(path);String parent=parent(path);try(Node node=parent.isEmpty()?null:directory(parent,true)){
                ParcelFileDescriptor fd=node==null?pinned:node.last();if(node!=null)node.requireDirectory();check();
                ensureDirectory(anchor(fd)+"/"+name(path),fd);if(node!=null)node.check();check();
            }}
            Node result=node(path,false);try{result.requireDirectory();return result;}catch(IOException error){result.close();throw error;}
        }
        private void ensureDirectory(String path,ParcelFileDescriptor parent)throws IOException {
            check();try {StructStat st=Os.lstat(path);if(!OsConstants.S_ISDIR(st.st_mode))throw new IOException("같은 이름의 파일이나 링크가 있어요.");}
            catch(ErrnoException missing){if(missing.errno!=OsConstants.ENOENT)throw io(missing);try{Os.mkdir(path,0700);Os.fsync(parent.getFileDescriptor());}catch(ErrnoException error){throw io(error);}}
            check();
        }
        Node mkdirExclusive(String path)throws IOException {
            safe(path);requireTargetPath(path);String parentPath=parent(path);try(Node parent=directory(parentPath,false)){
                parent.check();try{Os.mkdir(anchor(parent.last())+"/"+name(path),0700);}catch(ErrnoException error){throw io(error);}
                parent.check();parent.sync();Node made=directory(path,false);try{made.sync();return made;}catch(IOException error){made.close();throw error;}
            }
        }
        /**
         * Removes one file the app saved in a media folder (들퍼레/이미지·영상·오디오/<name>): the exact regular file it saw,
         * never a folder, a link, a file with other links, or anything outside those folders.
         */
        void removeMedia(String path)throws IOException {
            safe(path);requireTargetPath(path);String parentPath=parent(path);
            if(!MEDIA_FOLDERS.contains(parentPath))throw new IOException("들퍼레의 이미지·영상·오디오 폴더에서 만든 파일만 지울 수 있어요.");
            try(Node parent=directory(parentPath,false);Node file=node(path,false)){
                file.requireFile();parent.check();StructStat seen=file.stat();String target=anchor(parent.last())+"/"+name(path);
                sameIdentity(seen,lstat(target));
                try{Os.remove(target);}catch(ErrnoException error){throw io(error);}
                parent.sync();
            }
        }
        Created create(String path)throws IOException {
            safe(path);requireTargetPath(path);String parentPath=parent(path);Node parent=directory(parentPath,false);ParcelFileDescriptor fd=null;
            try {
                parent.check();fd=open(anchor(parent.last())+"/"+name(path),OsConstants.O_RDWR|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_NOFOLLOW,0600);
                StructStat st=stat(fd);if(!OsConstants.S_ISREG(st.st_mode)||st.st_size!=0)throw new IOException("새 빈 파일이 아니에요.");
                parent.check();sameIdentity(st,lstat(volume.getPath()+"/"+path));try{Os.fsync(fd.getFileDescriptor());}catch(ErrnoException error){throw io(error);}parent.sync();return new Created(parent,path,fd,st);
            }catch(IOException|RuntimeException error){quietClose(fd);parent.close();throw error;}
        }
        // ── 들퍼레/프로젝트: the one place the user's own projects may be replaced, renamed and removed (ProjectStores) ──
        private String project(String folder)throws IOException {single(folder);if(folder.startsWith("."))throw new IOException("숨김 폴더는 프로젝트로 쓰지 않아요.");return PROJECTS+"/"+folder;}
        /** The project folders holding an index.html, with that page's time (ms). */
        List<String[]> projects()throws IOException {
            List<String[]> out=new ArrayList<>();
            try(Node dir=directory(PROJECTS,true);java.nio.file.DirectoryStream<java.nio.file.Path> items=dir.children()){
                String base=anchor(dir.last());
                for(java.nio.file.Path item:items){
                    String name=item.getFileName().toString();if(name.startsWith("."))continue;
                    try{StructStat st=lstat(base+"/"+name);if(!OsConstants.S_ISDIR(st.st_mode))continue;StructStat page=lstat(base+"/"+name+"/index.html");
                        if(OsConstants.S_ISREG(page.st_mode))out.add(new String[]{name,String.valueOf(page.st_mtime*1000L)});}catch(IOException missing){/* not a project */}
                }
            }
            return out;
        }
        byte[] readProject(String folder,int limit)throws IOException {
            try(Node page=node(project(folder)+"/index.html",false)){
                page.requireFile();if(page.stat().st_size>limit)throw new IOException("HTML이 너무 커요.");
                try(FileInputStream in=new FileInputStream(page.last().getFileDescriptor())){byte[] bytes=in.readAllBytes();page.check();return bytes;}
            }
        }
        /** Writes the page beside it first and moves it over index.html in one step: a reader never sees half of it. */
        long writeProject(String folder,byte[] bytes)throws IOException {
            String dirPath=project(folder);
            try(Node dir=directory(dirPath,true)){
                String temp=".index-"+System.nanoTime()+".tmp";
                try(Created made=create(dirPath+"/"+temp)){
                    try(FileOutputStream out=new FileOutputStream(made.fd.getFileDescriptor())){out.write(bytes);out.flush();}
                    made.sync();
                    dir.check();
                    try{Os.rename(anchor(dir.last())+"/"+temp,anchor(dir.last())+"/index.html");}catch(ErrnoException e){try{Os.remove(anchor(dir.last())+"/"+temp);}catch(ErrnoException ignored){}throw io(e);}
                    dir.sync();
                }
                return lstat(volume.getPath()+"/"+dirPath+"/index.html").st_mtime*1000L;
            }
        }
        void renameProject(String from,String to)throws IOException {
            project(from);project(to);
            try(Node dir=directory(PROJECTS,false)){
                String base=anchor(dir.last());
                try{Os.lstat(base+"/"+to);throw new IOException("같은 이름의 프로젝트 폴더가 있어요.");}catch(ErrnoException absent){if(absent.errno!=OsConstants.ENOENT)throw io(absent);}
                if(!OsConstants.S_ISDIR(lstat(base+"/"+from).st_mode))throw new IOException("프로젝트 폴더가 아니에요.");
                try{Os.rename(base+"/"+from,base+"/"+to);}catch(ErrnoException e){throw io(e);}
                dir.sync();
            }
        }
        /** Removes one project folder and what is in it; a link inside is removed as a link, never followed. */
        void removeProject(String folder)throws IOException {
            project(folder);
            try(Node dir=directory(PROJECTS,false)){String base=anchor(dir.last());if(!OsConstants.S_ISDIR(lstat(base+"/"+folder).st_mode))throw new IOException("프로젝트 폴더가 아니에요.");removeTree(base+"/"+folder,0);dir.sync();}
            catch(IOException e){if(missing(e))return;throw e;}
        }
        private void removeTree(String path,int depth)throws IOException {
            if(depth>12)throw new IOException("폴더가 너무 깊어요.");
            StructStat st=lstat(path);
            if(OsConstants.S_ISDIR(st.st_mode)){
                String[] names=new File(path).list();
                if(names!=null)for(String name:names)removeTree(path+"/"+name,depth+1);
                try{Os.remove(path);}catch(ErrnoException e){throw io(e);}
            }else try{Os.remove(path);}catch(ErrnoException e){throw io(e);}
        }
        void checkChain(List<ParcelFileDescriptor> fds,List<StructStat> states,List<String> paths)throws IOException {check();for(int i=0;i<fds.size();i++){sameIdentity(states.get(i),stat(fds.get(i)));sameIdentity(states.get(i),lstat(paths.get(i)));}}
        @Override public void close()throws IOException {pinned.close();}
    }
    static final class Node implements AutoCloseable {
        final Access access;final String path;final List<ParcelFileDescriptor> fds;final List<StructStat> states;final List<String> paths;
        Node(Access a,String p,List<ParcelFileDescriptor> f,List<StructStat>s,List<String> paths){access=a;path=p;fds=f;states=s;this.paths=paths;}
        ParcelFileDescriptor last(){return fds.get(fds.size()-1);}
        void check()throws IOException {access.checkChain(fds,states,paths);}
        StructStat stat()throws IOException {check();return ManagedSharedStorage.stat(last());}
        void requireDirectory()throws IOException {if(!OsConstants.S_ISDIR(stat().st_mode))throw new IOException("폴더가 아니에요.");}
        void requireFile()throws IOException {StructStat st=stat();if(!OsConstants.S_ISREG(st.st_mode)||st.st_nlink!=1)throw new IOException("단일 위치의 일반 파일만 읽거나 쓸 수 있어요.");}
        java.nio.file.DirectoryStream<java.nio.file.Path> children()throws IOException {requireDirectory();java.nio.file.DirectoryStream<java.nio.file.Path> stream=java.nio.file.Files.newDirectoryStream(java.nio.file.Path.of(anchor(last())));try{check();return stream;}catch(IOException error){stream.close();throw error;}}
        void sync()throws IOException {check();try{for(int i=fds.size()-1;i>=0;i--)Os.fsync(fds.get(i).getFileDescriptor());Os.fsync(access.pinned.getFileDescriptor());}catch(ErrnoException e){throw io(e);}check();}
        @Override public void close(){for(int i=fds.size()-1;i>=0;i--)quietClose(fds.get(i));}
    }
    static final class Created implements AutoCloseable {
        final Node parent;final String path;final ParcelFileDescriptor fd;final StructStat identity;
        Created(Node p,String path,ParcelFileDescriptor fd,StructStat s){parent=p;this.path=path;this.fd=fd;identity=s;}
        void check()throws IOException {parent.check();StructStat current=stat(fd),visible=lstat(parent.access.volume.getPath()+"/"+path);sameIdentity(identity,current);sameIdentity(identity,visible);if(current.st_nlink!=1||visible.st_nlink!=1)throw new IOException("새 파일에 다른 링크가 생겨 쓰기를 중단했어요.");}
        void sync()throws IOException {check();try{Os.fsync(fd.getFileDescriptor());}catch(ErrnoException e){throw io(e);}parent.sync();check();}
        @Override public void close(){quietClose(fd);parent.close();}
    }
    private static void requireTargetPath(String path)throws IOException {if(!path.equals(ROOT_NAME)&&!path.startsWith(ROOT_NAME+"/"))throw new IOException("생성·쓰기는 내장 공유 저장소의 들퍼레 안에서만 가능해요.");}
    private static void requireAllowedPath(String path)throws IOException {if(!path.equals(ROOT_NAME)&&!path.startsWith(ROOT_NAME+"/")&&!path.equals(LEGACY)&&!path.startsWith(LEGACY+"/"))throw new IOException("이 파일 접근 경로는 들퍼레와 승인한 이전 원본 폴더로 제한돼요.");}
    static String safe(String path){if(path==null||path.isEmpty()||path.length()>4096)throw new IllegalArgumentException("안전하지 않은 경로예요.");for(String part:path.split("/",-1))single(part);return path;}
    static void single(String s){if(s==null||s.isEmpty()||s.equals(".")||s.equals("..")||s.indexOf('/')>=0||s.indexOf('\\')>=0||s.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("안전하지 않은 이름이에요.");}
    static String parent(String p){int i=p.lastIndexOf('/');return i<0?"":p.substring(0,i);}
    static String name(String p){return p.substring(p.lastIndexOf('/')+1);}
    static String key(StructStat s){return s.st_dev+":"+s.st_ino+":"+(s.st_mode&OsConstants.S_IFMT);}
    static void sameIdentity(StructStat a,StructStat b)throws IOException {if(!key(a).equals(key(b)))throw new IOException("파일이나 상위 폴더가 바뀌어 중단했어요.");}
    static StructStat stat(ParcelFileDescriptor p)throws IOException {try{return Os.fstat(p.getFileDescriptor());}catch(ErrnoException e){throw io(e);}}
    static StructStat lstat(String path)throws IOException {try{return Os.lstat(path);}catch(ErrnoException e){throw io(e);}}
    static ParcelFileDescriptor open(String path,int flags,int mode)throws IOException {try{FileDescriptor fd=Os.open(path,flags,mode);try{return ParcelFileDescriptor.dup(fd);}finally{Os.close(fd);}}catch(ErrnoException e){throw io(e);}}
    static String anchor(ParcelFileDescriptor fd){return "/proc/self/fd/"+fd.getFd();}
    static IOException io(ErrnoException e){return new IOException("Android 저장소 작업을 완료하지 못했어요: "+e.getMessage(),e);}
    static boolean missing(IOException e){return e.getCause() instanceof ErrnoException x&&x.errno==OsConstants.ENOENT;}
    static void quietClose(Closeable c){if(c!=null)try{c.close();}catch(IOException ignored){}}
}
