/*
 * Portions of this file are Java ports of osu!framework
 * (https://github.com/ppy/osu-framework), Copyright (c) ppy Pty Ltd
 * <contact@ppy.sh>, MIT License. See NOTICE / THIRD_PARTY_LICENSES.md.
 */
package com.speedbox.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * osu!lazer / osu-framework 长按进度圈 —— 1:1 Java 移植（纯自绘 Android View）。
 *
 * <p>权威源码：
 * <ul>
 *   <li>{@code osu-framework/osu.Framework/Graphics/Cursor/CursorContainer.cs} 第 125~171 行的私有嵌套类
 *       {@code CircularLongPressFeedback}（时间线 / 动画 / 颜色 / 半径全部照搬它）；</li>
 *   <li>{@code osu-framework/osu.Framework/Graphics/UserInterface/CircularProgress.cs}
 *       （{@code InnerRadius} = 相对半径的“内孔”比例，0 = 不绘制、越大环越细；
 *       {@code Progress} = 从正上方顺时针扫描填充的比例；默认 {@code RoundedCaps = false}，即平头端点，
 *       且 {@code !RoundedCaps && Progress == 0} 时整块不绘制）。</li>
 * </ul>
 *
 * <p>C# 原始时间线（D = duration，osu 中为 {@code touch_right_click_delay} = 750ms）：
 * <pre>
 * BeginAnimation(duration):
 *   using (BeginDelayedSequence(duration / 3))           // [0, D/3)：完全不可见
 *   {
 *       this.FadeInFromZero();                           // t = D/3 立刻 alpha = 1
 *       progress.FadeColour(Color4.SkyBlue)
 *               .TransformTo(InnerRadius, 0.2f)          // t = D/3 立刻 0.2
 *               .TransformTo(InnerRadius, 0.3f, 150, OutQuint)
 *               .ProgressTo(0)                           // t = D/3 立刻 0
 *               .ProgressTo(1, duration / 3 * 2);        // [D/3, D] 线性 0 -> 1
 *
 *       using (BeginDelayedSequence(duration / 3 * 2))   // 嵌套 = 相对外层起点，绝对时刻 t = D
 *       {
 *           this.FadeOut(500, OutQuint);                 // alpha 1 -> 0（500ms）
 *           progress.FadeColour(Color4.White, 800, OutQuint)
 *                   .TransformTo(InnerRadius, 0.6f, 500, OutQuint);
 *       }
 *   }
 *
 * CancelAnimation():
 *   this.FadeOut(400, OutQuint);
 *   progress.ProgressTo(0, 400, OutQuint)
 *           .TransformTo(InnerRadius, 0.2f, 50, OutQuint);
 * </pre>
 *
 * <p>Android 侧实现要点：
 * <ul>
 *   <li>尺寸：osu {@code Size = Vector2(180)} 按本工程既有换算（180 -> 96dp），故直径 = 96dp、R = 48dp；
 *       {@code Origin = Centre} → 圆以触点 (x, y) 为中心；</li>
 *   <li>颜色：起始 {@code Color4.SkyBlue} = {@code 0xFF87CEEB}，完成阶段 800ms OutQuint 渐变到 {@code Color4.White}；</li>
 *   <li>渲染：{@code Canvas.drawArc} + {@code Paint.Style.STROKE}，
 *       strokeWidth = {@code R - InnerRadius * R}，描边中心线半径 = {@code R * (1 + InnerRadius) / 2}，
 *       {@code StrokeCap.BUTT}（绝不圆头），起始角 -90°（正上方），扫过角 = {@code progress * 360°}；</li>
 *   <li>计时：单个 ValueAnimator 作为“帧时钟”（Linear 插值器，只负责每帧回调），
 *       四个通道（progress / innerRadius / alpha / 颜色）全部用 {@link SystemClock#uptimeMillis()} 的
 *       已流逝毫秒数按 C# 时间线解析式求值 —— 不每帧 new 对象，也不串多个 Animator。</li>
 * </ul>
 */
public final class CircularLongPressFeedbackView extends View {

    // =========================================================================
    // osu 参数常量（严格照抄 C#，不自创）
    // =========================================================================

    /** osu: {@code Size = new Vector2(180)}；按本工程换算 180 -> 96dp，即圆的直径。 */
    private static final float DIAMETER_DP = 96f;

    /** {@code Color4.SkyBlue}。 */
    private static final int COLOUR_SKY_BLUE = 0xFF87CEEB;

    /** {@code Color4.White}（完成阶段的目标色）。 */
    private static final int COLOUR_WHITE = 0xFFFFFFFF;

    /** {@code TransformTo(nameof(progress.InnerRadius), 0.2f)} —— 显示瞬间的内孔比例。 */
    private static final float INNER_RADIUS_START = 0.2f;

    /** 150ms OutQuint 之后到达的内孔比例。 */
    private static final float INNER_RADIUS_PEAK = 0.3f;

    /** 完成阶段 {@code TransformTo(InnerRadius, 0.6f, 500, OutQuint)} 的目标内孔比例。 */
    private static final float INNER_RADIUS_END = 0.6f;

    /** {@code TransformTo(InnerRadius, 0.3f, 150, OutQuint)} —— 0.2 -> 0.3。 */
    private static final long INNER_RADIUS_RISE_MS = 150L;

    /** 完成阶段 {@code this.FadeOut(500, OutQuint)} —— 整体 alpha 1 -> 0。 */
    private static final long FADE_OUT_MS = 500L;

    /** 完成阶段 {@code FadeColour(Color4.White, 800, OutQuint)} —— SkyBlue -> White。 */
    private static final long COLOUR_TO_WHITE_MS = 800L;

    /** 完成阶段 {@code TransformTo(InnerRadius, 0.6f, 500, OutQuint)} —— 0.3 -> 0.6。 */
    private static final long INNER_RADIUS_FALL_MS = 500L;

    /** {@code CancelAnimation}: {@code FadeOut(400, OutQuint)}。 */
    private static final long CANCEL_FADE_MS = 400L;

    /** {@code CancelAnimation}: {@code ProgressTo(0, 400, OutQuint)}。 */
    private static final long CANCEL_PROGRESS_MS = 400L;

    /** {@code CancelAnimation}: {@code TransformTo(InnerRadius, 0.2f, 50, OutQuint)}。 */
    private static final long CANCEL_INNER_RADIUS_MS = 50L;

    /** {@code CircularProgress.cs}: {@code !RoundedCaps && Progress == 0} 时不绘制（此处用极小容差吸收浮点误差）。 */
    private static final float PROGRESS_EPSILON = 1e-4f;

    /** 整体 alpha 低于该值视为完全不可见，直接跳过绘制。 */
    private static final float ALPHA_EPSILON = 1e-3f;

    /** 线性插值器（osu 的 ProgressTo(1, duration / 3 * 2) 即 Linear），无状态、可静态共享。 */
    private static final LinearInterpolator LINEAR = new LinearInterpolator();

    // =========================================================================
    // 绘制资源（全部预分配，onDraw 内零 new）
    // =========================================================================

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    /** R = 直径 / 2 = 48dp。 */
    private final float radiusPx;

    // =========================================================================
    // 运行时状态
    // =========================================================================

    /** 触点坐标（相对本 View），对应 osu 的 {@code Origin = Anchor.Centre}：圆以它为圆心。 */
    private float centerX;
    private float centerY;

    /** 本次长按的总时长 D（osu 中为 750ms）。 */
    private long durationMs = 750L;

    /** 完成阶段整体结束（t = D + 800ms）后触发的回调。 */
    private Runnable onComplete;

    /** 长按反馈是否正在运行（start() 之后、完成或 cancel() 之前为 true）。 */
    private boolean active;

    /** 长按判定是否已在 t = D 触发（之后再调用 cancel() 不应回卷，让收尾动画播完）。 */
    private boolean completed;

    // ---- 四个动画通道的当前帧取值 ----
    /** CircularProgress.Progress：0..1（从正上方顺时针）。 */
    private float progress;
    /** CircularProgress.InnerRadius：0..1（相对半径的内孔比例）。 */
    private float innerRadius;
    /** 整体可见度 alpha：0..1（osu 中该 Drawable 自身的 Alpha）。 */
    private float visibility;
    /** 颜色插值系数：0 = SkyBlue，1 = White。 */
    private float colourFraction;

    /** start() 的起始时刻（SystemClock.uptimeMillis()）。 */
    private long beginStartMs;
    /** 时间线总长度 = D + 800ms。 */
    private long totalMs;

    /**
     * 打断保护代数：每次 start()/cancel() 都会递增，旧动画的所有回调据此失效，
     * 保证 cancel() 能在任意阶段安全打断且不会误触发 onComplete。
     */
    private long generation;

    private ValueAnimator beginAnimator;
    private ValueAnimator cancelAnimator;

    // =========================================================================
    // 构造 / 公开 API
    // =========================================================================

    public CircularLongPressFeedbackView(Context c) {
        this(c, null);
    }

    public CircularLongPressFeedbackView(Context c, AttributeSet a) {
        super(c, a);

        Context ctx = c != null ? c : getContext();
        // 1:1 osu: Size = Vector2(180) -> 96dp 直径，R = 48dp
        this.radiusPx = LazerUi.dp(ctx, DIAMETER_DP * 0.5f);

        ringPaint.setStyle(Paint.Style.STROKE);
        // CircularProgress 默认 RoundedCaps = false —— 平头端点，绝不圆头
        ringPaint.setStrokeCap(Paint.Cap.BUTT);
        ringPaint.setColor(COLOUR_SKY_BLUE);

        // 初始状态对应 osu 的 Alpha = 0（C# 构造函数里 Alpha = 0）
        this.progress = 0f;
        this.innerRadius = INNER_RADIUS_START;
        this.visibility = 0f;
        this.colourFraction = 0f;

        // 纯反馈层：只负责自绘，不抢焦点、不消费触摸
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
    }

    /**
     * 开始长按进度圈动画，等价于 C# {@code TouchLongPressFeedback.BeginAnimation(duration)}。
     *
     * @param x            触点 X（相对本 View）
     * @param y            触点 Y（相对本 View）
     * @param durationMs   总时长 D（osu 中为 750ms）
     * @param onComplete   完成阶段（t = D + 800ms）结束后的回调，可为 null
     */
    public void start(float x, float y, long durationMs, Runnable onComplete) {
        // 任何进行中的动画立即停止（同时让旧回调失效），不触发旧回调
        stopAnimators();

        this.centerX = x;
        this.centerY = y;
        this.durationMs = Math.max(1L, durationMs);
        this.onComplete = onComplete;
        this.active = true;
        this.completed = false;

        // [0, D/3) 静默期内什么都不显示；InnerRadius/颜色回到起始状态
        this.visibility = 0f;
        this.progress = 0f;
        this.innerRadius = INNER_RADIUS_START;
        this.colourFraction = 0f;

        this.beginStartMs = SystemClock.uptimeMillis();
        // 时间线长度 = D + 完成阶段最长的 800ms（FadeColour(Color4.White, 800, OutQuint)）
        this.totalMs = this.durationMs + COLOUR_TO_WHITE_MS;

        final long token = generation;
        final Runnable completion = onComplete;

        beginAnimator = ValueAnimator.ofFloat(0f, 1f);
        beginAnimator.setDuration(totalMs);
        beginAnimator.setInterpolator(LINEAR);
        beginAnimator.addUpdateListener(anim -> {
            if (token != generation) return; // 已被 cancel()/新的 start() 打断
            long elapsed = SystemClock.uptimeMillis() - beginStartMs;
            applyBeginTimeline(elapsed);
            // 1:1 osu：长按判定在 t = D 触发（之后的气圈淡出/变白只是收尾视觉）
            if (!completed && elapsed >= durationMs) {
                completed = true;
                if (completion != null) completion.run();
            }
            invalidate();
        });
        beginAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (token != generation) return;
                // 收敛到终态
                applyBeginTimeline(totalMs);
                active = false;
                if (!completed) {
                    completed = true;
                    if (completion != null) completion.run();
                }
                invalidate();
            }
        });
        beginAnimator.start();

        invalidate();
    }

    /**
     * 取消长按反馈，等价于 C# {@code TouchLongPressFeedback.CancelAnimation()}。
     * 可在时间线任意阶段安全调用：从当前值出发做 400ms OutQuint 淡出 + progress 归零，
     * InnerRadius 50ms OutQuint 回到 0.2f，结束后隐藏（alpha = 0）。
     */
    public void cancel() {
        if (!active) {
            // 本来就没有进行中的反馈 → 不做任何补间，仅确保彻底隐藏
            snapHidden();
            return;
        }

        // 长按判定已完成：让收尾动画（FadeOut 500 / 变白 800 / 内孔外扩）继续播放，不要回卷。
        if (completed) return;

        // 打断瞬间的通道当前值（osu 的 FadeOut/ProgressTo/TransformTo 都从当前值出发继续补间）
        final float fromVisibility = visibility;
        final float fromProgress = progress;
        final float fromInnerRadius = innerRadius;

        stopAnimators();
        active = false;

        final long cancelStartMs = SystemClock.uptimeMillis();
        final long token = generation;

        cancelAnimator = ValueAnimator.ofFloat(0f, 1f);
        cancelAnimator.setDuration(Math.max(CANCEL_FADE_MS, CANCEL_PROGRESS_MS));
        cancelAnimator.setInterpolator(LINEAR);
        cancelAnimator.addUpdateListener(anim -> {
            if (token != generation) return;
            applyCancelTimeline(SystemClock.uptimeMillis() - cancelStartMs,
                    fromVisibility, fromProgress, fromInnerRadius);
            invalidate();
        });
        cancelAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (token != generation) return;
                snapHidden(); // 结束后隐藏 (alpha = 0)
            }
        });
        cancelAnimator.start();

        invalidate();
    }

    /** 是否有长按反馈正在运行。 */
    public boolean isActive() {
        return active;
    }

    // =========================================================================
    // 时间线求值（解析式，按帧调用，零分配）
    // =========================================================================

    /**
     * 依据「距 start() 已流逝的毫秒数」还原 osu {@code BeginAnimation} 的完整时间线。
     *
     * <ul>
     *   <li>[0, D/3)：alpha = 0，完全不可见；</li>
     *   <li>t = D/3：alpha 立即 = 1；InnerRadius 立即 0.2 后 150ms OutQuint -> 0.3；
     *       Progress 立即 0 后 2D/3 ms 线性 -> 1（正好在 t = D 到达 1）；</li>
     *   <li>[D, D+800]：整体 FadeOut(500ms, OutQuint)，颜色 800ms OutQuint 变白，
     *       InnerRadius 500ms OutQuint -> 0.6。</li>
     * </ul>
     */
    private void applyBeginTimeline(long t) {
        final long showAt = durationMs / 3;      // osu: BeginDelayedSequence(duration / 3)
        final long sweepMs = durationMs - showAt; // osu: ProgressTo(1, duration / 3 * 2) -> 恰好到 t = D

        if (t < showAt) {
            // 1) 静默期：完全不可见
            visibility = 0f;
            progress = 0f;
            innerRadius = INNER_RADIUS_START;
            colourFraction = 0f;
            return;
        }

        final long sinceShow = t - showAt;
        final long sinceComplete = t - durationMs; // 嵌套 BeginDelayedSequence 的绝对时刻 = D

        if (sinceComplete < 0) {
            // 2) [D/3, D)：开始扫描
            visibility = 1f;
            innerRadius = INNER_RADIUS_START
                    + (INNER_RADIUS_PEAK - INNER_RADIUS_START)
                    * LazerUi.LazerEasing.outQuint(clamp01(sinceShow / (float) INNER_RADIUS_RISE_MS));
            progress = clamp01(sinceShow / (float) sweepMs);
            colourFraction = 0f;
        } else {
            // 3) [D, D+800]：淡出 + 变白 + 内孔继续外扩
            visibility = 1f - LazerUi.LazerEasing.outQuint(clamp01(sinceComplete / (float) FADE_OUT_MS));
            innerRadius = INNER_RADIUS_PEAK
                    + (INNER_RADIUS_END - INNER_RADIUS_PEAK)
                    * LazerUi.LazerEasing.outQuint(clamp01(sinceComplete / (float) INNER_RADIUS_FALL_MS));
            progress = 1f;
            colourFraction = LazerUi.LazerEasing.outQuint(clamp01(sinceComplete / (float) COLOUR_TO_WHITE_MS));
        }
    }

    /**
     * 依据「距 cancel() 已流逝的毫秒数」还原 osu {@code CancelAnimation} 的补间。
     * C# 的 CancelAnimation 没有 FadeColour 调用，故颜色通道保持打断瞬间的值（此时整体已在淡出）。
     */
    private void applyCancelTimeline(long t, float fromVisibility, float fromProgress, float fromInnerRadius) {
        // this.FadeOut(400, OutQuint)
        final float fadeT = LazerUi.LazerEasing.outQuint(clamp01(t / (float) CANCEL_FADE_MS));
        visibility = fromVisibility * (1f - fadeT);

        // progress.ProgressTo(0, 400, OutQuint)
        final float progressT = LazerUi.LazerEasing.outQuint(clamp01(t / (float) CANCEL_PROGRESS_MS));
        progress = fromProgress * (1f - progressT);

        // .TransformTo(InnerRadius, 0.2f, 50, OutQuint) —— 比另外两个通道短，先归位
        final float radiusT = LazerUi.LazerEasing.outQuint(clamp01(t / (float) CANCEL_INNER_RADIUS_MS));
        innerRadius = fromInnerRadius + (INNER_RADIUS_START - fromInnerRadius) * radiusT;
    }

    // =========================================================================
    // 渲染
    // =========================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 整体可见度（osu 用该 Drawable 自身的 Alpha 控制整体可见度）
        if (visibility <= ALPHA_EPSILON) return;

        // CircularProgress.cs Blit(): InnerRadius == 0 或 (!RoundedCaps && Progress == 0) 时直接不绘制
        if (innerRadius <= 0f) return;
        if (progress <= PROGRESS_EPSILON) return;

        // osu CircularProgress 的 InnerRadius 是“环的厚度比例”（0 = 不可见，1 = 实心盘），
        // 不是“内孔比例”。内孔半径 = R * (1 - InnerRadius)，环厚 = R * InnerRadius。
        final float outerRadius = radiusPx;
        final float strokeWidth = outerRadius * innerRadius;
        if (strokeWidth <= 0f) return;

        // 描边中心线半径 = R * (1 - InnerRadius / 2)
        final float strokeCentreRadius = outerRadius * (1f - innerRadius * 0.5f);
        arcRect.set(centerX - strokeCentreRadius, centerY - strokeCentreRadius,
                centerX + strokeCentreRadius, centerY + strokeCentreRadius);

        ringPaint.setStrokeWidth(strokeWidth);
        // 颜色：SkyBlue -> White（OutQuint），alpha 由整体可见度烘焙进画笔（不额外开 View 图层）
        ringPaint.setColor(LazerUi.OsuColour.withAlpha(
                LazerUi.OsuColour.interpolateRgb(COLOUR_SKY_BLUE, COLOUR_WHITE, colourFraction),
                visibility));

        // 起始角 -90°（正上方），顺时针扫过 progress * 360°；BUTT 端点（RoundedCaps = false）
        canvas.drawArc(arcRect, -90f, progress * 360f, false, ringPaint);
    }

    // =========================================================================
    // 生命周期
    // =========================================================================

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 从窗口分离时立即停表并复位，避免动画回调解引用已废弃的 View
        stopAnimators();
        snapHidden();
    }

    // =========================================================================
    // 内部工具
    // =========================================================================

    /** 立即停止全部动画；generation 递增使所有在途回调失效（不会触发 onComplete）。 */
    private void stopAnimators() {
        generation++;
        if (beginAnimator != null) {
            beginAnimator.cancel();
            beginAnimator = null;
        }
        if (cancelAnimator != null) {
            cancelAnimator.cancel();
            cancelAnimator = null;
        }
    }

    /** 直接复位到完全隐藏状态（无补间）：alpha = 0、progress = 0、InnerRadius = 0.2f。 */
    private void snapHidden() {
        visibility = 0f;
        progress = 0f;
        innerRadius = INNER_RADIUS_START;
        colourFraction = 0f;
        active = false;
        completed = false;
        invalidate();
    }

    private static float clamp01(float v) {
        if (v <= 0f) return 0f;
        if (v >= 1f) return 1f;
        return v;
    }
}
