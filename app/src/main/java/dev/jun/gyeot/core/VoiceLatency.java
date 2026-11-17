package dev.jun.gyeot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Voice-call timings and Live token counts for the 오류 진단 보고서: monotonic milliseconds per call and per turn in bounded
 * rings, nearest-rank p50/p95 per engine·model. Numbers and model ids only: no words, audio, key, account or device id.
 * Live's server VAD decides when the user stopped talking and the app has no local detector, so the last input caption
 * before the answer stands in for the end of speech.
 */
public final class VoiceLatency {
    public static final int FORMAT = 1, TURN_LIMIT = 64, CALL_LIMIT = 16, TOOL_LIMIT = 128;
    /** Unset mark; monotonic clocks may start anywhere, zero included. */
    public static final long NONE = Long.MIN_VALUE;
    /** An audible frame later than this after the chunk arrived is a stale report, not a measurement. */
    static final long AUDIBLE_LIMIT_MS = 30_000;
    private final LongSupplier clock;
    private final Object lock = new Object();
    private final ArrayDeque<Call> calls = new ArrayDeque<>();
    private final ArrayDeque<Turn> turns = new ArrayDeque<>();
    /** How long the app took to answer each voice tool call (the call's arrival to its reply), most recent TOOL_LIMIT. */
    private final ArrayDeque<ToolTime> toolTimes = new ArrayDeque<>();
    private record ToolTime(String engine, String model, String name, long millis) {}

    /** Milliseconds on System.nanoTime, the clock AudioTrack timestamps use on Android. */
    public VoiceLatency() { this(() -> System.nanoTime() / 1_000_000); }
    public VoiceLatency(LongSupplier clock) { this.clock = clock; }
    public long now() { return clock.getAsLong(); }

    /** Push to talk (ConnectedVoice): one press is a call, the device recognizer hears and the main chat model answers. */
    public static final String CONNECTED = "connected";

    /** One voice call: engine is "live", "dictation" or CONNECTED; the model id is what the connection asked for. */
    public Call begin(String engine, String model) {
        synchronized (lock) {
            Call call = new Call(clean(engine, 20), clean(model, 80), now());
            calls.addLast(call);
            while (calls.size() > CALL_LIMIT) calls.removeFirst();
            return call;
        }
    }

    /** Token counts from one Live usageMetadata message; null when the message has none. */
    public record Usage(long prompt, long response, long cached, long thoughts, long tools, long total) {
        public static Usage from(JSONObject metadata) {
            if (metadata == null) return null;
            long prompt = count(metadata, "promptTokenCount", "prompt_token_count");
            long response = Math.max(count(metadata, "responseTokenCount", "response_token_count"), count(metadata, "candidatesTokenCount", "candidates_token_count"));
            long cached = count(metadata, "cachedContentTokenCount", "cached_content_token_count");
            long thoughts = count(metadata, "thoughtsTokenCount", "thoughts_token_count");
            long tools = count(metadata, "toolUsePromptTokenCount", "tool_use_prompt_token_count");
            long total = count(metadata, "totalTokenCount", "total_token_count");
            if (total == 0) total = prompt + response + thoughts + tools;
            return new Usage(prompt, response, cached, thoughts, tools, total);
        }
        private static long count(JSONObject metadata, String camel, String snake) {
            long value = metadata.has(camel) ? metadata.optLong(camel) : metadata.optLong(snake);
            return Math.max(0, value);
        }
    }

    public final class Call {
        final String engine; volatile String model; final long start;
        long mic = NONE, pcm = NONE, setup = NONE, heard = NONE, greeting = NONE;
        /** The last microphone frame of the user's words since the last answer began (voiced), and the loudest frame's level. */
        private long voice = NONE; private int loudest;
        long prompt, response, cached, thoughts, tools, total; int reports;
        boolean ended;
        private Turn turn, watch, barge;
        Call(String engine, String model, long start) { this.engine = engine; this.model = model; this.start = start; }
        /** A reconnect may use another model; the call is filed under the latest. */
        public void model(String value) { synchronized (lock) { if (!ended) model = clean(value, 80); } }
        public void micStarted() { synchronized (lock) { if (!ended && mic == NONE) mic = now() - start; } }
        /** A PCM frame actually went out on the socket. */
        public void pcmSent() { synchronized (lock) { if (!ended && pcm == NONE) pcm = now() - start; } }
        public void setupComplete() { synchronized (lock) { if (!ended && setup == NONE) setup = now() - start; } }
        /** The call's greeting (켤 때 인사하기, Greeter) was first heard at this monotonic ms. */
        public void greeted(long at) { synchronized (lock) { if (!ended && greeting == NONE && at >= start) greeting = at - start; } }
        /**
         * An input caption (interim or final): starts a turn, or moves its stand-in for the end of speech until the answer
         * starts. Captions often trail the first answer chunk, so they never split a turn; only completion or a barge-in ends it.
         */
        public void input() {
            synchronized (lock) {
                if (ended) return;
                long at = now();
                if (heard == NONE) heard = at - start;
                if (turn == null) turn = new Turn(engine, model);
                if (turn.input == NONE) turn.input = at;
                if (turn.output == NONE) turn.lastInput = at;
            }
        }
        /**
         * Push to talk: the user let go, or the recognizer heard the speech end, whichever came first. It stands for the end of
         * speech the way the last input caption does for Live, and starts the turn without counting as a caption.
         */
        public void speechEnded() {
            synchronized (lock) {
                if (ended) return;
                if (turn == null) turn = new Turn(engine, model);
                if (turn.output != NONE || turn.lastInput != NONE) return;
                turn.input = turn.lastInput = now();
            }
        }
        /**
         * A microphone frame at speech level (this RMS) was taken just now: the phone's own mark of the end of speech. Gemini's
         * input caption can come late, on a call's first turn together with the answer, so it cannot stand for it there.
         * The mark moves only with frames at a fifth or more of the loudest frame since the last answer began: after words
         * the microphone's gain stays up and lifts the room over the speech line too, but never near the words' own level.
         */
        public void voiced(int level) {
            synchronized (lock) {
                if (ended || level <= 0) return;
                if (level > loudest) loudest = level;
                if (level * VOICE_PART >= loudest) voice = now();
            }
        }
        /** An output audio chunk the app will play; the first of a turn waits for awaitAudible(). */
        public void output() {
            synchronized (lock) {
                if (ended) return;
                if (turn == null) turn = new Turn(engine, model);
                if (turn.output == NONE) { turn.output = now(); turn.spoke = voice; voice = NONE; loudest = 0; watch = turn; }
            }
        }
        /** The turn whose first chunk is being handed to the speaker, once; null otherwise. */
        public Turn awaitAudible() { synchronized (lock) { Turn value = watch; watch = null; return value; } }
        /** Live said the user cut in. Only a turn that was answering is a barge-in sample. */
        public void interrupted() {
            synchronized (lock) {
                if (ended || turn == null || turn.output == NONE) return;
                turn.interrupt = now(); barge = turn; close();
            }
        }
        /** The app flushed the speaker after interrupted(): its own reaction time, not when the sound died away. audible says whether anything was playing. */
        public void silenced(boolean audible) {
            synchronized (lock) {
                Turn value = barge; barge = null;
                if (audible && value != null && value.silent == NONE) value.silent = now();
            }
        }
        public void turnComplete() { synchronized (lock) { if (!ended) close(); } }
        /** The app answered one voice tool call this many ms after it came: the time the model waited on it. */
        public void tool(String name, long millis) {
            synchronized (lock) {
                String tool = clean(name, 64);
                if (ended || tool.isEmpty() || millis < 0) return;
                toolTimes.addLast(new ToolTime(engine, model, tool, millis));
                while (toolTimes.size() > TOOL_LIMIT) toolTimes.removeFirst();
            }
        }
        public void usage(Usage value) {
            if (value == null) return;
            synchronized (lock) {
                if (ended) return;
                prompt += value.prompt; response += value.response; cached += value.cached;
                thoughts += value.thoughts; tools += value.tools; total += value.total; reports++;
            }
        }
        public void end() { synchronized (lock) { if (!ended) { close(); watch = null; barge = null; ended = true; } } }
        private void close() {
            Turn value = turn; turn = null;
            // Words answered by a turn with nothing to hear are not the next answer's end of speech.
            if (value != null && value.output == NONE && value.input != NONE) { voice = NONE; loudest = 0; }
            if (value == null || value.output == NONE) return;
            turns.addLast(value);
            while (turns.size() > TURN_LIMIT) turns.removeFirst();
        }
    }

    /** An answer with no caption before it counts as an answer to speech only when the microphone heard speech this shortly before. */
    static final long UNCAPTIONED_MS = 15_000;
    /** A frame is part of the words when it is at least this part (one fifth, -14 dB) of the loudest frame of the same words. */
    static final int VOICE_PART = 5;

    public final class Turn {
        final String engine, model;
        long input = NONE, lastInput = NONE, output = NONE, audible = NONE, interrupt = NONE, silent = NONE;
        /** The last microphone frame at speech level before this answer began (Call.voiced); NONE when nothing was said. */
        long spoke = NONE;
        /** A restored turn: response, playback, first sound and barge-in in ms (-1 when missing). */
        private long[] frozen;
        Turn(String engine, String model) { this.engine = engine; this.model = model; }
        /** The first frame of the turn's first chunk was heard at this monotonic ms (AudioTrack timestamp). */
        public void audible(long at) {
            synchronized (lock) {
                if (audible != NONE || output == NONE || at < output || at - output > AUDIBLE_LIMIT_MS) return;
                audible = at;
            }
        }
        /**
         * The phone log's line for this answer once its first sound was heard, null before that:
         * the last input caption (the stand-in for the end of speech) to the first sound, split at the first audio chunk,
         * and, for an answer to spoken words, the time since the microphone last heard speech (말 끝난 뒤): the wait as the
         * user had it, also when the caption came late. Numbers only.
         */
        public String heardLine() {
            synchronized (lock) {
                if (output == NONE || audible == NONE) return null;
                String play = "재생 시작 " + number(audible - output) + "ms";
                // The caption can trail the first chunk; then only speech heard shortly before the answer is counted.
                if (lastInput == NONE) return "  · 대답 들림 · " + play + (spoke == NONE || spoke > output || output - spoke > UNCAPTIONED_MS ? "" : " · 말 끝난 뒤 " + number(audible - spoke) + "ms");
                return "  · 대답 들림 · 마지막 자막 후 " + number(audible - lastInput) + "ms (첫 음성 받기까지 " + number(output - lastInput) + "ms · " + play + ")"
                    + (spoke == NONE || spoke > output ? "" : " · 말 끝난 뒤 " + number(audible - spoke) + "ms");
            }
        }
        long[] metrics() {
            if (frozen != null) return frozen;
            return new long[]{span(lastInput, output), span(output, audible), span(lastInput, audible), span(interrupt, silent)};
        }
    }

    /**
     * When a chunk's first frame played, in nanoTime ns, from one AudioTrack timestamp (`position` presented at `nanoTime`);
     * NONE while that cannot be told yet. `frame` is the frames written before the chunk. Only a position past it proves
     * the frame played: a track resumed after a pause keeps reporting its old position with a stale or clamped time for a
     * while, and an unmoved position says nothing about when the chunk starts. A time before the write is stale as well.
     */
    public static long audibleAt(long position, long nanoTime, long frame, long writtenNanos, int rate) {
        if (rate <= 0 || position <= frame) return NONE;
        long at = nanoTime - (position - frame) * 1_000_000_000L / rate;
        return at >= writtenNanos ? at : NONE;
    }

    /** Report lines, most recent engine·model first. */
    public List<DiagnosticReport.Check> lines() {
        synchronized (lock) {
            List<DiagnosticReport.Check> out = new ArrayList<>();
            if (calls.isEmpty() && turns.isEmpty()) { out.add(new DiagnosticReport.Check("기록", "아직 기록된 음성 통화가 없어요.")); return out; }
            out.add(new DiagnosticReport.Check("기준", "단조 시계 ms, 최근 통화 " + CALL_LIMIT + "회·대화 " + TURN_LIMIT + "턴. 말 끝은 마지막 입력 자막으로 대신 재요."));
            Map<String, List<Call>> byCall = new LinkedHashMap<>();
            Map<String, List<Turn>> byTurn = new LinkedHashMap<>();
            List<Call> recentCalls = new ArrayList<>(calls); Collections.reverse(recentCalls);
            List<Turn> recentTurns = new ArrayList<>(turns); Collections.reverse(recentTurns);
            for (Call call : recentCalls) byCall.computeIfAbsent(cohort(call.engine, call.model), k -> new ArrayList<>()).add(call);
            for (Turn turn : recentTurns) byTurn.computeIfAbsent(cohort(turn.engine, turn.model), k -> new ArrayList<>()).add(turn);
            List<String> cohorts = new ArrayList<>(byCall.keySet());
            for (String key : byTurn.keySet()) if (!cohorts.contains(key)) cohorts.add(key);
            for (String key : cohorts) {
                List<Call> callList = byCall.getOrDefault(key, List.of());
                List<Turn> turnList = byTurn.getOrDefault(key, List.of());
                out.add(new DiagnosticReport.Check("엔진·모델", key.replace("\u0000", " · ") + " (통화 " + callList.size() + "회 · 대화 " + turnList.size() + "턴)"));
                if (key.startsWith(label(CONNECTED) + "\u0000")) {
                    // Push to talk: a press is a call; the end of speech is the release (or the recognizer's end), the output the first sentence.
                    if (!callList.isEmpty()) out.add(new DiagnosticReport.Check("마이크 준비(누름→듣기 시작)", stats(callList, c -> c.mic)));
                    if (!turnList.isEmpty()) {
                        out.add(new DiagnosticReport.Check("첫 소리(놓음→들림)", turnStats(turnList, 2)));
                        out.add(new DiagnosticReport.Check("응답 시작(놓음→첫 문장)", turnStats(turnList, 0)));
                        out.add(new DiagnosticReport.Check("재생 지연(첫 문장→들림)", turnStats(turnList, 1)));
                    }
                    continue;
                }
                if (!callList.isEmpty()) {
                    // Only a cohort whose calls greeted: dictation never does, and the greeting can be switched off.
                    if (callList.stream().anyMatch(c -> c.greeting != NONE)) out.add(new DiagnosticReport.Check("인사 첫 소리(시작→들림)", stats(callList, c -> c.greeting)));
                    out.add(new DiagnosticReport.Check("마이크 시작", stats(callList, c -> c.mic)));
                    out.add(new DiagnosticReport.Check("첫 음성 전송", stats(callList, c -> c.pcm)));
                    out.add(new DiagnosticReport.Check("연결 준비(setupComplete)", stats(callList, c -> c.setup)));
                    out.add(new DiagnosticReport.Check("첫 입력 자막(통화 시작부터)", stats(callList, c -> c.heard)));
                }
                if (!turnList.isEmpty()) {
                    out.add(new DiagnosticReport.Check("첫 소리(자막→들림)", turnStats(turnList, 2)));
                    out.add(new DiagnosticReport.Check("응답 시작(자막→첫 출력)", turnStats(turnList, 0)));
                    out.add(new DiagnosticReport.Check("재생 지연(출력→들림)", turnStats(turnList, 1)));
                    out.add(new DiagnosticReport.Check("끼어들기 처리(interrupted→재생 중단)", turnStats(turnList, 3)));
                }
                Map<String, List<Long>> byTool = new LinkedHashMap<>();
                List<ToolTime> recentTools = new ArrayList<>(toolTimes); Collections.reverse(recentTools);
                for (ToolTime time : recentTools) if (cohort(time.engine, time.model).equals(key)) byTool.computeIfAbsent(time.name, k -> new ArrayList<>()).add(time.millis);
                for (Map.Entry<String, List<Long>> tool : byTool.entrySet()) out.add(new DiagnosticReport.Check("도구 응답(" + tool.getKey() + ")", summary(tool.getValue())));
                if (!callList.isEmpty()) out.add(new DiagnosticReport.Check("토큰 합계", tokens(callList)));
            }
            return out;
        }
    }

    public String save() {
        synchronized (lock) {
            JSONArray savedCalls = new JSONArray(), savedTurns = new JSONArray();
            for (Call call : calls) savedCalls.put(obj("engine", call.engine, "model", call.model, "mic", ms(call.mic), "pcm", ms(call.pcm), "setup", ms(call.setup), "heard", ms(call.heard), "greeting", ms(call.greeting),
                "prompt", call.prompt, "response", call.response, "cached", call.cached, "thoughts", call.thoughts, "tools", call.tools, "total", call.total, "reports", call.reports));
            for (Turn turn : turns) {
                long[] value = turn.metrics();
                savedTurns.put(obj("engine", turn.engine, "model", turn.model, "response", value[0], "playback", value[1], "sound", value[2], "barge", value[3]));
            }
            JSONArray savedTools = new JSONArray();
            for (ToolTime time : toolTimes) savedTools.put(obj("engine", time.engine, "model", time.model, "name", time.name, "ms", time.millis));
            return obj("format", FORMAT, "calls", savedCalls, "turns", savedTurns, "tools", savedTools).toString();
        }
    }
    /** Puts a saved history before what this process already measured, keeping the limits; bad input is ignored. */
    public void restore(String saved) {
        if (saved == null || saved.isBlank()) return;
        JSONObject json;
        try { json = new JSONObject(saved); } catch (Exception e) { return; }
        if (json.optInt("format") != FORMAT) return;
        synchronized (lock) {
            ArrayDeque<Call> older = new ArrayDeque<>();
            JSONArray savedCalls = json.optJSONArray("calls");
            for (int i = 0; savedCalls != null && i < savedCalls.length(); i++) {
                JSONObject item = savedCalls.optJSONObject(i); if (item == null) continue;
                Call call = new Call(clean(item.optString("engine"), 20), clean(item.optString("model"), 80), 0);
                call.mic = mark(item, "mic"); call.pcm = mark(item, "pcm"); call.setup = mark(item, "setup"); call.heard = mark(item, "heard"); call.greeting = mark(item, "greeting");
                call.prompt = positive(item, "prompt"); call.response = positive(item, "response"); call.cached = positive(item, "cached");
                call.thoughts = positive(item, "thoughts"); call.tools = positive(item, "tools"); call.total = positive(item, "total");
                call.reports = (int) Math.min(Integer.MAX_VALUE, positive(item, "reports")); call.ended = true;
                older.addLast(call);
            }
            ArrayDeque<Turn> olderTurns = new ArrayDeque<>();
            JSONArray savedTurns = json.optJSONArray("turns");
            for (int i = 0; savedTurns != null && i < savedTurns.length(); i++) {
                JSONObject item = savedTurns.optJSONObject(i); if (item == null) continue;
                Turn turn = new Turn(clean(item.optString("engine"), 20), clean(item.optString("model"), 80));
                turn.frozen = new long[]{metric(item, "response"), metric(item, "playback"), metric(item, "sound"), metric(item, "barge")};
                olderTurns.addLast(turn);
            }
            ArrayDeque<ToolTime> olderTools = new ArrayDeque<>();
            JSONArray savedTools = json.optJSONArray("tools");
            for (int i = 0; savedTools != null && i < savedTools.length(); i++) {
                JSONObject item = savedTools.optJSONObject(i); if (item == null) continue;
                String name = clean(item.optString("name"), 64); long ms = item.optLong("ms", -1);
                if (!name.isEmpty() && ms >= 0) olderTools.addLast(new ToolTime(clean(item.optString("engine"), 20), clean(item.optString("model"), 80), name, ms));
            }
            olderTools.addAll(toolTimes); toolTimes.clear(); toolTimes.addAll(olderTools);
            while (toolTimes.size() > TOOL_LIMIT) toolTimes.removeFirst();
            older.addAll(calls); calls.clear(); calls.addAll(older);
            while (calls.size() > CALL_LIMIT) calls.removeFirst();
            olderTurns.addAll(turns); turns.clear(); turns.addAll(olderTurns);
            while (turns.size() > TURN_LIMIT) turns.removeFirst();
        }
    }

    /** Nearest-rank percentile of sorted values, or -1 when there are none. */
    static long percentile(List<Long> sorted, int percent) {
        return sorted.isEmpty() ? -1 : sorted.get((percent * sorted.size() + 99) / 100 - 1);
    }
    private interface Field { long of(Call call); }
    private static String stats(List<Call> list, Field field) {
        List<Long> values = new ArrayList<>();
        for (Call call : list) { long value = field.of(call); if (value != NONE && value >= 0) values.add(value); }
        return summary(values);
    }
    private static String turnStats(List<Turn> list, int index) {
        List<Long> values = new ArrayList<>();
        for (Turn turn : list) { long value = turn.metrics()[index]; if (value >= 0) values.add(value); }
        return summary(values);
    }
    private static String summary(List<Long> values) {
        if (values.isEmpty()) return "기록 없음";
        Collections.sort(values);
        return "p50 " + number(percentile(values, 50)) + "ms · p95 " + number(percentile(values, 95)) + "ms (" + values.size() + "개)";
    }
    private static String tokens(List<Call> list) {
        long prompt = 0, response = 0, cached = 0, thoughts = 0, tools = 0, total = 0; int reports = 0;
        for (Call call : list) { prompt += call.prompt; response += call.response; cached += call.cached; thoughts += call.thoughts; tools += call.tools; total += call.total; reports += call.reports; }
        if (reports == 0) return "보고 없음";
        return "입력 " + number(prompt) + " · 출력 " + number(response) + (thoughts > 0 ? " · 생각 " + number(thoughts) : "") + (tools > 0 ? " · 도구 " + number(tools) : "")
            + (cached > 0 ? " · 캐시 " + number(cached) : "") + " · 전체 " + number(total) + " (보고 " + reports + "회)";
    }
    private static String number(long value) { return String.format(Locale.ROOT, "%,d", value); }
    private static String cohort(String engine, String model) { return label(engine) + "\u0000" + (model.isEmpty() ? "모델 모름" : model); }
    private static String label(String engine) { return "dictation".equals(engine) ? "받아쓰기" : "live".equals(engine) ? "음성 비서" : CONNECTED.equals(engine) ? "연결형 음성" : engine.isEmpty() ? "음성" : engine; }
    private static long span(long from, long to) { return from == NONE || to == NONE || to < from ? -1 : to - from; }
    private static Object ms(long value) { return value == NONE ? -1 : value; }
    private static long mark(JSONObject item, String name) { long value = item.optLong(name, -1); return value < 0 ? NONE : value; }
    private static long metric(JSONObject item, String name) { return Math.max(-1, item.optLong(name, -1)); }
    private static long positive(JSONObject item, String name) { return Math.max(0, item.optLong(name)); }
    /** Engine and model ids are plain identifiers (a NIM id has a slash); anything else is dropped rather than carried into the report. */
    private static String clean(String value, int limit) {
        String text = value == null ? "" : value.strip();
        if (text.length() > limit || !text.matches("[A-Za-z0-9._/\\-]*")) return "";
        return text;
    }
}
