package dev.jun.gyeot;

import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.MediaTools;
import dev.jun.gyeot.core.VideoEdit;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Work models' media tools (core/MediaTools): a GPT image picture, a Grok video, Gemini speech, and the phone's own
 * video editor and web download (core/VideoEdit). The host (the Work run) draws, waits, edits and saves with the user's own
 * connections; this class only offers what is available, checks arguments and turns failures into results the model can
 * read. The ledger journals each call before it runs and never repeats one.
 */
final class MediaWorkTools extends WorkTools {
    interface Host {
        JSONObject picture(JSONObject checked) throws Exception;
        JSONObject video(JSONObject checked) throws Exception;
        JSONObject speech(JSONObject checked) throws Exception;
        default JSONObject edit(JSONObject checked) throws Exception { throw new IllegalStateException("영상 편집을 쓸 수 없어요."); }
        default JSONObject download(JSONObject checked) throws Exception { throw new IllegalStateException("파일 내려받기를 쓸 수 없어요."); }
    }
    private final boolean picture, video, speech, codex, edit, download; private final Host host; private final BooleanSupplier stopped;
    private final Consumer<JSONArray> outputs;
    MediaWorkTools(boolean picture, boolean video, boolean speech, boolean codex, Host host, BooleanSupplier stopped) {
        this(picture, video, speech, codex, false, false, host, stopped, null);
    }
    /** edit and download need no connection; outputs receives the files the model's code saved so far this turn. */
    MediaWorkTools(boolean picture, boolean video, boolean speech, boolean codex, boolean edit, boolean download, Host host, BooleanSupplier stopped, Consumer<JSONArray> outputs) {
        this.picture = picture; this.video = video; this.speech = speech; this.codex = codex; this.edit = edit; this.download = download;
        this.host = host; this.stopped = stopped; this.outputs = outputs;
    }
    boolean offers() { return picture || video || speech || edit || download; }
    @Override JSONArray declarations() {
        JSONArray out = MediaTools.declarations(picture, video, speech);
        if (download) out.put(VideoEdit.downloadDeclaration());
        if (edit) out.put(VideoEdit.editDeclaration());
        return out;
    }
    @Override String instructions() { return MediaTools.instructions(picture, video, speech) + VideoEdit.instructions(edit, download, speech); }
    @Override void providerFiles(JSONArray files) { if (outputs != null && files != null) outputs.accept(files); }
    @Override JSONObject run(String name, Object arguments) {
        if (stopped.getAsBoolean()) throw new CancellationException("작업을 멈췄어요.");
        JSONObject args;
        try { args = arguments instanceof JSONObject given ? given : new JSONObject(String.valueOf(arguments)); }
        catch (Exception e) { return GitHubFunctions.failure("도구의 인수를 확인해 주세요."); }
        try {
            return switch (name) {
                case MediaTools.PICTURE -> picture ? host.picture(MediaTools.picture(args, codex)) : GitHubFunctions.failure("그림 연결이 없어요.");
                case MediaTools.VIDEO -> video ? host.video(MediaTools.video(args)) : GitHubFunctions.failure("Grok 연결이 없어요.");
                case MediaTools.SPEECH -> speech ? host.speech(MediaTools.speech(args)) : GitHubFunctions.failure("Gemini API 키가 없어요.");
                case VideoEdit.EDIT -> edit ? host.edit(VideoEdit.edit(args)) : GitHubFunctions.failure("영상 편집을 쓸 수 없어요.");
                case VideoEdit.DOWNLOAD -> download ? host.download(VideoEdit.download(args)) : GitHubFunctions.failure("파일 내려받기를 쓸 수 없어요.");
                default -> GitHubFunctions.failure("허용하지 않은 도구예요.");
            };
        } catch (CancellationException stop) { throw stop; }
        catch (Exception failed) { return GitHubFunctions.failure(failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage()); }
    }
}
