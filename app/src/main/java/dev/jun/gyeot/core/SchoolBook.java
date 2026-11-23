package dev.jun.gyeot.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 나의학교: the teachers the user made (name, subject, how they teach, the learner's level, which AI answers) and each one's
 * 1:1 lesson, kept on this phone in one folder: teachers.json, chats/<id>.json and avatars/<id>.png. Nothing here talks to
 * the network; SchoolActivity asks the AI with what tutor() and history() give.
 */
public final class SchoolBook {
    /** One teacher. ai: "groq" (Groq's free GPT OSS 120B), "agy" (Antigravity, the user's subscription) or "app" (the teacher's own choice of the app's AIs). */
    public record Teacher(String id, String name, String subject, String style, String level, String ai, long made, String method) {
        public Teacher { name = clean(name, 30); subject = clean(subject, 40); style = clean(style, 600); level = clean(level, 80); ai = "app".equals(ai) || "agy".equals(ai) ? ai : "groq"; method = SchoolBook.method(method).id(); }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("subject", subject).put("style", style).put("level", level).put("ai", ai).put("made", made).put("method", method);
        }
        static Teacher of(JSONObject o) {
            return new Teacher(o.optString("id"), o.optString("name"), o.optString("subject"), o.optString("style"), o.optString("level"), o.optString("ai"), o.optLong("made"), o.optString("method"));
        }
        public Teacher with(String name, String subject, String style, String level, String method) { return new Teacher(id, name, subject, style, level, ai, made, method); }
        public Teacher ai(String value) { return new Teacher(id, name, subject, style, level, value, made, method); }
    }
    /**
     * A teaching method preset: how the lesson goes (lesson replaces the plain 과외 방식) and the ways to begin an empty lesson.
     * TOP_DOWN is Gabriel Petersson's way of learning with an AI tutor: the goal first, gaps chased down recursively, plain-words
     * translation, explaining back (Feynman), and trying everything at once.
     */
    public record Method(String id, String name, String about, String lesson, String[] starts) {}
    public static final Method PLAIN = new Method("", "기본 과외", "한 번에 한 개념씩 설명하고, 확인 질문과 연습 문제로 이해를 점검해요.", """
        과외 방식:
        - 한 번에 한 개념씩, 학생 수준에 맞춰 짧고 또렷하게 설명해. 쉬운 예시나 비유를 하나 곁들여.
        - 설명이 끝나면 이해했는지 확인하는 짧은 질문이나 연습 문제를 하나 내. 학생이 답하면 맞은 점을 칭찬하고, 틀린 점은 왜 틀렸는지 힌트부터 줘.
        - 숙제·문제 풀이를 물으면 정답만 주지 말고 스스로 풀 수 있게 단계별로 이끌어 줘. 학생이 답을 원하면 그때 풀이를 보여 줘.
        - 학생이 무엇을 배울지 모르면 과목에 맞는 시작점 두세 개를 제안해.
        """, new String[]{"오늘은 뭘 배우면 좋을까요?", "%s기초부터 차근차근 알려 주세요", "제 실력을 간단히 테스트해 주세요"});
    public static final Method TOP_DOWN = new Method("topdown", "목표부터 거꾸로 · 페테르손식",
        "가브리엘 페테르손이 AI로 공부한 방식: 만들 결과물부터 정하고, 해 보다 막히는 곳을 거꾸로 파고들어 빈틈을 메워요. 쉬운 말로 풀고, 내 말로 다시 설명하고, 바로 실험해요.", """
        지도법: 목표부터 거꾸로 배우기(하향식 프로젝트 학습). 이 순서와 원칙을 지켜.
        0. 과목에 맞추기: 모든 목표·과제·실험은 반드시 가르치는 과목 안에서 정해. 코딩 과목이 아니면 절대 프로그래밍이나 코드를 꺼내지 마(학생이 먼저 원할 때만). 예를 들어 외국어면 그 언어로 실제로 해낼 일(식당에서 주문하기, 자기소개 말하기, 쉬운 뉴스 한 편 읽기, 좋아하는 노래 가사 이해하기), 수학이면 실제로 풀고 싶은 문제, 역사면 답하고 싶은 큰 질문, 악기면 치고 싶은 곡, 코딩이면 만들고 싶은 프로그램이야.
        1. 하향식 프로젝트: 1장부터 기초를 가르치지 마. 먼저 학생이 그 과목으로 실제로 해내고 싶은 최종 목표를 함께 정해. 목표가 정해지면 그 목표에 바로 다가가는 가장 작은 '해 볼 수 있는 첫 단계'부터 줘(외국어면 목표 장면에서 쓸 진짜 문장 하나를 바로 말해 보기·써 보기). 기초는 필요해질 때 배워.
        2. 꼬리물기 역추적: 학생이 막히거나 '왜?'라고 물으면 그 지점을 단서로 한 층씩 아래로 파고들어: 실제 쓰임 → 규칙·패턴(원리) → 심화(예외·뉘앙스·더 깊은 이론) → 바탕이 되는 기초(외국어면 문법·어휘·발음, 수학·과학이면 기초 개념, 코딩이면 알고리즘·수학). 한 번에 한 층만 내려가고, 지금 어느 층인지 앞에 짧게 표시해(예: [역추적 2층 · 규칙]). 빈틈이 메워지면 '이제 목표로 돌아가요'라며 원래 단계로 돌아와.
        3. 눈높이 번역·시각화: 어려운 개념은 먼저 12살에게 설명하듯 직관과 비유로 풀고, 그다음 정확한 규칙이나 정의로 정리해. 표·도식이 도움이 되면 글로 간단히 그리거나 판서를 권해.
        4. 역설명(파인만 기법): 하나를 익히면 학생에게 자기 말로 다시 설명해 보라고 해(외국어면 규칙을 자기 말로 설명하거나 새 예문을 직접 만들어 보기). 학생이 하면 틀린 점과 빠진 점을 냉정하고 구체적으로 지적하고, 정확한 부분은 짚어 확인해 줘. 듣기 좋은 칭찬으로 넘기지 마.
        5. 즉각 실험: 눈으로만 익히게 두지 마. 매번 그 과목에 맞는 작은 실험 하나를 내(외국어: 단어 하나·어미 하나를 바꾼 문장의 뜻을 먼저 예측하고 확인하기, 직접 말하거나 써서 보내기 / 수학·과학: 값을 바꾸면 어떻게 될지 예측하고 직접 계산 / 코딩: 코드를 고쳐 실행). 학생이 결과를 알려 주면 그 결과로 다음 설명을 이어가.
        - 질문을 많이 하도록 격려해. 하루 수십 개의 질문도 환영이고, 사소한 질문일수록 반겨.
        """, new String[]{"제 최종 목표부터 함께 정해 주세요", "바로 해 볼 수 있는 첫 단계를 주세요", "방금 배운 걸 제 말로 설명할게요, 냉정하게 지적해 주세요"});
    public static final Method SOCRATIC = new Method("socratic", "질문으로 깨닫기 · 소크라테스식",
        "답을 알려 주지 않고 질문만으로 이끌어요. 내 생각을 꺼내고, 반례로 흔들고, 스스로 더 나은 답을 세우게 해요. 막히면 힌트를 달라고 하면 돼요.", """
        지도법: 질문으로 깨닫기(소크라테스식 문답법). 이 원칙을 지켜.
        1. 답 대신 질문: 설명하거나 정답을 먼저 말하지 마. 매 답은 짧은 반응 한두 문장과 질문 딱 하나로 끝내. 질문을 여러 개 늘어놓지 마.
        2. 내 생각부터: 새 주제는 '이게 뭐라고 생각해요?', '왜 그럴 것 같아요?'처럼 학생이 이미 가진 생각·정의를 먼저 꺼내게 해.
        3. 반례로 흔들기(논박): 학생 답에 허점이 있으면 틀렸다고 말하지 말고, 그 생각이 들어맞지 않는 예를 질문으로 보여 줘(예: '그럼 ~한 경우에도 그럴까요?').
        4. 모순 짚기: 학생의 말이 앞뒤로 부딪히면 그대로 짚어 줘(예: '아까는 A라고 했는데 지금은 B네요. 어느 쪽일까요?'). 모른다는 걸 깨닫는 순간을 반겨.
        5. 다시 세우기(산파술): 흔들린 뒤엔 학생이 더 나은 정의나 규칙을 직접 말하게 이끌어. 학생이 스스로 맞게 말하면 그때 짧게 인정하고 정확한 말로 다듬어 줘.
        6. 디딤돌: 학생이 막히면 답을 주지 말고 더 쉽고 구체적인 질문으로 한 칸 내려가.
        7. 막힘 탈출구: 같은 지점에서 다섯 번 넘게 돌거나, 학생이 '힌트', '그냥 알려 줘', '모르겠어'라고 하면 그때만 짧은 힌트나 설명을 주고, 곧바로 다시 질문으로 돌아와.
        8. 마무리: 결론에 닿으면 '오늘 스스로 찾아낸 것'을 세 줄로 정리해 줘.
        - 답마다 맨 앞에 지금 단계를 짧게 붙여: [1단계 · 내 생각 꺼내기], [2단계 · 반례로 흔들기], [3단계 · 다시 세우기], [정리].
        - 단어 암기처럼 질문으로 이끌 수 없는 사실은 짧게 알려 주고, 그 사실로 생각해 볼 질문을 이어가.
        """, new String[]{"제가 아는 걸 먼저 말해 볼게요", "이 주제를 질문으로만 이끌어 주세요", "제 생각에 반론을 던져 주세요"});
    public static final List<Method> METHODS = List.of(PLAIN, TOP_DOWN, SOCRATIC);
    public static Method method(String id) { for (Method m : METHODS) if (m.id().equals(id)) return m; return PLAIN; }
    /** One line of a lesson: "user" (the learner) or "teacher", with who answered ("Groq · GPT OSS 120B"). */
    public record Line(String role, String text, long at, String by) {
        JSONObject json() throws Exception { return new JSONObject().put("role", role).put("text", text).put("at", at).put("by", by == null ? "" : by); }
        static Line of(JSONObject o) { return new Line(o.optString("role"), o.optString("text"), o.optLong("at"), o.optString("by")); }
        public boolean mine() { return "user".equals(role); }
    }
    public static final int LINES = 400, TEACHERS = 60;
    private final File root;
    public SchoolBook(File root) { this.root = root; }

    public synchronized List<Teacher> teachers() {
        List<Teacher> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(read(new File(root, "teachers.json"), "[]"));
            for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && !o.optString("id").isBlank()) out.add(Teacher.of(o)); }
        } catch (Exception unreadable) { /* an empty school */ }
        return out;
    }
    public synchronized Teacher teacher(String id) { for (Teacher t : teachers()) if (t.id().equals(id)) return t; return null; }
    /** A new teacher, last in the list. */
    public synchronized Teacher add(String name, String subject, String style, String level, String ai) throws IOException { return add(name, subject, style, level, ai, ""); }
    public synchronized Teacher add(String name, String subject, String style, String level, String ai, String method) throws IOException {
        List<Teacher> all = teachers();
        if (all.size() >= TEACHERS) throw new IOException("선생님은 " + TEACHERS + "명까지 모실 수 있어요.");
        Teacher made = new Teacher(UUID.randomUUID().toString().replace("-", "").substring(0, 12), name, subject, style, level, ai, System.currentTimeMillis(), method);
        if (made.name().isBlank()) throw new IOException("선생님 이름을 적어 주세요.");
        all.add(made); keep(all); return made;
    }
    public synchronized void save(Teacher changed) throws IOException {
        if (changed.name().isBlank()) throw new IOException("선생님 이름을 적어 주세요.");
        List<Teacher> all = teachers(); boolean found = false;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(changed.id())) { all.set(i, changed); found = true; }
        if (!found) throw new IOException("이 선생님을 찾지 못했어요.");
        keep(all);
    }
    /** The teacher, their lesson and picture, gone. */
    public synchronized void remove(String id) throws IOException {
        List<Teacher> all = teachers(); all.removeIf(t -> t.id().equals(id)); keep(all);
        new File(root, "chats/" + id + ".json").delete(); avatar(id).delete();
    }
    private void keep(List<Teacher> all) throws IOException {
        JSONArray list = new JSONArray();
        try { for (Teacher t : all) list.put(t.json()); } catch (Exception e) { throw new IOException("선생님 목록을 저장하지 못했어요.", e); }
        write(new File(root, "teachers.json"), list.toString());
    }

    public File avatar(String id) { return new File(root, "avatars/" + id + ".png"); }
    public synchronized void avatar(String id, byte[] png) throws IOException {
        File target = avatar(id);
        if (png == null) { target.delete(); return; }
        writeBytes(target, png);
    }

    public synchronized List<Line> lines(String id) {
        List<Line> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(read(new File(root, "chats/" + id + ".json"), "[]"));
            for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null) out.add(Line.of(o)); }
        } catch (Exception unreadable) { /* a new lesson */ }
        return out;
    }
    public synchronized void say(String id, String role, String text, String by) throws IOException {
        List<Line> all = lines(id); all.add(new Line(role, text, System.currentTimeMillis(), by));
        while (all.size() > LINES) all.remove(0);
        JSONArray list = new JSONArray();
        try { for (Line l : all) list.put(l.json()); } catch (Exception e) { throw new IOException("대화를 저장하지 못했어요.", e); }
        write(new File(root, "chats/" + id + ".json"), list.toString());
    }
    public synchronized void clear(String id) { new File(root, "chats/" + id + ".json").delete(); }

    /** What the teacher is told: who they are, whom they teach and how a 1:1 lesson goes. */
    public static String tutor(Teacher t) {
        StringBuilder out = new StringBuilder();
        out.append("너는 들퍼레 앱 '나의학교'의 ").append(t.name()).append(" 선생님이야. 학생 한 명과 1:1 과외를 하고 있어.\n");
        if (!t.subject().isBlank()) out.append("가르치는 과목·분야: ").append(t.subject()).append('\n');
        if (!t.level().isBlank()) out.append("학생 수준: ").append(t.level()).append('\n');
        if (!t.style().isBlank()) out.append("선생님의 성격·말투·가르치는 방식(이대로 연기해): ").append(t.style()).append('\n');
        out.append(method(t.method()).lesson());
        out.append("""
            공통:
            - 모바일 화면이니 길게 늘어놓지 말고, 필요하면 짧은 목록이나 굵은 글씨로 핵심만 정리해. 수식은 평범한 글자로 써(예: x^2 + 3x = 0).
            - 그림이나 칠판 설명이 도움이 될 주제면 '판서 버튼을 누르면 칠판으로 정리해 줄게요'라고 한 줄 덧붙여도 좋아.
            - 학생과 같은 언어로 답하고, 선생님 캐릭터를 끝까지 지켜.""");
        return out.toString();
    }
    /** The lesson so far as role/text pairs, newest kept, at most chars long in all (oldest dropped first). */
    public static List<Line> history(List<Line> all, int chars) {
        List<Line> out = new ArrayList<>(); int used = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            Line l = all.get(i); int size = l.text().length();
            if (!out.isEmpty() && used + size > chars) break;
            String text = size > chars ? l.text().substring(0, chars) + "…" : l.text();
            out.add(0, new Line(l.role(), text, l.at(), l.by())); used += Math.min(size, chars);
        }
        return out;
    }
    /** The lesson written out for an AI that takes one input (학생:/선생님: lines), the new question last. */
    public static String transcript(List<Line> lines) {
        StringBuilder out = new StringBuilder();
        for (Line l : lines) out.append(l.mine() ? "학생: " : "선생님: ").append(l.text()).append("\n\n");
        out.append("위 대화에 이어 선생님으로서 학생의 마지막 말에 답해.");
        return out.toString();
    }
    /** What 판서's question field is filled with from the lesson (sent only by the user): the last question, with its subject. */
    public static String boardDraft(Teacher t, List<Line> lines) {
        for (int i = lines.size() - 1; i >= 0; i--) if (lines.get(i).mine()) { String q = lines.get(i).text().strip(); if (q.length() > 300) q = q.substring(0, 300); return (t.subject().isBlank() ? "" : "[" + t.subject() + "] ") + q; }
        return "";
    }
    /** What 판서 is asked to prepare from the lesson: the topic now, with the last explanation as context. */
    public static String boardAsk(Teacher t, List<Line> lines) {
        String question = "", answer = "";
        for (int i = lines.size() - 1; i >= 0 && (question.isEmpty() || answer.isEmpty()); i--) {
            Line l = lines.get(i);
            if (l.mine() && question.isEmpty()) question = l.text();
            else if (!l.mine() && answer.isEmpty()) answer = l.text();
        }
        if (question.isEmpty()) return t.subject().isBlank() ? "" : t.subject() + " 기초 개념";
        String ask = (t.subject().isBlank() ? "" : "[" + t.subject() + "] ") + question;
        if (!answer.isEmpty()) ask += "\n\n(과외에서 선생님이 설명한 내용: " + (answer.length() > 700 ? answer.substring(0, 700) + "…" : answer) + ")";
        if (!t.level().isBlank()) ask += "\n학생 수준: " + t.level();
        return ask;
    }

    /** AI 자동 채우기: what the AI is told; it answers only with JSON {name, subject, level, style}. */
    public static final String FILL = """
        너는 들퍼레 앱 '나의학교'에서 사용자가 만들 과외 선생님 프로필을 채워 주는 도우미야.
        입력으로 사용자가 이미 적은 칸과 비어 있는 칸이 와. 이미 적은 칸은 그대로 두고, 비어 있는 칸만 적은 내용과 어울리게 채워.
        모든 칸이 비어 있으면 배우면 재미있고 쓸모 있을 과목의 개성 있는 선생님을 하나 지어내.
        - name: 한국어 이름 하나(성+이름, 2~4글자). 과목·말투에 어울리게. 실존 유명인 이름은 쓰지 마.
        - subject: 과목·분야, 짧게(예: 중학 수학, 영어 회화, 파이썬 입문). 30자 이내.
        - level: 학생 수준, 짧게(예: 중학교 2학년, 회화 초급, 완전 처음). 이미 적은 과목에 맞게. 40자 이내.
        - style: 선생님의 성격·말투·가르치는 방식 2~3문장. 어떻게 설명하고, 어떻게 확인하고(퀴즈·질문), 어떤 말투인지. 200자 이내.
        반드시 JSON 객체 하나만 답해. 설명이나 코드 블록 없이: {"name":"…","subject":"…","level":"…","style":"…"}""";
    /** The form for FILL: each field as written, "(비어 있음)" for the empty ones. */
    public static String fillInput(String name, String subject, String level, String style) {
        return "name: " + shown(name) + "\nsubject: " + shown(subject) + "\nlevel: " + shown(level) + "\nstyle: " + shown(style);
    }
    private static String shown(String v) { return v == null || v.isBlank() ? "(비어 있음)" : v.strip(); }
    /** The AI's JSON read leniently (a code fence or words around it are skipped); {name, subject, level, style}, "" where missing. */
    public static String[] parseFill(String answer) {
        String text = answer == null ? "" : answer; int start = text.indexOf('{'), end = text.lastIndexOf('}');
        String[] out = {"", "", "", ""};
        if (start < 0 || end <= start) return out;
        try {
            JSONObject o = new JSONObject(text.substring(start, end + 1));
            out[0] = clean(o.optString("name"), 30); out[1] = clean(o.optString("subject"), 40); out[2] = clean(o.optString("level"), 80); out[3] = clean(o.optString("style"), 600);
        } catch (Exception unreadable) { /* nothing filled */ }
        return out;
    }

    static String clean(String value, int limit) { String v = value == null ? "" : value.strip(); return v.length() > limit ? v.substring(0, limit) : v; }
    private static String read(File file, String empty) throws IOException { return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : empty; }
    private static void write(File file, String text) throws IOException { writeBytes(file, text.getBytes(StandardCharsets.UTF_8)); }
    private static void writeBytes(File file, byte[] bytes) throws IOException {
        File folder = file.getParentFile(); if (folder != null && !folder.isDirectory() && !folder.mkdirs()) throw new IOException("나의학교 폴더를 만들지 못했어요.");
        File temp = new File(folder, file.getName() + ".tmp");
        Files.write(temp.toPath(), bytes);
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
