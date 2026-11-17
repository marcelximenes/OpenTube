package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenTube: um canal de TV (ex.: "History") que agrupa vários canais do YouTube.
 * A lista vem de assets/opentube/canais.json, gerado do canais.csv pelo gerar_canais_app.py.
 */
public final class TvChannel {
    private static final String ASSET_PATH = "opentube/canais.json";
    private static final int[] FALLBACK_COLORS = {
            0xFF5B9BE5, 0xFFE5484D, 0xFF3DD68C, 0xFFF2C94C, 0xFFE8944A, 0xFF9B7BE0, 0xFF3FB8AF, 0xFFE06BA8
    };
    private static List<TvChannel> sChannels;
    /** Pasta da lista enviada pelo PC que está valendo (ou null: vale a embutida no APK). */
    private static java.io.File sPushedRoot;

    public final String name;
    public final int color;
    public final String[] channelIds;
    public final String[] sourceNames;
    /** channel_id das fontes preferidas (marcadas na curadoria): entram duas vezes por rodada na programação. */
    public final java.util.Set<String> favorites;
    /** Caminho da capa em assets/opentube (ex.: "covers/arch3.png"), ou null. */
    public final String cover;
    /** Tempo mínimo de vídeo, em minutos (0 = sem mínimo). Configurado na curadoria. */
    public final int minMinutes;

    private TvChannel(String name, int color, String[] channelIds, String[] sourceNames, String cover, int minMinutes,
                      java.util.Set<String> favorites) {
        this.name = name;
        this.color = color;
        this.channelIds = channelIds;
        this.sourceNames = sourceNames;
        this.cover = cover;
        this.minMinutes = minMinutes;
        this.favorites = favorites;
    }

    public static synchronized List<TvChannel> load(Context context) {
        if (sChannels != null) {
            return sChannels;
        }

        List<TvChannel> result = new ArrayList<>();

        try {
            JSONObject root = new JSONObject(readList(context));
            JSONArray channels = root.getJSONArray("channels");

            for (int i = 0; i < channels.length(); i++) {
                JSONObject channel = channels.getJSONObject(i);
                JSONArray sources = channel.getJSONArray("sources");
                String[] ids = new String[sources.length()];
                String[] names = new String[sources.length()];
                java.util.Set<String> favorites = new java.util.HashSet<>();

                for (int j = 0; j < sources.length(); j++) {
                    JSONObject source = sources.getJSONObject(j);
                    ids[j] = source.getString("id");
                    names[j] = source.optString("name", "");
                    if (source.optBoolean("fav", false)) {
                        favorites.add(ids[j]);
                    }
                }

                if (ids.length == 0) {
                    continue;
                }

                int color = FALLBACK_COLORS[i % FALLBACK_COLORS.length];
                String colorHex = channel.optString("color", null);
                if (colorHex != null) {
                    try {
                        color = android.graphics.Color.parseColor(colorHex);
                    } catch (IllegalArgumentException e) {
                        // mantém a cor padrão
                    }
                }

                String cover = channel.optString("cover", "");
                result.add(new TvChannel(channel.getString("name"), color, ids, names,
                        cover.isEmpty() ? null : "opentube/" + cover, Math.max(0, channel.optInt("minMinutes", 0)), favorites));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        android.util.Log.d("OpenTube", "lista de canais: " + (sPushedRoot != null ? "enviada pelo PC (" + sPushedRoot + ")" : "embutida no APK")
                + ", " + result.size() + " canais");
        sChannels = Collections.unmodifiableList(result);
        return sChannels;
    }

    /** Até três nomes de fontes, para a legenda do card. */
    public String sourcesPreview() {
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (String sourceName : sourceNames) {
            if (sourceName == null || sourceName.isEmpty()) {
                continue;
            }
            if (count > 0) {
                builder.append(" · ");
            }
            builder.append(sourceName);
            if (++count == 3) {
                break;
            }
        }
        return builder.toString();
    }

    /** Pasta onde estão a lista e as capas enviadas pelo PC, se forem elas que valem. */
    static java.io.File pushedRoot() {
        return sPushedRoot;
    }

    /**
     * A lista enviada pelo PC (curadoria.html, via adb) vale mais que a embutida no APK, para dar
     * para atualizar os canais sem recompilar. Ela fica na pasta externa do app
     * (/sdcard/Android/data/app.opentube/files), onde o adb escreve mesmo no APK release.
     * A pasta interna (files/) é de envios antigos, feitos com run-as no APK debug.
     * Uma lista enviada antes da última instalação do APK é ignorada: o APK novo já traz a lista atual.
     */
    private static String readList(Context context) throws Exception {
        long installedAt = 0;
        try {
            installedAt = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            // sem a data, qualquer lista enviada vale
        }
        java.io.File[] roots = {context.getExternalFilesDir(null), context.getFilesDir()};
        for (java.io.File root : roots) {
            if (root == null) {
                continue;
            }
            java.io.File pushed = new java.io.File(root, ASSET_PATH);
            if (!pushed.exists() || pushed.length() == 0 || pushed.lastModified() < installedAt) {
                continue;
            }
            try {
                String content = read(new java.io.FileInputStream(pushed));
                new JSONObject(content); // valida antes de usar
                sPushedRoot = root;
                return content;
            } catch (Exception e) {
                e.printStackTrace(); // arquivo enviado com problema: tenta o próximo
            }
        }
        sPushedRoot = null;
        return read(context.getAssets().open(ASSET_PATH));
    }

    private static String read(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        } finally {
            input.close();
        }
    }
}
