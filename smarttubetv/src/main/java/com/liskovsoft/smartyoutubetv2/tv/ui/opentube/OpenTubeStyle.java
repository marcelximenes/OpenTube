package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * OpenTube: tokens visuais do System.DS do Memória (tema escuro).
 * Fundo 0B0B0B, superfície 232323, texto F0E6D2/B2AB9C, destaque DCD2BE, raio padrão 12.
 */
final class OpenTubeStyle {
    static final int BG = 0xFF0B0B0B;
    static final int SURFACE = 0xFF232323;
    static final int TEXT = 0xFFF0E6D2;
    static final int TEXT_SECONDARY = 0xFFB2AB9C;
    static final int TEXT_MUTED = 0xA6B2AB9C;
    static final int BRAND = 0xFFDCD2BE;
    static final int BORDER = 0x1AFFFFFF;
    static final int SCRIM = 0xD90B0B0B;

    static final float RADIUS_DP = 12;
    static final int DUR_FAST_MS = 120;
    static final int DUR_MEDIUM_MS = 220;
    static final int DUR_SLOW_MS = 360;

    /** Desacelera no fim: entradas e foco. */
    static final android.view.animation.Interpolator EASE_OUT = new android.view.animation.DecelerateInterpolator(2f);
    /** Passa um pouco do ponto e volta: foco dos cards e botões. */
    static final android.view.animation.Interpolator SPRING = new android.view.animation.OvershootInterpolator(1.6f);

    private OpenTubeStyle() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context context, int color, float radiusDp, float strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(context, strokeDp), strokeColor);
        }
        return drawable;
    }

    /** Degradê preto de baixo para cima, para o texto do player ficar legível sobre o vídeo. */
    static GradientDrawable bottomScrim() {
        return new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xF2000000, 0xB3000000, 0x40000000, 0x00000000});
    }

    /** Degradê preto curto, para o nome do canal no pé do card. */
    static GradientDrawable cardScrim(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xE6000000, 0x66000000, 0x00000000});
        float r = dp(context, radiusDp);
        drawable.setCornerRadii(new float[] {0, 0, 0, 0, r, r, r, r});
        return drawable;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    static TextView text(Context context, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    /** Rótulos em caixa alta com espaçamento largo, como no Memória. */
    static TextView label(Context context, float sizeSp, int color, float trackingEm) {
        TextView view = text(context, sizeSp, color, true);
        view.setAllCaps(true);
        if (Build.VERSION.SDK_INT >= 21) {
            view.setLetterSpacing(trackingEm);
        }
        return view;
    }
}
