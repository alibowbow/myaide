package dev.jun.gyeot;

import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.CaptionLanguage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A caption in Korean on a tap (the voice assistant's conversation, 음성 대화's bar on every screen, 판서's caption): the
 * main chat's AI translates it once, asked with no tools, and the answer is kept for the process so a second tap is
 * instant. Callbacks come on the main thread.
 */
final class CaptionTranslator {
    private CaptionTranslator() {}
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, String> DONE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 80; }
    };

    /** The translation already made for these words, or null. */
    static synchronized String known(String text) { return DONE.get(text); }

    /** Translates the words into Korean; done gets the translation, failed why it could not. */
    static void translate(AppStore store, String text, Consumer<String> done, Consumer<String> failed) {
        String have = known(text);
        if (have != null) { MAIN.post(() -> done.accept(have)); return; }
        Thread work = new Thread(() -> {
            try {
                ScopedModels models = ScopedModels.main(store); String engine = models.engine();
                if (engine == null || engine.isBlank() || !store.ready(engine)) { MAIN.post(() -> failed.accept("번역하려면 메인 대화의 AI를 연결해 주세요")); return; }
                String model = models.model(engine);
                String effort = new TeamRoster(store).profile(engine, model).normalize("low");
                String answer = CaptionLanguage.clean(FileAiOperationsUi.complete(store, FileAiOperationsUi.Connection.capture(store, engine), model, effort,
                    CaptionLanguage.INSTRUCTIONS, CaptionLanguage.input(text), () -> {}, new AtomicReference<>()));
                if (answer.isEmpty()) { MAIN.post(() -> failed.accept("번역 결과가 비어 있어요")); return; }
                synchronized (CaptionTranslator.class) { DONE.put(text, answer); }
                MAIN.post(() -> done.accept(answer));
            } catch (Exception e) {
                String why = e.getMessage();
                MAIN.post(() -> failed.accept("번역하지 못했어요" + (why == null || why.isBlank() ? "" : " · " + why)));
            }
        }, "gyeot-caption-translate");
        work.setDaemon(true); work.start();
    }
}
