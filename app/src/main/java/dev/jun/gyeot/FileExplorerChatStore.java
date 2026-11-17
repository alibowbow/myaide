package dev.jun.gyeot;

import android.content.Context;
import android.util.AtomicFile;
import dev.jun.gyeot.core.FileSearchPlan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.json.*;

/** App-private conversation, including explicitly requested AI replies, plus result metadata.
 * Never resolves a result URI, reads a document/provider, or automatically exports the transcript. */
final class FileExplorerChatStore {
    static final String FILE_NAME = "file-explorer-chat.json";
    static final int MAX_MESSAGES = 60, MAX_RESULTS = 100, MAX_BYTES = 256 * 1024, MAX_DRAFT = 2000, MAX_MESSAGE_TEXT = 16000;
    private static final Set<String> ROLES = Set.of("user", "system", "assistant");
    private static final Set<String> STATUSES = Set.of("pending", "completed", "cancelled", "error", "interrupted");
    private static final Map<String, FileExplorerChatStore> OPEN = new HashMap<>();
    private static final String TRIM_NOTICE = "기록 저장 한도로 오래된 대화나 결과 일부만 보관했어요. 현재 파일 목록은 다시 검색해 확인해 주세요.";
    private final AtomicFile file;
    private State state;

    record ResultMetadata(String id, String name, String mime, long size, long modified, String uri, String parent) {}
    record Message(String id, String role, String text, String status, long createdAt,
            String rootUri, String directory, List<ResultMetadata> results) {
        Message { results = List.copyOf(results); }
    }
    private record State(String rootUri, String directory, String draft, String previousQuery,
            String acceptedPlanJson, List<Message> messages, String notice) {
        State { messages = List.copyOf(messages); }
    }

    /** A recreation in the same live process does not turn its current pending operation into an interruption. */
    static synchronized FileExplorerChatStore open(Context context) throws IOException {
        File directory = context.getApplicationContext().getFilesDir();
        String key = new File(directory, FILE_NAME).getCanonicalPath();
        FileExplorerChatStore existing = OPEN.get(key);
        if (existing != null) return existing;
        FileExplorerChatStore created = new FileExplorerChatStore(directory);
        OPEN.put(key, created);
        return created;
    }

    /** Package-private disk-load seam for process-restart tests. Production callers use open(Context). */
    FileExplorerChatStore(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("대화 기록 폴더를 만들지 못했어요.");
        file = new AtomicFile(new File(directory, FILE_NAME));
        state = emptyState("");
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return;
        try { state = decode(readBounded()); }
        catch (MalformedRecord malformed) {
            isolateCorrupt();
            state = emptyState("읽을 수 없는 대화 기록을 앱 안에 별도 보관했어요. 이전 작업은 실행하지 않고 새 대화로 시작해요.");
            persist(state);
            return;
        }
        recoverInterrupted();
    }

    /** Explicit owner handoff only: the UI must first cancel/close its prior exclusive session.
     * Merely opening or reading the live singleton never invokes this recovery. */
    synchronized void recoverInterrupted() throws IOException {
        boolean interrupted = false;
        List<Message> recovered = new ArrayList<>();
        for (Message message : state.messages()) {
            if (message.status().equals("pending")) {
                interrupted = true;
                recovered.add(new Message(message.id(), message.role(), message.text(), "interrupted", message.createdAt(), message.rootUri(), message.directory(), message.results()));
            } else recovered.add(message);
        }
        if (interrupted) replace(new State(state.rootUri(), state.directory(), state.draft(), state.previousQuery(), state.acceptedPlanJson(), recovered,
            "이전에 진행 중이던 작업이 중단됐어요. 자동으로 다시 실행하지 않아요."));
    }

    synchronized List<Message> messages() { return state.messages(); }
    synchronized String draft() { return state.draft(); }
    synchronized String rootUri() { return state.rootUri(); }
    synchronized String directory() { return state.directory(); }
    synchronized String notice() { return state.notice(); }
    synchronized String acceptedPlanJson() { return state.acceptedPlanJson(); }
    synchronized String previousQuery() { return state.previousQuery(); }

    /** Call only after the UI's explicit app-private conversation-clear confirmation. No document is affected. */
    synchronized void clear() throws IOException { replace(new State(state.rootUri(), state.directory(), "", "", "", List.of(), "")); }

    /** History keeps its original local scope. Only model follow-up context is reset by a scope change. */
    synchronized void setScope(String rootUri, String directory) throws IOException {
        scope(rootUri, directory);
        if (rootUri.equals(state.rootUri()) && directory.equals(state.directory())) return;
        List<Message> messages = new ArrayList<>();
        for (Message message : state.messages()) messages.add(message.status().equals("pending")
            ? new Message(message.id(), message.role(), message.text(), "cancelled", message.createdAt(), message.rootUri(), message.directory(), message.results()) : message);
        replace(new State(rootUri, directory, state.draft(), "", "", messages, state.notice()));
    }

    synchronized void saveDraft(String draft) throws IOException {
        text(draft, MAX_DRAFT, "입력 중인 문장");
        if (draft.equals(state.draft())) return;
        replace(new State(state.rootUri(), state.directory(), draft, state.previousQuery(), state.acceptedPlanJson(), state.messages(), state.notice()));
    }

    synchronized String append(String role, String text, String status) throws IOException {
        messageFields(role, text, status);
        String id = UUID.randomUUID().toString();
        List<Message> messages = new ArrayList<>(state.messages());
        messages.add(new Message(id, role, text, status, System.currentTimeMillis(), state.rootUri(), state.directory(), List.of()));
        replace(new State(state.rootUri(), state.directory(), state.draft(), state.previousQuery(), state.acceptedPlanJson(), messages, state.notice()));
        return id;
    }

    /** Updating a cancelled/recovered or old-scope request cannot turn it into a newly completed action. */
    synchronized void update(String id, String text, String status, List<ResultMetadata> results) throws IOException {
        List<Message> messages = new ArrayList<>(state.messages());
        for (int i = 0; i < messages.size(); i++) {
            Message old = messages.get(i);
            if (!old.id().equals(id)) continue;
            messageFields(old.role(), text, status);
            if (!old.rootUri().equals(state.rootUri()) || !old.directory().equals(state.directory())) throw new IOException("검색 위치가 바뀌어 이전 작업 결과를 저장하지 않았어요.");
            if (!old.status().equals("pending") && !old.status().equals(status)) throw new IOException("끝나거나 중단된 작업은 다시 실행 상태로 바꿀 수 없어요.");
            if (results == null) throw new IOException("검색 결과 목록이 없어요.");
            List<ResultMetadata> bounded = new ArrayList<>();
            for (int at = 0; at < Math.min(results.size(), MAX_RESULTS); at++) { ResultMetadata result = results.get(at); metadata(result); bounded.add(result); }
            if (!bounded.isEmpty() && !old.role().equals("assistant")) throw new IOException("검색 결과는 비서 응답에만 보관해요.");
            messages.set(i, new Message(old.id(), old.role(), text, status, old.createdAt(), old.rootUri(), old.directory(), bounded));
            replace(new State(state.rootUri(), state.directory(), state.draft(), state.previousQuery(), state.acceptedPlanJson(), messages, results.size() > MAX_RESULTS ? TRIM_NOTICE : state.notice()));
            return;
        }
        throw new IOException("수정할 대화 기록을 찾지 못했어요.");
    }

    synchronized void acceptPlan(String query, String planJson, ZoneId zone) throws IOException {
        searchQuery(query);
        String canonical = canonicalPlan(planJson, zone);
        replace(new State(state.rootUri(), state.directory(), state.draft(), query, canonical, state.messages(), state.notice()));
    }

    /** For a metadata-search clarification, never an ASK/CREATE/REVISE request or assistant/body reply. */
    synchronized void rememberSearchQuery(String query) throws IOException {
        searchQuery(query);
        replace(new State(state.rootUri(), state.directory(), state.draft(), query, state.acceptedPlanJson(), state.messages(), state.notice()));
    }

    /** The only export surface for AI follow-up: no transcript, result names, IDs, URIs, scope, or file bodies. */
    synchronized JSONObject modelContextObject(String query, ZonedDateTime now) throws IOException {
        try {
            JSONObject context = new JSONObject(FileSearchPlan.request(query, now));
            if (!state.previousQuery().isEmpty()) {
                context.put("previous_query", state.previousQuery());
                if (!state.acceptedPlanJson().isEmpty()) context.put("previous_filters", new JSONObject(state.acceptedPlanJson()));
            }
            return context;
        } catch (Exception invalid) { throw new IOException("검색 요청을 준비하지 못했어요: " + invalid.getMessage(), invalid); }
    }
    synchronized JSONObject modelContext(String query, ZonedDateTime now) throws IOException { return modelContextObject(query, now); }
    synchronized String modelContextString(String query, ZonedDateTime now) throws IOException { return modelContextObject(query, now).toString(); }

    private static State emptyState(String notice) { return new State("", "", "", "", "", List.of(), notice); }
    private void replace(State next) throws IOException { next = bounded(next); persist(next); state = next; }
    private State bounded(State next) throws IOException {
        List<Message> messages = new ArrayList<>(next.messages()); boolean trimmed = false;
        while (messages.size() > MAX_MESSAGES) { removeOldestFinished(messages); trimmed = true; }
        int results = messages.stream().mapToInt(message -> message.results().size()).sum();
        while (results > MAX_RESULTS) { removeOldestResult(messages); results--; trimmed = true; }
        State candidate = copy(next, messages, trimmed ? TRIM_NOTICE : next.notice());
        while (encode(candidate).length > MAX_BYTES) {
            if (results > 0) { removeOldestResult(messages); results--; }
            else removeOldestFinished(messages);
            trimmed = true; candidate = copy(next, messages, TRIM_NOTICE);
        }
        return candidate;
    }
    private static State copy(State state, List<Message> messages, String notice) { return new State(state.rootUri(), state.directory(), state.draft(), state.previousQuery(), state.acceptedPlanJson(), messages, notice); }
    private static void removeOldestFinished(List<Message> messages) throws IOException {
        // Keep the newest turn and every in-flight ID so its eventual update cannot disappear underneath it.
        for (int i = 0; i < messages.size() - 1; i++) if (!messages.get(i).status().equals("pending")) { messages.remove(i); return; }
        throw new IOException("진행 중인 대화가 저장 한도에 도달했어요. 기존 작업을 끝내거나 취소한 뒤 다시 시도해 주세요.");
    }
    private static void removeOldestResult(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) if (!messages.get(i).results().isEmpty()) {
            Message old = messages.get(i);
            messages.set(i, new Message(old.id(), old.role(), old.text(), old.status(), old.createdAt(), old.rootUri(), old.directory(), old.results().subList(1, old.results().size()))); return;
        }
    }

    private void persist(State next) throws IOException {
        byte[] bytes = encode(next);
        if (bytes.length > MAX_BYTES) throw new IOException("대화 기록 저장 한도를 넘었어요.");
        FileOutputStream out = null;
        try {
            out = file.startWrite(); out.write(bytes); out.flush(); out.getFD().sync();
            file.finishWrite(out); out = null;
            // AtomicFile.finishWrite logs some platform failures rather than throwing. Verify before reporting saved.
            if (!Arrays.equals(bytes, readBounded())) throw new IOException("대화 기록 저장 결과를 확인하지 못했어요.");
        } catch (IOException error) {
            if (out != null) file.failWrite(out);
            throw new IOException("대화 기록을 저장하지 못했어요. 이전 기록은 자동 재실행하지 않아요. " + error.getMessage(), error);
        }
    }
    private byte[] readBounded() throws IOException {
        try (FileInputStream in = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new MalformedRecord("oversized local record");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }
    private void isolateCorrupt() throws IOException {
        File original = file.getBaseFile(), quarantine = new File(original.getParentFile(), FILE_NAME + ".corrupt-" + UUID.randomUUID());
        if (!original.renameTo(quarantine)) throw new IOException("손상된 대화 기록을 별도 보관하지 못했어요. 원본은 그대로 두었어요.");
    }

    private static byte[] encode(State state) throws IOException {
        try {
            JSONObject root = new JSONObject().put("version", 1).put("rootUri", state.rootUri()).put("directory", state.directory()).put("draft", state.draft())
                .put("previousQuery", state.previousQuery()).put("acceptedPlanJson", state.acceptedPlanJson()).put("notice", state.notice());
            JSONArray messages = new JSONArray();
            for (Message message : state.messages()) {
                JSONArray results = new JSONArray();
                for (ResultMetadata item : message.results()) results.put(new JSONObject().put("id", item.id()).put("name", item.name()).put("mime", item.mime()).put("size", item.size()).put("modified", item.modified()).put("uri", item.uri()).put("parent", item.parent()));
                messages.put(new JSONObject().put("id", message.id()).put("role", message.role()).put("text", message.text()).put("status", message.status()).put("createdAt", message.createdAt()).put("rootUri", message.rootUri()).put("directory", message.directory()).put("results", results));
            }
            return root.put("messages", messages).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException invalid) { throw new IOException("대화 기록 형식을 만들지 못했어요.", invalid); }
    }
    private static State decode(byte[] bytes) throws MalformedRecord {
        try {
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (!Arrays.equals(bytes, json.getBytes(StandardCharsets.UTF_8))) throw new IOException("invalid UTF-8");
            JSONTokener token = new JSONTokener(json); JSONObject root = new JSONObject(token);
            if (token.nextClean() != 0) throw new IOException("trailing data");
            keys(root, "version", "rootUri", "directory", "draft", "previousQuery", "acceptedPlanJson", "notice", "messages");
            if (number(root, "version") != 1) throw new IOException("unknown version");
            String tree = string(root, "rootUri", 4096), directory = string(root, "directory", 2048); scope(tree, directory);
            String draft = string(root, "draft", MAX_DRAFT), query = string(root, "previousQuery", 1000), plan = string(root, "acceptedPlanJson", 4096), notice = string(root, "notice", 1000);
            if (query.isEmpty() && !plan.isEmpty()) throw new IOException("accepted filters without a user query");
            if (!query.isEmpty()) searchQuery(query);
            if (!plan.isEmpty()) plan = canonicalPlan(plan, ZoneOffset.UTC);
            JSONArray rows = root.getJSONArray("messages");
            if (rows.length() > MAX_MESSAGES) throw new IOException("too many messages");
            List<Message> messages = new ArrayList<>(); Set<String> ids = new HashSet<>(); int count = 0;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i); keys(row, "id", "role", "text", "status", "createdAt", "rootUri", "directory", "results");
                String id = string(row, "id", 100), role = string(row, "role", 20), text = string(row, "text", MAX_MESSAGE_TEXT), status = string(row, "status", 20);
                if (id.isEmpty() || !ids.add(id)) throw new IOException("duplicate/missing message id");
                messageFields(role, text, status);
                String messageTree = string(row, "rootUri", 4096), messageDirectory = string(row, "directory", 2048); scope(messageTree, messageDirectory);
                long created = number(row, "createdAt"); if (created < 0) throw new IOException("invalid time");
                JSONArray results = row.getJSONArray("results"); count += results.length(); if (count > MAX_RESULTS) throw new IOException("too many result rows");
                List<ResultMetadata> items = new ArrayList<>();
                for (int r = 0; r < results.length(); r++) {
                    JSONObject result = results.getJSONObject(r); keys(result, "id", "name", "mime", "size", "modified", "uri", "parent");
                    ResultMetadata item = new ResultMetadata(string(result, "id", 2048), string(result, "name", 1024), string(result, "mime", 256), number(result, "size"), number(result, "modified"), string(result, "uri", 4096), string(result, "parent", 2048));
                    metadata(item); items.add(item);
                }
                if (!items.isEmpty() && !role.equals("assistant")) throw new IOException("results on non-assistant message");
                messages.add(new Message(id, role, text, status, created, messageTree, messageDirectory, items));
            }
            return new State(tree, directory, draft, query, plan, messages, notice);
        } catch (Exception invalid) { throw new MalformedRecord("invalid local conversation", invalid); }
    }
    private static String canonicalPlan(String json, ZoneId zone) throws IOException {
        try {
            FileSearchPlan plan = FileSearchPlan.parse(json, zone);
            return new JSONObject().put("nameContains", plan.nameContains).put("type", plan.type).put("modifiedFrom", plan.modifiedFrom).put("modifiedBefore", plan.modifiedBefore)
                .put("sort", plan.sort).put("duplicates", plan.duplicateCandidates ? "candidates" : "none").put("unsupported", "").toString();
        } catch (Exception invalid) { throw new IOException("허용되지 않은 검색 조건은 대화 문맥에 저장하지 않아요.", invalid); }
    }
    private static void messageFields(String role, String text, String status) throws IOException {
        if (role == null || status == null || !ROLES.contains(role) || !STATUSES.contains(status)) throw new IOException("허용되지 않은 대화 역할 또는 상태예요.");
        text(text, MAX_MESSAGE_TEXT, "대화 메시지");
    }
    private static void searchQuery(String query) throws IOException {
        text(query, 1000, "검색 요청");
        if (query.isBlank() || query.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) throw new IOException("검색 요청은 1~1,000자의 올바른 문장으로 입력해 주세요.");
    }
    private static void metadata(ResultMetadata item) throws IOException {
        if (item == null) throw new IOException("검색 결과 정보가 없어요.");
        text(item.id(), 2048, "결과 ID"); text(item.name(), 1024, "결과 이름"); text(item.mime(), 256, "결과 종류"); text(item.parent(), 2048, "결과 상위 위치"); text(item.uri(), 4096, "결과 URI");
        if (item.id().isEmpty() || !localMetadataUri(item.uri()) || item.size() < -1 || item.modified() < 0) throw new IOException("검색 결과 메타데이터 형식이 올바르지 않아요.");
    }
    private static void scope(String root, String directory) throws IOException {
        text(root, 4096, "검색 위치"); text(directory, 2048, "검색 폴더");
        if (root.isEmpty() != directory.isEmpty() || !root.isEmpty() && !localMetadataUri(root)) throw new IOException("검색 범위 정보가 올바르지 않아요.");
    }
    private static boolean localMetadataUri(String value) { return value.startsWith("content://") || value.startsWith("myaide-public://"); }
    private static void text(String value, int max, String label) throws IOException {
        if (value == null || value.length() > max || value.indexOf('\0') >= 0) throw new IOException(label + "이(가) 너무 길거나 올바르지 않아요.");
    }
    private static String string(JSONObject object, String key, int max) throws Exception {
        Object value = object.get(key); if (!(value instanceof String)) throw new IOException("not a string: " + key);
        String result = (String)value; text(result, max, key); return result;
    }
    private static long number(JSONObject object, String key) throws Exception {
        Object value = object.get(key); if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("not an integer: " + key);
        return ((Number)value).longValue();
    }
    private static void keys(JSONObject object, String... expected) throws IOException {
        Set<String> actual = new HashSet<>(); for (Iterator<String> keys = object.keys(); keys.hasNext();) actual.add(keys.next());
        if (!actual.equals(new HashSet<>(Arrays.asList(expected)))) throw new IOException("unexpected stored fields");
    }
    private static final class MalformedRecord extends IOException {
        MalformedRecord(String message) { super(message); }
        MalformedRecord(String message, Throwable cause) { super(message, cause); }
    }
}
