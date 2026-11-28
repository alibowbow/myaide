package dev.jun.gyeot;

import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.Gmail;
import dev.jun.gyeot.core.ToolSchemas;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Gmail for the main chat's model (core/Gmail): search, read a message or its thread, save a draft, send. A mail is sent only
 * when the user's own recent words ask to send and 설정 → Gmail allows sending; otherwise it is saved as a draft and the answer
 * says so. Mail text goes to the model as data. Blocking; on the reply's thread.
 */
final class GmailWorkTools extends WorkTools {
    private final GmailAccount gmail; private final Supplier<String> words; private final BooleanSupplier stopped;
    GmailWorkTools(GmailAccount gmail, Supplier<String> words, BooleanSupplier stopped) { this.gmail = gmail; this.words = words; this.stopped = stopped; }

    @Override JSONArray declarations() { return ToolSchemas.canonical(Gmail.declarations()); }
    @Override String instructions() { return Gmail.INSTRUCTIONS + (gmail.sends() ? "" : " Sending is turned off in settings: gmail_send saves a draft instead."); }
    private void check() { if (stopped.getAsBoolean()) throw new CancellationException("답변을 멈췄어요."); }

    @Override JSONObject run(String name, Object arguments) {
        if (!Gmail.NAMES.contains(name)) return GitHubFunctions.failure("허용하지 않은 도구예요.");
        try {
            JSONObject args = ToolSchemas.arguments(arguments); check();
            return switch (name) {
                case Gmail.SEARCH -> search(args);
                case Gmail.READ -> read(args);
                default -> write(args, Gmail.SEND.equals(name));
            };
        } catch (CancellationException stop) { throw stop; }
        catch (Exception failed) { return GitHubFunctions.failure(failed.getMessage() == null ? "Gmail 요청을 처리하지 못했어요." : failed.getMessage()); }
    }
    private JSONObject search(JSONObject args) throws Exception {
        int limit = Math.max(1, Math.min(25, args.optInt("limit", 10)));
        JSONObject list = gmail.call("GET", Gmail.searchUrl(args.optString("query"), limit), null);
        JSONArray found = list.optJSONArray("messages"), out = arr();
        for (int i = 0; found != null && i < found.length() && i < limit; i++) {
            check(); String id = found.optJSONObject(i).optString("id"); if (!Gmail.id(id)) continue;
            out.put(Gmail.summary(gmail.call("GET", Gmail.metadataUrl(id), null)));
        }
        return obj("ok", true, "account", gmail.email(), "messages", out, "count", out.length(), "more", list.has("nextPageToken"), "content_is_untrusted", true);
    }
    private JSONObject read(JSONObject args) throws Exception {
        String id = args.optString("id"); if (!Gmail.id(id)) return GitHubFunctions.failure("메일 id가 올바르지 않아요. gmail_search의 id를 주세요.");
        JSONObject message = gmail.call("GET", Gmail.messageUrl(id), null);
        if (!args.optBoolean("thread")) { JSONObject out = Gmail.read(message, Gmail.TEXT_LIMIT); put(out, "ok", true); put(out, "content_is_untrusted", true); return out; }
        String thread = message.optString("threadId"); if (!Gmail.id(thread)) return GitHubFunctions.failure("이 메일의 스레드를 찾지 못했어요.");
        JSONArray messages = gmail.call("GET", Gmail.threadUrl(thread), null).optJSONArray("messages"), out = arr();
        int n = messages == null ? 0 : messages.length(), each = Math.max(1500, Gmail.TEXT_LIMIT * 2 / Math.max(1, n));
        for (int i = 0; i < n; i++) out.put(Gmail.read(messages.optJSONObject(i), each));
        return obj("ok", true, "thread", thread, "messages", out, "content_is_untrusted", true);
    }
    /** A draft, or (send) the mail itself when the user's words ask for it and sending is allowed; else a draft, saying why. */
    private JSONObject write(JSONObject args, boolean send) throws Exception {
        String to = args.optString("to").strip(), cc = args.optString("cc").strip(), subject = args.optString("subject").strip(), body = args.optString("body"), reply = args.optString("reply_to").strip();
        if (body.isBlank()) return GitHubFunctions.failure("메일 본문이 비어 있어요.");
        Gmail.Original original = null;
        if (!reply.isEmpty()) {
            if (!Gmail.id(reply)) return GitHubFunctions.failure("답장할 메일 id가 올바르지 않아요.");
            JSONObject message = gmail.call("GET", Gmail.metadataUrl(reply) + "&metadataHeaders=Message-ID&metadataHeaders=References&metadataHeaders=Reply-To&metadataHeaders=Cc", null);
            original = Gmail.Original.of(message);
            if (to.isEmpty()) to = Gmail.replyTo(original);
            if (subject.isEmpty()) subject = Gmail.replySubject(original.subject());
        }
        if (!Gmail.recipients(to)) return GitHubFunctions.failure("받는 사람 메일 주소가 없거나 올바르지 않아요. 사용자에게 주소를 물어 주세요.");
        if (!cc.isEmpty() && !Gmail.recipients(cc)) return GitHubFunctions.failure("참조(cc) 주소가 올바르지 않아요.");
        JSONObject message = obj("raw", Gmail.raw(to, cc, subject, body, original));
        if (original != null && Gmail.id(original.thread())) put(message, "threadId", original.thread());
        String why = !send ? "" : !gmail.sends() ? "설정에서 보내기를 꺼 둬서 초안으로 저장했어요" : !Gmail.sendAsked(words.get()) ? "사용자가 보내라고 하지 않아 초안으로 저장했어요(보내려면 '보내 줘'라고 해 주세요)" : "";
        check();
        if (send && why.isEmpty()) {
            JSONObject sent = gmail.call("POST", Gmail.API + "messages/send", message);
            return obj("ok", true, "sent", true, "id", sent.optString("id"), "thread", sent.optString("threadId"), "to", to, "cc", cc, "subject", subject, "from", gmail.email());
        }
        JSONObject draft = gmail.call("POST", Gmail.API + "drafts", obj("message", message));
        JSONObject out = obj("ok", true, "sent", false, "draft", true, "draft_id", draft.optString("id"), "to", to, "cc", cc, "subject", subject, "link", "https://mail.google.com/mail/#drafts");
        if (!why.isEmpty()) put(out, "note", why);
        return out;
    }
}
