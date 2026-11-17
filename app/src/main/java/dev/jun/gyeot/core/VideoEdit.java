package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The phone's own video editor for every Work model (edit_video), and the phone-side web download that feeds it
 * (download_file). Work models with a code sandbox (Claude's code execution) have no internet, no ffmpeg and no access to the
 * phone, so joining clips, narration, music and captions into one MP4 happens on the phone (Media3 Transformer, VideoEditor).
 *
 * This class is host-testable: it declares the tools, checks arguments, parses captions and turns a checked request plus
 * the phone's probe of each source into an exact timeline. Captions are burned in by cutting scenes at caption boundaries,
 * so every piece carries one fixed caption picture and nothing depends on how the encoder reports frame times.
 */
public final class VideoEdit {
    public static final String EDIT = "edit_video", DOWNLOAD = "download_file";
    public static final int SCENES = 120, TRACKS = 6, CAPTIONS = 600, CAPTION_CHARS = 240, CARD_CHARS = 300, SOURCE_CHARS = 2000;
    /** Longest output, longest picture/card scene, shortest scene or caption piece (about one frame at 30 fps). */
    public static final long MAX_MS = 20 * 60_000L, STILL_MAX_MS = 120_000L, STILL_DEFAULT_MS = 4_000L, MIN_MS = 100L, PIECE_MS = 34L;
    public static final String[] ASPECTS = {"16:9", "9:16", "1:1", "4:5"}, RESOLUTIONS = {"720p", "1080p"}, POSITIONS = {"bottom", "middle", "top"};
    private VideoEdit() {}

    public static boolean is(String name) { return EDIT.equals(name) || DOWNLOAD.equals(name); }

    // ── Declarations ────────────────────────────────────────────────────────

    public static JSONObject editDeclaration() {
        JSONObject scene = obj("type", "object", "additionalProperties", false, "properties", obj(
            "source", obj("type", "string", "description", "What to show: an id or uri a tool returned (make_picture, make_video, download_file, edit_video), a gallery id, the name of a file attached or saved in this conversation, or the file name of a file your code saved to $OUTPUT_DIR in this turn."),
            "card", obj("type", "string", "description", "Instead of source: a title card, this text centered on a plain background."),
            "card_color", obj("type", "string", "description", "Optional card background as #RRGGBB (default #000000)."),
            "start", obj("type", "number", "description", "Video clips: where to start in the source, in seconds (default 0)."),
            "end", obj("type", "number", "description", "Video clips: where to stop in the source, in seconds (default its end)."),
            "seconds", obj("type", "number", "description", "Pictures and cards: how long to show them (default 4, at most 120)."),
            "volume", obj("type", "number", "minimum", 0, "maximum", 2, "description", "Video clips: their own sound, 0 silent to 2 (default 1).")));
        JSONObject track = obj("type", "object", "additionalProperties", false, "required", arr("source"), "properties", obj(
            "source", obj("type", "string", "description", "Audio (or a video's sound): an id or uri make_speech or download_file returned, a gallery id, or a file name from this conversation."),
            "at", obj("type", "number", "description", "When it starts in the finished video, in seconds (default 0)."),
            "start", obj("type", "number", "description", "Where to start in the source, in seconds (default 0)."),
            "end", obj("type", "number", "description", "Where to stop in the source, in seconds (default its end)."),
            "volume", obj("type", "number", "minimum", 0, "maximum", 2, "description", "0 silent to 2 (default 1; background music usually 0.15-0.3 under narration)."),
            "loop", obj("type", "boolean", "description", "Repeat until the video ends (background music). A looping track starts at 0.")));
        JSONObject caption = obj("type", "object", "additionalProperties", false, "required", arr("start", "end", "text"), "properties", obj(
            "start", obj("type", "number", "description", "Seconds in the finished video."),
            "end", obj("type", "number", "description", "Seconds in the finished video."),
            "text", obj("type", "string", "description", "One or two short lines.")));
        return obj("name", EDIT,
            "description", "Edit one MP4 on the user's phone and save it to the gallery's 들퍼레/영상 folder: scenes in order (video clips trimmed by start/end, pictures or title cards shown for some seconds), audio tracks mixed over them (narration, background music) with volume and start time, and captions burned in. Every scene is fitted to the chosen size with black bars. Takes from seconds to several minutes. Returns {id, name, uri, seconds}; the id can be a scene source of another edit.",
            "parameters", obj("type", "object", "additionalProperties", false, "required", arr("scenes"), "properties", obj(
                "scenes", obj("type", "array", "minItems", 1, "maxItems", SCENES, "items", scene),
                "audio", obj("type", "array", "maxItems", TRACKS, "items", track),
                "captions", obj("type", "array", "maxItems", CAPTIONS, "items", caption),
                "srt", obj("type", "string", "description", "Instead of captions: subtitles in SRT form, timed to the finished video."),
                "caption_position", obj("type", "string", "enum", new JSONArray(List.of(POSITIONS))),
                "aspect", obj("type", "string", "enum", new JSONArray(List.of(ASPECTS)), "description", "16:9 for YouTube (default), 9:16 for Shorts/Reels."),
                "resolution", obj("type", "string", "enum", new JSONArray(List.of(RESOLUTIONS)), "description", "Default 1080p."),
                "name", obj("type", "string", "description", "Optional file name for the result, without extension."))));
    }
    public static JSONObject downloadDeclaration() {
        return obj("name", DOWNLOAD,
            "description", "Download one public web file (a video, audio, picture, or document link found on the web) to the user's phone with the phone's own internet, and save it in the 들퍼레 folder (pictures, MP4 video and audio in their media folders). Use it for footage or music to edit with edit_video; code sandboxes cannot reach the internet. Give the direct file address, not a page. Respect the source's license and credit it. Returns {id, name, uri, mime, size}.",
            "parameters", obj("type", "object", "additionalProperties", false, "required", arr("url"), "properties", obj(
                "url", obj("type", "string", "description", "The https address of the file itself."),
                "name", obj("type", "string", "description", "Optional file name to save as, with its extension."))));
    }
    /** What the model is told about edit_video and download_file. */
    public static String instructions(boolean edit, boolean download) { return instructions(edit, download, true); }
    /**
     * As above; speech says whether make_speech is offered too. Whether code files can be scenes depends on the engine, so
     * WorkBook.instructions says that.
     */
    public static String instructions(boolean edit, boolean download, boolean speech) {
        if (!edit && !download) return "";
        StringBuilder out = new StringBuilder("\nVideo production on the phone: ");
        if (download) out.append(DOWNLOAD).append(" saves a public web file (footage, music, pictures) to the phone; ");
        if (edit) out.append(EDIT).append(" joins clips, pictures and title cards into one MP4, mixes narration and music, and burns in captions; ");
        out.append("results go to the user's 들퍼레 folder. A code sandbox (code execution) has no internet, no ffmpeg and cannot see phone files, so never download or encode video inside code")
            .append(download && edit ? ": fetch with " + DOWNLOAD + " and edit with " + EDIT + ". " : download ? ": fetch with " + DOWNLOAD + ". " : ": edit with " + EDIT + ". ");
        if (edit) out.append("Plan the timeline first: narration length").append(speech ? " (make_speech returns seconds)" : "")
            .append(" sets the scene lengths, and caption times are seconds in the finished video. Make one edit per request and never repeat an edit that returned ok.");
        return out.toString();
    }
    public static String stage(String name) { return EDIT.equals(name) ? "휴대폰에서 영상 편집하는 중" : DOWNLOAD.equals(name) ? "휴대폰으로 파일 내려받는 중" : ""; }

    // ── Checked request ─────────────────────────────────────────────────────

    /**
     * edit_video arguments as the editor uses them: {aspect, resolution, width, height, position, name, scenes:[{source|card,
     * color, startMs, endMs (-1 = to the end), stillMs (-1 = not a still), volume}], tracks:[{source, atMs, startMs, endMs, volume,
     * loop}], captions:[{fromMs, toMs, text}]}. Throws IllegalArgumentException with a Korean reason.
     */
    public static JSONObject edit(JSONObject args) {
        JSONObject a = args == null ? new JSONObject() : args;
        String aspect = choice(a.optString("aspect"), ASPECTS, "16:9"), resolution = choice(a.optString("resolution"), RESOLUTIONS, "1080p");
        int[] size = size(aspect, resolution);
        JSONArray scenes = a.optJSONArray("scenes"), checkedScenes = arr();
        if (scenes == null || scenes.length() == 0) throw new IllegalArgumentException("장면(scenes)을 하나 이상 적어 주세요.");
        if (scenes.length() > SCENES) throw new IllegalArgumentException("장면은 " + SCENES + "개까지예요.");
        for (int i = 0; i < scenes.length(); i++) {
            JSONObject s = scenes.optJSONObject(i); if (s == null) throw new IllegalArgumentException((i + 1) + "번째 장면을 확인해 주세요.");
            String card = s.optString("card").strip(), source = s.optString("source").strip();
            if (!card.isEmpty() && !source.isEmpty()) throw new IllegalArgumentException((i + 1) + "번째 장면은 source와 card 중 하나만 적어 주세요.");
            if (card.isEmpty() && source.isEmpty()) throw new IllegalArgumentException((i + 1) + "번째 장면의 source를 적어 주세요.");
            if (card.length() > CARD_CHARS) throw new IllegalArgumentException("카드 글은 " + CARD_CHARS + "자까지예요.");
            if (!source.isEmpty()) source(source);
            long start = seconds(s, "start", 0), end = seconds(s, "end", -1);
            if (end >= 0 && end - start < MIN_MS) throw new IllegalArgumentException((i + 1) + "번째 장면의 end가 start보다 커야 해요.");
            long still = s.has("seconds") && !s.isNull("seconds") ? seconds(s, "seconds", STILL_DEFAULT_MS) : -1;
            if (still >= 0 && (still < MIN_MS || still > STILL_MAX_MS)) throw new IllegalArgumentException("그림·카드는 0.1초에서 120초까지 보여 줄 수 있어요.");
            if (!card.isEmpty() && still < 0) still = STILL_DEFAULT_MS;
            checkedScenes.put(obj("source", source, "card", card, "color", color(s.optString("card_color"), "#000000"), "startMs", start, "endMs", end,
                "stillMs", still, "volume", volume(s)));
        }
        JSONArray tracks = a.optJSONArray("audio"), checkedTracks = arr();
        if (tracks != null && tracks.length() > TRACKS) throw new IllegalArgumentException("오디오 트랙은 " + TRACKS + "개까지예요.");
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i); if (t == null) throw new IllegalArgumentException((i + 1) + "번째 오디오를 확인해 주세요.");
            String source = t.optString("source").strip(); if (source.isEmpty()) throw new IllegalArgumentException((i + 1) + "번째 오디오의 source를 적어 주세요.");
            source(source);
            long start = seconds(t, "start", 0), end = seconds(t, "end", -1);
            if (end >= 0 && end - start < MIN_MS) throw new IllegalArgumentException((i + 1) + "번째 오디오의 end가 start보다 커야 해요.");
            boolean loop = t.optBoolean("loop");
            checkedTracks.put(obj("source", source, "atMs", loop ? 0 : seconds(t, "at", 0), "startMs", start, "endMs", end, "volume", volume(t), "loop", loop));
        }
        JSONArray captions;
        String srt = a.optString("srt");
        if (!srt.isBlank()) captions = srt(srt);
        else {
            captions = arr(); JSONArray given = a.optJSONArray("captions");
            if (given != null && given.length() > CAPTIONS) throw new IllegalArgumentException("자막은 " + CAPTIONS + "개까지예요.");
            for (int i = 0; given != null && i < given.length(); i++) {
                JSONObject c = given.optJSONObject(i); if (c == null) continue;
                caption(captions, seconds(c, "start", 0), seconds(c, "end", 0), c.optString("text"));
            }
        }
        String name = a.optString("name").strip().replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_");
        if (name.length() > 80) name = name.substring(0, 80);
        return obj("aspect", aspect, "resolution", resolution, "width", size[0], "height", size[1],
            "position", choice(a.optString("caption_position"), POSITIONS, "bottom"), "name", name,
            "scenes", checkedScenes, "tracks", checkedTracks, "captions", captions);
    }
    /** Output pixels for an aspect and resolution: the short side is 720 or 1080, both even. */
    public static int[] size(String aspect, String resolution) {
        int s = "720p".equals(resolution) ? 720 : 1080;
        return switch (aspect) {
            case "9:16" -> new int[]{s, s * 16 / 9};
            case "1:1" -> new int[]{s, s};
            case "4:5" -> new int[]{s, s * 5 / 4};
            default -> new int[]{s * 16 / 9, s};
        };
    }
    private static String choice(String value, String[] allowed, String fallback) {
        for (String option : allowed) if (option.equals(value == null ? "" : value.strip())) return option;
        return fallback;
    }
    /** A source reference: any short single-line text; the phone resolves it and refuses what it cannot read. */
    static String source(String value) {
        if (value.length() > SOURCE_CHARS || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("source가 올바르지 않아요.");
        return value;
    }
    /** Seconds as milliseconds; fallback when absent. Negative or past the output limit is refused. */
    static long seconds(JSONObject o, String key, long fallbackMs) {
        if (o == null || !o.has(key) || o.isNull(key)) return fallbackMs;
        double value = o.optDouble(key, Double.NaN);
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0 || value * 1000 > MAX_MS * 4) throw new IllegalArgumentException(key + " 시간(초)을 확인해 주세요.");
        return Math.round(value * 1000);
    }
    private static double volume(JSONObject o) {
        if (!o.has("volume") || o.isNull("volume")) return 1;
        double v = o.optDouble("volume", Double.NaN);
        if (Double.isNaN(v) || v < 0 || v > 2) throw new IllegalArgumentException("volume은 0에서 2 사이로 적어 주세요.");
        return Math.round(v * 100) / 100.0;
    }
    static String color(String value, String fallback) {
        String v = value == null ? "" : value.strip();
        return v.matches("#[0-9A-Fa-f]{6}") ? v.toUpperCase(Locale.ROOT) : fallback;
    }
    private static void caption(JSONArray out, long from, long to, String text) {
        String clean = text == null ? "" : text.replace("\r", "").replaceAll("<[^>]{1,40}>", "").replaceAll("[\\p{Cntrl}&&[^\n]]", "").strip();
        clean = clean.replaceAll("\n{2,}", "\n");
        if (clean.isEmpty() || to - from < MIN_MS) return;
        if (out.length() >= CAPTIONS) throw new IllegalArgumentException("자막은 " + CAPTIONS + "개까지예요.");
        if (clean.length() > CAPTION_CHARS) clean = clean.substring(0, CAPTION_CHARS);
        out.put(obj("fromMs", from, "toMs", to, "text", clean));
    }

    private static final Pattern SRT_TIME = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    /** SRT cues as captions [{fromMs, toMs, text}]; numbering lines and styling tags are dropped. */
    public static JSONArray srt(String text) {
        JSONArray out = arr();
        String[] blocks = (text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').replace("﻿", "")).split("\n\\s*\n");
        for (String block : blocks) {
            String[] lines = block.strip().split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = SRT_TIME.matcher(lines[i]);
                if (!m.find()) continue;
                long from = ms(m, 1), to = ms(m, 5);
                StringBuilder body = new StringBuilder();
                for (int j = i + 1; j < lines.length; j++) { if (body.length() > 0) body.append('\n'); body.append(lines[j].strip()); }
                caption(out, from, to, body.toString());
                break;
            }
        }
        if (out.length() == 0 && text != null && !text.isBlank()) throw new IllegalArgumentException("SRT 자막을 읽지 못했어요. 00:00:01,000 --> 00:00:03,000 형식으로 적어 주세요.");
        return out;
    }
    private static long ms(Matcher m, int group) {
        String fraction = m.group(group + 3);
        while (fraction.length() < 3) fraction += "0";
        return ((Long.parseLong(m.group(group)) * 60 + Long.parseLong(m.group(group + 1))) * 60 + Long.parseLong(m.group(group + 2))) * 1000 + Long.parseLong(fraction);
    }

    // ── Timeline ────────────────────────────────────────────────────────────

    /**
     * The exact timeline from a checked request and the phone's probe of each source. sceneProbes[i] = {kind: "video" | "image"
     * | "card", durationMs (videos), audio (has sound)}; trackProbes[j] = {durationMs}. Returns {width, height, totalMs, audio
     * (whether the video sequence carries sound), pieces:[{scene, kind, fromMs, toMs, lengthMs, atMs, caption (-1 none), volume}],
     * tracks:[{track, silenceMs, fromMs, toMs, volume, loop}], captions (clipped to the video), notes:[…]}.
     */
    public static JSONObject plan(JSONObject checked, JSONArray sceneProbes, JSONArray trackProbes) {
        JSONArray scenes = checked.optJSONArray("scenes"), notes = arr();
        List<long[]> spans = new ArrayList<>(); // per scene: {outputStart, outputEnd, sourceFrom}
        long at = 0; boolean sound = false;
        for (int i = 0; i < scenes.length(); i++) {
            JSONObject s = scenes.optJSONObject(i), probe = sceneProbes == null ? null : sceneProbes.optJSONObject(i);
            String kind = !s.optString("card").isEmpty() ? "card" : probe == null ? "" : probe.optString("kind");
            long length, from = 0;
            if (kind.equals("video")) {
                long duration = probe.optLong("durationMs", 0), start = s.optLong("startMs"), end = s.optLong("endMs", -1);
                if (duration <= 0) throw new IllegalArgumentException((i + 1) + "번째 장면 영상의 길이를 읽지 못했어요.");
                if (start >= duration - MIN_MS) throw new IllegalArgumentException((i + 1) + "번째 장면의 start가 영상 길이(" + seconds(duration) + "초)를 넘어요.");
                if (end < 0 || end > duration) { if (end > duration) notes.put((i + 1) + "번째 장면의 end를 영상 끝(" + seconds(duration) + "초)으로 맞췄어요."); end = duration; }
                from = start; length = end - start;
                if (probe.optBoolean("audio") && s.optDouble("volume", 1) > 0) sound = true;
            } else if (kind.equals("image") || kind.equals("card")) {
                long still = s.optLong("stillMs", -1);
                length = still < 0 ? STILL_DEFAULT_MS : still;
            } else throw new IllegalArgumentException((i + 1) + "번째 장면은 영상이나 그림이 아니에요.");
            if (length < MIN_MS) throw new IllegalArgumentException((i + 1) + "번째 장면이 너무 짧아요.");
            spans.add(new long[]{at, at + length, from});
            at += length;
            if (at > MAX_MS) throw new IllegalArgumentException("완성 영상은 " + (MAX_MS / 60_000) + "분까지 만들 수 있어요.");
        }
        long total = at;
        // Captions inside the video, later cues cutting earlier ones short so exactly one shows at a time.
        List<JSONObject> cues = new ArrayList<>(); JSONArray given = checked.optJSONArray("captions");
        for (int i = 0; given != null && i < given.length(); i++) {
            JSONObject c = given.optJSONObject(i); long f = c.optLong("fromMs"), t = Math.min(total, c.optLong("toMs"));
            if (f >= total) { notes.put("영상보다 늦은 자막은 뺐어요."); continue; }
            if (t - f >= MIN_MS) cues.add(obj("fromMs", f, "toMs", t, "text", c.optString("text")));
        }
        cues.sort((x, y) -> Long.compare(x.optLong("fromMs"), y.optLong("fromMs")));
        JSONArray captions = arr();
        for (int i = 0; i < cues.size(); i++) {
            JSONObject c = cues.get(i); long t = c.optLong("toMs");
            if (i + 1 < cues.size()) t = Math.min(t, cues.get(i + 1).optLong("fromMs"));
            if (t - c.optLong("fromMs") >= PIECE_MS) captions.put(obj("fromMs", c.optLong("fromMs"), "toMs", t, "text", c.optString("text")));
        }
        TreeSet<Long> cuts = new TreeSet<>();
        for (int i = 0; i < captions.length(); i++) { cuts.add(captions.optJSONObject(i).optLong("fromMs")); cuts.add(captions.optJSONObject(i).optLong("toMs")); }
        JSONArray pieces = arr();
        for (int i = 0; i < spans.size(); i++) {
            long[] span = spans.get(i); JSONObject s = scenes.optJSONObject(i), probe = sceneProbes == null ? null : sceneProbes.optJSONObject(i);
            String kind = !s.optString("card").isEmpty() ? "card" : probe.optString("kind");
            List<Long> bounds = new ArrayList<>(); bounds.add(span[0]);
            for (long cut : cuts.subSet(span[0] + PIECE_MS, span[1] - PIECE_MS + 1)) if (cut - bounds.get(bounds.size() - 1) >= PIECE_MS) bounds.add(cut);
            bounds.add(span[1]);
            for (int b = 0; b + 1 < bounds.size(); b++) {
                // Cuts keep PIECE_MS from each other and from the scene's edges, so no piece is shorter than a frame.
                long start = bounds.get(b), end = bounds.get(b + 1);
                long middle = (start + end) / 2;
                pieces.put(obj("scene", i, "kind", kind, "fromMs", span[2] + (start - span[0]), "toMs", span[2] + (end - span[0]), "lengthMs", end - start,
                    "atMs", start, "caption", captionAt(captions, middle), "volume", s.optDouble("volume", 1)));
            }
        }
        JSONArray tracks = checked.optJSONArray("tracks"), planned = arr();
        for (int j = 0; tracks != null && j < tracks.length(); j++) {
            JSONObject t = tracks.optJSONObject(j), probe = trackProbes == null ? null : trackProbes.optJSONObject(j);
            long duration = probe == null ? 0 : probe.optLong("durationMs", 0);
            if (duration <= 0) throw new IllegalArgumentException((j + 1) + "번째 오디오의 길이를 읽지 못했어요.");
            long start = t.optLong("startMs"), end = t.optLong("endMs", -1), offset = t.optLong("atMs");
            if (start >= duration - MIN_MS) throw new IllegalArgumentException((j + 1) + "번째 오디오의 start가 길이(" + seconds(duration) + "초)를 넘어요.");
            if (end < 0 || end > duration) end = duration;
            boolean loop = t.optBoolean("loop");
            if (!loop) {
                if (offset >= total - MIN_MS) { notes.put((j + 1) + "번째 오디오는 영상이 끝난 뒤에 시작해서 뺐어요."); continue; }
                if (offset + (end - start) > total) { end = start + (total - offset); notes.put((j + 1) + "번째 오디오를 영상 길이에 맞춰 잘랐어요."); }
            }
            planned.put(obj("track", j, "silenceMs", loop ? 0 : offset, "fromMs", start, "toMs", end, "volume", t.optDouble("volume", 1), "loop", loop));
        }
        return obj("width", checked.optInt("width"), "height", checked.optInt("height"), "totalMs", total, "audio", sound,
            "pieces", pieces, "tracks", planned, "captions", captions, "notes", notes);
    }
    private static int captionAt(JSONArray captions, long ms) {
        for (int i = 0; i < captions.length(); i++) {
            JSONObject c = captions.optJSONObject(i);
            if (c.optLong("fromMs") <= ms && ms < c.optLong("toMs")) return i;
        }
        return -1;
    }
    static String seconds(long ms) { return String.format(Locale.ROOT, "%.1f", ms / 1000.0); }

    /** The 44-byte header of a 16-bit PCM WAV with data bytes of samples (the editor's silence before a late audio track). */
    public static byte[] wavHeader(int data, int rate, int channels) {
        java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36 + data).put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            .put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) channels)
            .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
            .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(data);
        return out.array();
    }

    // ── download_file ───────────────────────────────────────────────────────

    /** download_file arguments {url, name}: only public http(s) addresses, never this phone or its local network. */
    public static JSONObject download(JSONObject args) {
        String url = args == null ? "" : args.optString("url").strip();
        if (url.isEmpty()) throw new IllegalArgumentException("내려받을 파일 주소(url)를 적어 주세요.");
        publicUrl(url);
        String name = args.optString("name").strip();
        if (name.length() > 120) name = name.substring(0, 120);
        return obj("url", url, "name", name);
    }
    /** Throws unless url is an http(s) address on a public host name or address (redirects are checked again with this). */
    public static java.net.URI publicUrl(String url) {
        java.net.URI uri;
        try { uri = new java.net.URI(url); } catch (Exception e) { throw new IllegalArgumentException("주소 형식이 올바르지 않아요."); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT), host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (url.length() > 4000 || !(scheme.equals("https") || scheme.equals("http")) || uri.getUserInfo() != null) throw new IllegalArgumentException("https 주소만 내려받을 수 있어요.");
        if (host.isEmpty() || !host.contains(".") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost") || host.equals("localhost"))
            throw new IllegalArgumentException("공개 인터넷 주소만 내려받을 수 있어요.");
        if (privateAddress(host)) throw new IllegalArgumentException("휴대폰이나 내부 네트워크 주소는 내려받지 않아요.");
        return uri;
    }
    /** A literal loopback, private, link-local, carrier-grade or unspecified IPv4/IPv6 address. Host names are allowed. */
    public static boolean privateAddress(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            String[] p = h.split("\\."); int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
            return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127) || a >= 224;
        }
        if (h.contains(":")) {
            String l = h.toLowerCase(Locale.ROOT);
            return l.equals("::") || l.equals("::1") || l.startsWith("fe8") || l.startsWith("fe9") || l.startsWith("fea") || l.startsWith("feb") || l.startsWith("fc") || l.startsWith("fd") || l.startsWith("::ffff:");
        }
        return false;
    }
    /** Where a downloaded file goes: "이미지", "영상", "오디오" for the media folders, "" for the 들퍼레 folder. */
    public static String category(String mime) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (m.equals("image/png") || m.equals("image/jpeg") || m.equals("image/webp")) return "이미지";
        if (m.equals("video/mp4")) return "영상";
        if (m.equals("audio/wav") || m.equals("audio/mpeg") || m.equals("audio/mp4")) return "오디오";
        return "";
    }
    /** The file's type from the server's Content-Type, else from its name; "" when unknown. */
    public static String mime(String contentType, String name) {
        String type = contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (type.equals("audio/x-wav") || type.equals("audio/wave") || type.equals("audio/vnd.wave")) type = "audio/wav";
        if (type.equals("audio/mp3")) type = "audio/mpeg";
        if (type.equals("audio/x-m4a") || type.equals("audio/m4a")) type = "audio/mp4";
        if (type.equals("image/jpg")) type = "image/jpeg";
        String byName = MediaPaths.mime(name);
        if (type.isEmpty() || type.equals("application/octet-stream") || type.equals("binary/octet-stream")) return byName;
        return type;
    }
    /** A file name for a download: the given name, else the address's last path part, with an extension matching mime. */
    public static String fileName(String given, String url, String mime) {
        String base = given == null ? "" : given.strip();
        if (base.isEmpty()) {
            try { String path = new java.net.URI(url).getPath(); base = path == null ? "" : java.net.URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), java.nio.charset.StandardCharsets.UTF_8); }
            catch (Exception e) { base = ""; }
        }
        if (base.isBlank()) base = "내려받은 파일";
        String extension = switch (mime == null ? "" : mime) {
            case "image/png" -> ".png"; case "image/jpeg" -> ".jpg"; case "image/webp" -> ".webp"; case "video/mp4" -> ".mp4";
            case "audio/wav" -> ".wav"; case "audio/mpeg" -> ".mp3"; case "audio/mp4" -> ".m4a"; default -> "";
        };
        if (!extension.isEmpty() && !MediaPaths.mime(base).equals(mime)) base = base.replaceAll("\\.[A-Za-z0-9]{1,5}$", "") + extension;
        return WorkBook.fileName(base);
    }
}
