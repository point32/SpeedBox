package com.speedbox.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import java.util.Locale;

/**
 * osu!lazer 数字输入框 —— OsuTextBox.cs / OsuNumberBox.cs 的 1:1 Java 移植。
 *
 * 忠实还原的视觉参数 (取自 OsuTextBox.cs 的构造函数与 load() 重写):
 *   - Height          = 40            (dp)
 *   - CornerRadius    = 5
 *   - LeftRightPadding= 10
 *   - CaretWidth      = 3             (聚焦时描边厚度 BorderThickness = 3)
 *   - BackgroundUnfocused = Background5  (SET_BG5)
 *   - BackgroundFocused   = Background4  (SET_BG4)
 *   - BackgroundCommit = BorderColour = Highlight1 (SET_HL1)
 *   - Placeholder.Colour  = Foreground1  (SET_FG1)
 *   - InputProperties = TextInputType.Number  (数字 + 小数)
 *   - SelectAllOnFocus = true         (OsuNumberBox.cs)
 *
 * Android 侧对应实现: 自绘圆角矩形 Drawable(填充色 + 可选描边), 聚焦切换时调用
 * invalidateSelf() 刷新背景; 输入法 IME_ACTION_DONE 或失焦时向上抛出提交回调。
 */
public final class OsuNumberBoxView extends EditText {

    /** 数值提交回调。IME 完成键 或 失去焦点 时触发。 */
    public interface OnValueCommittedListener {
        void onValueCommitted(float value);
    }

    // ---- 视觉常量 (与 osu!lazer 对应) ----
    private static final float HEIGHT_DP          = 40f;  // Height = 40
    private static final float CORNER_RADIUS_DP    = 5f;   // CornerRadius = 5
    private static final float LEFT_RIGHT_PADDING_DP = 10f; // LeftRightPadding = 10
    private static final float TEXT_SIZE_SP        = 16f;  // 正文 16sp
    private static final float BORDER_THICKNESS_DP = 3f;   // BorderThickness = 3 (聚焦)

    /** 自绘背景: 圆角矩形填充 + 可选描边。聚焦/失焦时通过 setAppearance() 换色并 invalidateSelf()。 */
    private final OsuTextBoxBackground background;

    private OnValueCommittedListener listener;

    /** 记录最近一次已知数值, 用作解析失败时的兜底 fallback。 */
    private float currentValue = 0f;

    public OsuNumberBoxView(Context c) {
        this(c, null);
    }

    public OsuNumberBoxView(Context c, AttributeSet a) {
        super(c, a);

        background = new OsuTextBoxBackground(LazerUi.dp(c, CORNER_RADIUS_DP));
        // 默认高度 40dp: 未显式提供 LayoutParams 时补一个默认布局参数
        if (getLayoutParams() == null) {
            setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(LazerUi.dp(c, HEIGHT_DP))));
        }
        setMinHeight(Math.round(LazerUi.dp(c, HEIGHT_DP)));

        applyBackgroundState(false);

        // 字体: sans-serif-condensed (对应 OsuFont / OsuSpriteText 风格)
        setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        // 文字 16sp, 颜色为白色 (SET_CONTENT1 == 0xFFFFFFFF)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(c, TEXT_SIZE_SP));
        setTextColor(LazerUi.OsuColour.SET_CONTENT1);
        // 占位提示色 Foreground1 (SET_FG1)
        setHintTextColor(LazerUi.OsuColour.SET_FG1);

        // 左右留白 10dp
        int pad = Math.round(LazerUi.dp(c, LEFT_RIGHT_PADDING_DP));
        setPadding(pad, getPaddingTop(), pad, getPaddingBottom());
        setIncludeFontPadding(false);

        // 单行 + 左对齐(垂直居中)
        setSingleLine(true);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        // 数字 + 小数 (OsuNumberBox: TextInputType.Number)
        setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        setImeOptions(EditorInfo.IME_ACTION_DONE);

        // IME_ACTION_DONE 提交数值
        setOnEditorActionListener((v, actionId, event) -> {
            boolean isDone = actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_UNSPECIFIED && event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (isDone) {
                // 提交并收起软键盘(不改变焦点, 避免失焦路径重复提交)
                commitValue();
                InputMethodManager imm = (InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });
    }

    // =========================================================================
    // 公开 API
    // =========================================================================

    public void setOnValueCommittedListener(OnValueCommittedListener l) {
        this.listener = l;
    }

    /** 解析当前文本为 float; 文本为空或非法时返回 fallback。 */
    public float getFloatValue(float fallback) {
        CharSequence cs = getText();
        if (cs == null) return fallback;
        String s = cs.toString().trim();
        if (s.isEmpty()) {
            return fallback;
        }
        // 容忍逗号小数点
        s = s.replace(',', '.');
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 格式化并写入文本; 不触发 OnValueCommittedListener。
     * (监听器仅在 IME 完成键 / 失焦时触发, 故程序写入天然静默。)
     */
    public void setFloatValue(float value) {
        currentValue = value;
        String formatted = formatFloat(value);
        // 仅当内容真正变化时才 setText, 避免无谓地重置光标
        if (!formatted.contentEquals(getText() == null ? "" : getText())) {
            setText(formatted);
        }
        setSelection(getText() == null ? 0 : getText().length());
    }

    // =========================================================================
    // 焦点处理: 切换背景 + 失焦提交
    // =========================================================================

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);

        // 换背景: 聚焦 SET_BG4 + 3dp SET_HL1 描边; 失焦 SET_BG5 无描边
        applyBackgroundState(focused);

        if (focused) {
            // OsuNumberBox: SelectAllOnFocus = true
            selectAll();
        } else {
            // 失焦即提交
            commitValue();
        }
    }

    // =========================================================================
    // 内部实现
    // =========================================================================

    private void applyBackgroundState(boolean focused) {
        // BasicTextBox.cs: 聚焦 FadeColour(BackgroundFocused, 200, OutExpo)
        //                   失焦 FadeColour(BackgroundUnfocused, 200, Easing.Out)
        if (focused) {
            background.animateAppearance(
                    LazerUi.OsuColour.SET_BG4,
                    LazerUi.OsuColour.SET_HL1,
                    LazerUi.dp(getContext(), BORDER_THICKNESS_DP),
                    200,
                    LazerUi.LazerEasing.OUT_EXPO);
        } else {
            background.animateAppearance(
                    LazerUi.OsuColour.SET_BG5,
                    Color.TRANSPARENT,
                    0f,
                    200,
                    LazerUi.LazerEasing.OUT_QUAD);
        }
        if (getBackground() != background) {
            int pad = Math.round(LazerUi.dp(getContext(), LEFT_RIGHT_PADDING_DP));
            setBackground(background);
            setPadding(pad, 0, pad, 0);
        }
    }

    private void commitValue() {
        if (listener == null) return;
        float v = getFloatValue(currentValue);
        currentValue = v;
        // 归一化显示(去尾零), 但不重入回调
        String formatted = formatFloat(v);
        if (!formatted.contentEquals(getText() == null ? "" : getText())) {
            setText(formatted);
        }
        listener.onValueCommitted(v);
    }

    /** 整数则去掉小数位, 否则去掉多余尾零。始终使用 '.' 作为小数点。 */
    private static String formatFloat(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return "0";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e9f) {
            return String.format(Locale.ROOT, "%d", (long) value);
        }
        String s = String.format(Locale.ROOT, "%.6f", value);
        // 去掉尾随 0 与孤立小数点
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    /**
     * 圆角矩形背景 Drawable —— 忠实映射 BasicTextBox 的
     * BackgroundUnfocused / BackgroundFocused / BorderColour / BorderThickness。
     */
    private static final class OsuTextBoxBackground extends Drawable {

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final float cornerRadius;

        private float borderThickness = 0f;
        private ValueAnimator appearanceAnim;

        OsuTextBoxBackground(float cornerRadiusPx) {
            this.cornerRadius = cornerRadiusPx;
            fillPaint.setStyle(Paint.Style.FILL);
            borderPaint.setStyle(Paint.Style.STROKE);
        }

        /** 更新填充色 / 描边色 / 描边厚度, 并请求重绘。 */
        void setAppearance(int fillColor, int borderColor, float borderThicknessPx) {
            fillPaint.setColor(fillColor);
            borderPaint.setColor(borderColor);
            this.borderThickness = borderThicknessPx;
            borderPaint.setStrokeWidth(borderThicknessPx);
            invalidateSelf();
        }

        /** 200ms 颜色/描边过渡（BasicTextBox.cs FadeColour 200ms）。 */
        void animateAppearance(int fillColor, int borderColor, float borderThicknessPx,
                               long duration, android.animation.TimeInterpolator interpolator) {
            if (appearanceAnim != null) appearanceAnim.cancel();

            int fromFill = fillPaint.getColor();
            int fromBorder = borderPaint.getColor();
            float fromThickness = borderThickness;
            android.animation.ArgbEvaluator argb = new android.animation.ArgbEvaluator();

            appearanceAnim = ValueAnimator.ofFloat(0f, 1f);
            appearanceAnim.setDuration(duration);
            appearanceAnim.setInterpolator(interpolator);
            appearanceAnim.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                fillPaint.setColor((int) argb.evaluate(t, fromFill, fillColor));
                borderPaint.setColor((int) argb.evaluate(t, fromBorder, borderColor));
                borderThickness = fromThickness + (borderThicknessPx - fromThickness) * t;
                borderPaint.setStrokeWidth(borderThickness);
                invalidateSelf();
            });
            appearanceAnim.start();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;

            rf.set(b);
            canvas.drawRoundRect(rf, cornerRadius, cornerRadius, fillPaint);

            if (borderThickness > 0f) {
                float inset = borderThickness * 0.5f;
                float r = Math.max(0f, cornerRadius - inset);
                rf.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
                canvas.drawRoundRect(rf, r, r, borderPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            borderPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            fillPaint.setColorFilter(cf);
            borderPaint.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
