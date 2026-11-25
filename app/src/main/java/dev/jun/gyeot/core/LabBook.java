package dev.jun.gyeot.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 연구실: research projects run by AI researchers, each with a role (책임연구원, 문헌조사원, 이론가, 실험가, 비평가, 기록원)
 * and its own model. Kept on this phone in one folder: projects.json, and per project notes.jsonl (the research notes, one
 * entry a line, append only), state.json (진행판: the stages, what waits for approval, the budget spent), ledger.json (검증
 * 장부: the claims and their status), sources.json (참고문헌, S1, S2 …) and files/ (보고서 and experiment figures).
 * Nothing here talks to the network: LabRunner asks the AIs with the prompts below and writes what they say here.
 */
public final class LabBook {
    // ── the researchers ──────────────────────────────────────────────

    /** A research role: what it does on screen (about) and what it is told (duty). */
    public record Role(String id, String name, String about, String duty, String mark) {}
    public static final Role PI = new Role("pi", "책임연구원", "계획을 세우고, 다음 단계를 정하고, 결과를 종합해요",
        "연구 전체를 이끈다. 질문을 작게 나누고, 가설과 방법을 정하고, 무엇을 먼저 확인할지 결정하고, 결과를 종합한다.", "책");
    public static final Role SCOUT = new Role("scout", "문헌조사원", "논문과 자료를 찾아 출처와 함께 정리해요",
        "이미 알려진 것을 찾는다. 논문의 주장과 한계를 출처 번호와 함께 정확히 옮기고, 아직 밝혀지지 않은 빈틈을 짚는다.", "문");
    public static final Role THEORIST = new Role("theorist", "이론가", "가설과 증명 아이디어, 모형을 내요",
        "가설과 이론적 설명, 증명의 뼈대, 수학적 모형을 낸다. 가정을 분명히 적고, 그 가설이 틀렸다면 무엇이 관찰될지 함께 말한다.", "이");
    public static final Role EXPERIMENTER = new Role("experimenter", "실험가", "Python으로 계산하고 시뮬레이션해서 확인해요",
        "주장을 계산·시뮬레이션·데이터로 확인할 방법을 설계하고, 앱 안 리눅스에서 돌릴 Python 코드를 쓰고, 결과를 해석한다.", "실");
    public static final Role CRITIC = new Role("critic", "비평가", "근거를 따지고 반례와 오류를 찾아요",
        "모든 주장의 근거를 따진다. 논리 비약, 반례, 빠진 대조군, 과장된 결론을 찾아 구체적으로 지적한다. 동의할 때도 남는 위험을 말한다.", "비");
    public static final Role SCRIBE = new Role("scribe", "기록원", "토론과 결과를 요약하고 보고서를 써요",
        "흩어진 기록을 정확하게 요약하고, 검증된 것과 아닌 것을 구분해 보고서로 묶는다. 없는 내용을 보태지 않는다.", "기");
    public static final List<Role> ROLES = List.of(PI, SCOUT, THEORIST, EXPERIMENTER, CRITIC, SCRIBE);
    public static Role role(String id) { for (Role r : ROLES) if (r.id().equals(id)) return r; return PI; }
    public static boolean known(String id) { for (Role r : ROLES) if (r.id().equals(id)) return true; return false; }

    // ── the fields ───────────────────────────────────────────────────

    /** A field of research: how its researchers work, and where its papers are searched first. */
    public record Field(String id, String name, String guide, List<String> sources, String sample) {}
    public static final List<Field> FIELDS = List.of(
        new Field("general", "일반", "분야를 가리지 않는다. 용어를 쉽게 풀고, 사실과 의견을 나눈다.", List.of("openalex", "semantic", "arxiv"), "도시 열섬 현상을 줄이는 가장 효과적인 방법은?"),
        new Field("math", "수학", "정의와 가정을 정확히 쓴다. 수치 실험은 증명이 아니라 증거일 뿐임을 분명히 한다. 알려진 정리와 추측을 구분한다.", List.of("arxiv", "openalex", "semantic"), "리만 제타 영점 간격의 분포를 수치로 살펴보기"),
        new Field("physics", "물리·천문", "단위와 차원을 확인한다. 근사의 범위를 밝히고, 관측·실험값과 비교한다.", List.of("arxiv", "openalex", "semantic"), "왜소은하의 회전 곡선은 암흑물질 없이 설명될까?"),
        new Field("cs", "컴퓨터·AI", "재현 가능한 실험을 설계한다. 기준선과 비교하고, 데이터·평가 지표의 한계를 밝힌다.", List.of("arxiv", "semantic", "openalex"), "작은 언어 모델의 추론 능력은 어디서 생길까?"),
        new Field("bio", "생명·의학", "근거 수준(메타분석, 무작위 시험, 관찰 연구, 동물·세포 실험)을 구분한다. 진단·치료 조언은 하지 않고 연구로만 다룬다.", List.of("pubmed", "openalex", "semantic"), "CRISPR 오프타깃을 줄이는 최신 방법 정리"),
        new Field("chem", "화학·소재", "반응 조건과 안전을 함께 본다. 계산 결과와 실험 결과를 구분한다.", List.of("openalex", "semantic", "arxiv"), "상온에서 쓸 수 있는 고체 전해질 후보 비교"),
        new Field("social", "사회·경제", "인과와 상관을 구분한다. 데이터의 출처·기간·표본을 밝히고, 다른 해석을 함께 적는다.", List.of("openalex", "semantic"), "기본소득 실험들은 노동 공급에 어떤 영향을 줬나?"));
    public static Field field(String id) { for (Field f : FIELDS) if (f.id().equals(id)) return f; return FIELDS.get(0); }

    // ── the research steps shown on 진행판 ──────────────────────────────

    public record Stage(String id, String name, String about) {}
    public static final List<Stage> STAGES = List.of(
        new Stage("plan", "계획", "질문을 나누고 가설과 방법을 정해요"),
        new Stage("idea", "새 아이디어", "아직 없는 가설을 내고, 정말 새로운지 문헌으로 확인해요"),
        new Stage("literature", "문헌 조사", "이미 알려진 것을 찾아요"),
        new Stage("debate", "토론·가설", "연구원들이 주장하고 반박해요"),
        new Stage("prereg", "사전 등록", "실험 전에 예측과 반증 기준을 정해 둬요"),
        new Stage("experiment", "실험", "리눅스에서 계산으로 확인해요"),
        new Stage("analysis", "분석", "결과를 해석해요"),
        new Stage("review", "비평·검증", "근거와 반례를 따져요"),
        new Stage("verdict", "판정·결론", "사전 등록한 예측과 결과를 맞춰 가설을 판정해요"),
        new Stage("report", "보고서", "검증된 것만 묶어요"),
        new Stage("paper", "논문", "논문으로 쓰고, 동료 심사를 받아 고쳐요"));

    // ── records ──────────────────────────────────────────────────────

    /**
     * One project. budget: how many AI answers one 자율 연구 run may use before it stops and asks; approve: an experiment waits
     * for the user's yes before it runs; debaters: the roles a 토론 hears (judge sums it up); rounds: 1–3.
     */
    public record Project(String id, String title, String question, String field, long made, long touched, int budget, boolean approve, List<String> debaters, int rounds, String judge) {
        public Project {
            title = clean(title, 60); question = clean(question, 1200); field = LabBook.field(field).id();
            budget = Math.max(6, Math.min(200, budget)); rounds = Math.max(1, Math.min(3, rounds));
            List<String> kept = new ArrayList<>(); if (debaters != null) for (String d : debaters) if (known(d) && !kept.contains(d)) kept.add(d);
            debaters = List.copyOf(kept); judge = known(judge) ? judge : PI.id();
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("title", title).put("question", question).put("field", field).put("made", made).put("touched", touched)
                .put("budget", budget).put("approve", approve).put("debaters", new JSONArray(debaters)).put("rounds", rounds).put("judge", judge);
        }
        static Project of(JSONObject o) {
            List<String> debaters = new ArrayList<>(); JSONArray d = o.optJSONArray("debaters");
            if (d != null) for (int i = 0; i < d.length(); i++) debaters.add(d.optString(i));
            return new Project(o.optString("id"), o.optString("title"), o.optString("question"), o.optString("field"), o.optLong("made"), o.optLong("touched"),
                o.optInt("budget", 30), o.optBoolean("approve", true), debaters, o.optInt("rounds", 2), o.optString("judge", PI.id()));
        }
        public Project with(String title, String question, String field) { return new Project(id, title, question, field, made, touched, budget, approve, debaters, rounds, judge); }
        public Project settings(int budget, boolean approve) { return new Project(id, title, question, field, made, touched, budget, approve, debaters, rounds, judge); }
        public Project debate(List<String> debaters, int rounds, String judge) { return new Project(id, title, question, field, made, touched, budget, approve, debaters, rounds, judge); }
        Project touched(long at) { return new Project(id, title, question, field, made, at, budget, approve, debaters, rounds, judge); }
    }

    /**
     * One entry of the research notes. kind: user (the user's words), say (a researcher in a 토론 or answering), judge (a 토론
     * summed up), plan, step (책임연구원's choice of the next step), search (문헌 조사), run (an experiment: its code and output),
     * analysis, critique, report, ask (waiting for the user), system (what happened), error. by: the model that wrote it.
     */
    public record Note(String id, long at, String kind, String role, String text, String by, JSONObject data) {
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("at", at).put("kind", kind).put("role", role == null ? "" : role).put("text", text == null ? "" : text)
                .put("by", by == null ? "" : by).put("data", data == null ? new JSONObject() : data);
        }
        static Note of(JSONObject o) {
            JSONObject data = o.optJSONObject("data");
            return new Note(o.optString("id"), o.optLong("at"), o.optString("kind"), o.optString("role"), o.optString("text"), o.optString("by"), data == null ? new JSONObject() : data);
        }
        public boolean mine() { return "user".equals(kind); }
    }

    /** One claim in 검증 장부: agreed (토론에서 합의), disputed (쟁점), check (확인 필요), verified (근거로 확인), refuted (반박됨). */
    public record Claim(String text, String status, String evidence, String by, long at) {
        public Claim { text = clean(text, 400); status = LabBook.status(status); evidence = clean(evidence, 80); by = by == null ? "" : by; }
        JSONObject json() throws Exception { return new JSONObject().put("text", text).put("status", status).put("evidence", evidence).put("by", by).put("at", at); }
        static Claim of(JSONObject o) { return new Claim(o.optString("text"), o.optString("status"), o.optString("evidence"), o.optString("by"), o.optLong("at")); }
    }
    public static final List<String> STATUSES = List.of("verified", "agreed", "check", "disputed", "refuted");
    static String status(String s) { String v = s == null ? "" : s.strip().toLowerCase(Locale.ROOT); return STATUSES.contains(v) ? v : "check"; }
    public static String statusName(String s) {
        return switch (status(s)) { case "verified" -> "검증됨"; case "agreed" -> "합의"; case "disputed" -> "쟁점"; case "refuted" -> "반박됨"; default -> "미검증"; };
    }

    /** One paper or page found by 문헌 조사; key is its number in this project (S1, S2 …). */
    public record Source(String key, String title, String authors, String year, String venue, String url, String summary, String from) {
        public Source { title = clean(title, 300); authors = clean(authors, 160); year = clean(year, 8); venue = clean(venue, 120); url = clean(url, 400); summary = clean(summary, 900); from = clean(from, 30); }
        JSONObject json() throws Exception {
            return new JSONObject().put("key", key).put("title", title).put("authors", authors).put("year", year).put("venue", venue).put("url", url).put("summary", summary).put("from", from);
        }
        static Source of(JSONObject o) { return new Source(o.optString("key"), o.optString("title"), o.optString("authors"), o.optString("year"), o.optString("venue"), o.optString("url"), o.optString("summary"), o.optString("from")); }
        public Source keyed(String value) { return new Source(value, title, authors, year, venue, url, summary, from); }
        /** One line for a prompt or the 참고문헌 list: "[S3] Title (2024) — Authors · Venue". */
        public String line() { return "[" + key + "] " + title + (year.isEmpty() ? "" : " (" + year + ")") + (authors.isEmpty() ? "" : " — " + authors) + (venue.isEmpty() ? "" : " · " + venue); }
    }

    public static final int NOTES = 3000, PROJECTS = 80;
    private final File root;
    public LabBook(File root) { this.root = root; }
    public File root() { return root; }
    public File dir(String id) { return new File(root, safe(id)); }
    public File files(String id) { File f = new File(dir(id), "files"); f.mkdirs(); return f; }

    // ── projects ─────────────────────────────────────────────────────

    /** Every project, the most recently touched first. */
    public synchronized List<Project> projects() {
        String text;
        try { text = read(new File(root, "projects.json"), "[]"); } catch (IOException e) { text = "[]"; }
        // The list as last parsed while the file holds the same words (read hands back the same string then).
        if (text == projectsText && projectsList != null) return new ArrayList<>(projectsList);
        List<Project> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(text);
            for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && !o.optString("id").isBlank()) out.add(Project.of(o)); }
        } catch (Exception ignored) {}
        out.sort((a, b) -> Long.compare(b.touched(), a.touched()));
        projectsText = text; projectsList = new ArrayList<>(out);
        return out;
    }
    private String projectsText; private List<Project> projectsList;
    public synchronized Project project(String id) { for (Project p : projects()) if (p.id().equals(id)) return p; return null; }
    /** A new project with the usual team: 이론가, 비평가 and 실험가 debate two rounds, 책임연구원 sums up. */
    public synchronized Project create(String title, String question, String field) throws IOException {
        if (clean(title, 60).isBlank() && clean(question, 1200).isBlank()) throw new IllegalArgumentException("연구 제목이나 질문을 적어 주세요.");
        List<Project> all = projects(); if (all.size() >= PROJECTS) throw new IllegalStateException("연구 프로젝트는 " + PROJECTS + "개까지 둘 수 있어요. 끝난 연구를 지워 주세요.");
        long now = System.currentTimeMillis();
        String name = clean(title, 60).isBlank() ? clean(question, 40) : title;
        Project p = new Project(UUID.randomUUID().toString().substring(0, 12), name, question, field, now, now, 30, true, List.of(THEORIST.id(), CRITIC.id(), EXPERIMENTER.id()), 2, PI.id());
        save(p);
        return p;
    }
    public synchronized void save(Project p) throws IOException {
        List<Project> all = projects(); JSONArray out = new JSONArray(); boolean found = false;
        try {
            for (Project q : all) { if (q.id().equals(p.id())) { out.put(p.json()); found = true; } else out.put(q.json()); }
            if (!found) out.put(p.json());
        } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        write(new File(root, "projects.json"), out.toString());
    }
    public synchronized void remove(String id) throws IOException {
        List<Project> all = projects(); JSONArray out = new JSONArray();
        try { for (Project q : all) if (!q.id().equals(id)) out.put(q.json()); } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        write(new File(root, "projects.json"), out.toString());
        delete(dir(id));
    }
    private void touch(String id, long at) { Project p = project(id); if (p != null) try { save(p.touched(at)); } catch (IOException ignored) {} }

    // ── notes ────────────────────────────────────────────────────────

    /** Each project's notes as last read, kept while the file stays the same (size and time): every redraw asks for them. */
    private final Map<String, Object[]> read = new java.util.HashMap<>();
    public synchronized List<Note> notes(String id) {
        File file = new File(dir(id), "notes.jsonl"); if (!file.isFile()) return new ArrayList<>();
        long size = file.length(), time = file.lastModified();
        Object[] kept = read.get(id);
        if (kept != null && (long) kept[0] == size && (long) kept[1] == time) { @SuppressWarnings("unchecked") List<Note> notes = (List<Note>) kept[2]; return new ArrayList<>(notes); }
        List<Note> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try { out.add(Note.of(new JSONObject(line))); } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        if (out.size() > NOTES) out = new ArrayList<>(out.subList(out.size() - NOTES, out.size()));
        read.put(id, new Object[]{size, time, new ArrayList<>(out)});
        return out;
    }
    /** How many entries the notes hold, counted without reading them (the list's line under each project). */
    public synchronized int noteCount(String id) {
        File file = new File(dir(id), "notes.jsonl"); if (!file.isFile()) return 0;
        Object[] kept = read.get(id); if (kept != null && (long) kept[0] == file.length() && (long) kept[1] == file.lastModified()) return ((List<?>) kept[2]).size();
        int lines = 0; byte[] buffer = new byte[64 * 1024];
        try (java.io.InputStream in = new java.io.FileInputStream(file)) { for (int n; (n = in.read(buffer)) > 0;) for (int i = 0; i < n; i++) if (buffer[i] == '\n') lines++; }
        catch (IOException e) { return 0; }
        return lines;
    }
    /** Writes an entry at the end of the notes and returns it. */
    public synchronized Note note(String id, String kind, String role, String text, String by, JSONObject data) throws IOException {
        Note n = new Note(UUID.randomUUID().toString().substring(0, 10), System.currentTimeMillis(), kind, role, text, by, data == null ? new JSONObject() : data);
        File file = new File(dir(id), "notes.jsonl"); file.getParentFile().mkdirs();
        try { Files.write(file.toPath(), (n.json().toString() + "\n").getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        touch(id, n.at());
        return n;
    }
    public synchronized void clearNotes(String id) throws IOException {
        Files.deleteIfExists(new File(dir(id), "notes.jsonl").toPath()); read.remove(id);
        Files.deleteIfExists(new File(dir(id), "ledger.json").toPath());
        Files.deleteIfExists(new File(dir(id), "sources.json").toPath());
        Files.deleteIfExists(new File(dir(id), "state.json").toPath());
        delete(new File(dir(id), "files"));
    }

    // ── 진행판 ────────────────────────────────────────────────────────

    public synchronized JSONObject state(String id) {
        try { return new JSONObject(read(new File(dir(id), "state.json"), "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    /** The state to look at, not to change: parsed once while the file holds the same words (the screens ask often). */
    private final Map<String, Object[]> states = new java.util.HashMap<>();
    private JSONObject seen(String id) {
        String text;
        try { text = read(new File(dir(id), "state.json"), "{}"); } catch (IOException e) { text = "{}"; }
        Object[] kept = states.get(id); if (kept != null && kept[0] == text) return (JSONObject) kept[1];
        JSONObject parsed; try { parsed = new JSONObject(text); } catch (Exception e) { parsed = new JSONObject(); }
        states.put(id, new Object[]{text, parsed});
        return parsed;
    }
    public synchronized void state(String id, JSONObject value) throws IOException { write(new File(dir(id), "state.json"), value.toString()); }
    /** A stage's state: "" (아직), doing, done. */
    public synchronized String stage(String id, String stage) { JSONObject s = seen(id).optJSONObject("stages"); return s == null ? "" : s.optString(stage); }
    public synchronized void stage(String id, String stage, String value) throws IOException {
        JSONObject state = state(id); JSONObject stages = state.optJSONObject("stages"); if (stages == null) stages = new JSONObject();
        try { stages.put(stage, value); state.put("stages", stages); } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        state(id, state);
    }
    /** What waits for the user ({action, focus, why}), or null. */
    public synchronized JSONObject pending(String id) { JSONObject p = seen(id).optJSONObject("pending"); if (p == null || p.length() == 0) return null; try { return new JSONObject(p.toString()); } catch (Exception e) { return null; } }
    public synchronized void pending(String id, JSONObject value) throws IOException {
        JSONObject state = state(id); try { if (value == null) state.remove("pending"); else state.put("pending", value); } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        state(id, state);
    }
    /** The AI answers spent by the current 자율 연구 run, and all of them. */
    public synchronized int spent(String id) { return seen(id).optInt("spent"); }
    public synchronized int total(String id) { return seen(id).optInt("total"); }
    public synchronized void spend(String id, int calls) throws IOException {
        JSONObject state = state(id);
        try { state.put("spent", state.optInt("spent") + calls); state.put("total", state.optInt("total") + calls); } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        state(id, state);
    }
    public synchronized void resetRun(String id) throws IOException { JSONObject state = state(id); try { state.put("spent", 0); state.put("steps", 0); state.put("midReport", false); } catch (Exception e) { throw new IOException(e.getMessage(), e); } state(id, state); }
    /** 자율 연구: "" (off), running, waiting (for the user), paused (budget), done. */
    public synchronized String autopilot(String id) { return seen(id).optString("autopilot"); }
    public synchronized void autopilot(String id, String value) throws IOException { JSONObject state = state(id); try { state.put("autopilot", value); } catch (Exception e) { throw new IOException(e.getMessage(), e); } state(id, state); }

    // ── 검증 장부 ─────────────────────────────────────────────────────

    public synchronized List<Claim> claims(String id) {
        List<Claim> out = new ArrayList<>();
        try { JSONArray list = new JSONArray(read(new File(dir(id), "ledger.json"), "[]")); for (int i = 0; i < list.length(); i++) out.add(Claim.of(list.getJSONObject(i))); } catch (Exception ignored) {}
        return out;
    }
    /** Adds claims; one already there (the same words) takes the newer status and evidence. At most 120 are kept. */
    public synchronized void claims(String id, List<Claim> added) throws IOException {
        if (added == null || added.isEmpty()) return;
        Map<String, Claim> all = new LinkedHashMap<>();
        for (Claim c : claims(id)) all.put(normal(c.text()), c);
        for (Claim c : added) if (!c.text().isBlank()) { String k = normal(c.text()); all.remove(k); all.put(k, c); }
        List<Claim> kept = new ArrayList<>(all.values()); if (kept.size() > 120) kept = kept.subList(kept.size() - 120, kept.size());
        JSONArray out = new JSONArray(); try { for (Claim c : kept) out.put(c.json()); } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        write(new File(dir(id), "ledger.json"), out.toString());
    }

    // ── 참고문헌 ──────────────────────────────────────────────────────

    public synchronized List<Source> sources(String id) {
        List<Source> out = new ArrayList<>();
        try { JSONArray list = new JSONArray(read(new File(dir(id), "sources.json"), "[]")); for (int i = 0; i < list.length(); i++) out.add(Source.of(list.getJSONObject(i))); } catch (Exception ignored) {}
        return out;
    }
    /** Adds what was found, numbered after the ones kept (a paper found again keeps its number); returns them with their keys. */
    public synchronized List<Source> sources(String id, List<Source> found) throws IOException {
        List<Source> all = sources(id), out = new ArrayList<>();
        for (Source s : found) {
            Source same = null;
            for (Source k : all) if ((!s.url().isEmpty() && s.url().equalsIgnoreCase(k.url())) || normal(s.title()).equals(normal(k.title()))) { same = k; break; }
            if (same == null) { same = s.keyed("S" + (all.size() + 1)); all.add(same); }
            if (!out.contains(same)) out.add(same);
        }
        JSONArray list = new JSONArray(); try { for (Source s : all) list.put(s.json()); } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        write(new File(dir(id), "sources.json"), list.toString());
        return out;
    }

    // ── 보고서 ────────────────────────────────────────────────────────

    public synchronized String report(String id) { try { return read(new File(dir(id), "report.md"), ""); } catch (IOException e) { return ""; } }
    public synchronized void report(String id, String markdown) throws IOException { write(new File(dir(id), "report.md"), markdown == null ? "" : markdown); }
    /** The paper (논문), its newest version: written by 기록원, then revised after 동료 심사. */
    public synchronized String paper(String id) { try { return read(new File(dir(id), "paper.md"), ""); } catch (IOException e) { return ""; } }
    public synchronized void paper(String id, String markdown) throws IOException { write(new File(dir(id), "paper.md"), markdown == null ? "" : markdown); }
    /** How many experiments have run. */
    public int experiments(String id) { int n = 0; for (Note note : notes(id)) if ("run".equals(note.kind())) n = Math.max(n, note.data().optInt("n")); return n; }

    // ── what the researchers are told ───────────────────────────────

    /** The part every researcher hears first: who they are, the project, and how to answer. */
    public static String brief(Project p, Role r) {
        Field f = field(p.field());
        return "너는 '들퍼레 연구실'의 " + r.name() + "이야. 역할: " + r.duty() + "\n"
            + "연구 프로젝트: " + p.title() + "\n"
            + (p.question().isBlank() ? "" : "연구 질문: " + p.question() + "\n")
            + "분야: " + f.name() + " — " + f.guide() + "\n"
            + """
              원칙:
              - 한국어로 답해. 마크다운(소제목, 목록, 굵게)을 써도 돼. 수식은 LaTeX 대신 읽기 쉬운 기호(x², √, ∑, ≤)로 써.
              - 확실한 것과 추측을 구분해. 추측이면 '가설:'이라고 붙여.
              - 출처가 없는 숫자, 논문, 인용을 지어내지 마. 연구 기록에 있는 근거 번호([S1] 문헌, [E2] 실험)만 인용해. 모르면 모른다고 해.
              - 위험한 생물·화학 실험 방법(병원체 강화, 독성 물질 합성 등)과 의료 진단·처방은 다루지 않아.
              """;
    }
    /** A 토론's first round: each answers without seeing the others. */
    public static String debateFirst(String topic, String digest) {
        return (digest.isBlank() ? "" : "지금까지의 연구 기록(요약):\n" + digest + "\n\n")
            + "토론 주제: " + topic + "\n\n"
            + "다른 연구원의 답을 보지 않은 상태야. 네 역할의 관점에서 답해: 핵심 주장 2~4개, 각 주장의 근거나 이유, 그 주장을 확인할 방법(문헌·실험·증명). 700자 안팎으로.";
    }
    /** A later round: the others' latest answers, and this one's own. */
    public static String debateRebut(String topic, String others, String own, int round) {
        return "토론 주제: " + topic + "\n\n다른 연구원들의 지난 답:\n" + others + "\n\n네 지난 답:\n" + (own.isBlank() ? "(없음)" : own) + "\n\n"
            + round + "라운드야. 동의하는 점은 한 줄로 짧게. 틀렸거나 약한 주장은 누구의 어떤 말인지 짚어서 구체적으로 반박해. 놓친 점을 보태. 생각이 바뀌었으면 바뀌었다고 말해. 600자 안팎으로.";
    }
    public static String judge(String topic, String transcript) {
        return "토론 주제: " + topic + "\n\n토론 기록:\n" + transcript + "\n\n"
            + """
              심판으로서 토론을 정리해. 누구 편도 들지 말고 근거의 힘으로만 판단해. 형식:
              ## 합의한 것
              ## 남은 쟁점
              ## 확인이 필요한 것
              (각각 어떻게 확인할지: 문헌 조사, 실험, 증명)
              ## 다음 단계 제안
              마지막에 검증 장부에 올릴 주장을 ```json 블록으로 붙여(최대 6개):
              {"claims":[{"text":"주장 한 문장","status":"agreed 또는 disputed 또는 check"}]}
              """;
    }
    public static String plan(String digest) {
        return (digest.isBlank() ? "" : "지금까지의 연구 기록(요약):\n" + digest + "\n\n")
            + """
              이 연구의 계획을 세워. 형식:
              ## 핵심 질문
              (연구 질문을 확인할 수 있는 작은 질문 2~4개로 나누기)
              ## 가설
              ## 방법
              (각 질문을 문헌 조사, 토론, 계산 실험 중 무엇으로 확인할지)
              ## 성공 기준
              (무엇이 나오면 답을 얻었다고 볼지)
              700자 안팎으로.
              """;
    }
    public static String searchPrompt(String focus, String digest) {
        return (digest.isBlank() ? "" : "지금까지의 연구 기록(요약):\n" + digest + "\n\n")
            + "조사할 초점: " + (focus.isBlank() ? "연구 질문 전체" : focus) + "\n\n"
            + "학술 검색(arXiv, PubMed, OpenAlex, Semantic Scholar)에 넣을 영어 검색어를 2~3개 만들어. 짧은 핵심어 조합으로(4~8단어), 서로 다른 측면을 겨냥해.\n"
            + "JSON만 답해: {\"queries\":[\"...\",\"...\"]}";
    }
    public static String literature(String focus, String list) {
        return "조사할 초점: " + (focus.isBlank() ? "연구 질문 전체" : focus) + "\n\n찾은 논문(번호, 제목, 요약):\n" + list + "\n\n"
            + """
              이 목록만 근거로 정리해. 문장마다 끝에 근거 번호를 붙여([S3]). 목록에 없는 논문이나 수치는 쓰지 마. 관련 없는 논문은 빼. 형식:
              ## 핵심 발견
              ## 서로 다른 견해
              ## 아직 밝혀지지 않은 것
              ## 우리 연구에 쓸 것
              마지막에 검증 장부에 올릴 주장을 ```json 블록으로 붙여(최대 6개, 목록의 근거가 있는 것만):
              {"claims":[{"text":"주장 한 문장","status":"verified","evidence":"S2"}]}
              """;
    }
    public static String experiment(String goal, String digest) { return experiment(goal, digest, "numpy, scipy, sympy, mpmath, matplotlib, pandas", ""); }
    /** packages: what is installed ("numpy, scipy, …"); data: the data files in data/ with their columns ("" for none). */
    public static String experiment(String goal, String digest, String packages, String data) {
        return (digest.isBlank() ? "" : "지금까지의 연구 기록(요약):\n" + digest + "\n\n")
            + "실험 목표: " + goal + "\n\n"
            + (data == null || data.isBlank() ? "" : "쓸 수 있는 데이터(data/ 폴더, CSV):\n" + data + "\n\n")
            + "이 목표를 확인할 Python 3 프로그램을 하나 써. 규칙:\n- 쓸 수 있는 것: 표준 라이브러리, " + packages + ". 다른 패키지는 없어.\n"
            + (data == null || data.isBlank() ? "" : "- 데이터는 data/ 폴더의 CSV를 pandas로 읽어(예: pd.read_csv('data/이름.csv')).\n")
            + """
              - 인터넷에 접속하지 마. 지금 폴더 밖의 파일을 읽거나 쓰지 마.
              - 휴대폰에서 돌아가니 90초 안에 끝나게 크기를 정해.
              - 핵심 숫자와 결론을 print로 또렷하게 출력해. 결과가 가설을 지지하는지 판단할 수 있게.
              - 그래프가 도움이 되면 matplotlib으로 그려 out/ 폴더에 PNG로 저장해(plt.savefig('out/fig1.png', dpi=130, bbox_inches='tight')). 그래프 글자는 영어로(한글 글꼴이 없어). plt.show()는 쓰지 마.
              - 무작위를 쓰면 시드를 고정해.
              - 결과 숫자 표는 out/results.csv(필요하면 out/ 아래 다른 이름.csv)로도 저장해. 첫 줄은 영어 열 이름. 이 표는 논문과 구글 시트로 가.
              - 통계 검정을 했으면 검정 이름, 통계량, p값, 효과 크기를 함께 출력해.
              답 형식: 무엇을 어떻게 확인하는지 두세 문장, 그다음 ```python 코드 블록 하나.
              """;
    }
    public static String fix(String code, String error) {
        return "이 Python 코드가 실패했어.\n```python\n" + code + "\n```\n오류(끝부분):\n```\n" + error + "\n```\n"
            + "원인을 한 문장으로 말하고, 고친 전체 코드를 ```python 블록 하나로 다시 줘. 같은 규칙(앞에서 말한 패키지만, 90초 안, 그림은 out/에 PNG, 표는 out/에 CSV)을 지켜. 없는 패키지 때문이면 numpy·scipy로 직접 구현해.";
    }
    public static String analysis(String goal, int n, String code, String output, int figures) {
        return "실험 E" + n + "의 목표: " + goal + "\n\n코드:\n```python\n" + clip(code, 3000) + "\n```\n\n출력:\n```\n" + clip(output, 5000) + "\n```\n"
            + (figures > 0 ? "그림 " + figures + "개를 저장했어.\n" : "")
            + """

              결과를 해석해. 출력에 있는 숫자만 근거로 써. 형식:
              ## 결과
              ## 해석
              (가설을 지지하나, 반박하나, 아직 모르나)
              ## 한계
              (크기, 근사, 빠진 대조군)
              마지막에 검증 장부에 올릴 주장을 ```json 블록으로 붙여(최대 4개):
              {"claims":[{"text":"주장 한 문장","status":"verified 또는 refuted 또는 check","evidence":"E%d"}]}
              """.formatted(n);
    }
    public static String critique(String digest, String ledger) {
        return "최근 연구 기록:\n" + digest + "\n\n" + (ledger.isBlank() ? "" : "검증 장부:\n" + ledger + "\n\n")
            + """
              비평가로서 냉정하게 검토해. 근거 없는 주장, 논리 비약, 반례, 빠진 대조군, 과장된 결론을 찾아. 잘된 점은 한 줄로만. 형식:
              ## 문제점
              (누구의 어떤 주장인지 짚어서)
              ## 반례와 다른 설명
              ## 보완할 것
              (구체적인 다음 확인 방법)
              마지막에 장부의 상태를 바꿀 주장을 ```json 블록으로 붙여(최대 6개):
              {"claims":[{"text":"주장 한 문장","status":"refuted 또는 disputed 또는 check","evidence":"근거 번호나 짧은 이유"}]}
              """;
    }
    public static String report(String digest, String ledger, String sources) {
        return "연구 기록(요약):\n" + digest + "\n\n검증 장부:\n" + (ledger.isBlank() ? "(비어 있음)" : ledger) + "\n\n참고문헌:\n" + (sources.isBlank() ? "(없음)" : sources) + "\n\n"
            + """
              지금까지의 연구를 보고서로 써. 형식:
              # (연구 제목)
              ## 요약
              (세 문장)
              ## 연구 질문
              ## 방법
              ## 결과
              (검증된 것 위주로, 문장마다 근거 번호 [S1], [E2])
              ## 논의
              (쟁점, 반박된 것, 한계)
              ## 다음 연구
              ## 참고문헌
              (위 참고문헌 가운데 본문에 인용한 것만, 받은 그대로)
              미검증 주장에는 '(미검증)'이라고 붙여. 기록에 없는 내용은 보태지 마.
              마지막 줄: "_AI 연구원들이 만든 가설과 계산이에요. 중요한 결정 전에는 원문으로 확인해 주세요._"
              """;
    }
    /** 책임연구원's choice of the next step for 자율 연구. */
    public static String next(String digest, String stages, int left, boolean python, boolean askedBefore) {
        return "연구 기록(요약):\n" + digest + "\n\n진행판: " + stages + "\n이번 자율 연구에서 남은 AI 답: " + left + "번\n\n"
            + "다음에 할 일 하나를 골라. 가능한 일:\n"
            + "- search: 문헌 조사 (focus: 무엇을 찾을지)\n"
            + "- debate: 연구원 토론 (focus: 토론 주제, 쟁점이 있을 때)\n"
            + (python ? "- experiment: 계산 실험 (focus: 확인할 것 한 가지, 계산으로 확인할 수 있을 때만)\n" : "- experiment: 지금은 못 써(앱 안 Python이 준비되지 않았어)\n")
            + "- ideate: 새 아이디어 찾기 (focus: 어느 방향으로) — 질문이 열려 있거나 새로운 연구를 하려는데 아직 '새 아이디어'를 고르지 않았을 때, 맨 처음에\n"
            + "- critique: 비평가의 검토 (결과가 쌓였을 때)\n"
            + (askedBefore ? "" : "- ask: 사용자에게 질문 (focus: 질문, 정말 사용자만 정할 수 있을 때만)\n")
            + "- paper: 논문을 쓰고 동료 심사를 받아 고친 뒤 마치기 (AI 답 4번 필요) — 문헌 조사와 실험 결과(E)가 하나 이상 있고 비평을 거쳤을 때\n"
            + "- report: 보고서를 쓰고 마치기 (실험 없이 정리만 할 때, 또는 남은 답이 5번 아래일 때)\n"
            + "같은 일을 같은 초점으로 되풀이하지 마. 문헌 조사 없이 결론 내지 마. 비평 없이 보고서로 가지 마.\n"
            + "learned에는 지금까지 알게 된 것(연구 질문에 대한 잠정 결론)을 두세 줄로, 근거 번호([S1], [E2])와 함께 써. 아직 모르는 건 모른다고.\n"
            + "JSON만 답해: {\"learned\":\"지금까지 알게 된 것 두세 줄\",\"action\":\"ideate|search|debate|experiment|critique|ask|paper|report\",\"focus\":\"...\",\"why\":\"한 문장\"}";
    }
    /** A researcher answering the user directly (@이름). */
    public static String direct(String message, String digest) {
        return (digest.isBlank() ? "" : "지금까지의 연구 기록(요약):\n" + digest + "\n\n") + "사용자가 너에게 물었어:\n" + message + "\n\n네 역할로 답해. 600자 안팎으로.";
    }

    // ── new research: ideas, their novelty, the paper and its review ──

    /**
     * 새 아이디어 (준: "새로운 연구, 실험을 하고 새로운 논문도 쓸 수 있게"): research ideas that are not known yet and can be
     * tested here (Python in the app's Linux, open papers), each with search words for checking whether it is new.
     */
    public static String ideas(String focus, String digest) {
        return (digest.isBlank() ? "" : "연구 기록(요약):\n" + digest + "\n\n") + "방향: " + (focus == null || focus.isBlank() ? "(연구 질문 그대로)" : focus) + "\n\n"
            + """
              이 방향에서 아직 연구되지 않았을 법한 새로운 연구 아이디어 5개를 내. 조건:
              - 기존에 잘 알려진 결과를 다시 확인하는 것이 아니라, 새로운 질문·조합·방법·측정이어야 해.
              - 이 앱에서 확인할 수 있어야 해: 공개 논문·데이터 + 휴대폰 안 Python(numpy, scipy, sympy, pandas, matplotlib, 150초 안)으로 계산·시뮬레이션·통계 분석.
              - 반증 가능해야 해: 무엇이 관찰되면 틀린 것인지.
              - 위험한 생물·화학 실험, 사람 대상 실험은 제외.
              JSON만 답해:
              {"ideas":[{"title":"짧은 제목","hypothesis":"가설 한 문장","novelty":"왜 새로운지 한 문장","test":"이 앱에서 확인하는 방법 한두 문장","outcome":"맞다면/틀리다면 보일 것","queries":["새로운지 확인할 영어 검색어 1","검색어 2"]}]}
              """;
    }
    /** One idea 새 아이디어 gave. */
    public record Idea(String title, String hypothesis, String novelty, String test, String outcome, List<String> queries) {
        public JSONObject json() {
            JSONObject o = new JSONObject();
            try { o.put("title", title).put("hypothesis", hypothesis).put("novelty", novelty).put("test", test).put("outcome", outcome).put("queries", new JSONArray(queries)); } catch (Exception ignored) {}
            return o;
        }
        public static Idea of(JSONObject o) {
            List<String> q = new ArrayList<>(); JSONArray a = o == null ? null : o.optJSONArray("queries");
            for (int i = 0; a != null && i < a.length() && q.size() < 2; i++) { String s = clean(a.optString(i), 120); if (!s.isBlank()) q.add(s); }
            return o == null ? null : new Idea(clean(o.optString("title"), 120), clean(o.optString("hypothesis"), 400), clean(o.optString("novelty"), 300), clean(o.optString("test"), 400), clean(o.optString("outcome"), 300), q);
        }
    }
    public static List<Idea> ideaList(String answer) {
        List<Idea> out = new ArrayList<>(); JSONObject o = json(answer); JSONArray a = o == null ? null : o.optJSONArray("ideas");
        for (int i = 0; a != null && i < a.length() && out.size() < 5; i++) { Idea idea = Idea.of(a.optJSONObject(i)); if (idea != null && !idea.title().isBlank() && !idea.hypothesis().isBlank()) out.add(idea); }
        return out;
    }
    /** 문헌조사원 judging each idea against the closest papers found for it: how new, how testable here, how much it matters. */
    public static String novelty(String ideasWithPapers) {
        return ideasWithPapers + "\n\n"
            + """
              위 아이디어마다, 함께 찾은 가장 가까운 논문들과 비교해 점수를 매겨(1~5).
              - novelty(새로움): 5 = 찾은 논문 어디에도 없음, 3 = 비슷한 연구가 있지만 질문·방법·대상이 다름, 1 = 이미 같은 연구가 있음
              - feasible(여기서 확인 가능): 이 앱의 Python(150초)과 공개 자료로 의미 있는 답을 낼 수 있는지
              - impact(의미): 맞다면 얼마나 중요한지
              closest에는 가장 가까운 논문의 번호([S3])를, note에는 무엇이 다른지 한 문장.
              JSON만 답해: {"scores":[{"i":1,"novelty":4,"feasible":3,"impact":3,"closest":"[S3]","note":"..."}]}
              """;
    }
    public record IdeaScore(int novelty, int feasible, int impact, String closest, String note) {
        public int total() { return novelty * 2 + feasible + impact; }
    }
    public static java.util.Map<Integer, IdeaScore> scores(String answer) {
        java.util.Map<Integer, IdeaScore> out = new java.util.HashMap<>(); JSONObject o = json(answer); JSONArray a = o == null ? null : o.optJSONArray("scores");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i); if (s == null) continue;
            out.put(s.optInt("i", i + 1), new IdeaScore(score(s.optInt("novelty", 3)), score(s.optInt("feasible", 3)), score(s.optInt("impact", 3)), clean(s.optString("closest"), 40), clean(s.optString("note"), 300)));
        }
        return out;
    }
    private static int score(int v) { return Math.max(1, Math.min(5, v)); }
    /** The experiments as a paper reads them: goal, outcome, figures by file name, the end of the output, the analysis. */
    public static String experiments(List<Note> notes) {
        StringBuilder out = new StringBuilder(); java.util.Map<Integer, String> analyses = new java.util.HashMap<>();
        for (Note n : notes) if ("analysis".equals(n.kind())) analyses.put(n.data().optInt("n"), prose(n.text()));
        for (Note n : notes) {
            if (!"run".equals(n.kind())) continue;
            JSONObject d = n.data(); int k = d.optInt("n");
            JSONArray f = d.optJSONArray("figures"); List<String> figures = new ArrayList<>(); for (int i = 0; f != null && i < f.length(); i++) figures.add(f.optString(i));
            String output = d.optString("output");
            out.append("[E").append(k).append("] 목표: ").append(d.optString("goal")).append(" · ").append(d.optInt("exit") == 0 ? "성공" : "실패")
                .append(" · ").append(d.optLong("millis") / 1000).append("초").append(figures.isEmpty() ? "" : " · 그림 파일: " + String.join(", ", figures)).append('\n')
                .append("출력(끝부분):\n").append(clip(output.length() > 1800 ? output.substring(output.length() - 1800) : output, 1800)).append('\n');
            if (analyses.containsKey(k)) out.append("분석: ").append(clip(analyses.get(k), 900)).append('\n');
            out.append('\n');
        }
        return out.toString().strip();
    }
    /** 논문: the research written as a paper, only from what is in the record (real sources, the experiments' own numbers and figures). */
    public static String paper(String digest, String ledger, String sources, String experiments, String idea, String extra) {
        return "연구 기록(요약):\n" + digest + "\n\n" + (idea.isBlank() ? "" : "고른 연구 아이디어:\n" + idea + "\n\n")
            + "검증 장부:\n" + (ledger.isBlank() ? "(비어 있음)" : ledger) + "\n\n실험:\n" + (experiments.isBlank() ? "(없음)" : experiments)
            + "\n\n참고문헌:\n" + (sources.isBlank() ? "(없음)" : sources) + (extra == null || extra.isBlank() ? "" : "\n\n사용자의 요청: " + extra) + "\n\n"
            + """
              이 연구를 학술 논문으로 써. 형식(마크다운):
              # (논문 제목)
              **초록** (150~250자: 질문, 방법, 핵심 결과의 숫자, 의미)
              ## 1. 서론 (왜 중요한지, 무엇이 아직 모르는지, 이 논문의 기여 2~3가지를 목록으로)
              ## 2. 관련 연구 (참고문헌을 [S1]처럼 인용하며, 이 연구가 무엇이 다른지)
              ## 3. 방법 (가설, 모형·수식, 계산·시뮬레이션 방법, 재현에 필요한 매개변수)
              ## 4. 결과 (실험 E마다: 무엇을 했고 숫자가 무엇이었는지. 숫자는 실험 출력에 있는 것만, 표로 정리. 그림은 ![그림 n. 설명](파일이름) 한 줄로, 위 '그림 파일'에 있는 이름만)
              ## 5. 논의 (가설이 지지되는지 반박되는지, 다른 설명, 검증 장부의 반박·논쟁 주장)
              ## 6. 한계와 다음 연구
              ## 7. 결론
              ## 참고문헌 (본문에 인용한 것만, 위 목록 그대로)
              규칙:
              - 실험 출력에 없는 숫자, 위 목록에 없는 논문은 절대 쓰지 마. 결과가 가설과 어긋나면 그대로 써.
              - 계산 실험은 증거이지 증명이 아니야. 확인된 범위를 정확히 밝혀.
              - 미검증 주장에는 '(미검증)'.
              - 마지막 줄: "_들퍼레 연구실의 AI 연구원들이 쓴 논문이에요. 동료 심사를 거쳤지만 사람의 검증이 필요해요._"
              """;
    }
    /** 동료 심사: a reviewer's report on the paper, with scores and what must change. */
    public static String peer(String paper, String ledger, String experiments) {
        return "심사할 논문:\n" + clip(paper, 14000) + "\n\n연구 기록의 검증 장부:\n" + (ledger.isBlank() ? "(비어 있음)" : ledger) + "\n\n실험 기록:\n" + (experiments.isBlank() ? "(없음)" : clip(experiments, 5000)) + "\n\n"
            + """
              학회 심사위원처럼 이 논문을 엄격하게 심사해. 실험 기록과 다른 숫자, 출처 없는 주장, 과장된 결론, 빠진 대조군·기준선, 재현 정보 부족을 찾아.
              형식:
              ## 요약
              ## 강점
              ## 약점
              (번호를 붙여 구체적으로)
              ## 고쳐야 할 것
              (필수 수정만, 번호를 붙여)
              ## 판정
              마지막에 ```json 블록:
              {"scores":{"novelty":1-10,"soundness":1-10,"clarity":1-10,"significance":1-10},"decision":"accept 또는 minor 또는 major 또는 reject"}
              """;
    }
    public record Review(int novelty, int soundness, int clarity, int significance, String decision) {
        public String line() { return "새로움 " + novelty + " · 타당성 " + soundness + " · 명확성 " + clarity + " · 중요성 " + significance + " · " + decisionName(decision); }
    }
    public static Review review(String answer) {
        JSONObject o = json(answer); JSONObject s = o == null ? null : o.optJSONObject("scores");
        String d = o == null ? "" : o.optString("decision").strip().toLowerCase(Locale.ROOT);
        if (!List.of("accept", "minor", "major", "reject").contains(d)) d = "major";
        return new Review(ten(s, "novelty"), ten(s, "soundness"), ten(s, "clarity"), ten(s, "significance"), d);
    }
    private static int ten(JSONObject s, String k) { return s == null ? 5 : Math.max(1, Math.min(10, s.optInt(k, 5))); }
    public static String decisionName(String d) {
        return switch (d == null ? "" : d) { case "accept" -> "게재"; case "minor" -> "소폭 수정 후 게재"; case "reject" -> "게재 불가"; default -> "대폭 수정"; };
    }
    /** 수정: the paper again, answering each review's required changes, with what changed at the end. */
    public static String revise(String paper, String reviews, String ledger, String sources, String experiments) {
        return "지금 논문:\n" + clip(paper, 14000) + "\n\n동료 심사:\n" + clip(reviews, 8000) + "\n\n검증 장부:\n" + (ledger.isBlank() ? "(비어 있음)" : ledger)
            + "\n\n실험:\n" + (experiments.isBlank() ? "(없음)" : clip(experiments, 5000)) + "\n\n참고문헌:\n" + (sources.isBlank() ? "(없음)" : sources) + "\n\n"
            + """
              심사의 '고쳐야 할 것'을 하나씩 반영해 논문 전체를 다시 써(같은 형식). 반영할 수 없는 것(새 실험이 필요한 것)은 '한계와 다음 연구'에 정직하게 적어.
              새 숫자나 새 논문을 지어내지 마. 맨 끝, 마지막 줄 바로 앞에 '## 심사 반영' 소제목을 두고 무엇을 어떻게 고쳤는지 번호로 짧게 적어.
              """;
    }

    /**
     * 사전 등록 (가설→결론 파이프라인): before any experiment, the hypothesis, what each prediction would show, what would
     * refute it, and the experiments and analysis that will decide, so the verdict is held to what was said first.
     */
    public static String prereg(String digest, String idea, String data) {
        return "연구 기록(요약):\n" + digest + "\n\n" + (idea.isBlank() ? "" : "연구할 아이디어:\n" + idea + "\n\n") + (data == null || data.isBlank() ? "" : "쓸 수 있는 데이터:\n" + data + "\n\n")
            + """
              실험 전에 연구를 사전 등록해. 형식:
              ## 가설
              ## 예측
              (P1, P2 …: 무엇을 측정하면 어떤 숫자·방향이 나와야 하는지, 기준값까지)
              ## 반증 기준
              (어떤 결과가 나오면 가설이 틀렸다고 인정할지)
              ## 실험 계획
              (E1, E2: 휴대폰 Python으로 150초 안에 돌릴 수 있는 계산·시뮬레이션·데이터 분석, 대조군·기준선 포함)
              ## 분석 계획
              (어떤 통계·지표로 판단할지, 유의수준)
              마지막에 ```json 블록:
              {"experiments":["E1에서 할 일 한 문장(측정할 것과 대조군 포함)","E2에서 할 일"],"predictions":[{"id":"P1","text":"예측","refute":"반증 조건"}]}
              """;
    }
    /** The experiments a 사전 등록 lists (at most two). */
    public static List<String> registered(String answer) {
        List<String> out = new ArrayList<>(); JSONObject o = json(answer); JSONArray a = o == null ? null : o.optJSONArray("experiments");
        for (int i = 0; a != null && i < a.length() && out.size() < 2; i++) { String s = clean(a.optString(i), 400); if (!s.isBlank()) out.add(s); }
        return out;
    }
    /** 판정·결론: each registered prediction against what the experiments printed, and the hypothesis judged by the refutation rule set first. */
    public static String verdict(String digest, String prereg, String experiments, String ledger) {
        return "사전 등록:\n" + clip(prereg, 5000) + "\n\n실험:\n" + (experiments.isBlank() ? "(없음)" : clip(experiments, 7000)) + "\n\n검증 장부:\n" + (ledger.isBlank() ? "(비어 있음)" : ledger)
            + "\n\n연구 기록(요약):\n" + digest + "\n\n"
            + """
              사전 등록한 예측과 실제 실험 출력을 하나씩 맞춰 가설을 판정해. 사전 등록 뒤에 기준을 바꾸지 마.
              형식:
              ## 판정
              (지지 / 반박 / 판단 불가 중 하나와 한 문장 이유)
              ## 예측별 결과
              (P1: 맞음·틀림·판단 불가 — 실제 숫자와 근거 [E1])
              ## 결론
              (이 연구로 말할 수 있는 것과 없는 것)
              ## 남은 의문과 다음 실험
              마지막에 ```json 블록:
              {"verdict":"supported 또는 refuted 또는 inconclusive","claims":[{"text":"결론 주장 한 문장","status":"verified 또는 refuted 또는 disputed 또는 check","evidence":"E1"}]}
              """;
    }
    public static String verdictOf(String answer) {
        JSONObject o = json(answer); String v = o == null ? "" : o.optString("verdict").strip().toLowerCase(Locale.ROOT);
        return List.of("supported", "refuted", "inconclusive").contains(v) ? v : "inconclusive";
    }
    public static String verdictName(String v) { return switch (v == null ? "" : v) { case "supported" -> "가설 지지"; case "refuted" -> "가설 반박"; default -> "판단 불가"; }; }
    /**
     * The data files in a folder (data/*.csv) as an experiment is told of them: name, rows, columns and the first rows.
     * "" when there are none.
     */
    public static String dataList(File folder) {
        File[] files = folder == null ? null : folder.listFiles((d, name) -> name.endsWith(".csv"));
        if (files == null || files.length == 0) return "";
        java.util.Arrays.sort(files); StringBuilder out = new StringBuilder();
        for (File f : files) {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
                List<String> head = new ArrayList<>(); String line; int rows = 0;
                while ((line = r.readLine()) != null) { if (head.size() < 4) head.add(clip(line, 300)); rows++; }
                out.append("- data/").append(f.getName()).append(" · ").append(Math.max(0, rows - 1)).append("행\n  ").append(String.join("\n  ", head)).append('\n');
            } catch (IOException unreadable) { out.append("- data/").append(f.getName()).append(" (읽지 못함)\n"); }
        }
        return out.toString().strip();
    }

    // ── reading the answers ─────────────────────────────────────────

    private static final Pattern FENCE_JSON = Pattern.compile("```(?:json)?\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);
    private static final Pattern FENCE_CODE = Pattern.compile("```(?:python|py|python3)?[ \\t]*\\n(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** The JSON object an answer carries (in a ```json block, else the first balanced {…}), or null. */
    public static JSONObject json(String text) {
        if (text == null) return null;
        Matcher m = FENCE_JSON.matcher(text);
        while (m.find()) { try { return new JSONObject(m.group(1)); } catch (Exception ignored) {} }
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0; boolean quoted = false, escaped = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') quoted = false; continue; }
                if (c == '"') quoted = true; else if (c == '{') depth++;
                else if (c == '}' && --depth == 0) { try { return new JSONObject(text.substring(start, i + 1)); } catch (Exception bad) { break; } }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }
    /** The answer without its ```json block (what the notes show). */
    public static String prose(String text) {
        if (text == null) return "";
        String out = text.replaceAll("(?s)```json\\s*\\{.*?\\}\\s*```", "");
        // A JSON block left at the very end without its fence.
        out = out.replaceAll("(?s)\\n\\s*\\{\\s*\"claims\"\\s*:.*$", "");
        return out.strip();
    }
    /** The Python in an answer (its ```python block, else the only fenced block), or "". */
    public static String code(String text) {
        if (text == null) return "";
        Matcher m = FENCE_CODE.matcher(text); String best = "";
        while (m.find()) if (m.group(1).length() > best.length()) best = m.group(1);
        return best.strip();
    }
    /** The words before an answer's code block (its explanation). */
    public static String beforeCode(String text) { if (text == null) return ""; int at = text.indexOf("```"); return (at < 0 ? text : text.substring(0, at)).strip(); }
    /** The claims an answer lists for 검증 장부. */
    public static List<Claim> claims(String text, String by) {
        List<Claim> out = new ArrayList<>(); JSONObject o = json(text); if (o == null) return out;
        JSONArray list = o.optJSONArray("claims"); if (list == null) return out;
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.length() && out.size() < 8; i++) {
            JSONObject c = list.optJSONObject(i); if (c == null || c.optString("text").isBlank()) continue;
            out.add(new Claim(c.optString("text"), c.optString("status"), c.optString("evidence"), by, now));
        }
        return out;
    }
    /** The search words 문헌조사원 gave (at most three), or the focus itself. */
    public static List<String> queries(String answer, String fallback) {
        List<String> out = new ArrayList<>(); JSONObject o = json(answer);
        JSONArray q = o == null ? null : o.optJSONArray("queries");
        if (q != null) for (int i = 0; i < q.length() && out.size() < 3; i++) { String s = clean(q.optString(i), 120); if (!s.isBlank() && !out.contains(s)) out.add(s); }
        if (out.isEmpty() && fallback != null && !fallback.isBlank()) out.add(clean(fallback, 120));
        return out;
    }
    /** 책임연구원's next step: {action, focus, why}; report when it cannot be read. */
    public record Step(String action, String focus, String why, String learned) {
        public Step(String action, String focus, String why) { this(action, focus, why, ""); }
    }
    public static Step step(String answer) {
        JSONObject o = json(answer);
        String a = o == null ? "" : o.optString("action").strip().toLowerCase(Locale.ROOT);
        if (!List.of("ideate", "search", "debate", "experiment", "critique", "ask", "paper", "report").contains(a)) a = "report";
        return new Step(a, o == null ? "" : clean(o.optString("focus"), 300), o == null ? "다음 단계를 읽지 못해 보고서로 마무리해요." : clean(o.optString("why"), 300), o == null ? "" : clean(o.optString("learned"), 900));
    }
    public static String actionName(String action) {
        return switch (action == null ? "" : action) {
            case "search" -> "문헌 조사"; case "debate" -> "토론"; case "experiment" -> "실험"; case "critique" -> "비평"; case "ask" -> "질문"; case "report" -> "보고서"; case "plan" -> "계획";
            case "ideate" -> "새 아이디어"; case "paper" -> "논문"; case "prereg" -> "사전 등록"; case "verdict" -> "판정·결론";
            default -> "다음 단계";
        };
    }

    // ── what the researchers read of the notes ──────────────────────

    /** The research so far, newest kept, in about budget characters: what each prompt is grounded in. */
    public static String digest(List<Note> notes, int budget) {
        List<String> parts = new ArrayList<>(); int used = 0;
        for (int i = notes.size() - 1; i >= 0 && used < budget; i--) {
            Note n = notes.get(i);
            if (List.of("system", "error", "ask").contains(n.kind())) continue;
            String who = n.mine() ? "사용자" : role(n.role()).name();
            String what = switch (n.kind()) {
                case "run" -> "실험 E" + n.data().optInt("n") + (n.data().optInt("exit") == 0 ? " 성공" : " 실패") + ": " + n.data().optString("goal") + "\n출력: " + clip(n.data().optString("output"), 900);
                case "step" -> "다음 단계: " + actionName(n.data().optString("action")) + " — " + n.data().optString("focus");
                case "paper" -> "논문 v" + n.data().optInt("version", 1) + " 작성: " + clip(prose(n.text()), 500);
                default -> prose(n.text());
            };
            int room = Math.max(300, Math.min(1400, budget - used));
            String line = "[" + kindName(n.kind()) + " · " + who + "] " + clip(what, room);
            parts.add(line); used += line.length();
        }
        java.util.Collections.reverse(parts);
        return String.join("\n\n", parts);
    }
    public static String kindName(String kind) {
        return switch (kind == null ? "" : kind) {
            case "user" -> "사용자"; case "say" -> "발언"; case "learned" -> "중간 정리"; case "judge" -> "토론 정리"; case "plan" -> "계획"; case "step" -> "결정"; case "search" -> "문헌 조사";
            case "run" -> "실험"; case "analysis" -> "분석"; case "critique" -> "비평"; case "report" -> "보고서"; case "ask" -> "승인·질문"; case "error" -> "오류";
            case "ideas" -> "새 아이디어"; case "paper" -> "논문"; case "peer" -> "동료 심사"; case "prereg" -> "사전 등록"; case "verdict" -> "판정·결론"; case "data" -> "데이터"; case "sheets" -> "구글 시트";
            default -> "기록";
        };
    }
    public static String ledger(List<Claim> claims) {
        StringBuilder out = new StringBuilder();
        for (Claim c : claims) out.append("- [").append(statusName(c.status())).append("] ").append(c.text()).append(c.evidence().isEmpty() ? "" : " (" + c.evidence() + ")").append('\n');
        return out.toString().strip();
    }
    public static String sourceList(List<Source> sources, boolean withSummary) {
        StringBuilder out = new StringBuilder();
        for (Source s : sources) { out.append(s.line()); if (withSummary && !s.summary().isEmpty()) out.append("\n  ").append(clip(s.summary(), 600)); out.append('\n'); }
        return out.toString().strip();
    }
    /** The stages as one line for 책임연구원: "계획 완료 · 문헌 조사 아직 …". */
    public static String stages(JSONObject state) {
        JSONObject s = state == null ? null : state.optJSONObject("stages"); List<String> out = new ArrayList<>();
        for (Stage st : STAGES) { String v = s == null ? "" : s.optString(st.id()); out.add(st.name() + " " + (v.equals("done") ? "완료" : v.equals("doing") ? "진행 중" : "아직")); }
        return String.join(" · ", out);
    }
    /** "@비평가 …" → the role called, or null. */
    public static Role mention(String text) {
        if (text == null) return null; String t = text.strip(); if (!t.startsWith("@")) return null;
        for (Role r : ROLES) if (t.startsWith("@" + r.name())) return r;
        return null;
    }
    public static String withoutMention(String text, Role r) { return r == null ? text.strip() : text.strip().substring(r.name().length() + 1).strip(); }

    // ── files ────────────────────────────────────────────────────────

    /** Korean particles after a word: 이/가 and 을/를 by whether its last syllable has a final consonant. */
    public static boolean batchim(String w) { if (w == null || w.isEmpty()) return false; char c = w.charAt(w.length() - 1); return c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0; }
    public static String ida(String w) { return w + (batchim(w) ? "이" : "가"); }
    public static String eul(String w) { return w + (batchim(w) ? "을" : "를"); }
    static String clean(String s, int max) { String t = s == null ? "" : s.replace('\u0000', ' ').strip(); return t.length() > max ? t.substring(0, max).strip() : t; }
    public static String clip(String s, int max) { String t = s == null ? "" : s.strip(); return t.length() > max ? t.substring(0, max) + " …" : t; }
    static String normal(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""); }
    private static String safe(String id) { return id == null ? "_" : id.replaceAll("[^A-Za-z0-9_-]", "_"); }
    /**
     * The small files (projects, state, sources, ledger, report) as last read or written: the same string back while the
     * file keeps its size and time, so a screen asking ten times reads the disk once. What this book writes goes in as it
     * is written; a file changed from outside (a backup put back) has another size or time and is read again.
     */
    private static final Map<String, Object[]> texts = new java.util.HashMap<>();
    private static String read(File file, String empty) throws IOException {
        if (!file.isFile()) { synchronized (texts) { texts.remove(file.getPath()); } return empty; }
        long size = file.length(), time = file.lastModified();
        synchronized (texts) { Object[] kept = texts.get(file.getPath()); if (kept != null && (long) kept[0] == size && (long) kept[1] == time) return (String) kept[2]; }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        synchronized (texts) { texts.put(file.getPath(), new Object[]{size, time, text}); }
        return text;
    }
    private static void write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.write(temp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        synchronized (texts) { texts.put(file.getPath(), new Object[]{file.length(), file.lastModified(), text}); }
    }
    static void delete(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() && !Files.isSymbolicLink(f.toPath()) ? f.listFiles() : null;
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}
