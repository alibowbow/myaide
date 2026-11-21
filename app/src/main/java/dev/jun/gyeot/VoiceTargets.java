package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import dev.jun.gyeot.core.RealtimeVoice;
import dev.jun.gyeot.core.WorkBook;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Where a spoken conversation with GPT or Grok belongs (VoiceChat.Target), one per screen. Each gives the model that
 * screen's conversation as context and one function that hands a request to the screen's own AI, with its tools, and
 * brings the result back to be told: the voice model talks, the screen's AI does the work.
 */
final class VoiceTargets {
    private VoiceTargets() {}
    /** The longest a handed-off request is waited for before the voice says it is still going. */
    static final long WAIT_MS = 4 * 60_000L;
    /** At most this much of a result goes back to the voice model. */
    static final int RESULT = 6000;

    /** Words for the voice model about handing work to the screen's own AI. */
    static String handOff(String function, String what) {
        return "\n\nFor " + what + ", call " + function + " with the user's request in their own words, say one short sentence while it works,"
            + " then tell the result briefly in speech. Do not read out links or long lists; say the result is also written on the screen.";
    }
    static JSONObject requestParameters(String description) {
        return obj("type", "object", "required", arr("request"), "properties", obj("request", obj("type", "string", "description", description)));
    }
    static String result(String text) { return obj("result", text == null ? "" : text.length() > RESULT ? text.substring(0, RESULT) + "…" : text).toString(); }

    // ── the main chat ────────────────────────────────────────────────────
    static VoiceChat.Target chat(AppStore store) {
        return new VoiceChat.Target() {
            @Override public String surface() { return "chat"; }
            @Override public String label() { return "메인 대화"; }
            @Override public String instructions() {
                return store.mainAssistant.voiceInstructions() + handOff("ask_chat",
                    "anything that needs the web (news, weather, prices, places), the user's calendar, pictures, files, a longer written answer or a task");
            }
            @Override public JSONArray tools() {
                return arr(obj("type", "function", "name", "ask_chat", "description",
                    "Sends the request to this chat's main AI, which can search the web, read pages, check the calendar, draw pictures and start tasks. Its answer is written in the chat and returned here.",
                    "parameters", requestParameters("What to ask, in the user's words")));
            }
            @Override public String call(String name, JSONObject args, VoiceChat chat) throws Exception {
                if (!"ask_chat".equals(name)) return obj("error", "no such function").toString();
                String request = args.optString("request").strip();
                if (request.isEmpty()) return obj("error", "empty request").toString();
                CountDownLatch done = new CountDownLatch(1);
                long reply = store.mainAssistant.enqueue(request, UsageLedger.VOICE, new MainChatEngine.Listener() {
                    @Override public void text(String delta) {}
                    @Override public void ended() { done.countDown(); }
                });
                if (!done.await(WAIT_MS, TimeUnit.MILLISECONDS)) return result("아직 답을 만드는 중이에요. 다 되면 대화에 나와요.");
                JSONObject m = store.mainChat.message(reply);
                if (m == null) return result("답을 찾지 못했어요.");
                return "done".equals(m.optString("state")) ? result(m.optString("text")) : obj("error", m.optString("text")).toString();
            }
            @Override public long line(boolean user, String text, long reply, String voice) { return store.mainAssistant.spoken(user, text, reply, voice); }
            @Override public String about() { return "날씨·검색·일정·그림 같은 일은 메인 대화의 AI에게 맡기고 결과를 말로 알려 줘요. 말한 내용은 메인 대화에 기록돼요."; }
        };
    }

    /** How long a handed-off run is waited for before the voice says it goes on and tells the result when it ends. */
    static final long SHORT_WAIT_MS = 25_000, LONG_WAIT_MS = 20 * 60_000L;
    /** Waits for slow results off every other thread; a result that comes later is told by the conversation (report). */
    private static final java.util.concurrent.ExecutorService waiters = java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "gyeot-voice-wait"); t.setDaemon(true); return t; });
    /** The words of what the voice said, for a work room: GPT's are ChatGPT's, Grok's Grok's. */
    static String engineOf(String voice) { return voice != null && voice.startsWith("Grok") ? WorkBook.GROK : WorkBook.CHATGPT; }

    // ── 작업창 ───────────────────────────────────────────────────────────
    /**
     * A room of 작업창 (roomId; "" for a new room, made by the first request). The voice hands what the user asks to the
     * room's own AI (ask_work), with the words the app heard as the run's authority, and tells its result; it can also
     * say how the room is doing and stop its run.
     */
    static VoiceChat.Target work(Context context, AppStore store, String roomId) {
        String[] room = {roomId == null ? "" : roomId};
        Context app = context.getApplicationContext();
        return new VoiceChat.Target() {
            @Override public String surface() { return "work"; }
            @Override public String label() { return "작업창"; }
            @Override public String about() { return "말로 시킨 일은 이 작업방의 AI가 하고, 끝나면 말로 알려 줘요. 말한 내용은 작업방에 기록돼요."; }
            JSONObject thread() { return room[0].isEmpty() ? null : WorkBook.find(store.works(), room[0]); }
            @Override public String instructions() {
                JSONObject t = thread(); StringBuilder recent = new StringBuilder();
                JSONArray messages = t == null ? null : t.optJSONArray("messages");
                for (int i = messages == null ? -1 : Math.max(0, messages.length() - 30); messages != null && i < messages.length(); i++) {
                    JSONObject m = messages.optJSONObject(i); if (m == null) continue;
                    String text = m.optString("text"); if (text.length() > 600) text = text.substring(0, 600) + "…";
                    recent.append(WorkBook.USER.equals(m.optString("role")) ? "user: " : "assistant: ").append(text).append('\n');
                }
                String clipped = recent.length() > 8000 ? recent.substring(recent.length() - 8000) : recent.toString();
                String place = t == null ? "the app's 작업창 (a place where an AI does longer tasks); no room is open yet"
                    : "a room of the app's 작업창 titled \"" + t.optString("title") + "\" (now: " + WorkBook.state(t) + ")";
                return RealtimeVoice.instructions(dev.jun.gyeot.core.Brand.called(store.assistantName()), place, store.persona(), store.pinnedNotes(), clipped)
                    + handOff("ask_work", "any task or question for this room (research, writing, files, code, using the browser)")
                    + " Use work_status when asked how it is going, and work_stop only when the user asks to stop it.";
            }
            @Override public JSONArray tools() {
                return arr(obj("type", "function", "name", "ask_work", "description", "Hands the request to this 작업창 room's AI, which does it with its tools; while it runs a request is added as guidance. Returns the result, or that it is still running (the app tells the result later).",
                        "parameters", requestParameters("What to do, in the user's words")),
                    obj("type", "function", "name", "work_status", "description", "How this room's work is going now.", "parameters", obj("type", "object", "properties", obj())),
                    obj("type", "function", "name", "work_stop", "description", "Stops this room's running work. Only when the user asks.", "parameters", obj("type", "object", "properties", obj())));
            }
            @Override public String call(String name, JSONObject args, VoiceChat chat) throws Exception {
                switch (name) {
                    case "work_status" -> {
                        JSONObject t = thread(); if (t == null) return obj("state", "no room yet").toString();
                        String stage = store.workStages.getOrDefault(room[0], "");
                        return obj("title", t.optString("title"), "state", WorkBook.state(t), "stage", stage, "last_answer", clip(last(t), 1500)).toString();
                    }
                    case "work_stop" -> {
                        if (room[0].isEmpty()) return obj("error", "no room").toString();
                        app.startService(new Intent(app, AssistantService.class).setAction(AssistantService.WORK_STOP).putExtra("thread", room[0]));
                        return obj("ok", true).toString();
                    }
                    case "ask_work" -> { return ask(args.optString("request").strip(), chat); }
                    default -> { return obj("error", "no such function").toString(); }
                }
            }
            String ask(String request, VoiceChat chat) throws Exception {
                if (request.isEmpty()) return obj("error", "empty request").toString();
                String heard = chat.heard(3000);
                java.util.Set<String> before = new java.util.HashSet<>(); JSONArray all = store.works();
                for (int i = 0; i < all.length(); i++) before.add(all.optJSONObject(i).optString("id"));
                long asked = System.currentTimeMillis();
                ScopedModels selection = new ScopedModels(store, room[0]);
                if (selection.readyEngine().isEmpty()) return obj("error", "작업창에서 쓸 AI가 연결되지 않았어요. 설정에서 연결해 주세요.").toString();
                Intent send = new Intent(app, AssistantService.class).setAction(AssistantService.WORK_SEND).putExtra("thread", room[0]).putExtra("text", request)
                    .putExtra("engine", selection.readyEngine()).putExtra("modelSelection", selection.snapshot().toString())
                    .putExtra(WorkBook.VOICE, true).putExtra(WorkBook.HEARD, heard == null || heard.isBlank() ? request : heard);
                app.startForegroundService(send);
                // A new room appears with the request: the conversation goes on in it.
                for (long until = System.currentTimeMillis() + 5000; room[0].isEmpty() && System.currentTimeMillis() < until; Thread.sleep(200)) {
                    JSONArray now = store.works();
                    for (int i = 0; i < now.length(); i++) { String id = now.optJSONObject(i).optString("id"); if (!before.contains(id)) { room[0] = id; store.workOpen = id; store.changed(); break; } }
                }
                String done = waitFor(asked, SHORT_WAIT_MS);
                if (done != null) return result(done);
                waiters.execute(() -> { try { String later = waitFor(asked, LONG_WAIT_MS); if (later != null && store.voiceChat == chat) chat.report("작업창 결과: " + clip(later, 1500)); } catch (Exception ignored) {} });
                return obj("state", "running", "note", "작업창에서 하고 있어요. 끝나면 앱이 결과를 알려 줘요.").toString();
            }
            /** The room's answer after asked once its run has ended; null when it has not within waitMs. */
            String waitFor(long asked, long waitMs) throws InterruptedException {
                for (long until = System.currentTimeMillis() + waitMs; System.currentTimeMillis() < until; Thread.sleep(500)) {
                    JSONObject t = thread(); if (t == null) continue;
                    if (WorkBook.RUNNING.equals(t.optString("state"))) continue;
                    JSONArray messages = t.optJSONArray("messages");
                    for (int i = messages == null ? -1 : messages.length() - 1; i >= 0; i--) {
                        JSONObject m = messages.optJSONObject(i);
                        if (m == null || m.optLong("at") < asked) break;
                        if (!WorkBook.USER.equals(m.optString("role")) && !WorkBook.SPOKEN.equals(m.optString("kind"))) return m.optString("text");
                    }
                }
                return null;
            }
            String last(JSONObject t) {
                JSONArray messages = t.optJSONArray("messages");
                for (int i = messages == null ? -1 : messages.length() - 1; i >= 0; i--) {
                    JSONObject m = messages.optJSONObject(i);
                    if (m != null && !WorkBook.USER.equals(m.optString("role")) && !WorkBook.SPOKEN.equals(m.optString("kind"))) return m.optString("text");
                }
                return "";
            }
            @Override public long line(boolean user, String text, long reply, String voice) throws Exception {
                if (room[0].isEmpty()) return 0;
                long now = System.currentTimeMillis();
                return store.changeWorks(list -> { JSONObject t = WorkBook.find(list, room[0]); return t == null ? 0L : WorkBook.said(t, user, text, engineOf(voice), voice, now); });
            }
        };
    }
    static String clip(String text, int max) { return text == null ? "" : text.length() > max ? text.substring(0, max) + "…" : text; }
}
