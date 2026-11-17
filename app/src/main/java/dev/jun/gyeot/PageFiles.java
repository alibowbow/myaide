package dev.jun.gyeot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import dev.jun.gyeot.core.PageBook;
import java.io.File;
import java.io.FileNotFoundException;

/**
 * A page's file for 공유: read only, not exported, reachable only through a URI this app grants to the one app
 * the user shares with. Named after the page's title, typed Markdown or HTML.
 */
public final class PageFiles extends ContentProvider {
    static final String AUTHORITY = "dev.jun.gyeot.pages";
    static Uri uri(String id) { return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(id).build(); }
    private PageBook.Page page(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size() != 1 || uri.getQuery() != null || uri.getFragment() != null)
            throw new FileNotFoundException("not a page");
        PageBook.Page page = PageStore.book(getContext()).page(uri.getLastPathSegment());
        if (page == null || !file(page).isFile()) throw new FileNotFoundException("page unavailable");
        return page;
    }
    private File file(PageBook.Page page) { return PageStore.book(getContext()).file(page); }
    static String mime(PageBook.Page page) { return page.html() ? "text/html" : "text/markdown"; }
    /** The title as a file name: no path or reserved characters, never empty. */
    static String name(PageBook.Page page) {
        String base = page.title().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        if (base.length() > 80) base = base.substring(0, 80).strip();
        return (base.isEmpty() || base.startsWith(".") ? "페이지" : base) + page.extension();
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        try { return mime(page(uri)); } catch (FileNotFoundException e) { return "application/octet-stream"; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        return ParcelFileDescriptor.open(file(page(uri)), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        PageBook.Page page;
        try { page = page(uri); } catch (FileNotFoundException e) { return null; }
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? name(page) : OpenableColumns.SIZE.equals(columns[i]) ? file(page).length() : null;
        MatrixCursor result = new MatrixCursor(columns, 1); result.addRow(row); return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
