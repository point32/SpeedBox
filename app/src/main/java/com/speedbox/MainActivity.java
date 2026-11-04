package com.speedbox;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.speedbox.ui.LazerUi;
import com.speedbox.ui.LazerUi.FooterButtonView;
import com.speedbox.ui.LazerUi.LazerEasing;
import com.speedbox.ui.LazerUi.MenuItemType;
import com.speedbox.ui.LazerUi.OsuMenuItem;
import com.speedbox.ui.LazerUi.OsuMenuItemSpacer;
import com.speedbox.ui.LazerUi.OsuContextMenuContainer;
import com.speedbox.ui.LazerUi.OsuColour;
import com.speedbox.ui.LazerUi.OsuCookieButtonView;
import com.speedbox.ui.LazerUi.OsuProgressBarView;
import com.speedbox.ui.LazerUi.OverlayPanelDrawable;
import com.speedbox.ui.LazerUi.PanelBeatmapDrawable;
import com.speedbox.ui.LazerUi.PanelSetDrawable;
import com.speedbox.ui.LazerUi.RoundedButtonView;
import com.speedbox.ui.LazerUi.ShearedButtonView;
import com.speedbox.ui.LazerUi.ShearedDrawable;
import com.speedbox.ui.LazerUi.ShearedSearchBoxDrawable;
import com.speedbox.ui.LazerUi.ShearedSliderView;
import com.speedbox.ui.LazerUi.StarRatingPillAndCounterView;
import com.speedbox.ui.LazerUi.StarSpectrumDotsView;
import com.speedbox.ui.LazerUi.WedgeDrawable;
import com.speedbox.ui.SettingsPanelView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SpeedBox 主界面 (v3 完整动效与布局精修版)：
 * 1. 修复任务队列卡片与 Mod 定制面板因大高度斜切导致的平行四边形变形与按钮出界问题（采用 OverlayPanelDrawable 端正圆角面板）。
 * 2. 修复预设倍率 HorizontalScrollView 边界裁剪问题（开启水平渐隐边缘 requiresFadingEdge + 内边距 + 内部 Canvas 弹性缩放）。
 * 3. 实现 osu!lazer 标志性的选歌轮盘微弧形视差滚动 (BeatmapCarousel Parabolic Arc)：屏幕中心卡片向左突出、上下两端平滑向右收拢。
 * 4. 完整接入 osu!lazer 源码级动效：
 *    - 卡片点击白光消散 (ActivationFlash 650ms OutQuint) 与左移弹性过渡
 *    - 曲目集展开时子难度卡片级联滑入 (Staggered OutQuint Cascade)
 *    - 10 颗星星波浪式弹性绽放 (StarCounter OutElasticHalf) 与星级数字滚动
 *    - 选中难度卡片与右下角 osu! 大圆饼按当前歌曲真实 BPM 同步节拍律动 (PulsatingBox)
 *    - 顶栏楔形块、底栏凸出按钮、Mod 面板与任务弹窗的 OutQuint / OutCubic 丝滑入场与收起过渡
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "speedbox_lazer_prefs";
    private static final String KEY_PATH = "beatmap_path";
    private static final String KEY_SPEED = "speed_spec";
    private static final String KEY_BPM_UNIT = "bpm_unit";
    private static final String KEY_MODE = "output_mode";
    private static final String KEY_LOCK_PITCH = "lock_pitch";
    private static final String KEY_SEMITONES = "pitch_semitones";

    private static final int REQ_IMPORT_ARCHIVE = 1001;

    private static final String[] PRESET_DIRS = {
            "/storage/emulated/0/data/malody/beatmap",
            "/storage/emulated/0/Android/data/me.mugzone.malody/files/beatmap",
            "/storage/emulated/0/osu!droid/Songs",
            "/storage/emulated/0/Download"
    };

    // UI References — Top Wedges
    private ImageView ivGlobalBgCover;
    private LinearLayout permBanner;
    private ShearedButtonView btnGrantPerm;
    private LinearLayout titleWedgeContainer;
    private LinearLayout difficultyDisplaySubWedge;
    private LinearLayout wedgeStatsInnerBox;
    private TextView tvWedgeStatusPill;
    private TextView tvWedgeStarPill;
    private TextView tvWedgeVersion;
    private TextView tvWedgeArtist;
    private TextView tvAutoSyncBadge;
    private ImageView ivWedgeCover;
    private TextView tvWedgeTitle;
    private TextView tvWedgePath;
    private TextView tvBpmConvertPreview;
    private TextView tvPitchPreviewBadge;

    // UI References — FilterControl Wedge
    private LinearLayout filterWedgeContainer;
    private FrameLayout searchBoxFrame;
    private EditText etSearch;
    private TextView btnClearSearch;
    private ShearedButtonView btnImportZip;
    private ShearedButtonView btnSelectCurrentSong;
    private ShearedButtonView btnClearSelection;
    private ShearedButtonView tabFilterAll;
    private ShearedButtonView tabFilterSelected;
    private ShearedButtonView tabFilterMc;
    private ShearedButtonView tabFilterOsu;
    private TextView tvLibraryStats;

    // UI References — Right-Anchored Carousel
    private ListView lvCarousel;
    private TextView tvEmptyCarousel;
    private CarouselAdapter carouselAdapter;
    private Library.Chart lastFocusedChart = null;

    // UI References — osu!lazer SettingsPanel (变速与输出)
    private SettingsPanelView settingsPanel;

    // UI References — Task Queue & Logs Overlay
    private FrameLayout taskOverlayContainer;
    private LinearLayout taskOverlayCard;
    private ShearedButtonView btnClearDoneTasks;
    private ShearedButtonView btnCopyLogs;
    private ListView lvTasks;
    private ScrollView svLog;
    private LinearLayout llLogLines;
    private String lastLogText = "";
    private TaskAdapter taskAdapter;

    // UI References — ScreenFooter & OsuCookieButton
    private ShearedButtonView btnBackExit;
    private FooterButtonView footerBtnSettings;
    private FooterButtonView footerBtnDir;
    private FooterButtonView footerBtnQueue;
    private OsuCookieButtonView btnOsuCookieStart;
    private OsuContextMenuContainer contextMenuContainer;

    // State & Data
    private final List<Library.Song> allSongs = new ArrayList<>();
    private final List<CarouselRow> visibleRows = new ArrayList<>();
    private final Set<Library.Chart> selectedCharts = new HashSet<>();
    private final Set<File> expandedSongDirs = new HashSet<>();
    private final Map<String, Long> dirFps = new HashMap<>();
    private final Map<Object, Long> flashTimestamps = new HashMap<>();
    private File newlyExpandedDir = null;
    private long newlyExpandedAtMs = 0L;
    private Library.Chart focusedChart = null;

    private int currentFilterTab = 0;
    private boolean bpmUnit = false;
    private boolean lockPitch = true;
    private int pitchSemitones = 0;
    private int outputMode = Library.MODE_LOOSE;
    private float speedValue = 1.25f;
    private String speedSpecText = "1.25";
    private volatile boolean syncInFlight = false;

    private LruCache<String, Bitmap> coverCache;
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbPool = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final class CarouselRow {
        final boolean isSongHeader;
        final Library.Song song;
        final Library.Chart chart;
        final float starRating;
        final float[] songStarSpread;
        final int diffIndexInSong;

        CarouselRow(Library.Song song, float[] spread) {
            this.isSongHeader = true;
            this.song = song;
            this.chart = null;
            this.starRating = spread.length > 0 ? spread[spread.length - 1] : 2.5f;
            this.songStarSpread = spread;
            this.diffIndexInSong = 0;
        }

        CarouselRow(Library.Song song, Library.Chart chart, float star, int diffIdx) {
            this.isSongHeader = false;
            this.song = song;
            this.chart = chart;
            this.starRating = star;
            this.songStarSpread = null;
            this.diffIndexInSong = diffIdx;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        int maxMem = (int) (Runtime.getRuntime().maxMemory() / 1024);
        coverCache = new LruCache<String, Bitmap>(Math.max(4096, maxMem / 8)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        bindViews();
        loadPreferences();
        setupInteractions();
        setupCarouselCurveAndAnimations();
        playScreenEntranceAnimations();
        startUiTickLoop();

        loadDiskCacheThenAutoSync();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionBanner();
        triggerAutoIncrementalSync(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        ioPool.shutdownNow();
        thumbPool.shutdownNow();
    }

    private void bindViews() {
        ivGlobalBgCover = findViewById(R.id.ivGlobalBgCover);
        permBanner = findViewById(R.id.permBanner);
        btnGrantPerm = findViewById(R.id.btnGrantPerm);
        titleWedgeContainer = findViewById(R.id.titleWedgeContainer);
        difficultyDisplaySubWedge = findViewById(R.id.difficultyDisplaySubWedge);
        wedgeStatsInnerBox = findViewById(R.id.wedgeStatsInnerBox);
        tvWedgeStatusPill = findViewById(R.id.tvWedgeStatusPill);
        tvWedgeStarPill = findViewById(R.id.tvWedgeStarPill);
        tvWedgeVersion = findViewById(R.id.tvWedgeVersion);
        tvWedgeArtist = findViewById(R.id.tvWedgeArtist);
        tvAutoSyncBadge = findViewById(R.id.tvAutoSyncBadge);
        ivWedgeCover = findViewById(R.id.ivWedgeCover);
        tvWedgeTitle = findViewById(R.id.tvWedgeTitle);
        tvWedgePath = findViewById(R.id.tvWedgePath);
        tvBpmConvertPreview = findViewById(R.id.tvBpmConvertPreview);
        tvPitchPreviewBadge = findViewById(R.id.tvPitchPreviewBadge);

        filterWedgeContainer = findViewById(R.id.filterWedgeContainer);
        searchBoxFrame = findViewById(R.id.searchBoxFrame);
        etSearch = findViewById(R.id.etSearch);
        btnClearSearch = findViewById(R.id.btnClearSearch);
        // 搜索清除按钮：osu 风格的按压反馈（0.9 缩放 + OutQuint 回弹）
        btnClearSearch.setBackground(directoryRowBackground());
        btnClearSearch.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100)
                            .setInterpolator(LazerEasing.OUT_QUINT).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(250)
                            .setInterpolator(LazerEasing.OUT_ELASTIC).start();
                    break;
                default:
                    break;
            }
            return false;
        });
        btnImportZip = findViewById(R.id.btnImportZip);
        btnSelectCurrentSong = findViewById(R.id.btnSelectCurrentSong);
        btnClearSelection = findViewById(R.id.btnClearSelection);
        tabFilterAll = findViewById(R.id.tabFilterAll);
        tabFilterSelected = findViewById(R.id.tabFilterSelected);
        tabFilterMc = findViewById(R.id.tabFilterMc);
        tabFilterOsu = findViewById(R.id.tabFilterOsu);
        tvLibraryStats = findViewById(R.id.tvLibraryStats);

        lvCarousel = findViewById(R.id.lvCarousel);
        tvEmptyCarousel = findViewById(R.id.tvEmptyCarousel);

        settingsPanel = findViewById(R.id.settingsPanel);

        taskOverlayContainer = findViewById(R.id.taskOverlayContainer);
        taskOverlayCard = findViewById(R.id.taskOverlayCard);
        btnClearDoneTasks = findViewById(R.id.btnClearDoneTasks);
        btnCopyLogs = findViewById(R.id.btnCopyLogs);
        lvTasks = findViewById(R.id.lvTasks);
        svLog = findViewById(R.id.svLog);
        llLogLines = findViewById(R.id.llLogLines);

        btnBackExit = findViewById(R.id.btnBackExit);
        footerBtnSettings = findViewById(R.id.footerBtnSettings);
        footerBtnDir = findViewById(R.id.footerBtnDir);
        footerBtnQueue = findViewById(R.id.footerBtnQueue);
        btnOsuCookieStart = findViewById(R.id.btnOsuCookieStart);
        contextMenuContainer = findViewById(R.id.osuContextMenuContainer);

        permBanner.setBackground(ShearedDrawable.create(
                this, OsuColour.BACKGROUND_4, OsuColour.ORANGE_1));

        titleWedgeContainer.setBackground(new WedgeDrawable(this, 0.90f, 0.58f, false));
        difficultyDisplaySubWedge.setBackground(new WedgeDrawable(this, 0.88f, 0.55f, false));
        wedgeStatsInnerBox.setBackground(new ShearedDrawable(
                0.2f, LazerUi.dp(this, 8f), 0f,
                OsuColour.withAlpha(OsuColour.BACKGROUND_6, 0.82f),
                OsuColour.withAlpha(OsuColour.BACKGROUND_6, 0.82f)));

        filterWedgeContainer.setBackground(new WedgeDrawable(this, 0.92f, 0.65f, true));
        searchBoxFrame.setBackground(new ShearedSearchBoxDrawable(this));

        // osu NotificationOverlay: 平铺 Background4，无描边/强调条
        taskOverlayCard.setBackground(flatPanel(OsuColour.BACKGROUND_4, 10f));

        // Configure ShearedButtons
        btnGrantPerm.configure("授予权限", OsuColour.ORANGE_1, OsuColour.ORANGE_1, OsuColour.BACKGROUND_6, OsuColour.BACKGROUND_6, 11.5f);
        btnImportZip.configure("导入包", OsuColour.BACKGROUND_3, OsuColour.HIGHLIGHT_1, OsuColour.CONTENT_1, OsuColour.BACKGROUND_6, 11f);
        btnSelectCurrentSong.configure("全选曲", OsuColour.BACKGROUND_3, OsuColour.HIGHLIGHT_1, OsuColour.CONTENT_1, OsuColour.BACKGROUND_6, 11f);
        btnClearSelection.configure("清空", OsuColour.BACKGROUND_3, OsuColour.RED_1, OsuColour.CONTENT_1, OsuColour.BACKGROUND_6, 11f);

        tabFilterAll.configure("全部", OsuColour.BACKGROUND_3, OsuColour.HIGHLIGHT_1, OsuColour.CONTENT_1, OsuColour.BACKGROUND_6, 10.5f);
        tabFilterSelected.configure("已勾选", OsuColour.BACKGROUND_3, OsuColour.HIGHLIGHT_1, OsuColour.CONTENT_1, OsuColour.BACKGROUND_6, 10.5f);
        tabFilterMc.configure("Malody", OsuColour.BACKGROUND_3, OsuColour.HIGHLIGHT_1, OsuColour.CONTENT_1, OsuColour.BACKGROUND_6, 10.5f);
        tabFilterOsu.configure("osu!", OsuColour.BACKGROUND_3, OsuColour.OSU_PINK, OsuColour.CONTENT_1, OsuColour.BACKGROUND_6, 10.5f);

        btnClearDoneTasks.configure("清理完成", OsuColour.BACKGROUND_3, OsuColour.HIGHLIGHT_1, OsuColour.CONTENT_1, OsuColour.BACKGROUND_6, 10.5f);
        btnCopyLogs.configure("复制日志", OsuColour.BACKGROUND_3, OsuColour.HIGHLIGHT_1, OsuColour.CONTENT_1, OsuColour.BACKGROUND_6, 10.5f);

        btnBackExit.configure("◀ 返回", OsuColour.BACK_DARKER, OsuColour.BACK_DARKER, OsuColour.CONTENT_1, OsuColour.CONTENT_1, 11f);
        btnBackExit.setBorderGradient(OsuColour.BACK_DARKER, OsuColour.BACK_LIGHTER);

        footerBtnSettings.configure("变速设置", "1.25x", OsuColour.LIME_1);
        footerBtnSettings.setIconType(FooterButtonView.ICON_GEAR);
        footerBtnDir.configure("曲库目录", "MALODY", OsuColour.PURPLE_1);
        footerBtnQueue.configure("任务队列", "IDLE", OsuColour.HIGHLIGHT_1);

        carouselAdapter = new CarouselAdapter();
        lvCarousel.setAdapter(carouselAdapter);

        taskAdapter = new TaskAdapter();
        lvTasks.setAdapter(taskAdapter);
    }

    // =========================================================================
    // BeatmapCarousel Parabolic Curve & Screen Entrance Animations
    // =========================================================================
    private void setupCarouselCurveAndAnimations() {
        lvCarousel.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {}

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                applyCarouselArcToVisibleChildren();
            }
        });

        lvCarousel.getViewTreeObserver().addOnPreDrawListener(() -> {
            applyCarouselReflow();
            applyCarouselArcToVisibleChildren();
            return true;
        });
    }

    // carousel 选中偏移补间状态（osu Panel.updateXOffset(): MoveToX(x, 400, OutQuint)）
    private static final int TAG_SEL_FROM = 0x0f5e0001;
    private static final int TAG_SEL_TO = 0x0f5e0002;
    private static final int TAG_SEL_START = 0x0f5e0003;
    private static final int TAG_SEL_KEY = 0x0f5e0004;
    private static final int TAG_REF_FROM = 0x0f5e0005;
    private static final int TAG_REF_START = 0x0f5e0006;
    private static final int TAG_EXITING = 0x0f5e0007;
    private static final float SEL_MOVE_DURATION_MS = 400f;
    /** Carousel.cs: Interpolation.DampContinuously(DrawYPosition, target, 50, elapsed) */
    private static final float CAROUSEL_REFLOW_HALF_LIFE_MS = 50f;

    private final java.util.HashMap<Object, Integer> carouselRowTops = new java.util.HashMap<>();
    private boolean carouselReflowPending = false;

    /** 新行/复用到别的行时把补间状态归位，避免 Recycler 复用把上一行的动画值带过来。 */
    private void resetSelectionTweenIfRecycled(View cardRoot, Object rowKey, float target) {
        Object prev = cardRoot.getTag(TAG_SEL_KEY);
        if (prev == null || !prev.equals(rowKey)) {
            cardRoot.setTag(TAG_SEL_KEY, rowKey);
            cardRoot.setTag(TAG_SEL_FROM, target);
            cardRoot.setTag(TAG_SEL_TO, target);
            cardRoot.setTag(TAG_SEL_START, 0L);
            cardRoot.setTag(TAG_REF_FROM, 0f);
            cardRoot.setTag(TAG_REF_START, 0L);
            cardRoot.setTag(TAG_EXITING, Boolean.FALSE);
            cardRoot.setAlpha(1f);
            cardRoot.setTranslationY(0f);
        }
    }

    private View carouselCardRoot(View rowView) {
        if (rowView == null) return null;
        View v = rowView.findViewById(R.id.songCardRoot);
        return v != null ? v : rowView.findViewById(R.id.chartCardRoot);
    }

    private Object carouselRowKey(View rowView) {
        View cardRoot = carouselCardRoot(rowView);
        return cardRoot != null ? cardRoot.getTag(TAG_SEL_KEY) : null;
    }

    /** 记录当前可见行的位置；下次 layout 后据此补间竖向位移。 */
    private void captureCarouselRowOffsets() {
        carouselRowTops.clear();
        if (lvCarousel == null) {
            carouselReflowPending = false;
            return;
        }
        for (int i = 0; i < lvCarousel.getChildCount(); i++) {
            View rowView = lvCarousel.getChildAt(i);
            Object key = carouselRowKey(rowView);
            if (key != null) carouselRowTops.put(key, rowView.getTop());
        }
        carouselReflowPending = true;
    }

    /**
     * Carousel.cs: 每个 panel 的 DrawYPosition 用 DampContinuously(halfLife = 50ms) 逼近
     * 目标位置；新 materialise 的 panel 用 FadeIn(400, OutQuint)。
     */
    private void applyCarouselReflow() {
        if (lvCarousel == null) return;
        long now = SystemClock.uptimeMillis();

        if (carouselReflowPending) {
            carouselReflowPending = false;
            for (int i = 0; i < lvCarousel.getChildCount(); i++) {
                View rowView = lvCarousel.getChildAt(i);
                View cardRoot = carouselCardRoot(rowView);
                if (cardRoot == null) continue;

                Object key = carouselRowKey(rowView);
                Integer oldTop = key != null ? carouselRowTops.get(key) : null;
                if (oldTop != null) {
                    float delta = oldTop - rowView.getTop();
                    cardRoot.setTag(TAG_REF_FROM, delta);
                    cardRoot.setTag(TAG_REF_START, Math.abs(delta) > 0.5f ? now : 0L);
                } else {
                    cardRoot.setTag(TAG_REF_FROM, 0f);
                    cardRoot.setTag(TAG_REF_START, 0L);
                    // 不影响 chart 展开时的级联动画
                    Object extra = cardRoot.getTag(R.id.rootContainer);
                    boolean cascading = extra instanceof Float && ((Float) extra) != 0f;
                    if (!cascading) {
                        cardRoot.setAlpha(0f);
                        cardRoot.animate().alpha(1f).setDuration(400)
                                .setInterpolator(LazerEasing.OUT_QUINT).start();
                    }
                }
            }
            carouselRowTops.clear();
        }

        for (int i = 0; i < lvCarousel.getChildCount(); i++) {
            View rowView = lvCarousel.getChildAt(i);
            View cardRoot = carouselCardRoot(rowView);
            if (cardRoot == null) continue;

            float value = 0f;
            Object fromObj = cardRoot.getTag(TAG_REF_FROM);
            Object startObj = cardRoot.getTag(TAG_REF_START);
            if (fromObj instanceof Float && startObj instanceof Long) {
                float from = (Float) fromObj;
                long start = (Long) startObj;
                if (start != 0L) {
                    float elapsed = now - start;
                    value = from * (float) Math.pow(0.5, elapsed / CAROUSEL_REFLOW_HALF_LIFE_MS);
                    if (Math.abs(value) < 0.5f) {
                        value = 0f;
                        cardRoot.setTag(TAG_REF_START, 0L);
                    } else {
                        lvCarousel.postInvalidateOnAnimation();
                    }
                }
            }
            cardRoot.setTranslationY(value);
        }
    }

    /** 收起时先 FadeOut(150, OutQuint) 再重建列表（对应 Carousel.cs 过期 panel 淡出）。 */
    private void collapseSongAnimated(Library.Song song) {
        boolean any = false;
        if (lvCarousel != null && song != null) {
            int first = lvCarousel.getFirstVisiblePosition();
            for (int i = 0; i < lvCarousel.getChildCount(); i++) {
                int pos = first + i;
                if (pos < 0 || pos >= visibleRows.size()) continue;
                CarouselRow r = visibleRows.get(pos);
                if (r == null || r.isSongHeader || r.chart == null || r.song == null) continue;
                if (!song.dir.equals(r.song.dir)) continue;

                View cardRoot = carouselCardRoot(lvCarousel.getChildAt(i));
                if (cardRoot == null) continue;
                any = true;
                cardRoot.setTag(TAG_EXITING, Boolean.TRUE);
                cardRoot.animate().alpha(0f).setDuration(150)
                        .setInterpolator(LazerEasing.OUT_QUINT).start();
            }
        }
        mainHandler.postDelayed(() -> {
            if (song != null) expandedSongDirs.remove(song.dir);
            rebuildVisibleCarousel();
            updateWedgeAndStatsUi();
        }, any ? 160L : 0L);
    }

    private float selectionTweenValue(float from, float to, long start, long now) {
        if (start == 0L) return to;
        float t = Math.min(1f, (now - start) / SEL_MOVE_DURATION_MS);
        return from + (to - from) * LazerEasing.outQuint(t);
    }

    private float updateSelectionTween(View cardRoot, float targetSel) {
        long now = SystemClock.uptimeMillis();
        Object fromObj = cardRoot.getTag(TAG_SEL_FROM);
        Object toObj = cardRoot.getTag(TAG_SEL_TO);
        Object startObj = cardRoot.getTag(TAG_SEL_START);

        if (fromObj == null || toObj == null || startObj == null) {
            cardRoot.setTag(TAG_SEL_FROM, targetSel);
            cardRoot.setTag(TAG_SEL_TO, targetSel);
            cardRoot.setTag(TAG_SEL_START, 0L);
            return targetSel;
        }

        float from = (Float) fromObj;
        float to = (Float) toObj;
        long start = (Long) startObj;

        if (Math.abs(to - targetSel) > 0.01f) {
            // 从当前视觉值重新开始 400ms OutQuint
            from = selectionTweenValue(from, to, start, now);
            to = targetSel;
            start = now;
            cardRoot.setTag(TAG_SEL_FROM, from);
            cardRoot.setTag(TAG_SEL_TO, to);
            cardRoot.setTag(TAG_SEL_START, start);
        }

        if (start == 0L) return to;

        float t = Math.min(1f, (now - start) / SEL_MOVE_DURATION_MS);
        if (t < 1f) lvCarousel.postInvalidateOnAnimation();
        return from + (to - from) * LazerEasing.outQuint(t);
    }

    /**
     * Exact 1:1 port of osu!lazer Carousel.cs arc offset:
     * const float circle_radius = 3;
     * float discriminant = MathF.Max(0, circle_radius * circle_radius - dist * dist);
     * return (circle_radius - MathF.Sqrt(discriminant)) * halfHeight;
     */
    private void applyCarouselArcToVisibleChildren() {
        int listH = lvCarousel.getHeight();
        if (listH <= 0) return;
        float centerY = listH * 0.48f;
        float halfSpan = Math.max(1f, listH * 0.50f);

        // osu!lazer factor: visibleHalfHeight with safe wheel deflection
        float factor = Math.max(LazerUi.dp(this, 180f), halfSpan * 0.45f);

        int count = lvCarousel.getChildCount();
        for (int i = 0; i < count; i++) {
            View rowView = lvCarousel.getChildAt(i);
            if (rowView == null) continue;
            View cardRoot = rowView.findViewById(R.id.songCardRoot);
            if (cardRoot == null) cardRoot = rowView.findViewById(R.id.chartCardRoot);
            if (cardRoot == null) continue;

            float rowCenterY = (rowView.getTop() + rowView.getBottom()) * 0.5f;
            float dist = Math.abs((rowCenterY - centerY) / halfSpan);

            // Exact osu!lazer circle_radius formula:
            final float circleRadius = 3f;
            float discriminant = Math.max(0f, circleRadius * circleRadius - dist * dist);
            float arcOffset = (circleRadius - (float) Math.sqrt(discriminant)) * factor;

            Object extraObj = cardRoot.getTag(R.id.rootContainer);
            float animExtraX = (extraObj instanceof Float) ? (Float) extraObj : 0f;

            Object selSongObj = cardRoot.getTag(R.id.songCardRoot);
            Object selChartObj = cardRoot.getTag(R.id.chartCardRoot);
            float targetSel = (selSongObj instanceof Float)
                    ? (Float) selSongObj
                    : ((selChartObj instanceof Float) ? (Float) selChartObj : 0f);

            // osu Panel.updateXOffset(): MoveToX(x, 400, Easing.OutQuint)
            float curSel = updateSelectionTween(cardRoot, targetSel);

            // Card and all its child elements (title, meta, dots, action buttons, checkboxes)
            // translate together as a single unified solid unit on the circular carousel track.
            cardRoot.setTranslationX(arcOffset + animExtraX + curSel);
        }
    }

    private void playScreenEntranceAnimations() {
        // SongSelect.cs PopIn: Top wedges slide in from -120dp with OutQuint (550ms)
        float shiftLeft = -LazerUi.dp(this, 110f);
        titleWedgeContainer.setTranslationX(shiftLeft);
        titleWedgeContainer.setAlpha(0f);
        titleWedgeContainer.animate()
                .translationX(0f).alpha(1f)
                .setDuration(520)
                .setInterpolator(LazerEasing.OUT_QUINT)
                .start();

        filterWedgeContainer.setTranslationX(shiftLeft);
        filterWedgeContainer.setAlpha(0f);
        filterWedgeContainer.animate()
                .translationX(0f).alpha(1f)
                .setStartDelay(55)
                .setDuration(520)
                .setInterpolator(LazerEasing.OUT_QUINT)
                .start();

        // ScreenFooter.cs AppearFromBottom: Staggered 35ms OutCubic pop-up
        View[] footerViews = {btnBackExit, footerBtnSettings, footerBtnDir, footerBtnQueue, btnOsuCookieStart};
        for (int i = 0; i < footerViews.length; i++) {
            View fv = footerViews[i];
            fv.setTranslationY(LazerUi.dp(this, 65f));
            fv.setAlpha(0f);
            fv.animate()
                    .translationY(0f).alpha(1f)
                    .setStartDelay(80L + i * 35L)
                    .setDuration(360)
                    .setInterpolator(LazerEasing.OUT_CUBIC)
                    .start();
        }
    }

    private void toggleAnimatedDrawer(FrameLayout overlay, View card, FooterButtonView btn, boolean show) {
        if (btn != null) btn.setActive(show);
        if (show) {
            overlay.setVisibility(View.VISIBLE);
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(400).setInterpolator(LazerEasing.OUT_QUINT).start();
            if (card != null) {
                card.setTranslationY(LazerUi.dp(this, 120f));
                card.setAlpha(0f);
                card.animate()
                        .translationY(0f).alpha(1f)
                        .setDuration(400)
                        .setInterpolator(LazerEasing.OUT_QUINT)
                        .withEndAction(null)
                        .start();
            }
        } else {
            overlay.animate().alpha(0f).setDuration(350).setInterpolator(LazerEasing.OUT_QUINT).start();
            if (card != null) {
                card.animate()
                        .translationY(LazerUi.dp(this, 100f)).alpha(0f)
                        .setDuration(350)
                        .setInterpolator(LazerEasing.OUT_QUINT)
                        .withEndAction(() -> overlay.setVisibility(View.GONE))
                        .start();
            } else {
                overlay.animate().withEndAction(() -> overlay.setVisibility(View.GONE)).start();
            }
        }
    }

    private void loadPreferences() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedPath = sp.getString(KEY_PATH, PRESET_DIRS[0]);
        tvWedgePath.setText(savedPath);

        String savedSpec = sp.getString(KEY_SPEED, "1.25");
        bpmUnit = sp.getBoolean(KEY_BPM_UNIT, false);
        outputMode = sp.getInt(KEY_MODE, Library.MODE_LOOSE);
        lockPitch = sp.getBoolean(KEY_LOCK_PITCH, true);
        pitchSemitones = sp.getInt(KEY_SEMITONES, 0);
        speedSpecText = savedSpec;

        configureSliderRange(savedSpec);
        syncSettingsUi();
    }

    private void savePreferences() {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        ed.putString(KEY_PATH, tvWedgePath.getText().toString().trim());
        ed.putString(KEY_SPEED, speedSpecText);
        ed.putBoolean(KEY_BPM_UNIT, bpmUnit);
        ed.putInt(KEY_MODE, outputMode);
        ed.putBoolean(KEY_LOCK_PITCH, lockPitch);
        ed.putInt(KEY_SEMITONES, pitchSemitones);
        ed.apply();
    }

    private void configureSliderRange(String specText) {
        float parsed = parsePrimarySpeed(specText, bpmUnit ? 200f : 1.25f);
        speedValue = parsed;
        if (bpmUnit) {
            settingsPanel.configureSpeedSlider(60f, 360f, 5f, parsed, 180f, "目标 BPM (TARGET BPM)", "%.0f BPM");
        } else {
            settingsPanel.configureSpeedSlider(0.50f, 2.00f, 0.05f, parsed, 1.00f, "变速倍率 (RATE MULTIPLIER)", "%.2fx");
        }
    }

    private static String formatSpec(float value, boolean bpmUnit) {
        if (bpmUnit) return String.valueOf(Math.round(value));
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void setupInteractions() {
        btnGrantPerm.setOnClickListener(v -> requestStoragePermission());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                rebuildVisibleCarousel();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        btnClearSearch.setOnClickListener(v -> etSearch.setText(""));

        View.OnClickListener filterClick = v -> {
            if (v == tabFilterAll) currentFilterTab = 0;
            else if (v == tabFilterSelected) currentFilterTab = 1;
            else if (v == tabFilterMc) currentFilterTab = 2;
            else if (v == tabFilterOsu) currentFilterTab = 3;
            syncFilterTabsUi();
            rebuildVisibleCarousel();
        };
        tabFilterAll.setOnClickListener(filterClick);
        tabFilterSelected.setOnClickListener(filterClick);
        tabFilterMc.setOnClickListener(filterClick);
        tabFilterOsu.setOnClickListener(filterClick);
        syncFilterTabsUi();

        btnImportZip.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(Intent.createChooser(intent, "选择 .mcz 或 .osz 谱面包"), REQ_IMPORT_ARCHIVE);
        });

        btnSelectCurrentSong.setOnClickListener(v -> {
            for (CarouselRow row : visibleRows) {
                if (!row.isSongHeader && row.chart != null) {
                    selectedCharts.add(row.chart);
                } else if (row.isSongHeader && row.song != null) {
                    selectedCharts.addAll(row.song.charts);
                }
            }
            updateWedgeAndStatsUi();
            carouselAdapter.notifyDataSetChanged();
        });

        btnClearSelection.setOnClickListener(v -> {
            selectedCharts.clear();
            updateWedgeAndStatsUi();
            carouselAdapter.notifyDataSetChanged();
        });

        // osu!lazer SettingsPanel 回调 —— 唯一的速度/音高/输出入口
        settingsPanel.setListener(new SettingsPanelView.Listener() {
            @Override public void onModeChanged(boolean pitchLocked) {
                lockPitch = pitchLocked;
                savePreferences();
                syncSettingsUi();
            }
            @Override public void onUnitChanged(boolean bpm) {
                double baseBpm = (focusedChart != null && focusedChart.bpm > 0) ? focusedChart.bpm : 180.0;
                if (bpm) speedValue = (float) Math.round(baseBpm * speedValue);
                else speedValue = (float) (speedValue / baseBpm);
                bpmUnit = bpm;
                speedSpecText = formatSpec(speedValue, bpmUnit);
                configureSliderRange(speedSpecText);
                savePreferences();
                syncSettingsUi();
            }
            @Override public void onSpeedChanged(float value) {
                speedValue = value;
                speedSpecText = formatSpec(value, bpmUnit);
                savePreferences();
                syncSettingsUi();
            }
            @Override public void onSemitonesChanged(int semitones) {
                pitchSemitones = semitones;
                savePreferences();
                syncSettingsUi();
            }
            @Override public void onOutputChanged(int libraryMode) {
                outputMode = libraryMode;
                savePreferences();
                syncSettingsUi();
            }
            @Override public void onCloseRequested() {
                settingsPanel.close();
                footerBtnSettings.setActive(false);
            }
        });

        footerBtnSettings.setOnClickListener(v -> {
            if (settingsPanel.isOpen()) {
                settingsPanel.close();
                footerBtnSettings.setActive(false);
            } else {
                if (taskOverlayContainer.getVisibility() == View.VISIBLE) {
                    toggleAnimatedDrawer(taskOverlayContainer, taskOverlayCard, footerBtnQueue, false);
                }
                syncSettingsUi();
                settingsPanel.open();
                footerBtnSettings.setActive(true);
            }
        });

        taskOverlayContainer.setOnClickListener(v -> {
            if (taskOverlayContainer.getVisibility() == View.VISIBLE) {
                toggleAnimatedDrawer(taskOverlayContainer, taskOverlayCard, footerBtnQueue, false);
            }
        });

        btnBackExit.setOnClickListener(v -> {
            if (contextMenuContainer != null && contextMenuContainer.isMenuOpen()) {
                contextMenuContainer.closeMenu();
            } else if (taskOverlayContainer.getVisibility() == View.VISIBLE) {
                toggleAnimatedDrawer(taskOverlayContainer, taskOverlayCard, footerBtnQueue, false);
            } else if (settingsPanel.isOpen()) {
                settingsPanel.close();
                footerBtnSettings.setActive(false);
            } else {
                finish();
            }
        });

        footerBtnDir.setOnClickListener(v -> showDirectorySwitchDialog());

        footerBtnQueue.setOnClickListener(v -> {
            boolean show = taskOverlayContainer.getVisibility() != View.VISIBLE;
            if (show && settingsPanel.isOpen()) {
                settingsPanel.close();
                footerBtnSettings.setActive(false);
            }
            if (show) {
                refreshTaskAndLogOverlay();
            }
            toggleAnimatedDrawer(taskOverlayContainer, taskOverlayCard, footerBtnQueue, show);
        });

        btnClearDoneTasks.setOnClickListener(v -> {
            TaskHub.clearFinished();
            refreshTaskAndLogOverlay();
        });

        btnCopyLogs.setOnClickListener(v -> {
            String log = Library.dumpLog();
            if (log == null || log.isEmpty()) log = "（暂无日志）";
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("SpeedBoxLog", log));
                Toast.makeText(this, "已复制 " + log.length() + " 字符", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "剪贴板不可用", Toast.LENGTH_SHORT).show();
            }
        });

        btnOsuCookieStart.setOnClickListener(v -> submitSpeedChangeTask());
    }

    private void syncFilterTabsUi() {
        tabFilterAll.setActive(currentFilterTab == 0);
        tabFilterSelected.setActive(currentFilterTab == 1);
        tabFilterMc.setActive(currentFilterTab == 2);
        tabFilterOsu.setActive(currentFilterTab == 3);
    }

    private void syncSettingsUi() {
        settingsPanel.setPitchLocked(lockPitch);
        settingsPanel.setBpmUnit(bpmUnit);
        settingsPanel.setSemitones(pitchSemitones);
        settingsPanel.setOutputMode(outputMode);
        settingsPanel.setSpeedValue(speedValue);

        String unitSuffix = bpmUnit ? " BPM" : "x";
        footerBtnSettings.setBadge(speedSpecText + unitSuffix);

        if (outputMode == Library.MODE_MCZ_SEPARATE) {
            btnOsuCookieStart.setLabels("osu!", "分包导出");
        } else if (outputMode == Library.MODE_MCZ_BUNDLE) {
            btnOsuCookieStart.setLabels("osu!", "合包.mcz");
        } else {
            btnOsuCookieStart.setLabels("osu!", "开始变速");
        }

        updateWedgeAndStatsUi();
        if (carouselAdapter != null) carouselAdapter.notifyDataSetChanged();
    }

    private void updateWedgeAndStatsUi() {
        if (focusedChart == null && !selectedCharts.isEmpty()) {
            focusedChart = selectedCharts.iterator().next();
        }
        if (focusedChart == null && !allSongs.isEmpty() && !allSongs.get(0).charts.isEmpty()) {
            focusedChart = allSongs.get(0).charts.get(0);
        }

        // carousel 面板脉动统一跟随“原曲 BPM”（不受变速倍率影响），所有选中面板同步。
        LazerUi.BeatClock.setBpm(focusedChart != null && focusedChart.bpm > 0 ? focusedChart.bpm : 160.0);

        String spec = speedSpecText;
        float primaryVal = parsePrimarySpeed(spec, bpmUnit ? 200f : 1.25f);

        if (focusedChart != null) {
            if (focusedChart != lastFocusedChart) {
                lastFocusedChart = focusedChart;
                difficultyDisplaySubWedge.setTranslationX(-LazerUi.dp(this, 16f));
                difficultyDisplaySubWedge.setAlpha(0.6f);
                difficultyDisplaySubWedge.animate()
                        .translationX(0f).alpha(1f)
                        .setDuration(320)
                        .setInterpolator(LazerEasing.OUT_QUINT)
                        .start();
            }
            float sr = OsuColour.estimateStarRating(focusedChart.version, focusedChart.bpm, 0);
            int starBg = OsuColour.forStarDifficulty(sr);
            int starFg = OsuColour.forStarDifficultyText(sr);
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(LazerUi.dp(this, 10f));
            pill.setColor(starBg);
            tvWedgeStarPill.setBackground(pill);
            tvWedgeStarPill.setTextColor(starFg);
            tvWedgeStarPill.setText(String.format(Locale.ROOT, "★ %.2f", sr));

            GradientDrawable statusPillBg = new GradientDrawable();
            statusPillBg.setCornerRadius(LazerUi.dp(this, 8f));
            statusPillBg.setColor(focusedChart.osu ? OsuColour.HIGHLIGHT_1 : OsuColour.GREEN_RANKED);
            tvWedgeStatusPill.setBackground(statusPillBg);
            tvWedgeStatusPill.setText(focusedChart.osu ? "OSU!MANIA" : "MALODY KEY");

            tvWedgeVersion.setTextColor(OsuColour.foregroundTextColour(sr));
            tvWedgeVersion.setText(focusedChart.label());
            tvWedgeTitle.setText(focusedChart.song.title);
            tvWedgeArtist.setText(focusedChart.osu ? "osu!mania" : "Malody Key");
            bindCoverAsync(focusedChart.song.cover, ivGlobalBgCover);

            double baseBpm = focusedChart.bpm > 0 ? focusedChart.bpm : 180.0;
            double targetBpm = bpmUnit ? primaryVal : (baseBpm * primaryVal);
            double rate = bpmUnit ? (primaryVal / baseBpm) : primaryVal;
            btnOsuCookieStart.setBpm(targetBpm);

            tvBpmConvertPreview.setText(String.format(
                    Locale.ROOT, "♪ BPM %.0f → %.1f (%.2fx) · 已选 %d 谱",
                    baseBpm, targetBpm, rate, selectedCharts.size()));
        } else {
            tvWedgeStarPill.setText("★ --");
            tvWedgeVersion.setText("就绪 · 点击右侧轮盘展开谱面");
            tvWedgeTitle.setText("SpeedBox osu!lazer");
            tvWedgeArtist.setText("osu!mania / Malody");
            tvBpmConvertPreview.setText(String.format(
                    Locale.ROOT, "♪ 目标: %s %s · 已选 %d 谱",
                    spec, bpmUnit ? "BPM" : "x", selectedCharts.size()));
        }

        String pitchDesc = (lockPitch ? "TEMPO 锁音高" : "PITCH 变调")
                + " · " + (pitchSemitones >= 0 ? "+" : "") + pitchSemitones + " st";
        tvPitchPreviewBadge.setText(pitchDesc);
        tvPitchPreviewBadge.setTextColor(lockPitch ? OsuColour.LIME_1 : OsuColour.PURPLE_1);

        int totalCharts = 0;
        for (Library.Song s : allSongs) totalCharts += s.charts.size();
        tvLibraryStats.setText(String.format(
                Locale.ROOT, "%d 曲 / %d 谱 · 已选 %d",
                allSongs.size(), totalCharts, selectedCharts.size()));
    }

    private float parsePrimarySpeed(String spec, float fallback) {
        if (spec == null || spec.isEmpty()) return fallback;
        String first = spec.split("[,:\\s]+")[0];
        try {
            return Float.parseFloat(first);
        } catch (Exception e) {
            return fallback;
        }
    }

    private File cacheFileForPath(String rootPath) {
        String safe = Integer.toHexString(rootPath.hashCode());
        return new File(getCacheDir(), "lazer_lib_" + safe + ".json");
    }

    private void loadDiskCacheThenAutoSync() {
        final String path = tvWedgePath.getText().toString().trim();
        footerBtnDir.setBadge(shortDirLabel(path));
        ioPool.execute(() -> {
            File cf = cacheFileForPath(path);
            if (cf.isFile()) {
                try {
                    String raw = new String(Files.readAllBytes(cf.toPath()), StandardCharsets.UTF_8);
                    JSONObject root = new JSONObject(raw);
                    JSONObject fpsObj = root.optJSONObject("fps");
                    Map<String, Long> loadedFps = new HashMap<>();
                    if (fpsObj != null) {
                        JSONArray names = fpsObj.names();
                        if (names != null) {
                            for (int i = 0; i < names.length(); i++) {
                                String k = names.getString(i);
                                loadedFps.put(k, fpsObj.optLong(k));
                            }
                        }
                    }
                    List<Library.Song> cachedSongs = Library.cacheFromJson(root.optString("songs_json", ""));
                    mainHandler.post(() -> {
                        dirFps.clear();
                        dirFps.putAll(loadedFps);
                        allSongs.clear();
                        if (cachedSongs != null) allSongs.addAll(cachedSongs);
                        if (!allSongs.isEmpty() && expandedSongDirs.isEmpty()) {
                            expandedSongDirs.add(allSongs.get(0).dir);
                        }
                        rebuildVisibleCarousel();
                        updateWedgeAndStatsUi();
                    });
                } catch (Exception e) {
                    Library.log("缓存读取跳过: " + e.getMessage());
                }
            }
            mainHandler.post(() -> triggerAutoIncrementalSync(true));
        });
    }

    private void triggerAutoIncrementalSync(boolean forceFullIfEmpty) {
        if (!hasStoragePermission()) {
            updatePermissionBanner();
            return;
        }
        if (syncInFlight) return;
        final String path = tvWedgePath.getText().toString().trim();
        final File rootDir = new File(path);
        if (!rootDir.isDirectory()) {
            tvAutoSyncBadge.setText("● 目录不存在");
            tvAutoSyncBadge.setTextColor(OsuColour.RED_1);
            return;
        }

        syncInFlight = true;
        tvAutoSyncBadge.setText("↻ 正在同步曲库…");
        tvAutoSyncBadge.setTextColor(OsuColour.HIGHLIGHT_1);

        ioPool.execute(() -> {
            try {
                File[] subDirs = rootDir.listFiles(File::isDirectory);
                if (subDirs == null) subDirs = new File[0];

                Map<String, Library.Song> existingByPath = new HashMap<>();
                for (Library.Song s : allSongs) {
                    existingByPath.put(s.dir.getAbsolutePath(), s);
                }

                Map<String, Long> nextFps = new HashMap<>();
                List<Library.Song> updatedSongs = new ArrayList<>();
                int changedCount = 0;

                for (File d : subDirs) {
                    String abs = d.getAbsolutePath();
                    long fp = computeDirFingerprint(d);
                    nextFps.put(abs, fp);
                    Long oldFp = dirFps.get(abs);
                    Library.Song oldSong = existingByPath.get(abs);

                    if (!forceFullIfEmpty && oldFp != null && oldFp == fp && oldSong != null) {
                        updatedSongs.add(oldSong);
                    } else {
                        Library.Song rescanned = Library.scanSongDir(d);
                        if (rescanned != null && !rescanned.charts.isEmpty()) {
                            updatedSongs.add(rescanned);
                            changedCount++;
                        }
                    }
                }

                Collections.sort(updatedSongs, (a, b) -> a.title.compareToIgnoreCase(b.title));

                try {
                    JSONObject cacheRoot = new JSONObject();
                    JSONObject fpsJson = new JSONObject();
                    for (Map.Entry<String, Long> e : nextFps.entrySet()) {
                        fpsJson.put(e.getKey(), e.getValue());
                    }
                    cacheRoot.put("fps", fpsJson);
                    cacheRoot.put("songs_json", Library.cacheToJson(updatedSongs, path));
                    Files.write(cacheFileForPath(path).toPath(),
                            cacheRoot.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {}

                final int delta = changedCount;
                mainHandler.post(() -> {
                    dirFps.clear();
                    dirFps.putAll(nextFps);
                    allSongs.clear();
                    allSongs.addAll(updatedSongs);
                    if (!allSongs.isEmpty() && expandedSongDirs.isEmpty()) {
                        expandedSongDirs.add(allSongs.get(0).dir);
                    }
                    rebuildVisibleCarousel();
                    updateWedgeAndStatsUi();
                    tvAutoSyncBadge.setText(delta > 0 ? ("● 已同步 +" + delta) : "● AUTO-SYNC");
                    tvAutoSyncBadge.setTextColor(OsuColour.LIME_1);
                    syncInFlight = false;
                });
            } catch (Exception e) {
                Library.log("自动同步异常: " + e.getMessage());
                mainHandler.post(() -> {
                    tvAutoSyncBadge.setText("● 同步就绪");
                    tvAutoSyncBadge.setTextColor(OsuColour.ORANGE_1);
                    syncInFlight = false;
                });
            }
        });
    }

    private long computeDirFingerprint(File dir) {
        long h = dir.lastModified();
        File[] children = dir.listFiles();
        if (children != null) {
            h = h * 31 + children.length;
            for (File f : children) {
                h = h * 31 + f.lastModified() + f.length();
            }
        }
        return h;
    }

    private void rebuildVisibleCarousel() {
        visibleRows.clear();
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);

        for (Library.Song song : allSongs) {
            List<Library.Chart> matchedCharts = new ArrayList<>();
            boolean songTitleMatches = q.isEmpty()
                    || song.title.toLowerCase(Locale.ROOT).contains(q)
                    || song.dir.getName().toLowerCase(Locale.ROOT).contains(q);

            for (Library.Chart c : song.charts) {
                if (currentFilterTab == 1 && !selectedCharts.contains(c)) continue;
                if (currentFilterTab == 2 && c.osu) continue;
                if (currentFilterTab == 3 && !c.osu) continue;

                boolean chartMatches = songTitleMatches
                        || c.label().toLowerCase(Locale.ROOT).contains(q)
                        || (c.bpm > 0 && String.valueOf(Math.round(c.bpm)).contains(q));
                if (chartMatches) {
                    matchedCharts.add(c);
                }
            }

            if (matchedCharts.isEmpty()) continue;

            float[] stars = new float[matchedCharts.size()];
            for (int i = 0; i < matchedCharts.size(); i++) {
                Library.Chart c = matchedCharts.get(i);
                stars[i] = OsuColour.estimateStarRating(c.version, c.bpm, i);
            }
            float[] sortedStars = stars.clone();
            java.util.Arrays.sort(sortedStars);
            visibleRows.add(new CarouselRow(song, sortedStars));

            boolean expanded = expandedSongDirs.contains(song.dir)
                    || !q.isEmpty()
                    || currentFilterTab == 1;
            if (expanded) {
                for (int i = 0; i < matchedCharts.size(); i++) {
                    visibleRows.add(new CarouselRow(song, matchedCharts.get(i), stars[i], i));
                }
            }
        }

        tvEmptyCarousel.setVisibility(visibleRows.isEmpty() ? View.VISIBLE : View.GONE);
        captureCarouselRowOffsets();
        carouselAdapter.notifyDataSetChanged();
    }

    // =========================================================================
    // Right-Anchored Beatmap Carousel Adapter with Full osu!lazer Motion
    // =========================================================================
    private final class CarouselAdapter extends BaseAdapter {
        // osu!framework touch_right_click_distance = 100（按长按圈 180→96dp 的同比例 ≈ 53dp 换算）
        private final float holdDistanceTolerance = LazerUi.dp(MainActivity.this, 53f);

        @Override public int getCount() { return visibleRows.size(); }
        @Override public Object getItem(int position) { return visibleRows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) {
            return visibleRows.get(position).isSongHeader ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final CarouselRow row = visibleRows.get(position);
            if (row.isSongHeader) {
                return bindSongSetView(row, convertView, parent);
            } else {
                return bindChartDifficultyView(row, convertView, parent);
            }
        }

        private View bindSongSetView(CarouselRow row, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_song, parent, false);
            }
            final LinearLayout cardRoot = convertView.findViewById(R.id.songCardRoot);
            final LinearLayout textContainer = convertView.findViewById(R.id.songTextContainer);
            final TextView tvTitle = convertView.findViewById(R.id.tvSongTitle);
            final TextView tvSub = convertView.findViewById(R.id.tvSongSub);
            final StarSpectrumDotsView spreadDots = convertView.findViewById(R.id.spreadDots);

            final Library.Song song = row.song;
            final boolean isExpanded = expandedSongDirs.contains(song.dir);

            int selectedInSong = 0;
            double firstBpm = 0;
            boolean hasOsu = false;
            for (Library.Chart c : song.charts) {
                if (selectedCharts.contains(c)) selectedInSong++;
                if (firstBpm <= 0 && c.bpm > 0) firstBpm = c.bpm;
                if (c.osu) hasOsu = true;
            }
            final boolean hasSel = selectedInSong > 0;

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cardRoot.getLayoutParams();
            // PanelXOffset = 4dp for songs, right margin 0dp so card seamlessly touches right screen edge
            lp.leftMargin = (int) LazerUi.dp(MainActivity.this, 4f);
            lp.rightMargin = 0;
            cardRoot.setLayoutParams(lp);

            float targetOffset = (isExpanded || hasSel) ? 0f : LazerUi.dp(MainActivity.this, 18f);
            cardRoot.setTag(R.id.songCardRoot, targetOffset);
            resetSelectionTweenIfRecycled(cardRoot, song.dir, targetOffset);

            textContainer.setPadding(
                    (int) LazerUi.dp(MainActivity.this, isExpanded ? 24f : 12f), 0, 0, 0);

            Long flashTs = flashTimestamps.get(song.dir);
            final long flashMs = flashTs != null ? flashTs : 0L;

            Bitmap cachedBmp = (song.cover != null && song.cover.isFile())
                    ? coverCache.get(song.cover.getAbsolutePath())
                    : null;
            cardRoot.setBackground(new PanelSetDrawable(MainActivity.this, isExpanded, hasSel, cachedBmp, flashMs));

            if (cachedBmp == null && song.cover != null && song.cover.isFile()) {
                final String key = song.cover.getAbsolutePath();
                cardRoot.setTag(key);
                thumbPool.execute(() -> {
                    Bitmap decoded = decodeSampledBitmap(song.cover, 320, 140);
                    if (decoded != null) {
                        coverCache.put(key, decoded);
                        mainHandler.post(() -> {
                            if (key.equals(cardRoot.getTag())) {
                                cardRoot.setBackground(new PanelSetDrawable(
                                        MainActivity.this, isExpanded, hasSel, decoded, flashMs));
                            }
                        });
                    }
                });
            }

            tvTitle.setText(song.title);
            String bpmText = firstBpm > 0 ? (" · BPM " + Math.round(firstBpm)) : "";
            String selText = hasSel ? (" · 已选 " + selectedInSong + "/" + song.charts.size()) : "";
            tvSub.setText(song.charts.size() + " 个难度" + bpmText + selText);

            String statusLabel = hasOsu ? "OSU!" : "MALODY";
            int statusCol = hasOsu ? OsuColour.HIGHLIGHT_1 : OsuColour.GREEN_RANKED;
            spreadDots.setSpread(statusLabel, statusCol, row.songStarSpread);


            final float[] songTouchCoord = new float[2];
            final float[] songDownPos = new float[2];
            final boolean[] songHoldTriggered = new boolean[1];

            View.OnTouchListener songTouchListener = (v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        songHoldTriggered[0] = false;
                        songTouchCoord[0] = event.getRawX();
                        songTouchCoord[1] = event.getRawY();
                        songDownPos[0] = event.getRawX();
                        songDownPos[1] = event.getRawY();
                        if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                            showSongContextMenu(song, songTouchCoord[0], songTouchCoord[1]);
                            return true;
                        }
                        if (contextMenuContainer != null) {
                            contextMenuContainer.startTouchHold(songTouchCoord[0], songTouchCoord[1], () -> {
                                songHoldTriggered[0] = true;
                                showSongContextMenu(song, songTouchCoord[0], songTouchCoord[1]);
                            });
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!songHoldTriggered[0]) {
                            float dx = event.getRawX() - songDownPos[0];
                            float dy = event.getRawY() - songDownPos[1];
                            if (dx * dx + dy * dy > holdDistanceTolerance * holdDistanceTolerance) {
                                if (contextMenuContainer != null) {
                                    contextMenuContainer.cancelTouchHold();
                                }
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (contextMenuContainer != null) {
                            contextMenuContainer.cancelTouchHold();
                        }
                        if (songHoldTriggered[0]) {
                            return true;
                        }
                        break;
                }
                return false;
            };

            cardRoot.setOnTouchListener(songTouchListener);
            cardRoot.setOnClickListener(v -> {
                if (songHoldTriggered[0]) {
                    songHoldTriggered[0] = false;
                    return;
                }
                flashTimestamps.put(song.dir, SystemClock.uptimeMillis());
                if (expandedSongDirs.contains(song.dir)) {
                    collapseSongAnimated(song);
                } else {
                    expandedSongDirs.add(song.dir);
                    newlyExpandedDir = song.dir;
                    newlyExpandedAtMs = SystemClock.uptimeMillis();
                    if (!song.charts.isEmpty()) focusedChart = song.charts.get(0);
                    rebuildVisibleCarousel();
                    updateWedgeAndStatsUi();
                }
            });
            cardRoot.setOnLongClickListener(v -> true);

            return convertView;
        }

        private View bindChartDifficultyView(CarouselRow row, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_chart, parent, false);
            }
            final LinearLayout cardRoot = convertView.findViewById(R.id.chartCardRoot);
            TextView tvVersion = convertView.findViewById(R.id.tvChartVersion);
            TextView tvMeta = convertView.findViewById(R.id.tvChartMeta);
            final StarRatingPillAndCounterView starPillAndCounter = convertView.findViewById(R.id.starPillAndCounter);
            TextView tvCheck = convertView.findViewById(R.id.tvChartCheck);

            final Library.Chart chart = row.chart;
            final boolean isSelected = selectedCharts.contains(chart);

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cardRoot.getLayoutParams();
            // PanelXOffset = 20dp for charts (nested 16dp inside song set), right margin 0dp
            lp.leftMargin = (int) LazerUi.dp(MainActivity.this, 20f);
            lp.rightMargin = 0;
            cardRoot.setLayoutParams(lp);

            float targetOffset = isSelected ? 0f : LazerUi.dp(MainActivity.this, 20f);
            cardRoot.setTag(R.id.chartCardRoot, targetOffset);
            resetSelectionTweenIfRecycled(cardRoot, chart, targetOffset);

            Long flashTs = flashTimestamps.get(chart);
            long flashMs = flashTs != null ? flashTs : 0L;

            float specVal = parsePrimarySpeed(speedSpecText, bpmUnit ? 200f : 1.25f);
            double baseBpm = chart.bpm > 0 ? chart.bpm : 180.0;
            double targetBpm = bpmUnit ? specVal : (baseBpm * specVal);

            cardRoot.setBackground(new PanelBeatmapDrawable(
                    MainActivity.this, row.starRating, isSelected, chart.label().hashCode(), flashMs));

            tvVersion.setText(chart.label());

            String fmtTag = chart.osu ? "osu!mania" : (chart.entry != null ? ".mcz" : ".mc");
            tvMeta.setText(String.format(Locale.ROOT, "BPM %.0f → %.0f · %s", baseBpm, targetBpm, fmtTag));

            // Check if this difficulty belongs to a freshly expanded song set (staggered cascade + star wave!)
            boolean justExpanded = (newlyExpandedDir != null
                    && newlyExpandedDir.equals(row.song.dir)
                    && (SystemClock.uptimeMillis() - newlyExpandedAtMs) < 350L);

            starPillAndCounter.setStars(row.starRating, true, justExpanded || (SystemClock.uptimeMillis() - flashMs < 150L));

            if (justExpanded) {
                float startSlidePx = LazerUi.dp(MainActivity.this, 36f);
                cardRoot.setTag(R.id.rootContainer, startSlidePx);
                cardRoot.setAlpha(0f);
                android.animation.ValueAnimator slideAnim = android.animation.ValueAnimator.ofFloat(startSlidePx, 0f);
                slideAnim.setStartDelay(Math.min(row.diffIndexInSong, 8) * 28L);
                slideAnim.setDuration(340L);
                slideAnim.setInterpolator(LazerEasing.OUT_QUINT);
                slideAnim.addUpdateListener(a -> {
                    float val = (float) a.getAnimatedValue();
                    cardRoot.setTag(R.id.rootContainer, val);
                    cardRoot.setAlpha(1f - (val / startSlidePx));
                    applyCarouselArcToVisibleChildren();
                });
                slideAnim.start();
            } else {
                cardRoot.setTag(R.id.rootContainer, 0f);
                cardRoot.setAlpha(1f);
            }

            tvCheck.setText(isSelected ? "●" : "○");
            tvCheck.setTextColor(isSelected ? OsuColour.LIME_1 : OsuColour.FOREGROUND_1);

            final float[] chartTouchCoord = new float[2];
            final float[] chartDownPos = new float[2];
            final boolean[] chartHoldTriggered = new boolean[1];

            View.OnTouchListener chartTouchListener = (v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        chartHoldTriggered[0] = false;
                        chartTouchCoord[0] = event.getRawX();
                        chartTouchCoord[1] = event.getRawY();
                        chartDownPos[0] = event.getRawX();
                        chartDownPos[1] = event.getRawY();
                        if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                            showChartContextMenu(row.song, chart, chartTouchCoord[0], chartTouchCoord[1]);
                            return true;
                        }
                        if (contextMenuContainer != null) {
                            contextMenuContainer.startTouchHold(chartTouchCoord[0], chartTouchCoord[1], () -> {
                                chartHoldTriggered[0] = true;
                                showChartContextMenu(row.song, chart, chartTouchCoord[0], chartTouchCoord[1]);
                            });
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!chartHoldTriggered[0]) {
                            float dx = event.getRawX() - chartDownPos[0];
                            float dy = event.getRawY() - chartDownPos[1];
                            if (dx * dx + dy * dy > holdDistanceTolerance * holdDistanceTolerance) {
                                if (contextMenuContainer != null) {
                                    contextMenuContainer.cancelTouchHold();
                                }
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (contextMenuContainer != null) {
                            contextMenuContainer.cancelTouchHold();
                        }
                        if (chartHoldTriggered[0]) {
                            return true;
                        }
                        break;
                }
                return false;
            };

            cardRoot.setOnTouchListener(chartTouchListener);

            cardRoot.setOnClickListener(v -> {
                if (chartHoldTriggered[0]) {
                    chartHoldTriggered[0] = false;
                    return;
                }
                focusedChart = chart;
                flashTimestamps.put(chart, SystemClock.uptimeMillis());
                if (selectedCharts.contains(chart)) {
                    selectedCharts.remove(chart);
                } else {
                    selectedCharts.add(chart);
                }
                updateWedgeAndStatsUi();
                notifyDataSetChanged();
            });
            cardRoot.setOnLongClickListener(v -> true);

            return convertView;
        }
    }

    private void showSongContextMenu(Library.Song song) {
        if (song == null) return;
        float defaultX = getResources().getDisplayMetrics().widthPixels * 0.65f;
        float defaultY = getResources().getDisplayMetrics().heightPixels * 0.45f;
        showSongContextMenu(song, defaultX, defaultY);
    }

    // 1:1 osu!lazer PanelBeatmapSet.cs ContextMenuItems (tight & clean phrasing)
    private void showSongContextMenu(Library.Song song, float rawX, float rawY) {
        if (song == null) return;
        List<OsuMenuItem> items = new ArrayList<>();

        // 1. 展开/收起 (1:1 osu!lazer ButtonsExpand / ButtonsCollapse)
        final boolean isExpanded = expandedSongDirs.contains(song.dir);
        if (isExpanded) {
            items.add(new OsuMenuItem("收起", MenuItemType.Standard, () -> {
                flashTimestamps.put(song.dir, SystemClock.uptimeMillis());
                collapseSongAnimated(song);
            }));
        } else {
            items.add(new OsuMenuItem("展开", MenuItemType.Highlighted, () -> {
                flashTimestamps.put(song.dir, SystemClock.uptimeMillis());
                expandedSongDirs.add(song.dir);
                newlyExpandedDir = song.dir;
                newlyExpandedAtMs = SystemClock.uptimeMillis();
                if (!song.charts.isEmpty()) focusedChart = song.charts.get(0);
                rebuildVisibleCarousel();
                updateWedgeAndStatsUi();
            }));
        }

        items.add(new OsuMenuItemSpacer());

        // 2. 重新扫描
        items.add(new OsuMenuItem("重新扫描", MenuItemType.Standard, () -> {
            Toast.makeText(MainActivity.this, "正在重新扫描...", Toast.LENGTH_SHORT).show();
            thumbPool.execute(() -> {
                int count = Library.rescanSong(song);
                mainHandler.post(() -> {
                    flashTimestamps.put(song.dir, SystemClock.uptimeMillis());
                    rebuildVisibleCarousel();
                    updateWedgeAndStatsUi();
                    if (carouselAdapter != null) carouselAdapter.notifyDataSetChanged();
                    Toast.makeText(MainActivity.this, "已更新 " + count + " 个难度", Toast.LENGTH_SHORT).show();
                });
            });
        }));

        // 3. 导出 (.mcz)
        items.add(new OsuMenuItem("导出 (.mcz)", MenuItemType.Standard, () -> {
            Toast.makeText(MainActivity.this, "正在导出 .mcz...", Toast.LENGTH_SHORT).show();
            thumbPool.execute(() -> {
                try {
                    File out = Library.exportSongToMcz(song);
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "已导出: " + out.getName(), Toast.LENGTH_LONG).show();
                        shareFile(out, "application/zip", "分享 .mcz");
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }));

        // 4. 导出 (.osz)
        items.add(new OsuMenuItem("导出 (.osz)", MenuItemType.Standard, () -> {
            Toast.makeText(MainActivity.this, "正在导出 .osz...", Toast.LENGTH_SHORT).show();
            thumbPool.execute(() -> {
                try {
                    File out = Library.exportSongToOsz(song);
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "已导出: " + out.getName(), Toast.LENGTH_LONG).show();
                        shareFile(out, "application/zip", "分享 .osz");
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }));

        // 5. 打开目录
        items.add(new OsuMenuItem("打开目录", MenuItemType.Standard, () -> openDirectory(song.dir)));

        // 6. 分享谱包（长按谱包：默认全部；若其中有已选谱面则默认只勾选已选）
        items.add(new OsuMenuItem("分享谱包…", MenuItemType.Standard,
                () -> showShareChartsDialog(song, null)));

        items.add(new OsuMenuItemSpacer());

        // 6. 全选 / 取消全选
        int selCount = 0;
        for (Library.Chart c : song.charts) {
            if (selectedCharts.contains(c)) selCount++;
        }
        final boolean allSelected = selCount == song.charts.size() && !song.charts.isEmpty();
        items.add(new OsuMenuItem(allSelected ? "取消全选" : "全选本曲", MenuItemType.Standard, () -> {
            if (allSelected) {
                selectedCharts.removeAll(song.charts);
            } else {
                selectedCharts.addAll(song.charts);
                if (!song.charts.isEmpty()) focusedChart = song.charts.get(0);
            }
            flashTimestamps.put(song.dir, SystemClock.uptimeMillis());
            updateWedgeAndStatsUi();
            if (carouselAdapter != null) carouselAdapter.notifyDataSetChanged();
        }));

        if (contextMenuContainer != null) {
            contextMenuContainer.showMenu(rawX, rawY, items);
        }
    }

    // 1:1 osu!lazer PanelBeatmap.cs ContextMenuItems
    private void showChartContextMenu(Library.Song song, Library.Chart chart, float rawX, float rawY) {
        if (song == null || chart == null) return;
        List<OsuMenuItem> items = new ArrayList<>();

        final boolean isSelected = selectedCharts.contains(chart);
        items.add(new OsuMenuItem(isSelected ? "取消选择" : "选择难度", MenuItemType.Highlighted, () -> {
            focusedChart = chart;
            flashTimestamps.put(chart, SystemClock.uptimeMillis());
            if (isSelected) {
                selectedCharts.remove(chart);
            } else {
                selectedCharts.add(chart);
            }
            updateWedgeAndStatsUi();
            if (carouselAdapter != null) carouselAdapter.notifyDataSetChanged();
        }));

        items.add(new OsuMenuItemSpacer());

        items.add(new OsuMenuItem("打开目录", MenuItemType.Standard, () -> openDirectory(song.dir)));

        // 分享此谱面（长按单个谱面：默认只勾它；若它已在选择集中则默认勾选整包已选谱面）
        items.add(new OsuMenuItem("分享此谱面…", MenuItemType.Standard,
                () -> showShareChartsDialog(song, chart)));

        items.add(new OsuMenuItem("导出 (.osz)", MenuItemType.Standard, () -> {
            Toast.makeText(MainActivity.this, "正在导出 .osz...", Toast.LENGTH_SHORT).show();
            thumbPool.execute(() -> {
                try {
                    File out = Library.exportSongToOsz(song);
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "已导出: " + out.getName(), Toast.LENGTH_LONG).show();
                        shareFile(out, "application/zip", "分享 .osz");
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }));

        items.add(new OsuMenuItem("导出 (.mcz)", MenuItemType.Standard, () -> {
            Toast.makeText(MainActivity.this, "正在导出 .mcz...", Toast.LENGTH_SHORT).show();
            thumbPool.execute(() -> {
                try {
                    File out = Library.exportSongToMcz(song);
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "已导出: " + out.getName(), Toast.LENGTH_LONG).show();
                        shareFile(out, "application/zip", "分享 .mcz");
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> Toast.makeText(MainActivity.this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }));

        items.add(new OsuMenuItemSpacer());

        items.add(new OsuMenuItem("全选本曲", MenuItemType.Standard, () -> {
            selectedCharts.addAll(song.charts);
            focusedChart = chart;
            flashTimestamps.put(song.dir, SystemClock.uptimeMillis());
            updateWedgeAndStatsUi();
            if (carouselAdapter != null) carouselAdapter.notifyDataSetChanged();
        }));

        if (contextMenuContainer != null) {
            contextMenuContainer.showMenu(rawX, rawY, items);
        }
    }

    private void shareFile(File file, String mimeType, String title) {
        if (file == null || !file.exists()) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, "com.speedbox.files", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, title));
        } catch (Exception e) {
            Toast.makeText(this, "无法调起系统分享: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void openDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            Toast.makeText(this, "目标文件夹不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:" +
                    Uri.encode(dir.getAbsolutePath().replaceFirst("^/storage/emulated/0/", "")));
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "vnd.android.document/directory");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception fallback) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("folder", dir.getAbsolutePath()));
                Toast.makeText(this, "已复制文件夹路径: " + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void bindCoverAsync(File coverFile, ImageView target) {
        if (target == null) return;
        if (coverFile == null || !coverFile.isFile()) {
            target.setImageBitmap(null);
            return;
        }
        final String key = coverFile.getAbsolutePath();
        target.setTag(key);
        Bitmap cached = coverCache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        thumbPool.execute(() -> {
            Bitmap bmp = decodeSampledBitmap(coverFile, 320, 180);
            if (bmp != null) {
                coverCache.put(key, bmp);
                mainHandler.post(() -> {
                    if (key.equals(target.getTag())) {
                        target.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    private static Bitmap decodeSampledBitmap(File file, int reqW, int reqH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            int inSample = 1;
            while ((opts.outWidth / inSample) > reqW * 2 && (opts.outHeight / inSample) > reqH * 2) {
                inSample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = inSample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 曲库目录切换 —— 自绘 osu 面板，替代原生 AlertDialog。
     * 结构参考 osu.Game DirectorySelectScreen：暗色圆角卡 + 顶部强调条 + 列表行 + 底部按钮。
     */
    private void showDirectorySwitchDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = (int) LazerUi.dp(this, 16f);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        // osu DirectorySelectScreen: 平铺 Background4，圆角 10，无描边
        col.setBackground(flatPanel(OsuColour.BACKGROUND_4, 10f));

        TextView title = new TextView(this);
        title.setText("切换曲库目录");
        title.setTextColor(OsuColour.CONTENT_1);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(this, 15.5f));
        title.setPadding((int) LazerUi.dp(this, 4f), 0, 0, (int) LazerUi.dp(this, 6f));
        col.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("切换后会自动增量同步");
        subtitle.setTextColor(0xFF948FA3); // SET_FG1
        subtitle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(this, 11f));
        subtitle.setPadding((int) LazerUi.dp(this, 4f), 0, 0, (int) LazerUi.dp(this, 8f));
        col.addView(subtitle);

        String[] labels = {
                "Malody 旧版默认目录",
                "Malody V / Android/data",
                "osu!droid 歌曲目录",
                "系统 Download 下载目录"
        };
        for (int i = 0; i < PRESET_DIRS.length && i < labels.length; i++) {
            final String path = PRESET_DIRS[i];
            TextView row = new TextView(this);
            row.setText(labels[i]);
            row.setTextColor(OsuColour.CONTENT_1);
            row.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(this, 14f));
            int hp = (int) LazerUi.dp(this, 12f);
            row.setPadding(hp, hp, hp, hp);
            row.setBackground(directoryRowBackground());
            row.setOnClickListener(v -> {
                dialog.dismiss();
                applyNewBeatmapDirectory(path);
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = (int) LazerUi.dp(this, 2f);
            col.addView(row, rlp);
        }

        // OsuTextBox: Height 40, LeftRightPadding 10, Background5（未聚焦，无描边）
        //             / Background4 + Highlight1 描边（聚焦）
        EditText input = new EditText(this);
        input.setText(tvWedgePath.getText().toString());
        input.setSingleLine(true);
        input.setIncludeFontPadding(false);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setMinHeight(0);
        input.setMinimumHeight(0);
        input.setMinWidth(0);
        input.setTextColor(OsuColour.CONTENT_1);
        input.setHintTextColor(0xFF948FA3);
        input.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(this, 12.5f));
        int ip = (int) LazerUi.dp(this, 10f);
        input.setPadding(ip, 0, ip, 0);
        input.setBackground(directoryInputBackground());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) LazerUi.dp(this, 40f));
        ilp.topMargin = (int) LazerUi.dp(this, 10f);
        col.addView(input, ilp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = (int) LazerUi.dp(this, 14f);
        col.addView(actions, alp);

        // DirectorySelectScreen 的按钮角色是 RoundedButton（不是 ShearedButton）
        RoundedButtonView cancel = new RoundedButtonView(this);
        cancel.configure("取消", OsuColour.BACKGROUND_3, 13f);
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                (int) LazerUi.dp(this, 88f), (int) LazerUi.dp(this, 40f));
        actions.addView(cancel, clp);

        RoundedButtonView ok = new RoundedButtonView(this);
        ok.configure("确定", OsuColour.SET_HL1, 13f);
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            applyNewBeatmapDirectory(input.getText().toString().trim());
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                (int) LazerUi.dp(this, 96f), (int) LazerUi.dp(this, 40f));
        olp.setMarginStart((int) LazerUi.dp(this, 8f));
        actions.addView(ok, olp);

        dialog.setContentView(col);
        dialog.show();

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setDimAmount(0.65f);
            int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            win.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** osu 风格的平铺圆角面板（无描边）。 */
    private GradientDrawable flatPanel(int color, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(LazerUi.dp(this, radiusDp));
        return gd;
    }

    /** 目录行：按下时 #172023 圆角高亮（DrawableOsuMenuItem hover 色）。 */
    private StateListDrawable directoryRowBackground() {
        StateListDrawable sld = new StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(0xFF172023);
        pressed.setCornerRadius(LazerUi.dp(this, 5f));
        sld.addState(new int[]{android.R.attr.state_pressed}, pressed);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        normal.setCornerRadius(LazerUi.dp(this, 5f));
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /** OsuTextBox 背景：未聚焦 Background5 无描边；聚焦 Background4 + Highlight1 描边。 */
    private StateListDrawable directoryInputBackground() {
        StateListDrawable sld = new StateListDrawable();

        GradientDrawable focused = new GradientDrawable();
        focused.setColor(OsuColour.SET_BG4);
        focused.setCornerRadius(LazerUi.dp(this, 5f));
        focused.setStroke((int) LazerUi.dp(this, 2f), OsuColour.SET_HL1);
        sld.addState(new int[]{android.R.attr.state_focused}, focused);

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(OsuColour.SET_BG5);
        normal.setCornerRadius(LazerUi.dp(this, 5f));
        sld.addState(new int[]{}, normal);
        return sld;
    }

    private void applyNewBeatmapDirectory(String newPath) {
        if (newPath == null || newPath.isEmpty()) return;
        tvWedgePath.setText(newPath);
        footerBtnDir.setBadge(shortDirLabel(newPath));
        savePreferences();
        selectedCharts.clear();
        expandedSongDirs.clear();
        dirFps.clear();
        allSongs.clear();
        rebuildVisibleCarousel();
        loadDiskCacheThenAutoSync();
    }

    private String shortDirLabel(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("osu")) return "OSU!";
        if (lower.contains("download")) return "DOWNLOAD";
        if (lower.contains("android/data")) return "MALODY-V";
        return "MALODY";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_ARCHIVE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            ioPool.execute(() -> {
                try {
                    File destDir = new File(tvWedgePath.getText().toString().trim(),
                            "import_" + System.currentTimeMillis());
                    destDir.mkdirs();
                    File archiveFile = new File(destDir, "imported.mcz");
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(archiveFile)) {
                        if (in != null) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    }
                    for (String entry : Mcz.listEntries(archiveFile)) {
                        File outF = new File(destDir, new File(entry).getName());
                        try (FileOutputStream fos = new FileOutputStream(outF)) {
                            fos.write(Mcz.readEntry(archiveFile, entry));
                        }
                    }
                    archiveFile.delete();
                    mainHandler.post(() -> {
                        Toast.makeText(this, "已导入谱面包并自动同步曲库", Toast.LENGTH_SHORT).show();
                        triggerAutoIncrementalSync(true);
                    });
                } catch (Exception e) {
                    Library.log("导入失败: " + e.getMessage());
                    mainHandler.post(() ->
                            Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    private void submitSpeedChangeTask() {
        List<Library.Chart> targets = new ArrayList<>(selectedCharts);
        if (targets.isEmpty() && focusedChart != null) {
            targets.add(focusedChart);
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "请先在右侧轮盘勾选至少一个谱面难度", Toast.LENGTH_SHORT).show();
            return;
        }
        String spec = speedSpecText;
        if (spec.isEmpty()) spec = bpmUnit ? "200" : "1.25";

        String taskTitle = targets.get(0).song.title
                + (targets.size() > 1 ? (" 等 " + targets.size() + " 张谱面") : (" [" + targets.get(0).label() + "]"))
                + " · " + spec + (bpmUnit ? " BPM" : "x");

        TaskHub.submit(taskTitle, targets, spec, bpmUnit, outputMode, lockPitch, pitchSemitones);

        Intent svc = new Intent(this, SpeedService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        Toast.makeText(this, "已加入 osu!lazer 变速队列: " + taskTitle, Toast.LENGTH_SHORT).show();
        if (settingsPanel.isOpen()) {
            settingsPanel.close();
            footerBtnSettings.setActive(false);
        }
        toggleAnimatedDrawer(taskOverlayContainer, taskOverlayCard, footerBtnQueue, true);
        refreshTaskAndLogOverlay();
    }

    private void startUiTickLoop() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                int active = TaskHub.countActive();
                footerBtnQueue.setBadge(active > 0 ? ("RUN " + active) : ("IDLE (" + TaskHub.TASKS.size() + ")"));

                boolean needAutoSync = false;
                synchronized (TaskHub.TASKS) {
                    for (TaskHub.Task t : TaskHub.TASKS) {
                        if (t.refreshLibrary) {
                            t.refreshLibrary = false;
                            needAutoSync = true;
                        }
                    }
                }
                if (needAutoSync) {
                    triggerAutoIncrementalSync(false);
                }
                if (taskOverlayContainer.getVisibility() == View.VISIBLE) {
                    refreshTaskAndLogOverlay();
                }
                mainHandler.postDelayed(this, 600L);
            }
        }, 600L);
    }

    private void refreshTaskAndLogOverlay() {
        taskAdapter.notifyDataSetChanged();
        String log = Library.dumpLog();
        if (log.isEmpty()) log = "（暂无日志）";
        // 只在内容变化时重建，避免每 600ms 重置滚动位置
        if (!log.equals(lastLogText)) {
            lastLogText = log;
            renderLogLines(log);
        }
    }

    /**
     * osu ChatLine 风格：时间戳（Background1 灰、等宽小字）+ 正文（白色 12sp），
     * 交替行底色 #3B3234 @ 20%，自动滚到底部。
     */
    private void renderLogLines(String log) {
        if (llLogLines == null) return;
        llLogLines.removeAllViews();

        String[] lines = log.split("\n");
        int start = Math.max(0, lines.length - 150);
        int hp = (int) LazerUi.dp(this, 4f);
        int vp = (int) LazerUi.dp(this, 3f);

        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;

            String ts = "";
            String msg = line;
            int sp = line.indexOf(' ');
            if (sp > 0 && sp <= 9) {
                ts = line.substring(0, sp);
                msg = line.substring(sp + 1);
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(hp, vp, hp, vp);
            if (((i - start) & 1) == 1) {
                row.setBackground(flatPanel(0x333B3234, 4f));
            }

            TextView tvTime = new TextView(this);
            tvTime.setText(ts);
            tvTime.setTextColor(0xFF5C6B70); // Background1
            tvTime.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            tvTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(this, 10f));
            row.addView(tvTime, new LinearLayout.LayoutParams(
                    (int) LazerUi.dp(this, 52f), ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tvMsg = new TextView(this);
            tvMsg.setText(msg);
            tvMsg.setTextColor(OsuColour.CONTENT_1);
            tvMsg.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
            tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(this, 12f));
            row.addView(tvMsg, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            llLogLines.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (svLog != null) {
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
        }
    }

    private final class TaskAdapter extends BaseAdapter {
        @Override public int getCount() { return TaskHub.TASKS.size(); }
        @Override public Object getItem(int position) { return TaskHub.TASKS.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_task, parent, false);
            }
            final TaskHub.Task t = TaskHub.TASKS.get(position);
            LinearLayout root = convertView.findViewById(R.id.taskItemRoot);
            TextView tvTitle = convertView.findViewById(R.id.tvTaskTitle);
            TextView tvBadge = convertView.findViewById(R.id.tvTaskStateBadge);
            TextView tvDetail = convertView.findViewById(R.id.tvTaskDetail);
            OsuProgressBarView pb = convertView.findViewById(R.id.pbTask);
            ShearedButtonView btnShare = convertView.findViewById(R.id.btnTaskShare);
            ShearedButtonView btnCancel = convertView.findViewById(R.id.btnTaskCancel);

            int borderCol = "DONE".equals(t.state) ? OsuColour.LIME_1
                    : ("FAIL".equals(t.state) ? OsuColour.RED_1 : OsuColour.HIGHLIGHT_1);
            // osu Notification: 平铺 Background3 圆角卡，无描边
            root.setBackground(flatPanel(OsuColour.BACKGROUND_3, 6f));

            tvTitle.setText(t.name);
            tvBadge.setText(t.state + " · " + t.progress + "%");
            tvBadge.setTextColor(borderCol);
            tvDetail.setText(t.status);
            pb.setProgress(t.progress, borderCol);

            boolean isRunningOrQueued = "RUN".equals(t.state) || "QUEUED".equals(t.state);
            btnCancel.setVisibility(isRunningOrQueued ? View.VISIBLE : View.GONE);
            btnCancel.configure("取消", OsuColour.RED_1, OsuColour.RED_1, OsuColour.CONTENT_1, OsuColour.CONTENT_1, 10.5f);
            btnCancel.setOnClickListener(v -> {
                TaskHub.cancelTask(t);
                notifyDataSetChanged();
            });

            boolean hasOutputs = "DONE".equals(t.state)
                    && (!t.shareFiles.isEmpty() || !t.outputs.isEmpty());
            btnShare.setVisibility(hasOutputs ? View.VISIBLE : View.GONE);
            // Malody 兼容 osu：优先 .osz；谱面里出现 .mc 时才用 .mcz
            btnShare.configure("分享 ." + Library.shareExtension(t),
                    OsuColour.HIGHLIGHT_1, OsuColour.HIGHLIGHT_1,
                    OsuColour.BACKGROUND_6, OsuColour.BACKGROUND_6, 10.5f);
            btnShare.setOnClickListener(v -> shareTask(t));

            return convertView;
        }
    }

    /** 任务行“分享”：先选整个文件夹（zip）还是单个谱面。 */
    private void shareTask(TaskHub.Task t) {
        List<File> candidates = new ArrayList<>();
        synchronized (t.shareFiles) {
            candidates.addAll(t.shareFiles);
        }
        if (candidates.isEmpty()) {
            synchronized (t.outputs) {
                candidates.addAll(t.outputs);
            }
        }

        final List<File> files = new ArrayList<>();
        for (File f : candidates) {
            if (f != null && f.isFile()) files.add(f);
        }
        if (files.isEmpty()) {
            Library.log("分享失败: 任务没有可分享的产物");
            Toast.makeText(this, "没有可分享的产物", Toast.LENGTH_SHORT).show();
            return;
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) LazerUi.dp(this, 14f);
        col.setPadding(pad, pad, pad, pad);
        col.setBackground(flatPanel(OsuColour.BACKGROUND_4, 10f));

        TextView title = new TextView(this);
        title.setText("分享产物");
        title.setTextColor(OsuColour.CONTENT_1);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(this, 15f));
        title.setPadding((int) LazerUi.dp(this, 4f), 0, 0, (int) LazerUi.dp(this, 8f));
        col.addView(title);

        TextView all = makeShareRow("分享整个文件夹（" + files.size() + " 个包 · .zip）");
        all.setOnClickListener(v -> {
            dialog.dismiss();
            Toast.makeText(this, "正在打包…", Toast.LENGTH_SHORT).show();
            thumbPool.execute(() -> {
                try {
                    File zip = Library.zipShareFiles(files, t.name);
                    mainHandler.post(() -> shareFiles(java.util.Collections.singletonList(zip)));
                } catch (Exception e) {
                    Library.log("打包分享失败: " + e);
                    mainHandler.post(() -> Toast.makeText(this,
                            "打包失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        });
        col.addView(all);

        int limit = Math.min(files.size(), 12);
        for (int i = 0; i < limit; i++) {
            final File f = files.get(i);
            TextView row = makeShareRow("只分享：" + f.getName());
            row.setOnClickListener(v -> {
                dialog.dismiss();
                shareFiles(java.util.Collections.singletonList(f));
            });
            col.addView(row);
        }
        if (files.size() > limit) {
            TextView more = makeShareRow("… 其余 " + (files.size() - limit) + " 个包请用“整个文件夹”");
            more.setTextColor(OsuColour.SET_FG1);
            col.addView(more);
        }

        TextView cancel = makeShareRow("取消");
        cancel.setTextColor(OsuColour.SET_FG1);
        cancel.setOnClickListener(v -> dialog.dismiss());
        col.addView(cancel);

        dialog.setContentView(col);
        dialog.show();

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setDimAmount(0.65f);
            win.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private TextView makeShareRow(String text) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextColor(OsuColour.CONTENT_1);
        row.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        row.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(this, 13.5f));
        int hp = (int) LazerUi.dp(this, 12f);
        int vp = (int) LazerUi.dp(this, 11f);
        row.setPadding(hp, vp, hp, vp);
        row.setBackground(directoryRowBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) LazerUi.dp(this, 2f);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 分享谱面对话框：根据长按对象与当前选择给出默认勾选。
     * - 长按谱包：默认全选；若该谱包内有已选谱面，则默认只勾这些已选谱面。
     * - 长按谱面：默认只勾该谱面；若它本身已选，则默认勾选该谱包内所有已选谱面。
     * 用户可逐个调整，确认后打包分享。
     */
    private void showShareChartsDialog(Library.Song song, Library.Chart focusChart) {
        if (song == null || song.charts == null || song.charts.isEmpty()) {
            Toast.makeText(this, "没有可分享的谱面", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<Library.Chart> all = new ArrayList<>(song.charts);
        final java.util.LinkedHashSet<Library.Chart> checked = new java.util.LinkedHashSet<>();

        if (focusChart != null) {
            if (selectedCharts.contains(focusChart)) {
                for (Library.Chart c : all) {
                    if (selectedCharts.contains(c)) checked.add(c);
                }
            }
            if (checked.isEmpty()) checked.add(focusChart);
        } else {
            for (Library.Chart c : all) {
                if (selectedCharts.contains(c)) checked.add(c);
            }
            if (checked.isEmpty()) checked.addAll(all);
        }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) LazerUi.dp(this, 14f);
        col.setPadding(pad, pad, pad, pad);
        col.setBackground(flatPanel(OsuColour.BACKGROUND_4, 10f));

        TextView title = new TextView(this);
        title.setText("分享谱面");
        title.setTextColor(OsuColour.CONTENT_1);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(this, 15f));
        title.setPadding((int) LazerUi.dp(this, 4f), 0, 0, (int) LazerUi.dp(this, 4f));
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText(song.title + " · 将打包为 ." + Library.shareExtension(all));
        sub.setTextColor(OsuColour.SET_FG1);
        sub.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(this, 11f));
        sub.setPadding((int) LazerUi.dp(this, 4f), 0, 0, (int) LazerUi.dp(this, 8f));
        col.addView(sub);

        final List<TextView> rows = new ArrayList<>();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (Library.Chart c : all) {
            final TextView row = makeShareRow("");
            rows.add(row);
            row.setOnClickListener(v -> {
                if (checked.contains(c)) checked.remove(c);
                else checked.add(c);
                refreshShareChartRows(all, checked, rows);
            });
            list.addView(row);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) LazerUi.dp(this, 320f)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = (int) LazerUi.dp(this, 12f);
        col.addView(actions, alp);

        TextView toggle = makeShareRow("全选 / 全不选");
        toggle.setTextColor(OsuColour.SET_HL1);
        toggle.setOnClickListener(v -> {
            if (checked.size() == all.size()) checked.clear();
            else {
                checked.clear();
                checked.addAll(all);
            }
            refreshShareChartRows(all, checked, rows);
        });
        actions.addView(toggle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        RoundedButtonView cancel = new RoundedButtonView(this);
        cancel.configure("取消", OsuColour.BACKGROUND_3, 13f);
        cancel.setOnClickListener(v -> dialog.dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(
                (int) LazerUi.dp(this, 76f), (int) LazerUi.dp(this, 40f)));

        RoundedButtonView ok = new RoundedButtonView(this);
        ok.configure("分享", OsuColour.SET_HL1, 13f);
        ok.setOnClickListener(v -> {
            if (checked.isEmpty()) {
                Toast.makeText(this, "至少选择一个谱面", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            shareCharts(new ArrayList<>(checked), song.title);
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                (int) LazerUi.dp(this, 88f), (int) LazerUi.dp(this, 40f));
        olp.setMarginStart((int) LazerUi.dp(this, 8f));
        actions.addView(ok, olp);

        refreshShareChartRows(all, checked, rows);

        dialog.setContentView(col);
        dialog.show();
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setDimAmount(0.65f);
            win.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void refreshShareChartRows(List<Library.Chart> all,
                                       java.util.Set<Library.Chart> checked,
                                       List<TextView> rows) {
        for (int i = 0; i < all.size() && i < rows.size(); i++) {
            Library.Chart c = all.get(i);
            boolean on = checked.contains(c);
            TextView row = rows.get(i);
            row.setText((on ? "● " : "○ ") + c.label());
            row.setTextColor(on ? OsuColour.HIGHLIGHT_1 : OsuColour.CONTENT_1);
        }
    }

    /** 从曲库直接分享原始谱面（整首或单个难度），后台即时打包。 */
    private void shareCharts(List<Library.Chart> charts, String label) {
        if (charts == null || charts.isEmpty()) return;
        Toast.makeText(this, "正在打包 " + label + "…", Toast.LENGTH_SHORT).show();
        thumbPool.execute(() -> {
            try {
                File pkg = Library.buildSharePackage(charts);
                mainHandler.post(() -> shareFiles(java.util.Collections.singletonList(pkg)));
            } catch (Exception e) {
                Library.log("分享打包失败: " + e);
                mainHandler.post(() -> Toast.makeText(this,
                        "打包失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 实际发起系统分享；一个文件用 SEND，多个用 SEND_MULTIPLE。 */
    private void shareFiles(List<File> files) {
        try {
            if (files.size() == 1) {
                Uri uri = FileProvider.getUriForFile(this, "com.speedbox.files", files.get(0));
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("*/*");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "分享变速谱面"));
            } else {
                ArrayList<Uri> uris = new ArrayList<>();
                for (File f : files) {
                    uris.add(FileProvider.getUriForFile(this, "com.speedbox.files", f));
                }
                Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE);
                share.setType("*/*");
                share.putExtra(Intent.EXTRA_STREAM, uris);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ClipData clip = ClipData.newUri(getContentResolver(), "SpeedBox", uris.get(0));
                for (int i = 1; i < uris.size(); i++) {
                    clip.addItem(new ClipData.Item(uris.get(i)));
                }
                share.setClipData(clip);
                startActivity(Intent.createChooser(share, "分享变速谱面"));
            }
        } catch (Exception e) {
            Library.log("分享失败: " + e);
            Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void updatePermissionBanner() {
        permBanner.setVisibility(hasStoragePermission() ? View.GONE : View.VISIBLE);
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (contextMenuContainer != null && contextMenuContainer.isMenuOpen()) {
            contextMenuContainer.closeMenu();
            return;
        }
        if (taskOverlayContainer != null && taskOverlayContainer.getVisibility() == View.VISIBLE) {
            toggleAnimatedDrawer(taskOverlayContainer, taskOverlayCard, footerBtnQueue, false);
            return;
        }
        if (settingsPanel != null && settingsPanel.isOpen()) {
            settingsPanel.close();
            footerBtnSettings.setActive(false);
            return;
        }
        super.onBackPressed();
    }
}
