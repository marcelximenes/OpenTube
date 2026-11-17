package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenTube: capas dos canais de TV (assets/opentube/covers). São poucas e pequenas (240 px),
 * então ficam todas em memória depois da primeira leitura, feita fora da thread principal.
 */
final class OpenTubeCovers {
    private static final Map<String, Bitmap> sCache = new HashMap<>();
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private OpenTubeCovers() {
    }

    /** Lê todas as capas em segundo plano, para a grade e o player abrirem sem esperar. */
    static void preload(Context context, Iterable<TvChannel> channels) {
        final Context app = context.getApplicationContext();
        for (final TvChannel channel : channels) {
            if (channel.cover == null) {
                continue;
            }
            sExecutor.execute(() -> decode(app, channel.cover));
        }
    }

    /**
     * Põe a capa (cantos arredondados) no ImageView. Sem capa, usa um degradê na cor do canal.
     * O ImageView recebe a capa com um fade curto quando ela ainda não estava em memória.
     */
    static void bind(final ImageView view, final TvChannel channel, final float radiusDp) {
        view.setTag(channel.name);
        if (channel.cover == null) {
            view.setImageDrawable(placeholder(view.getContext(), channel, radiusDp));
            return;
        }

        Bitmap cached;
        synchronized (sCache) {
            cached = sCache.get(channel.cover);
        }
        if (cached != null) {
            view.setImageDrawable(rounded(view.getContext(), cached, radiusDp));
            view.setAlpha(1f);
            return;
        }

        view.setImageDrawable(placeholder(view.getContext(), channel, radiusDp));
        final Context app = view.getContext().getApplicationContext();
        sExecutor.execute(() -> {
            final Bitmap bitmap = decode(app, channel.cover);
            sMain.post(() -> {
                if (bitmap == null || !channel.name.equals(view.getTag())) {
                    return; // a view já foi reaproveitada para outro canal
                }
                view.setImageDrawable(rounded(view.getContext(), bitmap, radiusDp));
                view.setAlpha(0f);
                view.animate().alpha(1f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS).start();
            });
        });
    }

    private static Drawable rounded(Context context, Bitmap bitmap, float radiusDp) {
        RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(context.getResources(), bitmap);
        drawable.setCornerRadius(OpenTubeStyle.dp(context, radiusDp));
        drawable.setAntiAlias(true);
        drawable.setFilterBitmap(true);
        return drawable;
    }

    private static Drawable placeholder(Context context, TvChannel channel, float radiusDp) {
        int base = channel.color;
        int dark = (base & 0x00FFFFFF) | 0x33000000;
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {(base & 0x00FFFFFF) | 0xCC000000, dark});
        drawable.setCornerRadius(OpenTubeStyle.dp(context, radiusDp));
        return drawable;
    }

    private static Bitmap decode(Context context, String path) {
        synchronized (sCache) {
            Bitmap cached = sCache.get(path);
            if (cached != null) {
                return cached;
            }
        }
        Bitmap bitmap = null;
        try (InputStream input = context.getAssets().open(path)) {
            bitmap = BitmapFactory.decodeStream(input);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (bitmap != null) {
            synchronized (sCache) {
                sCache.put(path, bitmap);
            }
        }
        return bitmap;
    }
}
