package dev.jun.gyeot;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.GitHubRepos;
import java.util.List;
import java.util.function.Consumer;
import static dev.jun.gyeot.Ui.*;

/**
 * The GitHub repository picker, as in Claude Code: a sheet with 저장소 검색 and a refresh button on top, the chosen
 * repository first with a check, the user's other repositories (most recently pushed first, read with GitHubRepoList),
 * an owner/name typed that GitHub did not list, and 저장소 없음 last. It opens at once with the list read last and
 * refreshes behind it. The caller decides what a choice changes (a 작업창 room, or the default for new rooms).
 * Without a token, or with 작업창·메인 대화에서 GitHub 쓰기 off, it says so instead, reads nothing, and offers
 * 설정 → 커넥터 → GitHub when the caller gave the way there.
 */
final class GitHubRepoPicker {
    private final Activity activity;
    private final Ui ui;
    private final AppStore store;
    private final String chosen;
    /** Opens 설정 → 커넥터 → GitHub; null when the picker is opened from that page. */
    private final Runnable settings;
    private final Consumer<String> choose;
    private final Ui.Sheet sheet;
    private final EditText search;
    private List<GitHubRepos.Repo> repos = List.of();
    private boolean loading;
    private String error = "";
    private Runnable stop;

    /** chosen is "owner/name" or "" (none); choose gets the new choice, "" for 저장소 없음, after the sheet closes. */
    GitHubRepoPicker(Activity activity, Ui ui, AppStore store, String title, String subtitle, String chosen, Consumer<String> choose) {
        this(activity, ui, store, title, subtitle, chosen, null, choose);
    }
    /** The same with settings, which opens 설정 → 커넥터 → GitHub: offered when there is nothing to pick from and beside a refused token. */
    GitHubRepoPicker(Activity activity, Ui ui, AppStore store, String title, String subtitle, String chosen, Runnable settings, Consumer<String> choose) {
        this.activity = activity; this.ui = ui; this.store = store; this.chosen = chosen == null ? "" : chosen; this.settings = settings; this.choose = choose;
        sheet = ui.selectionSheet(title, subtitle); sheet.primary.setVisibility(View.GONE);
        sheet.primary.setText("GitHub 설정 열기"); sheet.primary.setOnClickListener(v -> openSettings());
        LinearLayout top = ui.row();
        search = ui.searchField(ui.input("저장소 검색…", 1, 200)); search.setTextSize(15); search.setContentDescription("저장소 이름 검색");
        top.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView refresh = ui.quietIcon(R.drawable.ic_refresh, "저장소 목록 새로 불러오기", this::refresh);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); rp.leftMargin = ui.dp(4); top.addView(refresh, rp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.topMargin = ui.dp(10); sheet.controls.addView(top, tp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { fill(); }
        });
        sheet.setOnDismissListener(d -> { if (stop != null) stop.run(); });
    }

    void show() {
        // Nothing is read, not even the kept list, while there is nothing to pick from.
        boolean usable = blocked().isEmpty();
        if (usable) repos = GitHubRepoList.cached(store);
        fill(); sheet.show();
        if (usable && (repos.isEmpty() || GitHubRepoList.stale(store, System.currentTimeMillis()))) refresh();
    }

    /** Why there is nothing to pick from: no token, or 작업창·메인 대화에서 GitHub 쓰기 off; "" when the list can be read. No token is decrypted. */
    private String blocked() {
        if (!store.githubConnected()) return "GitHub가 연결돼 있지 않아요. 토큰을 연결하면 내 저장소가 여기에 보여요.";
        if (!store.settings.getBoolean("githubCoding", true)) return "GitHub 쓰기가 꺼져 있어요. 켜면 작업창에서 저장소를 골라요.";
        return "";
    }

    private void openSettings() { sheet.dismiss(); if (settings != null) settings.run(); }

    private void refresh() {
        if (loading) return;
        loading = true; error = ""; fill();
        stop = GitHubRepoList.refresh(store, (list, reason) -> {
            loading = false; stop = null;
            if (list != null) repos = list; else error = reason;
            if (sheet.isShowing()) fill();
        });
    }

    /**
     * The list for what is typed: the chosen one first, then the others; a typed owner/name; 저장소 없음 last. Without a
     * token or with GitHub 쓰기 off (also when a read ends after the token was removed), only why, and the way to 설정.
     */
    private void fill() {
        sheet.body.removeAllViews();
        String off = blocked();
        sheet.controls.setVisibility(off.isEmpty() ? View.VISIBLE : View.GONE);
        sheet.primary.setVisibility(off.isEmpty() || settings == null ? View.GONE : View.VISIBLE);
        if (!off.isEmpty()) { TextView why = ui.label(off, 13, MUTED); why.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(8)); sheet.body.addView(why); return; }
        String query = search.getText().toString();
        List<GitHubRepos.Repo> shown = GitHubRepos.matching(GitHubRepos.ordered(repos, chosen), query);
        if (!error.isEmpty()) {
            TextView problem = ui.label(error, 13, ERROR); problem.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); sheet.body.addView(problem);
            // A token GitHub refused is replaced in 설정 → 커넥터 → GitHub.
            sheet.body.addView(ui.actions(ui.chip("다시 불러오기", this::refresh), GitHubRepoList.REFUSED.equals(error) && settings != null ? ui.chip("GitHub 설정 열기", this::openSettings) : null));
        }
        LinearLayout list = ui.group();
        for (GitHubRepos.Repo repo : shown) ui.addRow(list, row(repo.name(), notes(repo), repo.name().equalsIgnoreCase(chosen)), false);
        String typed = GitHubRepos.typed(repos, query);
        if (!typed.isEmpty() && !typed.equalsIgnoreCase(chosen)) ui.addRow(list, row(typed, "목록에 없는 저장소 · 토큰이 볼 수 있어야 해요", false), false);
        if (query.isBlank()) ui.addRow(list, row("", "대화에서 저장소를 말하면 그곳에서 일해요", chosen.isEmpty()), false);
        if (list.getChildCount() > 0) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = ui.dp(4); sheet.body.addView(list, lp); }
        String state = loading ? (repos.isEmpty() ? "저장소 목록을 불러오는 중이에요…" : "목록을 새로 불러오는 중이에요…")
            : shown.isEmpty() && typed.isEmpty() && !query.isBlank() ? "‘" + query.strip() + "’에 맞는 저장소가 없어요. owner/저장소 형식으로 적으면 그대로 쓸 수 있어요."
            : repos.isEmpty() && error.isEmpty() ? "토큰이 볼 수 있는 저장소가 없어요. 토큰의 저장소 접근 범위를 확인해 주세요." : "";
        if (!state.isEmpty()) { TextView line = ui.label(state, 13, MUTED); line.setPadding(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(4)); sheet.body.addView(line); }
    }

    /** "비공개 · 읽기만 가능" and the like, from what GitHub said about the repository. */
    private static String notes(GitHubRepos.Repo repo) {
        StringBuilder words = new StringBuilder();
        if (repo.secret()) words.append("비공개");
        if (repo.archived()) words.append(words.length() == 0 ? "" : " · ").append("보관됨");
        if (repo.fork()) words.append(words.length() == 0 ? "" : " · ").append("포크");
        if (!repo.push() && !repo.pushed().isEmpty()) words.append(words.length() == 0 ? "" : " · ").append("읽기만 가능");
        return words.toString();
    }

    /** One choice: owner quiet and the name in the text color, a check when chosen; "" is 저장소 없음. */
    private View row(String name, String note, boolean on) {
        ImageView check = new ImageView(activity); check.setImageResource(R.drawable.ic_check); check.setImageTintList(ColorStateList.valueOf(ACCENT));
        check.setVisibility(on ? View.VISIBLE : View.INVISIBLE); check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout trailing = ui.row(); trailing.addView(check, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        String title = name.isEmpty() ? "저장소 없음" : name;
        LinearLayout row = ui.listRow(0, 0, title, note, trailing, () -> { sheet.dismiss(); if (!name.equalsIgnoreCase(chosen)) choose.accept(name); });
        TextView words = ((Ui.ListRow) row).parts.title();
        int slash = name.indexOf('/');
        if (slash > 0) {
            android.text.SpannableStringBuilder styled = new android.text.SpannableStringBuilder(name);
            styled.setSpan(new android.text.style.ForegroundColorSpan(MUTED), 0, slash + 1, 0);
            words.setText(styled);
        }
        words.setSingleLine(true); words.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        if (on) { words.setTypeface(Ui.weight(650)); if (slash < 0) words.setTextColor(ACCENT); }
        row.setSelected(on); row.setContentDescription(title + (note.isEmpty() ? "" : ", " + note) + (on ? ", 선택됨" : ""));
        return row;
    }
}
