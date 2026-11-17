package dev.jun.gyeot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.ProfileMotion;
import dev.jun.gyeot.core.ProfileShelf;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Makes a picture's expression GIFs with GPT Image 2.6, one mood after another, in the background of the app process:
 * closing the sheet does not stop it, and every finished GIF is kept in the library at once, so stopping (or the app
 * being closed) loses only the one being drawn. One series at a time.
 */
final class ProfilePack {
    private ProfilePack() {}
    private static final ExecutorService RUN = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "profile-pack"); t.setDaemon(true); return t; });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Job current;

    /** One series: which picture, how far, and what it is doing now. Read on the main thread through listeners. */
    static final class Job {
        final String entryId; final int total; final List<String> moods;
        volatile int done; volatile String now = "", step = "", error = "", note = ""; volatile boolean running = true, stopped;
        final AtomicReference<Runnable> cancel = new AtomicReference<>();
        Job(String entryId, List<String> moods) { this.entryId = entryId; this.moods = moods; this.total = moods.size(); }
        String status() {
            if (running) return done + "/" + total + " · " + (!step.isEmpty() ? step : now.isEmpty() ? "준비하는 중…" : now + " 표정 만드는 중…");
            String told = note.isEmpty() ? "" : "\n" + note;
            if (!error.isEmpty()) return done + "/" + total + "개 만들었어요 · " + error;
            return (stopped ? done + "/" + total + "개 만들고 멈췄어요." : "표정 " + done + "개를 다 만들었어요.") + told;
        }
    }
    static Job current() { return current; }
    static boolean busy() { Job j = current; return j != null && j.running; }
    static void listen(Runnable r) { LISTENERS.add(r); }
    static void unlisten(Runnable r) { LISTENERS.remove(r); }
    private static void changed() { MAIN.post(() -> { for (Runnable r : LISTENERS) try { r.run(); } catch (RuntimeException ignored) {} }); }

    /**
     * Starts the moods still missing for a picture. reference sends the picture itself to OpenAI so the character stays
     * the same; without it, character (the description) is all GPT Image 2.6 gets.
     */
    static Job start(Context context, AppStore store, ProfileShelf.Entry entry, List<String> moods, String character, boolean reference, boolean codex) {
        synchronized (ProfilePack.class) {
            if (busy()) throw new IllegalStateException("이미 표정 GIF를 만들고 있어요. 끝나거나 멈춘 뒤에 다시 해 주세요.");
            if (moods.isEmpty()) throw new IllegalStateException("만들 표정을 골라 주세요.");
            if (!reference && ProfileMotion.character(character).isEmpty()) throw new IllegalStateException("참고 그림 없이 만들려면 캐릭터 설명을 적어 주세요.");
            Context app = context.getApplicationContext();
            Job job = new Job(entry.id, List.copyOf(moods)); current = job; changed();
            RUN.execute(() -> run(app, store, job, character, reference, codex));
            return job;
        }
    }
    static void stop() {
        Job job = current; if (job == null || !job.running) return;
        job.stopped = true; Runnable c = job.cancel.getAndSet(null);
        if (c != null) try { c.run(); } catch (RuntimeException ignored) {}
        changed();
    }

    private static void run(Context app, AppStore store, Job job, String character, boolean reference, boolean codex) {
        ProfileArt.Route route = new ProfileArt.Route();
        route.step = text -> { job.step = text == null ? "" : text; changed(); };
        boolean blankPrompt = false, keptDescription = false;
        try {
            ProfileShelf.Entry entry = ProfileLibrary.read(app).find(job.entryId);
            if (entry == null) throw new IllegalStateException("보관함에서 그림이 지워졌어요.");
            blankPrompt = entry.prompt.isBlank() && character.isBlank();
            // The description is remembered for next time (and for a fallback without the reference).
            if (!character.isBlank() && !character.equals(entry.prompt)) try { ProfileLibrary.describe(app, entry.id, character); } catch (Exception ignored) {}
            byte[] picture = reference ? AssistantProfile.avatar(ProfileLibrary.image(app, entry)) : null;
            for (String id : job.moods) {
                if (job.stopped) break;
                ProfileMotion.Mood mood = ProfileMotion.mood(id); if (mood == null) continue;
                job.now = mood.label(); changed();
                byte[] gif = ProfileArt.maker.gif(store, character, mood, picture, job.cancel, codex, route);
                if (job.note.isEmpty()) {
                    if (route.gifDisabled)
                        job.note = "이 연결은 아직 GIF를 바로 만들지 못해서, 표정마다 9장면을 한 장에 그린 뒤 휴대폰에서 GIF로 묶었어요. 움직임이 조금 덜 매끄러울 수 있어요.";
                    else if (route.editsRefused && reference)
                        job.note = "이 연결은 참고 그림을 직접 움직이지 못해서 " + (route.described.isEmpty() ? "적은 설명으로" : "그림을 보고 만든 설명으로")
                            + " 그렸어요. 캐릭터가 그림과 조금 다를 수 있어요.";
                }
                if (!keptDescription && blankPrompt && !route.described.isEmpty()) keptDescription = keepDescription(app, job.entryId, route.described);
                if (job.stopped) break;
                AssistantProfile.Decoded check = AssistantProfile.decode(gif);
                if (check.animation() instanceof android.graphics.drawable.Animatable a) a.stop();
                ProfileLibrary.saveMood(app, job.entryId, id, gif);
                job.done++; changed(); AssistantProfile.refreshAll();
            }
        } catch (Exception | OutOfMemoryError e) {
            if (!job.stopped) job.error = e.getMessage() == null ? "표정 GIF를 만들지 못했어요." : e.getMessage();
        } finally {
            // Also when the series ends early: the next try starts from the description instead of looking again.
            if (!keptDescription && blankPrompt && !route.described.isEmpty()) keepDescription(app, job.entryId, route.described);
            job.running = false; job.now = ""; job.step = ""; changed(); AssistantProfile.refreshAll();
        }
    }
    /**
     * Holds a picture's expression GIFs still, without drawing them again (흔들림 줄이기): each kept GIF is read, its frames
     * are matched to each other and it is written back when that moved any (core/GifSteady). GIFs made before every new one
     * was steadied, or steadied by the older rule that left a frame far out of place where it was. Returns {GIFs changed,
     * GIFs looked at}; a GIF that cannot be read or is already still is left as it is.
     */
    static int[] steady(Context context, String entryId) throws java.io.IOException {
        Context app = context.getApplicationContext();
        ProfileShelf.Entry entry = ProfileLibrary.read(app).find(entryId);
        if (entry == null) throw new java.io.IOException("보관함에서 그림이 지워졌어요.");
        int changed = 0, seen = 0;
        for (String mood : List.copyOf(entry.moods)) {
            byte[] gif; try { gif = ProfileLibrary.mood(app, entry, mood); } catch (java.io.IOException missing) { continue; }
            seen++;
            dev.jun.gyeot.core.GifSteady.Result result;
            try { result = dev.jun.gyeot.core.GifSteady.steady(gif, ProfileMotion.GIF_LIMIT); } catch (RuntimeException | OutOfMemoryError failed) { continue; }
            if (!result.changed()) continue;
            // Only a GIF the profile can show replaces the one kept.
            try { AssistantProfile.Decoded check = AssistantProfile.decode(result.gif()); if (check.animation() instanceof android.graphics.drawable.Animatable a) a.stop(); }
            catch (Exception | OutOfMemoryError unreadable) { continue; }
            ProfileLibrary.saveMood(app, entryId, mood, result.gif()); changed++;
        }
        if (changed > 0) AssistantProfile.refreshAll();
        return new int[] {changed, seen};
    }
    /** A description made from the picture is kept as its prompt, so the sheet shows it next time and it is not made again. */
    private static boolean keepDescription(Context app, String id, String description) {
        try { ProfileLibrary.describe(app, id, description); } catch (Exception ignored) {}
        return true;
    }
}
