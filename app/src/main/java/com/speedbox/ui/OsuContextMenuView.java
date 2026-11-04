package com.speedbox.ui;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import com.speedbox.ui.LazerUi.DrawableOsuMenuItem;
import com.speedbox.ui.LazerUi.OsuMenuItem;
import com.speedbox.ui.LazerUi.OsuColour;

import java.util.List;

/**
 * 1:1 移植自 osu!framework / osu!lazer：
 *  - framework `Menu.cs`（MaskingContainer / ItemsContainer / UpdateSize / 300ms OutQuint 展开）
 *  - framework `ScrollContainer.cs`（Target/Current 指数阻尼、ClampExtension 越界回弹、滚动条尺寸/位置）
 *  - osu.Game `OsuMenu.cs` / `OsuContextMenu.cs`（280ms 淡入、50ms 延迟 + 280ms 淡出）
 *  - osu.Game `OsuScrollContainer.cs` / `OsuScrollbar`（宽 10、圆角 5、margin 3、Additive、最小 30）
 *  - osu.Game `DrawableOsuMenuItem.cs`（见 LazerUi.DrawableOsuMenuItem）
 *
 * 全部自绘，不使用 ScrollView / LinearLayout / TextView。
 */
public final class OsuContextMenuView extends FrameLayout {

    // ===== OsuContextMenu / OsuMenu =====
    private static final int BG = 0xFF223034;            // OsuColour.ContextMenuGray
    private static final float CORNER_RADIUS_DP = 5f;    // OsuContextMenu.MaskingContainer.CornerRadius
    // OsuContextMenu.MaxHeight = 250。菜单高度 = min(内容高度, MaxHeight)，
    // 正常情况下刚好容下所有 children（ScrollableExtent = 0，滚动条不显示），
    // 但 OnDragStart 仍永远允许拖拽 → rubber-band 回弹始终可触发。
    private static final float MAX_HEIGHT_DP = 250f;
    private static final float ITEMS_PADDING_VERTICAL_DP = 4f; // ItemsContainer.Padding = Vertical 4
    private static final long FADE_DURATION = 280;       // OsuMenu.FADE_DURATION
    private static final long DELAY_BEFORE_FADE_OUT = 50; // OsuMenu.DELAY_BEFORE_FADE_OUT
    private static final long MENU_RESIZE_DURATION = 300; // OsuMenu.UpdateSize -> ResizeHeightTo(300, OutQuint)

    // ===== OsuScrollContainer / OsuScrollbar =====
    private static final float SCROLL_BAR_WIDTH_DP = 10f; // OsuScrollContainer.SCROLL_BAR_WIDTH
    private static final float SCROLL_BAR_PADDING_DP = 3f; // margin
    private static final float SCROLL_BAR_MIN_DP = 30f;   // MinimumDimSize = SCROLL_BAR_WIDTH * 3
    private static final int SCROLLBAR_DEFAULT = 0xFF888888; // Gray8
    private static final int SCROLLBAR_HOVER = 0xFFFFFFFF;   // GrayF
    // OsuScrollbar: colourProvider?.Highlight1 ?? colours.Green。
    // ctx menu 没有 OverlayColourProvider → 回退 OsuColour.Green = #88B300
    private static final int SCROLLBAR_ACTIVE = 0xFF88B300;

    // ===== ScrollContainer physics =====
    private static final double CLAMP_EXTENSION = 500;
    private static final double DISTANCE_DECAY_CLAMPING = 0.012;
    private static final double DISTANCE_DECAY_DRAG = 0.0035;
    private static final double DISTANCE_DECAY_JUMP = 0.01;

    private final int maxHeightPx;
    private final int itemsPaddingVerticalPx;
    private final int scrollBarWidthPx;
    private final int scrollBarPaddingPx;
    private final int scrollBarMinPx;
    private final int touchSlop;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrollbarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();

    // ===== 菜单尺寸（Menu.UpdateSize：高度 300ms OutQuint） =====
    private int contentHeightPx = 0;
    private int targetHeightPx = 0;
    private int displayHeightPx = 0;
    private boolean opened = false;
    private ValueAnimator heightAnimator;

    // ===== 滚动状态（ScrollContainer） =====
    private double scrollCurrent = 0;
    private double scrollTarget = 0;
    private double distanceDecay = DISTANCE_DECAY_JUMP;
    private boolean draggingContent = false;
    private float dragLastY = 0f;
    private long dragLastTimeMs = 0L;
    private double averageDragTime = 0;
    private double averageDragDelta = 0;
    private boolean framePosted = false;
    private long lastFrameMs = 0L;
    private final Runnable frameRunnable = this::onFrame;

    // ===== 滚动条 =====
    private float scrollbarAlpha = 0f;
    private float scrollbarAlphaTarget = 0f;
    private ValueAnimator scrollbarFade;
    private int lastScrollbarMeasureHeight = -1;
    private int scrollbarColor = SCROLLBAR_DEFAULT;
    private int scrollbarColorTarget = SCROLLBAR_DEFAULT;
    private ValueAnimator scrollbarColorFade;
    private boolean draggingScrollbar = false;
    private float scrollbarDragOffset = 0f;
    private boolean scrollbarHovered = false;

    // ===== 条目交互 =====
    private int activeIndex = -1;
    private Runnable itemClickCallback;

    public OsuContextMenuView(Context context) {
        this(context, null);
    }

    public OsuContextMenuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);

        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        maxHeightPx = (int) LazerUi.dp(context, MAX_HEIGHT_DP);
        itemsPaddingVerticalPx = (int) LazerUi.dp(context, ITEMS_PADDING_VERTICAL_DP);
        scrollBarWidthPx = (int) LazerUi.dp(context, SCROLL_BAR_WIDTH_DP);
        scrollBarPaddingPx = (int) LazerUi.dp(context, SCROLL_BAR_PADDING_DP);
        scrollBarMinPx = (int) LazerUi.dp(context, SCROLL_BAR_MIN_DP);

        bgPaint.setColor(BG);
        // OsuScrollbar.Blending = Additive
        scrollbarPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

        // MaskingContainer.EdgeEffect = Shadow, Black.Opacity(0.1), Radius 4
        setElevation(LazerUi.dp(context, 4f));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        LazerUi.dp(getContext(), CORNER_RADIUS_DP));
            }
        });
        // 用系统 outline 裁剪代替 canvas.clipPath：clipPath 在硬件加速下与 ADD 混合/子层混合
        // 容易产生圆角锯齿、内容不裁剪等绘制异常。
        setClipToOutline(true);

        setVisibility(View.GONE);
    }

    // =====================================================================
    // 内容 / 尺寸
    // =====================================================================

    public int getMaxHeightPx() {
        return maxHeightPx;
    }

    public void setItems(List<OsuMenuItem> items, Runnable onItemClick) {
        this.itemClickCallback = onItemClick;
        removeAllViews();
        if (items != null) {
            Context c = getContext();
            for (OsuMenuItem item : items) {
                addView(new DrawableOsuMenuItem(c, item));
            }
        }
        scrollCurrent = 0;
        scrollTarget = 0;
        applyScrollToChildren();
        targetHeightPx = measureContentHeight(getWidth() > 0 ? getWidth() : (int) LazerUi.dp(getContext(), 160f));
        requestLayout();
    }

    /** osu Menu.UpdateAfterChildren: 宽度 = 最宽条目 ContentDrawWidth；高度 = itemsFlow.Height（被 MaxHeight 截断）。 */
    private int measureContentHeight(int width) {
        int h = itemsPaddingVerticalPx * 2;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            h += child.getMeasuredHeight();
        }
        contentHeightPx = h;
        return Math.min(h, maxHeightPx);
    }

    /** 菜单自适应宽度（滚动条与内容重叠：ScrollbarOverlapsContent 默认 true）。 */
    public int computeFitWidth() {
        int maxContent = 0;
        boolean any = false;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof DrawableOsuMenuItem)) continue;
            DrawableOsuMenuItem di = (DrawableOsuMenuItem) child;
            if (di.isSpacer()) continue;
            any = true;
            maxContent = Math.max(maxContent, di.getContentWidth());
        }
        if (!any) maxContent = (int) LazerUi.dp(getContext(), 80f);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int maxW = Math.max((int) LazerUi.dp(getContext(), 120f), screenW - (int) LazerUi.dp(getContext(), 16f));
        return Math.min(maxContent, maxW);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);

        int h = itemsPaddingVerticalPx * 2;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            h += child.getMeasuredHeight();
        }
        contentHeightPx = h;
        targetHeightPx = Math.min(h, maxHeightPx);

        int measured = opened ? displayHeightPx : targetHeightPx;
        setMeasuredDimension(width, Math.max(0, measured));

        // ScrollContainer.cs:562-578：只要 AvailableContent 或 DisplayableContent 变化，
        // scrollbarCache 就失效 → 每帧重新执行
        //   Scrollbar.FadeTo(ScrollbarVisible && DefinitelyBigger(Available, Displayable, 1) ? 1 : 0, 200)
        // DisplayableContent 是展开动画中的实时高度，所以 300ms 展开期间每帧都会重发 FadeTo(200)，
        // 从当前 alpha 重新开始线性逼近近似指数（τ≈190ms，约 500ms 到 1.0）。这才是 osu 的透明度时序。
        boolean displayableChanged = measured != lastScrollbarMeasureHeight;
        lastScrollbarMeasureHeight = measured;
        if (targetHeightPx > 0) {
            setScrollbarTarget(opened && contentHeightPx > measured + 1 ? 1f : 0f, displayableChanged);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int y = itemsPaddingVerticalPx;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int ch = child.getMeasuredHeight();
            child.layout(0, y, width, y + ch);
            y += ch;
        }
        applyScrollToChildren();
    }

    /**
     * 不使用 View.scrollTo：它会把整个 View 的绘制（包括 dispatchDraw 里绘制的菜单背景）
     * 一起平移，滚动时背景跟着内容走、露出透明区域。
     * 这里只平移 item 子 View，背景固定在菜单视图坐标里。
     */
    private void applyScrollToChildren() {
        float offset = -(float) scrollCurrent;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setTranslationY(offset);
        }
    }

    // =====================================================================
    // 打开 / 关闭（OsuMenu.AnimateOpen / AnimateClose + UpdateSize）
    // =====================================================================

    public void animateOpen() {
        animate().cancel();
        setVisibility(View.VISIBLE);
        setAlpha(0f);

        opened = true;
        displayHeightPx = 0;
        scrollCurrent = 0;
        scrollTarget = 0;
        applyScrollToChildren();
        requestLayout();
        scrollbarAlpha = 0f;
        scrollbarAlphaTarget = 0f;
        lastScrollbarMeasureHeight = -1;

        // Menu.UpdateSize: ResizeHeightTo(newSize.Y, 300, Easing.OutQuint)
        if (heightAnimator != null) heightAnimator.cancel();
        heightAnimator = ValueAnimator.ofInt(0, targetHeightPx);
        heightAnimator.setDuration(MENU_RESIZE_DURATION);
        heightAnimator.setInterpolator(LazerUi.LazerEasing.OUT_QUINT);
        heightAnimator.addUpdateListener(a -> {
            displayHeightPx = (int) a.getAnimatedValue();
            requestLayout();
        });
        heightAnimator.start();

        // OsuMenu.AnimateOpen: FadeIn(280, OutQuint)
        animate().alpha(1f).setDuration(FADE_DURATION)
                .setInterpolator(LazerUi.LazerEasing.OUT_QUINT).start();
    }

    public void animateClose(Runnable onClosed) {
        animate().cancel();
        if (heightAnimator != null) heightAnimator.cancel();

        // OsuMenu.AnimateClose: Delay(50).FadeOut(280, OutQuint)
        postDelayed(() -> animate()
                .alpha(0f)
                .setDuration(FADE_DURATION)
                .setInterpolator(LazerUi.LazerEasing.OUT_QUINT)
                .withEndAction(() -> {
                    // OsuMenu.UpdateSize: 关闭后高度归零
                    opened = false;
                    displayHeightPx = 0;
                    setVisibility(View.GONE);
                    if (onClosed != null) onClosed.run();
                })
                .start(), DELAY_BEFORE_FADE_OUT);
    }

    // =====================================================================
    // 滚动物理（ScrollContainer.Update）
    // =====================================================================

    private double maxScroll() {
        return Math.max(contentHeightPx - getHeight(), 0);
    }

    /** ScrollContainer.Clamp(position, extension) */
    private double clamp(double position, double extension) {
        return Math.max(Math.min(position, maxScroll() + extension), -extension);
    }

    private double clamp(double position) {
        return clamp(position, 0);
    }

    private void scrollToValue(double value, boolean animated, double decay) {
        scrollTarget = clamp(value, CLAMP_EXTENSION);
        if (animated) distanceDecay = decay;
        else scrollCurrent = scrollTarget;
        ensureFrame();
    }

    private void ensureFrame() {
        if (framePosted) return;
        framePosted = true;
        lastFrameMs = SystemClock.uptimeMillis();
        postOnAnimation(frameRunnable);
    }

    private boolean needsFrame() {
        return Math.abs(scrollCurrent - scrollTarget) > 0.1
                || Math.abs(scrollCurrent - clamp(scrollCurrent)) > 0.1;
    }

    private void onFrame() {
        long now = SystemClock.uptimeMillis();
        double dt = Math.max(1.0, now - lastFrameMs);
        lastFrameMs = now;

        updatePosition(dt);
        applyScrollToChildren();
        invalidate();

        if (needsFrame()) postOnAnimation(frameRunnable);
        else framePosted = false;
    }

    /** 1:1 ScrollContainer.updatePosition */
    private void updatePosition(double elapsed) {
        double localDistanceDecay = distanceDecay;

        boolean currentOutOfBounds = Math.abs(scrollCurrent - clamp(scrollCurrent)) > 0.001;
        boolean targetOutOfBounds = scrollTarget < 0.01 || scrollTarget > maxScroll() - 0.01;

        if (!draggingContent && currentOutOfBounds && targetOutOfBounds) {
            scrollTarget = clamp(scrollTarget, CLAMP_EXTENSION);

            if ((scrollCurrent < scrollTarget && scrollTarget < 0)
                    || (scrollCurrent > scrollTarget && scrollTarget > maxScroll())) {
                localDistanceDecay = DISTANCE_DECAY_CLAMPING * 2;
            }

            scrollTarget = lerp(clamp(scrollTarget), scrollTarget, Math.exp(-DISTANCE_DECAY_CLAMPING * elapsed));

            double clamped = clamp(scrollTarget);
            if (Math.abs(clamped - scrollTarget) < 0.01) scrollTarget = clamped;
        }

        scrollCurrent = lerp(scrollTarget, scrollCurrent, Math.exp(-localDistanceDecay * elapsed));

        if (Math.abs(scrollCurrent - scrollTarget) < 0.01) scrollCurrent = scrollTarget;
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    // =====================================================================
    // 绘制（MaskingContainer + background + items + OsuScrollbar）
    // =====================================================================

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cr = LazerUi.dp(getContext(), CORNER_RADIUS_DP);
        rf.set(0, 0, w, h);
        canvas.drawRoundRect(rf, cr, cr, bgPaint);

        // 子项与滚动条由 clipToOutline 统一裁剪到圆角轮廓内
        super.dispatchDraw(canvas);
        drawScrollbar(canvas, w, h);
    }

    private float scrollbarTrackHeight() {
        // osu 竖向 scrollbar 的 Margin 只有 Left/Right = 3，Top/Bottom = 0，
        // 且 Margin 不会缩小 DrawSize → 滑块轨道 = 整个高度。
        return Math.max(0, getHeight());
    }

    /** ScrollContainer: Size = Clamp(Displayable / Available, MinimumDimSize / size, 1) */
    private float scrollbarThumbHeight() {
        float track = scrollbarTrackHeight();
        int h = getHeight();
        if (h <= 0 || track <= 0 || contentHeightPx <= 0) return track;

        float ratio = Math.min(1f, (float) h / contentHeightPx);
        float minFrac = Math.min(scrollBarMinPx / track, 1f);
        return Math.max(minFrac, ratio) * track;
    }

    /** ToScrollbarPosition(Current) = MovementExtent * (Current / ScrollableExtent) */
    private float scrollbarThumbTop() {
        float travel = Math.max(0f, scrollbarTrackHeight() - scrollbarThumbHeight());
        double extent = maxScroll();
        if (extent <= 0) return 0f;
        return (float) (travel * (scrollCurrent / extent));
    }

    private void setScrollbarTarget(float target) {
        setScrollbarTarget(target, false);
    }

    private void setScrollbarTarget(float target, boolean restartFade) {
        if (restartFade) {
            // 每帧重发 FadeTo(200)：从当前 alpha 重新开始线性逼近
            if (Math.abs(scrollbarAlpha - target) < 0.001f) {
                scrollbarAlphaTarget = target;
                return;
            }
        } else if (Math.abs(scrollbarAlphaTarget - target) < 0.001f) {
            return;
        }
        scrollbarAlphaTarget = target;
        if (scrollbarFade != null) scrollbarFade.cancel();
        scrollbarFade = ValueAnimator.ofFloat(scrollbarAlpha, target);
        scrollbarFade.setDuration(200); // Scrollbar.FadeTo(..., 200)
        scrollbarFade.setInterpolator(new android.view.animation.LinearInterpolator());
        scrollbarFade.addUpdateListener(a -> {
            scrollbarAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        scrollbarFade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { scrollbarFade = null; }
        });
        scrollbarFade.start();
    }

    /** OsuScrollbar.OnHover / OnMouseDown / OnMouseUp: FadeColour(..., 100) */
    private void setScrollbarColor(int color) {
        if (scrollbarColorTarget == color) return;
        scrollbarColorTarget = color;
        if (scrollbarColorFade != null) scrollbarColorFade.cancel();
        scrollbarColorFade = ValueAnimator.ofArgb(scrollbarColor, color);
        scrollbarColorFade.setDuration(100);
        scrollbarColorFade.setInterpolator(new android.view.animation.LinearInterpolator());
        scrollbarColorFade.addUpdateListener(a -> {
            scrollbarColor = (int) a.getAnimatedValue();
            invalidate();
        });
        scrollbarColorFade.start();
    }

    private void drawScrollbar(Canvas canvas, int w, int h) {
        // 只按 alpha 判断：内容刚好装下时滚动条会 ResizeTo(1) 并 FadeTo(0,200)，
        // 这 200ms 仍要画出来（否则会“瞬间消失”而不是渐变）。
        if (scrollbarAlpha <= 0.01f) return;

        float barH = scrollbarThumbHeight();
        float top = scrollbarThumbTop();
        float x = w - scrollBarPaddingPx - scrollBarWidthPx;

        scrollbarPaint.setColor(OsuColour.withAlpha(scrollbarColor, scrollbarAlpha));
        float r = scrollBarWidthPx * 0.5f;
        rf.set(x, top, x + scrollBarWidthPx, top + barH);
        canvas.drawRoundRect(rf, r, r, scrollbarPaint);
    }

    // =====================================================================
    // 触摸（ScrollContainer.OnDrag / Scrollbar.Dragged / Menu 条目）
    // =====================================================================

    private DrawableOsuMenuItem itemAt(int index) {
        if (index < 0 || index >= getChildCount()) return null;
        View v = getChildAt(index);
        return v instanceof DrawableOsuMenuItem ? (DrawableOsuMenuItem) v : null;
    }

    private int findIndexAt(float x, float y) {
        if (x < 0 || x > getWidth() || y < 0 || y > getHeight()) return -1;
        // 子 View 只有 translationY，没有 layout 偏移；把触摸点换算到内容坐标
        float contentY = y - (getChildCount() > 0 ? getChildAt(0).getTranslationY() : 0f);
        float cy = contentY;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof DrawableOsuMenuItem)) continue;
            if (((DrawableOsuMenuItem) child).isSpacer()) continue;
            if (cy >= child.getTop() && cy <= child.getBottom()) return i;
        }
        return -1;
    }

    private void updateHighlight(int index) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof DrawableOsuMenuItem) {
                ((DrawableOsuMenuItem) child).setHighlighted(i == index);
            }
        }
    }

    public void clearHighlights() {
        updateHighlight(-1);
    }

    private void dragScrollbarTo(float thumbTop) {
        float travel = Math.max(1f, scrollbarTrackHeight() - scrollbarThumbHeight());
        double t = Math.max(0.0, Math.min(1.0, thumbTop / travel));
        // onScrollbarMovement: OnUserScroll(Clamp(FromScrollbarPosition(value)), animated: false)
        scrollCurrent = clamp(t * maxScroll());
        scrollTarget = scrollCurrent;
        applyScrollToChildren();
        invalidate();
    }

    private void applyContentDrag(float y) {
        long now = SystemClock.uptimeMillis();
        double timeDelta = now - dragLastTimeMs;
        double decay = Math.pow(0.95, Math.max(0, timeDelta));

        averageDragTime = averageDragTime * decay + timeDelta;
        averageDragDelta = averageDragDelta * decay - (y - dragLastY);

        dragLastTimeMs = now;

        double scrollOffset = -(y - dragLastY);
        double clampedOffset = clamp(scrollTarget + scrollOffset) - clamp(scrollTarget);

        // 拖出边界时减半，给用户“拉橡皮筋”的手感
        scrollOffset = clampedOffset + (scrollOffset - clampedOffset) / 2;

        dragLastY = y;

        scrollTarget = clamp(scrollTarget + scrollOffset, CLAMP_EXTENSION);
        scrollCurrent = scrollTarget;
        applyScrollToChildren();
        invalidate();
    }

    private void endContentDrag() {
        if (averageDragTime <= 0.0) return;

        double velocity = averageDragDelta / averageDragTime;

        // 停止拖拽瞬间已静止则不触发惯性
        if (Math.abs(Math.pow(0.95, SystemClock.uptimeMillis() - dragLastTimeMs) * velocity) < 0.1)
            velocity = 0;

        double distance = velocity / (1 - Math.exp(-DISTANCE_DECAY_DRAG));
        scrollToValue(scrollTarget + distance, true, DISTANCE_DECAY_DRAG);
    }

    public boolean handleMenuTouch(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();
        boolean hasScrollbar = contentHeightPx > getHeight();

        // ---- OsuScrollbar 拖动（命中区 = 滑块本身，与 ScrollbarContainer 的 Size 一致） ----
        if (hasScrollbar) {
            float barTop = scrollbarThumbTop();
            float barH = scrollbarThumbHeight();
            float barLeft = getWidth() - scrollBarPaddingPx - scrollBarWidthPx;
            float slop = LazerUi.dp(getContext(), 4f);
            boolean onThumb = x >= barLeft - slop && x <= getWidth() - scrollBarPaddingPx + slop
                    && y >= barTop && y <= barTop + barH;

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (onThumb) {
                        draggingScrollbar = true;
                        scrollbarDragOffset = y - barTop;
                        setScrollbarColor(SCROLLBAR_ACTIVE); // OsuScrollbar.OnMouseDown
                        clearHighlights();
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (draggingScrollbar) {
                        dragScrollbarTo(y - scrollbarDragOffset);
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (draggingScrollbar) {
                        draggingScrollbar = false;
                        // OsuScrollbar.OnMouseUp: box.FadeColour(White, 100)
                        setScrollbarColor(scrollbarHovered ? SCROLLBAR_HOVER : SCROLLBAR_DEFAULT);
                        invalidate();
                        return true;
                    }
                    break;
            }
            if (draggingScrollbar) return true;
        }

        // ---- ScrollContainer 内容拖拽 / Menu 条目 ----
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                dragLastY = y;
                dragLastTimeMs = SystemClock.uptimeMillis();
                averageDragTime = 0;
                averageDragDelta = 0;
                draggingContent = false;
                scrollTarget = scrollCurrent; // ScrollContainer.OnMouseDown: Target = Current
                activeIndex = findIndexAt(x, y);
                updateHighlight(activeIndex);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dy = y - dragLastY;
                // ScrollContainer.OnDragStart 只检查 Content.AliveInternalChildren.Count != 0，
                // 与 AvailableContent 是否大于 DisplayableContent 无关：
                // 即使内容完全装下（ScrollableExtent = 0），也要允许拖拽产生 rubber-band 回弹。
                if (!draggingContent && getChildCount() > 0
                        && Math.abs(dy) > touchSlop) {
                    draggingContent = true;
                    activeIndex = -1;
                    clearHighlights();
                }
                if (draggingContent) {
                    applyContentDrag(y);
                    return true;
                }
                int idx = findIndexAt(x, y);
                if (idx != activeIndex) {
                    activeIndex = idx;
                    updateHighlight(idx);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (draggingContent) {
                    endContentDrag();
                    draggingContent = false;
                    clearHighlights();
                    return true;
                }

                DrawableOsuMenuItem released = activeIndex >= 0 ? itemAt(activeIndex) : itemAt(findIndexAt(x, y));
                clearHighlights();
                activeIndex = -1;
                if (released != null && !released.isSpacer()) {
                    try {
                        released.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                    } catch (Exception ignored) {
                    }
                    if (itemClickCallback != null) itemClickCallback.run();
                    if (released.getItem().action != null) released.getItem().action.run();
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                draggingContent = false;
                activeIndex = -1;
                clearHighlights();
                return true;
        }
        return false;
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE: {
                float barTop = scrollbarThumbTop();
                float barH = scrollbarThumbHeight();
                float barLeft = getWidth() - scrollBarPaddingPx - scrollBarWidthPx;
                boolean in = contentHeightPx > getHeight()
                        && event.getX() >= barLeft
                        && event.getX() <= getWidth() - scrollBarPaddingPx
                        && event.getY() >= barTop && event.getY() <= barTop + barH;
                if (in != scrollbarHovered) {
                    scrollbarHovered = in;
                    if (!draggingScrollbar) setScrollbarColor(in ? SCROLLBAR_HOVER : SCROLLBAR_DEFAULT);
                    invalidate();
                }
                break;
            }
            case MotionEvent.ACTION_HOVER_EXIT:
                if (scrollbarHovered) {
                    scrollbarHovered = false;
                    if (!draggingScrollbar) setScrollbarColor(SCROLLBAR_DEFAULT);
                    invalidate();
                }
                break;
        }
        return super.onHoverEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (heightAnimator != null) { heightAnimator.cancel(); heightAnimator = null; }
        if (scrollbarFade != null) { scrollbarFade.cancel(); scrollbarFade = null; }
        if (scrollbarColorFade != null) { scrollbarColorFade.cancel(); scrollbarColorFade = null; }
        removeCallbacks(frameRunnable);
        framePosted = false;
    }
}
