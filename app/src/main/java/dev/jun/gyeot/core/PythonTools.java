package dev.jun.gyeot.core;

import java.util.regex.Pattern;
import org.json.JSONArray;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * python_run for the 작업창's models and the main chat's: Python 3 in the app's own Linux on this phone (the 계산 환경 that
 * 연구실 prepares: numpy, scipy, sympy, mpmath, matplotlib, pandas), for calculations, statistics, simulations and charts the
 * model should not do in its head. No network inside, 150 seconds at most; figures saved in out/ go to 들퍼레/이미지.
 */
public final class PythonTools {
    private PythonTools() {}
    public static final String RUN = "python_run";
    public static boolean is(String name) { return RUN.equals(name); }
    public static String stage(String name) { return RUN.equals(name) ? "휴대폰에서 Python을 돌리는 중" : ""; }

    /** Words that ask for a calculation, numbers or a chart (the main chat offers python_run then). */
    private static final Pattern WANTED = Pattern.compile("(?i)계산|그래프|차트|플롯|도표|통계|평균|중앙값|분산|표준편차|상관|회귀|확률|시뮬레이|몬테카를로|파이썬|python|코드[ \\t]*(?:실행|돌려)"
        + "|적분|미분|방정식|행렬|수식|데이터|엑셀|csv|합계|퍼센트|비율|복리|이자|수익률|환산|몇[ \\t]*배|[0-9][0-9,.]*[ \\t]*[+\\-*/×÷^][ \\t]*[0-9]");
    public static boolean wanted(String text) { return text != null && WANTED.matcher(text).find(); }
    /** Words that ask for a picture of the numbers (a chart): python_run makes it on the phone so the user can see it. */
    private static final Pattern CHART = Pattern.compile("(?i)그래프|차트|플롯|도표|그려|시각화|plot|chart|graph");
    public static boolean chart(String text) { return text != null && CHART.matcher(text).find(); }

    public static final String INSTRUCTIONS = " Python 3 runs on the user's phone (python_run, in the app's own Linux, with numpy, scipy, sympy, mpmath, matplotlib and pandas; "
        + "no internet inside, 150 seconds at most). Use it on your own whenever an answer needs exact arithmetic, statistics, a simulation, a table worked out or a chart, "
        + "instead of working numbers out in your head: print the results plainly, and save a chart with matplotlib to out/ as PNG (English labels; there is no Korean font), "
        + "which the app keeps in 들퍼레/이미지 (a chart made by your own server-side code execution is not shown to the user, so make charts with python_run). If it fails, read the error and run a fixed version. Report the numbers python_run printed, not guesses.";

    public static JSONArray declarations() {
        return arr(obj("name", RUN, "description", "Run a Python 3 program on the user's phone and get what it printed (the end, at most 8,000 characters) and the charts it saved in out/. "
                + "Packages: the standard library, numpy, scipy, sympy, mpmath, matplotlib, pandas. No internet. At most 150 seconds.",
            "parameters", obj("type", "object", "required", arr("code"), "properties", obj(
                "code", obj("type", "string", "description", "The whole program; print the results; save charts as out/<name>.png"),
                "purpose", obj("type", "string", "description", "A few words saying what it works out, shown to the user while it runs")))));
    }
}
