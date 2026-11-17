package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.app.Activity;
import android.content.Context;
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

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
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
 *     <li>Canto superior direito: capa do canal de TV (quadradinho) e o nome.</li>
 *     <li>Ao entrar num canal: tela "sintonizando" com a capa até o vídeo começar.</li>
 *     <li>Player escondido: qualquer botão mostra o player (CH+/CH− trocam de vídeo direto).</li>
 *     <li>Player visível, na linha do tempo: OK pausa/continua, ←/→ volta/avança 10 s
 *     (segurar acelera), ↓ vai para os botões, ↑ ou Voltar esconde.</li>
 *     <li>Botões: Legendas, Áudio e dublagem, Próximo, Mais opções (controles do SmartTube).</li>
 * </ul>
 * As informações do vídeo ficam sobre um degradê preto no pé da tela, sem card.
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
    private static final String[] BUTTON_LABELS = {"Legendas", "Áudio e dublagem", "Próximo", "Mais opções"};

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
    private final TextView mLabel;
    private final TextView mTitle;
    private final TextView mDescription;
    private final TextView mTimeCurrent;
    private final TextView mTimeTotal;
    private final FrameLayout mTrack;
    private final View mFill;
    private final View mKnob;
    private final TextView[] mButtons = new TextView[BUTTON_LABELS.length];

    private final TextView mCenterIcon;
    private final TextView mSeekBubble;

    private boolean mResumed;
    private boolean mPlayerVisible;
    private boolean mOnButtons;
    private int mButtonIndex;
    private String mLastVideoId;
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

    private void autoHide() {
        if (mFragment != null && !mFragment.getPlayWhenReady()) {
            return; // pausado: o player fica na tela
        }
        setPlayerVisible(false);
    }

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
        mPlayerContent.setPadding(side, OpenTubeStyle.dp(context, 120), side, OpenTubeStyle.dp(context, 30));
        mPlayer.addView(mPlayerContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mLabel = OpenTubeStyle.label(context, 11, OpenTubeStyle.TEXT_SECONDARY, 0.16f);
        mLabel.setSingleLine(true);
        mLabel.setEllipsize(TextUtils.TruncateAt.END);
        mPlayerContent.addView(mLabel);

        mTitle = OpenTubeStyle.text(context, 24, OpenTubeStyle.TEXT, true);
        mTitle.setMaxLines(2);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mTitle.setShadowLayer(8, 0, 1, 0x99000000);
        LinearLayout.LayoutParams titleParams = wrap(context, 8);
        mPlayerContent.addView(mTitle, titleParams);

        mDescription = OpenTubeStyle.text(context, 13, OpenTubeStyle.TEXT_SECONDARY, false);
        mDescription.setMaxLines(2);
        mDescription.setEllipsize(TextUtils.TruncateAt.END);
        mDescription.setLineSpacing(0, 1.25f);
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
        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(knob, knob, Gravity.CENTER_VERTICAL);
        knobParams.topMargin = 0;
        mTrack.addView(mKnob, knobParams);

        mTimeTotal = OpenTubeStyle.text(context, 12, OpenTubeStyle.TEXT_SECONDARY, true);
        timeline.addView(mTimeTotal);

        // Botões
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        mPlayerContent.addView(buttons, wrap(context, 18));

        for (int i = 0; i < BUTTON_LABELS.length; i++) {
            TextView button = OpenTubeStyle.text(context, 13, OpenTubeStyle.TEXT, true);
            button.setText(BUTTON_LABELS[i]);
            int padH = OpenTubeStyle.dp(context, 16);
            int padV = OpenTubeStyle.dp(context, 9);
            button.setPadding(padH, padV, padH, padV);
            button.setBackground(OpenTubeStyle.rounded(context, 0x26FFFFFF, 999, 0, 0));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = OpenTubeStyle.dp(context, 10);
            buttons.addView(button, params);
            mButtons[i] = button;
        }

        // --- Ícone central (pausa/continua) e bolha do avanço ---
        mCenterIcon = OpenTubeStyle.text(context, 30, OpenTubeStyle.TEXT, true);
        mCenterIcon.setGravity(Gravity.CENTER);
        mCenterIcon.setBackground(OpenTubeStyle.circle(0x99000000));
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

        boolean handled = mPlayerVisible ? onKeyVisible(event) : onKeyHidden(event);
        if (handled) {
            mConsumedKeys.add(code);
        }
        return handled;
    }

    private boolean onKeyHidden(KeyEvent event) {
        boolean first = event.getRepeatCount() == 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
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
                    showPlayer(SHOW_ON_KEY_MS, false);
                }
                return true;
        }
    }

    private boolean onKeyVisible(KeyEvent event) {
        boolean first = event.getRepeatCount() == 0;
        int code = event.getKeyCode();

        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_HOME:
                return false;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (first) {
                    if (mOnButtons) {
                        focusTimeline();
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

        showPlayer(SHOW_ON_KEY_MS, true);
        updateProgress(position, duration);

        long seconds = Math.abs(deltaMs) / 1000;
        showSeekBubble((deltaMs > 0 ? "+" : "−") + seconds + " s", deltaMs > 0);
    }

    private final Runnable mClearPendingSeek = () -> mPendingSeekMs = -1;

    private void togglePlay() {
        boolean play = !mFragment.getPlayWhenReady();
        mFragment.setPlayWhenReady(play);
        flashCenterIcon(play ? "▶" : "❚❚");
        showPlayer(SHOW_ON_KEY_MS, true);
        if (!play) {
            mHandler.removeCallbacks(mAutoHide); // pausado: fica na tela
        }
    }

    private void skip(boolean next) {
        mSuppressSmartTubeUiUntil = SystemClock.uptimeMillis() + SUPPRESS_SMARTTUBE_UI_MS;
        flashCenterIcon(next ? "⏭" : "⏮");
        if (next) {
            mFragment.skipToNext();
        } else {
            mFragment.skipToPrevious();
        }
    }

    private void openSmartTubeControls() {
        mSmartTubeUiRequested = true;
        setPlayerVisible(false);
        mFragment.showOverlay(true);
    }

    private void pressButton(int index) {
        TextView button = mButtons[index];
        button.animate().scaleX(0.94f).scaleY(0.94f).setDuration(OpenTubeStyle.DUR_FAST_MS / 2)
                .withEndAction(() -> button.animate().scaleX(1.06f).scaleY(1.06f)
                        .setDuration(OpenTubeStyle.DUR_FAST_MS).setInterpolator(OpenTubeStyle.SPRING).start())
                .start();

        switch (index) {
            case BUTTON_SUBTITLES:
                showSubtitlesMenu();
                break;
            case BUTTON_AUDIO:
                showAudioMenu();
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

    /** Legendas do vídeo (as originais e as automáticas do YouTube), como o SmartTube lista. */
    private void showSubtitlesMenu() {
        List<FormatItem> formats = mFragment.getSubtitleFormats();
        if (formats == null || formats.size() <= 1) {
            MessageHelpers.showMessage(mActivity, "Este vídeo não tem legendas");
            return;
        }
        PlayerData playerData = PlayerData.instance(mActivity);
        List<OptionItem> options = UiOptionItem.from(formats, option -> {
            FormatItem format = UiOptionItem.toFormat(option);
            mFragment.setFormat(format);
            playerData.setFormat(format);
        }, "Desligadas");

        AppDialogPresenter dialog = AppDialogPresenter.instance(mActivity);
        dialog.appendRadioCategory("Legendas", options);
        dialog.showDialog("Legendas");
    }

    /**
     * Faixas de áudio por idioma: a original e as dublagens (inclusive as automáticas do YouTube).
     * A escolha vira preferência: os próximos vídeos com o mesmo idioma já começam dublados.
     */
    private void showAudioMenu() {
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
        List<OptionItem> options = new ArrayList<>();
        for (Map.Entry<String, FormatItem> item : byLanguage.entrySet()) {
            final String language = item.getKey();
            final FormatItem format = item.getValue();
            CharSequence title = languageName(language);
            options.add(UiOptionItem.from(title, format.getTitle(), option -> {
                mFragment.setFormat(format);
                playerData.setAudioLanguage(primaryLanguage(language));
            }, format.isSelected()));
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(mActivity);
        dialog.appendRadioCategory("Áudio e dublagem", options);
        dialog.showDialog("Áudio e dublagem");
    }

    private static String primaryLanguage(String language) {
        if (TextUtils.isEmpty(language)) {
            return "";
        }
        return language.split("[-_.]")[0].toLowerCase(Locale.ROOT);
    }

    private static String languageName(String language) {
        if (TextUtils.isEmpty(language)) {
            return "Original";
        }
        String code = language.split("[.]")[0].replace('_', '-');
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

        TvChannel channel = OpenTubeSession.getChannel();
        if (channel != null && channel != mBugChannel) {
            mBugChannel = channel;
            mBugName.setText(channel.name);
            OpenTubeCovers.bind(mBugCover, channel, 6);
        }

        // Entrou num canal novo: "sintonizando" até o vídeo começar a tocar
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
            mSuppressSmartTubeUiUntil = SystemClock.uptimeMillis() + SUPPRESS_SMARTTUBE_UI_MS;
            if (!mTuning) {
                showPlayer(SHOW_ON_NEW_VIDEO_MS, false);
            }
        } else if (mPlayerVisible) {
            updateInfo(video, false);
        }
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

    private void showPlayer(long durationMs, boolean keepFocus) {
        Video video = mFragment != null ? mFragment.getVideo() : null;
        if (video == null) {
            return;
        }
        if (!mPlayerVisible || !keepFocus) {
            if (!mPlayerVisible) {
                focusTimeline();
            }
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
    }

    private void focusButton(int index) {
        mOnButtons = true;
        mButtonIndex = index;
        mKnob.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.6f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS).start();
        for (int i = 0; i < mButtons.length; i++) {
            styleButton(i, i == index);
        }
    }

    private void styleButton(int index, boolean focused) {
        TextView button = mButtons[index];
        button.setBackground(OpenTubeStyle.rounded(mActivity, focused ? OpenTubeStyle.BRAND : 0x26FFFFFF, 999, 0, 0));
        button.setTextColor(focused ? OpenTubeStyle.BG : OpenTubeStyle.TEXT);
        float scale = focused ? 1.06f : 1f;
        button.animate().scaleX(scale).scaleY(scale).setDuration(OpenTubeStyle.DUR_MEDIUM_MS)
                .setInterpolator(focused ? OpenTubeStyle.SPRING : OpenTubeStyle.EASE_OUT).start();
    }

    private void flashCenterIcon(String symbol) {
        mCenterIcon.setText(symbol);
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

            String description = video.description;
            if (TextUtils.isEmpty(description)) {
                description = OpenTubeRepository.instance(mActivity).getDescription(video.videoId);
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
