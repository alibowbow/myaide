package dev.jun.gyeot;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import dev.jun.gyeot.core.GeminiMedia;
import dev.jun.gyeot.core.WorkBook;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Files for 작업창, kept in the app's private storage (AppStore.attachmentFile): what the user attaches (images made smaller
 * and turned upright, PDFs and media copied as they are, text read in) and pictures ChatGPT made. Nothing here goes to the
 * network; blocking, so call it off the main thread.
 */
final class WorkFiles {
    /** Longest side of a picture sent to an AI: plenty for reading text in a screenshot, and small enough to send often. */
    static final int IMAGE_SIDE = 1600;
    private WorkFiles() {}

    /** A file the user picked, as an attachment record ({id, name, mime, kind, size, text?, durationMs?}); refuses what cannot be sent. */
    static JSONObject add(Context context, AppStore store, Uri uri) throws Exception {
        final Uri source;
        try { source=rootInputUri(context,uri); }
        catch(java.io.IOException denied){throw new java.io.IOException("AI 첨부는 들퍼레 폴더 안의 파일만 읽을 수 있어요. 다른 폴더는 목록 검색만 가능해요.",denied);}
        return add(context, store, source, () -> requireRootInput(context,source));
    }
    /**
     * A file the user picked themselves in the system picker (the result of this app's own ACTION_OPEN_DOCUMENT): read once,
     * here, wherever it is, and copied into private storage as every attachment is. Picking it is the user's own grant for
     * that one file. A file inside the 들퍼레 folder still goes through that folder's gate. Nothing is read from the address
     * again, and nothing else may call this: every other reader (the assistant's own file tools, previews, video sources)
     * stays inside the 들퍼레 folder through add(Context, AppStore, Uri).
     */
    static JSONObject addPicked(Context context, AppStore store, Uri uri) throws Exception {
        if (uri == null || !"content".equals(uri.getScheme())) throw new java.io.IOException("파일 선택 화면에서 고른 파일만 첨부할 수 있어요.");
        Uri inside = null;
        try { inside = rootInputUri(context, uri); } catch (Exception elsewhere) { /* another folder: the pick itself is the grant */ }
        if (inside != null) { Uri source = inside; return add(context, store, source, () -> requireRootInput(context, source)); }
        try { return add(context, store, uri, () -> {}); }
        catch (SecurityException gone) { throw new java.io.IOException("이 파일을 읽을 권한이 없어요. 파일 선택 화면에서 다시 골라 주세요.", gone); }
    }
    /** Narrow entry points supply a live content guard; ordinary private attachment behavior is unchanged. */
    static JSONObject add(Context context, AppStore store, Uri uri, Runnable guard) throws Exception {
        guard.run();
        ContentResolver resolver = context.getContentResolver();
        String name = "", mime = resolver.getType(uri); long size = -1;
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) { if (!cursor.isNull(0)) name = cursor.getString(0); if (!cursor.isNull(1)) size = cursor.getLong(1); }
        }
        guard.run();
        if (name == null || name.isBlank()) name = "첨부";
        if (mime == null || mime.isBlank() || mime.equals("application/octet-stream")) {
            String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            mime = guessed == null ? "application/octet-stream" : guessed;
        }
        String kind = WorkBook.kind(mime, name), clean = WorkBook.fileName(name);
        if (kind.isEmpty() || clean == null) throw new IllegalArgumentException("‘" + name + "’은(는) 보낼 수 없는 형식이에요. 사진·PDF·글 파일·영상·음성을 보낼 수 있어요.");
        String id = "a" + UUID.randomUUID().toString().replace("-", "");
        File target = store.attachmentFile(id);
        switch (kind) {
            case WorkBook.TEXT -> {
                String text;
                try (InputStream input = guardedInput(resolver, uri, guard)) { text = readText(input, WorkBook.FILE_TEXT); }
                if (text.isBlank()) throw new IllegalArgumentException("‘" + name + "’에 읽을 글이 없어요.");
                guard.run();
                return obj("id", id, "name", clean, "mime", mime, "kind", kind, "size", Math.max(size, text.length()), "text", text);
            }
            case WorkBook.IMAGE -> {
                byte[] picture;
                try (InputStream input = guardedInput(resolver, uri, guard)) { picture = readAll(input, 60L * 1024 * 1024, name); }
                guard.run();
                boolean png = mime.equals("image/png");
                byte[] small = image(picture, png, orientation(picture));
                guard.run();
                write(target, small);
                guard.run();
                return obj("id", id, "name", clean, "mime", png ? "image/png" : "image/jpeg", "kind", kind, "size", small.length);
            }
            default -> {
                long limit = kind.equals(WorkBook.PDF) ? WorkBook.PDF_LIMIT : WorkBook.MEDIA_LIMIT;
                if (size > limit) throw new IllegalArgumentException("‘" + name + "’이(가) 너무 커요(" + (limit / 1024 / 1024) + "MB까지).");
                long copied;
                try (InputStream input = guardedInput(resolver, uri, guard)) { copied = copy(input, target, limit, name); }
                guard.run();
                JSONObject record = obj("id", id, "name", clean, "mime", mime, "kind", kind, "size", copied);
                if (kind.equals(WorkBook.VIDEO) || kind.equals(WorkBook.AUDIO)) {
                    guard.run();
                    long duration = duration(target);
                    guard.run();
                    if (kind.equals(WorkBook.VIDEO) && duration > GeminiMedia.LONGEST_MS) { target.delete(); throw new IllegalArgumentException("‘" + name + "’은(는) 1시간이 넘어 Gemini가 다 볼 수 없어요. 잘라서 첨부해 주세요."); }
                    if (duration > 0) record.put("durationMs", duration);
                }
                guard.run();
                return record;
            }
        }
    }
    private static void requireRootInput(Context context,Uri uri) {
        try { SharedOutputFolder.requireContentAccess(context,uri); }
        catch(java.io.IOException denied){throw new IllegalStateException("들퍼레 파일 권한이 바뀌어 읽기를 중단했어요.",denied);}
    }
    /** A picker URI is normalized only through an existing real canonical root grant/backend. */
    static Uri rootInputUri(Context context,Uri picked)throws Exception {
        if(picked==null||!"content".equals(picked.getScheme())||picked.getQuery()!=null||picked.getFragment()!=null
            ||!(SharedOutputFolder.AUTHORITY.equals(picked.getAuthority())||ManagedSharedStorage.AUTHORITY.equals(picked.getAuthority())))
            throw new java.io.IOException("들퍼레 폴더 안의 표준 문서 주소만 읽을 수 있어요.");
        if(SharedOutputFolder.canAccess(context,picked))return picked;
        Uri tree=picked!=null&&SharedOutputFolder.AUTHORITY.equals(picked.getAuthority())
            ? SharedOutputFolder.selectedSafTree(context):SharedOutputFolder.selectedTree(context);
        if(picked==null||!"content".equals(picked.getScheme())||!tree.getAuthority().equals(picked.getAuthority())||picked.getQuery()!=null||picked.getFragment()!=null)
            throw new java.io.IOException("들퍼레 폴더 안의 파일만 읽을 수 있어요.");
        String id;try{id=android.provider.DocumentsContract.getDocumentId(picked);}catch(RuntimeException malformed){throw new java.io.IOException("문서 주소를 확인하지 못했어요.",malformed);}
        if(!picked.equals(android.provider.DocumentsContract.buildDocumentUri(tree.getAuthority(),id)))throw new java.io.IOException("들퍼레 폴더 안의 표준 문서 주소가 아니에요.");
        Uri normalized=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,id);SharedOutputFolder.requireContentAccess(context,normalized);return normalized;
    }
    static InputStream guardedInput(ContentResolver resolver, Uri uri, Runnable guard) throws Exception {
        guard.run(); InputStream source=resolver.openInputStream(uri);
        if(source==null)throw new java.io.IOException("파일을 열지 못했어요.");
        try{guard.run();}catch(RuntimeException denied){source.close();throw denied;}
        return new java.io.FilterInputStream(source) {
            @Override public int read() throws java.io.IOException {guard.run();int value=in.read();guard.run();return value;}
            @Override public int read(byte[] bytes,int offset,int length)throws java.io.IOException{guard.run();int count=in.read(bytes,offset,length);guard.run();return count;}
            @Override public long skip(long count)throws java.io.IOException{guard.run();long skipped=in.skip(count);guard.run();return skipped;}
        };
    }
    /** A verified browser body already in private storage. Original bytes remain available by id. */
    static JSONObject browserAttachment(AppStore store, String id, String name, String mime, String kind) throws Exception {
        File file = store.attachmentFile(id);
        if (!file.isFile() || file.length() == 0 || WorkBook.fileName(name) == null) throw new IllegalArgumentException("저장된 파일을 확인하지 못했어요.");
        JSONObject record = obj("id", id, "name", name, "mime", mime, "kind", kind, "size", file.length());
        switch (kind) {
            case WorkBook.TEXT -> {
                try (InputStream input = new java.io.FileInputStream(file)) { put(record, "text", readText(input, WorkBook.FILE_TEXT)); }
            }
            case WorkBook.IMAGE -> {
                byte[] bytes;
                try (InputStream input = new java.io.FileInputStream(file)) { bytes = readAll(input, 60L * 1024 * 1024, name); }
                boolean png = mime.equals("image/png");
                byte[] small = image(bytes, png, orientation(bytes));
                String copy = "a" + UUID.randomUUID().toString().replace("-", "");
                write(store.attachmentFile(copy), small);
                put(record, "id", copy); put(record, "source", id); put(record, "mime", png ? "image/png" : "image/jpeg"); put(record, "size", small.length);
            }
            case WorkBook.VIDEO, WorkBook.AUDIO -> {
                long duration = duration(file);
                if (duration <= 0) throw new IllegalArgumentException("미디어 내용을 확인하지 못해 모델 첨부는 보류했어요.");
                if (duration > GeminiMedia.LONGEST_MS) throw new IllegalArgumentException("미디어가 1시간을 넘어 모델 첨부는 보류했어요.");
                put(record, "durationMs", duration);
            }
            case WorkBook.PDF -> { }
            default -> throw new IllegalArgumentException("이 형식은 저장만 지원하고 모델 내용 분석은 지원하지 않아요.");
        }
        return record;
    }

    /**
     * A picture ChatGPT made, kept small for sending back with the next message (so "make it brighter" edits it) and for
     * the preview: the record of the private copy ({id, mime, size}).
     */
    static JSONObject keepPicture(AppStore store, byte[] picture) throws Exception {
        String id = "g" + UUID.randomUUID().toString().replace("-", "");
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(picture, 0, picture.length, bounds);
        byte[] small = image(picture, true, ExifInterface.ORIENTATION_NORMAL);
        write(store.attachmentFile(id), small);
        return obj("id", id, "mime", "image/png", "size", small.length, "w", Math.max(0, bounds.outWidth), "h", Math.max(0, bounds.outHeight));
    }
    /** A new attachment made from a private picture (a drawing from the gallery), so taking it off the message leaves the original. */
    static JSONObject attachCopy(AppStore store, String id, String name) throws Exception {
        File source = store.attachmentFile(id);
        if (!source.isFile()) throw new IllegalStateException("그림 사본을 찾지 못했어요.");
        String copy = "a" + UUID.randomUUID().toString().replace("-", "");
        File target = store.attachmentFile(copy);
        try (InputStream input = new java.io.FileInputStream(source)) { copy(input, target, 30L * 1024 * 1024, name); }
        return obj("id", copy, "name", WorkBook.fileName(name) == null ? "그림.png" : WorkBook.fileName(name), "mime", "image/png", "kind", WorkBook.IMAGE, "size", target.length(), "source", id);
    }
    /** Decodes a private picture for the screen, about width pixels wide; null when it is gone or unreadable. */
    static Bitmap preview(File file, int width) {
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= width) options.inSampleSize *= 2;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    /** Prefer the saved original for zooming; a removed photo or revoked URI falls back to the private preview. */
    static Bitmap galleryPreview(Context context, String uri, File fallback, int edge) {
        return galleryPreview(context, uri, fallback, edge, () -> requireRootInput(context,Uri.parse(uri == null ? "" : uri)));
    }
    static Bitmap galleryPreview(Context context, String uri, File fallback, int edge, Runnable guard) {
        if (uri != null && !uri.isBlank()) try {
            Uri source = Uri.parse(uri); BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            try (InputStream in = guardedInput(context.getContentResolver(), source, guard)) { BitmapFactory.decodeStream(in, null, bounds); }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > Math.max(512, edge)) options.inSampleSize *= 2;
                try (InputStream in = guardedInput(context.getContentResolver(), source, guard)) { Bitmap result = BitmapFactory.decodeStream(in, null, options); if (result != null) { try { guard.run(); } catch (RuntimeException denied) { result.recycle(); throw denied; } return result; } }
            }
        } catch (Exception ignored) {}
        return preview(fallback, edge);
    }

    /** A square thumbnail size pixels wide, cut from the middle of a private picture; null when it is gone or unreadable. */
    static Bitmap thumb(File file, int size) {
        if (!file.isFile() || size <= 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        int shortest = Math.min(bounds.outWidth, bounds.outHeight);
        while (shortest / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
        if (decoded == null) return null;
        int side = Math.min(decoded.getWidth(), decoded.getHeight());
        float scale = Math.min(1f, (float) size / side);
        Matrix matrix = new Matrix(); matrix.postScale(scale, scale);
        Bitmap square = Bitmap.createBitmap(decoded, (decoded.getWidth() - side) / 2, (decoded.getHeight() - side) / 2, side, side, matrix, true);
        if (square != decoded) decoded.recycle();
        return square;
    }
    /** At most IMAGE_SIDE on the longest side, turned upright; PNG when asked (it keeps transparency), JPEG otherwise. */
    private static byte[] image(byte[] picture, boolean png, int orientation) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(picture, 0, picture.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("사진을 읽지 못했어요. 다른 형식(JPG·PNG)으로 저장해 첨부해 주세요.");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / (options.inSampleSize * 2) >= IMAGE_SIDE) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
        if (bitmap == null) throw new IllegalArgumentException("사진을 읽지 못했어요.");
        float scale = Math.min(1f, (float) IMAGE_SIDE / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        Matrix matrix = new Matrix(); matrix.postScale(scale, scale);
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90);
            case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180);
            case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270);
            default -> {}
        }
        Bitmap shaped = scale < 1f || !matrix.isIdentity() ? Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true) : bitmap;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        shaped.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 85, output);
        if (shaped != bitmap) shaped.recycle();
        bitmap.recycle();
        return output.toByteArray();
    }
    private static int orientation(byte[] picture) {
        try (InputStream input = new java.io.ByteArrayInputStream(picture)) { return new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); }
        catch (Exception e) { return ExifInterface.ORIENTATION_NORMAL; }
    }
    private static long duration(File media) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try { retriever.setDataSource(media.getPath()); String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION); return value == null ? 0 : Long.parseLong(value); }
        catch (Exception e) { return 0; }
        finally { try { retriever.release(); } catch (Exception ignored) {} }
    }
    /** UTF-8 text, at most limit characters (a cut file says so). */
    private static String readText(InputStream input, int limit) throws Exception {
        if (input == null) throw new IllegalStateException("파일을 열지 못했어요.");
        byte[] bytes = input.readNBytes(limit * 4 + 4);
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\u0000", "");
        return text.length() > limit ? text.substring(0, limit) + "\n…(파일이 길어 앞부분만 넣었어요)" : text;
    }
    private static byte[] readAll(InputStream input, long limit, String name) throws Exception {
        if (input == null) throw new IllegalStateException("파일을 열지 못했어요.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[64 * 1024]; int count;
        while ((count = input.read(buffer)) > 0) { if (bytes.size() + count > limit) throw new IllegalArgumentException("‘" + name + "’이(가) 너무 커요."); bytes.write(buffer, 0, count); }
        return bytes.toByteArray();
    }
    private static long copy(InputStream input, File target, long limit, String name) throws Exception {
        if (input == null) throw new IllegalStateException("파일을 열지 못했어요.");
        long total = 0; byte[] buffer = new byte[64 * 1024]; int count;
        try (OutputStream output = new FileOutputStream(target)) {
            while ((count = input.read(buffer)) > 0) {
                total += count;
                if (total > limit) throw new IllegalArgumentException("‘" + name + "’이(가) 너무 커요(" + (limit / 1024 / 1024) + "MB까지).");
                output.write(buffer, 0, count);
            }
        } catch (Exception e) { target.delete(); throw e; }
        if (total == 0) { target.delete(); throw new IllegalArgumentException("‘" + name + "’이(가) 비어 있어요."); }
        return total;
    }
    private static void write(File target, byte[] bytes) throws Exception {
        try (OutputStream output = new FileOutputStream(target)) { output.write(bytes); } catch (Exception e) { target.delete(); throw e; }
    }
}
