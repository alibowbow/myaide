package dev.jun.gyeot.core;

import java.util.regex.Pattern;
import org.json.JSONArray;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Background work from the main chat: background_task hands a request to a new 작업창 job, which runs on its own with a
 * real web browser and the 들퍼레 folder, and whose result comes back into the conversation when it ends. Offered only when
 * the user's words are about browser work, downloading or collecting files, or work in the background, and started only
 * when they ask for it. Pure Java: the words, the declaration and the instructions; MainChatEngine starts the job.
 */
public final class BackgroundWork {
    private BackgroundWork() {}
    public static final String START = "background_task";
    /** Jobs one reply may start. */
    public static final int PER_REPLY = 2;
    private static final Pattern WANTED = Pattern.compile("브라우저|사이트|홈페이지|웹\\s*페이지|다운로드|내려\\s*받|받아\\s*(?:줘|주|서|와|놔|둬)|수집|모아\\s*(?:줘|주|서|와|놔|둬)|백그라운드|뒤에서|작업창|작업으로|들퍼레\\s*폴더"
        + "|(?i:browser|download|background)");
    private static final Pattern FORBID = Pattern.compile("(?:작업창|백그라운드|브라우저)[^.!?\\n]{0,12}(?:맡기|보내|쓰|돌리)(?:지)?\\s*(?:마|말|않)");
    /** Whether the user's words are about work the background job does; only then is the function offered. */
    public static boolean wanted(String words) { return words != null && WANTED.matcher(words).find(); }
    /** Whether the user's own words for this reply allow starting one. */
    public static boolean asked(String words) { return wanted(words) && !FORBID.matcher(words).find(); }

    public static final String INSTRUCTIONS = " Exception: this conversation also offers background_task, which starts a job in 작업창 that runs on its own "
        + "with a real web browser (opening sites, clicking, downloading files) and can save downloaded files into the user's 들퍼레 folder. "
        + "Use it when the user asks for browser work, for downloading or collecting files, or for work to run in the background. "
        + "Write the job's request so it stands on its own: everything the user asked, in their words and language, with any details from this conversation it needs. "
        + "Then tell the user in one short line that it is running in 작업창 and that its result will come back here. "
        + "Never claim the job is done or describe its results before they come back.";
    public static final String NOT_ASKED = "Nothing was started: the user's own words did not ask for browser or background work. Answer here, and tell the user they can ask, "
        + "for example \"백그라운드로 브라우저에서 찾아서 받아 줘\".";

    public static JSONArray declarations() {
        return arr(obj("name", START, "description", "Start a background job in 작업창 with a real web browser and the user's 들퍼레 folder: browser work, downloading or collecting "
                + "files, or long work the user wants run in the background. Returns at once; the job's result comes back into this conversation when it ends.",
            "parameters", obj("type", "object", "required", arr("request"), "properties", obj(
                "request", obj("type", "string", "description", "The complete request for the job, standing on its own, in the user's language")), "additionalProperties", false)));
    }
}
