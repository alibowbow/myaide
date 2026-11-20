package dev.jun.gyeot.core;

import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 판서 from the voice assistant: the call hands a request, and the phone screen when the user means it, to this app's AI
 * whiteboard (BoardActivity), which then teaches by writing and speaking. The call takes the screen itself, and with phone
 * control on it scrolls down for more of it (up to PAGES screens), then ends so only the board speaks.
 */
public final class VoiceBoard {
    private VoiceBoard() {}
    public static final String FUNCTION = "board_explain";
    /** At most this many screens, scrolling down between them. */
    public static final int PAGES = 6;

    public static JSONObject declaration() {
        return obj("name", FUNCTION, "description", "Explain on 판서, this app's AI whiteboard: it opens, writes the explanation by hand and speaks it in its own voice. "
                + "Call it whenever the user wants something explained 판서로, on the board or 칠판, or written out while explaining (판서해 줘, 판서로 설명해 줘, 써 가면서 설명해 줘), "
                + "including what is on their screen (지금 화면 판서로 설명해 줘) and a document they want gone through by scrolling (스크롤하면서 판서해 줘): "
                + "the app takes the screen and scrolls it itself; no other screen or phone tool is needed for that. "
                + "After it starts, say one short sentence (판서로 설명할게요); this call then ends so that only the board speaks.",
            "parameters", obj("type", "OBJECT", "required", arr("request"), "properties", obj(
                "request", obj("type", "STRING", "description", "What to explain, in the user's own words with what they want stressed"),
                "screen", obj("type", "BOOLEAN", "description", "true to put what is on the phone screen now on the board"),
                "pages", obj("type", "INTEGER", "description", "With screen: how many screens to take, scrolling down between them (1-" + PAGES + "). "
                    + "More than 1 only when the user asks to scroll, to go through the page, or to explain the whole document"))));
    }
    /** How many screens a call's arguments ask for: 0 without the screen, else 1 to PAGES. */
    public static int pages(JSONObject args) {
        if (args == null || !args.optBoolean("screen")) return 0;
        return Math.max(1, Math.min(PAGES, args.optInt("pages", 1)));
    }
    /** Whether the user's words ask for 판서 (the screen look then waits for this tool instead of taking a picture itself). */
    public static boolean asked(String words) {
        String s = words == null ? "" : words.replaceAll("\\s", "");
        return s.contains("판서") || s.contains("칠판에") || s.contains("화이트보드");
    }
}
