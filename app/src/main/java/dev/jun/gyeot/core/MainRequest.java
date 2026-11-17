package dev.jun.gyeot.core;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One main-chat request, the same for every engine: a reply, a summary or a memory pass. The instructions and the input go
 * exactly as the chat built them, and the answer's text goes to onText as it streams in. The optional parts stay off
 * unless a caller turns them on: the provider's readable summary of its thinking, its hosted web search and page reading,
 * the photos and PDFs attached to the question, and a usage report. MainAdapters turns the request into each provider's own request with that client's
 * builder. An option an engine cannot carry is refused before anything is sent (check), or a caller that would rather
 * answer without it leaves it out on purpose first (forEngine); a wrong field is never sent. A cache key, when set, lets
 * the provider keep the request's fixed start in its prompt cache (cache()). Immutable: each option returns a copy.
 * always forces the search where the provider can force it (ChatGPT); elsewhere a request with
 * it is the same as without.
 */
public record MainRequest(String instructions, String input, Consumer<String> onText, Consumer<String> onThinking,
        boolean search, boolean fetch, List<Attachment> files, Consumer<Usage> onUsage, String cacheKey, boolean always) {
    /** The optional parts, all off by default; the label names one in a refusal. */
    public enum Option {
        THINKING_SUMMARY("생각 요약"), SEARCH("웹 검색"), FETCH("링크 읽기"), FILES("사진·PDF 첨부"), USAGE("사용량 집계");
        public final String label;
        Option(String label) { this.label = label; }
    }
    /**
     * A photo or a PDF attached to the question: its name, its type and its bytes in base64. It goes with the question as the
     * provider's own file part (each client's 작업창 converter); a text file goes in the input as text instead.
     */
    public record Attachment(String name, String mime, String data) {
        public Attachment {
            if (mime == null || !(mime.matches("image/[a-z0-9.+-]{1,40}") || mime.equals("application/pdf")) || data == null || data.isEmpty()) throw new IllegalArgumentException("사진이나 PDF만 첨부할 수 있어요.");
            name = name == null || name.isBlank() ? "첨부" : name;
        }
        public boolean pdf() { return mime.equals("application/pdf"); }
        /** As 작업창's turns carry a file ({name, mime, kind, data}), for the clients' converters. */
        public org.json.JSONObject json() { return Jsons.obj("name", name, "mime", mime, "kind", pdf() ? WorkBook.PDF : WorkBook.IMAGE, "data", data); }
        @Override public String toString() { return "Attachment[" + mime + ", " + data.length() + " chars]"; }
    }
    /** No partial text: the caller takes only the finished answer. */
    public static final Consumer<String> QUIET = text -> {};
    /** The longest thinking summary a stream keeps from one answer; the rest is not shown. */
    public static final int THINKING = 64_000;
    /**
     * The provider refused the request only for asking for a thinking summary (an OpenAI organization not verified for
     * reasoning summaries, a model that takes no display setting). It did no work, so MainAdapters remembers that for the
     * account and model and sends the request once more without the summary.
     */
    public static final class SummaryRefused extends IllegalStateException {
        public SummaryRefused() { super("생각 요약을 받지 않는 연결이에요."); }
    }
    /**
     * The provider refused the request's hosted web tools before doing any work (Gemini's 400 for google_search or
     * url_context). MainAdapters sends it once more with one tool fewer (page reading first) and, once an answer comes,
     * remembers the tool it left out for the account and model for a week.
     */
    public static final class ToolsRefused extends IllegalStateException {
        /** The tool the refusal names, SEARCH or FETCH; null when it names no tool of its own. */
        public final Option tool;
        public ToolsRefused() { this(null); }
        public ToolsRefused(Option tool) { super("웹 검색 도구를 받지 않는 연결이에요."); this.tool = tool; }
    }

    public MainRequest {
        if (instructions == null || input == null) throw new IllegalArgumentException("메인 요청에 지침과 입력이 있어야 해요.");
        if (onText == null) onText = QUIET;
        files = files == null ? List.of() : List.copyOf(files);
        if (cacheKey != null && cacheKey.isBlank()) cacheKey = null;
    }
    /** The text alone, as the main chat always sent it: no partial text and no option. */
    public static MainRequest of(String instructions, String input) { return new MainRequest(instructions, input, QUIET, null, false, false, List.of(), null, null, false); }
    /** A copy whose answer goes to onText piece by piece as it streams in; null takes none. */
    public MainRequest onText(Consumer<String> onText) { return new MainRequest(instructions, input, onText, onThinking, search, fetch, files, onUsage, cacheKey, always); }
    /**
     * A copy asking for the provider's readable summary of its thinking, for onThinking; null asks for none. Only a request
     * that already thinks carries it (a GPT model sent a reasoning level, a Claude model with adaptive thinking, Gemini's
     * thinking setting); with no thinking there is nothing to summarize and no field is sent.
     */
    public MainRequest thinkingSummary(Consumer<String> onThinking) { return new MainRequest(instructions, input, onText, onThinking, search, fetch, files, onUsage, cacheKey, always); }
    /** A copy with the provider's own hosted web search and page reading, as asked. */
    public MainRequest tools(boolean search, boolean fetch) { return new MainRequest(instructions, input, onText, onThinking, search, fetch, files, onUsage, cacheKey, always); }
    /** A copy whose web search is forced (검색: 항상) where the provider can force it; it rides on search, so alone it adds nothing. */
    public MainRequest searchAlways(boolean always) { return new MainRequest(instructions, input, onText, onThinking, search, fetch, files, onUsage, cacheKey, always); }
    /** A copy with these photos and PDFs attached to the question; null or none attaches nothing. */
    public MainRequest files(List<Attachment> files) { return new MainRequest(instructions, input, onText, onThinking, search, fetch, files, onUsage, cacheKey, always); }
    /** The attached files as 작업창's turns carry them, for the clients; null when there are none, so a request without them is sent as before. */
    public org.json.JSONArray parts() {
        if (files.isEmpty()) return null;
        org.json.JSONArray parts = new org.json.JSONArray();
        for (Attachment file : files) parts.put(file.json());
        return parts;
    }
    /**
     * A copy that reports what the request used to onUsage, once, when its answer has come (the provider responses it took
     * summed, core/Usage); null reports nothing. A count the provider did not give is null there.
     */
    public MainRequest onUsage(Consumer<Usage> onUsage) { return new MainRequest(instructions, input, onText, onThinking, search, fetch, files, onUsage, cacheKey, always); }
    /**
     * A copy whose fixed start the provider may keep in its prompt cache, under key (a hash, never content): Claude marks the
     * end of the instructions for its cache, ChatGPT sends the key as prompt_cache_key, and the other engines cache on
     * their own. Not an option: every engine takes it or has nothing to send. Null or blank asks for nothing.
     */
    public MainRequest cache(String key) { return new MainRequest(instructions, input, onText, onThinking, search, fetch, files, onUsage, key, always); }

    public boolean streams() { return onText != QUIET; }
    public boolean thinkingSummary() { return onThinking != null; }
    public boolean countsUsage() { return onUsage != null; }
    /** The options this request asks for. */
    public Set<Option> options() {
        Set<Option> asked = EnumSet.noneOf(Option.class);
        if (thinkingSummary()) asked.add(Option.THINKING_SUMMARY);
        if (search) asked.add(Option.SEARCH);
        if (fetch) asked.add(Option.FETCH);
        if (!files.isEmpty()) asked.add(Option.FILES);
        if (countsUsage()) asked.add(Option.USAGE);
        return asked;
    }
    /**
     * What each engine's adapter can carry, after the field each provider documents:
     * - a thinking summary on ChatGPT (reasoning.summary), Claude (thinking.display) and Gemini (thinkingConfig.includeThoughts);
     *   not on Grok (xAI documents only encrypted reasoning, no readable summary) or NVIDIA NIM (no request field);
     * - hosted web search on all but NIM;
     * - page reading as a tool of its own on Claude (web_fetch) and Gemini (url_context, with Google Search or alone, so a
     *   pasted link is read with search off); the web search of ChatGPT and Grok opens pages itself and has no separate
     *   tool to ask for;
     * - a usage report on all five (only NIM's stream needs asking: stream_options.include_usage);
     * - attached photos on all five and PDFs on all but NIM (whose chat API takes images only: check refuses a PDF there).
     */
    public static Set<Option> supported(String engine) {
        return switch (WorkBook.engine(engine)) {
            case WorkBook.CLAUDE, WorkBook.GEMINI -> EnumSet.of(Option.THINKING_SUMMARY, Option.SEARCH, Option.FETCH, Option.FILES, Option.USAGE);
            case WorkBook.GROK -> EnumSet.of(Option.SEARCH, Option.FILES, Option.USAGE);
            case WorkBook.NIM -> EnumSet.of(Option.FILES, Option.USAGE);
            default -> EnumSet.of(Option.THINKING_SUMMARY, Option.SEARCH, Option.FILES, Option.USAGE);
        };
    }
    /** Why the attached files cannot go to engine (MainAttachments.refusedBy: NIM takes photos only), or "" when they can. */
    public String filesRefused(String engine) { return MainAttachments.refusedBy(engine, parts()); }
    /** The options asked for that this engine cannot carry. */
    public Set<Option> unsupported(String engine) {
        Set<Option> left = options(); left.removeAll(supported(engine));
        return left;
    }
    /** This request, when the engine can carry everything it asks for; else refused before anything is sent. */
    public MainRequest check(String engine) {
        Set<Option> left = unsupported(engine);
        String files = filesRefused(engine);
        if (!files.isEmpty()) throw new IllegalArgumentException(files);
        if (left.isEmpty()) return this;
        throw new IllegalArgumentException("메인 대화의 " + WorkBook.author(WorkBook.engine(engine)) + " 연결에서는 쓸 수 없어요: " + labels(left) + ".");
    }
    /** The options' labels for a refusal, in order: "생각 요약, 웹 검색". */
    public static String labels(Set<Option> options) {
        StringBuilder names = new StringBuilder();
        for (Option option : options) names.append(names.length() == 0 ? "" : ", ").append(option.label);
        return names.toString();
    }
    /** A copy without the options this engine cannot carry, for a caller that answers without them rather than not at all. */
    public MainRequest forEngine(String engine) {
        Set<Option> left = unsupported(engine);
        if (left.isEmpty()) return this;
        return new MainRequest(instructions, input, onText, left.contains(Option.THINKING_SUMMARY) ? null : onThinking, search && !left.contains(Option.SEARCH),
            fetch && !left.contains(Option.FETCH), left.contains(Option.FILES) ? List.of() : files, left.contains(Option.USAGE) ? null : onUsage, cacheKey, always);
    }
    /** Sizes and options only: the conversation never goes to a log or an error. */
    @Override public String toString() { return "MainRequest[" + instructions.length() + "+" + input.length() + " chars, " + options() + (always && search ? ", always" : "") + (cacheKey == null ? "" : ", cached") + "]"; }
}
