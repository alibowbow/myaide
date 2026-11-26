package dev.jun.gyeot;

import android.content.Context;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import org.json.JSONObject;

/**
 * 퍼레's short words in its own Typecast voice, made once and kept on the phone (준: "프리셋 보이스 100개 만들까?", "타입캐스트로"):
 * a sound the moment the user stops talking (음~, 오잉?, 알았어!) while the answer is still being made, so 퍼레 never goes
 * quiet, and its hellos, goodbyes and "say that again". About 500 characters of the month's 15,000, once per voice: made in
 * the background when a talk starts and some are missing, one after another, stopping at the first refusal (tried again next
 * talk). Kept by voice, so another voice gets its own set. Any thread.
 */
final class VoicePresets {
    private VoicePresets() {}
    /** kind, words: think (while the answer is made), ok (a thing to do), react, hello, bye, again (not heard), yes. */
    static final String[][] LINES = {
        {"think", "음~"}, {"think", "오잉?"}, {"think", "어디 보자~"}, {"think", "잠깐만!"}, {"think", "흐음…"}, {"think", "음음!"}, {"think", "아하!"},
        {"think", "오호~"}, {"think", "그렇구나~"}, {"think", "어디 한번 볼까?"}, {"think", "생각 중!"}, {"think", "두구두구…"}, {"think", "음, 그러니까~"},
        {"think", "오, 좋아!"}, {"think", "알았어, 잠깐만!"}, {"think", "흠흠."}, {"think", "어머나!"}, {"think", "오오!"}, {"think", "그거 말이지~"},
        {"think", "응응!"}, {"think", "에헤~"}, {"think", "아, 그거!"}, {"think", "음, 보자보자."}, {"think", "오케이!"}, {"think", "좋은 질문!"},
        {"think", "이야~"}, {"think", "후후~"}, {"think", "앗, 그거!"}, {"think", "아항~"}, {"think", "에이~"},
        {"ok", "알았어!"}, {"ok", "바로 할게!"}, {"ok", "맡겨 줘!"}, {"ok", "오케이, 해 볼게!"}, {"ok", "지금 할게!"}, {"ok", "알겠어, 잠깐만!"},
        {"ok", "출동!"}, {"ok", "접수 완료!"}, {"ok", "좋아, 간다!"}, {"ok", "뿅, 할게!"}, {"ok", "그래그래, 해 줄게!"}, {"ok", "응, 바로!"},
        {"react", "헤헤."}, {"react", "우와!"}, {"react", "짠!"}, {"react", "뿅!"}, {"react", "히히."}, {"react", "에헤헤."}, {"react", "앗!"},
        {"react", "와아!"}, {"react", "오호!"}, {"react", "야호!"}, {"react", "꺄!"}, {"react", "두둥!"}, {"react", "헉!"}, {"react", "깜짝이야!"},
        {"react", "대박!"}, {"react", "멋지다!"}, {"react", "좋아좋아!"}, {"react", "신난다!"}, {"react", "킥킥."}, {"react", "어라?"},
        {"hello", "응, 듣고 있어!"}, {"hello", "불렀어?"}, {"hello", "나 여기 있어!"}, {"hello", "응, 말해 봐!"}, {"hello", "짠, 왔어!"},
        {"hello", "뭐든 말해 줘!"}, {"hello", "응응, 듣는 중!"}, {"hello", "안녕!"}, {"hello", "반가워!"}, {"hello", "오, 왔어?"},
        {"bye", "또 불러 줘!"}, {"bye", "안녕, 또 봐!"}, {"bye", "이따 또 얘기하자!"}, {"bye", "그럼 이만, 뿅!"}, {"bye", "언제든 불러!"},
        {"bye", "다음에 또 놀자!"}, {"bye", "나 여기서 기다릴게!"}, {"bye", "바이바이!"},
        {"again", "응? 다시 말해 줄래?"}, {"again", "잘 못 들었어, 한 번만 더!"}, {"again", "어? 뭐라고?"}, {"again", "조금만 크게 말해 줄래?"},
        {"again", "다시 한 번만!"}, {"again", "음, 놓쳤어. 다시!"}, {"again", "미안, 못 들었어!"},
        {"yes", "응!"}, {"yes", "그럼!"}, {"yes", "맞아!"}, {"yes", "당연하지!"}, {"yes", "아니~"}, {"yes", "글쎄~"}, {"yes", "그렇지!"}, {"yes", "물론!"}
    };
    private static final Random RANDOM = new Random();
    private static final Deque<Integer> recent = new ArrayDeque<>();
    private static volatile boolean making;

    /** The folder of this voice's set. */
    static File dir(Context c, String voiceId) { return new File(new File(c.getFilesDir(), "buddy-voice"), "presets-" + voiceId.replaceAll("[^A-Za-z0-9_-]", "_")); }
    private static File file(Context c, String voiceId, int i) { return new File(dir(c, voiceId), i + ".mp3"); }
    /** How many of the hundred this voice has, of how many. */
    static int[] ready(Context c, AppStore store) {
        String id = store.typecast.voice().optString("id"); int n = 0;
        if (!id.isBlank()) for (int i = 0; i < LINES.length; i++) if (file(c, id, i).isFile()) n++;
        return new int[]{n, LINES.length};
    }

    /** Makes the missing ones for the chosen voice, in the background, once at a time; stops at the first refusal or the month's end. */
    static void prepare(Context context, AppStore store) {
        TypecastAccount t = store.typecast; JSONObject voice = t.voice(); String id = voice.optString("id");
        if (making || !t.connected() || id.isBlank()) return;
        Context c = context.getApplicationContext(); making = true;
        Thread worker = new Thread(() -> {
            try {
                dir(c, id).mkdirs();
                for (int i = 0; i < LINES.length; i++) {
                    File out = file(c, id, i); if (out.isFile()) continue;
                    String words = LINES[i][1]; if (!t.fits(words.length())) return;
                    File made;
                    try { made = BuddyVoice.synth(c, t, voice, words); } catch (Exception refused) { return; }
                    t.count(words.length());
                    File tmp = new File(out.getPath() + ".tmp");
                    try (java.io.InputStream in = new java.io.FileInputStream(made); java.io.OutputStream o = new java.io.FileOutputStream(tmp)) { in.transferTo(o); }
                    if (!tmp.renameTo(out)) { tmp.delete(); return; }
                    try { Thread.sleep(150); } catch (InterruptedException stop) { return; }
                }
            } catch (Exception broken) { /* tried again next talk */ }
            finally { making = false; }
        }, "gyeot-voice-presets");
        worker.setDaemon(true); worker.start();
    }

    /** One of this kind in the chosen voice, not one of the last few played; null when none is made yet. */
    static synchronized File pick(Context c, AppStore store, String kind) {
        String id = store.typecast.voice().optString("id"); if (id.isBlank()) return null;
        List<Integer> have = new ArrayList<>();
        for (int i = 0; i < LINES.length; i++) if (LINES[i][0].equals(kind) && file(c, id, i).isFile() && !recent.contains(i)) have.add(i);
        if (have.isEmpty()) for (int i = 0; i < LINES.length; i++) if (LINES[i][0].equals(kind) && file(c, id, i).isFile()) have.add(i);
        if (have.isEmpty()) return null;
        int i = have.get(RANDOM.nextInt(have.size()));
        recent.addLast(i); while (recent.size() > 6) recent.removeFirst();
        return file(c, id, i);
    }
}
