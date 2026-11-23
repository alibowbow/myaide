package dev.jun.gyeot;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One Antigravity turn, the way agy runs as an agent: --input-format/--output-format stream-json, the words streamed as
 * they come (step_update · agent_response · text_delta), a conversation of its own continued with --conversation, the
 * model and reasoning level agy offers (--model, --effort), and the app's own tools offered over MCP.
 *
 * The tools: agy's agent "gyeot" (in /root/gyeot-work/.agents) may use only the MCP server "gyeot"; agy's own commands,
 * file reading and writing and URL reading are denied in its settings. That MCP server is a few lines of bash that hand
 * each JSON-RPC line to the app with curl, on 127.0.0.1 with a token made for this one turn; the app answers it with the
 * room's WorkTools (GitHub, files, pages, calendar …) under the turn's guard, so a stop stops them too.
 */
final class AgyRunner {
    private AgyRunner() {}
    static final String WORK = "/root/gyeot-work";
    record Result(String text, String conversation, boolean usedTools) {}

    static Result run(Context c, String model, String effort, String conversation, String prompt, WorkTools tools,
            Consumer<String> onText, Consumer<String> onStage, Runnable guard, AtomicReference<Runnable> cancel, long timeoutMs) throws Exception {
        if (!AntigravityCli.installed(c)) throw new IllegalStateException("Antigravity CLI가 설치되지 않았어요. 설정 → Antigravity에서 설치하고 로그인해 주세요.");
        String m = model == null || model.isBlank() || AntigravityAccount.DEFAULT.equals(model) ? "" : model;
        if (!m.isEmpty() && !m.matches("[a-z0-9][a-z0-9._-]{1,120}")) throw new IllegalArgumentException("Antigravity 모델 이름을 확인해 주세요.");
        String e = effort == null ? "" : effort.strip();
        if (!e.isEmpty() && !e.matches("[a-z0-9_-]{1,20}")) e = "";
        String id = conversation == null ? "" : conversation.strip();
        if (!id.isEmpty() && !id.matches("[A-Za-z0-9._:-]{4,200}")) id = "";
        Bridge bridge = tools != null && tools.declarations().length() > 0 ? new Bridge(tools, onStage, guard) : null;
        try {
            prepare(c, bridge);
            String command = "cd " + WORK + " && exec " + AntigravityCli.BINARY + " --input-format stream-json --output-format stream-json --agent gyeot --disable-slash-commands"
                + (id.isEmpty() ? "" : " --conversation " + id) + (m.isEmpty() ? "" : " --model " + m) + (e.isEmpty() ? "" : " --effort " + e);
            Process p = LinuxRuntime.start(c, LinuxRuntime.rootfs(c), command, AntigravityCli.env());
            if (cancel != null) cancel.set(p::destroyForcibly);
            try (OutputStream in = p.getOutputStream()) {
                in.write((new JSONObject().put("event", "user").put("message", new JSONObject().put("content", prompt)).toString() + "\n").getBytes(StandardCharsets.UTF_8));
                in.flush();
                StringBuilder text = new StringBuilder(), other = new StringBuilder(); String found = id; Boolean ok = null; String failure = "", answer = "";
                long until = System.currentTimeMillis() + timeoutMs;
                Thread watchdog = new Thread(() -> { try { if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly(); } catch (InterruptedException ignored) {} }, "agy-timeout");
                watchdog.setDaemon(true); watchdog.start();
                BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                for (String line; (line = out.readLine()) != null; ) {
                    if (guard != null) guard.run();
                    String t = line.strip(); if (!t.startsWith("{")) { if (other.length() < 4000) other.append(t).append('\n'); continue; }
                    JSONObject ev; try { ev = new JSONObject(t); } catch (Exception notJson) { continue; }
                    String conv = ev.optString("conversation_id", ev.optString("session_id", ""));
                    if (!conv.isEmpty()) found = conv;
                    String kind = ev.optString("event");
                    if ("step_update".equals(kind)) {
                        JSONObject step = ev.optJSONObject("step_update"); if (step == null) continue;
                        String type = step.optString("step_type");
                        if ("agent_response".equals(type)) { String d = step.optString("text_delta"); if (!d.isEmpty()) { text.append(d); if (onText != null) onText.accept(d); } }
                        else if ("tool".equals(type) && onStage != null) onStage.accept("Antigravity가 도구를 쓰는 중");
                    } else if ("result".equals(kind)) {
                        JSONObject r = ev.optJSONObject("result"); if (r == null) r = new JSONObject();
                        if (!r.optString("conversation_id").isEmpty()) found = r.optString("conversation_id");
                        ok = "SUCCESS".equals(r.optString("status"));
                        answer = r.optString("response"); failure = r.optString("error", r.optString("status"));
                        break;
                    }
                }
                p.destroy();
                if (ok == null) {
                    if (System.currentTimeMillis() > until) throw new IllegalStateException("Antigravity가 제때 답하지 않았어요.");
                    String why = LinuxTerminal.plain(other.toString()).strip();
                    if (why.matches("(?is).*(authentication required|not (?:logged|signed)[- ]?in|login required|unauthenticated).*")) throw new IllegalStateException("Antigravity에 로그인해 주세요 (설정 → Antigravity → 로그인).");
                    throw new IllegalStateException("Antigravity가 답하지 못했어요" + (why.isEmpty() ? "." : ": " + clip(why)));
                }
                if (!ok) throw new IllegalStateException("Antigravity가 답하지 못했어요: " + clip(failure.isBlank() ? answer : failure));
                String whole = text.length() > 0 ? text.toString() : answer;
                if (text.length() == 0 && !whole.isEmpty() && onText != null) onText.accept(whole);
                return new Result(whole.strip(), found, bridge != null && bridge.used);
            } finally { p.destroyForcibly(); }
        } finally { if (bridge != null) bridge.close(); }
    }

    /**
     * agy's agent and settings in its own home and work folder: the "gyeot" agent (no commands, its tools only), agy's own
     * file, command and URL tools denied, and the app's MCP server when this turn has tools (none otherwise).
     */
    private static void prepare(Context c, Bridge bridge) throws Exception {
        File root = LinuxRuntime.rootfs(c), work = new File(root, WORK.substring(1)), agents = new File(work, ".agents/agents");
        agents.mkdirs();
        write(new File(agents, "gyeot.md"), "---\nname: gyeot\ndescription: 들퍼레 app assistant\nmainAgent: true\nsubagent: false\ncommandExecutionPolicy: off\ntools: []\n---\n"
            + "Answer the user's message. For anything on GitHub, files, pages, the calendar or the phone, use only the provided gyeot MCP tools.\n");
        JSONObject servers = new JSONObject();
        if (bridge != null) {
            File script = new File(root, "root/.gyeot/mcp-bridge.sh"); script.getParentFile().mkdirs();
            write(script, "#!/bin/bash\n# 들퍼레: each MCP line to the app (127.0.0.1, this turn's token), its answer back.\nurl=\"$1\"; token=\"$2\"\n"
                + "while IFS= read -r line; do\n  [ -z \"$line\" ] && continue\n"
                + "  out=$(printf '%s' \"$line\" | curl -s -X POST -H \"Authorization: Bearer $token\" -H 'Content-Type: application/json' --data-binary @- \"$url\")\n"
                + "  [ -n \"$out\" ] && printf '%s\\n' \"$out\"\ndone\n");
            android.system.Os.chmod(script.getPath(), 0700);
            servers.put("gyeot", new JSONObject().put("command", "/bin/bash").put("args", new JSONArray().put("/root/.gyeot/mcp-bridge.sh").put("http://127.0.0.1:" + bridge.port + "/mcp").put(bridge.token)));
        }
        write(new File(work, ".agents/mcp_config.json"), new JSONObject().put("mcpServers", servers).toString(2));
        File settingsFile = new File(root, (AntigravityCli.HOME + "/.gemini/antigravity-cli/settings.json").substring(1)); settingsFile.getParentFile().mkdirs();
        JSONObject settings; try { settings = settingsFile.isFile() ? new JSONObject(new String(Files.readAllBytes(settingsFile.toPath()), StandardCharsets.UTF_8)) : new JSONObject(); } catch (Exception broken) { settings = new JSONObject(); }
        settings.put("toolPermission", "request-review").put("artifactReviewPolicy", "asks-for-review")
            .put("permissions", new JSONObject().put("deny", new JSONArray().put("command(*)").put("read_file(*)").put("write_file(*)").put("read_url(*)"))
                .put("allow", bridge == null ? new JSONArray() : new JSONArray().put("mcp(gyeot/*)")));
        write(settingsFile, settings.toString(2));
    }
    private static void write(File f, String text) throws Exception { Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static String clip(String s) { String t = LinuxTerminal.plain(s == null ? "" : s).strip(); return t.length() > 300 ? t.substring(0, 300) + "…" : t; }

    /** The app's side of the MCP server: JSON-RPC over HTTP on 127.0.0.1, answered with the turn's WorkTools. */
    static final class Bridge implements AutoCloseable {
        final int port; final String token; volatile boolean used;
        private final ServerSocket server; private final WorkTools tools; private final Consumer<String> onStage; private final Runnable guard;
        Bridge(WorkTools tools, Consumer<String> onStage, Runnable guard) throws Exception {
            this.tools = tools; this.onStage = onStage; this.guard = guard;
            byte[] b = new byte[24]; new SecureRandom().nextBytes(b); StringBuilder t = new StringBuilder(); for (byte x : b) t.append(String.format(Locale.ROOT, "%02x", x)); token = t.toString();
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")); port = server.getLocalPort();
            Thread accept = new Thread(this::serve, "agy-mcp"); accept.setDaemon(true); accept.start();
        }
        private void serve() {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) { s.setSoTimeout(30_000); handle(s); } catch (Exception closed) { if (server.isClosed()) return; }
            }
        }
        private void handle(Socket s) throws Exception {
            InputStream in = s.getInputStream(); String head = readHead(in); if (head == null) return;
            int length = 0; boolean authorized = false;
            for (String line : head.split("\r\n")) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) length = Integer.parseInt(line.substring(15).strip());
                if (lower.startsWith("authorization:") && line.substring(14).strip().equals("Bearer " + token)) authorized = true;
            }
            if (!authorized || length < 0 || length > 4_000_000) { reply(s, 403, ""); return; }
            byte[] body = new byte[length]; int at = 0; while (at < length) { int n = in.read(body, at, length - at); if (n < 0) break; at += n; }
            String answer = answer(new String(body, 0, at, StandardCharsets.UTF_8));
            reply(s, 200, answer);
        }
        /** One JSON-RPC message's reply ("" for a notification). */
        String answer(String text) {
            JSONObject msg; try { msg = new JSONObject(text); } catch (Exception e) { return error(null, -32700, "parse error"); }
            Object id = msg.opt("id"); String method = msg.optString("method");
            if (id == null) return "";
            try {
                JSONObject result = switch (method) {
                    case "initialize" -> new JSONObject().put("protocolVersion", msg.optJSONObject("params") == null ? "2025-06-18" : msg.optJSONObject("params").optString("protocolVersion", "2025-06-18"))
                        .put("capabilities", new JSONObject().put("tools", new JSONObject())).put("serverInfo", new JSONObject().put("name", "gyeot").put("version", "1"));
                    case "ping" -> new JSONObject();
                    case "tools/list" -> {
                        JSONArray list = new JSONArray(), offered = dev.jun.gyeot.core.RealtimeVoice.tools(tools.declarations());
                        for (int i = 0; i < offered.length(); i++) { JSONObject f = offered.optJSONObject(i);
                            list.put(new JSONObject().put("name", f.optString("name")).put("description", f.optString("description")).put("inputSchema", f.optJSONObject("parameters"))); }
                        yield new JSONObject().put("tools", list);
                    }
                    case "tools/call" -> {
                        JSONObject p = msg.optJSONObject("params"); String name = p == null ? "" : p.optString("name");
                        if (!tools.supports(name)) yield new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "이 도구는 없어요: " + name))).put("isError", true);
                        if (guard != null) guard.run();
                        if (onStage != null) onStage.accept(WorkTools.stage(name));
                        used = true;
                        JSONObject out = tools.run(name, p.optJSONObject("arguments") == null ? new JSONObject() : p.optJSONObject("arguments"));
                        yield new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", out == null ? "{}" : out.toString())))
                            .put("isError", out != null && out.has("error"));
                    }
                    default -> null;
                };
                if (result == null) return error(id, -32601, "method not found: " + method);
                return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString();
            } catch (Exception e) { return error(id, -32000, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
        }
        private static String error(Object id, int code, String message) {
            try { return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id).put("error", new JSONObject().put("code", code).put("message", message)).toString(); }
            catch (Exception e) { return ""; }
        }
        private static String readHead(InputStream in) throws Exception {
            StringBuilder b = new StringBuilder(); int c, size = 0;
            while ((c = in.read()) >= 0) { b.append((char) c); if (++size > 16_384) return null; if (b.length() >= 4 && b.substring(b.length() - 4).equals("\r\n\r\n")) return b.toString(); }
            return null;
        }
        private static void reply(Socket s, int status, String body) throws Exception {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = s.getOutputStream();
            out.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Forbidden") + "\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(b); out.flush();
        }
        @Override public void close() { try { server.close(); } catch (Exception ignored) {} }
    }
}
