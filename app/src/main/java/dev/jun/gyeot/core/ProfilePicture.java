package dev.jun.gyeot.core;

/**
 * The image request for a GPT-made assistant profile picture: the user's own description, an optional style, and the
 * framing an avatar needs (one centered subject that still reads inside a small circle, no text). Only this text is sent;
 * the current profile photo never is.
 */
public final class ProfilePicture {
    /** Style capsules shown in the editor, in order; the first adds nothing. */
    public static final String[] STYLES = {"자동", "일러스트", "3D", "수채화", "픽셀 아트", "사진처럼"};
    private static final String[] HINTS = {"", "Clean flat vector illustration.", "Soft, friendly 3D render, like a small character figurine.",
        "Gentle watercolor painting.", "Crisp pixel art.", "Photorealistic, natural light."};
    /** Characters of the user's description kept in the request. */
    public static final int LIMIT = 600;
    private ProfilePicture() {}

    /** The style's English hint ("" for 자동). */
    public static String hint(int style) { return style > 0 && style < HINTS.length ? HINTS[style] : ""; }
    public static String prompt(String description, int style) {
        String text = description == null ? "" : description.strip();
        if (text.isEmpty()) throw new IllegalArgumentException("어떤 모습인지 한 줄이라도 적어 주세요.");
        if (text.length() > LIMIT) text = text.substring(0, Character.isHighSurrogate(text.charAt(LIMIT - 1)) ? LIMIT - 1 : LIMIT);
        String hint = style > 0 && style < HINTS.length ? HINTS[style] + " " : "";
        return "Profile picture (avatar) for a personal AI assistant app. " + hint
            + "Square image with one clear subject centered and comfortable margin around it, so it still reads inside a small circle. "
            + "Simple, uncluttered background. No text, letters, logos, watermark or frame. "
            + "The user describes it in their own words: " + text;
    }
}
