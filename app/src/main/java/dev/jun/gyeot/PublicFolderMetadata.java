package dev.jun.gyeot;

import android.net.Uri;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;

/** OS-visible directory entries only. No file-byte open, MIME sniffing, thumbnails or mutation. */
final class PublicFolderMetadata {
    static final long TIMEOUT_NANOS = 30_000_000_000L;
    final Path directory;
    final String volumeId, label, root;
    final Uri tree;
    interface RootCheck { void validate() throws IOException; }
    interface DirectoryOpener { DirectoryStream<Path> open(Path root) throws IOException; }
    private final RootCheck rootCheck;
    private final DirectoryOpener opener;
    private final Object rootKey;
    private int skipped;
    private boolean limited;

    /** The caller supplies an OS-discovered trusted volume root; tests use an isolated synthetic volume. */
    PublicFolderMetadata(Path trustedVolumeRoot, String volumeId, String label) throws IOException {
        this(trustedVolumeRoot,volumeId,label,()->{});
    }
    PublicFolderMetadata(Path trustedVolumeRoot, String volumeId, String label, RootCheck rootCheck) throws IOException {
        this(trustedVolumeRoot,volumeId,label,rootCheck,Files::newDirectoryStream);
    }
    PublicFolderMetadata(Path trustedVolumeRoot, String volumeId, String label, RootCheck rootCheck, DirectoryOpener opener) throws IOException {
        if (!volumeId.matches("[A-Za-z0-9_-]+")) throw new IOException("저장소 식별자를 확인하지 못했어요.");
        this.directory=trustedVolumeRoot.toFile().getCanonicalFile().toPath();
        this.volumeId=volumeId; this.label=label; this.root="public:"+volumeId;
        this.tree=Uri.parse("myaide-public://volume/"+volumeId);this.rootCheck=rootCheck;this.opener=opener;
        BasicFileAttributes initial=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!initial.isDirectory()||initial.isSymbolicLink())throw new IOException("공유 저장소 루트가 폴더가 아니에요.");
        this.rootKey=initial.fileKey();
    }
    static boolean isTree(Uri uri) {
        if(uri==null)return false;
        return uri.toString().matches("myaide-public://volume/[A-Za-z0-9_-]+");
    }
    Uri document(String id) { return Uri.parse(tree+"/entry/"+Uri.encode(id)); }
    boolean partial() { return skipped>0||limited; }
    String notice() {
        return "Android가 현재 표시 가능한 항목이에요. 운영체제가 숨기거나 차단한 항목은 포함되지 않을 수 있어요. 파일 종류는 확장자로 추정해요."
            +(skipped>0?" 접근 제한·링크·비공개 경로 "+skipped+"개는 제외했어요.":"")
            +(limited?" 검색 목록 한도에 도달해 일부 항목만 확인했어요.":"");
    }
    private List<String> segments(String id) throws IOException {
        if(root.equals(id))return List.of();
        if(id==null||!id.startsWith(root+"/"))throw new IOException("이 공유 저장소 밖의 경로는 탐색하지 않아요.");
        List<String> parts=Arrays.asList(id.substring(root.length()+1).split("/",-1));
        for(String part:parts)if(part.isEmpty()||part.equals(".")||part.equals("..")||part.indexOf('\\')>=0||part.chars().anyMatch(Character::isISOControl))throw new IOException("안전하지 않은 폴더 경로예요.");
        if(privatePath(parts))throw new IOException("Android 비공개 앱 폴더는 탐색하지 않아요.");
        return parts;
    }
    private static boolean privatePath(List<String> parts) {
        if(parts.isEmpty())return false;
        if(parts.get(0).equalsIgnoreCase(".android_secure"))return true;
        return parts.size()>1&&parts.get(0).equalsIgnoreCase("Android")
            &&(parts.get(1).equalsIgnoreCase("data")||parts.get(1).equalsIgnoreCase("obb"));
    }
    void requireMetadataAccess(String id) throws IOException {
        segments(id);rootCheck.validate();
        BasicFileAttributes attrs=Files.readAttributes(directory,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isDirectory()||attrs.isSymbolicLink()||(rootKey!=null&&!rootKey.equals(attrs.fileKey()))||!directory.equals(directory.toFile().getCanonicalFile().toPath()))throw new IOException("공유 저장소가 분리됐거나 경로가 변경됐어요. 위치를 다시 열어 주세요.");
    }
    private SecureDirectoryStream<Path> open(String id) throws IOException {
        List<String> parts=segments(id);requireMetadataAccess(id);
        DirectoryStream<Path> first=opener.open(directory);
        if(!(first instanceof SecureDirectoryStream<?>)) {first.close();throw new IOException("이 기기는 안전한 공개 폴더 목록 접근을 지원하지 않아요. Android 폴더 선택기로 허용한 위치를 이용해 주세요.");}
        @SuppressWarnings("unchecked") SecureDirectoryStream<Path> current=(SecureDirectoryStream<Path>)first;
        try {
            requireMetadataAccess(id); // Recheck the trusted mount immediately after the initial open.
            BasicFileAttributeView openedView=current.getFileAttributeView(BasicFileAttributeView.class);
            if(openedView==null)throw new IOException("공유 저장소의 열린 경로를 검증하지 못했어요.");
            BasicFileAttributes opened=openedView.readAttributes();
            if(!opened.isDirectory()||(rootKey!=null&&!rootKey.equals(opened.fileKey())))throw new IOException("공유 저장소가 열리는 동안 변경되어 중단했어요.");
            for(String part:parts) {
                SecureDirectoryStream<Path> next=current.newDirectoryStream(Path.of(part),LinkOption.NOFOLLOW_LINKS);
                SecureDirectoryStream<Path> previous=current;current=next;previous.close();
            }
            return current;
        } catch(IOException|RuntimeException failure) {current.close();throw failure;}
    }
    private static void check(BooleanSupplier cancelled,long deadline) throws IOException {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("폴더 목록 조회를 취소했어요. 파일은 변경하지 않았어요.");
        if(System.nanoTime()-deadline>=0)throw new IOException("공개 폴더 응답이 오래 걸려 중단했어요. Android가 현재 허용하는 더 작은 폴더에서 다시 열어 주세요.");
    }
    FileExplorerRepository.Page browse(String parent,FileExplorerRepository.Entry after,int limit,int sort,BooleanSupplier cancelled)throws IOException {
        if(limit<1||limit>2000)throw new IOException("잘못된 목록 크기예요.");
        segments(parent);if(after!=null)segments(after.id());skipped=0;limited=false;
        Comparator<FileExplorerRepository.Entry> order=FileExplorerRepository.browseOrder(sort);
        PriorityQueue<FileExplorerRepository.Entry> selected=new PriorityQueue<>(limit+1,order.reversed());
        long deadline=System.nanoTime()+TIMEOUT_NANOS;check(cancelled,deadline);
        try(SecureDirectoryStream<Path> stream=open(parent)) {
            Iterator<Path> iterator=stream.iterator();
            while(true) {
                check(cancelled,deadline);if(!iterator.hasNext())break;Path next=iterator.next();check(cancelled,deadline);
                String name=next.getFileName().toString(),id=parent+"/"+name;
                BasicFileAttributes attrs;
                try {
                    segments(id);
                    BasicFileAttributeView view=stream.getFileAttributeView(Path.of(name),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
                    if(view==null)throw new IOException("목록 속성 없음");attrs=view.readAttributes();
                    if(attrs.isSymbolicLink()||(!attrs.isDirectory()&&!attrs.isRegularFile())){skipped++;continue;}
                } catch(IOException|SecurityException denied) {skipped++;continue;}
                FileExplorerRepository.Entry entry=new FileExplorerRepository.Entry(id,name,attrs.isDirectory()?FileExplorerRepository.DIR:mimeFromName(name),
                    attrs.isDirectory()?-1:attrs.size(),attrs.lastModifiedTime().toMillis(),document(id),parent);
                if(after!=null&&order.compare(entry,after)<=0)continue;
                if(selected.size()<limit+1)selected.add(entry);else if(order.compare(entry,selected.peek())<0){selected.remove();selected.add(entry);}
            }
        }catch(DirectoryIteratorException failure){throw new IOException("Android가 이 폴더 목록 접근을 제한했어요. 다른 위치나 시스템 폴더 선택기를 이용해 주세요.",failure.getCause());}
        catch(AccessDeniedException|SecurityException failure){throw new IOException("Android가 이 폴더 목록 접근을 제한했어요. 다른 위치나 시스템 폴더 선택기를 이용해 주세요.",failure);}
        requireMetadataAccess(parent);
        List<FileExplorerRepository.Entry> entries=new ArrayList<>(selected);entries.sort(order);boolean more=entries.size()>limit;if(more)entries.remove(entries.size()-1);
        return new FileExplorerRepository.Page(List.copyOf(entries),more);
    }
    List<FileExplorerRepository.Entry> list(String parent,BooleanSupplier cancelled)throws IOException {
        FileExplorerRepository.Page page=browse(parent,null,2000,0,cancelled);limited=page.more();return page.entries();
    }
    FileExplorerRepository.Entry findChild(String parent,String wanted,BooleanSupplier cancelled)throws IOException {
        segments(wanted);
        // Bounded memory; one metadata pass is enough by matching an exact direct child path.
        if(!wanted.startsWith(parent+"/")||wanted.substring(parent.length()+1).contains("/"))return null;
        long deadline=System.nanoTime()+TIMEOUT_NANOS;check(cancelled,deadline);
        try(SecureDirectoryStream<Path> stream=open(parent)) {
            String name=wanted.substring(parent.length()+1);check(cancelled,deadline);
            BasicFileAttributeView view=stream.getFileAttributeView(Path.of(name),BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes attrs=view.readAttributes();check(cancelled,deadline);
            if(attrs.isSymbolicLink()||!attrs.isDirectory())return null;
            requireMetadataAccess(parent);
            return new FileExplorerRepository.Entry(wanted,name,FileExplorerRepository.DIR,-1,attrs.lastModifiedTime().toMillis(),document(wanted),parent);
        }catch(NoSuchFileException missing){return null;}
    }
    private static String mimeFromName(String name) {
        int at=name.lastIndexOf('.');String ext=at<0?"":name.substring(at+1).toLowerCase(Locale.ROOT);
        return switch(ext){case "mp3"->"audio/mpeg";case "m4a"->"audio/mp4";case "aac"->"audio/aac";case "wav"->"audio/wav";case "flac"->"audio/flac";case "ogg","opus"->"audio/ogg";
            case "mp4","m4v"->"video/mp4";case "webm"->"video/webm";case "mkv"->"video/x-matroska";case "mov"->"video/quicktime";
            case "jpg","jpeg"->"image/jpeg";case "png"->"image/png";case "gif"->"image/gif";case "webp"->"image/webp";case "heic"->"image/heic";
            case "pdf"->"application/pdf";case "txt","md","log"->"text/plain";case "csv"->"text/csv";case "json"->"application/json";case "html","htm"->"text/html";case "zip"->"application/zip";
            case "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";case "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";case "pptx"->"application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default->"application/octet-stream";};
    }
}
