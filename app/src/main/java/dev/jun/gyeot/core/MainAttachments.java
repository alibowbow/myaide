package dev.jun.gyeot.core;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Files attached to a main-chat message: photos, PDFs and text files, as 작업창 takes them
 * (WorkFiles.add: {id, name, mime, kind, size, text?}). A photo or a PDF goes to the engine as the provider's own file part
 * with that message (the protocols' 작업창 converters); a text file goes inside the message as text, so every engine can
 * read it, in the request's attached_files section after the question. Later turns see a marker naming what was attached,
 * not the files again.
 */
public final class MainAttachments {
    /** Files per message at most. */
    public static final int COUNT = 5;
    /** Photos and PDFs of one message together at most (bytes as stored; a request carries them in base64). */
    public static final long BYTES = WorkBook.FILE_BYTES;
    /** Text of the text files of one message together at most, in characters. */
    public static final int TEXT = WorkBook.FILE_TEXT;
    /** What the main chat sends: photos, PDFs and text. Video and audio stay 작업창's. */
    public static final Set<String> KINDS = Set.of(WorkBook.IMAGE, WorkBook.PDF, WorkBook.TEXT);
    /** The words a message with files and no words of its own asks with. */
    public static final String ASK = "첨부한 파일을 봐 주세요.";
    private MainAttachments() {}

    /** Why a picked file cannot be added to the ones already picked, or "" when it can. */
    public static String refusal(JSONArray picked, JSONObject file) {
        if (file == null || !KINDS.contains(file.optString("kind")))
            return "메인 대화에는 사진·PDF·글 파일을 첨부할 수 있어요. 영상·음성은 작업창에 보내 주세요.";
        if (picked != null && picked.length() >= COUNT) return "한 번에 " + COUNT + "개까지 첨부할 수 있어요.";
        long bytes = binary(file) ? Math.max(0, file.optLong("size")) : 0; int text = WorkBook.TEXT.equals(file.optString("kind")) ? file.optString("text").length() : 0;
        for (int i = 0; picked != null && i < picked.length(); i++) {
            JSONObject other = picked.optJSONObject(i); if (other == null) continue;
            if (binary(other)) bytes += Math.max(0, other.optLong("size")); else text += other.optString("text").length();
        }
        if (bytes > BYTES) return "사진·PDF는 한 번에 " + (BYTES / (1024 * 1024)) + "MB까지 보낼 수 있어요.";
        if (text > TEXT) return "글 파일은 한 번에 " + String.format(java.util.Locale.ROOT, "%,d", TEXT) + "자까지 보낼 수 있어요.";
        return "";
    }
    /**
     * Why these files cannot go to engine, or "" when they can: NIM's chat API takes JPEG, PNG and WebP photos and never a
     * PDF; the other four take both. Checked before the message is kept, and again before a request is sent (MainRequest).
     */
    public static String refusedBy(String engine, JSONArray files) {
        if (WorkBook.ANTIGRAVITY.equals(WorkBook.engine(engine))) {
            for (int i = 0; files != null && i < files.length(); i++) if (binary(files.optJSONObject(i))) return "메인 대화의 Antigravity 연결에는 사진·PDF를 보낼 수 없어요. 글만 보내거나 다른 엔진을 골라 주세요.";
            return "";
        }
        if (!WorkBook.NIM.equals(WorkBook.engine(engine))) return "";
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i); if (!binary(file)) continue;
            if (WorkBook.PDF.equals(file.optString("kind"))) return "메인 대화의 " + WorkBook.author(WorkBook.NIM) + " 연결에는 PDF를 보낼 수 없어요. 사진·글 파일만 보내거나 다른 엔진을 골라 주세요.";
            if (!Set.of("image/jpeg", "image/png", "image/webp").contains(file.optString("mime"))) return "메인 대화의 " + WorkBook.author(WorkBook.NIM) + " 연결에는 JPEG·PNG·WebP 사진만 보낼 수 있어요.";
        }
        return "";
    }
    /** A photo or a PDF: sent as a file part, kept as a file. */
    public static boolean binary(JSONObject file) { return file != null && (WorkBook.IMAGE.equals(file.optString("kind")) || WorkBook.PDF.equals(file.optString("kind"))); }
    /** The stored list: only what the main chat sends, with safe ids and names, never any bytes. */
    public static JSONArray clean(JSONArray files) {
        JSONArray kept = arr();
        for (int i = 0; files != null && i < files.length() && kept.length() < COUNT; i++) {
            JSONObject file = files.optJSONObject(i); if (file == null || !KINDS.contains(file.optString("kind"))) continue;
            String name = WorkBook.fileName(file.optString("name")), id = file.optString("id");
            if (name == null || !WorkBook.safeId(id)) continue;
            JSONObject copy = obj("id", id, "name", name, "mime", file.optString("mime"), "kind", file.optString("kind"), "size", Math.max(0, file.optLong("size")));
            if (WorkBook.TEXT.equals(file.optString("kind"))) put(copy, "text", file.optString("text"));
            kept.put(copy);
        }
        return kept;
    }
    /** The files of a stored message (its files column), [] for none or a value that is not a list. */
    public static JSONArray of(JSONObject message) {
        Object value = message == null ? null : message.opt("files");
        if (value instanceof JSONArray list) return list;
        String text = value == null ? "" : String.valueOf(value);
        if (text.isBlank()) return arr();
        try { return new JSONArray(text); } catch (Exception notList) { return arr(); }
    }
    /** "[첨부: 보고서.pdf, 사진.jpg]" for a message's line in the history; "" without files. */
    public static String marker(JSONArray files) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i); if (file == null) continue;
            names.append(names.length() == 0 ? "" : ", ").append(file.optString("name"));
        }
        return names.length() == 0 ? "" : "[첨부: " + names + "]";
    }
    /** The question as the request carries it: the words, then the marker naming every attached file. Without files, the words as they are. */
    public static String question(String words, JSONArray files) {
        String marker = marker(files), text = words == null ? "" : words;
        return marker.isEmpty() ? text : (text.strip() + "\n\n" + marker).strip();
    }
    /**
     * The text files' text for this request's attached_files section (MainChatContext), each under its name, at most TEXT
     * characters together; "" when none of the files is a text file.
     */
    public static String texts(JSONArray files) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i); if (file == null || !WorkBook.TEXT.equals(file.optString("kind"))) continue;
            text.append(text.length() == 0 ? "" : "\n\n").append("[파일: ").append(file.optString("name")).append("]\n").append(file.optString("text").strip());
        }
        return text.length() > TEXT ? MainChatContext.clip(text.toString(), TEXT) + "\n…(첨부한 글이 길어 뒷부분은 줄였어요)" : text.toString();
    }
    /** The one user turn a request with file parts sends (MainRequest.parts), in 작업창's turn form, for the protocols' converters. */
    public static JSONArray turn(String input, JSONArray parts) { return arr(obj("role", "user", "content", input, "files", parts)); }
    /** The line under a message with files: "📄 보고서.pdf · 🖼 사진.jpg". */
    public static String label(JSONArray files) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i); if (file == null) continue;
            String icon = switch (file.optString("kind")) { case WorkBook.IMAGE -> "🖼"; case WorkBook.PDF -> "📄"; default -> "📝"; };
            line.append(line.length() == 0 ? "" : " · ").append(icon).append(' ').append(file.optString("name"));
        }
        return line.toString();
    }
}
