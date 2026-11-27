package dev.jun.gyeot;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 내 음악's see-through 파일 추가 (the LP page's sheet, through PieceExtras): Android's own file picker for music files (several
 * at once) or a folder, the read permission kept for good for what was picked, and the rest (names, lengths, pictures) read
 * on PieceMusicStore's thread after this window has closed. Closed without picking, nothing changes; closed without an answer
 * at all (the home button over the picker clears it), the page hears it was cancelled. A permission taken here for what does
 * not come onto the list (not sound, unreadable, past the list's most, a folder with no music) is given back (PieceMusicStore).
 */
public final class MusicPickActivity extends Activity {
    static final String KIND = "kind", FILES = "files", FOLDER = "folder";
    private static final int PICK = 1;
    private boolean asked, answered;

    /** The picker for music files: openable documents of any sound type (ogg files say application/ogg), several at once. */
    static Intent files() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "application/ogg"}).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    }
    static Intent folder() { return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        asked = state != null && state.getBoolean("asked");
        if (asked) return; // recreated while the picker shows: its answer comes here
        asked = true;
        boolean tree = FOLDER.equals(getIntent().getStringExtra(KIND));
        try {
            startActivityForResult(tree ? folder() : files(), PICK);
            PieceMusicStore.of(this).pick("open");
        } catch (ActivityNotFoundException | SecurityException none) {
            PieceMusicStore.of(this).pick("cancelled");
            Toast.makeText(this, "이 휴대폰에서는 파일 고르는 창을 열 수 없어요", Toast.LENGTH_LONG).show();
            finish();
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putBoolean("asked", asked); }
    /** Gone without the picker's answer (the home screen came to front over it): the page stops waiting. */
    @Override protected void onDestroy() {
        if (!answered && !isChangingConfigurations()) PieceMusicStore.of(this).pick("cancelled");
        super.onDestroy();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK) return;
        answered = true;
        PieceMusicStore store = PieceMusicStore.of(this);
        if (result != RESULT_OK || data == null) { store.pick("cancelled"); finish(); return; }
        List<Uri> files = new ArrayList<>(); Uri tree = null;
        boolean folder = FOLDER.equals(getIntent().getStringExtra(KIND));
        if (folder) tree = data.getData();
        else {
            ClipData clip = data.getClipData();
            for (int i = 0; clip != null && i < clip.getItemCount(); i++) if (clip.getItemAt(i).getUri() != null) files.add(clip.getItemAt(i).getUri());
            if (files.isEmpty() && data.getData() != null) files.add(data.getData());
        }
        if (files.isEmpty() && tree == null) { store.pick("cancelled"); finish(); return; }
        // Only the permission here, at once (it may not last once this window is gone); the reading on the store's thread,
        // which gives back what it took for anything that does not come onto the list.
        Set<Uri> held = new HashSet<>();
        try { for (UriPermission p : getContentResolver().getPersistedUriPermissions()) if (p.isReadPermission()) held.add(p.getUri()); } catch (RuntimeException unknown) {}
        List<Uri> taken = new ArrayList<>();
        if (tree != null && keep(tree) && !held.contains(tree)) taken.add(tree);
        for (Uri u : files) if (keep(u) && !held.contains(u)) taken.add(u);
        store.addLater(files, tree, taken);
        finish();
    }
    /** The read permission kept across restarts; false when the provider gives none to keep (it still lends it while the app runs). */
    private boolean keep(Uri uri) {
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); return true; }
        catch (SecurityException notPersistable) { return false; }
    }
}
