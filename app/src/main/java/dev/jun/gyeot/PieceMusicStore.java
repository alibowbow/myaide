package dev.jun.gyeot;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import dev.jun.gyeot.core.PieceMusic;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 내 음악's list, kept in files/piece-music/shelf.json (AtomicFile), with the pictures found in the files
 * (files/piece-music/art/<id>.jpg, 512 px). Read once and kept: read again only when the file's size or time changed. The
 * files are the user's own documents, opened through the read permission they gave with Android's picker (kept for good;
 * given back when a file leaves the list), or the songs of Android's media index the user chose in 휴대폰 노래 (read with the
 * READ_MEDIA_AUDIO permission, asked when they first open it). Reading a picked file's details runs on one background thread,
 * never the main one; the media index is asked on the caller's (the web view's request thread).
 */
final class PieceMusicStore {
    private static PieceMusicStore one;
    /** The app's one store (a new one only for another app's files, as each check in tests/ui has). */
    static synchronized PieceMusicStore of(Context c) {
        Context app = c.getApplicationContext() == null ? c : c.getApplicationContext();
        if (one == null || !one.dir.getParentFile().equals(app.getFilesDir())) one = new PieceMusicStore(app);
        return one;
    }
    /** The one thread that reads picked files (their names, lengths, pictures) and writes the list. */
    static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-piece-music"); t.setDaemon(true); return t; });
    private static final int FOLDER_MOST = 300, ART = 512;

    private final Context app;
    private final File dir, file, art;
    private PieceMusic.Shelf shelf = PieceMusic.EMPTY;
    private long shelfLength = -1, shelfTime = -1;
    private volatile String pick = "", perm = "";

    private PieceMusicStore(Context app) {
        this.app = app;
        dir = new File(app.getFilesDir(), "piece-music"); file = new File(dir, "shelf.json"); art = new File(dir, "art");
    }

    /** The list as now; the file is read only when it changed (or the first time). */
    synchronized PieceMusic.Shelf shelf() {
        long length = file.length(), time = file.lastModified();
        if (length == shelfLength && time == shelfTime) return shelf;
        try { shelf = file.isFile() ? PieceMusic.read(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8)) : PieceMusic.EMPTY; }
        catch (Exception unreadable) { shelf = PieceMusic.EMPTY; }
        shelfLength = length; shelfTime = time;
        return shelf;
    }
    private synchronized PieceMusic.Shelf save(PieceMusic.Shelf next) {
        if (next == shelf) return shelf;
        dir.mkdirs();
        AtomicFile out = new AtomicFile(file); FileOutputStream w = null;
        try { w = out.startWrite(); w.write(PieceMusic.write(next).getBytes(StandardCharsets.UTF_8)); out.finishWrite(w); }
        catch (Exception failed) { if (w != null) out.failWrite(w); return shelf; }
        shelf = next; shelfLength = file.length(); shelfTime = file.lastModified();
        return shelf;
    }
    PieceMusic.Track find(String id) { return shelf().find(id); }
    File art(String id) { return new File(art, id + ".jpg"); }

    /** How the last 파일 추가 is going, for the page: "open", "notification", "cancelled", "done" or "". */
    String pick() { return pick; }
    void pick(String state) { pick = state == null ? "" : state; }
    /** How the last ask for reading the phone's songs went: "asking", "notification" (a tap on it asks), "granted", "denied", "blocked" (asked no more), "cancelled" or "". */
    String perm() { return perm; }
    void perm(String state) { perm = state == null ? "" : state; }

    /**
     * Every sound file Android's media index knows (all volumes), with what it says of each (PieceMusic.song() picks the songs).
     * Needs READ_MEDIA_AUDIO; empty without. Not on the main thread (the web view's request thread asks it).
     */
    List<PieceMusic.Found> scan() {
        List<PieceMusic.Found> out = new ArrayList<>();
        Uri base = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String[] cols = { MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.IS_MUSIC, MediaStore.Audio.Media.IS_RINGTONE, MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_ALARM, MediaStore.Audio.Media.IS_PODCAST, MediaStore.Audio.Media.IS_AUDIOBOOK, MediaStore.Audio.Media.IS_RECORDING };
        int[] bits = { PieceMusic.MUSIC, PieceMusic.RINGTONE, PieceMusic.NOTIFICATION, PieceMusic.ALARM, PieceMusic.PODCAST, PieceMusic.AUDIOBOOK, PieceMusic.RECORDING };
        try (Cursor c = app.getContentResolver().query(base, cols, null, null, null)) {
            if (c == null) return out;
            int[] at = new int[cols.length];
            for (int i = 0; i < cols.length; i++) at[i] = c.getColumnIndex(cols[i]);
            while (c.moveToNext() && out.size() < SCAN_ROWS) {
                if (at[0] < 0 || c.isNull(at[0])) continue;
                long id = c.getLong(at[0]);
                String name = text(c, at[1]), data = text(c, at[5]), rel = text(c, at[6]);
                int flags = 0;
                for (int b = 0; b < bits.length; b++) { int k = at[9 + b]; if (k >= 0 && !c.isNull(k) && c.getInt(k) != 0) flags |= bits[b]; }
                // (no IS_MUSIC column at all: an index that does not tell, taken as music, the other rules still apply)
                if (at[9] < 0) flags |= PieceMusic.MUSIC;
                long dur = at[4] < 0 || c.isNull(at[4]) ? 0 : c.getLong(at[4]), size = at[8] < 0 || c.isNull(at[8]) ? -1 : c.getLong(at[8]);
                String path = !data.isEmpty() ? data : rel;
                out.add(new PieceMusic.Found(android.content.ContentUris.withAppendedId(base, id).toString(), name, path, text(c, at[2]), text(c, at[3]), dur, text(c, at[7]), size, flags));
            }
        } catch (RuntimeException unreadable) { /* no permission, or the index is busy: nothing found */ }
        return out;
    }
    private static String text(Cursor c, int i) { try { return i < 0 || c.isNull(i) ? "" : c.getString(i); } catch (RuntimeException odd) { return ""; } }
    private static final int SCAN_ROWS = 20000;

    /** Songs chosen in 휴대폰 노래 (their media index entries), read and added off the main thread like picked files. */
    void addMediaLater(List<PieceMusic.Found> chosen) {
        try { WORK.execute(() -> {
            try {
                PieceMusic.Shelf now = shelf();
                List<PieceMusic.Track> found = new ArrayList<>();
                for (PieceMusic.Found f : chosen) {
                    if (now.items().size() + found.size() >= PieceMusic.MOST) break;
                    PieceMusic.Track t = read(Uri.parse(f.uri()), null, f.name(), f.size(), now, f);
                    if (t != null) found.add(t);
                }
                synchronized (this) { save(PieceMusic.add(shelf(), found)); }
            } catch (RuntimeException failed) { DiagnosticsExport.record(app, "storage", failed); }
            finally { pick("done"); }
        }); }
        catch (RuntimeException stopped) { pick("cancelled"); }
    }

    /**
     * Files and a folder the user just picked (their read permission already taken: those newly taken in taken), read and added
     * off the main thread; what was taken for anything that did not come onto the list is given back (an app keeps at most 512).
     */
    void addLater(List<Uri> files, Uri tree, List<Uri> taken) {
        try { WORK.execute(() -> {
            try { untake(add(files, tree), taken); }
            catch (RuntimeException failed) { DiagnosticsExport.record(app, "storage", failed); untake(shelf(), taken); }
            finally { pick("done"); }
        }); }
        catch (RuntimeException stopped) { untake(shelf(), taken); pick("cancelled"); }
    }
    /** Gives back each of taken that no track of the list is (a file) or came with (a folder). */
    void untake(PieceMusic.Shelf now, List<Uri> taken) {
        if (taken == null) return;
        ContentResolver cr = app.getContentResolver();
        for (Uri u : taken) {
            String s = u.toString(); boolean used = false;
            for (PieceMusic.Track t : now.items()) if (t.uri().equals(s) || t.tree().equals(s)) { used = true; break; }
            if (!used) try { cr.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (RuntimeException notHeld) {}
        }
    }
    /** Reads each file (and a folder's music, two levels deep), then adds them to the list. On the background thread. */
    PieceMusic.Shelf add(List<Uri> files, Uri tree) {
        List<PieceMusic.Track> found = new ArrayList<>();
        PieceMusic.Shelf now = shelf();
        for (Uri u : files) { PieceMusic.Track t = read(u, null, null, -1, now, null); if (t != null) found.add(t); }
        if (tree != null) found.addAll(folder(tree, now));
        synchronized (this) { return save(PieceMusic.add(shelf(), found)); }
    }
    /**
     * One file: its name and size from its provider, its title, artist, length and picture from the file itself (else what the
     * media index said of it, hint); null when not sound.
     */
    PieceMusic.Track read(Uri uri, Uri tree, String knownName, long knownSize, PieceMusic.Shelf now, PieceMusic.Found hint) {
        if (uri == null || now.has(uri.toString())) return null;
        ContentResolver cr = app.getContentResolver();
        String name = knownName; long size = knownSize;
        if (name == null) {
            try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    name = c.isNull(0) ? null : c.getString(0);
                    size = c.isNull(1) ? -1 : c.getLong(1);
                }
            } catch (RuntimeException unreadable) { /* the name from its address */ }
        }
        if (name == null) name = uri.getLastPathSegment();
        String type = null;
        try { type = cr.getType(uri); } catch (RuntimeException unknown) {}
        if (!PieceMusic.audio(type, name)) return null;
        String id = PieceMusic.id(uri.toString());
        String title = null, artist = "", mime = PieceMusic.mime(type, name); long dur = 0; boolean pic = false;
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(app, uri);
            title = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (a == null || a.isBlank()) a = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            artist = a == null ? "" : a.strip();
            String d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) try { dur = Long.parseLong(d.strip()); } catch (NumberFormatException ignored) {}
            pic = picture(id, m.getEmbeddedPicture());
        } catch (RuntimeException | OutOfMemoryError unreadable) { /* the page learns the length when it plays */ }
        finally { try { m.release(); } catch (Exception ignored) {} }
        if (hint != null) {
            if (title == null || title.isBlank()) title = hint.shownTitle();
            if (artist.isEmpty()) artist = hint.shownArtist();
            if (dur <= 0) dur = hint.durMs();
        }
        String shown = title == null || title.isBlank() ? PieceMusic.title(name) : title.strip();
        return new PieceMusic.Track(id, uri.toString(), tree == null ? "" : tree.toString(), shown, artist, Math.max(0, dur), mime, size, System.currentTimeMillis(), pic);
    }
    private boolean picture(String id, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1; while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= ART) sample *= 2;
            o = new BitmapFactory.Options(); o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (b == null) return false;
            float k = Math.min(1f, ART / (float) Math.max(b.getWidth(), b.getHeight()));
            if (k < 1f) { Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true); if (s != b) b.recycle(); b = s; }
            art.mkdirs();
            try (FileOutputStream out = new FileOutputStream(art(id))) { b.compress(Bitmap.CompressFormat.JPEG, 86, out); }
            b.recycle();
            return true;
        } catch (Exception | OutOfMemoryError unreadable) { return false; }
    }
    /** A folder's sound files, two levels deep, at most FOLDER_MOST. */
    private List<PieceMusic.Track> folder(Uri tree, PieceMusic.Shelf now) {
        List<PieceMusic.Track> out = new ArrayList<>();
        ContentResolver cr = app.getContentResolver();
        ArrayDeque<String[]> todo = new ArrayDeque<>(); // document id, depth
        try { todo.add(new String[]{DocumentsContract.getTreeDocumentId(tree), "0"}); } catch (RuntimeException notATree) { return out; }
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        while (!todo.isEmpty() && out.size() < FOLDER_MOST) {
            String[] at = todo.poll(); int depth = Integer.parseInt(at[1]);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, at[0]);
            List<String[]> files = new ArrayList<>();
            try (Cursor c = cr.query(children, cols, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String doc = c.getString(0), name = c.getString(1), type = c.getString(2);
                    long size = c.isNull(3) ? -1 : c.getLong(3);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) { if (depth < 2) todo.add(new String[]{doc, String.valueOf(depth + 1)}); }
                    else if (PieceMusic.audio(type, name)) files.add(new String[]{doc, name, String.valueOf(size)});
                }
            } catch (RuntimeException unreadable) { continue; }
            files.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a[1] == null ? "" : a[1], b[1] == null ? "" : b[1]));
            for (String[] f : files) {
                if (out.size() >= FOLDER_MOST) break;
                PieceMusic.Track t = read(DocumentsContract.buildDocumentUriUsingTree(tree, f[0]), tree, f[1], Long.parseLong(f[2]), now, null);
                if (t != null) out.add(t);
            }
        }
        return out;
    }

    /** A file off the list; its read permission is given back when no other track and no folder of the list needs it. */
    PieceMusic.Shelf remove(String id) {
        PieceMusic.Track gone; PieceMusic.Shelf next;
        synchronized (this) { gone = shelf().find(id); if (gone == null) return shelf; next = save(PieceMusic.remove(shelf, id)); }
        art(id).delete();
        release(gone, next);
        return next;
    }
    private void release(PieceMusic.Track gone, PieceMusic.Shelf left) {
        ContentResolver cr = app.getContentResolver();
        for (PieceMusic.Track t : left.items()) if (t.uri().equals(gone.uri())) return;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            if (gone.tree().isEmpty()) {
                cr.releasePersistableUriPermission(Uri.parse(gone.uri()), flags);
                return;
            }
            // A folder's permission goes when the last file from it does.
            for (PieceMusic.Track t : left.items()) if (t.tree().equals(gone.tree())) return;
            for (UriPermission p : cr.getPersistedUriPermissions())
                if (p.getUri().toString().equals(gone.tree())) cr.releasePersistableUriPermission(p.getUri(), flags);
        } catch (RuntimeException notHeld) { /* nothing to give back */ }
    }
}
