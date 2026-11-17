package dev.jun.gyeot;

import android.content.Context;
import android.view.Gravity;
import android.widget.*;
import static dev.jun.gyeot.Ui.*;

/** One quiet row: conversation actions are explicit in the menu, never hidden behind a plus. */
final class BrowserPanelHeader extends LinearLayout {
    final TextView title;
    final ImageView menuButton, collapseButton;

    BrowserPanelHeader(Context context, Ui ui, Runnable collapse, Runnable menu) {
        super(context); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
        int height = ui.dp(Math.max(48, 20 * getResources().getConfiguration().fontScale + 8));
        setMinimumHeight(height);
        title = ui.text("AI 대화", 15, 650, TEXT); title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(title, new LayoutParams(0, -2, 1));
        menuButton = ui.quietIcon(R.drawable.ic_more, "이 탭의 대화 메뉴 · 새 대화, 기록, 지우기", menu);
        menuButton.setImageTintList(android.content.res.ColorStateList.valueOf(TEXT));
        menuButton.setTooltipText("대화 메뉴");
        menuButton.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        addView(menuButton, new LayoutParams(ui.dp(48), height));
        collapseButton = ui.quietIcon(R.drawable.ic_close, "AI 대화 패널만 접기 · 기록은 유지", collapse);
        collapseButton.setImageTintList(android.content.res.ColorStateList.valueOf(TEXT));
        collapseButton.setTooltipText("패널 접기 · 대화는 유지돼요");
        collapseButton.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        addView(collapseButton, new LayoutParams(ui.dp(48), height));
    }

    void update(String site) {
        title.setContentDescription("AI 대화 · " + (site.isBlank() ? "현재 탭" : site + " · 현재 탭"));
    }
}
