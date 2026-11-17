package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.PlaybackFragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenTube: player próprio sobre o player do SmartTube, ativo só nos vídeos de um canal de TV.
 * <ul>
 *     <li>Canto superior direito: nome e capa do canal de TV (quadradinho).</li>
 *     <li>Ao entrar num canal: tela "sintonizando" com a capa até o vídeo começar.</li>
 *     <li>Player escondido: qualquer botão mostra o player (CH+/CH− trocam de vídeo direto).</li>
 *     <li>Player visível, na linha do tempo: OK pausa/continua, ←/→ volta/avança 10 s
 *     (segurar acelera), ↓ vai para os botões, ↑ ou Voltar esconde.</li>
 *     <li>Botões (ícones): legendas, áudio e dublagem, próximo, mais opções (controles do SmartTube).</li>
 *     <li>Legendas e dublagem abrem numa gaveta lateral própria, no visual do OpenTube.</li>
 * </ul>
 * As informações do vídeo ficam sobre um degradê preto no pé da tela, com a foto redonda do canal.
 */
public class OpenTubeOverlay {
    private static final long SHOW_ON_NEW_VIDEO_MS = 6000;
    private static final long SHOW_ON_KEY_MS = 5000;
    private static final long SHOW_ON_BUTTONS_MS = 9000;
    private static final long SEEK_STEP_MS = 10_000;
    private static final long TICK_VISIBLE_MS = 250;
    private static final long TICK_HIDDEN_MS = 500;
    private static final long TUNE_MAX_MS = 15_000;
    private static final long SUPPRESS_SMARTTUBE_UI_MS = 3000;

    private static final int BUTTON_SUBTITLES = 0;
    private static final int BUTTON_AUDIO = 1;
    private static final int BUTTON_NEXT = 2;
    private static final int BUTTON_MORE = 3;
    private static final String[] BUTTON_LABELS = {"Legendas", "Áudio e dublagem", "Próximo vídeo", "Mais opções"};
    private static final int[] BUTTON_ICONS = {R.drawable.ot_ic_subtitles, R.drawable.ot_ic_dub, R.drawable.ot_ic_next, R.drawable.ot_ic_more};

    private final Activity mActivity;
    private final PlaybackFragment mFragment;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<Integer> mConsumedKeys = new HashSet<>();

    private final FrameLayout mRoot;
    private final LinearLayout mBug;
    private final ImageView mBugCover;
    private final TextView mBugName;

    private final FrameLayout mTune;
    private final ImageView mTuneCover;
    private final TextView mTuneName;
    private final TextView mTuneStatus;

    private final FrameLayout mPlayer;
    private final LinearLayout mPlayerContent;
    private final ImageView mAvatar;
    private final TextView mLabel;
    private final TextView mTitle;
    private final TextView mDescription;
    private final TextView mTimeCurrent;
    private final TextView mTimeTotal;
    private final FrameLayout mTrack;
    private final View mFill;
    private final View mKnob;
    private final ImageView[] mButtons = new ImageView[BUTTON_LABELS.length];
    private final TextView mButtonCaption;

    private final ImageView mCenterIcon;
    private final TextView mSeekBubble;

    private final OpenTubeDrawer mDrawer;

    private boolean mResumed;
    private boolean mPlayerVisible;
    private boolean mOnButtons;
    private int mButtonIndex;
    private String mLastVideoId;
    private String mAvatarUrl;
    private TvChannel mBugChannel;
    private int mTuneSession = -1;
    private boolean mTuning;
    private long mTuneStartedAt;
    private long mSuppressSmartTubeUiUntil;
    private boolean mSmartTubeUiRequested;
    private long mPendingSeekMs = -1;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            tick();
            if (mResumed) {
                mHandler.postDelayed(this, mPlayerVisible || mTuning ? TICK_VISIBLE_MS : TICK_HIDDEN_MS);
            }
        }
    };

    private final Runnable mAutoHide = this::autoHide;
    private final Runnable mClearPendingSeek = () -> mPendingSeekMs = -1;

    public OpenTubeOverlay(Activity activity, PlaybackFragment fragment) {
        mActivity = activity;
        mFragment = fragment;
        Context context = activity;

        mRoot = new FrameLayout(context);
        mRoot.setVisibility(View.GONE);
        mRoot.setFocusable(false);
        mRoot.setClickable(false);
        mRoot.setClipChildren(false);

        // --- Player: degradê preto no pé da tela, com as informações por cima ---
        mPlayer = new FrameLayout(context);
        mPlayer.setBackground(OpenTubeStyle.bottomScrim());
        mPlayer.setVisibility(View.GONE);
        mPlayer.setClipChildren(false);
        mRoot.addView(mPlayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        mPlayerContent = new LinearLayout(context);
        mPlayerContent.setOrientation(LinearLayout.VERTICAL);
        mPlayerContent.setClipChildren(false);
        int side = OpenTubeStyle.dp(context, 48);
        mPlayerContent.setPadding(side, OpenTubeStyle.dp(context, 120), side, OpenTubeStyle.dp(context, 28));
        mPlayer.addView(mPlayerContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Foto redonda do canal + "CANAL DE TV · autor"
        LinearLayout labelRow = new LinearLayout(context);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        mPlayerContent.addView(labelRow);

        mAvatar = new ImageView(context);
        mAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mAvatar.setVisibility(View.GONE);
        int avatar = OpenTubeStyle.dp(context, 26);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(avatar, avatar);
        avatarParams.rightMargin = OpenTubeStyle.dp(context, 10);
        labelRow.addView(mAvatar, avatarParams);

        mLabel = OpenTubeStyle.label(context, 11, OpenTubeStyle.TEXT_SECONDARY, 0.16f);
        mLabel.setSingleLine(true);
        mLabel.setEllipsize(TextUtils.TruncateAt.END);
        labelRow.addView(mLabel);

        mTitle = OpenTubeStyle.text(context, 24, OpenTubeStyle.TEXT, true);
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setShadowLayer(8, 0, 1, 0x99000000);
        mTitle.setLineSpacing(0, 1.1f);
        mPlayerContent.addView(mTitle, wrap(context, 10));

        mDescription = OpenTubeStyle.text(context, 13, OpenTubeStyle.TEXT_SECONDARY, false);
        mDescription.setMaxLines(2);
        mDescription.setEllipsize(TextUtils.TruncateAt.END);
        mDescription.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                Math.round(context.getResources().getDisplayMetrics().widthPixels * 0.62f), ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionParams.topMargin = OpenTubeStyle.dp(context, 8);
        mPlayerContent.addView(mDescription, descriptionParams);

        // Linha do tempo: tempo atual · barra · duração
        LinearLayout timeline = new LinearLayout(context);
        timeline.setOrientation(LinearLayout.HORIZONTAL);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        timeline.setClipChildren(false);
        mPlayerContent.addView(timeline, matchWidth(context, 18));

        mTimeCurrent = OpenTubeStyle.text(context, 12, OpenTubeStyle.TEXT, true);
        mTimeCurrent.setMinWidth(OpenTubeStyle.dp(context, 52));
        timeline.addView(mTimeCurrent);

        mTrack = new FrameLayout(context);
        mTrack.setClipChildren(false);
        mTrack.setBackground(OpenTubeStyle.rounded(context, 0x40F0E6D2, 2, 0, 0));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(0, OpenTubeStyle.dp(context, 4), 1f);
        trackParams.leftMargin = OpenTubeStyle.dp(context, 10);
        trackParams.rightMargin = OpenTubeStyle.dp(context, 14);
        timeline.addView(mTrack, trackParams);

        mFill = new View(context);
        mFill.setBackground(OpenTubeStyle.rounded(context, OpenTubeStyle.BRAND, 2, 0, 0));
        mFill.setPivotX(0);
        mFill.setScaleX(0f);
        mTrack.addView(mFill, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mKnob = new View(context);
        int knob = OpenTubeStyle.dp(context, 14);
        mKnob.setBackground(OpenTubeStyle.circle(OpenTubeStyle.TEXT));
        mTrack.addView(mKnob, new FrameLayout.LayoutParams(knob, knob, Gravity.CENTER_VERTICAL));

        mTimeTotal = OpenTubeStyle.text(context, 12, OpenTubeStyle.TEXT_SECONDARY, true);
        timeline.addView(mTimeTotal);

        // Botões em ícone + legenda do botão focado
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setClipChildren(false);
        int breathe = OpenTubeStyle.dp(context, 5); // espaço para o botão focado crescer sem cortar
        buttons.setPadding(breathe, breathe, 0, breathe);
        mPlayerContent.addView(buttons, wrap(context, 11));

        int size = OpenTubeStyle.dp(context, 44);
        int iconPad = OpenTubeStyle.dp(context, 11);
        for (int i = 0; i < BUTTON_LABELS.length; i++) {
            ImageView button = new ImageView(context);
            button.setImageResource(BUTTON_ICONS[i]);
            button.setPadding(iconPad, iconPad, iconPad, iconPad);
            button.setContentDescription(BUTTON_LABELS[i]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.rightMargin = OpenTubeStyle.dp(context, 10);
            buttons.addView(button, params);
            mButtons[i] = button;
        }

        mButtonCaption = OpenTubeStyle.label(context, 11, OpenTubeStyle.TEXT, 0.14f);
        mButtonCaption.setAlpha(0f);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.leftMargin = OpenTubeStyle.dp(context, 6);
        buttons.addView(mButtonCaption, captionParams);

        // --- Ícone central (pausa/continua/pular) e bolha do avanço ---
        mCenterIcon = new ImageView(context);
        int centerPad = OpenTubeStyle.dp(context, 22);
        mCenterIcon.setPadding(centerPad, centerPad, centerPad, centerPad);
        mCenterIcon.setBackground(OpenTubeStyle.circle(0x99000000));
        tint(mCenterIcon, OpenTubeStyle.TEXT);
        mCenterIcon.setAlpha(0f);
        int icon = OpenTubeStyle.dp(context, 84);
        mRoot.addView(mCenterIcon, new FrameLayout.LayoutParams(icon, icon, Gravity.CENTER));

        mSeekBubble = OpenTubeStyle.text(context, 16, OpenTubeStyle.TEXT, true);
        mSeekBubble.setGravity(Gravity.CENTER);
        int bubblePadH = OpenTubeStyle.dp(context, 16);
        int bubblePadV = OpenTubeStyle.dp(context, 8);
        mSeekBubble.setPadding(bubblePadH, bubblePadV, bubblePadH, bubblePadV);
        mSeekBubble.setBackground(OpenTubeStyle.rounded(context, 0x99000000, 999, 0, 0));
        mSeekBubble.setAlpha(0f);
        mRoot.addView(mSeekBubble, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // --- Gaveta lateral (legendas, dublagem) ---
        mDrawer = new OpenTubeDrawer(context, () -> restartAutoHide(SHOW_ON_BUTTONS_MS));
        mRoot.addView(mDrawer.getView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Tela "sintonizando" ao entrar num canal ---
        mTune = new FrameLayout(context);
        mTune.setBackgroundColor(0xFF000000);
        mTune.setVisibility(View.GONE);
        mRoot.addView(mTune, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout tuneContent = new LinearLayout(context);
        tuneContent.setOrientation(LinearLayout.VERTICAL);
        tuneContent.setGravity(Gravity.CENTER_HORIZONTAL);
        mTune.addView(tuneContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        mTuneCover = new ImageView(context);
        mTuneCover.setScaleType(ImageView.ScaleType.FIT_XY);
        int tuneCover = OpenTubeStyle.dp(context, 132);
        tuneContent.addView(mTuneCover, new LinearLayout.LayoutParams(tuneCover, tuneCover));

        mTuneName = OpenTubeStyle.label(context, 16, OpenTubeStyle.TEXT, 0.12f);
        tuneContent.addView(mTuneName, wrap(context, 18));

        mTuneStatus = OpenTubeStyle.text(context, 12, OpenTubeStyle.TEXT_MUTED, false);
        mTuneStatus.setText("sintonizando…");
        tuneContent.addView(mTuneStatus, wrap(context, 6));

        // --- Canto superior direito: nome + capa do canal (fica por cima de tudo) ---
        mBug = new LinearLayout(context);
        mBug.setOrientation(LinearLayout.HORIZONTAL);
        mBug.setGravity(Gravity.CENTER_VERTICAL);
        int bugPad = OpenTubeStyle.dp(context, 5);
        mBug.setPadding(OpenTubeStyle.dp(context, 12), bugPad, bugPad, bugPad);
        mBug.setBackground(OpenTubeStyle.rounded(context, 0x8C000000, 10, 0, 0));
        mBug.setAlpha(0.92f);

        mBugName = OpenTubeStyle.label(context, 11, OpenTubeStyle.TEXT, 0.14f);
        LinearLayout.LayoutParams bugNameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bugNameParams.rightMargin = OpenTubeStyle.dp(context, 10);
        mBug.addView(mBugName, bugNameParams);

        mBugCover = new ImageView(context);
        mBugCover.setScaleType(ImageView.ScaleType.FIT_XY);
        int bugCover = OpenTubeStyle.dp(context, 36);
        mBug.addView(mBugCover, new LinearLayout.LayoutParams(bugCover, bugCover));

        FrameLayout.LayoutParams bugParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        bugParams.topMargin = OpenTubeStyle.dp(context, 24);
        bugParams.rightMargin = OpenTubeStyle.dp(context, 32);
        mRoot.addView(mBug, bugParams);

        for (int i = 0; i < mButtons.length; i++) {
            styleButton(i, false);
        }

        activity.addContentView(mRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void onResume() {
        mResumed = true;
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    public void onPause() {
        mResumed = false;
        mHandler.removeCallbacks(mTick);
    }

    // ------------------------------------------------------------------ Teclas

    /** @return true se a tecla foi tratada pelo modo TV */
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        // Solta da tecla cujo "aperto" foi nosso: não deixa vazar para o SmartTube
        if (!down) {
            return mConsumedKeys.remove(code) && event.getAction() == KeyEvent.ACTION_UP;
        }

        if (!isActive()) {
            return false;
        }

        boolean handled;
        boolean drawerWasOpen = mDrawer.isOpen();
        if (mDrawer.isOpen()) {
            handled = !isSystemKey(code) && mDrawer.onKey(event);
        } else {
            handled = mPlayerVisible ? onKeyVisible(event) : onKeyHidden(event);
        }
        if (handled) {
            mConsumedKeys.add(code);
        }
        android.util.Log.d("OpenTube", "tecla " + KeyEvent.keyCodeToString(code) + " visível=" + mPlayerVisible
                + " botões=" + mOnButtons + " gaveta=" + drawerWasOpen + " tratada=" + handled);
        return handled;
    }

    private static boolean isSystemKey(int code) {
        return code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    private boolean onKeyHidden(KeyEvent event) {
        boolean first = event.getRepeatCount() == 0;
        int code = event.getKeyCode();
        if (isSystemKey(code)) {
            return false;
        }
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return false;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (first) {
                    skip(true);
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (first) {
                    skip(false);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (first) {
                    togglePlay();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (first) {
                    openSmartTubeControls();
                }
                return true;
            default:
                // Qualquer outro botão só faz o player aparecer
                if (first) {
                    showPlayer(SHOW_ON_KEY_MS);
                }
                return true;
        }
    }

    private boolean onKeyVisible(KeyEvent event) {
        boolean first = event.getRepeatCount() == 0;
        int code = event.getKeyCode();
        if (isSystemKey(code)) {
            return false;
        }

        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (first) {
                    if (mOnButtons) {
                        focusTimeline();
                        restartAutoHide(SHOW_ON_KEY_MS);
                    } else {
                        setPlayerVisible(false);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (first) {
                    skip(true);
                }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (first) {
                    skip(false);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (first) {
                    togglePlay();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-step(event));
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(step(event));
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (first) {
                    openSmartTubeControls();
                }
                return true;
            default:
                break;
        }

        if (mOnButtons) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (first || event.getRepeatCount() % 3 == 0) {
                        focusButton(Math.max(0, mButtonIndex - 1));
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (first || event.getRepeatCount() % 3 == 0) {
                        focusButton(Math.min(mButtons.length - 1, mButtonIndex + 1));
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (first) {
                        focusTimeline();
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (first) {
                        pressButton(mButtonIndex);
                    }
                    break;
                default:
                    break;
            }
            restartAutoHide(SHOW_ON_BUTTONS_MS);
            return true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (first) {
                    togglePlay();
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-step(event));
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(step(event));
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (first) {
                    focusButton(mButtonIndex);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (first) {
                    setPlayerVisible(false);
                }
                return true;
            default:
                break;
        }
        restartAutoHide(SHOW_ON_KEY_MS);
        return true;
    }

    private boolean isActive() {
        if (mFragment == null || mFragment.isOverlayShown()) {
            return false;
        }
        Video video = mFragment.getVideo();
        return video != null && OpenTubeSession.contains(video.videoId);
    }

    // ------------------------------------------------------------------ Ações

    /** 10 s por toque; segurando, o passo cresce até 60 s. */
    private static long step(KeyEvent event) {
        int boost = Math.min(event.getRepeatCount() / 4, 5);
        return SEEK_STEP_MS * (1 + boost);
    }

    private void seekBy(long deltaMs) {
        long duration = mFragment.getDurationMs();
        if (duration <= 0) {
            return;
        }
        // Acumula os toques seguidos, para a barra andar sem esperar o player
        long base = mPendingSeekMs >= 0 ? mPendingSeekMs : mFragment.getPositionMs();
        long position = Math.max(0, Math.min(duration - 1000, base + deltaMs));
        mPendingSeekMs = position;
        mFragment.setPositionMs(position);
        mHandler.removeCallbacks(mClearPendingSeek);
        mHandler.postDelayed(mClearPendingSeek, 700);

        showPlayer(SHOW_ON_KEY_MS);
        updateProgress(position, duration);

        long seconds = Math.abs(deltaMs) / 1000;
        showSeekBubble((deltaMs > 0 ? "+" : "−") + seconds + " s", deltaMs > 0);
    }

    private void autoHide() {
        if (mDrawer.isOpen() || (mFragment != null && !mFragment.getPlayWhenReady())) {
            return; // pausado ou com a gaveta aberta: o player fica na tela
        }
        setPlayerVisible(false);
    }

    private void togglePlay() {
        boolean play = !mFragment.getPlayWhenReady();
        mFragment.setPlayWhenReady(play);
        flashCenterIcon(play ? R.drawable.ot_ic_play : R.drawable.ot_ic_pause);
        showPlayer(SHOW_ON_KEY_MS);
        if (!play) {
            mHandler.removeCallbacks(mAutoHide); // pausado: fica na tela
        }
    }

    private void skip(boolean next) {
        mSuppressSmartTubeUiUntil = SystemClock.uptimeMillis() + SUPPRESS_SMARTTUBE_UI_MS;
        flashCenterIcon(next ? R.drawable.ot_ic_next : R.drawable.ot_ic_previous);
        if (next) {
            mFragment.skipToNext();
        } else {
            mFragment.skipToPrevious();
        }
    }

    private void openSmartTubeControls() {
        mSmartTubeUiRequested = true;
        mDrawer.close();
        setPlayerVisible(false);
        mFragment.showOverlay(true);
    }

    private void pressButton(int index) {
        android.util.Log.d("OpenTube", "botão " + BUTTON_LABELS[index]);
        ImageView button = mButtons[index];
        button.animate().scaleX(0.9f).scaleY(0.9f).setDuration(OpenTubeStyle.DUR_FAST_MS / 2)
                .withEndAction(() -> button.animate().scaleX(1.12f).scaleY(1.12f)
                        .setDuration(OpenTubeStyle.DUR_FAST_MS).setInterpolator(OpenTubeStyle.SPRING).start())
                .start();

        switch (index) {
            case BUTTON_SUBTITLES:
                showSubtitlesDrawer();
                break;
            case BUTTON_AUDIO:
                showAudioDrawer();
                break;
            case BUTTON_NEXT:
                skip(true);
                break;
            case BUTTON_MORE:
                openSmartTubeControls();
                break;
            default:
                break;
        }
    }

    /** Legendas do vídeo: desligadas, as originais e as automáticas do YouTube. */
    private void showSubtitlesDrawer() {
        List<FormatItem> formats = mFragment.getSubtitleFormats();
        if (formats == null || formats.size() <= 1) {
            MessageHelpers.showMessage(mActivity, "Este vídeo não tem legendas");
            return;
        }

        final PlayerData playerData = PlayerData.instance(mActivity);
        List<OpenTubeDrawer.Option> original = new ArrayList<>();
        List<OpenTubeDrawer.Option> automatic = new ArrayList<>();
        OpenTubeDrawer.Option off = null;
        boolean anySelected = false;

        for (final FormatItem format : formats) {
            if (format == null) {
                continue;
            }
            Runnable apply = () -> {
                mFragment.setFormat(format);
                playerData.setFormat(format);
            };
            if (format.isDefault()) {
                off = new OpenTubeDrawer.Option("Desligadas", null, format.isSelected(), apply);
                continue;
            }
            anySelected |= format.isSelected();
            String title = format.getTitle() != null ? format.getTitle().toString() : String.valueOf(format.getLanguage());
            boolean auto = isAutoSubtitle(format, title);
            String clean = title.replaceAll("\\s*\\((auto|automatic|automáticas?|auto-generated)\\)\\s*", "").trim();
            String code = format.getLanguage() != null ? format.getLanguage() : clean;
            String name = looksLikeCode(clean) ? languageName(code) : clean;
            OpenTubeDrawer.Option option = new OpenTubeDrawer.Option(name, auto ? "Automática" : null, format.isSelected(), apply);
            boolean portuguese = primaryLanguage(code).equals("pt");
            List<OpenTubeDrawer.Option> target = auto ? automatic : original;
            if (portuguese) {
                target.add(0, option); // português no topo de cada grupo
            } else {
                target.add(option);
            }
        }

        List<OpenTubeDrawer.Option> options = new ArrayList<>();
        if (off != null) {
            off.selected = !anySelected;
            options.add(off);
        }
        options.addAll(original);
        options.addAll(automatic);
        mDrawer.open("Legendas", R.drawable.ot_ic_subtitles, options);
        mBug.animate().alpha(0f).setDuration(OpenTubeStyle.DUR_FAST_MS).start();
        restartAutoHide(SHOW_ON_BUTTONS_MS);
    }

    private static boolean isAutoSubtitle(FormatItem format, String title) {
        String language = format.getLanguage() != null ? format.getLanguage().toLowerCase(Locale.ROOT) : "";
        String lower = title.toLowerCase(Locale.ROOT);
        return language.contains("auto") || lower.contains("auto");
    }

    /**
     * Faixas de áudio por idioma: a original e as dublagens (inclusive as automáticas do YouTube).
     * A escolha vira preferência: os próximos vídeos com o mesmo idioma já começam dublados.
     */
    private void showAudioDrawer() {
        List<FormatItem> formats = mFragment.getAudioFormats();
        Map<String, FormatItem> byLanguage = new LinkedHashMap<>();
        if (formats != null) {
            for (FormatItem format : formats) {
                if (format == null) {
                    continue;
                }
                String language = format.getLanguage() != null ? format.getLanguage() : "";
                FormatItem current = byLanguage.get(language);
                if (current == null || (format.isSelected() && !current.isSelected())) {
                    byLanguage.put(language, format);
                }
            }
        }

        if (byLanguage.size() <= 1) {
            MessageHelpers.showMessage(mActivity, "Este vídeo não tem dublagem");
            return;
        }

        final PlayerData playerData = PlayerData.instance(mActivity);
        List<OpenTubeDrawer.Option> options = new ArrayList<>();
        List<String> languages = new ArrayList<>(byLanguage.keySet());
        // Original primeiro, depois português, depois o resto em ordem alfabética do nome
        java.util.Collections.sort(languages, (a, b) -> {
            int ra = audioRank(a), rb = audioRank(b);
            return ra != rb ? ra - rb : languageName(a).compareTo(languageName(b));
        });
        for (final String language : languages) {
            final FormatItem format = byLanguage.get(language);
            String kind = languageKind(language);
            String detail = "original".equals(kind) ? "Áudio original"
                    : kind != null && kind.contains("auto") ? "Dublagem automática"
                    : kind != null && kind.contains("dub") ? "Dublagem" : null;
            options.add(new OpenTubeDrawer.Option(languageName(language), detail, format.isSelected(), () -> {
                mFragment.setFormat(format);
                playerData.setAudioLanguage(primaryLanguage(language));
            }));
        }
        mDrawer.open("Áudio e dublagem", R.drawable.ot_ic_dub, options);
        mBug.animate().alpha(0f).setDuration(OpenTubeStyle.DUR_FAST_MS).start();
        restartAutoHide(SHOW_ON_BUTTONS_MS);
    }

    /** "pt-br (dubbed-auto)" → "pt" */
    private static String primaryLanguage(String language) {
        if (TextUtils.isEmpty(language)) {
            return "";
        }
        return language.trim().split("[-_.\\s(]")[0].toLowerCase(Locale.ROOT);
    }

    /** "pt-br (dubbed-auto)" → "dubbed-auto"; sem parênteses → null */
    private static String languageKind(String language) {
        if (language == null) {
            return null;
        }
        int open = language.indexOf('('), close = language.indexOf(')');
        return open >= 0 && close > open ? language.substring(open + 1, close).trim().toLowerCase(Locale.ROOT) : null;
    }

    private static int audioRank(String language) {
        if ("original".equals(languageKind(language)) || TextUtils.isEmpty(language)) {
            return 0;
        }
        return "pt".equals(primaryLanguage(language)) ? 1 : 2;
    }

    private static boolean looksLikeCode(String text) {
        return text.matches("[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,4})?(\\s*\\(.*\\))?");
    }

    /** "pt-br (dubbed-auto)" → "Português (Brasil)" */
    private static String languageName(String language) {
        if (TextUtils.isEmpty(language)) {
            return "Original";
        }
        String code = language.trim().split("[.\\s(]")[0].replace('_', '-');
        String name = Build.VERSION.SDK_INT >= 21
                ? Locale.forLanguageTag(code).getDisplayName(new Locale("pt", "BR"))
                : new Locale(primaryLanguage(code)).getDisplayLanguage(new Locale("pt", "BR"));
        if (TextUtils.isEmpty(name)) {
            return language;
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    // ------------------------------------------------------------------ Estado na tela

    private void tick() {
        Video video = mFragment != null ? mFragment.getVideo() : null;
        boolean ours = video != null && OpenTubeSession.contains(video.videoId);

        if (!ours) {
            mRoot.setVisibility(View.GONE);
            mLastVideoId = null;
            mDrawer.close();
            setPlayerVisible(false);
            hideTune();
            return;
        }

        // O SmartTube abre os controles dele ao trocar de vídeo; no modo TV, só quando pedido
        if (mFragment.isOverlayShown()) {
            if (!mSmartTubeUiRequested && SystemClock.uptimeMillis() < mSuppressSmartTubeUiUntil) {
                mFragment.showOverlay(false);
            } else {
                mRoot.setVisibility(View.GONE);
                return;
            }
        } else {
            mSmartTubeUiRequested = false;
        }

        mRoot.setVisibility(View.VISIBLE);

        // A capa do canto sai da frente quando a gaveta abre
        float bugAlpha = mDrawer.isOpen() ? 0f : 0.92f;
        if (mBug.getAlpha() != bugAlpha && mBug.getTag() == null) {
            mBug.setTag(Boolean.TRUE);
            mBug.animate().alpha(bugAlpha).setDuration(OpenTubeStyle.DUR_MEDIUM_MS)
                    .withEndAction(() -> mBug.setTag(null)).start();
        }

        TvChannel channel = OpenTubeSession.getChannel();
        if (channel != null && channel != mBugChannel) {
            mBugChannel = channel;
            mBugName.setText(channel.name);
            OpenTubeCovers.bind(mBugCover, channel, 6);
        }

        // Entrou num canal: "sintonizando" até o vídeo começar a tocar
        if (channel != null && OpenTubeSession.getSessionId() != mTuneSession) {
            mTuneSession = OpenTubeSession.getSessionId();
            showTune(channel);
        }
        if (mTuning && (mFragment.isPlaying() || SystemClock.uptimeMillis() - mTuneStartedAt > TUNE_MAX_MS)) {
            hideTune();
            if (mFragment.isPlaying()) {
                mLastVideoId = null; // mostra as informações agora que a imagem apareceu
            }
        }

        if (!video.videoId.equals(mLastVideoId)) {
            mLastVideoId = video.videoId;
            mAvatarUrl = null;
            mAvatar.setVisibility(View.GONE);
            mDrawer.close();
            mSuppressSmartTubeUiUntil = SystemClock.uptimeMillis() + SUPPRESS_SMARTTUBE_UI_MS;
            if (!mTuning) {
                showPlayer(SHOW_ON_NEW_VIDEO_MS, true);
            }
        } else if (mPlayerVisible) {
            updateInfo(video, false);
        }
        updateAvatar(video);
    }

    /** Foto do canal: chega com os metadados do vídeo, um pouco depois do vídeo abrir. */
    private void updateAvatar(Video video) {
        String url = video.openTubeAuthorImageUrl;
        if (TextUtils.isEmpty(url) || TextUtils.equals(url, mAvatarUrl)) {
            return;
        }
        mAvatarUrl = url;
        Glide.with(mActivity.getApplicationContext())
                .load(url)
                .apply(RequestOptions.circleCropTransform())
                .into(mAvatar);
        mAvatar.setVisibility(View.VISIBLE);
        mAvatar.setAlpha(0f);
        mAvatar.setScaleX(0.7f);
        mAvatar.setScaleY(0.7f);
        mAvatar.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS)
                .setInterpolator(OpenTubeStyle.SPRING).start();
    }

    private void showTune(TvChannel channel) {
        mTuning = true;
        mTuneStartedAt = SystemClock.uptimeMillis();
        mTuneName.setText(channel.name);
        OpenTubeCovers.bind(mTuneCover, channel, 14);
        mTune.animate().cancel();
        mTune.setAlpha(1f);
        mTune.setVisibility(View.VISIBLE);
        mTuneCover.setScaleX(0.9f);
        mTuneCover.setScaleY(0.9f);
        mTuneCover.animate().scaleX(1f).scaleY(1f).setDuration(OpenTubeStyle.DUR_SLOW_MS)
                .setInterpolator(OpenTubeStyle.SPRING).start();
        pulse(mTuneStatus);
    }

    private void pulse(final View view) {
        if (!mTuning) {
            view.setAlpha(1f);
            return;
        }
        float target = view.getAlpha() > 0.6f ? 0.3f : 1f;
        view.animate().alpha(target).setDuration(600).withEndAction(() -> pulse(view)).start();
    }

    private void hideTune() {
        if (!mTuning) {
            return;
        }
        mTuning = false;
        mTune.animate().alpha(0f).setDuration(OpenTubeStyle.DUR_SLOW_MS).setInterpolator(OpenTubeStyle.EASE_OUT)
                .withEndAction(() -> {
                    if (!mTuning) {
                        mTune.setVisibility(View.GONE);
                    }
                }).start();
    }

    private void showPlayer(long durationMs) {
        showPlayer(durationMs, false);
    }

    private void showPlayer(long durationMs, boolean resetFocus) {
        Video video = mFragment != null ? mFragment.getVideo() : null;
        if (video == null) {
            return;
        }
        if (!mPlayerVisible || resetFocus) {
            focusTimeline();
        }
        updateInfo(video, true);
        setPlayerVisible(true);
        restartAutoHide(durationMs);
    }

    private void restartAutoHide(long durationMs) {
        mHandler.removeCallbacks(mAutoHide);
        mHandler.postDelayed(mAutoHide, durationMs);
    }

    private void setPlayerVisible(boolean visible) {
        if (mPlayerVisible == visible) {
            return;
        }
        mPlayerVisible = visible;
        mHandler.removeCallbacks(mAutoHide);
        int rise = OpenTubeStyle.dp(mActivity, 24);

        mPlayer.animate().cancel();
        mPlayerContent.animate().cancel();
        if (visible) {
            mPlayer.setAlpha(0f);
            mPlayer.setVisibility(View.VISIBLE);
            mPlayerContent.setTranslationY(rise);
            mPlayer.animate().alpha(1f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS).setInterpolator(OpenTubeStyle.EASE_OUT).start();
            mPlayerContent.animate().translationY(0).setDuration(OpenTubeStyle.DUR_SLOW_MS).setInterpolator(OpenTubeStyle.EASE_OUT).start();
            mHandler.removeCallbacks(mTick);
            mHandler.post(mTick); // passa a atualizar a barra mais rápido
        } else {
            mOnButtons = false;
            mDrawer.close();
            mPlayer.animate().alpha(0f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS).setInterpolator(OpenTubeStyle.EASE_OUT)
                    .withEndAction(() -> {
                        if (!mPlayerVisible) {
                            mPlayer.setVisibility(View.GONE);
                        }
                    }).start();
            mPlayerContent.animate().translationY(rise / 2f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS).start();
        }
    }

    private void focusTimeline() {
        mOnButtons = false;
        mKnob.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS)
                .setInterpolator(OpenTubeStyle.SPRING).start();
        for (int i = 0; i < mButtons.length; i++) {
            styleButton(i, false);
        }
        mButtonCaption.animate().alpha(0f).translationX(-OpenTubeStyle.dp(mActivity, 6))
                .setDuration(OpenTubeStyle.DUR_FAST_MS).start();
    }

    private void focusButton(int index) {
        mOnButtons = true;
        mButtonIndex = index;
        mKnob.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.6f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS).start();
        for (int i = 0; i < mButtons.length; i++) {
            styleButton(i, i == index);
        }
        mButtonCaption.setText(BUTTON_LABELS[index]);
        mButtonCaption.setTranslationX(-OpenTubeStyle.dp(mActivity, 6));
        mButtonCaption.animate().alpha(1f).translationX(0).setDuration(OpenTubeStyle.DUR_MEDIUM_MS)
                .setInterpolator(OpenTubeStyle.EASE_OUT).start();
    }

    private void styleButton(int index, boolean focused) {
        ImageView button = mButtons[index];
        button.setBackground(OpenTubeStyle.circle(focused ? OpenTubeStyle.BRAND : 0x26FFFFFF));
        tint(button, focused ? OpenTubeStyle.BG : OpenTubeStyle.TEXT);
        float scale = focused ? 1.12f : 1f;
        button.animate().scaleX(scale).scaleY(scale).setDuration(OpenTubeStyle.DUR_MEDIUM_MS)
                .setInterpolator(focused ? OpenTubeStyle.SPRING : OpenTubeStyle.EASE_OUT).start();
    }

    static void tint(ImageView view, int color) {
        if (Build.VERSION.SDK_INT >= 21) {
            view.setImageTintList(ColorStateList.valueOf(color));
        } else {
            view.setColorFilter(color);
        }
    }

    private void flashCenterIcon(int iconRes) {
        mCenterIcon.setImageResource(iconRes);
        mCenterIcon.animate().cancel();
        mCenterIcon.setAlpha(1f);
        mCenterIcon.setScaleX(0.8f);
        mCenterIcon.setScaleY(0.8f);
        mCenterIcon.animate().scaleX(1.1f).scaleY(1.1f).alpha(0f).setStartDelay(150)
                .setDuration(OpenTubeStyle.DUR_SLOW_MS + 200).setInterpolator(OpenTubeStyle.EASE_OUT)
                .withEndAction(() -> mCenterIcon.animate().setStartDelay(0)).start();
    }

    private void showSeekBubble(String text, boolean forward) {
        mSeekBubble.setText(text);
        mSeekBubble.animate().cancel();
        mSeekBubble.setAlpha(1f);
        int offset = OpenTubeStyle.dp(mActivity, 160);
        mSeekBubble.setTranslationX(forward ? offset : -offset);
        mSeekBubble.animate().alpha(0f).translationX(forward ? offset + 24 : -offset - 24).setStartDelay(350)
                .setDuration(OpenTubeStyle.DUR_SLOW_MS).setInterpolator(OpenTubeStyle.EASE_OUT)
                .withEndAction(() -> mSeekBubble.animate().setStartDelay(0)).start();
    }

    private void updateInfo(Video video, boolean texts) {
        if (texts) {
            TvChannel channel = OpenTubeSession.getChannel();
            String author = video.author != null ? video.author : "";
            mLabel.setText(channel != null ? channel.name + "   ·   " + author : author);
            mTitle.setText(video.title != null ? video.title : "");

            String description = OpenTubeRepository.instance(mActivity).getDescription(video.videoId);
            if (TextUtils.isEmpty(description)) {
                description = video.description;
            }
            description = description != null ? description.trim().replace('\n', ' ') : "";
            mDescription.setText(description);
            mDescription.setVisibility(TextUtils.isEmpty(description) ? View.GONE : View.VISIBLE);
        }

        if (mPendingSeekMs < 0) {
            updateProgress(mFragment.getPositionMs(), mFragment.getDurationMs());
        }
    }

    private void updateProgress(long positionMs, long durationMs) {
        if (durationMs <= 0) {
            mTimeCurrent.setText("");
            mTimeTotal.setText("");
            mFill.setScaleX(0f);
            mKnob.setTranslationX(0);
            return;
        }
        float fraction = Math.max(0f, Math.min(1f, positionMs / (float) durationMs));
        mTimeCurrent.setText(format(positionMs));
        mTimeTotal.setText(format(durationMs));
        mFill.setScaleX(fraction);
        int width = mTrack.getWidth();
        mKnob.setTranslationX(width * fraction - mKnob.getWidth() / 2f);
    }

    private static String format(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private static LinearLayout.LayoutParams wrap(Context context, float topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = OpenTubeStyle.dp(context, topDp);
        return params;
    }

    private static LinearLayout.LayoutParams matchWidth(Context context, float topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = OpenTubeStyle.dp(context, topDp);
        return params;
    }
}
