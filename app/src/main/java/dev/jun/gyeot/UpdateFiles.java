package dev.jun.gyeot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands the verified update APK to Android's package installer, the way the installer expects (a content address it is
 * granted for this one install, read-only). Only update files in the app's own cache can be named; nothing is exported.
 */
public final class UpdateFiles extends ContentProvider {
    static final String AUTHORITY = "dev.jun.gyeot.updates", TYPE = "application/vnd.android.package-archive";

    static File folder(Context context) { return new File(context.getCacheDir(), "updates"); }
    static Uri uri(File apk) { return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(apk.getName()).build(); }
    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null || !name.matches("gyeot-[0-9A-Za-z._-]{1,40}\\.apk")) throw new FileNotFoundException("not an update file");
        File apk = new File(folder(getContext()), name);
        if (!apk.isFile()) throw new FileNotFoundException("update file is gone");
        return apk;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return TYPE; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File apk;
        try { apk = file(uri); } catch (FileNotFoundException e) { return null; }
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? apk.getName() : OpenableColumns.SIZE.equals(columns[i]) ? apk.length() : null;
        MatrixCursor cursor = new MatrixCursor(columns, 1); cursor.addRow(row);
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
