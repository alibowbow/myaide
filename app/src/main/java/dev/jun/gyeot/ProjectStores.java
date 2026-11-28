package dev.jun.gyeot;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import dev.jun.gyeot.core.ProjectBook;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Where 프로젝트 live on the phone: 들퍼레/프로젝트, through the connection the user gave the 들퍼레 folder (내 휴대폰 →
 * 저장 위치). With Android's all-files access, the app's own guarded storage (ManagedSharedStorage); with the folder chosen
 * in Android's picker, that folder's documents. null when neither is there: the projects stay in the app until it is.
 */
final class ProjectStores {
    private ProjectStores() {}
    static final String WHERE = "들퍼레/프로젝트", FOLDER = "프로젝트", PAGE = "index.html";

    static ProjectBook.Store current(Context context) {
        Context app = context.getApplicationContext();
        if (SharedOutputFolder.managedSelected(app) && SharedOutputFolder.managedAuthorized(app) && ManagedSharedStorage.available(app)) return new Managed(app);
        try { return new Picked(app, SharedOutputFolder.selectedSafTree(app)); } catch (IOException | RuntimeException none) { return null; }
    }

    /** The app's guarded access to shared storage (all-files access). */
    private record Managed(Context app) implements ProjectBook.Store {
        @Override public List<Item> list() throws IOException {
            List<Item> out = new ArrayList<>();
            try (ManagedSharedStorage.Access access = ManagedSharedStorage.access(app)) { for (String[] p : access.projects()) out.add(new Item(p[0], Long.parseLong(p[1]))); }
            return out;
        }
        @Override public String read(String folder) throws IOException {
            try (ManagedSharedStorage.Access access = ManagedSharedStorage.access(app)) { return new String(access.readProject(folder, ProjectBook.MAX_HTML * 4), StandardCharsets.UTF_8); }
        }
        @Override public long write(String folder, String html) throws IOException {
            try (ManagedSharedStorage.Access access = ManagedSharedStorage.access(app)) { return access.writeProject(folder, html.getBytes(StandardCharsets.UTF_8)); }
        }
        @Override public void rename(String from, String to) throws IOException { try (ManagedSharedStorage.Access access = ManagedSharedStorage.access(app)) { access.renameProject(from, to); } }
        @Override public void delete(String folder) throws IOException { try (ManagedSharedStorage.Access access = ManagedSharedStorage.access(app)) { access.removeProject(folder); } }
        @Override public String where() { return WHERE; }
    }

    /** The 들퍼레 folder the user picked in Android's folder picker (its documents). */
    private record Picked(Context app, Uri tree) implements ProjectBook.Store {
        private ContentResolver resolver() { return app.getContentResolver(); }
        private Uri doc(String id) { return DocumentsContract.buildDocumentUriUsingTree(tree, id); }
        private record Child(String id, String name, boolean dir, long modified) {}
        private List<Child> children(String parent) throws IOException {
            List<Child> out = new ArrayList<>();
            Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
            String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
            try (Cursor c = resolver().query(uri, columns, null, null, null)) {
                if (c == null) throw new IOException("들퍼레 폴더를 읽지 못했어요.");
                while (c.moveToNext()) out.add(new Child(c.getString(0), c.getString(1), DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)), c.isNull(3) ? 0 : c.getLong(3)));
            } catch (SecurityException denied) { throw new IOException("들퍼레 폴더 권한이 없어요.", denied); }
            return out;
        }
        private Child child(String parent, String name) throws IOException { for (Child c : children(parent)) if (name.equals(c.name())) return c; return null; }
        private String projects(boolean make) throws IOException {
            String root = DocumentsContract.getTreeDocumentId(tree);
            Child dir = child(root, FOLDER);
            if (dir != null) { if (!dir.dir()) throw new IOException("들퍼레 안에 ‘프로젝트’라는 파일이 있어 폴더를 만들 수 없어요."); return dir.id(); }
            if (!make) return null;
            Uri made = DocumentsContract.createDocument(resolver(), doc(root), DocumentsContract.Document.MIME_TYPE_DIR, FOLDER);
            if (made == null) throw new IOException("프로젝트 폴더를 만들지 못했어요.");
            return DocumentsContract.getDocumentId(made);
        }
        private static void safe(String folder) throws IOException { if (!ProjectBook.folderName(folder).equals(folder)) throw new IOException("안전하지 않은 폴더 이름이에요."); }
        @Override public List<Item> list() throws IOException {
            List<Item> out = new ArrayList<>(); String dir = projects(false); if (dir == null) return out;
            for (Child c : children(dir)) {
                if (!c.dir() || c.name().startsWith(".")) continue;
                Child page = child(c.id(), PAGE); if (page != null && !page.dir()) out.add(new Item(c.name(), page.modified()));
            }
            return out;
        }
        @Override public String read(String folder) throws IOException {
            safe(folder); String dir = projects(false); Child f = dir == null ? null : child(dir, folder); Child page = f == null ? null : child(f.id(), PAGE);
            if (page == null) throw new IOException("프로젝트 파일을 찾지 못했어요.");
            try (InputStream in = resolver().openInputStream(doc(page.id()))) {
                if (in == null) throw new IOException("프로젝트 파일을 열지 못했어요.");
                byte[] bytes = in.readNBytes(ProjectBook.MAX_HTML * 4 + 1); if (bytes.length > ProjectBook.MAX_HTML * 4) throw new IOException("HTML이 너무 커요.");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        @Override public long write(String folder, String html) throws IOException {
            safe(folder); String dir = projects(true); Child f = child(dir, folder);
            String folderId;
            if (f == null) { Uri made = DocumentsContract.createDocument(resolver(), doc(dir), DocumentsContract.Document.MIME_TYPE_DIR, folder); if (made == null) throw new IOException("프로젝트 폴더를 만들지 못했어요."); folderId = DocumentsContract.getDocumentId(made); }
            else if (!f.dir()) throw new IOException("같은 이름의 파일이 있어요."); else folderId = f.id();
            Child page = child(folderId, PAGE); Uri target;
            if (page == null) { target = DocumentsContract.createDocument(resolver(), doc(folderId), "text/html", PAGE); if (target == null) throw new IOException("프로젝트 파일을 만들지 못했어요."); }
            else target = doc(page.id());
            try (OutputStream out = resolver().openOutputStream(target, "wt")) { if (out == null) throw new IOException("프로젝트 파일에 쓰지 못했어요."); out.write(html.getBytes(StandardCharsets.UTF_8)); }
            Child written = child(folderId, PAGE); return written == null ? System.currentTimeMillis() : written.modified();
        }
        @Override public void rename(String from, String to) throws IOException {
            safe(from); safe(to); String dir = projects(false); Child f = dir == null ? null : child(dir, from); if (f == null) throw new IOException("프로젝트 폴더를 찾지 못했어요.");
            if (child(dir, to) != null) throw new IOException("같은 이름의 프로젝트 폴더가 있어요.");
            if (DocumentsContract.renameDocument(resolver(), doc(f.id()), to) == null) throw new IOException("폴더 이름을 바꾸지 못했어요.");
        }
        @Override public void delete(String folder) throws IOException {
            safe(folder); String dir = projects(false); Child f = dir == null ? null : child(dir, folder); if (f == null) return;
            if (!DocumentsContract.deleteDocument(resolver(), doc(f.id()))) throw new IOException("프로젝트 폴더를 지우지 못했어요.");
        }
        @Override public String where() { return WHERE; }
    }
}
