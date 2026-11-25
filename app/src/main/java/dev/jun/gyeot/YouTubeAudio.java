package dev.jun.gyeot;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A YouTube video's sound, taken in the app's own Linux (LinuxRuntime) with yt-dlp: as a small file for Groq Whisper to write
 * down when the video has no captions (YouTubeTranscriptClient), or as an MP3 kept in 들퍼레/마이튜브 오디오 (ffmpeg makes it).
 * yt-dlp is the single-file release from its own GitHub page, so it is always the newest; when YouTube changes and a fetch
 * fails, it updates itself (yt-dlp -U) and tries once more. Blocking; never on the main thread.
 */
final class YouTubeAudio {
    private YouTubeAudio() {}
    private static final String BIN = "usr/local/bin/yt-dlp";
    private static final Map<String, String> ENV = Map.of("HOME", "/root", "PYTHONUNBUFFERED", "1");

    /** yt-dlp is in the Linux (with Python): enough for Whisper's audio. */
    static boolean ready(Context c) {
        if (!LinuxRuntime.installed(c)) return false;
        File root = LinuxRuntime.rootfs(c);
        return new File(root, BIN).isFile() && new File(root, "usr/bin/python3").exists();
    }
    /** ffmpeg too: an MP3 can be made. */
    static boolean mp3Ready(Context c) { return ready(c) && new File(LinuxRuntime.rootfs(c), "usr/bin/ffmpeg").exists(); }
    /** What 유튜브 오디오 도구 준비 runs in the terminal: Python and ffmpeg by pacman, then yt-dlp's newest release. */
    static String installCommand() {
        return LabPython.pacman("python ffmpeg")
            + "mkdir -p /usr/local/bin; ok=; for try in 1 2 3; do if curl -fL --retry 2 -o /usr/local/bin/yt-dlp.new https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp; "
            + "then mv /usr/local/bin/yt-dlp.new /usr/local/bin/yt-dlp; ok=1; break; fi; echo \"다시 받아요 ($try/3)\"; sleep 5; done; [ -n \"$ok\" ]; "
            + "chmod +x /usr/local/bin/yt-dlp; echo \"yt-dlp $(yt-dlp --version)\"; echo '유튜브 오디오 도구 준비 완료'";
    }

    /**
     * What yt-dlp knows of the video (yt-dlp -J, nothing downloaded), for its caption addresses: it asks as YouTube's TV and
     * other apps do, whose caption files need no token, where the page's own come back empty. Kept in a file, not the output.
     */
    static org.json.JSONObject info(Context c, String id, AtomicReference<Runnable> cancel) throws Exception {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("유튜브 영상 주소를 확인해 주세요.");
        if (!ready(c)) throw new IllegalStateException("유튜브 오디오 도구가 아직 없어요.");
        File dir = new File(LinuxRuntime.rootfs(c), "root/yt/info"); dir.mkdirs();
        File out = new File(dir, id + ".json"); out.delete();
        String once = "timeout 50 yt-dlp -J --skip-download --no-warnings --no-playlist 'https://www.youtube.com/watch?v=" + id + "' > /root/yt/info/" + id + ".json";
        LinuxRuntime.Result r;
        try { r = LinuxRuntime.run(c, "{ " + once + " || { yt-dlp -U >/dev/null 2>&1; " + once + "; }; }", 120_000, ENV, cancel); }
        catch (Exception failed) { out.delete(); throw failed; }
        try {
            if (out.length() > 24L * 1024 * 1024) throw new IOException("yt-dlp가 준 영상 정보가 너무 커요.");
            String text = out.isFile() ? new String(java.nio.file.Files.readAllBytes(out.toPath()), java.nio.charset.StandardCharsets.UTF_8).strip() : "";
            // On a refusal yt-dlp writes "null" and says why on its error output (a sign-in check, an age limit …).
            if (!text.startsWith("{")) {
                String said = LinuxTerminal.plain(r.output()).strip(); if (said.length() > 300) said = said.substring(said.length() - 300);
                throw new IOException("yt-dlp가 영상 정보를 주지 않았어요" + (said.isEmpty() ? "." : ": " + said));
            }
            return new org.json.JSONObject(text);
        } finally { out.delete(); }
    }
    /** The small sound for Whisper (m4a or opus at about 50 kbps: an hour is about 22 MB, under Groq's 25 MB). */
    static File forTranscript(Context c, String id, AtomicReference<Runnable> cancel) throws Exception {
        return fetch(c, id, "root/yt/whisper", "-f '139/249/250/140/251'", 330_000, cancel);
    }
    /** The best sound as an MP3 (VBR about 190 kbps). */
    static File mp3(Context c, String id, AtomicReference<Runnable> cancel) throws Exception {
        if (!mp3Ready(c)) throw new IllegalStateException("MP3를 만들 도구(ffmpeg)가 아직 없어요.");
        File out = fetch(c, id, "root/yt/mp3", "-f 'bestaudio/best' -x --audio-format mp3 --audio-quality 2", 900_000, cancel);
        if (!out.getName().endsWith(".mp3")) throw new IOException("MP3로 바꾸지 못했어요.");
        return out;
    }
    /** Removes what fetch left for this video (after it was sent or copied). */
    static void forget(File file) { if (file != null && !file.delete()) file.deleteOnExit(); }

    private static File fetch(Context c, String id, String folder, String format, long timeoutMs, AtomicReference<Runnable> cancel) throws Exception {
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException("유튜브 영상 주소를 확인해 주세요.");
        if (!ready(c)) throw new IllegalStateException("유튜브 오디오 도구가 아직 없어요.");
        File dir = new File(LinuxRuntime.rootfs(c), folder); dir.mkdirs();
        File[] old = dir.listFiles((d, name) -> name.startsWith(id + ".")); if (old != null) for (File f : old) f.delete();
        long seconds = timeoutMs / 1000 / 2 - 5;
        String once = "timeout " + seconds + " yt-dlp -q --no-warnings --no-playlist --no-progress --no-mtime " + format + " -o '/" + folder + "/%(id)s.%(ext)s' 'https://www.youtube.com/watch?v=" + id + "'";
        LinuxRuntime.Result r = LinuxRuntime.run(c, "cd /" + folder + " && { " + once + " || { yt-dlp -U >/dev/null 2>&1; " + once + "; }; }", timeoutMs, ENV, cancel);
        File[] made = dir.listFiles((d, name) -> name.startsWith(id + ".") && !name.endsWith(".part") && !name.endsWith(".ytdl") && !name.contains(".temp"));
        File best = null;
        if (made != null) for (File f : made) if (f.length() > 0 && (best == null || f.getName().endsWith(".mp3"))) best = f;
        if (best == null) {
            String said = LinuxTerminal.plain(r.output()).strip();
            if (said.length() > 300) said = said.substring(said.length() - 300);
            throw new IOException("소리를 받지 못했어요" + (said.isEmpty() ? "." : ": " + said));
        }
        return best;
    }
    /** The type Groq and Android know the file by. */
    static String mime(File f) {
        String n = f.getName();
        return n.endsWith(".mp3") ? "audio/mpeg" : n.endsWith(".webm") ? "audio/webm" : n.endsWith(".opus") || n.endsWith(".ogg") ? "audio/ogg" : "audio/mp4";
    }
}
