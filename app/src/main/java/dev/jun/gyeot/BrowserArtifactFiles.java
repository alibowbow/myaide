package dev.jun.gyeot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import dev.jun.gyeot.core.BrowserArtifactPolicy;
import java.io.File;
import java.io.FileNotFoundException;
import org.json.JSONObject;

/** Read-only, explicitly URI-granted access to browser files already persisted in a Work room. */
public final class BrowserArtifactFiles extends ContentProvider {
    static final String AUTHORITY = "dev.jun.gyeot.browser-artifacts";
    static Uri uri(String id) { return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(id).build(); }
    private AppStore store() { return ((GyeotApp) getContext().getApplicationContext()).store(); }
    private JSONObject record(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size() != 1 || uri.getQuery() != null || uri.getFragment() != null)
            throw new FileNotFoundException("not a browser artifact");
        JSONObject record = BrowserArtifactPolicy.stored(store().works(), uri.getLastPathSegment());
        if (record == null || !store().attachmentFile(record.optString("id")).isFile()) throw new FileNotFoundException("browser artifact unavailable");
        return record;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        try { return record(uri).optString("mime", "application/octet-stream"); }
        catch (FileNotFoundException e) { return "application/octet-stream"; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        JSONObject saved = record(uri);
        File file = store().attachmentFile(saved.optString("id"));
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        JSONObject saved;
        try { saved = record(uri); } catch (FileNotFoundException e) { return null; }
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? saved.optString("name") :
            OpenableColumns.SIZE.equals(columns[i]) ? store().attachmentFile(saved.optString("id")).length() : null;
        MatrixCursor result = new MatrixCursor(columns, 1); result.addRow(row); return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
