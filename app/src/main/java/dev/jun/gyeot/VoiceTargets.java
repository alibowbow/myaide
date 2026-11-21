package dev.jun.gyeot;

import dev.jun.gyeot.core.RealtimeVoice;
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
            @Override public String call(String name, JSONObject args) throws Exception {
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
        };
    }
}
