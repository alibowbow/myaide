package dev.jun.gyeot;

import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** An on-demand inventory of single APKs. Never requests additional storage privileges. */
final class SecurityDownloads {
    static final String MIME="application/vnd.android.package-archive";
    record Item(String name, Uri uri, long bytes, long modified) {}
    record Listing(List<Item> files, boolean partial) {}
    static boolean apkName(String name) { return name!=null && name.toLowerCase(Locale.ROOT).endsWith(".apk"); }
    static Listing list(Context c) throws IOException {
        File downloads=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        ArrayList<Item> found=new ArrayList<>();
        boolean partial=true;
        if(Environment.isExternalStorageManager() && downloads.isDirectory() && downloads.canRead()) {
            // The public Downloads folder itself. Other locations are available via the document picker.
            try(var paths=Files.newDirectoryStream(downloads.toPath())) {
                int seen=0; partial=false;
                for(Path p:paths) {
                    if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    if(++seen>5000) { partial=true; break; }
                    File f=p.toFile();
                    if(apkName(f.getName()) && Files.isRegularFile(p,java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        found.add(new Item(f.getName(),Uri.fromFile(f),f.length(),f.lastModified()));
                }
            }
        } else {
            Uri root=MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String[] cols={"_id","_display_name","_size","date_modified"};
            try(var cursor=c.getContentResolver().query(root,cols,"relative_path = ? AND _display_name LIKE ?",
                    new String[]{Environment.DIRECTORY_DOWNLOADS+"/","%.apk"},"date_modified DESC")) {
                if(cursor==null) throw new IOException();
                while(cursor.moveToNext() && found.size()<100) {
                    if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    String name=cursor.getString(1);
                    if(apkName(name)) found.add(new Item(name,ContentUris.withAppendedId(root,cursor.getLong(0)),cursor.getLong(2),cursor.getLong(3)*1000));
                }
            }
        }
        found.sort(Comparator.comparingLong(Item::modified).reversed());
        return new Listing(List.copyOf(found.subList(0,Math.min(100,found.size()))),partial || found.size()>100);
    }
}
