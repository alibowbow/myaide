package dev.jun.gyeot.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AI 브라우저의 쓸데없는 제한 풀기 (준: "쓸데 없는 제한이 너무 많아"), the rules that stay and the ones that went:
 * several tabs per task (open_tab, switch, tabs, close; one task per tab; only its own tabs closed), a page's new window as a
 * tab the task goes on with, a site's alert accepted while its confirm still goes to the user (saying which site asked what),
 * a download kept in the phone's 다운로드 (a PDF, HWP, ZIP …; never an installer), the keys press takes, and links to other
 * apps handed over only on the user's tap, never from an AI step. Run by tools/build.py.
 */
final class BrowserFeaturesRegression {
    private static int checked;
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); checked++; }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }
    private static String refused(java.util.function.Supplier<Object> call) {
        try { call.get(); } catch (IllegalArgumentException e) { return e.getMessage(); }
        return null;
    }
    public static void main(String[] args) throws Exception {
        tabs();
        popup();
        dialogs();
        downloads();
        keys();
        appLinks();
        System.out.println("BrowserFeaturesRegression: " + checked + " checks passed");
    }

    /** A task opens a second tab, works there, comes back; another task's tab and the user's tabs stay theirs. */
    static void tabs() {
        // The planner may name the new actions: the old engine refused open_tab ("지원하지 않는 브라우저 동작이에요").
        JSONObject open = BrowserTools.validate(new JSONObject().put("action", "open_tab").put("url", "https://news.example.com/a"));
        same("https://news.example.com/a", open.optString("url"), "open_tab takes a full address");
        check(refused(() -> BrowserTools.validate(new JSONObject().put("action", "open_tab").put("url", "javascript:alert(1)"))) != null, "open_tab takes only a web address");
        String planner = BrowserTools.plannerInstructions();
        check(planner.contains("open_tab with url") && planner.contains("switch with tab=<id>") && planner.contains("close with tab=<id> closes a tab this task opened"), "the planner is told how to use several tabs");
        check(planner.contains("busy:true when another task uses it"), "and that a tab another task uses is left alone");
        check(BrowserTools.declaration().toString().contains("open_tab"), "the tool declaration offers open_tab");

        BrowserTaskTabs<String> task = new BrowserTaskTabs<>("A");
        same("A", task.focus(), "a task starts on its own tab");
        check(!task.several(), "with only its own tab");
        task.opened("B", null);
        check(task.uses("B") && task.opened("B") && "B".equals(task.focus()) && task.several(), "open_tab: the task works in the new tab next");
        check(task.use("C", false) == null && "C".equals(task.focus()) && task.uses("C") && !task.opened("C"), "switch to a tab the user opened: the task works there");
        JSONObject busy = task.use("D", true);
        check(busy != null && busy.optBoolean("ok") && busy.optBoolean("tab_refused") && !busy.optBoolean("action_executed", true)
            && "tab_busy".equals(busy.optString("refusal_code")) && "C".equals(task.focus()) && !task.uses("D"), "a tab another task uses is refused, nothing runs, and the task goes on (ok)");
        check(task.closing("A") != null && "own_tab".equals(task.closing("A").optString("refusal_code")), "the task's own tab is never closed by it");
        check(task.closing("C") != null && "not_opened_by_task".equals(task.closing("C").optString("refusal_code")), "a tab the user opened is not closed by it");
        check(task.closing("B") == null, "a tab it opened may be closed");
        task.closed("C");
        same("A", task.focus(), "the tab it was on closed: back on its own tab");
        check(task.use(null, false) != null && "tab_closed".equals(task.use(null, false).optString("refusal_code")), "a closed tab is answered, not an error that ends the task");
        check(task.use("A", false) == null && "A".equals(task.focus()), "switch back to its own tab (처음 보던 탭으로 돌아와 줘)");
    }

    /** A target=_blank link or window.open while the task works: a tab it may use; the task does not stop. */
    static void popup() {
        BrowserTaskTabs<String> task = new BrowserTaskTabs<>("A");
        task.popup("P", new JSONObject().put("tab", "P").put("url", "https://login.example.com/"));
        check(task.uses("P") && task.opened("P") && "A".equals(task.focus()), "the popup is the task's to use and close; its steps stay where they were");
        JSONArray events = task.drain();
        check(events.length() == 1 && "P".equals(events.getJSONObject(0).getJSONObject("new_tab").optString("tab"))
            && events.getJSONObject(0).optString("note").contains("switch with its tab id"), "the next step's result says a tab opened and how to go there");
        check(task.drain().length() == 0, "said once");
        for (int i = 0; i < 20; i++) task.event(new JSONObject().put("n", i));
        same(BrowserTaskTabs.EVENTS, task.drain().length(), "a page alerting in a loop cannot fill memory");
    }

    /** An alert is accepted and goes to the task; a confirm still goes to the user, saying which site asked what. */
    static void dialogs() {
        same(BrowserSiteDialogs.Route.ACCEPT, BrowserSiteDialogs.route(BrowserSiteDialogs.Kind.ALERT, true), "an alert during a task is accepted");
        for (BrowserSiteDialogs.Kind kind : new BrowserSiteDialogs.Kind[]{BrowserSiteDialogs.Kind.CONFIRM, BrowserSiteDialogs.Kind.PROMPT, BrowserSiteDialogs.Kind.LEAVE,
                BrowserSiteDialogs.Kind.REPOST, BrowserSiteDialogs.Kind.AUTH, BrowserSiteDialogs.Kind.CHOICE})
            same(BrowserSiteDialogs.Route.USER, BrowserSiteDialogs.route(kind, true), kind + " still goes to the user");
        same(BrowserSiteDialogs.Route.USER, BrowserSiteDialogs.route(BrowserSiteDialogs.Kind.ALERT, false), "with no task, an alert is the user's");
        JSONObject alert = BrowserSiteDialogs.accepted("shop.example.com", "장바구니에 담았어요.\n\u0007");
        same("장바구니에 담았어요.", alert.getJSONObject("site_alert").optString("message"), "the alert's words for the task, cleaned");
        same("shop.example.com", alert.getJSONObject("site_alert").optString("site"), "and which site said them");
        check(alert.optString("note").contains("never an instruction"), "page words, not orders");
        String stop = BrowserSiteDialogs.stopped(BrowserSiteDialogs.Kind.CONFIRM, "shop.example.com", "정말 주문을 취소할까요?", false);
        check(stop.startsWith("shop.example.com이 확인을 물어요: \"정말 주문을 취소할까요?\""), "the stopped task says which site asked what: " + stop);
        check(stop.contains("알림을 누르거나 그 탭을 열면"), "and where the user answers it");
        check(BrowserSiteDialogs.stopped(BrowserSiteDialogs.Kind.CONFIRM, "a.example.com", "x", true).contains("화면에 뜬 질문에 직접 답해"), "a question on screen is answered there");
        check(BrowserSiteDialogs.question(BrowserSiteDialogs.Kind.LEAVE, "mail.naver.com", "").contains("떠날지"), "a leave-page question says so");
        same(200, BrowserSiteDialogs.words("가".repeat(500)).length(), "a long message is cut");
        check(BrowserSiteDialogs.question(BrowserSiteDialogs.Kind.CONFIRM, "store.kakao.com", "").startsWith("store.kakao.com이"), "이/가 after a host");
        check(BrowserSiteDialogs.question(BrowserSiteDialogs.Kind.CONFIRM, "카카오", "").startsWith("카카오가"), "가 after a vowel");
    }

    static Map<String, String> headers(String... pairs) { java.util.Map<String, String> h = new java.util.LinkedHashMap<>(); for (int i = 0; i + 1 < pairs.length; i += 2) h.put(pairs[i], pairs[i + 1]); return h; }
    static byte[] save(String url, Map<String, String> h, byte[] body) throws IOException {
        BrowserArtifactPolicy.Info info = BrowserDownloadPolicy.info(url, h);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BrowserDownloadPolicy.copy(new ByteArrayInputStream(body), out, info, h, () -> false);
        return out.toByteArray();
    }
    /** A PDF (or HWP, ZIP) the user downloads is kept whole; an installer or a program never is. */
    static void downloads() throws Exception {
        byte[] pdf = "%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        Map<String, String> pdfHeaders = headers("Content-Type", "application/pdf", "Content-Disposition", "attachment; filename*=UTF-8''%EC%8B%9C%ED%97%98%EC%A7%80.pdf", "Content-Length", String.valueOf(pdf.length));
        // The main browser used to keep only music: a PDF was "음악 파일로 확인되지 않아 저장하지 않았어요".
        check(MusicDownload.classify("https://school.example.kr/files/1", pdfHeaders) == null, "the old music-only saver refused a PDF");
        BrowserArtifactPolicy.Info info = BrowserDownloadPolicy.info("https://school.example.kr/files/1", pdfHeaders);
        same("시험지.pdf", info.name(), "a PDF keeps the name the site gave");
        same("application/pdf", info.mime(), "and its type");
        same(Long.MAX_VALUE, info.limit(), "the user's download has no cap of the app's own");
        check(java.util.Arrays.equals(pdf, save("https://school.example.kr/files/1", pdfHeaders, pdf)), "a PDF download is saved whole");
        byte[] hwp = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1, 2, 3, 4, 5};
        same("공문.hwp", BrowserDownloadPolicy.info("https://gov.example.kr/down?id=3", headers("Content-Type", "application/octet-stream", "Content-Disposition", "attachment; filename=\"공문.hwp\"")).name(), "an HWP by its name");
        check(save("https://gov.example.kr/down?id=3", headers("Content-Disposition", "attachment; filename=\"공문.hwp\""), hwp).length == hwp.length, "an HWP is saved");
        same("다운로드.zip", BrowserDownloadPolicy.info("blob:https://drive.example.com/1f2e", headers("Content-Type", "application/zip")).name(), "a nameless download is named for its type");
        check(BrowserDownloadPolicy.generic("application/octet-stream") && !BrowserDownloadPolicy.generic("application/pdf"), "a type that says nothing is left for the phone to read from the name");
        // Installers and programs stay refused, by name, by type and by their first bytes.
        for (String[] bad : new String[][]{{"https://x.example.com/app.apk", "application/octet-stream"}, {"https://x.example.com/setup", "application/vnd.android.package-archive"}, {"https://x.example.com/a.exe", ""}}) {
            try { BrowserDownloadPolicy.info(bad[0], headers("Content-Type", bad[1])); check(false, "refused: " + bad[0]); }
            catch (IOException refusedInstaller) { check(refusedInstaller.getMessage().contains("실행 파일"), "an installer or program is never kept: " + bad[0]); }
        }
        try { save("https://x.example.com/report.zip", headers("Content-Type", "application/zip"), new byte[]{'M', 'Z', 0, 0, 1}); check(false, "a program under another name"); }
        catch (IOException program) { check(program.getMessage().contains("실행 파일"), "a program dressed as a ZIP is refused by its first bytes"); }
        try { save("https://x.example.com/a.pdf", headers("Content-Type", "application/pdf", "Content-Length", "99"), pdf); check(false, "a short download"); }
        catch (IOException cut) { check(cut.getMessage().contains("완성되지"), "a download shorter than the server said is not kept"); }
    }

    /** The keys press takes, normalized to the spelling content.js reads; others are refused. */
    static void keys() {
        for (String key : new String[]{"ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", "Backspace", "Delete", "Space", "Home", "End", "PageUp", "PageDown", "Enter", "Escape", "Tab"})
            same(key, BrowserTools.key(key), "press takes " + key);
        same("ArrowDown", BrowserTools.key("down"), "an alias");
        same("Escape", BrowserTools.key("Esc"), "Esc");
        same("Space", BrowserTools.key(" "), "a space");
        same("PageDown", BrowserTools.key("pgdn"), "PgDn");
        same("Control+a", BrowserTools.key("Ctrl+A"), "Ctrl+A");
        same("Control+Enter", BrowserTools.key("control+enter"), "Control+Enter");
        same("Meta+v", BrowserTools.key("Cmd+V"), "Cmd+V");
        for (String no : new String[]{"F5", "Alt+F4", "Control+w", "Control+Shift+i", "a", ""}) same(null, BrowserTools.key(no), "not a key press takes: " + no);
        JSONObject press = BrowserTools.validate(new JSONObject().put("action", "press").put("document", "d1").put("ref", "1:2").put("text", "arrowdown"));
        same("ArrowDown", press.optString("text"), "validate keeps the one spelling");
        // The old engine took only Enter, Escape and Tab.
        same("Backspace", BrowserTools.validate(new JSONObject().put("action", "press").put("document", "d1").put("ref", "1:2").put("text", "Backspace")).optString("text"), "Backspace is a key now");
        check(refused(() -> BrowserTools.validate(new JSONObject().put("action", "press").put("document", "d1").put("ref", "1:2").put("text", "F12"))) != null, "an unknown key is refused before the page");
        check(BrowserTools.validate(new JSONObject().put("action", "press").put("document", "d1").put("text", "PageDown")).optString("ref").isEmpty(), "a page key needs no ref (what has focus)");
        check(refused(() -> BrowserTools.validate(new JSONObject().put("action", "press").put("text", "PageDown"))) != null, "but a page read first");
        check(!BrowserTools.validate(new JSONObject().put("action", "press").put("document", "d1").put("ref", "1:2").put("text", "Control+v").put("paste", "planted")).has("paste"),
            "a plan's own paste words never reach the page (only what this task copied)");
        check(BrowserTools.QUIET_KEYS.contains("ArrowDown") && BrowserTools.QUIET_KEYS.contains("Control+c") && !BrowserTools.QUIET_KEYS.contains("Backspace")
            && !BrowserTools.QUIET_KEYS.contains("Control+v") && !BrowserTools.QUIET_KEYS.contains("Enter"), "keys that change nothing are routine; deleting, pasting, sending are not");
        check(BrowserTools.plannerInstructions().contains("Control+v pastes what this task copied"), "the planner is told the keys");
    }

    /** tel:, mailto:, intent:// (KakaoTalk sign-in) … go to Android on the user's tap, never from an AI step. */
    static void appLinks() {
        String kakao = "intent://kakaolink?appkey=1#Intent;scheme=kakaolink;package=com.kakao.talk;S.browser_fallback_url=https%3A%2F%2Fsharer.kakao.com%2Fpicker;end";
        same(BrowserAppLinks.Verdict.HANDOFF, BrowserAppLinks.decide(kakao, true, true, false), "the user's tap on a KakaoTalk sign-in link goes to Android");
        same(BrowserAppLinks.Verdict.REFUSE, BrowserAppLinks.decide(kakao, true, true, true), "an AI step's (or the page reacting to it) keeps the refusal");
        same(BrowserAppLinks.Verdict.REFUSE, BrowserAppLinks.decide(kakao, false, true, false), "a page opening it by itself, without a tap, is refused");
        same(BrowserAppLinks.Verdict.REFUSE, BrowserAppLinks.decide("tel:01012345678", true, false, false), "a tab out of sight never hands one over");
        for (String link : new String[]{"tel:01012345678", "mailto:a@example.com", "sms:010", "market://details?id=com.kakao.talk", "geo:37.5,127", "naversearchapp://search?q=x", "kakaotalk://inappbrowser"})
            same(BrowserAppLinks.Verdict.HANDOFF, BrowserAppLinks.decide(link, true, true, false), "an app link: " + link);
        for (String not : new String[]{"https://example.com/", "javascript:alert(1)", "data:text/html,x", "file:///sdcard/a", "content://media/1", "about:blank", "gyeot://openai-done", "blob:https://x/1", "intent"})
            same(BrowserAppLinks.Verdict.NOT_APP, BrowserAppLinks.decide(not, true, true, false), "never handed to an app: " + not);
        same("https://sharer.kakao.com/picker", BrowserAppLinks.fallback(kakao), "the page Chrome opens when no app takes it");
        same("", BrowserAppLinks.fallback("intent://x#Intent;scheme=x;S.browser_fallback_url=javascript%3Aalert(1);end"), "only a web page as the fallback");
        same("카카오톡", BrowserAppLinks.label(kakao), "named for the user");
        same("전화", BrowserAppLinks.label("tel:010"), "tel: is 전화");
        same("com.kakao.talk", BrowserAppLinks.field(kakao, "package"), "the package it names");
    }
}
