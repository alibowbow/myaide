package dev.jun.gyeot;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AtomicFile;
import dev.jun.gyeot.core.AvatarCharacter;
import dev.jun.gyeot.core.AvatarSpec;
import dev.jun.gyeot.core.BotAvatar;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Where the living avatar is kept (a small JSON file next to the profile, never uploaded) and what it is made from: the
 * open-bot-avatar shapes bundled in the app, the design prompt for shapes an AI draws, and the profile picture as a
 * character. The profile file itself is unchanged, so an older app version still reads the profile.
 */
final class AssistantAvatar {
    private static final Object STORE = new Object();
    private AssistantAvatar() {}

    static File file(Context c) { return new File(c.getFilesDir(), "assistant-avatar-v1.json"); }

    /** The saved avatar, or null when none is set or the file cannot be trusted (then the plain profile picture shows). */
    static AvatarSpec read(Context c) {
        synchronized (STORE) {
            AtomicFile file = new AtomicFile(file(c));
            if (!file.getBaseFile().exists()) return null;
            try (InputStream in = file.openRead()) {
                byte[] bytes = in.readNBytes(AvatarSpec.MAX_JSON + 1);
                if (bytes.length > AvatarSpec.MAX_JSON) return null;
                return AvatarSpec.parse(new String(bytes, StandardCharsets.UTF_8));
            } catch (Exception | OutOfMemoryError e) { return null; }
        }
    }
    static void save(Context c, AvatarSpec spec) throws IOException {
        byte[] bytes = spec.json().getBytes(StandardCharsets.UTF_8);
        AvatarSpec.parse(new String(bytes, StandardCharsets.UTF_8)); // never write what could not be read back
        synchronized (STORE) {
            AtomicFile file = new AtomicFile(file(c)); FileOutputStream out = null;
            try { out = file.startWrite(); out.write(bytes); out.flush(); file.finishWrite(out); out = null; }
            finally { if (out != null) file.failWrite(out); }
        }
    }
    static void clear(Context c) { synchronized (STORE) { new AtomicFile(file(c)).delete(); } }

    /** A checksum of the exact profile picture a picture character was traced from. */
    static String checksum(byte[] picture) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(picture == null ? new byte[0] : picture);
            StringBuilder out = new StringBuilder(); for (int i = 0; i < 12; i++) out.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** One shape the editor offers: the definition and its own colour. */
    record Choice(JSONObject definition, String color) {}
    /** The engine's built-in starters, then the gallery, each once, in their own colours. */
    static List<Choice> choices(Context c) {
        List<Choice> out = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>(); JSONObject colors = new JSONObject();
        JSONArray gallery = new JSONArray();
        try { JSONObject bundle = new JSONObject(asset(c, "avatar/gallery.json")); colors = bundle.optJSONObject("colors") == null ? colors : bundle.optJSONObject("colors"); gallery = bundle.optJSONArray("avatars") == null ? gallery : bundle.optJSONArray("avatars"); }
        catch (Exception ignored) {}
        for (BotAvatar.Definition d : BotAvatar.examples()) if (seen.add(d.id())) out.add(new Choice(d.json(), colors.optString(d.id(), BotAvatar.DEFAULT_COLOR)));
        for (int i = 0; i < gallery.length(); i++) {
            JSONObject d = gallery.optJSONObject(i); if (d == null || !BotAvatar.validate(d).isEmpty() || !seen.add(d.optString("id"))) continue;
            out.add(new Choice(d, colors.optString(d.optString("id"), BotAvatar.DEFAULT_COLOR)));
        }
        return out;
    }
    /** open-bot-avatar's model-neutral design prompt (assets/avatar/system-prompt.md). */
    static String designPrompt(Context c) throws IOException { return asset(c, "avatar/system-prompt.md"); }
    private static String asset(Context c, String name) throws IOException {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16 * 1024]; int n;
            while ((n = in.read(buffer)) > 0) { out.write(buffer, 0, n); if (out.size() > 256 * 1024) throw new IOException("asset too large"); }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** Traces a picture (scaled to at most 512 px) into a character. Throws IllegalArgumentException with the reason. */
    static AvatarCharacter.Result trace(Bitmap picture) {
        if (picture == null || picture.getWidth() < 16 || picture.getHeight() < 16) throw new IllegalArgumentException("먼저 프로필 사진을 정해 주세요.");
        float scale = Math.min(1f, 512f / Math.max(picture.getWidth(), picture.getHeight()));
        Bitmap work = scale < 1 ? Bitmap.createScaledBitmap(picture, Math.max(16, Math.round(picture.getWidth() * scale)), Math.max(16, Math.round(picture.getHeight() * scale)), true) : picture;
        try {
            Bitmap argb = work.getConfig() == Bitmap.Config.ARGB_8888 ? work : work.copy(Bitmap.Config.ARGB_8888, false);
            try {
                int w = argb.getWidth(), h = argb.getHeight(); int[] pixels = new int[w * h];
                argb.getPixels(pixels, 0, w, 0, 0, w, h);
                return AvatarCharacter.trace(pixels, w, h);
            } finally { if (argb != work) argb.recycle(); }
        } finally { if (work != picture) work.recycle(); }
    }
}
