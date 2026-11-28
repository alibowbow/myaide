package dev.jun.gyeot;

import dev.jun.gyeot.core.AutomationGate;
import org.json.*;

/** The trusted local browser classifier behind AutomationGate's tiers. Never executes;
 * the operation and its observed target decide, never a model or page risk flag. Every "words" argument here is the
 * user's own words (AutomationGate.userWords), never a voice envelope, a 작업창 model subtask or page text. */
final class BrowserDecisionEvidence {
    private BrowserDecisionEvidence() {}
    /** The local gate for one proposed browser step. userWords are the user's own words only, never page or tool text;
     * afterRead: a page was read in this task (a planner always reads before it acts). */
    static AutomationGate.Verdict verdict(JSONObject action, JSONObject page, String userWords, boolean afterRead) {
        AutomationGate.Tier tier = tier(action, page, userWords);
        boolean credential = tier == AutomationGate.Tier.FORBIDDEN && credentialInput(action, page);
        AutomationGate.Verdict verdict = AutomationGate.verdict(tier, kind(action, page), userWords, afterRead, credential);
        // A refused step says what it was and on which site (CLAUDE.md: what, where, which request), not only that it was refused.
        if (tier != AutomationGate.Tier.FORBIDDEN || credential) return verdict;
        String why = refusal(action, page);
        return why.isEmpty() ? verdict : new AutomationGate.Verdict(tier, verdict.kind(), false, verdict.code(), why);
    }
    static AutomationGate.Verdict verdict(JSONObject action, JSONObject page, String userWords) { return verdict(action, page, userWords, true); }
    static AutomationGate.Tier tier(JSONObject action, JSONObject page, String userWords) {
        if (observationAction(action)) return AutomationGate.Tier.OBSERVATION;
        if (forbidden(action, page)) return deletionAsked(action, page, userWords) ? AutomationGate.Tier.CONSEQUENTIAL : AutomationGate.Tier.FORBIDDEN;
        if (routineAction(action, page, userWords)) return AutomationGate.Tier.ROUTINE;
        return AutomationGate.Tier.CONSEQUENTIAL;
    }
    /** Payment, money transfer, deletion, credential entry and banking or securities sites. */
    static boolean forbidden(JSONObject action, JSONObject page) { return !refusal(action, page).isEmpty(); }
    /**
     * Why this step never runs, in the user's words: what it was and on which site ("" when it may run). Payment, money
     * transfer, deletion, credential entry and banking, card, securities, pay and crypto-exchange sites. A link or a card that
     * only shows a price is none of them (it opens the product page); the words of the control, the address it goes to or
     * the form it submits say whether it pays.
     */
    static String refusal(JSONObject action, JSONObject page) {
        if (action == null) return "";
        String kind = action.optString("action");
        if (kind.equals("open")) {
            String url = action.optString("url");
            if (AutomationGate.bankingHost(url)) return bankSite(url);
            return AutomationGate.forbiddenUrl(url) ? host(url) + "의 결제·주문·송금·삭제 주소라 자동으로 열지 않았어요. 필요하면 직접 열어 주세요." : "";
        }
        if (!java.util.Set.of("click", "type", "select", "press").contains(kind)) return "";
        if (page != null && AutomationGate.bankingHost(page.optString("url"))) return bankSite(page.optString("url"));
        JSONObject target = target(action, page);
        if (target == null) return "";
        String label = target.optString("label"), named = "'" + clip(label.isBlank() ? target.optString("placeholder", "이 항목") : label) + "'";
        if (AutomationGate.forbiddenUrl(target.optString("href")))
            return named + " 링크는 " + host(target.optString("href")) + "의 결제·주문·송금·삭제 주소로 가서 자동으로 누르지 않았어요. 필요하면 직접 눌러 주세요.";
        boolean submits = "click".equals(kind) || "press".equals(kind) && "Enter".equals(action.optString("text"));
        if (submits && AutomationGate.forbiddenUrl(target.optString("form_action")))
            return named + ": " + host(target.optString("form_action")) + "의 결제·주문·송금·삭제 양식을 보내는 동작이라 자동으로 하지 않았어요. 필요하면 직접 해 주세요.";
        if (credentialInput(action, page)) return AutomationGate.CREDENTIAL_MESSAGE;
        if (!("click".equals(kind) && (sectionNavigation(target) || priceLink(target))) && AutomationGate.forbiddenLabel(label))
            return DELETION.matcher(label).find()
                ? named + ": 지우거나 비우는 동작이라 자동으로 하지 않았어요. 사용자가 지우라고 한 항목만 하나씩 지우고, 계정·전체·휴지통 비우기는 직접 해야 해요."
                : named + ": 결제·구매·송금·구독처럼 돈이 드는 동작이라 자동으로 누르지 않았어요. 필요하면 직접 눌러 주세요.";
        return "";
    }
    private static final java.util.regex.Pattern DELETION = dev.jun.gyeot.core.Regex.unicode("(?i)삭제|지우|지워|비우|비워|제거|탈퇴|해지|초기화|포맷|휴지통"
        + "|(?<![A-Za-z0-9])(?:delete|remove|erase|clear|wipe|empty|reset|discard|uninstall|format)(?![A-Za-z0-9])");
    /** A link to a page (an observed public address, no checkout) whose label only shows a price: a product link opens its page. */
    private static boolean priceLink(JSONObject target) {
        String href = target.optString("href");
        return "a".equalsIgnoreCase(target.optString("tag")) && !href.isBlank() && !AutomationGate.forbiddenUrl(href)
            && dev.jun.gyeot.core.BrowserReadNavigation.safeHref(href) && AutomationGate.priceOnly(target.optString("label"));
    }
    private static String bankSite(String url) {
        return host(url) + ": 은행·증권·카드·결제·코인 거래소 사이트라 AI가 열거나 누르지 않아요. 그 사이트는 직접 써 주세요. 다른 사이트의 일은 계속 맡길 수 있어요.";
    }
    private static String host(String url) {
        try { String host = new java.net.URI(url == null ? "" : url.strip()).getHost(); return host == null || host.isBlank() ? "이 페이지" : host.toLowerCase(java.util.Locale.ROOT); }
        catch (Exception invalid) { return "이 페이지"; }
    }
    private static String clip(String text) {
        String plain = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return plain.length() <= 60 ? plain : plain.substring(0, 59) + "…";
    }
    /**
     * The stop after too many refused steps for one instruction (BrowserAgent): which step was refused, on which site, at what
     * time, for which request, why, and what the user can do themselves; nothing was run.
     */
    static String refusalStop(JSONObject action, JSONObject page, AutomationGate.Verdict verdict, int refusals, String request, long at) {
        String kind = action == null ? "" : action.optString("action"), url = page != null && !page.optString("url").isBlank() ? page.optString("url") : action == null ? "" : action.optString("url");
        JSONObject target = action == null ? null : target(action, page);
        String label = target == null ? "" : clip(target.optString("label").isBlank() ? target.optString("placeholder") : target.optString("label"));
        String what = switch (kind) {
            case "open" -> "'" + clip(action.optString("url")) + "' 열기";
            case "type" -> (label.isEmpty() ? "입력칸" : "'" + label + "' 칸") + "에 입력하기";
            case "select" -> (label.isEmpty() ? "목록" : "'" + label + "'") + "에서 고르기";
            case "press" -> (label.isEmpty() ? "" : "'" + label + "'에서 ") + action.optString("text") + " 키 누르기";
            default -> (label.isEmpty() ? "그 항목" : "'" + label + "'") + " 누르기";
        };
        String when = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(new java.util.Date(at));
        String asked = request == null ? "" : request.replaceAll("\\s+", " ").strip();
        if (asked.length() > 120) asked = asked.substring(0, 119) + "…";
        boolean readOnly = AutomationGate.NOT_REQUESTED.equals(verdict.code());
        return when + " " + host(url) + "에서 " + what + "를 실행하지 않았어요. 자동으로 하지 않는 동작이 이 지시에서 " + refusals + "번 필요해서 작업을 멈췄어요. "
            + "까닭: " + verdict.message() + (asked.isEmpty() ? "" : " 요청: '" + asked + "'.")
            + (readOnly ? " 그 동작까지 맡기려면 무엇을 누르거나 보낼지 같이 말해서 다시 지시해 주세요. 읽기·요약은 그대로 맡길 수 있어요."
                : " 필요하면 브라우저에서 이 탭을 열어 그 동작은 직접 해 주세요. 페이지 읽기·확인과 다른 일은 계속 맡길 수 있어요.");
    }
    /**
     * A click on a control that only deletes an item (AutomationGate.deletionOnly), on a site that is no bank, when the user's
     * own words ask to delete (AutomationGate.requestsDeletion): it runs as a consequential step, not a forbidden one.
     */
    static boolean deletionAsked(JSONObject action, JSONObject page, String userWords) {
        if (action == null || !"click".equals(action.optString("action")) || !AutomationGate.requestsDeletion(userWords)) return false;
        if (page != null && AutomationGate.bankingHost(page.optString("url"))) return false;
        JSONObject target = target(action, page);
        return target != null && !AutomationGate.forbiddenUrl(target.optString("href")) && AutomationGate.deletionOnly(target.optString("label"));
    }
    /** A tab, a selected or current navigation link (aria-selected, aria-current), or a read-navigation label, whose only
     * forbidden words are section names (선물하기, 충전, 대여, 소장, 초기화): clicking it switches sections or opens a record. On a
     * button or any other control the same words still forbid it. */
    private static boolean sectionNavigation(JSONObject target) {
        String label = target.optString("label");
        boolean link = "a".equalsIgnoreCase(target.optString("tag"));
        return ("tab".equals(target.optString("role")) || link && (target.optBoolean("selected") || target.optBoolean("current"))) && AutomationGate.sectionName(label)
            || AutomationGate.sectionReadLabel(label);
    }
    private static boolean credentialInput(JSONObject action, JSONObject page) {
        JSONObject target = target(action, page);
        if (target == null || !java.util.Set.of("type", "select").contains(action.optString("action"))) return false;
        return "password".equalsIgnoreCase(target.optString("type")) || dev.jun.gyeot.core.DecisionSemanticText.sensitiveInput(
            target.optString("type") + " " + target.optString("label") + " " + target.optString("placeholder") + " " + target.optString("autocomplete"), action.optString("text"));
    }
    static AutomationGate.Kind kind(JSONObject action, JSONObject page) {
        JSONObject target = action == null ? null : target(action, page);
        if (target == null) return AutomationGate.Kind.OTHER;
        // Enter in a chat or reply composer sends, in a comment box posts; anywhere else it is unsure.
        if ("press".equals(action.optString("action")) && "Enter".equals(action.optString("text"))) {
            String field = fieldKind(target);
            return "chat".equals(field) ? AutomationGate.Kind.SEND : "comment".equals(field) ? AutomationGate.Kind.POST : AutomationGate.Kind.OTHER;
        }
        if (!"click".equals(action.optString("action"))) return AutomationGate.Kind.OTHER;
        return AutomationGate.kind(target.optString("label"));
    }
    /** A field's own description: its label for an input or textarea, its placeholder, type and role. A rich-text editor's
     * label can be its typed text, so only its placeholder counts. */
    private static String description(JSONObject target) {
        boolean native_ = java.util.Set.of("input", "textarea", "select").contains(target.optString("tag").toLowerCase(java.util.Locale.ROOT))
            || java.util.Set.of("searchbox", "combobox").contains(target.optString("role"));
        return ((native_ ? target.optString("label") : "") + " " + target.optString("placeholder") + " " + target.optString("type") + " " + target.optString("role")).strip();
    }
    /** chat, comment, search or other, from the field itself. Only a real search box is a search box: an input of type search,
     * an input with role searchbox or inside a search landmark (role=search, a search element, as the bridge reports it), or
     * else a single-line text input labelled as a search, where a comment or message word in the label wins. A text area or
     * a rich-text editor is never a search box, whatever it is labelled. */
    static String fieldKind(JSONObject target) {
        String described = description(target).toLowerCase(java.util.Locale.ROOT);
        String tag = target.optString("tag").toLowerCase(java.util.Locale.ROOT), type = target.optString("type").toLowerCase(java.util.Locale.ROOT);
        boolean input = "input".equals(tag), text = input && java.util.Set.of("", "text").contains(type);
        if (input && "search".equals(type) || text && ("searchbox".equals(target.optString("role")) || target.optBoolean("in_search"))) return "search";
        if (described.matches("(?s).*(댓글|답글|덧글|코멘트|comment).*")) return "comment";
        if (described.matches("(?s).*(메시지|메세지|채팅|대화|답장|message|chat|reply|compose|\\bdm\\b).*")) return "chat";
        return text && described.matches("(?s).*(search|검색).*") ? "search" : "other";
    }
    private static JSONObject target(JSONObject action, JSONObject page) {
        JSONArray elements = page == null ? null : page.optJSONArray("elements");
        if (elements == null || action == null || action.optString("ref").isEmpty()) return null;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject item = elements.optJSONObject(i);
            if (item != null && action.optString("ref").equals(item.optString("ref"))) return item;
        }
        return null;
    }
    /** Classification is derived from actual operation/observed target, never a model risk flag. */
    static boolean observationAction(JSONObject action) {
        return action != null && java.util.Set.of("read","tabs").contains(action.optString("action"));
    }
    static boolean routineAction(JSONObject action, JSONObject page) { return routineAction(action,page,""); }
    /** words: the user's own words (M4), so a voice envelope's handoff text or a 작업창 subtask cannot change a tier. */
    static boolean routineAction(JSONObject action, JSONObject page, String words) {
        if(action==null)return false;
        String kind=action.optString("action");
        if(observationAction(action))return true;
        if(java.util.Set.of("scroll","back","switch","set_mode").contains(kind))return true;
        if(kind.equals("open"))return (!dangerous(action.optString("url")) || readLink(action,page,action.optString("url"))) && dev.jun.gyeot.core.BrowserReadNavigation.safeHref(action.optString("url"));
        if(page==null || page.optBoolean("requires_user") || !page.optString("document").equals(action.optString("document")))return false;
        JSONObject target=null;JSONArray elements=page.optJSONArray("elements");
        if(elements!=null)for(int n=0;n<elements.length();n++){JSONObject item=elements.optJSONObject(n);if(item!=null&&action.optString("ref").equals(item.optString("ref"))){target=item;break;}}
        if(target==null || target.optBoolean("disabled") || target.optBoolean("readonly"))return false;
        String label=target.optString("label"), type=target.optString("type"), role=target.optString("role"), text=action.optString("text");
        // L1: a link to a read page named for one ("결제 내역", "Order history") opens like any public page.
        if(kind.equals("click") && "a".equalsIgnoreCase(target.optString("tag")) && AutomationGate.readNavigationLabel(label)
                && !AutomationGate.forbiddenUrl(target.optString("href")) && dev.jun.gyeot.core.BrowserReadNavigation.safeHref(target.optString("href")))return true;
        if(dangerous(label+" "+type+" "+target.optString("autocomplete")))return false;
        boolean editable=target.optBoolean("editable") || java.util.Set.of("textbox","searchbox","combobox").contains(role)
            || java.util.Set.of("input","textarea","select").contains(target.optString("tag"));
        if(kind.equals("type")||kind.equals("select"))return editable && text.length()<=dev.jun.gyeot.core.BrowserTools.TYPE_LIMIT
            && !dev.jun.gyeot.core.DecisionSemanticText.sensitiveInput(label+" "+type+" "+target.optString("placeholder")+" "+target.optString("autocomplete"),text)
            && text.equals(dev.jun.gyeot.core.DecisionSemanticText.redact(text));
        if(kind.equals("press")) {
            if(java.util.Set.of("Tab","Escape").contains(text))return true;
            // Enter runs a search in a search box, or a generation site's prompt the user asked to generate from; in a chat,
            // comment or other field it may send, post or submit.
            return text.equals("Enter") && editable && ("search".equals(fieldKind(target)) || generationComposer(page,words,elements) || createPrompt(page,target,words,elements));
        }
        if(!kind.equals("click"))return false;
        if("a".equalsIgnoreCase(target.optString("tag"))) {
            String href=target.optString("href");
            if(!href.isBlank())return !dangerous(href) && dev.jun.gyeot.core.BrowserReadNavigation.safeHref(href);
            // Sites often render menu/tab controls as javascript:/fragment anchors; normal DOM guards still apply.
            if(!java.util.Set.of("scripted","fragment").contains(target.optString("navigation_kind")))return false;
        }
        if("tab".equals(role))return true;
        // A control named for a file ("국어영역_문제지.pdf"), or a download control when the user asked for files, fetches a
        // file: a read of the page's own documents, as a link to one is.
        if(fileControl(label) || downloadControl(label) && asksFiles(words))return true;
        // The whole command names exactly this control as a destination ("공지 탭으로 들어가죠").
        if(dev.jun.gyeot.core.BrowserNavigationCompletion.onlyOpen(words,label))return true;
        String lower=label.strip().toLowerCase(java.util.Locale.ROOT);
        if(MENU_LABEL.matcher(lower).matches())return true;
        if(lower.equals("search")||lower.equals("검색"))return hasSearch(elements);
        // A generation site's own submit (a composer's send, a music or image generator's Create/Generate): the user's own
        // generation request is its authority, and nothing else's.
        return generationSubmit(page,label,words,elements);
    }
    private static boolean generationSubmit(JSONObject page,String label,String words,JSONArray elements) {
        if(words==null || dangerous(words) || !AutomationGate.generationSubmit(page.optString("url"),label,words))return false;
        return "chat".equals(AutomationGate.generationSite(page.optString("url"))) ? generationComposer(page,words,elements) : !invitation(page,elements);
    }
    /** Enter in a music or image generator's prompt field, when the user asked to generate. */
    private static boolean createPrompt(JSONObject page,JSONObject target,String words,JSONArray elements) {
        if(!"create".equals(AutomationGate.generationSite(page.optString("url"))) || words==null || dangerous(words) || !AutomationGate.requestsGeneration(words) || invitation(page,elements))return false;
        return description(target).toLowerCase(java.util.Locale.ROOT).matches("(?s).*(prompt|프롬프트|describe|설명|imagine|아이디어|idea).*");
    }
    /** An invitation or sharing surface, or a recipient field: never a generation submit. */
    private static boolean invitation(JSONObject page,JSONArray elements) {
        // Body text may be generated documentation or an ordinary Share conversation toolbar.
        // Only navigation/dialog context and actual recipient controls classify an invitation.
        String surface=(page.optString("url")+" "+page.optString("title")).toLowerCase(java.util.Locale.ROOT);
        if(elements!=null)for(int i=0;i<elements.length();i++){
            JSONObject element=elements.optJSONObject(i);
            if(element!=null && ("dialog".equals(element.optString("role")) || "dialog".equals(element.optString("tag"))))
                surface += " " + element.optString("label").toLowerCase(java.util.Locale.ROOT);
        }
        if(surface.matches("(?s).*(/invite|/share|invitation|invite (?:people|members|collaborators)|share (?:chat|conversation|link)|add (?:member|collaborator)|초대(?:하기|할| 보내| 링크)|대화 공유|링크 공유|멤버 추가|공동작업자).*") )return true;
        if(elements!=null)for(int i=0;i<elements.length();i++){
            JSONObject e=elements.optJSONObject(i);if(e==null)continue;
            boolean editable=e.optBoolean("editable")||"textbox".equals(e.optString("role"))||"textarea".equals(e.optString("tag"));
            String label=(e.optString("label")+" "+e.optString("placeholder")+" "+e.optString("type")).toLowerCase(java.util.Locale.ROOT);
            if(editable && label.matches("(?s).*(invite|invitation|collaborator|member|contact|email|recipient|초대|공유|받는.?사람|수신자|이메일|연락처|멤버).*") )return true;
        }
        return false;
    }
    /** An observed link to a read page named for one ("결제 내역"), opened natively from its ref (L1). */
    private static boolean readLink(JSONObject action, JSONObject page, String url) {
        JSONObject target = target(action, page);
        return target != null && "a".equalsIgnoreCase(target.optString("tag")) && url.equals(target.optString("href"))
            && AutomationGate.readNavigationLabel(target.optString("label")) && !AutomationGate.forbiddenUrl(url);
    }
    private static final java.util.regex.Pattern MENU_LABEL = java.util.regex.Pattern.compile("(?:canvas|캔버스|tools?|도구|menu|메뉴|more|더보기|더\\s*보기|close|닫기|뒤로|back|forward|다음|advanced|고급|개념\\s*글|최신순|전체|공지|베스트|인기\\s*글?|추천\\s*글?|최신\\s*글?|펼치기|접기|이전\\s*페이지|다음\\s*페이지|show more|load more|see more)");
    /**
     * A routine step that changes nothing when repeated, so the engine keeps no fence for it: a click on a link, a tab, a
     * menu or a file control, or Tab/Escape. A generation submit, Enter and typing stay fenced even when routine.
     */
    static boolean unfenced(JSONObject action, JSONObject page, String words) {
        if (action == null || page == null || !routineAction(action, page, words)) return false;
        String kind = action.optString("action");
        if (kind.equals("press")) return java.util.Set.of("Tab", "Escape").contains(action.optString("text"));
        if (!kind.equals("click")) return false;
        JSONArray elements = page.optJSONArray("elements"); JSONObject target = null;
        for (int i = 0; elements != null && i < elements.length() && target == null; i++) {
            JSONObject item = elements.optJSONObject(i);
            if (item != null && action.optString("ref").equals(item.optString("ref"))) target = item;
        }
        if (target == null) return false;
        String label = target.optString("label");
        if (generationSubmit(page, label, words, elements)) return false;
        return "a".equalsIgnoreCase(target.optString("tag")) || "tab".equals(target.optString("role")) || fileControl(label)
            || downloadControl(label) && asksFiles(words) || MENU_LABEL.matcher(label.strip().toLowerCase(java.util.Locale.ROOT)).matches();
    }
    private static final java.util.regex.Pattern FILE_NAME = java.util.regex.Pattern.compile("(?i)[^\\s/\\\\]+\\.(?:pdf|hwp|hwpx|zip|docx?|xlsx?|pptx?|mp3|m4a|wav|mp4)(?![a-z0-9])");
    /** A control whose name is a file's name: a document, sound, video or archive. */
    static boolean fileControl(String label) {
        String plain = label == null ? "" : label.strip();
        return !plain.isEmpty() && plain.length() <= 160 && FILE_NAME.matcher(plain).find();
    }
    /** A control that only says it downloads ("다운로드", "파일 받기", "Download"). */
    static boolean downloadControl(String label) {
        String plain = label == null ? "" : label.strip().toLowerCase(java.util.Locale.ROOT);
        return plain.length() <= 40 && plain.matches("(?:(?:첨부\\s*)?파일\\s*)?(?:다운로드|내려\\s*받기|받기)(?:\\s*하기)?|download(?:\\s+(?:file|pdf))?");
    }
    /** Whether the user's own words ask for files to be downloaded, kept or collected. */
    static boolean asksFiles(String words) {
        return words != null && words.matches("(?is).*(다운로드|내려\\s*받|받아|받기|저장|수집|모아|모으|download|save).*");
    }
    private static boolean dangerous(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(password|passwd|otp|verification|login|sign.in|sign.out|log.out|api.?key|token|secret|credential|purchase|checkout|payment|delete|remove|publish|share|invite|invitation|collaborator|upload|transfer|withdraw|unsubscribe|terminal|console|비밀번호|비번|인증|로그인|로그아웃|결제|구매|삭제|게시(?!판|글|물)|공유|초대|공동작업|송금|탈퇴|구독취소).*");
    }
    private static boolean hasSearch(JSONArray elements) {
        if(elements!=null)for(int i=0;i<elements.length();i++){JSONObject e=elements.optJSONObject(i);if(e!=null&&"search".equals(fieldKind(e)))return true;}
        return false;
    }
    /** An AI chat composer on the page, on an AI chat site, with the user's own generation request ("앱 만들어 줘", "질문해 줘"). */
    private static boolean generationComposer(JSONObject page,String goal,JSONArray elements) {
        if(goal==null || dangerous(goal) || !AutomationGate.requestsGeneration(goal))return false;
        if(!"chat".equals(AutomationGate.generationSite(page.optString("url"))) || invitation(page,elements))return false;
        boolean composer=false;
        if(elements!=null)for(int i=0;i<elements.length();i++){
            JSONObject e=elements.optJSONObject(i);if(e==null)continue;
            boolean editable=e.optBoolean("editable")||"textbox".equals(e.optString("role"))||"textarea".equals(e.optString("tag"));
            String label=(e.optString("label")+" "+e.optString("placeholder")+" "+e.optString("type")).toLowerCase(java.util.Locale.ROOT);
            if(editable && !dangerous(label) && label.matches("(?s).*(prompt|프롬프트|message (?:gemini|chatgpt|claude|grok)|ask (?:anything|gemini|chatgpt|claude|grok)|gemini에게 물어|무엇이든 물어|클로드에게|그록에게).*") )composer=true;
        }
        return composer;
    }
}
