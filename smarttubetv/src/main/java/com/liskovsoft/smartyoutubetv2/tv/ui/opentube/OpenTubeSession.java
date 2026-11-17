package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.content.Context;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenTube: canal de TV que está no ar. Monta a fila do player do SmartTube e guarda
 * quais vídeos pertencem ao canal, para o player mostrar o logo e o painel só neles.
 */
public final class OpenTubeSession {
    private static TvChannel sChannel;
    private static int sSessionId;
    private static final Set<String> sVideoIds = new HashSet<>();

    private OpenTubeSession() {
    }

    public static TvChannel getChannel() {
        return sChannel;
    }

    /** Muda a cada vez que um canal é posto no ar (para o player mostrar "sintonizando"). */
    public static int getSessionId() {
        return sSessionId;
    }

    public static boolean contains(String videoId) {
        return sChannel != null && videoId != null && sVideoIds.contains(videoId);
    }

    /** Põe o canal no ar: começa um vídeo do zero e segue sozinho pela fila. */
    public static void play(Context context, TvChannel channel, List<Video> videos) {
        if (videos == null || videos.isEmpty()) {
            MessageHelpers.showMessage(context, "Sem vídeos novos em " + channel.name + " agora");
            return;
        }

        // A programação já vem sem os vistos (OpenTubeHistory): começa sempre do primeiro
        List<Video> ordered = new ArrayList<>(videos.size());
        for (Video video : videos) {
            video.openTubeFromZero = true;
            video.fromQueue = false;
            ordered.add(video);
        }

        sChannel = channel;
        sSessionId++;
        sVideoIds.clear();
        for (Video video : ordered) {
            sVideoIds.add(video.videoId);
        }

        Playlist playlist = Playlist.instance();
        playlist.clear();
        playlist.addAll(ordered);

        Video first = ordered.get(0);
        playlist.setCurrent(first);
        // Já está na fila: impede o player de mover o vídeo para o fim da lista
        first.fromQueue = true;

        PlaybackPresenter.instance(context).openVideo(first);
    }
}
