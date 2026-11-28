package dev.jun.gyeot;

import android.content.Intent;
import android.net.Uri;
import dev.jun.gyeot.core.BrowserAppLinks;
import java.util.Locale;
import java.util.Set;

/**
 * The Android intent for a link to another app the user tapped on a page (BrowserAppLinks decides when), made as Chrome makes
 * it: an intent:// link is read with Intent.parseUri(URI_INTENT_SCHEME), any other scheme (tel:, mailto:, sms:, market: …) is
 * a VIEW of its address. Either way it can only reach an activity that says it opens from the web (CATEGORY_BROWSABLE); a
 * named component, the selector's component, flags and anything that would grant access to a file (URI grant flags, clip
 * data, EXTRA_STREAM) are dropped; nothing of this app is reached.
 */
final class BrowserAppIntent {
    private BrowserAppIntent() {}
    /** Data an app link may never carry: a file, app data, a script, or this app's own scheme. */
    private static final Set<String> NEVER_DATA = Set.of("file", "content", "javascript", "data", "blob", "about", "resource", "gyeot");

    /** The intent to start, or null when the link is not one to hand over (malformed, aimed at this app, or at a file). */
    static Intent of(String raw, String ownPackage) {
        if (!BrowserAppLinks.appLink(raw)) return null;
        Intent intent;
        try {
            intent = "intent".equals(BrowserAppLinks.scheme(raw)) ? Intent.parseUri(raw, Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, Uri.parse(raw));
        } catch (java.net.URISyntaxException | RuntimeException unreadable) { return null; }
        clean(intent);
        Intent selector = intent.getSelector();
        if (selector != null) clean(selector);
        if (aimsHere(intent, ownPackage) || selector != null && aimsHere(selector, ownPackage)) return null;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
    private static void clean(Intent intent) {
        intent.setComponent(null);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        // No flag the page chose: none of FLAG_GRANT_*_URI_PERMISSION, nor a task flag that would clear another app's screens.
        intent.setFlags(0);
        intent.setClipData(null);
        intent.removeExtra(Intent.EXTRA_STREAM);
    }
    private static boolean aimsHere(Intent intent, String ownPackage) {
        if (ownPackage != null && ownPackage.equals(intent.getPackage())) return true;
        Uri data = intent.getData();
        String scheme = data == null || data.getScheme() == null ? "" : data.getScheme().toLowerCase(Locale.ROOT);
        return NEVER_DATA.contains(scheme);
    }
    /** The package an intent:// link names (for Play when it is not installed), "" when none. */
    static String packageOf(String raw) { return BrowserAppLinks.field(raw, "package").matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") ? BrowserAppLinks.field(raw, "package") : ""; }
}
