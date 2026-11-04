/*
 * Portions of this file are Java ports of osu!lazer
 * (https://github.com/ppy/osu), Copyright (c) ppy Pty Ltd <contact@ppy.sh>,
 * MIT License. See NOTICE / THIRD_PARTY_LICENSES.md.
 */
package com.speedbox.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * osu!lazer {@code OsuTabControl<T>} + {@code OsuTabControl.OsuTabItem} 的 1:1 Java 移植。
 *
 * 忠实还原的视觉参数 (取自 osu.Game/Graphics/UserInterface/OsuTabControl.cs):
 *   - TabContainer.Spacing = new Vector2(HORIZONTAL_SPACING, 0)  → HORIZONTAL_SPACING = 10  (条目水平间距 10)
 *   - OsuTabItem: AutoSizeAxes = X, RelativeSizeAxes = Y          (宽度自适应文本, 高度撑满控件)
 *   - OsuTabItem.Text: Font = OsuFont.GetFont(size: 14)           (14sp, 项目约定 sans-serif-condensed)
 *   - OsuTabItem.Text.Margin = { Top = 5, Bottom = 5 }            (文本上/下留白 5)
 *   - OsuTabItem.Text: Anchor/Origin = BottomLeft                 (文本沉底, 对应 Gravity.BOTTOM)
 *   - OsuTabItem.Bar: Box { RelativeSizeAxes = X, Height = 1, Alpha = 0, Colour = White, BottomLeft }
 *                     (每个条目自带一条 1px 悬停底栏, 悬停/激活时以 500ms OutQuint 淡入)
 *   - OsuTabItem.TRANSITION_LENGTH = 500: FadeHovered()/FadeUnhovered() 均为 500ms Easing.OutQuint
 *   - strip: Box { Anchor/Origin = BottomLeft, Height = 1, Colour = Color4.White.Opacity(0) }
 *            (控件级底部条, 默认透明白色; 目标几何追向当前选中条目, 500ms Easing.OutQuint)
 *   - AccentColour 默认 colours.Blue → 本移植按项目约定取 LazerUi.OsuColour.SET_HL1 (osu!lazer 设置面板 Highlight1)
 *   - OnActivated: 文本加粗 + FadeHovered (文本转 White); OnDeactivated: 恢复常规字重 + FadeUnhovered
 *
 * Android 侧对应实现: 本控件即 TabContainer (水平 LinearLayout, 每个条目一个子 View),
 * strip 由父控件在 dispatchDraw() 中自绘 (等价 osu!lazer 中 strip 在 TabContainer 之后 AddInternal 的绘制顺序),
 * 条目自带的 Bar 由条目自身在 dispatchDraw() 中自绘。
 *
 * 未移植项: HoverSounds / `UI/tabselect-select` 采样 (工程内无音频资源)。
 */
public final class OsuTabControlView extends LinearLayout {

    /** 选中项变化回调 (对应 osu!lazer TabControl.Current 的 ValueChanged)。 */
    public interface OnTabSelectedListener {
        void onTabSelected(int index);
    }

    // =========================================================================
    // 1:1 常量 (OsuTabControl.cs)
    // =========================================================================
    /** OsuTabControl.HORIZONTAL_SPACING = 10 → TabContainer.Spacing = new Vector2(10f, 0f) */
    private static final float HORIZONTAL_SPACING_DP = 10f;
    /** OsuTabItem: Font = OsuFont.GetFont(size: 14) */
    private static final float DEFAULT_TEXT_SIZE_SP = 14f;
    /** OsuTabItem: Text.Margin = new MarginPadding { Top = 5, Bottom = 5 } */
    private static final float TEXT_VERTICAL_MARGIN_DP = 5f;
    /** OsuTabControl: strip { Height = 2 } (按项目约定 2dp 强调条) */
    private static final float STRIP_HEIGHT_DP = 2f;
    /** OsuTabControl: strip 颜色跟随 accentColour (默认 SET_HL1) */
    private static final int STRIP_COLOUR = LazerUi.OsuColour.SET_HL1;
    /** OsuTabControl: strip.Colour = Color4.White.Opacity(0) —— 默认完全透明 */
    private static final float STRIP_INITIAL_OPACITY = 0f;
    /** OsuTabItem.TRANSITION_LENGTH = 500; 同时用于 strip 的 500ms 追值 (UpdateAfterChildren) */
    private static final long TRANSITION_LENGTH = 500L;

    /** OsuSpriteText 字体: 未选中 = Torus Medium (工程约定 condensed NORMAL), 选中 = Torus Bold (BOLD) */
    private static final Typeface TYPEFACE_REGULAR = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    private static final Typeface TYPEFACE_BOLD = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    // ---- TabContainer 子条目 ----
    private final List<TabItemView> items = new ArrayList<>();

    private OnTabSelectedListener listener;

    /** 当前选中索引; -1 表示尚无选中项 (空控件)。 */
    private int selectedIndex = -1;

    /** OsuTabControl.AccentColour: 默认 colours.Blue → 工程取 SET_HL1 */
    private int accentColour = LazerUi.OsuColour.SET_HL1;

    private float textSizeSp = DEFAULT_TEXT_SIZE_SP;

    // ---- strip (控件级底部条) ----
    private final Paint stripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float stripHeightPx;
    private final float itemSpacingPx;
    private final float textMarginPx;

    /** strip 当前几何 (x / 宽度, 均相对本控件) */
    private float stripX;
    private float stripWidth;
    private float stripTargetX;
    private float stripTargetWidth;
    /** 是否已按真实布局完成首次几何对齐 (首次直接吸附, 之后才走 500ms OutQuint 过渡) */
    private boolean stripGeometryReady;

    /** strip 当前透明度 (strip.Colour.MaxAlpha 的等价物) */
    private float stripOpacity = STRIP_INITIAL_OPACITY;
    /** strip 目标透明度: 默认 0 (Color4.White.Opacity(0)), 首次出现选中项时淡入为 1 */
    private float stripOpacityTarget = STRIP_INITIAL_OPACITY;

    private ValueAnimator stripAnimator;
    private ValueAnimator stripOpacityAnimator;

    public OsuTabControlView(Context c) {
        this(c, null);
    }

    public OsuTabControlView(Context c, AttributeSet a) {
        super(c, a);

        // TabContainer: FillFlowContainer, 水平排列, 条目垂直撑满
        setOrientation(HORIZONTAL);
        setGravity(Gravity.BOTTOM);
        setWillNotDraw(false);

        stripHeightPx = Math.max(1f, LazerUi.dp(c, STRIP_HEIGHT_DP));
        itemSpacingPx = LazerUi.dp(c, HORIZONTAL_SPACING_DP);
        textMarginPx = LazerUi.dp(c, TEXT_VERTICAL_MARGIN_DP);

        stripPaint.setColor(STRIP_COLOUR);
        stripPaint.setAlpha(Math.round(255f * STRIP_INITIAL_OPACITY));

        // 未显式提供 LayoutParams 时给出默认尺寸: 宽度自适应条目内容, 高度自适应文本 + 上下留白
        if (getLayoutParams() == null) {
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    // =========================================================================
    // 公开 API
    // =========================================================================

    /** 重建全部条目 (对应 TabControl.AddItem 的批量形式); 不触发 OnTabSelectedListener。 */
    public void setItems(String[] labels) {
        removeAllViews();
        items.clear();

        int count = labels == null ? 0 : labels.length;
        for (int i = 0; i < count; i++) {
            TabItemView item = new TabItemView(getContext(), labels[i], i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = Math.round(itemSpacingPx); // TabContainer.Spacing.X = 10
            item.setLayoutParams(lp);
            addView(item);
            items.add(item);
        }

        // 选中项合法性: 空控件 → -1; 越界 → 夹紧到 [0, n-1]
        if (items.isEmpty()) {
            selectedIndex = -1;
        } else if (selectedIndex < 0) {
            selectedIndex = 0;
        } else if (selectedIndex >= items.size()) {
            selectedIndex = items.size() - 1;
        }

        syncItems();
        fadeStripIn();
        requestLayout();
    }

    /**
     * 程序化设置选中项 —— 只更新视觉 (粗体 + 文本色 + strip), 不触发 OnTabSelectedListener。
     */
    public void setSelectedIndex(int index) {
        if (items.isEmpty()) {
            // 条目尚未创建: 先记住意图, setItems() 时再夹紧生效
            selectedIndex = Math.max(0, index);
            return;
        }
        int clamped = Math.max(0, Math.min(index, items.size() - 1));
        if (clamped == selectedIndex) return;

        selectedIndex = clamped;
        syncItems();
        fadeStripIn();
        requestLayout();
    }

    /** 当前选中索引; 无条目时为 -1。 */
    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setOnTabSelectedListener(OnTabSelectedListener l) {
        this.listener = l;
    }

    /** OsuTabControl.AccentColour: 未选中条目文本色 + 悬停回落色。 */
    public void setAccentColor(int color) {
        if (accentColour == color) return;
        this.accentColour = color;
        for (TabItemView item : items) {
            item.setAccentColour(color);
        }
    }

    /** OsuTabItem 文本字号 (默认 14sp)。 */
    public void setTextSizeSp(float sp) {
        this.textSizeSp = sp;
        for (TabItemView item : items) {
            item.setTextSizeSp(sp);
        }
        // AutoSizeAxes = X: 字号变化会改变条目宽度, 需要重新测量后再对齐 strip
        requestLayout();
    }

    // =========================================================================
    // 选中 / 布局
    // =========================================================================

    /** 由条目点击触发: 只有索引真正变化才算一次 TabControl 的激活 (与 osu!lazer 一致)。 */
    private void select(int index, boolean byUser) {
        if (index < 0 || index >= items.size()) return;
        if (index == selectedIndex) return;

        selectedIndex = index;
        syncItems();
        fadeStripIn();
        requestLayout();

        if (byUser && listener != null) {
            listener.onTabSelected(index);
        }
    }

    /** 把当前 accent / 选中态同步到全部条目 (OnActivated / OnDeactivated)。 */
    private void syncItems() {
        for (int i = 0; i < items.size(); i++) {
            TabItemView item = items.get(i);
            item.setAccentColour(accentColour);
            item.setActive(i == selectedIndex);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int minH = Math.round(LazerUi.dp(getContext(), 28f));
        if (getMeasuredHeight() < minH) {
            setMeasuredDimension(getMeasuredWidth(), minH);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // 1:1 OsuTabControl.UpdateAfterChildren: 每帧让 strip 追向目标宽度。
        // Android 侧在布局完成后再算目标, 保证粗体切换造成的条目宽度变化后仍然精确对齐。
        alignStrip();
    }

    /** 重新计算 strip 目标几何 (选中条目的左边缘 + 宽度), 首次直接吸附, 之后 500ms OutQuint 过渡。 */
    private void alignStrip() {
        // 1:1 "dont bother calculating if the strip is invisible"
        if (stripOpacityTarget <= 0f) return;
        if (selectedIndex < 0 || selectedIndex >= items.size()) return;

        View selected = items.get(selectedIndex);
        float targetX = selected.getLeft();
        float targetWidth = selected.getRight() - selected.getLeft();

        if (!stripGeometryReady) {
            stripX = targetX;
            stripWidth = targetWidth;
            stripTargetX = targetX;
            stripTargetWidth = targetWidth;
            stripGeometryReady = true;
            invalidate();
            return;
        }

        if (Math.abs(targetX - stripTargetX) < 0.5f && Math.abs(targetWidth - stripTargetWidth) < 0.5f) return;

        stripTargetX = targetX;
        stripTargetWidth = targetWidth;

        final float fromX = stripX;
        final float fromWidth = stripWidth;

        if (stripAnimator != null) stripAnimator.cancel();
        stripAnimator = ValueAnimator.ofFloat(0f, 1f);
        stripAnimator.setDuration(TRANSITION_LENGTH);                 // 500ms
        stripAnimator.setInterpolator(LazerUi.LazerEasing.OUT_QUINT);  // Easing.OutQuint
        stripAnimator.addUpdateListener(anim -> {
            float eased = (float) anim.getAnimatedValue();             // 已按 OutQuint 缓动
            // 等价 Interpolation.ValueAt(..., strip.Width, StripWidth, 0, 500, Easing.OutQuint)
            stripX = fromX + (stripTargetX - fromX) * eased;
            stripWidth = fromWidth + (stripTargetWidth - fromWidth) * eased;
            invalidate();
        });
        stripAnimator.start();
    }

    /** strip 默认透明度为 0 (Color4.White.Opacity(0)); 首次出现选中项时按 500ms OutQuint 淡入。 */
    private void fadeStripIn() {
        if (items.isEmpty()) return;
        if (stripOpacityTarget >= 1f) return;

        stripOpacityTarget = 1f;

        if (stripOpacityAnimator != null) stripOpacityAnimator.cancel();
        stripOpacityAnimator = ValueAnimator.ofFloat(stripOpacity, 1f);
        stripOpacityAnimator.setDuration(TRANSITION_LENGTH);
        stripOpacityAnimator.setInterpolator(LazerUi.LazerEasing.OUT_QUINT);
        stripOpacityAnimator.addUpdateListener(anim -> {
            stripOpacity = (float) anim.getAnimatedValue();
            invalidate();
        });
        stripOpacityAnimator.start();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        // 1:1 OsuTabControl.strip: 激活项底部指示条 (以 500ms OutQuint 顺滑滑动/追随)
        if (stripOpacity <= 0.001f || stripWidth <= 0f) return;

        stripPaint.setColor(accentColour);
        stripPaint.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, stripOpacity))));
        float bottom = getHeight();
        canvas.drawRect(stripX, bottom - stripHeightPx, stripX + stripWidth, bottom, stripPaint);
    }

    // =========================================================================
    // 条目: 1:1 OsuTabControl.OsuTabItem
    // =========================================================================

    /**
     * 单个条目 (AutoSizeAxes = X / RelativeSizeAxes = Y):
     * 文本 (14sp, 上下留白 5, 沉底左对齐) + 自带 1px 白色悬停底栏 (alpha 0)。
     */
    private final class TabItemView extends LinearLayout {

        private final TextView text;
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int index;

        /** 未激活时文本色 (OsuColour.SET_FG1 = 0xFF948FA3) */
        private int itemInactiveColour = LazerUi.OsuColour.SET_FG1;

        /** Active.Value */
        private boolean active;
        /** IsHovered (触摸设备上按下即视为悬停, 与 osu!lazer 由 InputManager 位置驱动的 IsHovered 等价) */
        private boolean hovered;

        /** 文本当前插值色 */
        private int textColour;

        private ValueAnimator textColourAnimator;

        /** OsuTabItem.Bar: RelativeSizeAxes X, Height 1, White, Alpha 0 */
        private float barAlpha = 0f;
        private ValueAnimator barAnimator;

        TabItemView(Context c, String label, int index) {
            super(c);
            this.index = index;

            setOrientation(VERTICAL);
            // Text: Anchor/Origin = BottomLeft
            setGravity(Gravity.BOTTOM);
            setClickable(true);
            setFocusable(true);
            setWillNotDraw(false);

            // OsuSpriteText: Font = OsuFont.GetFont(size: 14), Margin { Top = 5, Bottom = 5 }
            text = new TextView(c);
            text.setText(label == null ? "" : label);
            text.setSingleLine(true);
            text.setIncludeFontPadding(false);
            text.setTypeface(TYPEFACE_REGULAR);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(c, textSizeSp));
            int vPad = Math.round(textMarginPx);
            text.setPadding(0, vPad, 0, vPad);
            text.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(text);

            textColour = itemInactiveColour;
            text.setTextColor(textColour);

            // OnActivatedByUser → TabControl 激活该条目
            setOnClickListener(v -> select(index, true));

            // OnHover / OnHoverLost: 触摸按下即悬停, 抬起/取消即离开
            setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        applyHovered(true);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        applyHovered(false);
                        break;
                    default:
                        break;
                }
                return false; // 不消费事件, 点击仍由 OnClickListener 处理
            });
            setOnHoverListener((v, e) -> {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_ENTER) applyHovered(true);
                else if (action == MotionEvent.ACTION_HOVER_EXIT) applyHovered(false);
                return false;
            });
        }

        // ---- OsuTabItem 状态迁移 ----

        /** OnActivated: 文本 Bold + 白色; OnDeactivated: 文本 Regular + 未激活淡色。 */
        void setActive(boolean value) {
            if (active == value) return;
            active = value;

            if (active) {
                text.setTypeface(TYPEFACE_BOLD);
                fadeHovered();
            } else {
                text.setTypeface(TYPEFACE_REGULAR);
                fadeUnhovered();
            }
            // AutoSizeAxes = X: 字重改变会改变条目宽度
            requestLayout();
        }

        void setAccentColour(int color) {
            // strip 颜色在 OsuTabControlView 中使用 accentColour
        }

        void setTextSizeSp(float sp) {
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(getContext(), sp));
        }

        /** 注意: 不能命名 setHovered —— View 自身已有 public setHovered(boolean)。 */
        private void applyHovered(boolean value) {
            if (hovered == value) return;
            hovered = value;
            if (active) {
                // 1:1 OnHover/OnHoverLost: 激活态下不改变悬停视觉
                return;
            }
            if (value) fadeHovered();
            else fadeUnhovered();
        }

        /** 1:1 FadeHovered(): Bar.FadeIn(500, OutQuint) + Text.FadeColour(White, 500, OutQuint) */
        private void fadeHovered() {
            fadeTextColourTo(LazerUi.OsuColour.SET_CONTENT1); // Color4.White
            fadeBar(1f);
        }

        /** 1:1 FadeUnhovered(): Bar.FadeTo(IsHovered ? 1 : 0, 500, OutQuint) + Text.FadeColour(...) */
        private void fadeUnhovered() {
            fadeTextColourTo(hovered ? LazerUi.OsuColour.SET_CONTENT1 : itemInactiveColour);
            fadeBar(hovered ? 1f : 0f);
        }

        private void fadeBar(float target) {
            if (barAnimator != null) barAnimator.cancel();
            barAnimator = ValueAnimator.ofFloat(barAlpha, target);
            barAnimator.setDuration(TRANSITION_LENGTH); // 500ms
            barAnimator.setInterpolator(LazerUi.LazerEasing.OUT_QUINT);
            barAnimator.addUpdateListener(anim -> {
                barAlpha = (float) anim.getAnimatedValue();
                invalidate();
            });
            barAnimator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // OsuTabItem.Bar: Height = 1, 底部对齐, White
            if (barAlpha <= 0.01f) return;
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            float barH = LazerUi.dp(getContext(), 1f);
            barPaint.setColor(LazerUi.OsuColour.withAlpha(0xFFFFFFFF, barAlpha));
            canvas.drawRect(0f, h - barH, w, h, barPaint);
        }

        private void fadeTextColourTo(int target) {
            if (textColour == target && textColourAnimator == null) return;
            if (textColourAnimator != null) textColourAnimator.cancel();

            textColourAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), textColour, target);
            textColourAnimator.setDuration(TRANSITION_LENGTH);           // 500ms
            textColourAnimator.setInterpolator(LazerUi.LazerEasing.OUT_QUINT);
            textColourAnimator.addUpdateListener(anim -> {
                textColour = (int) anim.getAnimatedValue();
                text.setTextColor(textColour);
            });
            textColourAnimator.start();
        }
    }
}
