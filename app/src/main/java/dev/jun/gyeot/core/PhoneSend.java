package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.Set;

/**
 * Finding the send button for phone_action send: after set_text in a messenger or form, the button that sends what was
 * typed, read from the live screen. A button counts when its whole label is a send word (전송, 보내기, Send…) or its view id
 * is a send button; "미디어 전송", attach, sticker and the like never count. The one in the typed field's row, to its right,
 * wins. Pure logic; the phone service walks the screen.
 */
public final class PhoneSend {
    private static final Set<String> WORDS = Set.of("전송", "보내기", "전송 버튼", "보내기 버튼", "메시지 보내기", "메시지 전송", "send", "send message", "submit", "제출");
    private static final String[] NOT = {"미디어", "media", "attach", "첨부", "sticker", "스티커", "emoticon", "이모티콘", "voice", "음성", "photo", "사진", "file", "파일", "gift", "선물", "keyboard", "키보드"};
    private PhoneSend() {}

    /** Whether a button with this text, description and view id sends what was typed. */
    public static boolean sends(String text, String description, String viewId) {
        String label = clean(text), said = clean(description), id = viewId == null ? "" : viewId.toLowerCase(Locale.ROOT);
        for (String no : NOT) if (label.contains(no) || said.contains(no) || id.contains(no)) return false;
        if (WORDS.contains(label) || WORDS.contains(said)) return true;
        String name = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;
        return name.matches("(btn_?)?send(_?(button|btn|icon|image|view))?|send_?message|(btn_?)?submit");
    }
    private static String clean(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }

    /**
     * How well a button fits the field just typed into, lower is better: in the field's row and to its right first, then by
     * distance. Boxes are {left, top, right, bottom}; field may be null when no field is known.
     */
    public static long rank(int[] button, int[] field) {
        if (field == null) return 1_000_000L - (long) button[1];
        int buttonY = (button[1] + button[3]) / 2, fieldY = (field[1] + field[3]) / 2, height = Math.max(1, field[3] - field[1]);
        boolean row = Math.abs(buttonY - fieldY) <= Math.max(height, 80), right = button[0] >= field[0] + (field[2] - field[0]) / 2;
        long dx = (button[0] + button[2]) / 2 - (field[0] + field[2]) / 2L, dy = buttonY - fieldY;
        long distance = (long) Math.sqrt((double) dx * dx + (double) dy * dy);
        return (row && right ? 0 : row ? 100_000 : 200_000) + distance;
    }
}
