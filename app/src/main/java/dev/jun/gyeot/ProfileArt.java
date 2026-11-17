package dev.jun.gyeot;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.RectF;
import dev.jun.gyeot.core.CodexImages;
import dev.jun.gyeot.core.Drawing;
import dev.jun.gyeot.core.FrameAligner;
import dev.jun.gyeot.core.GifWriter;
import dev.jun.gyeot.core.SpriteSheet;
import dev.jun.gyeot.core.ProfileMotion;
import dev.jun.gyeot.core.OpenAiProtocol;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * GPT Image 2.6 for profile pictures, on the same two routes as the profile's GPT image: the Codex image account for a
 * ChatGPT plan, else an API key. Animated GIFs (one per avatar mood) and SVG drawings; the SVG is rendered on the phone
 * (AndroidSVG) into the square PNG the profile shows, and kept as SVG in the picture library. Network threads only.
 */
final class ProfileArt {
    private ProfileArt() {}

    /** The network part, replaced in tests. reference is a PNG to animate, or null for the description alone. */
    interface Maker {
        byte[] gif(AppStore store, String character, ProfileMotion.Mood mood, byte[] reference, AtomicReference<Runnable> cancel, boolean codex, Route route) throws Exception;
        String svg(AppStore store, String prompt, AtomicReference<Runnable> cancel, boolean codex) throws Exception;
    }
    /**
     * What one series of requests learned: an endpoint that will not animate from a reference is not asked again, and the
     * reference described in words (when no description was given) is reused for every mood of the series.
     */
    static final class Route {
        volatile boolean editsRefused;
        /** GIF output is switched off for this connection altogether: every mood is drawn as a sheet and joined on the phone. */
        volatile boolean gifDisabled;
        /** The service's own words when it would not make a GIF at all. */
        volatile String gifRefusal = "";
        /** The edit endpoint's own words when it refused, for the message if the description cannot be made either. */
        volatile String editRefusal = "";
        /** The character as a text model saw it in the reference; empty until needed. */
        volatile String described = "";
        /** Told what the series is doing between moods (looking at the picture). */
        volatile Consumer<String> step = s -> {};
    }
    static volatile Maker maker = new Maker() {
        @Override public byte[] gif(AppStore store, String character, ProfileMotion.Mood mood, byte[] reference, AtomicReference<Runnable> cancel, boolean codex, Route route) throws Exception {
            return ProfileArt.gifOnline(store, character, mood, reference, cancel, codex, route);
        }
        @Override public String svg(AppStore store, String prompt, AtomicReference<Runnable> cancel, boolean codex) throws Exception {
            return ProfileArt.svgOnline(store, prompt, cancel, codex);
        }
    };

    private record Answer(int status, JSONObject json, String text) {
        String failure() { return OpenAiProtocol.failure(json, text); }
    }

    /**
     * A GIF as GPT image sent it, held still (core/GifSteady): the model draws each frame on its own, a little to one side
     * of the last, and in the profile's small circle that reads as the whole picture shaking from side to side. The sheet
     * route steadies its frames before it joins them (joinSheet). A GIF that cannot be steadied is kept as it came.
     */
    static byte[] steadied(byte[] gif) {
        try { return dev.jun.gyeot.core.GifSteady.steady(gif, ProfileMotion.GIF_LIMIT).gif(); }
        catch (RuntimeException | OutOfMemoryError failed) { return gif; }
    }
    static byte[] gifOnline(AppStore store, String character, ProfileMotion.Mood mood, byte[] reference, AtomicReference<Runnable> cancel, boolean codex, Route route) throws Exception {
        if (!route.gifDisabled && reference != null && !route.editsRefused) {
            Answer a;
            if (codex) a = codex(store, "/images/edits", ProfileMotion.codexGifEdit(character, mood, reference), cancel);
            else {
                String boundary = "gyeot" + Long.toHexString(System.nanoTime());
                a = api(store, dev.jun.gyeot.core.Drawing.OPENAI_EDITS, "multipart/form-data; boundary=" + boundary, ProfileMotion.multipart(ProfileMotion.gifEditFields(character, mood), reference, boundary), cancel);
            }
            if (a.status < 300) return steadied(ProfileMotion.gif(a.json, a.text));
            String why = a.failure();
            if (ProfileMotion.gifDisabled(a.status, why)) { route.gifDisabled = true; route.gifRefusal = why; }
            else if (ProfileMotion.editUnsupported(a.status, why)) { route.editsRefused = true; route.editRefusal = why; }
            else throw failed(a);
        }
        String who = ProfileMotion.character(character);
        if (who.isEmpty()) who = route.described;
        if (!route.gifDisabled) {
            // Generations take no picture: without a description, the reference is put into words once for the whole series.
            if (who.isEmpty() && reference != null) who = route.described = describe(store, reference, cancel, route);
            JSONObject body = ProfileMotion.gifRequest(who, mood);
            Answer a = codex ? codex(store, "/images/generations", body, cancel)
                : api(store, dev.jun.gyeot.core.Drawing.OPENAI_IMAGES, "application/json", body.toString().getBytes(StandardCharsets.UTF_8), cancel);
            if (a.status < 300) return steadied(ProfileMotion.gif(a.json, a.text));
            String why = a.failure();
            if (!ProfileMotion.gifDisabled(a.status, why) && !ProfileMotion.editUnsupported(a.status, why)) throw failed(a);
            route.gifDisabled = true; route.gifRefusal = why;
        }
        return sheetGif(store, who, mood, reference, cancel, codex, route);
    }

    /**
     * GIF output is not enabled for this connection: GPT image draws the mood's nine frames as one ordinary PNG on a 3 × 3
     * grid (an edit of the reference when there is one, so the character stays the same), and the phone cuts the grid and
     * joins the frames into a looping GIF. The same route the profile's ordinary GPT pictures take (PictureMaker).
     */
    static byte[] sheetGif(AppStore store, String who, ProfileMotion.Mood mood, byte[] reference, AtomicReference<Runnable> cancel, boolean codex, Route route) throws Exception {
        String refusal = route.gifRefusal == null ? "" : route.gifRefusal.strip();
        if (refusal.length() > 120) refusal = refusal.substring(0, 120) + "…";
        try {
            JSONObject args = Drawing.directArguments(new JSONObject().put("prompt", ProfileMotion.sheetPrompt(who, mood, reference != null))
                .put("aspect", "square").put("edit", reference != null).put("codex", codex).toString());
            JSONArray sources = new JSONArray();
            if (reference != null) sources.put(new JSONObject().put("data", Base64.getEncoder().encodeToString(reference)).put("mime", "image/png"));
            JSONObject picture = PictureMaker.draw(store, args, sources, cancel, () -> {}, codex);
            byte[] sheet = Base64.getMimeDecoder().decode(picture.optString("data"));
            if (sheet.length == 0) throw new IllegalStateException("GPT 이미지가 그림을 보내지 않았어요.");
            return joinSheet(sheet, ProfileMotion.sheetFrameMs(mood));
        } catch (java.util.concurrent.CancellationException stopped) {
            throw stopped;
        } catch (Exception e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank() ? "" : " (" + e.getMessage() + ")";
            throw new IllegalStateException("이 연결은 GIF를 바로 만들지 못해서" + (refusal.isEmpty() ? "" : "(" + refusal + ")")
                + " 9장면을 한 장에 그려 GIF로 묶으려 했는데, 그것도 안 됐어요" + reason + ". 잠시 뒤 다시 해 주세요.", e);
        }
    }
    /**
     * The sheet PNG cut into its 3 × 3 frames (SpriteSheet), held still against each other (FrameAligner: the model draws
     * each cell on its own, a little larger, smaller or off from the last) and joined into a looping GIF (GifWriter).
     */
    static byte[] joinSheet(byte[] sheetBytes, int frameMs) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap sheet = BitmapFactory.decodeByteArray(sheetBytes, 0, sheetBytes.length, options);
        if (sheet == null) throw new IOException("GPT 이미지가 보낸 그림을 읽지 못했어요.");
        try {
            int w = sheet.getWidth(), h = sheet.getHeight();
            if ((long) w * h > 16_000_000L) throw new IOException("GPT 이미지가 보낸 그림이 너무 커요.");
            int[] argb = new int[w * h]; sheet.getPixels(argb, 0, w, 0, 0, w, h);
            SpriteSheet.Frames frames = SpriteSheet.frames(argb, w, h, ProfileMotion.SHEET_COLUMNS, ProfileMotion.SHEET_ROWS, ProfileMotion.SHEET_FRAME_MAX);
            FrameAligner.Steady steady = FrameAligner.steady(frames.pixels(), frames.side());
            byte[] gif = GifWriter.encode(steady.pixels(), steady.side(), steady.side(), frameMs);
            if (gif.length > ProfileMotion.GIF_LIMIT) throw new IOException("GIF가 너무 커요(8MB까지). 다시 만들어 주세요.");
            return gif;
        } finally { sheet.recycle(); }
    }

    /**
     * The character in the reference, in words, from the ChatGPT connection's own model (plan or API key): the endpoint
     * will not animate from the picture, so GPT Image 2.6 gets this description instead.
     */
    static String describe(AppStore store, byte[] reference, AtomicReference<Runnable> cancel, Route route) throws Exception {
        OpenAiAccount account = store.openai;
        String refusal = route.editRefusal == null ? "" : route.editRefusal.strip();
        if (refusal.length() > 120) refusal = refusal.substring(0, 120) + "…";
        String why = "이 연결은 참고 그림으로 GIF를 만들지 못한대요" + (refusal.isEmpty() ? "" : "(" + refusal + ")") + ". ";
        if (!account.connected())
            throw new IllegalStateException(why + "그림을 보고 캐릭터 설명을 대신 만들려면 설정 → ChatGPT(구독 로그인이나 API 키)가 필요해요. 아니면 캐릭터 설명을 적고 다시 만들어 주세요.");
        route.step.accept("그림을 보고 캐릭터 설명을 만드는 중…");
        try {
            String model = account.model();
            if (model.isBlank() && !account.usesPlan()) model = ProfileMotion.SVG_AUTHOR;
            String effort = ProfileMotion.lookEffort(account.reasoningProfile(model).efforts);
            String answer = OpenAiClient.look(account, model, effort, ProfileMotion.LOOK_INSTRUCTIONS, ProfileMotion.LOOK_QUESTION, "image/png", reference, () -> {}, cancel);
            return ProfileMotion.described(answer);
        } catch (java.util.concurrent.CancellationException stopped) {
            throw stopped;
        } catch (Exception e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank() ? "" : " (" + e.getMessage() + ")";
            throw new IllegalStateException(why + "그림을 보고 캐릭터 설명을 만들려 했지만 그것도 안 됐어요" + reason + ". 캐릭터 설명을 적고 다시 만들어 주세요.", e);
        } finally {
            route.step.accept("");
        }
    }

    static String svgOnline(AppStore store, String prompt, AtomicReference<Runnable> cancel, boolean codex) throws Exception {
        Answer a = null;
        for (boolean author : new boolean[] {false, true}) {
            JSONObject body = ProfileMotion.svgRequest(prompt, author);
            a = codex ? codex(store, "/images/generations", body, cancel)
                : api(store, dev.jun.gyeot.core.Drawing.OPENAI_IMAGES, "application/json", body.toString().getBytes(StandardCharsets.UTF_8), cancel);
            if (a.status < 300 || !ProfileMotion.authorMissing(a.status, a.failure())) break;
        }
        if (a.status >= 300) throw failed(a);
        return ProfileMotion.svg(a.json, a.text);
    }

    private static IllegalStateException failed(Answer a) {
        String message = a.failure(), lower = message.toLowerCase(java.util.Locale.ROOT);
        if (a.status == 404 || lower.contains("model") && (lower.contains("not found") || lower.contains("does not exist") || lower.contains("access")))
            return new IllegalStateException("이 계정에서는 아직 GPT 이미지 2.6(" + ProfileMotion.MODEL + ")을 쓸 수 없어요." + (message.isBlank() ? "" : " (" + message + ")"));
        return new IllegalStateException(OpenAiProtocol.explain(a.status, message));
    }

    private static Answer api(AppStore store, String url, String type, byte[] body, AtomicReference<Runnable> cancel) throws Exception {
        if (store.openai.usesPlan()) throw new IllegalStateException("이미지용 Codex 연결이 필요해요. 설정 → ChatGPT에서 연결해 주세요.");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + store.openai.bearer()); headers.put("Accept", "application/json"); headers.put("Content-Type", type);
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", url, headers, body, 300_000, cancel)) {
            String text = OpenAiAccount.Http.read(response.body, 60 * 1024 * 1024);
            JSONObject json; try { json = new JSONObject(text); } catch (Exception e) { json = new JSONObject(); }
            return new Answer(response.status, json, text);
        } finally { if (cancel != null) cancel.set(null); }
    }
    private static Answer codex(AppStore store, String path, JSONObject body, AtomicReference<Runnable> cancel) throws Exception {
        StringBuilder raw = new StringBuilder();
        CodexImages.Reply reply = store.codexImages.send(path, body, cancel, () -> {}, raw);
        return new Answer(reply.status, reply.json, raw.toString());
    }

    /**
     * A checked SVG drawn into a transparent square PNG of side size, fitted by its viewBox (xMidYMid meet). AndroidSVG is
     * given no file or network resolver, so nothing outside the text is read.
     */
    static byte[] render(String svgText, int size) throws IOException {
        Bitmap bitmap = null;
        try {
            com.caverock.androidsvg.SVG svg = com.caverock.androidsvg.SVG.getFromString(svgText);
            RectF box = svg.getDocumentViewBox();
            if (box == null) {
                float w = svg.getDocumentWidth(), h = svg.getDocumentHeight();
                if (!(w > 0 && h > 0)) throw new IOException("SVG 크기를 알 수 없어요.");
                svg.setDocumentViewBox(0, 0, w, h);
            }
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            svg.renderToCanvas(new Canvas(bitmap), new com.caverock.androidsvg.RenderOptions().viewPort(0, 0, size, size));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("SVG 그림을 저장하지 못했어요.");
            return out.toByteArray();
        } catch (com.caverock.androidsvg.SVGParseException e) {
            throw new IOException("SVG를 그리지 못했어요." + (e.getMessage() == null ? "" : " (" + e.getMessage() + ")"), e);
        } catch (RuntimeException | OutOfMemoryError e) {
            throw new IOException("SVG를 그리지 못했어요.", e);
        } finally { if (bitmap != null) bitmap.recycle(); }
    }
}
