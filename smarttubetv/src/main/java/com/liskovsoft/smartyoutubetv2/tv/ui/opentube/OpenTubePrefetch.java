package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.util.Log;

import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenTube: busca antes os dados de reprodução (formatos, token) de um vídeo que vai tocar logo.
 * <p>
 * Cada vídeo leva uns 3 s só nesse pedido; com o app recém-aberto, mais uns 6 s preparando o
 * token e o decifrador. A biblioteca guarda o último resultado, então, quando o player pede o mesmo
 * vídeo, ele já está pronto. Usado para o próximo vídeo da fila e para o primeiro vídeo do canal
 * em foco na grade.
 */
final class OpenTubePrefetch {
    private static final String TAG = "OpenTube";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    /** Só o pedido mais recente importa: o foco na grade muda rápido. */
    private static final AtomicReference<String> sWanted = new AtomicReference<>();

    private OpenTubePrefetch() {
    }

    static void video(String videoId) {
        // Pedido igual ao que já está na fila: nada a fazer. Repetir um já feito é barato (a
        // biblioteca devolve do cache), e precisa ser possível: o player pode ter trocado o cache.
        if (videoId == null || videoId.equals(sWanted.getAndSet(videoId))) {
            return;
        }
        EXECUTOR.execute(() -> {
            String id = sWanted.get();
            if (id == null) {
                return;
            }
            long start = System.currentTimeMillis();
            try {
                YouTubeServiceManager.instance().getMediaItemService().getFormatInfo(id);
                Log.d(TAG, "pré-carregado " + id + " em " + (System.currentTimeMillis() - start) + " ms");
            } catch (Throwable e) {
                Log.w(TAG, "pré-carregamento falhou: " + id, e);
            } finally {
                sWanted.compareAndSet(id, null);
            }
        });
    }
}
