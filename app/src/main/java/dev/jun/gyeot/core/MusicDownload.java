package dev.jun.gyeot.core;

import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Provider-independent audio download policy. Never infers audio from a service name. */
public final class MusicDownload {
    private MusicDownload() {}
    private static final Map<String, String> TYPES = Map.ofEntries(
        Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"), Map.entry("m4a", "audio/mp4"),
        Map.entry("aac", "audio/aac"), Map.entry("flac", "audio/flac"), Map.entry("ogg", "audio/ogg"),
        Map.entry("opus", "audio/opus"), Map.entry("aiff", "audio/aiff"), Map.entry("webm", "audio/webm"));
    public record FileInfo(String name, String mime) {}
    public static String header(Map<String,String> headers, String key) {
        if (headers != null) for (var entry : headers.entrySet()) if (key.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return "";
    }
    public static FileInfo classify(String url, Map<String,String> headers) {
        String mime = header(headers, "Content-Type").split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (mime.equals("audio/x-wav") || mime.equals("audio/wave")) mime = "audio/wav";
        if (mime.equals("audio/x-flac")) mime = "audio/flac";
        String name = dispositionName(header(headers, "Content-Disposition"));
        if (name.isBlank()) try { String path = URI.create(url).getPath(); name = path.substring(path.lastIndexOf('/') + 1); } catch (Exception ignored) { }
        name = name.replaceAll("[\\\\/\\p{Cntrl}\\p{Cf}:*?\"<>|]", "_").replaceAll("^\\.+", "").trim();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        boolean generic = mime.isEmpty() || mime.equals("application/octet-stream") || mime.equals("binary/octet-stream");
        // An explicit video/document/HTML MIME wins over an audio-looking filename.
        if (generic) {
            // webm and ogg containers are ambiguous without an explicit audio MIME.
            if (ext.equals("webm") || ext.equals("ogg") || !TYPES.containsKey(ext)) return null;
            mime = TYPES.get(ext);
        }
        if (!TYPES.containsValue(mime)) return null;
        String outputExt = ext;
        if (!mime.equals(TYPES.get(ext))) {
            outputExt = "";
            for (var type : TYPES.entrySet()) if (type.getValue().equals(mime)) { outputExt = type.getKey(); break; }
        }
        String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        if (stem.isBlank()) stem = "music";
        // Android filesystem filename limits are byte-based, not Java character-based.
        StringBuilder safeStem = new StringBuilder(); int utf8Bytes = 0;
        for (int offset = 0; offset < stem.length();) {
            int codePoint = stem.codePointAt(offset); String piece = new String(Character.toChars(codePoint));
            int size = piece.getBytes(StandardCharsets.UTF_8).length;
            if (utf8Bytes + size > 160) break;
            safeStem.append(piece); utf8Bytes += size; offset += Character.charCount(codePoint);
        }
        stem = safeStem.toString();
        return new FileInfo(stem + "-" + UUID.randomUUID() + "." + outputExt, mime);
    }
    static String dispositionName(String disposition) {
        Matcher utf = Pattern.compile("(?:^|;)\\s*filename\\*\\s*=\\s*(?:\")?UTF-8'[^']*'([^;\"]+)", Pattern.CASE_INSENSITIVE).matcher(disposition);
        if (utf.find()) try { return URLDecoder.decode(utf.group(1).trim().replace("+", "%2B"), StandardCharsets.UTF_8); } catch (IllegalArgumentException ignored) { }
        Matcher plain = Pattern.compile("(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))", Pattern.CASE_INSENSITIVE).matcher(disposition);
        return plain.find() ? (plain.group(1) == null ? plain.group(2).trim() : plain.group(1)) : "";
    }
    /** Transaction boundary shared by the SAF adapter and JVM integration tests. */
    public interface Destination {
        OutputStream create(FileInfo info) throws Exception;
        void commit() throws Exception;
        void rollback() throws Exception;
    }
    /** The largest audio file kept. */
    public static final long LIMIT = 4L * 1024 * 1024 * 1024;
    public static long save(InputStream input, FileInfo info, long expectedBytes, Destination destination) throws Exception {
        if (input == null || info == null) throw new IOException("음악 파일 본문이 없어요.");
        long count = 0;
        try (PushbackInputStream source = new PushbackInputStream(input, 256)) {
            byte[] prefix = source.readNBytes(256);
            String start = new String(prefix, StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
            if (start.startsWith("<!doctype html") || start.startsWith("<html") || start.startsWith("{\"") || start.startsWith("<?xml"))
                throw new IOException("음악 대신 오류 페이지를 받아 저장하지 않았어요.");
            source.unread(prefix);
            try (OutputStream output = destination.create(info)) {
                if (output == null) throw new IOException("저장 폴더에 쓸 수 없어요.");
                byte[] buffer = new byte[32 * 1024]; int size;
                while ((size = source.read(buffer)) != -1) {
                    count += size;
                    if (count > LIMIT) throw new IOException("음악 파일이 " + (LIMIT >> 30) + "GB를 넘어요.");
                    output.write(buffer, 0, size);
                }
                if (count == 0 || (expectedBytes >= 0 && count != expectedBytes)) throw new IOException("음악 다운로드가 완성되지 않았어요.");
            }
            destination.commit();
            return count;
        } catch (Exception failure) {
            try { destination.rollback(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
