package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The 들퍼레 folder for a work job's model: folder_list shows what a folder in it holds, folder_save copies a file the
 * job downloaded with the browser into a folder in it (made when missing). Only inside 들퍼레, never over an existing file,
 * and a save only when the user's own words ask to keep files. Pure Java: names, paths and the rule; the phone does the rest.
 */
public final class FolderTools {
    private FolderTools() {}
    public static final String LIST = "folder_list", SAVE = "folder_save", ROOT = "들퍼레";
    public static final int DEPTH = 5, ENTRIES = 300, NAME = 120;
    /** The kinds of file that may be kept: documents, pictures, sound and video, and archives of them. */
    public static final Set<String> TYPES = Set.of("pdf", "png", "jpg", "jpeg", "webp", "gif", "txt", "md", "csv", "json", "hwp", "hwpx", "docx", "xlsx", "pptx", "zip", "mp3", "m4a", "wav", "mp4");
    public static boolean is(String name) { return LIST.equals(name) || SAVE.equals(name); }
    public static String stage(String name) { return SAVE.equals(name) ? "들퍼레 폴더에 저장하는 중" : "들퍼레 폴더를 살펴보는 중"; }

    public static final String INSTRUCTIONS = " The user's 들퍼레 folder (on this phone) is available: folder_list shows what a folder in it holds, "
        + "folder_save copies a file this job downloaded with the browser (its artifact id from the browser result) into a folder in it, making the folder when needed. "
        + "Save only when the user asked to save, keep or collect files there; use the folder and the file names the user asked for. "
        + "Nothing is ever overwritten: a file already there with the same name and size is reported as already kept, another with that name gets a number. "
        + "Check with folder_list before downloading what may already be there. Report only what folder_save said it saved.";
    public static final String NOT_ASKED = "Nothing was saved: the user's own words did not ask to save or keep files in the 들퍼레 folder. Tell the user they can ask, "
        + "for example \"들퍼레/수능 폴더에 저장해 줘\".";

    public static JSONArray declarations() {
        return arr(
            obj("name", LIST, "description", "List a folder inside the user's 들퍼레 folder: names, which are folders, and sizes. An empty folder path is 들퍼레 itself.",
                "parameters", obj("type", "object", "properties", obj(
                    "folder", obj("type", "string", "description", "A folder path inside 들퍼레, like 수능/2025; empty for 들퍼레 itself")), "additionalProperties", false)),
            obj("name", SAVE, "description", "Copy one file this job downloaded with the browser into a folder inside 들퍼레 (made when missing). Never overwrites. "
                    + "Only when the user asked to save or keep files there.",
                "parameters", obj("type", "object", "required", arr("file_id", "folder"), "properties", obj(
                    "file_id", obj("type", "string", "description", "The downloaded file's artifact id from the browser result"),
                    "folder", obj("type", "string", "description", "A folder path inside 들퍼레, like 수능/2025"),
                    "name", obj("type", "string", "description", "The file name to keep it under, with its extension; the downloaded name when empty")), "additionalProperties", false)));
    }

    private static final Pattern KEEP = Pattern.compile("저장|보관|모아|모으|수집|넣어|담아|정리해|폴더|다운로드|내려받|받아\\s*(?:줘|주|서|놔|둬)|(?i:save|download|folder)");
    private static final Pattern FORBID = Pattern.compile("(?:저장|보관|다운로드)(?:하)?지\\s*(?:마|말|않)");
    /** Whether the user's own words for this run ask to keep files: a keeping word, and not asked against. */
    public static boolean asked(String words) { return words != null && !words.isBlank() && KEEP.matcher(words).find() && !FORBID.matcher(words).find(); }

    /** A folder inside 들퍼레 as a relative path ("수능/2025"), "" for 들퍼레 itself, or null when it is not one. */
    public static String folder(String value) {
        String path = value == null ? "" : value.strip();
        if (path.indexOf('\\') >= 0) return null;
        while (path.startsWith("/")) path = path.substring(1);
        if (path.equals(ROOT)) return "";
        if (path.startsWith(ROOT + "/")) path = path.substring(ROOT.length() + 1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) return "";
        String[] parts = path.split("/", -1);
        if (parts.length > DEPTH) return null;
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String piece = part.strip();
            if (!single(piece) || piece.length() > 80) return null;
            if (out.length() > 0) out.append('/');
            out.append(piece);
        }
        return out.toString();
    }
    /** A name that is one plain part of a path: not empty, not hidden, no separators or control characters. */
    static boolean single(String part) {
        return part != null && !part.isEmpty() && !part.startsWith(".") && part.indexOf('/') < 0 && part.indexOf('\\') < 0
            && part.chars().noneMatch(c -> Character.isISOControl(c) || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|');
    }
    /** The extension of a name, lower case, without its dot; "" when it has none. */
    public static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    /** What the first bytes of a file say it is: pdf, png, jpg, gif, webp or zip; "" when they say none of these. */
    public static String sniff(byte[] head) {
        if (head == null) return "";
        if (starts(head, "%PDF")) return "pdf";
        if (head.length >= 8 && (head[0] & 255) == 0x89 && starts(head, 1, "PNG")) return "png";
        if (head.length >= 3 && (head[0] & 255) == 0xff && (head[1] & 255) == 0xd8 && (head[2] & 255) == 0xff) return "jpg";
        if (starts(head, "GIF8")) return "gif";
        if (starts(head, "RIFF") && starts(head, 8, "WEBP")) return "webp";
        if (starts(head, "PK\u0003\u0004")) return "zip";
        return "";
    }
    private static boolean starts(byte[] head, String text) { return starts(head, 0, text); }
    private static boolean starts(byte[] head, int at, String text) {
        if (head.length < at + text.length()) return false;
        for (int i = 0; i < text.length(); i++) if ((head[at + i] & 255) != text.charAt(i)) return false;
        return true;
    }
    private static final Set<String> ZIPPED = Set.of("zip", "docx", "xlsx", "pptx", "hwpx");
    /**
     * The name a file is kept under: the one asked for (else the downloaded one), with the extension of what the file
     * really is. null when the name is not a plain file name, the kind may not be kept, or the name asks for a kind the
     * bytes are not (a web page saved as .pdf).
     */
    public static String name(String wanted, String downloaded, String kind) {
        String base = wanted == null || wanted.isBlank() ? (downloaded == null ? "" : downloaded.strip()) : wanted.strip();
        if (!single(base)) return null;
        String asked = extension(base), real = kind == null ? "" : kind;
        if (asked.isEmpty()) {
            String from = !real.isEmpty() ? real : extension(downloaded);
            if (from.isEmpty()) return null;
            base = base + "." + from; asked = from;
        }
        if (!TYPES.contains(asked)) return null;
        // What the bytes say wins over a name: a page of HTML is not kept as a PDF, a picture not as a document.
        if (!real.isEmpty() && !real.equals(asked) && !(real.equals("jpg") && asked.equals("jpeg")) && !(real.equals("zip") && ZIPPED.contains(asked))) return null;
        if (real.isEmpty() && Set.of("pdf", "png", "jpg", "jpeg", "gif", "webp", "zip").contains(asked)) return null;
        if (base.length() > NAME) { int keep = NAME - asked.length() - 1; base = base.substring(0, Math.max(1, keep)).strip() + "." + asked; }
        return base;
    }
    /** The name itself when it is free, else the same with " (2)", " (3)" … before its extension. */
    public static String unique(String name, Set<String> taken) {
        if (!taken.contains(name)) return name;
        int dot = name.lastIndexOf('.'); String stem = dot > 0 ? name.substring(0, dot) : name, tail = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) { String next = stem + " (" + n + ")" + tail; if (!taken.contains(next)) return next; }
    }
}
