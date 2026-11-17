package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.PageBook;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import static dev.jun.gyeot.Ui.*;

/**
 * 페이지에 저장, from an answer's menu in the main chat and the 작업창: the answer as it is (its suggested next
 * requests left out by the caller), a title to check and one category to pick or make. The format is decided from the text
 * (a whole HTML document is a page of its own; anything else is Markdown). Saved only by the sheet's own button.
 */
final class PageSaveSheet {
    private PageSaveSheet() {}
    /** File work off the main thread; tests run it in place. */
    static Executor work = Executors.newSingleThreadExecutor();

    /** track: the screen's own dialog list, so the sheet closes with it (null for none). */
    static Ui.Sheet show(Activity activity, Ui ui, String text, String source, String origin, Consumer<android.app.Dialog> track) {
        PageBook book = PageStore.book(activity);
        Ui.Sheet sheet = ui.selectionSheet("페이지에 저장", PageBook.HTML.equals(PageBook.decide(null, text)[0]) ? "HTML 문서로 저장해요 · 페이지 메뉴에서 볼 수 있어요" : "Markdown으로 저장해요 · 페이지 메뉴에서 볼 수 있어요");
        if (track != null) track.accept(sheet);
        if (!book.problem().isEmpty()) {
            sheet.body.addView(ui.banner("페이지 목록 파일을 읽지 못해서 저장할 수 없어요. 원본 파일은 그대로 두었어요.", ERROR));
            sheet.primary.setText("닫기");
            sheet.show();
            return sheet;
        }
        sheet.body.addView(ui.sectionHeader("제목"));
        EditText title = ui.input("페이지 제목", 1, PageBook.TITLE_LIMIT);
        title.setText(PageBook.suggestedTitle(text)); title.setSelectAllOnFocus(true); title.setContentDescription("페이지 제목");
        sheet.body.addView(title);
        sheet.body.addView(ui.sectionHeader("카테고리"));
        Picker picker = new Picker(activity, ui, book.categories(), null);
        sheet.body.addView(picker.view);
        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> {
            String name = picker.value();
            if (name == null) { picker.fresh.requestFocus(); picker.fresh.setError("새 카테고리 이름을 적어 주세요"); return; }
            sheet.primary.setEnabled(false);
            String wanted = title.getText().toString();
            Handler main = new Handler(Looper.getMainLooper());
            work.execute(() -> {
                PageBook.Page saved = null; String problem = null;
                try { saved = book.save(wanted, name, null, text, source, origin); }
                // A file error's own words can carry the app's file path: a fixed sentence instead.
                catch (java.io.IOException failed) { problem = "페이지 파일을 저장하지 못했어요. 저장 공간을 확인해 주세요."; }
                catch (IllegalArgumentException | IllegalStateException refused) { problem = refused.getMessage() == null ? "페이지에 저장하지 못했어요." : refused.getMessage(); }
                catch (RuntimeException failed) { problem = "페이지에 저장하지 못했어요."; }
                PageBook.Page page = saved; String error = problem;
                main.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    // Closed while saving: the page is saved all the same, and a short line says so.
                    if (!sheet.isShowing()) { Toast.makeText(activity, page != null ? "페이지 › " + page.category() + "에 저장했어요" : error, Toast.LENGTH_LONG).show(); return; }
                    sheet.primary.setEnabled(true);
                    if (page == null) { Toast.makeText(activity, error, Toast.LENGTH_LONG).show(); return; }
                    saved(activity, ui, sheet, page);
                });
            });
        });
        sheet.show();
        return sheet;
    }

    /** The sheet becomes its own receipt: where the page went, a way to open it, and 닫기. */
    private static void saved(Activity activity, Ui ui, Ui.Sheet sheet, PageBook.Page page) {
        sheet.heading.setText("저장했어요");
        if (sheet.subheading != null) sheet.subheading.setText("페이지 › " + page.category());
        sheet.body.removeAllViews();
        TextView where = ui.text(page.title(), HEADLINE, 650, TEXT); where.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); where.setMaxLines(3); where.setEllipsize(TextUtils.TruncateAt.END);
        sheet.body.addView(where);
        sheet.primary.setText("페이지에서 보기");
        sheet.primary.setOnClickListener(v -> { sheet.dismiss(); open(activity, page.id()); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(8);
        sheet.footer.addView(ui.button("닫기", false, sheet::dismiss), p);
    }
    /**
     * An answer as a document: the app's own cards are left out (a drawn picture's record, a picture request, a calendar card:
     * a page cannot show the picture or add the event) and so are the next requests the answer suggests. Everything else,
     * code and visual blocks included, stays as written.
     */
    static String document(String answer) {
        java.util.Set<String> cards = java.util.Set.of(dev.jun.gyeot.core.ChatImages.MADE, dev.jun.gyeot.core.ChatImages.ASK, dev.jun.gyeot.core.CalendarCards.CARD, dev.jun.gyeot.core.RichBlocks.SUGGEST);
        StringBuilder out = new StringBuilder(); String fence = null; boolean drop = false;
        for (String line : answer.split("\n", -1)) {
            String start = line.stripLeading();
            if (fence == null && (start.startsWith("```") || start.startsWith("~~~"))) {
                fence = start.substring(0, 3); drop = cards.contains(start.substring(3).replace("`", "").replace("~", "").strip().toLowerCase(java.util.Locale.ROOT));
                if (!drop) out.append(line).append('\n');
            } else if (fence != null) {
                if (!drop) out.append(line).append('\n');
                if (start.startsWith(fence) && start.substring(3).replace("`", "").replace("~", "").isBlank()) { fence = null; drop = false; }
            } else out.append(line).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }
    /** Opens the 페이지 screen on one page. */
    static void open(Activity activity, String id) {
        activity.startActivity(new Intent(activity, PagesActivity.class).putExtra(PagesActivity.OPEN, id)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /**
     * One category to pick, the most recently used first, or a new one typed in: the save sheet's and the page screen's
     * 카테고리 옮기기. value() is null only while 새 카테고리 is chosen and its name is still blank.
     */
    static final class Picker {
        final LinearLayout view; final EditText fresh;
        private final Ui ui; private final List<View> checks = new ArrayList<>(); private final List<String> names = new ArrayList<>();
        private int chosen;
        Picker(Activity activity, Ui ui, List<PageBook.Category> categories, String current) {
            this.ui = ui; view = ui.column();
            List<PageBook.Category> order = new ArrayList<>(categories);
            // The category last written to comes first: the next document usually belongs with the last one.
            order.sort((a, b) -> Long.compare(b.updated(), a.updated()));
            LinearLayout group = ui.group();
            for (PageBook.Category category : order) names.add(category.name());
            for (int i = 0; i < names.size(); i++) row(activity, group, names.get(i), (order.get(i).count() > 0 ? order.get(i).count() + "개" : "비어 있음"), i);
            row(activity, group, "새 카테고리", "이름을 직접 적어요", names.size());
            view.addView(group);
            fresh = ui.input(names.isEmpty() ? "예: 여행, 업무 · 비워 두면 ‘기타’" : "예: 여행, 업무, 레시피", 1, PageBook.CATEGORY_LIMIT); fresh.setContentDescription("새 카테고리 이름");
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(10); view.addView(fresh, p);
            int start = current == null ? 0 : Math.max(0, names.indexOf(current));
            choose(names.isEmpty() ? 0 : start);
        }
        private void row(Activity activity, LinearLayout group, String name, String note, int index) {
            ImageView check = new ImageView(activity); check.setImageResource(R.drawable.ic_check); check.setImageTintList(ColorStateList.valueOf(ACCENT));
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout row = ui.listRow(index == names.size() ? R.drawable.ic_add : R.drawable.ic_folder, index == names.size() ? ACCENT : BLUE, name, note, check, () -> choose(index));
            TextView label = ((Ui.ListRow) row).parts.title(); label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END);
            checks.add(check); ui.addRow(group, row, true);
        }
        private void choose(int index) {
            chosen = index;
            for (int i = 0; i < checks.size(); i++) {
                checks.get(i).setVisibility(i == index ? View.VISIBLE : View.INVISIBLE);
                View row = (View) checks.get(i).getParent();
                if (row != null) row.setContentDescription((i < names.size() ? names.get(i) : "새 카테고리") + (i == index ? ", 선택됨" : ""));
            }
            boolean creating = index == names.size();
            fresh.setVisibility(creating ? View.VISIBLE : View.GONE);
            if (creating) fresh.requestFocus();
        }
        String value() {
            if (chosen < names.size()) return names.get(chosen);
            String typed = PageBook.category(fresh.getText().toString());
            // With no category yet, a blank name files the page under 기타.
            return !typed.isEmpty() ? typed : names.isEmpty() ? PageBook.DEFAULT_CATEGORY : null;
        }
    }
}
