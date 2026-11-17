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

    public final String name;
    public final int color;
    public final String[] channelIds;
    public final String[] sourceNames;
    /** Caminho da capa em assets/opentube (ex.: "covers/arch3.png"), ou null. */
    public final String cover;

    private TvChannel(String name, int color, String[] channelIds, String[] sourceNames, String cover) {
        this.name = name;
        this.color = color;
        this.channelIds = channelIds;
        this.sourceNames = sourceNames;
        this.cover = cover;
    }

    public static synchronized List<TvChannel> load(Context context) {
        if (sChannels != null) {
            return sChannels;
        }

        List<TvChannel> result = new ArrayList<>();

        try {
            JSONObject root = new JSONObject(readAsset(context, ASSET_PATH));
            JSONArray channels = root.getJSONArray("channels");

            for (int i = 0; i < channels.length(); i++) {
                JSONObject channel = channels.getJSONObject(i);
                JSONArray sources = channel.getJSONArray("sources");
                String[] ids = new String[sources.length()];
                String[] names = new String[sources.length()];

                for (int j = 0; j < sources.length(); j++) {
                    JSONObject source = sources.getJSONObject(j);
                    ids[j] = source.getString("id");
                    names[j] = source.optString("name", "");
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
                        cover.isEmpty() ? null : "opentube/" + cover));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

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

    private static String readAsset(Context context, String path) throws Exception {
        InputStream input = context.getAssets().open(path);
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
