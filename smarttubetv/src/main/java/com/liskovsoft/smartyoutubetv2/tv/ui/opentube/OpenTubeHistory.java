package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenTube: o que já passou na TV, para a programação não repetir vídeo visto
 * e para aplicar o tempo mínimo de cada canal.
 * <p>
 * Guarda em files/opentube/historico.json:
 * <ul>
 *     <li>vistos: vídeos assistidos (metade ou 5 min) ou pulados com "Próximo";</li>
 *     <li>duração de cada vídeo que já abriu no player (o RSS não traz a duração).</li>
 * </ul>
 * Também consulta o histórico local do SmartTube (VideoStateService), que tem a posição e a
 * duração dos vídeos vistos antes desta versão.
 */
final class OpenTubeHistory {
    private static final String FILE = "opentube/historico.json";
    /** Versão 2: a 1 (só no dia do teste) marcava lives como vistas; os vistos dela são descartados. */
    private static final int VERSION = 2;
    private static final int MAX_ITEMS = 6000;
    private static final long WATCHED_MIN_MS = 5 * 60 * 1000;
    private static final long SAVE_DELAY_MS = 4000;

    private static OpenTubeHistory sInstance;

    private final Context mContext;
    /** videoId → quando foi visto. Ordem de inserção, para cortar os mais antigos. */
    private final LinkedHashMap<String, Long> mWatched = new LinkedHashMap<>();
    /** videoId → duração em segundos. */
    private final LinkedHashMap<String, Integer> mDurations = new LinkedHashMap<>();
    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mSave = this::save;

    private OpenTubeHistory(Context context) {
        mContext = context.getApplicationContext();
        load();
    }

    static synchronized OpenTubeHistory instance(Context context) {
        if (sInstance == null) {
            sInstance = new OpenTubeHistory(context);
        }
        return sInstance;
    }

    /** Já visto aqui ou no histórico do SmartTube. */
    synchronized boolean isWatched(String videoId) {
        if (videoId == null) {
            return false;
        }
        if (mWatched.containsKey(videoId)) {
            return true;
        }
        State state = smartTubeState(videoId);
        return state != null && (state.video == null || !state.video.isLive) && isWatched(state.positionMs, state.durationMs);
    }

    /** Duração em segundos, ou -1 se o vídeo nunca abriu no player. */
    synchronized int durationSec(String videoId) {
        Integer known = mDurations.get(videoId);
        if (known != null) {
            return known;
        }
        State state = smartTubeState(videoId);
        return state != null && state.durationMs > 0 ? (int) (state.durationMs / 1000) : -1;
    }

    /** Curto demais para o canal (só quando a duração é conhecida). */
    boolean isTooShort(String videoId, int minMinutes) {
        if (minMinutes <= 0) {
            return false;
        }
        int duration = durationSec(videoId);
        return duration > 0 && duration < minMinutes * 60;
    }

    /** Chamado pelo player a cada tique: grava a duração e marca como visto quando passar do ponto. */
    synchronized void onProgress(String videoId, long positionMs, long durationMs) {
        if (videoId == null || durationMs <= 0) {
            return;
        }
        boolean changed = false;
        int seconds = (int) (durationMs / 1000);
        Integer known = mDurations.get(videoId);
        if (known == null || known != seconds) {
            mDurations.put(videoId, seconds);
            changed = true;
        }
        if (!mWatched.containsKey(videoId) && isWatched(positionMs, durationMs)) {
            mWatched.put(videoId, System.currentTimeMillis());
            changed = true;
        }
        if (changed) {
            scheduleSave();
        }
    }

    /** "Próximo" num vídeo: ele sai da programação. */
    synchronized void markWatched(String videoId) {
        if (videoId != null && !mWatched.containsKey(videoId)) {
            mWatched.put(videoId, System.currentTimeMillis());
            scheduleSave();
        }
    }

    private static boolean isWatched(long positionMs, long durationMs) {
        return positionMs >= WATCHED_MIN_MS || (durationMs > 0 && positionMs >= durationMs / 2);
    }

    private State smartTubeState(String videoId) {
        try {
            return VideoStateService.instance(mContext).getByVideoId(videoId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ disco

    private void scheduleSave() {
        mHandler.removeCallbacks(mSave);
        mHandler.postDelayed(mSave, SAVE_DELAY_MS);
    }

    private synchronized void save() {
        trim(mWatched);
        trim(mDurations);
        try {
            JSONObject watched = new JSONObject();
            for (Map.Entry<String, Long> item : mWatched.entrySet()) {
                watched.put(item.getKey(), item.getValue());
            }
            JSONObject durations = new JSONObject();
            for (Map.Entry<String, Integer> item : mDurations.entrySet()) {
                durations.put(item.getKey(), item.getValue());
            }
            JSONObject root = new JSONObject();
            root.put("versao", VERSION);
            root.put("vistos", watched);
            root.put("duracoes", durations);

            File file = new File(mContext.getFilesDir(), FILE);
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
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

    private void load() {
        File file = new File(mContext.getFilesDir(), FILE);
        if (!file.exists()) {
            return;
        }
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            JSONObject root = new JSONObject(output.toString("UTF-8"));
            JSONObject watched = root.optInt("versao", 1) >= VERSION ? root.optJSONObject("vistos") : null;
            if (watched != null) {
                for (Iterator<String> keys = watched.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    mWatched.put(key, watched.optLong(key));
                }
            }
            JSONObject durations = root.optJSONObject("duracoes");
            if (durations != null) {
                for (Iterator<String> keys = durations.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    mDurations.put(key, durations.optInt(key));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void trim(LinkedHashMap<String, ?> map) {
        Iterator<String> keys = map.keySet().iterator();
        while (map.size() > MAX_ITEMS && keys.hasNext()) {
            keys.next();
            keys.remove();
        }
    }
}
