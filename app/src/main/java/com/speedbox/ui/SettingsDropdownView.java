/*
 * Portions of this file are Java ports of osu!lazer
 * (https://github.com/ppy/osu), Copyright (c) ppy Pty Ltd <contact@ppy.sh>,
 * MIT License. See NOTICE / THIRD_PARTY_LICENSES.md.
 */
package com.speedbox.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;

/**
 * osu!lazer {@code OsuDropdown<T>} 的 1:1 Java 移植 (Header + Menu + DrawableOsuDropdownMenuItem)。
 *
 * <p>对应源文件 osu.Game/Graphics/UserInterface/OsuDropdown.cs：
 * <ul>
 *   <li><b>OsuDropdownHeader</b>：高度 40dp、圆角 5、背景 Background5(=SET_BG5)、
 *       Foreground.Padding = 10、左侧截断文本 (TruncatingSpriteText, 左对齐)、
 *       右侧 ChevronDown (Size = 10, Margin.Right = 2)；菜单开合时 Chevron
 *       300ms OutQuint 翻转 180° (源为 ScaleTo(1, -1))。</li>
 *   <li><b>OsuDropdownMenu</b>：CornerRadius = 5、背景 Background5、Alpha 从 0 淡入、
 *       ItemsContainer.Padding = 5；HoverColour = Light4 (浅紫)、SelectionColour = Background3；
 *       AnimateOpen/Close = FadeIn/FadeOut(300, OutQuint)。</li>
 *   <li><b>DrawableOsuDropdownMenuItem</b>：Masking + CornerRadius = 5、Foreground.Padding = 2、
 *       文本左对齐且左内边距 15、Hover 浅紫 / 选中 Background3。菜单最高 200dp，超出可滚动。</li>
 * </ul>
 *
 * <p>配色取自 {@link LazerUi.OsuColour} 的 Purple (设置面板) 方案：
 * SET_BG5 / SET_BG3 / SET_LIGHT1 / SET_CONTENT1 / SET_HL1。</p>
 */
public final class SettingsDropdownView extends View {

    /** 选中回调。仅在用户点击某一项时触发；setSelectedIndex() 不会触发。 */
    public interface OnItemSelectedListener {
        void onItemSelected(int index);
    }

    // ===================== OsuDropdown.cs 常量 =====================
    private static final float CORNER_RADIUS_DP = 5f;         // corner_radius = 5
    private static final float HEADER_HEIGHT_DP = 40f;        // Height = 40
    private static final float HEADER_PADDING_DP = 10f;       // Foreground.Padding = new MarginPadding(10)
    private static final float CHEVRON_SIZE_DP = 10f;         // SpriteIcon Size = new Vector2(10)
    private static final float CHEVRON_MARGIN_RIGHT_DP = 2f;  // Margin.Right = 2
    private static final float ITEM_TEXT_LEFT_DP = 15f;       // Label.Padding.Left = 15
    private static final float MENU_PADDING_DP = 5f;          // ItemsContainer.Padding = new MarginPadding(5)
    private static final float MENU_ITEM_HEIGHT_DP = 40f;     // 菜单项行高 ~40dp
    private static final float MENU_MAX_HEIGHT_DP = 200f;     // 菜单最高 200dp
    private static final long ANIM_DURATION_MS = 300L;        // FadeIn/FadeOut(300, Easing.OutQuint)

    private static final float HEADER_TEXT_SIZE_SP = 14f;
    private static final float ITEM_TEXT_SIZE_SP = 13.5f;

    // ===================== 颜色 (Purple 设置面板配色) =====================
    /** Header 背景：Background5。 */
    private static final int HEADER_BG = LazerUi.OsuColour.SET_BG5;
    /**
     * Header 悬停：源中为 colourProvider.Light4。
     * 此处将 SET_BG5 向 SET_LIGHT1(浅紫) 混合，得到"更亮"的紫调背景且保证白字可读。
     */
    private static final int HEADER_BG_HOVER =
            LazerUi.OsuColour.interpolateRgb(LazerUi.OsuColour.SET_BG5, LazerUi.OsuColour.SET_LIGHT1, 0.40f);
    /** 菜单项 Hover：HoverColour = Light4 -> 浅紫。 */
    private static final int MENU_HOVER = LazerUi.OsuColour.SET_LIGHT1;
    /** 菜单项选中：SelectionColour = Background3。 */
    private static final int MENU_SELECTED = LazerUi.OsuColour.SET_BG3;
    /** 文字/图标：Content1 (白)。 */
    private static final int CONTENT = LazerUi.OsuColour.SET_CONTENT1;

    // ===================== 状态 =====================
    private String[] items = new String[0];
    private int selectedIndex = -1;
    private String placeholder = "";
    private int accentColor = LazerUi.OsuColour.SET_HL1;
    private OnItemSelectedListener listener;

    private boolean hovered = false;
    private boolean menuOpen = false;
    private float chevronFlip = 0f; // 0 = 朝下, 1 = 朝上
    private ValueAnimator chevronAnim;

    // ===================== 绘制资源 =====================
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevronPath = new Path();
    private final RectF rf = new RectF();

    // ===================== 弹窗 =====================
    private PopupWindow popup;
    private ScrollView menuScroll;
    private MenuItemView[] menuItems;

    public SettingsDropdownView(Context c) {
        this(c, null);
    }

    public SettingsDropdownView(Context c, AttributeSet a) {
        super(c, a);

        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);

        labelPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        labelPaint.setTextAlign(Paint.Align.LEFT);
        labelPaint.setColor(CONTENT);

        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(LazerUi.dp(c, 2f));
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        chevronPaint.setColor(CONTENT);

        bgPaint.setStyle(Paint.Style.FILL);
        accentPaint.setStyle(Paint.Style.FILL);

        // 鼠标/触控笔悬停（Android 上触摸设备用按下态模拟，见 onTouchEvent）
        setOnHoverListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    hovered = true;
                    invalidate();
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    hovered = false;
                    invalidate();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    // =========================================================================
    // 公共 API
    // =========================================================================

    public void setItems(String[] labels) {
        if (menuOpen) closeMenu();
        if (labels == null) labels = new String[0];
        this.items = labels;
        if (selectedIndex >= items.length) selectedIndex = -1;
        invalidate();
    }

    /** 程序化设置选中项——不会触发 OnItemSelectedListener。 */
    public void setSelectedIndex(int index) {
        int next = (index >= 0 && index < items.length) ? index : -1;
        if (next == selectedIndex) return;
        selectedIndex = next;
        if (menuItems != null) {
            for (int i = 0; i < menuItems.length; i++) {
                if (menuItems[i] != null) menuItems[i].setItemSelected(i == next);
            }
        }
        invalidate();
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setOnItemSelectedListener(OnItemSelectedListener l) {
        this.listener = l;
    }

    public void setPlaceholder(String text) {
        this.placeholder = text != null ? text : "";
        invalidate();
    }

    /**
     * 强调色。OsuDropdown 本身完全由 overlay 配色推导颜色，没有 accent 概念；
     * 这里把 accentColor 用作"菜单展开时 Header 底部的 2dp 强调下划线"，
     * 让该 API 有实际视觉效果（默认 = SET_HL1 紫色）。
     */
    public void setAccentColor(int color) {
        this.accentColor = color;
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        // OsuDropdownHeader.updateColour(): Alpha = Enabled.Value ? 1 : 0.3f
        setAlpha(enabled ? 1f : 0.3f);
        if (!enabled && menuOpen) closeMenu();
    }

    // =========================================================================
    // 测量
    // =========================================================================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Context c = getContext();
        int desiredW = Math.round(LazerUi.dp(c, 240f));
        int desiredH = Math.round(LazerUi.dp(c, HEADER_HEIGHT_DP));
        int w = resolveSize(desiredW, widthMeasureSpec);
        int h = resolveSize(desiredH, heightMeasureSpec);
        setMeasuredDimension(w, h);
    }

    // =========================================================================
    // 绘制 (OsuDropdownHeader)
    // =========================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Context c = getContext();
        float cr = LazerUi.dp(c, CORNER_RADIUS_DP);

        // ---- 背景：Background5，悬停时更亮 (Light4 方向) ----
        bgPaint.setColor(hovered ? HEADER_BG_HOVER : HEADER_BG);
        rf.set(0f, 0f, w, h);
        canvas.drawRoundRect(rf, cr, cr, bgPaint);

        // ---- Chevron (FontAwesome Solid ChevronDown, Size = 10, Margin.Right = 2) ----
        float s = LazerUi.dp(c, CHEVRON_SIZE_DP);
        float chevronCx = w - LazerUi.dp(c, HEADER_PADDING_DP)
                - LazerUi.dp(c, CHEVRON_MARGIN_RIGHT_DP) - s * 0.5f;
        float chevronCy = h * 0.5f;

        // ---- 左侧截断文本 (左对齐, 内边距 10) ----
        String text = currentLabel();
        labelPaint.setTextSize(LazerUi.sp(c, HEADER_TEXT_SIZE_SP));
        float textLeft = LazerUi.dp(c, HEADER_PADDING_DP);
        float textRight = chevronCx - s * 0.5f - LazerUi.dp(c, 6f);
        float avail = Math.max(0f, textRight - textLeft);
        CharSequence shown = TextUtils.ellipsize(text, labelPaint, Math.max(0f, avail), TextUtils.TruncateAt.END);
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        float baseline = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(shown, 0, shown.length(), textLeft, baseline, labelPaint);

        // ---- Chevron 路径 (翻转时绕自身中心旋转 180°) ----
        float hw = s * 0.5f;
        float hh = s * 0.28f;
        chevronPath.reset();
        chevronPath.moveTo(-hw, -hh);
        chevronPath.lineTo(0f, hh);
        chevronPath.lineTo(hw, -hh);

        canvas.save();
        canvas.translate(chevronCx, chevronCy);
        canvas.rotate(180f * chevronFlip);
        canvas.drawPath(chevronPath, chevronPaint);
        canvas.restore();

        // ---- 展开时的 2dp 强调下划线 (accentColor) ----
        if (chevronFlip > 0.01f) {
            float barH = LazerUi.dp(c, 2f);
            accentPaint.setColor(accentColor);
            accentPaint.setAlpha(Math.round(255f * Math.min(1f, chevronFlip)));
            rf.set(cr, h - barH, w - cr, h);
            canvas.drawRoundRect(rf, barH * 0.5f, barH * 0.5f, accentPaint);
        }
    }

    private String currentLabel() {
        if (selectedIndex >= 0 && selectedIndex < items.length && items[selectedIndex] != null) {
            return items[selectedIndex];
        }
        return placeholder != null ? placeholder : "";
    }

    // =========================================================================
    // 触摸 / 点击 (点击 Header 打开下拉菜单)
    // =========================================================================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                hovered = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                hovered = false;
                invalidate();
                performClick();
                toggleMenu();
                return true;
            case MotionEvent.ACTION_CANCEL:
                hovered = false;
                invalidate();
                return true;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    // =========================================================================
    // 菜单 (OsuDropdownMenu) —— PopupWindow 承载
    // =========================================================================

    private void toggleMenu() {
        if (menuOpen) {
            closeMenu();
        } else {
            openMenu();
        }
    }

    private void openMenu() {
        if (menuOpen) return;
        if (items.length == 0) return;
        if (!isAttachedToWindow()) return;

        // 清掉可能残余的上一次弹窗，避免重叠。
        destroyPopup();

        buildPopup();
        if (popup == null || menuScroll == null) {
            menuOpen = false;
            animateChevron(false);
            return;
        }

        menuOpen = true;
        animateChevron(true);

        // AnimateOpen(): FadeIn(300, OutQuint)
        final View content = menuScroll;
        content.setAlpha(0f);
        try {
            popup.showAsDropDown(this, 0, Math.round(LazerUi.dp(getContext(), 4f)));
        } catch (Exception e) {
            destroyPopup();
            menuOpen = false;
            animateChevron(false);
            return;
        }
        content.animate()
                .alpha(1f)
                .setDuration(ANIM_DURATION_MS)
                .setInterpolator(LazerUi.LazerEasing.OUT_QUINT)
                .start();
    }

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        animateChevron(false);

        final PopupWindow p = popup;
        if (p == null) return;

        // AnimateClose(): FadeOut(300, OutQuint)
        View content = p.getContentView();
        if (content != null) {
            content.animate().cancel();
            content.animate()
                    .alpha(0f)
                    .setDuration(ANIM_DURATION_MS)
                    .setInterpolator(LazerUi.LazerEasing.OUT_QUINT)
                    .withEndAction(() -> {
                        if (p == popup) {
                            destroyPopup();
                        } else if (p.isShowing()) {
                            p.dismiss();
                        }
                    })
                    .start();
        } else if (p.isShowing()) {
            p.dismiss();
        }
    }

    private void buildPopup() {
        Context c = getContext();
        final int pad = Math.round(LazerUi.dp(c, MENU_PADDING_DP));
        final int itemH = Math.round(LazerUi.dp(c, MENU_ITEM_HEIGHT_DP));

        // ---- ItemsContainer (OsuDropdownMenu.ItemsContainer.Padding = 5) ----
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        menuItems = new MenuItemView[items.length];
        for (int i = 0; i < items.length; i++) {
            MenuItemView item = new MenuItemView(c);
            item.bind(i, items[i] != null ? items[i] : "", i == selectedIndex);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemH);
            column.addView(item, lp);
            menuItems[i] = item;
        }

        // ---- MaskingContainer: CornerRadius = 5, BackgroundColour = Background5 ----
        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(HEADER_BG);
        menuBg.setCornerRadius(LazerUi.dp(c, CORNER_RADIUS_DP));
        scroll.setBackground(menuBg);
        scroll.setClipToOutline(true);
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 尺寸：宽度 = max(Header 宽度, 最宽项)，高度 = min(内容, 200dp) ----
        TextPaint measure = new TextPaint(labelPaint);
        measure.setTextSize(LazerUi.sp(c, ITEM_TEXT_SIZE_SP));
        float maxTextW = 0f;
        for (String s : items) {
            if (s != null) maxTextW = Math.max(maxTextW, measure.measureText(s));
        }
        float headerW = getWidth() > 0 ? getWidth() : LazerUi.dp(c, 240f);
        int contentW = (int) Math.ceil(maxTextW)
                + Math.round(LazerUi.dp(c, ITEM_TEXT_LEFT_DP))
                + Math.round(LazerUi.dp(c, 12f))
                + pad * 2;
        int width = (int) Math.max(headerW, contentW);
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        width = Math.min(width, screenW - Math.round(LazerUi.dp(c, 16f)));

        int contentH = items.length * itemH + pad * 2;
        int height = Math.min(contentH, Math.round(LazerUi.dp(c, MENU_MAX_HEIGHT_DP)));

        popup = new PopupWindow(scroll, width, height);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(LazerUi.dp(c, 6f));

        menuScroll = scroll;

        final PopupWindow self = popup;
        popup.setOnDismissListener(() -> {
            if (popup != self) return;
            popup = null;
            menuScroll = null;
            menuItems = null;
            menuOpen = false;
            animateChevron(false);
        });
    }

    private void destroyPopup() {
        PopupWindow p = popup;
        popup = null;
        menuScroll = null;
        menuItems = null;
        if (p != null) {
            p.setOnDismissListener(null);
            if (p.isShowing()) p.dismiss();
        }
    }

    private void animateChevron(boolean open) {
        if (chevronAnim != null) chevronAnim.cancel();
        chevronAnim = ValueAnimator.ofFloat(chevronFlip, open ? 1f : 0f);
        chevronAnim.setDuration(ANIM_DURATION_MS);
        chevronAnim.setInterpolator(LazerUi.LazerEasing.OUT_QUINT);
        chevronAnim.addUpdateListener(a -> {
            chevronFlip = (float) a.getAnimatedValue();
            invalidate();
        });
        chevronAnim.start();
    }

    private void handleItemClick(int index) {
        if (index < 0 || index >= items.length) return;

        selectedIndex = index;
        if (menuItems != null) {
            for (int i = 0; i < menuItems.length; i++) {
                if (menuItems[i] != null) menuItems[i].setItemSelected(i == index);
            }
        }
        invalidate();

        closeMenu();

        if (listener != null) listener.onItemSelected(index);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (chevronAnim != null) {
            chevronAnim.cancel();
            chevronAnim = null;
        }
        menuOpen = false;
        destroyPopup();
    }

    // =========================================================================
    // DrawableOsuDropdownMenuItem (菜单项)
    // =========================================================================

    private final class MenuItemView extends View {

        private int index;
        private boolean itemSelected;
        private boolean itemHovered;
        /** DrawableOsuDropdownMenuItem: 背景 FadeIn(100,OutQuint) / FadeOut(600,OutQuint) */
        private float itemBgAlpha = 0f;
        private int itemBgColour = MENU_HOVER;
        private ValueAnimator itemBgAnimator;

        private final Paint itemBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint itemTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF itemRf = new RectF();

        MenuItemView(Context c) {
            super(c);
            setClickable(true);
            itemTextPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
            itemTextPaint.setTextAlign(Paint.Align.LEFT);
            itemTextPaint.setColor(CONTENT);

            // 鼠标/触控笔悬停
            setOnHoverListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_HOVER_ENTER:
                        itemHovered = true;
                        updateItemBackground();
                        invalidate();
                        break;
                    case MotionEvent.ACTION_HOVER_EXIT:
                        itemHovered = false;
                        updateItemBackground();
                        invalidate();
                        break;
                    default:
                        break;
                }
                return false;
            });
        }

        void bind(int index, String label, boolean selected) {
            this.index = index;
            this.itemSelected = selected;
            setContentDescription(label);
            updateItemBackground();
            invalidate();
        }

        void setItemSelected(boolean selected) {
            if (itemSelected == selected) return;
            itemSelected = selected;
            updateItemBackground();
            invalidate();
        }

        private void updateItemBackground() {
            boolean show = itemHovered || itemSelected;
            itemBgColour = itemHovered ? MENU_HOVER : MENU_SELECTED;
            if (itemBgAnimator != null) itemBgAnimator.cancel();
            itemBgAnimator = ValueAnimator.ofFloat(itemBgAlpha, show ? 1f : 0f);
            itemBgAnimator.setDuration(show ? 100L : 600L);
            itemBgAnimator.setInterpolator(LazerUi.LazerEasing.OUT_QUINT);
            itemBgAnimator.addUpdateListener(a -> {
                itemBgAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            itemBgAnimator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            Context c = getContext();

            // DrawableOsuDropdownMenuItem: Masking + CornerRadius = 5
            // UpdateBackgroundColour(): Hover(Light4) 优先于 Selected(Background3)
            itemRf.set(0f, 0f, w, h);
            float cr = LazerUi.dp(c, CORNER_RADIUS_DP);
            if (itemBgAlpha > 0.01f) {
                itemBgPaint.setColor(LazerUi.OsuColour.withAlpha(itemBgColour, itemBgAlpha));
                canvas.drawRoundRect(itemRf, cr, cr, itemBgPaint);
            }

            // Label: 左对齐 + 左内边距 15
            String label = (index >= 0 && index < items.length && items[index] != null) ? items[index] : "";
            itemTextPaint.setTextSize(LazerUi.sp(c, ITEM_TEXT_SIZE_SP));
            float textLeft = LazerUi.dp(c, ITEM_TEXT_LEFT_DP);
            float avail = Math.max(0f, w - textLeft - LazerUi.dp(c, 12f));
            CharSequence shown = TextUtils.ellipsize(label, itemTextPaint, Math.max(0f, avail), TextUtils.TruncateAt.END);
            Paint.FontMetrics fm = itemTextPaint.getFontMetrics();
            float baseline = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(shown, 0, shown.length(), textLeft, baseline, itemTextPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    itemHovered = true;
                    updateItemBackground();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    boolean inside = isInside(event.getX(), event.getY());
                    if (itemHovered != inside) {
                        itemHovered = inside;
                        updateItemBackground();
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    boolean inside = isInside(event.getX(), event.getY());
                    itemHovered = false;
                    updateItemBackground();
                    invalidate();
                    if (inside) {
                        performClick();
                        handleItemClick(index);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    itemHovered = false;
                    updateItemBackground();
                    invalidate();
                    return true;
                default:
                    break;
            }
            return super.onTouchEvent(event);
        }

        private boolean isInside(float x, float y) {
            return x >= 0f && x <= getWidth() && y >= 0f && y <= getHeight();
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
