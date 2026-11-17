package com.liskovsoft.smartyoutubetv2.tv.ui.opentube;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.LeanbackActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenTube: grade de canais de TV, com a capa de cada canal de fundo no card.
 * Abre por cima da tela principal do SmartTube; "Voltar" leva ao YouTube completo.
 * OK num canal põe o canal no ar.
 */
public class OpenTubeActivity extends LeanbackActivity {
    private static final int COLUMNS = 6;
    private static final float CARD_RADIUS_DP = 10;
    private static final long PREFETCH_FOCUS_DELAY_MS = 600;
    private static final long PREFETCH_RETRY_MS = 1000;
    private static boolean sOpenRequested;

    private List<TvChannel> mChannels;
    private ChannelAdapter mAdapter;
    private RecyclerView mGrid;
    private final List<TvChannel> mPrefetchQueue = new ArrayList<>();
    private boolean mPrefetching;
    private boolean mEntered;
    private TvChannel mOpening;

    /** Chamado na abertura do app (SplashActivity). */
    public static void requestOpenOnNextBrowse() {
        sOpenRequested = true;
    }

    /** Chamado quando a tela principal aparece (BrowseActivity). */
    public static void openIfRequested(Activity activity) {
        if (!sOpenRequested) {
            return;
        }
        sOpenRequested = false;

        if (TvChannel.load(activity).isEmpty()) {
            return;
        }

        ViewManager.instance(activity).startView(OpenTubeView.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mChannels = TvChannel.load(this);
        OpenTubeCovers.preload(this, mChannels);
        setContentView(buildLayout());

        // Programação salva em disco: os canais já abrem "no ar"; a rede atualiza por trás.
        OpenTubeRepository.instance(this).loadFromDisk(mChannels, () -> {
            if (mAdapter != null) {
                mAdapter.notifyItemRangeChanged(0, mChannels.size());
            }
            prefetchAll();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mOpening = null;
        if (mAdapter != null) {
            mAdapter.notifyItemRangeChanged(0, mChannels.size()); // atualiza "no ar"/"carregando"
        }
    }

    private View buildLayout() {
        Context context = this;

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(OpenTubeStyle.BG);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(OpenTubeStyle.dp(context, 48), OpenTubeStyle.dp(context, 32), OpenTubeStyle.dp(context, 48), 0);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        column.addView(header);

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView brand = OpenTubeStyle.label(context, 11, OpenTubeStyle.TEXT_SECONDARY, 0.24f);
        brand.setText("OpenTube");
        titles.addView(brand);

        TextView title = OpenTubeStyle.text(context, 28, OpenTubeStyle.TEXT, true);
        title.setText("Canais");
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = OpenTubeStyle.dp(context, 6);
        titles.addView(title, titleParams);

        TextView hint = OpenTubeStyle.text(context, 12, OpenTubeStyle.TEXT_MUTED, false);
        hint.setText("OK põe no ar  ·  Voltar abre o YouTube completo");
        header.addView(hint);

        mGrid = new RecyclerView(context);
        GridLayoutManager layoutManager = new GridLayoutManager(context, COLUMNS);
        layoutManager.setItemPrefetchEnabled(true);
        mGrid.setLayoutManager(layoutManager);
        mGrid.setHasFixedSize(true);
        mGrid.setItemViewCacheSize(mChannels.size());
        mGrid.setClipToPadding(false);
        mGrid.setClipChildren(false);
        int pad = OpenTubeStyle.dp(context, 12); // espaço para o card crescer ao receber foco
        mGrid.setPadding(0, pad, 0, OpenTubeStyle.dp(context, 40));
        mGrid.setItemAnimator(null);
        final int gap = OpenTubeStyle.dp(context, 7);
        mGrid.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                outRect.set(gap, gap, gap, gap);
            }
        });
        mAdapter = new ChannelAdapter();
        mGrid.setAdapter(mAdapter);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gridParams.topMargin = OpenTubeStyle.dp(context, 14);
        column.addView(mGrid, gridParams);

        mGrid.post(() -> {
            RecyclerView.ViewHolder first = mGrid.findViewHolderForAdapterPosition(0);
            if (first != null) {
                first.itemView.requestFocus();
            }
            mEntered = true;
        });

        return root;
    }

    /** Atualiza pela rede os canais com programação antiga ou sem programação, um por vez. */
    private void prefetchAll() {
        mPrefetchQueue.clear();
        OpenTubeRepository repository = OpenTubeRepository.instance(this);
        for (TvChannel channel : mChannels) {
            if (!repository.isFresh(channel)) {
                mPrefetchQueue.add(channel);
            }
        }
        prefetchNext();
    }

    private void prefetchNext() {
        if (mPrefetching || mPrefetchQueue.isEmpty()) {
            return;
        }
        mPrefetching = true;
        final TvChannel channel = mPrefetchQueue.remove(0);
        OpenTubeRepository.instance(this).refresh(channel, videos -> {
            mPrefetching = false;
            notifyChannelChanged(channel);
            prefetchNext();
        });
        notifyChannelChanged(channel);
    }

    private void notifyChannelChanged(TvChannel channel) {
        int index = mChannels.indexOf(channel);
        if (index >= 0 && mAdapter != null) {
            mAdapter.notifyItemChanged(index);
        }
    }

    private void openChannel(final TvChannel channel, final View card) {
        if (mOpening != null) {
            return; // evita abrir dois canais com cliques repetidos
        }
        mOpening = channel;

        // Toque: o card afunda e volta, como um botão físico
        card.animate().scaleX(0.96f).scaleY(0.96f).setDuration(OpenTubeStyle.DUR_FAST_MS)
                .setInterpolator(OpenTubeStyle.EASE_OUT)
                .withEndAction(() -> card.animate().scaleX(1.08f).scaleY(1.08f)
                        .setDuration(OpenTubeStyle.DUR_MEDIUM_MS).setInterpolator(OpenTubeStyle.SPRING).start())
                .start();

        mPrefetchQueue.remove(channel);
        OpenTubeRepository.instance(this).load(channel, videos -> {
            notifyChannelChanged(channel);
            if (isFinishing() || mOpening != channel) {
                return;
            }
            if (videos == null || videos.isEmpty()) {
                mOpening = null;
            }
            OpenTubeSession.play(OpenTubeActivity.this, channel, videos);
        });
        notifyChannelChanged(channel);
    }

    private final class ChannelAdapter extends RecyclerView.Adapter<ChannelHolder> {
        @NonNull
        @Override
        public ChannelHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ChannelHolder(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull ChannelHolder holder, int position) {
            holder.bind(mChannels.get(position), position);
        }

        @Override
        public int getItemCount() {
            return mChannels.size();
        }
    }

    private final class ChannelHolder extends RecyclerView.ViewHolder {
        private final ImageView mCover;
        private final View mDim;
        private final View mScrim;
        private final TextView mName;
        private final View mRing;
        private TvChannel mChannel;
        private final Runnable mPrefetch = new Runnable() {
            @Override
            public void run() {
                if (mChannel == null || !itemView.hasFocus() || mOpening != null) {
                    return;
                }
                String first = OpenTubeRepository.instance(itemView.getContext()).firstVideoId(mChannel);
                if (first != null) {
                    OpenTubePrefetch.video(first);
                } else {
                    // App recém-aberto: a programação ainda está chegando. Tenta de novo.
                    itemView.postDelayed(this, PREFETCH_RETRY_MS);
                }
            }
        };
        private boolean mAnimatedIn;

        ChannelHolder(Context context) {
            super(new FrameLayout(context));

            FrameLayout card = (FrameLayout) itemView;
            card.setFocusable(true);
            card.setClickable(true);
            card.setClipChildren(false);
            card.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Capa quadrada: o card mede a largura da coluna e fica com a mesma altura
            mCover = new SquareImageView(context);
            mCover.setScaleType(ImageView.ScaleType.FIT_XY);
            card.addView(mCover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // O card é só a capa (ela já traz o nome). O nome escrito só aparece em canal sem capa.
            mScrim = new View(context);
            mScrim.setBackground(OpenTubeStyle.cardScrim(context, CARD_RADIUS_DP));
            FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, OpenTubeStyle.dp(context, 64), Gravity.BOTTOM);
            card.addView(mScrim, scrimParams);

            mName = OpenTubeStyle.label(context, 12, OpenTubeStyle.TEXT, 0.08f);
            mName.setSingleLine(true);
            mName.setEllipsize(TextUtils.TruncateAt.END);
            mName.setShadowLayer(6, 0, 1, 0xCC000000);
            int pad = OpenTubeStyle.dp(context, 10);
            mName.setPadding(pad, 0, pad, pad);
            card.addView(mName, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            // Escurece os cards sem foco, para o focado "acender"
            mDim = new View(context);
            mDim.setBackground(OpenTubeStyle.rounded(context, 0x73000000, CARD_RADIUS_DP, 0, 0));
            card.addView(mDim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            mRing = new View(context);
            mRing.setBackground(OpenTubeStyle.rounded(context, 0x00000000, CARD_RADIUS_DP + 2, 2, OpenTubeStyle.BRAND));
            mRing.setAlpha(0f);
            int ring = -OpenTubeStyle.dp(context, 3);
            FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            ringParams.setMargins(ring, ring, ring, ring);
            card.addView(mRing, ringParams);

            card.setOnClickListener(v -> {
                if (mChannel != null) {
                    openChannel(mChannel, v);
                }
            });

            card.setOnFocusChangeListener((v, hasFocus) -> {
                float scale = hasFocus ? 1.08f : 1f;
                v.animate().scaleX(scale).scaleY(scale)
                        .setDuration(hasFocus ? OpenTubeStyle.DUR_MEDIUM_MS : OpenTubeStyle.DUR_FAST_MS)
                        .setInterpolator(hasFocus ? OpenTubeStyle.SPRING : OpenTubeStyle.EASE_OUT)
                        .start();
                mDim.animate().alpha(hasFocus ? 0f : 1f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS).start();
                mRing.animate().alpha(hasFocus ? 1f : 0f).setDuration(OpenTubeStyle.DUR_MEDIUM_MS).start();
                if (Build.VERSION.SDK_INT >= 21) {
                    v.setTranslationZ(hasFocus ? OpenTubeStyle.dp(v.getContext(), 10) : 0);
                }
                // Parou num canal: busca já os dados do primeiro vídeo dele (e, com o app recém-aberto,
                // prepara o token do YouTube), para "sintonizar" mais rápido
                v.removeCallbacks(mPrefetch);
                if (hasFocus) {
                    v.postDelayed(mPrefetch, PREFETCH_FOCUS_DELAY_MS);
                }
            });
        }

        void bind(TvChannel channel, int position) {
            boolean sameChannel = channel == mChannel;
            mChannel = channel;
            if (!sameChannel) {
                OpenTubeCovers.bind(mCover, channel, CARD_RADIUS_DP);
            }
            mName.setText(channel.name);
            int nameVisibility = channel.cover == null ? View.VISIBLE : View.GONE;
            mName.setVisibility(nameVisibility);
            mScrim.setVisibility(nameVisibility);
            itemView.setContentDescription(channel.name);
            mDim.setAlpha(itemView.hasFocus() ? 0f : 1f);

            // Entrada: os cards sobem e aparecem em cascata na primeira vez
            if (!mAnimatedIn && !mEntered) {
                mAnimatedIn = true;
                itemView.setAlpha(0f);
                itemView.setTranslationY(OpenTubeStyle.dp(itemView.getContext(), 16));
                itemView.animate().alpha(1f).translationY(0f)
                        .setStartDelay(Math.min(position, 18) * 22L)
                        .setDuration(OpenTubeStyle.DUR_SLOW_MS)
                        .setInterpolator(OpenTubeStyle.EASE_OUT)
                        .withEndAction(() -> itemView.animate().setStartDelay(0))
                        .start();
            } else {
                mAnimatedIn = true;
            }
        }
    }

    /** ImageView que fica quadrado: altura = largura. */
    private static final class SquareImageView extends ImageView {
        SquareImageView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }
}
