package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenTube: monta a "programação" de cada canal de TV a partir do RSS dos canais do YouTube.
 * <p>
 * Para abrir na hora: a última programação de cada canal fica salva em disco. Ao abrir o app,
 * os canais já entram "no ar" com essa cópia, e a atualização pela rede roda por trás.
 */
public final class OpenTubeRepository {
    public interface Callback {
        void onResult(List<Video> videos);
    }

    private static final long MAX_AGE_MS = 30 * 60 * 1000;
    private static final int MAX_VIDEOS = 50; // limite seguro da fila do SmartTube em aparelhos com pouca RAM
    private static OpenTubeRepository sInstance;

    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(2);
    private final Map<String, List<Entry>> mCache = new HashMap<>();
    private final Map<String, Long> mUpdatedAt = new HashMap<>();
    private final Map<String, List<Callback>> mPending = new HashMap<>();
    private final Map<String, Integer> mNextStart = new HashMap<>();
    private final Map<String, String> mDescriptions = new HashMap<>();
    private boolean mDiskLoaded;

    /** Vídeo da programação, guardado como dado simples (vira {@link Video} na hora de tocar). */
    private static final class Entry {
        String videoId;
        String channelId;
        String title;
        String author;
        String thumbnail;
        String description;
    }

    private OpenTubeRepository(Context context) {
        mContext = context.getApplicationContext();
    }

    public static OpenTubeRepository instance(Context context) {
        if (sInstance == null) {
            sInstance = new OpenTubeRepository(context);
        }
        return sInstance;
    }

    /** Lê do disco a programação salva de todos os canais. Rápido; chamar ao abrir a grade. */
    public void loadFromDisk(List<TvChannel> channels, Runnable onDone) {
        if (mDiskLoaded) {
            onDone.run();
            return;
        }
        mExecutor.execute(() -> {
            final Map<String, List<Entry>> loaded = new HashMap<>();
            final Map<String, Long> times = new HashMap<>();
            for (TvChannel channel : channels) {
                long[] time = new long[1];
                List<Entry> entries = readDisk(channel, time);
                if (entries != null && !entries.isEmpty()) {
                    loaded.put(channel.name, entries);
                    times.put(channel.name, time[0]);
                }
            }
            mMain.post(() -> {
                for (Map.Entry<String, List<Entry>> item : loaded.entrySet()) {
                    if (!mCache.containsKey(item.getKey())) {
                        mCache.put(item.getKey(), item.getValue());
                        mUpdatedAt.put(item.getKey(), times.get(item.getKey()));
                        remember(item.getValue());
                    }
                }
                mDiskLoaded = true;
                onDone.run();
            });
        });
    }

    public boolean isReady(TvChannel channel) {
        List<Entry> cached = mCache.get(channel.name);
        return cached != null && !cached.isEmpty();
    }

    public boolean isLoading(TvChannel channel) {
        return mPending.containsKey(channel.name);
    }

    public boolean isFresh(TvChannel channel) {
        Long updatedAt = mUpdatedAt.get(channel.name);
        return isReady(channel) && updatedAt != null && System.currentTimeMillis() - updatedAt < MAX_AGE_MS;
    }

    public String getDescription(String videoId) {
        return videoId != null ? mDescriptions.get(videoId) : null;
    }

    /**
     * Entrega a programação na thread principal. Se já existe uma cópia (memória ou disco),
     * entrega na hora, mesmo antiga, e atualiza por trás quando ela passou de 30 minutos.
     */
    public void load(final TvChannel channel, Callback callback) {
        if (isReady(channel)) {
            if (callback != null) {
                callback.onResult(toVideos(mCache.get(channel.name)));
            }
            if (!isFresh(channel)) {
                refresh(channel, null);
            }
            return;
        }
        refresh(channel, callback);
    }

    /** Busca na rede. O callback recebe o resultado (ou null se nada veio). */
    public void refresh(final TvChannel channel, Callback callback) {
        List<Callback> pending = mPending.get(channel.name);
        if (pending != null) {
            if (callback != null) {
                pending.add(callback);
            }
            return;
        }

        pending = new ArrayList<>();
        if (callback != null) {
            pending.add(callback);
        }
        mPending.put(channel.name, pending);

        mExecutor.execute(() -> {
            final List<Entry> entries = build(OpenTubeFeed.fetchAll(channel.channelIds));
            if (!entries.isEmpty()) {
                writeDisk(channel, entries);
            }
            mMain.post(() -> {
                if (!entries.isEmpty()) {
                    mCache.put(channel.name, entries);
                    mUpdatedAt.put(channel.name, System.currentTimeMillis());
                    remember(entries);
                }
                List<Callback> callbacks = mPending.remove(channel.name);
                List<Entry> current = mCache.get(channel.name);
                if (callbacks != null) {
                    for (Callback item : callbacks) {
                        item.onResult(current != null ? toVideos(current) : null);
                    }
                }
            });
        });
    }

    /** Cada vez que o canal é aberto, a programação continua de onde parou, como uma TV. */
    public int nextStartIndex(TvChannel channel, int size) {
        Integer start = mNextStart.get(channel.name);
        int index = start == null || size == 0 ? 0 : start % size;
        mNextStart.put(channel.name, index + 1);
        return index;
    }

    private void remember(List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.description != null) {
                mDescriptions.put(entry.videoId, entry.description);
            }
        }
    }

    private static List<Video> toVideos(List<Entry> entries) {
        List<Video> videos = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            Video video = new Video();
            video.videoId = entry.videoId;
            video.channelId = entry.channelId;
            video.title = entry.title;
            video.author = entry.author;
            video.secondTitle = entry.author;
            video.description = entry.description;
            video.cardImageUrl = entry.thumbnail;
            video.bgImageUrl = entry.thumbnail;
            video.openTubeFromZero = true;
            videos.add(video);
        }
        return videos;
    }

    /**
     * Intercala os canais do YouTube (um vídeo de cada, em ordem sorteada),
     * do mais novo para o mais antigo dentro de cada canal.
     */
    private static List<Entry> build(List<List<OpenTubeFeed.Entry>> feeds) {
        List<List<OpenTubeFeed.Entry>> lists = new ArrayList<>(feeds);
        Collections.shuffle(lists);

        List<Entry> result = new ArrayList<>();
        boolean added = true;
        for (int i = 0; added && result.size() < MAX_VIDEOS; i++) {
            added = false;
            for (List<OpenTubeFeed.Entry> list : lists) {
                if (i < list.size() && result.size() < MAX_VIDEOS) {
                    OpenTubeFeed.Entry item = list.get(i);
                    Entry entry = new Entry();
                    entry.videoId = item.videoId;
                    entry.channelId = item.channelId;
                    entry.title = item.title;
                    entry.author = item.author;
                    entry.thumbnail = item.thumbnail;
                    entry.description = item.description;
                    result.add(entry);
                    added = true;
                }
            }
        }
        return result;
    }

    private File diskFile(TvChannel channel) {
        File dir = new File(mContext.getFilesDir(), "opentube/programacao");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, channel.name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + ".json");
    }

    private void writeDisk(TvChannel channel, List<Entry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) {
                JSONObject item = new JSONObject();
                item.put("v", entry.videoId);
                item.put("c", entry.channelId);
                item.put("t", entry.title);
                item.put("a", entry.author);
                item.put("i", entry.thumbnail);
                item.put("d", entry.description);
                array.put(item);
            }
            JSONObject root = new JSONObject();
            root.put("updatedAt", System.currentTimeMillis());
            root.put("videos", array);

            File file = diskFile(channel);
            File temp = new File(file.getPath() + ".tmp");
            try (OutputStream output = new FileOutputStream(temp)) {
                output.write(root.toString().getBytes("UTF-8"));
            }
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(file);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private List<Entry> readDisk(TvChannel channel, long[] updatedAt) {
        File file = diskFile(channel);
        if (!file.exists()) {
            return null;
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            JSONObject root = new JSONObject(new String(bytes, 0, offset, "UTF-8"));
            updatedAt[0] = root.optLong("updatedAt");
            JSONArray array = root.getJSONArray("videos");
            List<Entry> entries = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                Entry entry = new Entry();
                entry.videoId = item.optString("v", null);
                entry.channelId = item.optString("c", null);
                entry.title = item.optString("t", null);
                entry.author = item.optString("a", null);
                entry.thumbnail = item.optString("i", null);
                entry.description = item.optString("d", null);
                if (entry.videoId != null) {
                    entries.add(entry);
                }
            }
            return entries;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
