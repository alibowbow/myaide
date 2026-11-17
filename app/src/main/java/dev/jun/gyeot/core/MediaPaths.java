package dev.jun.gyeot.core;

/**
 * Where a picture or video saved in the 들퍼레 folder lives on the phone's primary storage, so Android's media index can
 * register it. Only documents inside 들퍼레 from the two providers the app saves through (the app's managed provider and
 * Android's external-storage provider) map to a path; anything else maps to nothing.
 */
public final class MediaPaths {
    public static final String EXTERNAL = "com.android.externalstorage.documents", MANAGED = "dev.jun.gyeot.shared-documents";
    public static final String ROOT = "들퍼레";
    private MediaPaths() {}

    /** The absolute path for a saved document, or null when it is not a file inside 들퍼레 on the primary volume. */
    public static String path(String authority, String documentId, String volume) {
        if (authority == null || documentId == null || volume == null || volume.isBlank() || !volume.startsWith("/")) return null;
        if (!EXTERNAL.equals(authority) && !MANAGED.equals(authority)) return null;
        if (!documentId.startsWith("primary:")) return null;
        String relative = documentId.substring("primary:".length());
        if (!relative.startsWith(ROOT + "/")) return null;
        for (String part : relative.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.contains("\\") || part.chars().anyMatch(Character::isISOControl)) return null;
        String base = volume.endsWith("/") ? volume.substring(0, volume.length() - 1) : volume;
        return base + "/" + relative;
    }
    /** Pictures, videos and audio go to the media index; documents do not. */
    public static boolean media(String mime) { return mime != null && (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")); }
    /** A media type from a saved file's name, for files whose type was not recorded. */
    public static String mime(String name) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        return "";
    }
}
