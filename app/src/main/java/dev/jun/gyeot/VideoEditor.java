package dev.jun.gyeot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import com.google.common.collect.ImmutableList;
import dev.jun.gyeot.core.MediaPaths;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * edit_video on the phone with Media3 Transformer. core/VideoEdit checks the request and plans the exact timeline; this class
 * probes each source, draws title cards and captions, builds one Composition (the pictures and clips in order, plus one
 * sequence per audio track mixed under them) and exports an H.264/AAC MP4 to the app's cache. The caller saves it to
 * 들퍼레/영상 and removes the cache file.
 *
 * Captions never depend on frame times: the planner cut every scene where a caption starts or ends, so each piece carries
 * one fixed caption picture. All pieces share one caption bitmap, redrawn when a new piece asks for it, so memory stays at
 * one frame-sized bitmap however many captions there are.
 */
final class VideoEditor {
    static final int FRAME_RATE = 30;
    static final long TIMEOUT_MS = 45 * 60_000L;
    private VideoEditor() {}

    /** A resolved source: a uri this app may read, the type it is known by (may be ""), and a name for messages. */
    static final class Source {
        final Uri uri; final String mime, name;
        Source(Uri uri, String mime, String name) { this.uri = uri; this.mime = mime == null ? "" : mime; this.name = name == null ? "" : name; }
    }

    // ── Probing ─────────────────────────────────────────────────────────────

    /** {kind: video | image | audio | "", durationMs, audio, video, rate, channels, mime} for a source. */
    static JSONObject probe(Context context, Source source) {
        String mime = source.mime;
        try { String type = context.getContentResolver().getType(source.uri); if (type != null && !type.isBlank()) mime = type; } catch (Exception ignored) {}
        if (mime.isBlank()) mime = MediaPaths.mime(source.name.isBlank() ? source.uri.getLastPathSegment() : source.name);
        if (mime.startsWith("image/")) return image(context, source, mime);
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        boolean video = false, audio = false; long duration = 0;
        try {
            retriever.setDataSource(context, source.uri);
            video = "yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
            audio = "yes".equals(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            String ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            duration = ms == null ? 0 : Long.parseLong(ms.strip());
        } catch (Exception unreadable) {
            JSONObject picture = image(context, source, mime);
            if (!picture.optString("kind").isEmpty()) return picture;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        if (!video && !audio) {
            JSONObject picture = image(context, source, mime);
            if (!picture.optString("kind").isEmpty()) return picture;
        }
        JSONObject out = obj("kind", video ? "video" : audio ? "audio" : "", "durationMs", duration, "audio", audio, "video", video, "mime", mime);
        if (audio) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, source.uri, null);
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i); String type = format.getString(MediaFormat.KEY_MIME);
                    if (type == null || !type.startsWith("audio/")) continue;
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) put(out, "rate", format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) put(out, "channels", format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    break;
                }
            } catch (Exception ignored) {
            } finally { extractor.release(); }
        }
        return out;
    }
    private static JSONObject image(Context context, Source source, String mime) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(source.uri)) {
            if (in != null) BitmapFactory.decodeStream(in, null, bounds);
        } catch (Exception ignored) {}
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return obj("kind", "");
        String type = bounds.outMimeType != null && bounds.outMimeType.startsWith("image/") ? bounds.outMimeType : mime.startsWith("image/") ? mime : MimeTypes.IMAGE_PNG;
        return obj("kind", "image", "mime", type, "width", bounds.outWidth, "height", bounds.outHeight);
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    /**
     * Renders plan (VideoEdit.plan) to an MP4 in the cache. scenes[i] and tracks[j] are the resolved sources (null for cards),
     * sceneProbes/trackProbes their probes. guard throws to stop; progress gets 0-100. Returns the file; the caller removes it.
     */
    static File render(Context context, JSONObject checked, JSONObject plan, Source[] scenes, JSONArray sceneProbes, Source[] tracks, JSONArray trackProbes,
            Runnable guard, IntConsumer progress) throws Exception {
        File dir = new File(context.getCacheDir(), "video-edit");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("편집용 임시 폴더를 만들지 못했어요.");
        List<File> temporary = new ArrayList<>();
        File output = new File(dir, "edit-" + UUID.randomUUID() + ".mp4");
        try {
            int width = plan.optInt("width"), height = plan.optInt("height");
            Captions captions = new Captions(width, height, checked.optString("position", "bottom"), plan.optJSONArray("captions"));
            JSONArray sceneList = checked.optJSONArray("scenes"), pieces = plan.optJSONArray("pieces");
            Uri[] cards = new Uri[sceneList.length()];
            List<EditedMediaItem> visuals = new ArrayList<>();
            for (int p = 0; p < pieces.length(); p++) {
                guard.run();
                JSONObject piece = pieces.optJSONObject(p); int index = piece.optInt("scene"); String kind = piece.optString("kind");
                MediaItem.Builder item = new MediaItem.Builder();
                List<AudioProcessor> audio = new ArrayList<>(); List<Effect> video = new ArrayList<>();
                video.add(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT));
                if (piece.optInt("caption", -1) >= 0) video.add(new OverlayEffect(ImmutableList.<TextureOverlay>of(new CaptionOverlay(captions, piece.optInt("caption")))));
                boolean still = !kind.equals("video");
                if (kind.equals("card")) {
                    if (cards[index] == null) {
                        JSONObject scene = sceneList.optJSONObject(index);
                        File card = card(dir, width, height, scene.optString("card"), scene.optString("color", "#000000"));
                        temporary.add(card); cards[index] = Uri.fromFile(card);
                    }
                    item.setUri(cards[index]).setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(piece.optLong("lengthMs"));
                } else if (kind.equals("image")) {
                    item.setUri(scenes[index].uri).setMimeType(sceneProbes.optJSONObject(index).optString("mime", MimeTypes.IMAGE_PNG)).setImageDurationMs(piece.optLong("lengthMs"));
                } else {
                    item.setUri(scenes[index].uri).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(piece.optLong("fromMs")).setEndPositionMs(piece.optLong("toMs")).build());
                    double volume = piece.optDouble("volume", 1);
                    if (sceneProbes.optJSONObject(index).optBoolean("audio") && volume != 1) audio.add(volume(volume));
                }
                EditedMediaItem.Builder edited = new EditedMediaItem.Builder(item.build()).setEffects(new Effects(audio, video));
                if (still) edited.setFrameRate(FRAME_RATE);
                visuals.add(edited.build());
            }
            List<EditedMediaItemSequence> sequences = new ArrayList<>();
            sequences.add(new EditedMediaItemSequence(visuals, false));
            JSONArray planned = plan.optJSONArray("tracks");
            for (int t = 0; planned != null && t < planned.length(); t++) {
                guard.run();
                JSONObject track = planned.optJSONObject(t); int index = track.optInt("track"); JSONObject probe = trackProbes.optJSONObject(index);
                List<EditedMediaItem> items = new ArrayList<>();
                long silence = track.optLong("silenceMs");
                if (silence > 0) {
                    File quiet = silence(dir, silence, probe.optInt("rate", 48_000), probe.optInt("channels", 2));
                    temporary.add(quiet);
                    items.add(new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(quiet))).build());
                }
                MediaItem sound = new MediaItem.Builder().setUri(tracks[index].uri).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(track.optLong("fromMs")).setEndPositionMs(track.optLong("toMs")).build()).build();
                List<AudioProcessor> processors = new ArrayList<>();
                double volume = track.optDouble("volume", 1);
                if (volume != 1) processors.add(volume(volume));
                EditedMediaItem.Builder edited = new EditedMediaItem.Builder(sound).setEffects(new Effects(processors, new ArrayList<>()));
                if (probe.optBoolean("video")) edited.setRemoveVideo(true);
                items.add(edited.build());
                sequences.add(new EditedMediaItemSequence(items, track.optBoolean("loop")));
            }
            Composition.Builder composition = new Composition.Builder(sequences);
            // Pictures and silent clips get silence, so a sequence of mixed clips and pictures still carries one audio track.
            if (plan.optBoolean("audio") || (planned != null && planned.length() > 0)) composition.experimentalSetForceAudioTrack(true);
            export(context, composition.build(), output, guard, progress);
            if (!output.isFile() || output.length() == 0) throw new IOException("편집한 영상 파일이 만들어지지 않았어요.");
            return output;
        } catch (Exception failure) {
            if (!output.delete()) output.deleteOnExit();
            throw failure;
        } finally {
            for (File file : temporary) if (!file.delete()) file.deleteOnExit();
        }
    }

    /** Runs the Transformer on its own looper thread and waits, stopping it when guard throws or the time limit passes. */
    private static void export(Context context, Composition composition, File output, Runnable guard, IntConsumer progress) throws Exception {
        HandlerThread thread = new HandlerThread("video-edit"); thread.start();
        Handler handler = new Handler(thread.getLooper());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<Transformer> running = new AtomicReference<>();
        AtomicInteger percent = new AtomicInteger(-1);
        try {
            handler.post(() -> {
                try {
                    Transformer transformer = new Transformer.Builder(context).setLooper(thread.getLooper())
                        .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(new Transformer.Listener() {
                            @Override public void onCompleted(Composition finished, ExportResult result) { done.countDown(); }
                            @Override public void onError(Composition finished, ExportResult result, ExportException error) { failure.set(error); done.countDown(); }
                        }).build();
                    running.set(transformer);
                    transformer.start(composition, output.getAbsolutePath());
                } catch (Exception error) { failure.set(error); done.countDown(); }
            });
            long until = System.currentTimeMillis() + TIMEOUT_MS; int shown = -1;
            while (true) {
                boolean finished;
                try { finished = done.await(1, TimeUnit.SECONDS); }
                catch (InterruptedException stopped) { cancel(handler, running); Thread.currentThread().interrupt(); throw new CancellationException("작업을 멈췄어요."); }
                if (finished) break;
                try { guard.run(); }
                catch (RuntimeException stop) { cancel(handler, running); throw stop; }
                if (System.currentTimeMillis() > until) { cancel(handler, running); throw new IOException("영상 편집이 " + (TIMEOUT_MS / 60_000) + "분 안에 끝나지 않아 멈췄어요."); }
                handler.post(() -> {
                    Transformer transformer = running.get(); if (transformer == null) return;
                    ProgressHolder holder = new ProgressHolder();
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) percent.set(holder.progress);
                });
                int now = percent.get();
                if (now >= 0 && now != shown) { shown = now; progress.accept(now); }
            }
            Exception error = failure.get();
            if (error != null) throw new IOException("영상 편집에 실패했어요: " + reason(error), error);
        } finally {
            thread.quitSafely();
        }
    }
    private static void cancel(Handler handler, AtomicReference<Transformer> running) {
        handler.post(() -> { Transformer transformer = running.get(); if (transformer != null) transformer.cancel(); });
    }
    private static String reason(Exception error) {
        String message = error.getMessage();
        if (error instanceof ExportException export) message = "code " + export.errorCode + (message == null ? "" : " · " + message);
        Throwable cause = error.getCause();
        if (cause != null && cause.getMessage() != null) message = (message == null ? "" : message + " · ") + cause.getMessage();
        message = message == null ? error.getClass().getSimpleName() : message;
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    /** A volume change for any channel count up to 8 (0 silent, 1 unchanged, 2 double). */
    private static AudioProcessor volume(double volume) {
        ChannelMixingAudioProcessor processor = new ChannelMixingAudioProcessor();
        for (int channels = 1; channels <= 8; channels++) processor.putChannelMixingMatrix(ChannelMixingMatrix.create(channels, channels).scaleBy((float) volume));
        return processor;
    }

    /** A 16-bit PCM WAV of silence in the format of the audio that follows it, so the sequence never changes format mid-way. */
    static File silence(File dir, long ms, int rate, int channels) throws IOException {
        int sampleRate = rate > 0 && rate <= 192_000 ? rate : 48_000, count = channels > 0 && channels <= 8 ? channels : 2;
        long frames = ms * sampleRate / 1000, data = frames * count * 2;
        if (data > Integer.MAX_VALUE - 64) throw new IOException("소리 앞의 빈 시간이 너무 길어요.");
        File file = new File(dir, "silence-" + UUID.randomUUID() + ".wav");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
            out.write(dev.jun.gyeot.core.VideoEdit.wavHeader((int) data, sampleRate, count));
            byte[] zeros = new byte[1 << 16]; long left = data;
            while (left > 0) { int n = (int) Math.min(zeros.length, left); out.write(zeros, 0, n); left -= n; }
        }
        return file;
    }

    /** A title card: the text centered on the background color, in white or black for contrast. */
    static File card(File dir, int width, int height, String text, String color) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            int background = Color.parseColor(color == null || !color.matches("#[0-9A-Fa-f]{6}") ? "#000000" : color);
            bitmap.eraseColor(background);
            double light = 0.299 * Color.red(background) + 0.587 * Color.green(background) + 0.114 * Color.blue(background);
            Canvas canvas = new Canvas(bitmap); float base = Math.min(width, height);
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG); paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(base * 0.075f); paint.setColor(light > 150 ? Color.BLACK : Color.WHITE);
            int wrap = (int) (width * 0.84f);
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, wrap).setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0, 1.12f).setMaxLines(8).setEllipsize(TextUtils.TruncateAt.END).build();
            canvas.save(); canvas.translate((width - wrap) / 2f, (height - layout.getHeight()) / 2f); layout.draw(canvas); canvas.restore();
            File file = new File(dir, "card-" + UUID.randomUUID() + ".png");
            try (OutputStream out = new FileOutputStream(file)) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("카드 그림을 만들지 못했어요."); }
            return file;
        } finally { bitmap.recycle(); }
    }

    /** One frame-sized caption picture shared by every piece; redrawn when a different piece asks for it. */
    static final class Captions {
        private final int width, height; private final String position; private final JSONArray cues;
        private Bitmap bitmap; private Object owner;
        Captions(int width, int height, String position, JSONArray cues) { this.width = width; this.height = height; this.position = position; this.cues = cues == null ? new JSONArray() : cues; }
        synchronized Bitmap draw(Object who, int index) {
            if (bitmap == null) bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (owner != who) { owner = who; paint(index); }
            return bitmap;
        }
        private void paint(int index) {
            bitmap.eraseColor(Color.TRANSPARENT);
            JSONObject cue = cues.optJSONObject(index); if (cue == null) return;
            String text = cue.optString("text"); if (text.isBlank()) return;
            Canvas canvas = new Canvas(bitmap); float base = Math.min(width, height);
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG); paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(base * 0.05f); paint.setColor(Color.WHITE);
            int wrap = (int) (width * 0.86f);
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, wrap).setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0, 1.1f).setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build();
            float lineWidth = 0; for (int i = 0; i < layout.getLineCount(); i++) lineWidth = Math.max(lineWidth, layout.getLineWidth(i));
            float padX = base * 0.03f, padY = base * 0.016f, boxWidth = lineWidth + padX * 2, boxHeight = layout.getHeight() + padY * 2;
            float top = switch (position) {
                case "top" -> height * 0.08f;
                case "middle" -> (height - boxHeight) / 2f;
                // Vertical videos keep captions above the app buttons of Shorts and Reels.
                default -> height - boxHeight - height * (height > width ? 0.2f : 0.07f);
            };
            Paint box = new Paint(Paint.ANTI_ALIAS_FLAG); box.setColor(Color.argb(160, 0, 0, 0));
            float left = (width - boxWidth) / 2f, radius = base * 0.015f;
            canvas.drawRoundRect(new RectF(left, top, left + boxWidth, top + boxHeight), radius, radius, box);
            canvas.save(); canvas.translate((width - wrap) / 2f, top + padY); layout.draw(canvas); canvas.restore();
        }
    }
    private static final class CaptionOverlay extends BitmapOverlay {
        private final Captions captions; private final int index;
        CaptionOverlay(Captions captions, int index) { this.captions = captions; this.index = index; }
        @Override public Bitmap getBitmap(long presentationTimeUs) { return captions.draw(this, index); }
    }

    /** A Korean progress line for the Work screen. */
    static String stage(int percent) { return String.format(Locale.ROOT, "휴대폰에서 영상 편집하는 중 · %d%%", Math.max(0, Math.min(100, percent))); }
}
