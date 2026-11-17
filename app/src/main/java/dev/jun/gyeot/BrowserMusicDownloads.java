package dev.jun.gyeot;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import dev.jun.gyeot.core.MusicDownload;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.mozilla.geckoview.WebResponse;

/** Saves the browser's original response stream; never re-fetches signed URLs or copies cookies. */
final class BrowserMusicDownloads {
    static final int REQUEST_FOLDER = 6814;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "music-download"));
    private BrowserMusicDownloads() {}
    static void chooseFolder(Activity activity, int requestCode) { SharedOutputFolder.chooseFolder(activity, requestCode); }
    static String acceptFolder(Context context, Intent data, int resultCode) { return SharedOutputFolder.acceptFolder(context, data, resultCode); }
    static Uri selectedTree(Context context) throws IOException { return SharedOutputFolder.selectedTree(context); }
    static void save(Context context, WebResponse response, Consumer<String> callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            String result;
            try (InputStream body = response.body) {
                if (response.statusCode < 200 || response.statusCode >= 300 || response.statusCode == 206)
                    throw new IOException("다운로드 응답이 완전하지 않아요. 사이트에서 다시 다운로드해 주세요.");
                MusicDownload.FileInfo info = MusicDownload.classify(response.uri, response.headers);
                if (info == null) throw new IOException("음악 파일로 확인되지 않아 저장하지 않았어요. 사이트의 오디오 다운로드를 선택해 주세요.");
                Uri tree = selectedTree(app);
                response.setReadTimeoutMillis(30_000);
                long expected = -1;
                String length = MusicDownload.header(response.headers, "Content-Length");
                String encoding = MusicDownload.header(response.headers, "Content-Encoding");
                if (encoding.isBlank() || encoding.equalsIgnoreCase("identity")) try { expected = Long.parseLong(length); } catch (NumberFormatException ignored) { }
                MusicDownload.save(body, info, expected, new FolderDestination(app, tree));
                result = "저장 완료: 내장 저장공간/들퍼레/음악/" + info.name();
            } catch (SecurityException error) { result = "저장 폴더 권한이 없어요. 음악 저장 폴더를 다시 선택해 주세요."; }
            catch (Exception error) {
                result = error instanceof IOException && error.getMessage() != null ? error.getMessage() : "음악을 저장하지 못했어요. 저장 공간과 폴더 권한을 확인하고 다시 다운로드해 주세요.";
                if (error.getSuppressed().length > 0) result += " 미완성 파일 정리가 실패했을 수 있어 저장 폴더도 확인해 주세요.";
            }
            String message = result;
            new Handler(Looper.getMainLooper()).post(() -> callback.accept(message));
        });
    }
    private static final class FolderDestination implements MusicDownload.Destination {
        final Context context; final ContentResolver resolver; final Uri tree; Uri file; boolean ownsFile;
        FolderDestination(Context context, Uri tree) { this.context = context; this.resolver = context.getContentResolver(); this.tree = tree; }
        private void check(Uri document) throws IOException {
            if (!tree.equals(SharedOutputFolder.selectedTree(context))) throw new IOException("저장 폴더가 변경되어 중단했어요.");
            SharedOutputFolder.requireContentAccess(context, document);
        }
        public OutputStream create(MusicDownload.FileInfo info) throws Exception {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId), folder = null;
            check(parent);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
            try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null) throw new IOException("저장 폴더를 읽지 못했어요.");
                while (cursor.moveToNext()) if ("음악".equals(cursor.getString(1))) {
                    if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) throw new IOException("음악이라는 파일이 있어 폴더를 만들지 못했어요.");
                    folder = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)); break;
                }
            }
            if (folder == null) { check(parent); folder = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, "음악"); }
            if (folder == null) throw new IOException("음악 폴더를 만들지 못했어요.");
            check(folder);
            if (!(rootId + "/음악").equals(DocumentsContract.getDocumentId(folder))) throw new IOException("음악 폴더 위치를 확인하지 못했어요.");
            java.util.Set<String> existing = new java.util.HashSet<>();
            try (Cursor cursor = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder)),
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null) throw new IOException("음악 폴더를 읽지 못했어요.");
                while (cursor.moveToNext()) {
                    existing.add(cursor.getString(0));
                    if (info.name().equals(cursor.getString(1))) throw new IOException("같은 이름이 있어 덮어쓰지 않았어요.");
                }
            }
            check(folder);
            file = DocumentsContract.createDocument(resolver, folder, info.mime(), info.name());
            if (file == null) throw new IOException("음악 파일을 만들지 못했어요.");
            check(file);
            if (!(DocumentsContract.getDocumentId(folder) + "/" + info.name()).equals(DocumentsContract.getDocumentId(file))
                || existing.contains(DocumentsContract.getDocumentId(file)) || file.equals(folder)) throw new IOException("새 파일 위치를 확인하지 못해 쓰기를 중단했어요.");
            ownsFile = true;
            check(file);
            OutputStream opened=resolver.openOutputStream(file,"w");
            if(opened==null)throw new IOException("새 음악 파일을 열지 못했어요.");
            return new FilterOutputStream(opened){
                @Override public void write(int value)throws IOException{check(file);out.write(value);check(file);}
                @Override public void write(byte[] bytes,int offset,int length)throws IOException{check(file);out.write(bytes,offset,length);check(file);}
                @Override public void flush()throws IOException{check(file);out.flush();check(file);}
                @Override public void close()throws IOException{IOException denied=null;try{check(file);}catch(IOException error){denied=error;}try{out.close();}catch(IOException error){if(denied==null)denied=error;else denied.addSuppressed(error);}if(denied!=null)throw denied;check(file);}
            };
        }
        public void commit() throws Exception { check(file); /* Byte-length and successful close, not a SHA-256/durability receipt. */ }
        public void rollback() throws Exception {
            if (file != null && ownsFile) { check(file); if(ManagedSharedStorage.AUTHORITY.equals(file.getAuthority())) throw new IOException("미완성 음악 사본은 보존했어요. 앱 저장 폴더는 자동 삭제하지 않아요."); if (!DocumentsContract.deleteDocument(resolver, file)) throw new IOException("미완성 파일을 정리하지 못했어요."); }
        }
    }
}
