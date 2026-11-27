package dev.jun.gyeot;

import dev.jun.gyeot.core.BuddyLife;
import dev.jun.gyeot.core.BuddyMind;
import dev.jun.gyeot.core.BuddyWorld;
import dev.jun.gyeot.core.ChatStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레 자동 점검 off the phone (tools/buddy_eval.py compiles and runs this, 준: "자동테스트도 구현해줘"): every scenario of
 * assets/buddy/scenarios.json through the same words layer as the phone (BuddyCheck.words), then the ones for the brain
 * through the very request 퍼레's voice talk sends (BuddyPrompt.voiceRules, BuddyTools.rule and its tools, core/ChatStream,
 * the retry with a tool required when it only promised) to NVIDIA NIM's quick model (BuddyPrompt.NIM_MODEL), one at a
 * time, judged as the phone judges (BuddyCheck.judge). What only the phone knows (the user's memories, saved places, the
 * phone's state) is left out or a fixed sample. --path chat asks as 퍼레와 대화 does (the text form, NVIDIA's chat model).
 *
 * Exit: 0 all passed, or the brain could not be reached (said why: no network, no key, CI); 1 a scenario failed; 2 the
 * scenario file does not read.
 */
public final class BuddyEval {
    private BuddyEval() {}
    static final String NIM = "https://integrate.api.nvidia.com/v1/chat/completions";
    /** --raw: each brain answer's words and tool calls as they came. */
    static boolean raw;

    public static void main(String[] args) throws Exception {
        String file = "app/src/main/assets/buddy/scenarios.json", path = "voice", only = null, out = null, model = BuddyPrompt.NIM_MODEL;
        boolean wordsOnly = false, verbose = false;
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "--scenarios" -> file = args[++i];
            case "--path" -> path = args[++i];
            case "--only" -> only = args[++i];
            case "--json" -> out = args[++i];
            case "--model" -> model = args[++i];
            case "--words-only" -> wordsOnly = true;
            case "--verbose" -> verbose = true;
            case "--raw" -> raw = true;
            default -> { System.err.println("모르는 인자: " + args[i]); System.exit(2); }
        }
        List<BuddyCheck.Scenario> all;
        try { all = BuddyCheck.parse(Files.readString(Path.of(file))); }
        catch (IllegalArgumentException broken) { System.err.println(broken.getMessage()); System.exit(2); return; }
        System.out.println("퍼레 자동 점검 · 문장 " + all.size() + "개 · " + file);

        List<BuddyCheck.Result> results = new ArrayList<>(BuddyCheck.words(all));
        int[] w = BuddyCheck.count(results, BuddyCheck.WORDS);
        System.out.println("\n[말 · AI 없이] " + w[0] + "/" + w[1] + " 통과");
        for (BuddyCheck.Result r : results) if (verbose || !r.pass()) System.out.println(row(r));

        String stopped = ""; List<String> used = new ArrayList<>();
        if (!wordsOnly) {
            List<String> paths = path.equals("all") ? List.of("voice", "chat") : List.of(path);
            for (String p : paths) {
                String m = p.equals("chat") && model.equals(BuddyPrompt.NIM_MODEL) ? dev.jun.gyeot.core.NimProtocol.MODEL : model;
                System.out.println("\n[두뇌 · " + (p.equals("chat") ? "글(퍼레와 대화)" : "음성 대화") + "] NVIDIA NIM · " + m);
                used.add((p.equals("chat") ? "글 " : "음성 ") + "NVIDIA NIM · " + m);
                stopped = brain(all, results, p, m, only);
                if (!stopped.isEmpty()) { System.out.println("멈춤: " + stopped); break; }
            }
        }
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(new Date());
        System.out.println("\n" + BuddyCheck.report(results, String.join(" · ", used), when, stopped));
        if (out != null) {
            JSONArray rows = new JSONArray();
            for (BuddyCheck.Result r : results) rows.put(new JSONObject().put("layer", r.layer()).put("path", r.path()).put("said", r.said()).put("pass", r.pass())
                .put("expected", r.expected()).put("got", r.got()).put("toolMs", r.toolMs()).put("wordsMs", r.wordsMs()));
            Path o = Path.of(out); if (o.getParent() != null) Files.createDirectories(o.getParent());
            Files.writeString(o, new JSONObject().put("when", when).put("brain", String.join(" · ", used)).put("stopped", stopped).put("summary", BuddyCheck.summary(results)).put("results", rows).toString(1));
        }
        boolean failed = !BuddyCheck.failures(results).isEmpty();
        // The brain out of reach (CI: no network or key) is said, not failed: the words layer still counts.
        System.exit(failed ? 1 : 0);
    }

    private static String row(BuddyCheck.Result r) {
        String time = r.toolMs() > 0 ? "  " + BuddyCheck.secs(r.toolMs()) + (r.wordsMs() > 0 ? " / 첫 말 " + BuddyCheck.secs(r.wordsMs()) : "") : "";
        return (r.pass() ? "  ✓ " : "  ✗ ") + pad(r.said(), 22) + "  기대 " + pad(r.expected(), 28) + "  실제 " + r.got() + time;
    }
    private static String pad(String s, int width) {
        int w = 0; for (int i = 0; i < s.length(); i++) w += s.charAt(i) >= 0x1100 ? 2 : 1;
        return w >= width ? s : s + " ".repeat(width - w);
    }

    /** The brain's scenarios on one path, one request at a time; "" when all were asked, else why it stopped. */
    private static String brain(List<BuddyCheck.Scenario> all, List<BuddyCheck.Result> results, String path, String model, String only) throws Exception {
        String key = firstNonBlank(System.getenv("NIM_API_KEY"), System.getenv("NVIDIA_API_KEY"), "proxy-injected");
        int asked = 0, unreachable = 0;
        for (BuddyCheck.Scenario s : all) {
            if (!s.toBrain() || only != null && !s.said().contains(only)) continue;
            Answer a;
            try { a = path.equals("chat") ? chat(s, key, model) : voice(s, key, model); }
            catch (Refused refused) {
                if (asked == 0 || refused.status == 401 || refused.status == 403) return "NVIDIA NIM이 받지 않았어요 (HTTP " + refused.status + (refused.status == 401 || refused.status == 403 ? ": 키가 없거나 맞지 않아요" : "") + ")";
                a = new Answer(null, "", "HTTP " + refused.status, 0, 0);
            } catch (java.io.IOException offline) {
                if (asked == 0 || ++unreachable >= 2) return "NVIDIA NIM에 닿지 않아요 (" + offline.getClass().getSimpleName() + (offline.getMessage() == null ? "" : ": " + offline.getMessage()) + ")";
                a = new Answer(null, "", "연결 끊김", 0, 0);
            }
            if (a.failed == null) unreachable = 0;
            asked++;
            BuddyCheck.Result r = BuddyCheck.judge(s, path.equals("chat") ? BuddyCheck.CHAT : BuddyCheck.VOICE, a.act, a.text, a.failed, a.toolMs, a.wordsMs);
            results.add(r); System.out.println(row(r)); System.out.flush();
        }
        return "";
    }
    record Answer(JSONObject act, String text, String failed, long toolMs, long wordsMs) {}
    static final class Refused extends java.io.IOException { final int status; Refused(int status) { super("HTTP " + status); this.status = status; } }

    /** What 퍼레 sees now, as the phone writes it (BuddyTalk.replyInput) without the user's own: the hour, its life, a fixed phone state. */
    private static String context() {
        long now = System.currentTimeMillis();
        String when = new SimpleDateFormat("yyyy년 M월 d일 EEEE a h:mm", Locale.KOREA).format(new Date(now));
        return "지금: " + when + "\n" + BuddyLife.words(BuddyLife.read("", now), now) + "\n" + "휴대폰 상태(물을 때만 알려 줄 것): 배터리 80%. 와이파이 연결.\n\n";
    }
    private static String world() {
        BuddyWorld.know(BuddyBoard.CLOTHES, BuddyBoard.PLAYS);
        return BuddyWorld.words(new JSONObject(), System.currentTimeMillis());
    }
    private static String persona() { return BuddyMind.persona("퍼레", BuddyMind.TITLE); }

    /** The voice talk's turn (BuddyTalk.reply with no talk before it): tools streamed, the thing to do read back, a retry with one required when it only promised. */
    private static Answer voice(BuddyCheck.Scenario s, String key, String model) throws Exception {
        String said = BuddyCheck.heard(s.said());
        String instructions = BuddyPrompt.voiceRules(persona(), BuddyMind.TITLE, false) + world();
        boolean self = BuddyPrompt.self(said, List.of());
        JSONArray tools = self ? BuddyTools.TOOLS : BuddyTools.PHONE;
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", context() + BuddyPrompt.voiceTail("", List.of(), false, said)));
        long begun = System.nanoTime(); long[] first = {0};
        ChatStream.Turn t = send(key, ChatStream.body(model, instructions + BuddyTools.rule(self), messages, null, 0.7, tools, "auto"), piece -> { if (first[0] == 0 && !piece.isBlank()) first[0] = System.nanoTime(); });
        if (raw) System.out.println("    ↳ 말: " + t.text().replace("\n", " ") + " · 도구: " + t.calls());
        JSONObject act = BuddyTools.act(t.calls()); if (act == null) act = BuddyPrompt.act(t.text());
        if (act == null && BuddyTools.promised(said, t.text())) {
            ChatStream.Turn again = send(key, ChatStream.body(model, instructions + BuddyTools.rule(true), messages, null, 0.2, BuddyTools.orNothing(), "required"), null);
            if (raw) System.out.println("    ↳ 다시(도구 꼭): " + again.calls());
            act = BuddyTools.act(again.calls());
        }
        long end = System.nanoTime();
        String words = BuddyPrompt.spoken(t.text());
        if ((words == null || words.isBlank()) && act == null) return new Answer(null, "", "빈 답", ms(begun, end), 0);
        return new Answer(act, t.text().replaceAll("(?s)<think>.*?</think>", "").strip(), null, ms(begun, end), first[0] == 0 ? 0 : ms(begun, first[0]));
    }
    /** 퍼레와 대화's turn (BuddyTalk.chatText on NVIDIA): the text form, the thing to do read from its "할일:" line. */
    private static Answer chat(BuddyCheck.Scenario s, String key, String model) throws Exception {
        String now = new SimpleDateFormat("yyyy년 M월 d일 EEEE a h:mm", Locale.KOREA).format(new Date());
        String instructions = BuddyPrompt.chatRules(persona(), now) + BuddyPrompt.ACTS + BuddyPrompt.CHAT_PHONE + "휴대폰 상태: 배터리 80%. 와이파이 연결.\n" + world();
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", BuddyPrompt.chatInput("[]", s.said())));
        JSONObject body = dev.jun.gyeot.core.NimProtocol.request(model, instructions, messages, null);
        long begun = System.nanoTime(); long[] first = {0};
        ChatStream.Turn t = send(key, body, piece -> { if (first[0] == 0 && !piece.isBlank()) first[0] = System.nanoTime(); });
        long end = System.nanoTime();
        String text = t.text().replaceAll("(?s)<think>.*?</think>", "").strip();
        return new Answer(BuddyPrompt.act(text), text.replaceAll("(?m)^\\s*할\\s*일\\s*[:：].*$", "").strip(), text.isEmpty() ? "빈 답" : null, ms(begun, end), first[0] == 0 ? 0 : ms(begun, first[0]));
    }
    private static long ms(long from, long to) { return Math.max(1, (to - from) / 1_000_000); }

    /** One streamed request to NIM; 429 and 502 waited out twice, as the app's NIM client does once (its Retry-After, else a few seconds). */
    private static ChatStream.Turn send(String key, JSONObject body, java.util.function.Consumer<String> onText) throws Exception {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection c = (HttpURLConnection) URI.create(NIM).toURL().openConnection();
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(10_000); c.setReadTimeout(30_000);
            c.setRequestProperty("Authorization", "Bearer " + key); c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "text/event-stream");
            try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = c.getResponseCode();
            if (dev.jun.gyeot.core.NimProtocol.retries(status) && attempt < 2) { long wait = 3; try { wait = Math.max(1, Math.min(30, Long.parseLong(c.getHeaderField("Retry-After")))); } catch (Exception none) {} drain(c.getErrorStream()); Thread.sleep(wait * 1000); continue; }
            if (status != 200) { drain(c.getErrorStream()); throw new Refused(status); }
            try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) { return ChatStream.read(in, onText); }
            finally { c.disconnect(); }
        }
    }
    private static void drain(InputStream in) { if (in == null) return; try (in) { in.readAllBytes(); } catch (Exception ignored) {} }
    private static String firstNonBlank(String... values) { for (String v : values) if (v != null && !v.isBlank()) return v; return ""; }
}
