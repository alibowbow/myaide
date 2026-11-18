package dev.jun.gyeot;

import static dev.jun.gyeot.Ui.*;
import static dev.jun.gyeot.core.Jsons.*;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Search by course, achievement code, content or unit. One selected context belongs to one board.
 */
final class BoardClassroom {
    static final String[] SUBJECTS = {
        "국어", "물리", "화학", "지구과학", "생명과학 (생물)", "영어", "수학", "한국사 (국사)", "세계사"
    };
    static final String[] GRADES = {"중1", "중2", "중3", "고1", "고2", "고3"};
    static final String[] MODES = {"개념 수업", "문제 풀이", "자료 분석", "시험 대비", "오답 해설"};
    private static final String TAGS = "KPCEBLMHW";
    private static final String[] GUIDES = {
        "작품·지문의 표현이나 논증 근거를 짚고 원문과 해설을 나누어 읽는다.",
        "힘의 방향과 계를 도해로 그리고 변인·가정·단위를 명시한 그래프로 예측을 검토한다.",
        "화학식을 표시하고 입자 모형과 원자 수, 물질량 또는 실험 자료를 대조한다.",
        "공간 규모와 시간 규모를 밝히고 지도·단면·시계열에서 관측 근거를 찾는다.",
        "세포·생태계의 구조와 기능을 설명하고 구조 그림의 해당 부위를 차례로 강조한다.",
        "영어 원문과 한국어 해설을 나누고 문장 구조·담화 기능·독해 근거를 표시한다.",
        "식·그래프·좌표·도형 사이를 연결하고 정의역·예외와 결과를 검산한다.",
        "사료의 시기와 작성 주체를 밝히고 한반도 지도·연표로 사건의 인과와 관점을 대조한다.",
        "지역별 동시대 사건을 연표로 비교하고 교역·이동은 지도에 표시하며 사료의 관점을 읽는다."
    };

    record Standard(
            String key,
            String code,
            String school,
            String tags,
            String course,
            String area,
            String content,
            String book,
            String edition) {
        boolean matches(String query) {
            String hay =
                    (code + " " + course + " " + area + " " + content)
                            .replace(" ", "")
                            .toLowerCase(Locale.ROOT);
            for (String term : query.strip().toLowerCase(Locale.ROOT).split("\\s+"))
                if (!hay.contains(term)) return false;
            return true;
        }
    }

    static List<Standard> read(BufferedReader reader, String edition) throws java.io.IOException {
        List<Standard> out = new ArrayList<>();
        String row;
        while ((row = reader.readLine()) != null) {
            String[] c = row.split("\\t", -1);
            if (c.length != 8) throw new java.io.IOException("교과 기준 자료 형식 오류");
            out.add(new Standard(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], edition));
        }
        return out;
    }

    private final Ui ui;
    private final List<Standard> catalog = new ArrayList<>();

    BoardClassroom(android.content.Context context, Ui ui) throws java.io.IOException {
        this.ui = ui;
        for (String edition : List.of("2015", "2022")) {
            String file = edition.equals("2022") ? "standards.tsv" : "standards2015.tsv";
            try (var in =
                    new BufferedReader(
                            new InputStreamReader(
                                    context.getAssets().open("board/" + file),
                                    StandardCharsets.UTF_8))) {
                catalog.addAll(read(in, edition));
            }
        }
    }

    static int edition(int grade, LocalDate date) {
        int schoolYear = date.getYear() - (date.getMonthValue() < 3 ? 1 : 0);
        return schoolYear >= 2025 + grade % 3 ? 2022 : 2015;
    }

    static String summary(JSONObject ctx) {
        if (ctx == null || ctx.length() == 0) return "";
        return GRADES[index(ctx, "grade", GRADES.length)]
                + " "
                + SUBJECTS[index(ctx, "subject", SUBJECTS.length)]
                + " · "
                + ctx.optString("topic");
    }

    static String instruction(JSONObject ctx) {
        if (ctx == null || ctx.length() == 0) return "";
        return "\n교과 설정: "
                + summary(ctx)
                + " · "
                + ctx.optString("edition")
                + " 개정 · "
                + MODES[index(ctx, "mode", MODES.length)]
                + "\n"
                + GUIDES[index(ctx, "subject", SUBJECTS.length)]
                + (ctx.has("code")
                        ? "\n선택한 성취기준 " + ctx.optString("code") + " " + ctx.optString("content")
                        : "")
                + "\n학년 수준에 맞춰 예시와 확인 문제를 구성한다. 자유 주제에 성취기준 코드를 지어내지 않는다. 학습자의 이번 질문을 우선한다.";
    }

    private static int index(JSONObject o, String key, int count) {
        return Math.max(0, Math.min(count - 1, o.optInt(key)));
    }

    Ui.Sheet open(JSONObject saved, Consumer<JSONObject> apply) {
        return new Picker(saved, apply).sheet;
    }

    private final class Picker {
        final Ui.Sheet sheet;
        final Consumer<JSONObject> apply;
        final EditText query;
        final LinearLayout results, editions;
        final TextView chosen, count;
        int grade, subject, mode, year, limit = 10;
        boolean auto;
        Standard selected;

        Picker(JSONObject saved, Consumer<JSONObject> apply) {
            this.apply = apply;
            grade = index(saved, "grade", 6);
            subject = index(saved, "subject", 9);
            mode = index(saved, "mode", 5);
            auto = saved.optBoolean("auto", true);
            year = auto ? defaultYear() : saved.optInt("edition", 2022);
            sheet = ui.selectionSheet("교과 수업", "단원·성취기준을 찾거나 배우고 싶은 주제를 직접 입력하세요.");
            sheet.body.addView(ui.sectionHeader("학년 · 교과"));
            LinearLayout filters = ui.row();
            sheet.body.addView(filters);
            filters.addView(
                    ui.spinner(
                            "학년",
                            GRADES,
                            grade,
                            i -> {
                                grade = i;
                                if (auto) year = defaultYear();
                                reset();
                            }),
                    new LinearLayout.LayoutParams(0, -2, 1));
            filters.addView(
                    ui.spinner(
                            "교과",
                            SUBJECTS,
                            subject,
                            i -> {
                                subject = i;
                                reset();
                            }),
                    new LinearLayout.LayoutParams(0, -2, 1.65f));
            editions = ui.column();
            sheet.body.addView(editions);
            paintEdition();
            sheet.body.addView(ui.sectionHeader("수업 방식"));
            sheet.body.addView(ui.spinner("수업 방식", MODES, mode, i -> mode = i));
            sheet.body.addView(ui.sectionHeader("주제 또는 성취기준"));
            query = ui.input("예: 판 구조론, 미분, 비유, 9역", 1, 280);
            sheet.body.addView(query);
            if (saved.has("key"))
                for (Standard s : catalog)
                    if (s.edition.equals(String.valueOf(year))
                            && s.key.equals(saved.optString("key"))) {
                        selected = s;
                        break;
                    }
            if (selected == null) query.setText(saved.optString("topic"));
            chosen = ui.text("", CALLOUT, 600, ACCENT);
            sheet.body.addView(chosen);
            count = ui.footer("");
            sheet.body.addView(count);
            results = ui.column();
            sheet.body.addView(results);
            query.addTextChangedListener(
                    new TextWatcher() {
                        public void afterTextChanged(Editable e) {
                            selected = null;
                            limit = 10;
                            paint();
                        }

                        public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                        public void onTextChanged(CharSequence s, int a, int before, int n) {}
                    });
            sheet.primary.setText("이 주제로 판서하기");
            sheet.primary.setOnClickListener(v -> start());
            if (saved.length() > 0)
                sheet.body.addView(
                        ui.button(
                                "교과 설정 해제",
                                false,
                                () -> {
                                    sheet.dismiss();
                                    apply.accept(obj());
                                }));
            sheet.body.addView(
                    ui.footer(
                            "2022 교육부 고시 제2022-33호 · greatsong/k-curriculum-2022 (CC BY 4.0)\n"
                                    + "2015 교육부 고시 제2015-74호 · 역사 제2018-162호"));
            paint();
        }

        int defaultYear() {
            return edition(grade, LocalDate.now(ZoneId.of("Asia/Seoul")));
        }

        void paintEdition() {
            editions.removeAllViews();
            String[] choices = {"학년 기준 · " + defaultYear(), "2015 개정", "2022 개정"};
            editions.addView(
                    ui.spinner(
                            "교육과정",
                            choices,
                            auto ? 0 : year == 2015 ? 1 : 2,
                            i -> {
                                auto = i == 0;
                                year = auto ? defaultYear() : i == 1 ? 2015 : 2022;
                                reset();
                            }));
        }

        void reset() {
            selected = null;
            limit = 10;
            paintEdition();
            paint();
        }

        void paint() {
            results.removeAllViews();
            String text = query.getText().toString().strip(),
                    tag = TAGS.substring(subject, subject + 1);
            String school = grade < 3 ? "M" : "H", edition = String.valueOf(year);
            int hits = 0;
            for (Standard s : catalog)
                if (s.school.equals(school)
                        && s.tags.contains(tag)
                        && s.edition.equals(edition)
                        && s.matches(text)) {
                    if (hits++ >= limit) continue;
                    results.addView(
                            ui.listRow(
                                    R.drawable.ic_book,
                                    BLUE,
                                    s.course + " · " + s.area,
                                    s.code + " " + s.content,
                                    selected == s ? ui.badge("선택", GREEN) : null,
                                    () -> {
                                        selected = s;
                                        paint();
                                    }));
                }
            count.setText("성취기준 " + hits + "개" + (hits == 0 ? " · 입력한 주제로 시작할 수 있어요" : ""));
            if (hits > limit)
                results.addView(
                        ui.button(
                                "더 보기",
                                false,
                                () -> {
                                    limit += 15;
                                    paint();
                                }));
            chosen.setText(
                    selected == null ? "" : "선택한 기준  " + selected.code + " " + selected.content);
            chosen.setVisibility(selected == null ? View.GONE : View.VISIBLE);
            sheet.primary.setEnabled(selected != null || !text.isBlank());
        }

        void start() {
            JSONObject cfg =
                    obj(
                            "grade",
                            grade,
                            "subject",
                            subject,
                            "mode",
                            mode,
                            "edition",
                            year,
                            "auto",
                            auto,
                            "topic",
                            selected == null
                                    ? query.getText().toString().strip()
                                    : selected.course + " · " + selected.area);
            if (selected != null) {
                put(cfg, "key", selected.key);
                put(cfg, "code", selected.code);
                put(cfg, "content", selected.content);
            }
            sheet.dismiss();
            apply.accept(cfg);
        }
    }
}
