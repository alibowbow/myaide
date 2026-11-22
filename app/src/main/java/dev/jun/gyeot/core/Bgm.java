package dev.jun.gyeot.core;

import static dev.jun.gyeot.core.Jsons.*;
import org.json.JSONObject;

/**
 * 배경음악 by voice (LyriaPlayer): new music Lyria makes live from a mood. Kept apart from playing real songs: a song, a
 * singer, a playlist or 마이튜브 is app_navigation's 마이튜브 play; only "배경음악", "BGM" or "음악 만들어 줘" with a mood is this.
 */
public final class Bgm {
    private Bgm() {}
    public static final String FUNCTION = "background_music";
    public static JSONObject declaration() {
        return obj("name", FUNCTION, "description", "배경음악 (BGM) the app makes live with Lyria from a mood, not real songs. Use only when the user asks for 배경음악, BGM, "
            + "or to make/compose music (\"배경음악 틀어 줘, 잔잔한 피아노로\", \"BGM 좀 더 신나게\", \"배경음악 꺼 줘\"). A song, a singer, a playlist, a genre to listen "
            + "to or anything with 마이튜브 is 마이튜브 instead (app_navigation). play starts it or changes its mood smoothly; pause, resume, stop.",
            "parameters", obj("type", "OBJECT", "properties", obj(
                "action", obj("type", "STRING", "enum", arr("play", "pause", "resume", "stop")),
                "mood", obj("type", "STRING", "description", "For play: the music in English, instruments, genre and feel, e.g. \"calm lofi hip hop, warm piano, rain\"; when changing, the whole new mood"),
                "bpm", obj("type", "INTEGER", "description", "Only when the user asks for a tempo (60-200)")), "required", arr("action")));
    }
}
