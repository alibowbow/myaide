package dev.jun.gyeot.core;

import java.util.regex.Pattern;

/**
 * The browser in the main chat: when the user's words are about browser work, downloading or collecting files, or the
 * 들퍼레 folder, a reply is offered the browser (browser_tasks, run in tabs the user does not see) and the 들퍼레 folder
 * (folder_list, folder_make, folder_save) for itself, and its progress shows in the conversation. Pure Java: the words
 * and the instructions; MainChatEngine and MainBrowsing run them.
 */
public final class MainBrowser {
    private MainBrowser() {}
    /** Function rounds a reply that browses may take: a large collection is several rounds of downloads and of saves. */
    public static final int ROUNDS = 40;
    private static final Pattern WANTED = Pattern.compile("브라우저|사이트|홈페이지|웹\\s*페이지|다운로드|내려\\s*받|받아\\s*(?:줘|주|서|와|놔|둬)|수집|모아\\s*(?:줘|주|서|와|놔|둬)|백그라운드|뒤에서|들퍼레|폴더"
        + "|(?i:browser|download|background|folder)");
    /** Whether the user's words are about what the browser and the folder do; only then are they offered. */
    public static boolean wanted(String words) { return words != null && WANTED.matcher(words).find(); }

    public static final String INSTRUCTIONS = " Exception: this conversation also offers the app's own web browser and the user's 들퍼레 folder on this phone. "
        + "browser_tasks runs browser work in tabs the user does not see (opening sites, clicking, downloading files) and waits for it; its results come back to you. "
        + "Downloaded files are not read for you here: what you get is each file's name, size and artifact id. "
        + "Files you do not keep with folder_save in this reply are removed when it ends, so save every file the user wants kept before you answer. "
        + "Check with folder_list first and do not download what is already there. "
        + "When you answer, say plainly what was saved where and what could not be done and why; never claim a download or a save a result did not confirm.";
}
