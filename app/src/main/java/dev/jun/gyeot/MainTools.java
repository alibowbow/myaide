package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.Archive;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.GitHubTools;
import dev.jun.gyeot.core.Jsons;
import dev.jun.gyeot.core.Mcp;
import dev.jun.gyeot.core.ToolSchemas;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The services the main chat's model can use, run on the phone while it answers: GitHub and
 * Google Drive as 작업창 offers them, under the same switches (설정 → 커넥터: GitHub's 쓰기 and 저장소 바꾸기 허용, Drive's
 * 쓰기 and 수정·정리 허용), 마이아카이브 and the MCP connectors the voice assistant uses. Only what is connected is offered,
 * so a chat with none of them sends exactly the request it always did. Tokens and addresses never reach the model.
 */
final class MainTools {
    private MainTools() {}

    /** The tools for one reply, or null when nothing is connected. stopped ends a running tool when the reply is stopped. */
    static WorkTools build(Context context, AppStore store, AtomicReference<Runnable> cancel, BooleanSupplier stopped, String model) {
        WorkTools tools = null;
        // Only 설정 → 커넥터 decides: whatever is connected there is used (no per-chat switch).
        GitHubTools github = store.githubTools();
        if (github != null) tools = WorkTools.combine(tools, new GitHubRunner(github, cancel, stopped));
        if (store.drive.forWork()) {
            Runnable check = () -> { if (stopped.getAsBoolean()) throw new CancellationException("답변을 멈췄어요."); };
            tools = WorkTools.combine(tools, new DriveWorkTools(store.drive, store.drive.writable(), DriveFiles.main(context, store, check), stopped));
        }
        if (store.archiveConnected()) tools = WorkTools.combine(tools, new Archives(store, Brand.NAME + " · " + model));
        if (store.connectorDeclarations().length() > 0) tools = WorkTools.combine(tools, new Connectors(store, stopped));
        return tools;
    }

    /**
     * 마이아카이브: the voice assistant's archive_ functions plus archive_edit and archive_update, each through ArchiveClient
     * (its address holds the key). archive_edit reads the whole piece here, applies the model's snippet replacements and
     * saves the result, so a long piece is never retyped. A whole new text from the model (archive_update content) is sent
     * only once this reply has read all of the piece, so an edit made from a part never cuts the rest away. What the
     * archive's own permissions allow, it decides.
     */
    static class Archives extends WorkTools {
        private final AppStore store; private final String model;
        /** Per piece, how far this reply has read it without a gap from the start, and its length. */
        private final java.util.Map<String, long[]> read = new java.util.concurrent.ConcurrentHashMap<>();
        Archives(AppStore store, String model) { this.store = store; this.model = model; }
        @Override JSONArray declarations() { return ToolSchemas.canonical(Archive.chatDeclarations()); }
        @Override String instructions() { return Archive.CHAT_INSTRUCTIONS; }
        @Override JSONObject run(String name, Object arguments) {
            if (!Archive.NAMES.contains(name)) return GitHubFunctions.failure("허용하지 않은 도구예요.");
            try {
                JSONObject args = ToolSchemas.arguments(arguments); String id = args.optString("id");
                if (Archive.UPDATE.equals(name) && args.has("content") && !readAll(id))
                    return GitHubFunctions.failure("Read the whole piece first: archive_read " + id + " from offset 0, then each metadata.next_offset until no next_offset is left, in this reply. Then send the complete revised text. Nothing was changed.");
                if (Archive.EDIT.equals(name)) { read.remove(id); return Archive.answer(edit(id, args.optJSONArray("edits")), Archive.CHAT_LIMIT); }
                String text = call(name, args);
                if (Archive.READ.equals(name)) seen(id, Archive.span(text));
                // A piece changed by this reply is read again before another new text (a refused change throws above).
                if (Archive.UPDATE.equals(name) || Archive.APPEND.equals(name)) read.remove(id);
                return Archive.answer(text, Archive.CHAT_LIMIT);
            }
            catch (CancellationException stopped) { throw stopped; }
            catch (Exception failed) { return GitHubFunctions.failure(Jsons.message(failed, "")); }
        }
        String call(String name, JSONObject args) throws Exception { return ArchiveClient.call(store.archiveAddress(), Archive.request(name, args, model)); }
        /** The whole piece read part by part (the archive's largest parts), the edits applied, and the result saved. */
        private String edit(String id, JSONArray edits) throws Exception {
            if (id.isBlank()) throw new IllegalArgumentException("id is missing.");
            StringBuilder whole = new StringBuilder(); long offset = 0, chars = -1;
            while (true) {
                String part = call(Archive.READ, Jsons.obj("id", id, "offset", offset, "max_chars", 60_000));
                JSONObject doc = new JSONObject(part); JSONObject meta = doc.optJSONObject("metadata"); long[] span = Archive.span(part);
                if (meta == null || span == null || span[0] != offset || (chars >= 0 && span[2] != chars)) throw new IllegalStateException("그 글을 끝까지 읽지 못했어요(읽는 동안 바뀌었을 수 있어요). 다시 시도해 주세요. 바꾼 것은 없어요.");
                chars = span[2]; whole.append(doc.optString("text"));
                if (span[1] >= chars) break;
                if (span[1] <= offset || whole.length() > Archive.PIECE_LIMIT) throw new IllegalStateException("그 글을 끝까지 읽지 못했어요. 바꾼 것은 없어요.");
                offset = span[1];
            }
            if (whole.length() != chars) throw new IllegalStateException("그 글을 끝까지 읽지 못했어요(길이가 맞지 않아요). 바꾼 것은 없어요.");
            String revised = Archive.applyEdits(whole.toString(), edits);
            if (revised.equals(whole.toString())) return Jsons.obj("message", "바뀐 내용이 없어서 저장하지 않았어요.", "id", id).toString();
            if (revised.length() > Archive.PIECE_LIMIT) throw new IllegalArgumentException("고친 글이 마이아카이브의 한도(100만 자)를 넘어요. 바꾼 것은 없어요.");
            return call(Archive.UPDATE, Jsons.obj("id", id, "content", revised));
        }
        private void seen(String id, long[] span) {
            if (span == null || id.isBlank()) return;
            long[] before = read.getOrDefault(id, new long[]{0, span[2]});
            // A part that starts where the reading so far ended (or the start) carries it on; a gap leaves it as it was.
            if (span[0] <= before[0] && before[1] == span[2]) read.put(id, new long[]{Math.max(before[0], span[1]), span[2]});
            else if (span[0] == 0) read.put(id, new long[]{span[1], span[2]});
        }
        private boolean readAll(String id) { long[] got = read.get(id); return got != null && got[0] >= got[1]; }
    }

    /** The MCP connectors added in 설정 → 커넥터: their saved tools, each call sent to its own server with its own token. */
    static final class Connectors extends WorkTools {
        private final AppStore store; private final BooleanSupplier stopped; private final JSONArray declared;
        Connectors(AppStore store, BooleanSupplier stopped) { this.store = store; this.stopped = stopped; declared = ToolSchemas.canonical(store.connectorDeclarations()); }
        @Override JSONArray declarations() { return declared; }
        @Override String instructions() { return Mcp.CHAT_INSTRUCTIONS; }
        @Override JSONObject run(String name, Object arguments) {
            if (stopped.getAsBoolean()) throw new CancellationException("답변을 멈췄어요.");
            JSONObject target = store.connectorTarget(name);
            if (target == null) return GitHubFunctions.failure("그 커넥터 도구를 찾지 못했어요. 설정 → 커넥터를 확인해 주세요.");
            String id = target.optString("id"), label = target.optString("name");
            try { return Mcp.answer(label, McpClient.callTool(id, store.connectorSecret(id, "url"), ConnectorAuth.bearer(store, id), label, target.optString("tool"), ToolSchemas.arguments(arguments))); }
            catch (CancellationException halted) { throw halted; }
            // McpClient's messages never carry the address or the token.
            catch (Exception failed) { return GitHubFunctions.failure(Jsons.message(failed, "")); }
        }
    }
}
