package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * OpenTube: leitura direta do RSS dos canais do YouTube, todos em paralelo.
 * <p>
 * O SmartTube, para cada canal do RSS, também baixa a página do canal para filtrar Shorts e lives.
 * Com 15 a 23 canais por canal de TV, isso deixava a abertura lenta. Aqui o filtro sai do próprio RSS:
 * Shorts têm link /shorts/ e estreias ainda não lançadas têm zero visualizações.
 */
final class OpenTubeFeed {
    private static final String RSS_URL = "https://www.youtube.com/feeds/videos.xml?channel_id=";
    private static final int TIMEOUT_MS = 7000;
    private static final long TOTAL_TIMEOUT_MS = 12000;
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(8);

    /** Um vídeo lido do RSS. */
    static final class Entry {
        String videoId;
        String channelId;
        String title;
        String author;
        String thumbnail;
        String description;
        String published;
    }

    private OpenTubeFeed() {
    }

    /** Bloqueia até todos os canais responderem (ou o tempo esgotar). Chamar fora da thread principal. */
    static List<List<Entry>> fetchAll(String[] channelIds) {
        List<Future<List<Entry>>> futures = new ArrayList<>();
        for (final String channelId : channelIds) {
            futures.add(sExecutor.submit(() -> fetch(channelId)));
        }

        long deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS;
        List<List<Entry>> result = new ArrayList<>();
        for (Future<List<Entry>> future : futures) {
            try {
                long left = Math.max(1, deadline - System.currentTimeMillis());
                List<Entry> entries = future.get(left, TimeUnit.MILLISECONDS);
                if (entries != null && !entries.isEmpty()) {
                    result.add(entries);
                }
            } catch (Exception e) {
                future.cancel(true); // canal lento ou fora do ar: segue sem ele
            }
        }
        return result;
    }

    private static List<Entry> fetch(String channelId) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RSS_URL + channelId).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (connection.getResponseCode() != 200) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                return parse(input);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static List<Entry> parse(InputStream input) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(input, "UTF-8");

        List<Entry> entries = new ArrayList<>();
        Entry entry = null;
        boolean inAuthor = false;
        boolean skip = false;

        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("entry".equals(tag)) {
                    entry = new Entry();
                    skip = false;
                } else if (entry == null) {
                    continue;
                } else if ("author".equals(tag)) {
                    inAuthor = true;
                } else if ("yt:videoId".equals(tag)) {
                    entry.videoId = parser.nextText();
                } else if ("yt:channelId".equals(tag)) {
                    entry.channelId = parser.nextText();
                } else if ("title".equals(tag)) {
                    entry.title = parser.nextText();
                } else if ("name".equals(tag) && inAuthor) {
                    entry.author = parser.nextText();
                } else if ("published".equals(tag)) {
                    entry.published = parser.nextText();
                } else if ("media:description".equals(tag)) {
                    entry.description = parser.nextText();
                } else if ("media:thumbnail".equals(tag)) {
                    entry.thumbnail = parser.getAttributeValue(null, "url");
                } else if ("link".equals(tag)) {
                    String href = parser.getAttributeValue(null, "href");
                    if (href != null && href.contains("/shorts/")) {
                        skip = true;
                    }
                } else if ("media:statistics".equals(tag)) {
                    if ("0".equals(parser.getAttributeValue(null, "views"))) {
                        skip = true; // estreia ou live ainda não começou
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                String tag = parser.getName();
                if ("author".equals(tag)) {
                    inAuthor = false;
                } else if ("entry".equals(tag) && entry != null) {
                    if (!skip && entry.videoId != null) {
                        entries.add(entry);
                    }
                    entry = null;
                }
            }
        }
        return entries;
    }
}
