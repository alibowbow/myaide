package dev.jun.gyeot;

import android.content.Context;
import android.net.Uri;
import dev.jun.gyeot.core.ChatImages;
import dev.jun.gyeot.core.Drawing;
import dev.jun.gyeot.core.Gallery;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.obj;

/**
 * Draws the main chat's image requests with GPT image (the Codex image account, or an API key — the route Work uses) and
 * keeps each picture the way Work keeps its drawings: a small private copy for the chat and edits, the full file in the
 * 들퍼레 folder's 이미지 category, and a gallery entry. The answer is shown first with a "그리는 중" card for each request;
 * the card then holds the picture record, or the reason it was not drawn (MainChatEngine draws them).
 */
final class MainChatPictures {
    interface Drawer { JSONObject draw(JSONObject args, JSONArray sources, AtomicReference<Runnable> cancel, Runnable guard, boolean codex) throws Exception; }
    interface Saver { Uri save(String key, String name, String mime, byte[] bytes, Runnable guard) throws Exception; }
    /** The main chat's own switch; Work's 그림 그리기 setting is separate. */
    static final String SETTING = "mainImages";
    static final String STAGE = "그림을 그리고 있어요";
    private final AppStore app;
    /** Replaced in tests: drawing and the export to the shared folder. */
    volatile Drawer drawer; volatile Saver saver;
    MainChatPictures(Context context, AppStore app) {
        this.app = app; Context appContext = context.getApplicationContext();
        drawer = (args, sources, cancel, guard, codex) -> PictureMaker.draw(app, args, sources, cancel, guard, codex);
        saver = (key, name, mime, bytes, guard) -> SharedMediaOutput.save(appContext, key, "이미지", name, mime, bytes, guard);
    }
    static boolean enabled(AppStore app) { return app.settings.getBoolean(SETTING, true); }
    /** "codex", "api", "" (not connected) or "off" (the main switch). */
    static String route(AppStore app) { return enabled(app) ? AssistantProfileUi.route(app) : "off"; }
    static String unavailable(AppStore app, String route) {
        if ("off".equals(route)) return "메인 대화의 이미지 만들기가 꺼져 있어요. ⋯ → 대화 설정에서 켤 수 있어요.";
        return app.openai.usesPlan() ? "그림을 그리려면 설정 → ChatGPT에서 ‘이미지용 Codex로 로그인’을 눌러 연결해 주세요."
            : "그림을 그리려면 설정 → ChatGPT에서 이미지용 Codex 계정이나 이미지 권한이 있는 API 키를 연결해 주세요.";
    }

    /** What the model is told about pictures: the block protocol when drawing works, otherwise how to say it cannot. */
    static String instructions(AppStore app) {
        String route = route(app);
        if (route.isEmpty() || "off".equals(route))
            return "\n\nImages: picture creation is unavailable right now (" + ("off".equals(route) ? "turned off in this chat's settings" : "no image connection") + "). When asked for an image, say so in one sentence (" + unavailable(app, route) + ") and offer to write the prompt; do not suggest 작업으로 맡기기 for images.";
        return "\n\nImages: you can make pictures in this chat with GPT image. When the user asks for an image (a picture, photo, illustration, poster, wallpaper, logo, character, or gives an image prompt to run), answer with one short sentence in the user's language and one fenced block:\n"
            + "```image\n{\"prompt\": \"...\", \"aspect\": \"square\"}\n```\n"
            + "prompt is the complete picture description in English: subject, setting, style, composition, lighting, colors, and any text to show word for word; when the user gives their own prompt, keep it intact. aspect is one of square, portrait, landscape, 9:16, 16:9, 4:3, 3:4. "
            + "To change a picture shown earlier, add \"edit\": \"<the id from its picture block>\" and describe the change. At most " + ChatImages.MAX + " blocks per answer. "
            + "The app draws them, shows them here and saves them to the gallery and the user's 들퍼레 folder; picture blocks in the history are pictures already made. Never write a picture block yourself, never claim a picture exists before the app shows it, do not suggest 작업으로 맡기기 for images, and if the user only wants a prompt written, write it as text.";
    }

    /**
     * The answer to show and keep now: each image request becomes a "그리는 중" card (ChatImages.pending) that a drawing
     * fills in after the answer is shown, or the reason when drawing is off or not connected; picture blocks the model wrote
     * become code. Nothing is drawn here.
     */
    String pending(String text) {
        List<JSONObject> asks = ChatImages.requests(text);
        if (asks.isEmpty()) return ChatImages.apply(text, List.of());
        String route = route(app); List<JSONObject> cards = new ArrayList<>();
        for (JSONObject ask : asks) cards.add(route.isEmpty() || "off".equals(route) ? ChatImages.failure(ask, unavailable(app, route)) : ChatImages.pending(ask, token()));
        return ChatImages.apply(text, cards);
    }
    /** A fresh drawing token for one card. */
    static String token() { return java.util.UUID.randomUUID().toString().replace("-", ""); }

    /**
     * Draws one card's request on the route as it is now and keeps the picture the way Work keeps its drawings; key names
     * the export so a drawing asked for again is a new file, never the old one's. A route that is off or gone is refused.
     */
    JSONObject draw(JSONObject ask, String request, String key, int index, int count, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        return drawFor("메인 대화", "메인", ask, request, key, index, count, guard, cancel);
    }
    /** The same for another screen (마이튜브's 요약 대화): place goes in the gallery, tag in the file name. Drawing is not tied to the main chat's switch there. */
    JSONObject drawFor(String place, String tag, JSONObject ask, String request, String key, int index, int count, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        String route = "메인 대화".equals(place) ? route(app) : AssistantProfileUi.route(app);
        if (route.isEmpty() || "off".equals(route)) throw new IllegalStateException(unavailable(app, route));
        return draw(place, tag, ask, request, key, index, count, "codex".equals(route), guard, cancel);
    }
    private JSONObject draw(String place, String tag, JSONObject ask, String request, String key, int index, int count, boolean codex, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        String edit = ask.optString("edit"); JSONArray sources = new JSONArray();
        if (!edit.isEmpty()) {
            // Only a picture in the gallery can be edited; its small private copy is what goes to the image model.
            if (Gallery.find(app.gallery(), edit) == null || !app.attachmentFile(edit).isFile()) throw new IllegalStateException("고칠 그림이 갤러리에 없어 그리지 않았어요. 새로 그려 달라고 해 주세요.");
            sources.put(obj("data", Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(app.attachmentFile(edit).toPath())), "mime", "image/png"));
        }
        JSONObject args = Drawing.directArguments(obj("prompt", ask.optString("prompt"), "aspect", ask.optString("aspect"), "edit", !edit.isEmpty(), "codex", codex).toString());
        guard.run();
        JSONObject picture = drawer.draw(args, sources, cancel, guard, codex);
        guard.run();
        byte[] bytes = Base64.getMimeDecoder().decode(picture.optString("data"));
        if (bytes.length == 0) throw new IllegalStateException("이미지 모델이 그림을 보내지 않았어요. 표현을 바꿔 다시 요청해 보세요.");
        JSONObject kept = WorkFiles.keepPicture(app, bytes);
        String format = picture.optString("format", "png"), extension = format.equals("jpeg") || format.equals("jpg") ? "jpg" : format.equals("webp") ? "webp" : "png";
        String mime = extension.equals("jpg") ? "image/jpeg" : "image/" + extension;
        String stamp = java.time.format.DateTimeFormatter.ofPattern("MMdd-HHmmss").format(java.time.LocalDateTime.now());
        String name = "그림-" + stamp + "-" + tag + (count > 1 ? "-" + (index + 1) : "") + "." + extension;
        String model = picture.optString("model", Drawing.OPENAI_MODEL);
        JSONObject record = obj("id", kept.optString("id"), "name", name, "prompt", args.optString("prompt"), "aspect", args.optString("aspect"),
            "w", kept.optInt("w"), "h", kept.optInt("h"), "model", model);
        if (!edit.isEmpty()) record.put("from", edit);
        try {
            String uri = saver.save(key, name, mime, bytes, guard).toString();
            JSONObject entry = Gallery.entry(kept.optString("id"), uri, name, request, picture.optString("revised"), "", place, model,
                System.currentTimeMillis(), bytes.length, kept.optInt("w"), kept.optInt("h"), edit, false);
            entry.put("generationPrompt", args.optString("prompt"));
            // The export checks the guard all along; a conversation that changed meanwhile gets no gallery entry either.
            guard.run();
            List<String> dropped = app.changeGallery(list -> Gallery.add(list, entry));
            for (String gone : dropped) app.releaseAttachment(gone);
        } catch (CancellationException stopped) { app.releaseAttachment(kept.optString("id")); throw stopped; }
        catch (Exception failed) { record.put("note", "갤러리·들퍼레 폴더에 저장하지 못했어요. " + (failed.getMessage() == null ? "" : failed.getMessage())); }
        return record;
    }
}
