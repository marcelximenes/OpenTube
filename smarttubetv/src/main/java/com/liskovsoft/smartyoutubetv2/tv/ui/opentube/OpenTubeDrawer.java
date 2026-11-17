package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenTube: gaveta lateral do player (legendas, áudio e dublagem), no visual do OpenTube.
 * Entra pela direita sobre um degradê escuro. ↑/↓ escolhem, OK aplica e fecha, ← ou Voltar fecham.
 * O foco é controlado pelas teclas que o overlay repassa, sem depender do foco do Android.
 */
final class OpenTubeDrawer {
    /** Uma opção da gaveta. */
    static final class Option {
        final String title;
        final String detail;
        boolean selected;
        final Runnable action;

        Option(String title, String detail, boolean selected, Runnable action) {
            this.title = title;
            this.detail = detail;
            this.selected = selected;
            this.action = action;
        }
    }

    private static final float WIDTH_DP = 380;

    private final Context mContext;
    private final Runnable mOnInteraction;
    private final FrameLayout mRoot;
    private final View mShade;
    private final LinearLayout mPanel;
    private final ImageView mIcon;
    private final TextView mTitle;
    private final ScrollView mScroll;
    private final LinearLayout mList;
    private final List<View> mRows = new ArrayList<>();
    private List<Option> mOptions = new ArrayList<>();
    private int mFocus;
    private boolean mOpen;

    OpenTubeDrawer(Context context, Runnable onInteraction) {
        mContext = context;
        mOnInteraction = onInteraction;

        mRoot = new FrameLayout(context);
        mRoot.setVisibility(View.GONE);

        // Escurece o vídeo do lado da gaveta
        mShade = new View(context);
        mShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[] {0xE6000000, 0x99000000, 0x00000000}));
        mRoot.addView(mShade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mPanel = new LinearLayout(context);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        int pad = OpenTubeStyle.dp(context, 28);
        mPanel.setPadding(pad, OpenTubeStyle.dp(context, 36), pad, OpenTubeStyle.dp(context, 24));
        mPanel.setBackgroundColor(0xF20B0B0B);
        mRoot.addView(mPanel, new FrameLayout.LayoutParams(
                OpenTubeStyle.dp(context, WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        mPanel.addView(header);

        mIcon = new ImageView(context);
        OpenTubeOverlay.tint(mIcon, OpenTubeStyle.BRAND);
        int icon = OpenTubeStyle.dp(context, 20);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(icon, icon);
        iconParams.rightMargin = OpenTubeStyle.dp(context, 10);
        header.addView(mIcon, iconParams);

        mTitle = OpenTubeStyle.label(context, 12, OpenTubeStyle.TEXT_SECONDARY, 0.18f);
        header.addView(mTitle);

        mScroll = new ScrollView(context);
        mScroll.setVerticalScrollBarEnabled(false);
        mScroll.setFocusable(false);
        mScroll.setClipToPadding(false);
        mScroll.setFadingEdgeLength(OpenTubeStyle.dp(context, 32));
        mScroll.setVerticalFadingEdgeEnabled(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = OpenTubeStyle.dp(context, 18);
        mPanel.addView(mScroll, scrollParams);

        mList = new LinearLayout(context);
        mList.setOrientation(LinearLayout.VERTICAL);
        mScroll.addView(mList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = OpenTubeStyle.text(context, 11, OpenTubeStyle.TEXT_MUTED, false);
        hint.setText("OK escolhe  ·  Voltar fecha");
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = OpenTubeStyle.dp(context, 14);
        mPanel.addView(hint, hintParams);
    }

    View getView() {
        return mRoot;
    }

    boolean isOpen() {
        return mOpen;
    }

    void open(String title, int iconRes, List<Option> options) {
        android.util.Log.d("OpenTube", "gaveta " + title + ": " + options.size() + " opções");
        mOptions = options;
        mTitle.setText(title);
        mIcon.setImageResource(iconRes);
        buildRows();

        mFocus = 0;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).selected) {
                mFocus = i;
                break;
            }
        }
        for (int i = 0; i < mRows.size(); i++) {
            styleRow(i, i == mFocus, false);
        }
        mScroll.post(() -> scrollTo(mFocus, false));

        mOpen = true;
        mRoot.setVisibility(View.VISIBLE);
        float width = OpenTubeStyle.dp(mContext, WIDTH_DP);
        mPanel.animate().cancel();
        mShade.animate().cancel();
        mPanel.setTranslationX(width);
        mShade.setAlpha(0f);
        mPanel.animate().translationX(0).setDuration(OpenTubeStyle.DUR_SLOW_MS).setInterpolator(OpenTubeStyle.EASE_OUT).start();
        mShade.animate().alpha(1f).setDuration(OpenTubeStyle.DUR_SLOW_MS).start();

        // Linhas entram em cascata
        for (int i = 0; i < mRows.size() && i < 12; i++) {
            View row = mRows.get(i);
            row.setAlpha(0f);
            row.setTranslationX(OpenTubeStyle.dp(mContext, 24));
            row.animate().alpha(1f).translationX(0).setStartDelay(80 + i * 18L)
                    .setDuration(OpenTubeStyle.DUR_MEDIUM_MS).setInterpolator(OpenTubeStyle.EASE_OUT)
                    .withEndAction(() -> row.animate().setStartDelay(0)).start();
        }
    }

    void close() {
        if (!mOpen) {
            return;
        }
        mOpen = false;
        float width = OpenTubeStyle.dp(mContext, WIDTH_DP);
        mPanel.animate().translationX(width).setDuration(OpenTubeStyle.DUR_MEDIUM_MS).setInterpolator(OpenTubeStyle.EASE_OUT).start();
        mShade.animate().alpha(0f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS).withEndAction(() -> {
            if (!mOpen) {
                mRoot.setVisibility(View.GONE);
            }
        }).start();
    }

    boolean onKey(KeyEvent event) {
        boolean first = event.getRepeatCount() == 0;
        mOnInteraction.run();
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (first) {
                    choose(mFocus);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (first) {
                    close();
                }
                return true;
            default:
                return true; // a gaveta segura o controle enquanto está aberta
        }
    }

    private void move(int delta) {
        int next = Math.max(0, Math.min(mRows.size() - 1, mFocus + delta));
        if (next == mFocus) {
            return;
        }
        styleRow(mFocus, false, true);
        mFocus = next;
        styleRow(mFocus, true, true);
        scrollTo(mFocus, true);
    }

    private void choose(int index) {
        if (index < 0 || index >= mOptions.size()) {
            return;
        }
        for (int i = 0; i < mOptions.size(); i++) {
            mOptions.get(i).selected = i == index;
            styleRow(i, i == mFocus, false);
        }
        View row = mRows.get(index);
        row.animate().scaleX(0.97f).scaleY(0.97f).setDuration(OpenTubeStyle.DUR_FAST_MS / 2)
                .withEndAction(() -> row.animate().scaleX(1f).scaleY(1f).setDuration(OpenTubeStyle.DUR_FAST_MS).start())
                .start();
        mOptions.get(index).action.run();
        mRoot.postDelayed(this::close, 220);
    }

    private void scrollTo(int index, boolean smooth) {
        if (index < 0 || index >= mRows.size()) {
            return;
        }
        View row = mRows.get(index);
        int target = row.getTop() - (mScroll.getHeight() - row.getHeight()) / 2;
        target = Math.max(0, target);
        if (smooth) {
            mScroll.smoothScrollTo(0, target);
        } else {
            mScroll.scrollTo(0, target);
        }
    }

    private void buildRows() {
        mList.removeAllViews();
        mRows.clear();
        for (Option option : mOptions) {
            LinearLayout row = new LinearLayout(mContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = OpenTubeStyle.dp(mContext, 14);
            int padV = OpenTubeStyle.dp(mContext, 11);
            row.setPadding(padH, padV, padH, padV);

            LinearLayout texts = new LinearLayout(mContext);
            texts.setOrientation(LinearLayout.VERTICAL);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = OpenTubeStyle.text(mContext, 15, OpenTubeStyle.TEXT, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setText(option.title);
            texts.addView(title);

            if (!TextUtils.isEmpty(option.detail)) {
                TextView detail = OpenTubeStyle.text(mContext, 11, OpenTubeStyle.TEXT_MUTED, false);
                detail.setSingleLine(true);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                detail.setText(option.detail);
                LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                detailParams.topMargin = OpenTubeStyle.dp(mContext, 4);
                texts.addView(detail, detailParams);
            }

            ImageView check = new ImageView(mContext);
            check.setImageResource(R.drawable.ot_ic_check);
            int checkSize = OpenTubeStyle.dp(mContext, 18);
            LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(checkSize, checkSize);
            checkParams.leftMargin = OpenTubeStyle.dp(mContext, 10);
            row.addView(check, checkParams);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = OpenTubeStyle.dp(mContext, 4);
            mList.addView(row, rowParams);
            mRows.add(row);
        }
    }

    private void styleRow(int index, boolean focused, boolean animate) {
        if (index < 0 || index >= mRows.size()) {
            return;
        }
        LinearLayout row = (LinearLayout) mRows.get(index);
        Option option = mOptions.get(index);
        row.setBackground(focused
                ? OpenTubeStyle.rounded(mContext, OpenTubeStyle.BRAND, 10, 0, 0)
                : OpenTubeStyle.rounded(mContext, 0x00000000, 10, 0, 0));

        LinearLayout texts = (LinearLayout) row.getChildAt(0);
        ((TextView) texts.getChildAt(0)).setTextColor(focused ? OpenTubeStyle.BG : OpenTubeStyle.TEXT);
        if (texts.getChildCount() > 1) {
            ((TextView) texts.getChildAt(1)).setTextColor(focused ? 0xB30B0B0B : OpenTubeStyle.TEXT_MUTED);
        }

        ImageView check = (ImageView) row.getChildAt(1);
        check.setVisibility(option.selected ? View.VISIBLE : View.INVISIBLE);
        OpenTubeOverlay.tint(check, focused ? OpenTubeStyle.BG : OpenTubeStyle.BRAND);

        float scale = focused ? 1.02f : 1f;
        if (animate) {
            row.animate().scaleX(scale).scaleY(scale).setDuration(OpenTubeStyle.DUR_MEDIUM_MS)
                    .setInterpolator(OpenTubeStyle.EASE_OUT).start();
        } else {
            row.setScaleX(scale);
            row.setScaleY(scale);
        }
    }
}
