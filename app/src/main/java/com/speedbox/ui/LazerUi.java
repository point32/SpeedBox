/*
 * Portions of this file are Java ports of osu!lazer / osu!framework
 * (https://github.com/ppy/osu, https://github.com/ppy/osu-framework),
 * Copyright (c) ppy Pty Ltd <contact@ppy.sh>, MIT License.
 * See NOTICE / THIRD_PARTY_LICENSES.md / assets/licenses/osu-MIT.txt.
 */
package com.speedbox.ui;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.speedbox.R;

/**
 * osu!lazer (SelectV2 / Argon) 1:1 渲染与动效引擎 (v3):
 * - LazerEasing: 移植 osu.Framework.Graphics.Easing (OutQuint, OutCubic, OutElasticHalf, OutElasticQuarter)
 * - OverlayPanelDrawable: 修复弹窗/抽屉/任务卡大面积斜切变形问题 —— 采用标准端正圆角矩形 (CornerRadius=12dp) + 顶部高光色条 + 柔和渐变描边
 * - ShearedButtonView: 内置自适应字号缩放 (防止窄屏文字出界) + 内部 Canvas 弹性缩放 (不触发父容器裁剪) + OutQuint 点击闪白
 * - PanelSetDrawable & PanelBeatmapDrawable: 支持 ActivationFlash 点击闪白动效 + BPM 同步呼吸光晕 (PulsatingBox)
 * - StarRatingPillAndCounterView: 支持星级数字滚动过渡 + 10 颗星阶梯延迟 OutElasticHalf 波浪绽放动效
 */
public final class LazerUi {

    private LazerUi() {}

    public static float dp(Context ctx, float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    public static float sp(Context ctx, float sp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp, ctx.getResources().getDisplayMetrics());
    }

    // =========================================================================
    // 0. LazerEasing (Direct port of osu.Framework.Graphics.Easing)
    // =========================================================================
    public static final class LazerEasing {
        // osu.Framework DefaultEasingFunction.cs 常量
        private static final double ELASTIC_CONST = 2 * Math.PI / 0.3;
        private static final double ELASTIC_CONST2 = 0.3 / 4;
        private static final double EXPO_OFFSET = Math.pow(2, -10);
        private static final double ELASTIC_OFFSET_FULL = Math.pow(2, -11);
        private static final double ELASTIC_OFFSET_HALF = Math.pow(2, -10) * Math.sin((0.5 - ELASTIC_CONST2) * ELASTIC_CONST);
        private static final double ELASTIC_OFFSET_QUARTER = Math.pow(2, -10) * Math.sin((0.25 - ELASTIC_CONST2) * ELASTIC_CONST);

        /** Easing.OutQuint: 1 - (1 - t)^5 */
        public static final TimeInterpolator OUT_QUINT = t -> {
            float inv = 1f - t;
            return 1f - inv * inv * inv * inv * inv;
        };

        /** Easing.OutCubic: 1 - (1 - t)^3 */
        public static final TimeInterpolator OUT_CUBIC = t -> {
            float inv = 1f - t;
            return 1f - inv * inv * inv;
        };

        /** Easing.Out (OutQuad): t * (2 - t) */
        public static final TimeInterpolator OUT_QUAD = t -> t * (2f - t);

        /** Easing.InQuad: t^2 */
        public static final TimeInterpolator IN_QUAD = t -> t * t;

        /** Easing.InOutCubic */
        public static final TimeInterpolator IN_OUT_CUBIC = t ->
                t < 0.5f ? 4f * t * t * t : 1f + 4f * (t - 1f) * (t - 1f) * (t - 1f);

        /** Easing.InOutSine: .5 - .5*cos(pi*t) */
        public static final TimeInterpolator IN_OUT_SINE = t -> (float) (0.5 - 0.5 * Math.cos(Math.PI * t));

        /** Easing.OutElastic: 2^(-10t)*sin((t-.075)c) + 1 - elastic_offset_full*t */
        public static final TimeInterpolator OUT_ELASTIC = t -> {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            return (float) (Math.pow(2, -10 * t) * Math.sin((t - ELASTIC_CONST2) * ELASTIC_CONST)
                    + 1 - ELASTIC_OFFSET_FULL * t);
        };

        /** Easing.OutElasticHalf: 时间轴压缩为 0.5t（不是把 sin 乘 0.5） */
        public static final TimeInterpolator OUT_ELASTIC_HALF = t -> {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            return (float) (Math.pow(2, -10 * t) * Math.sin((0.5 * t - ELASTIC_CONST2) * ELASTIC_CONST)
                    + 1 - ELASTIC_OFFSET_HALF * t);
        };

        /** Easing.OutElasticQuarter */
        public static final TimeInterpolator OUT_ELASTIC_QUARTER = t -> {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            return (float) (Math.pow(2, -10 * t) * Math.sin((0.25 * t - ELASTIC_CONST2) * ELASTIC_CONST)
                    + 1 - ELASTIC_OFFSET_QUARTER * t);
        };

        /** Easing.OutExpo: 1 - 2^(-10t) + expo_offset*t */
        public static final TimeInterpolator OUT_EXPO = t ->
                (t >= 1f) ? 1f : (float) (1 - Math.pow(2, -10 * t) + EXPO_OFFSET * t);

        public static float outQuint(float t) {
            t = Math.max(0f, Math.min(1f, t));
            float inv = 1f - t;
            return 1f - inv * inv * inv * inv * inv;
        }

        public static float outQuad(float t) {
            t = Math.max(0f, Math.min(1f, t));
            return t * (2f - t);
        }

        public static float inQuad(float t) {
            t = Math.max(0f, Math.min(1f, t));
            return t * t;
        }

        public static float inOutCubic(float t) {
            t = Math.max(0f, Math.min(1f, t));
            return t < 0.5f ? 4f * t * t * t : 1f + 4f * (t - 1f) * (t - 1f) * (t - 1f);
        }

        public static float inOutSine(float t) {
            t = Math.max(0f, Math.min(1f, t));
            return (float) (0.5 - 0.5 * Math.cos(Math.PI * t));
        }

        public static float outElastic(float t) {
            t = Math.max(0f, Math.min(1f, t));
            if (t <= 0f || t >= 1f) return t;
            return (float) (Math.pow(2, -10 * t) * Math.sin((t - ELASTIC_CONST2) * ELASTIC_CONST)
                    + 1 - ELASTIC_OFFSET_FULL * t);
        }

        public static float outElasticHalf(float t) {
            t = Math.max(0f, Math.min(1f, t));
            if (t <= 0f || t >= 1f) return t;
            return (float) (Math.pow(2, -10 * t) * Math.sin((0.5 * t - ELASTIC_CONST2) * ELASTIC_CONST)
                    + 1 - ELASTIC_OFFSET_HALF * t);
        }

        public static float outElasticQuarter(float t) {
            t = Math.max(0f, Math.min(1f, t));
            if (t <= 0f || t >= 1f) return t;
            return (float) (Math.pow(2, -10 * t) * Math.sin((0.25 * t - ELASTIC_CONST2) * ELASTIC_CONST)
                    + 1 - ELASTIC_OFFSET_QUARTER * t);
        }

        public static float outExpo(float t) {
            t = Math.max(0f, Math.min(1f, t));
            return (t >= 1f) ? 1f : (float) (1.0 - Math.pow(2, -10 * t) + EXPO_OFFSET * t);
        }
    }

    // =========================================================================
    // 0b. BeatClock (共享节拍相位 — carousel 面板脉动统一同步，用原曲 BPM)
    //     osu!lazer Panel.PulsatingBox 跟随 working beatmap 的 clock：
    //     所有面板共享同一节拍，且不受 rate mod 影响。
    // =========================================================================
    public static final class BeatClock {
        private static double bpm = 160.0;
        private static double beats = 0.0; // 累计拍数（连续，不取模）
        private static long lastUpdate = SystemClock.uptimeMillis();

        private BeatClock() {}

        /** 设置当前节拍 BPM（应为原曲 BPM，不含变速倍率）。 */
        public static synchronized void setBpm(double b) {
            advance();
            bpm = b > 30 ? b : 160.0;
        }

        public static double beatLengthMs() {
            return Math.max(250.0, 60000.0 / bpm);
        }

        /** 累计已走过的拍数（连续值，BPM 变化时保持连续、不跳变）。 */
        public static synchronized double beats() {
            advance();
            return beats;
        }

        private static void advance() {
            long now = SystemClock.uptimeMillis();
            double dt = now - lastUpdate;
            lastUpdate = now;
            if (dt <= 0) return;
            beats += dt / beatLengthMs();
        }
    }

    // =========================================================================
    // 1. OsuColour.cs & OverlayColourProvider.cs
    // =========================================================================
    public static final class OsuColour {
        public static final int COLOUR_0     = 0xFF99DDFF;
        public static final int HIGHLIGHT_1  = 0xFF66CCFF;
        public static final int COLOUR_2     = 0xFF33B5E5;
        public static final int LIGHT_1      = 0xFFB8D4E3;
        public static final int LIGHT_2      = 0xFF9EC4D9;
        public static final int CONTENT_1    = 0xFFFFFFFF;
        public static final int CONTENT_2    = 0xFFDCE9F0;
        public static final int FOREGROUND_1 = 0xFF8F9CA3;
        public static final int BACKGROUND_1 = 0xFF5C6B70;
        public static final int BACKGROUND_2 = 0xFF455054;
        public static final int BACKGROUND_3 = 0xFF394247;
        public static final int BACKGROUND_4 = 0xFF2E3538;
        public static final int BACKGROUND_5 = 0xFF22272A;
        public static final int BACKGROUND_6 = 0xFF171A1C;

        public static final int OSU_PINK     = 0xFFFF66AB;
        public static final int BACK_DARKER  = 0xFFDE31AE;
        public static final int BACK_LIGHTER = 0xFFFF86DD;
        public static final int LIME_0       = 0xFFCCFF99;
        public static final int LIME_1       = 0xFFB2FF66;
        public static final int GREEN_RANKED = 0xFFB3FF66;
        public static final int PURPLE_1     = 0xFF8C66FF;
        public static final int ORANGE_1     = 0xFFFFD966;
        public static final int DARK_ORANGE1 = 0xFFFF9966;
        public static final int RED_1        = 0xFFFF6666;
        public static final int RED_DANGER   = 0xFFED1121;
        public static final int YELLOW       = 0xFFFFCC22;
        public static final int BLUE_1       = 0xFF66CCFF;

        // OverlayColourProvider, OverlayColourScheme.Purple (Hue = 255) — osu!lazer Settings panel
        public static final int SET_BG1      = 0xFF615C70;
        public static final int SET_BG3      = 0xFF3D3946;
        public static final int SET_BG4      = 0xFF302E38;
        public static final int SET_BG5      = 0xFF242229;
        public static final int SET_BG6      = 0xFF18171C;
        public static final int SET_FG1      = 0xFF948FA3;
        public static final int SET_LIGHT1   = 0xFFC2B8E0;
        public static final int SET_LIGHT3   = 0xFFA394D1;
        public static final int SET_CONTENT1 = 0xFFFFFFFF;
        public static final int SET_CONTENT2 = 0xFFE0DBF0;
        public static final int SET_HL1      = 0xFF8C66FF;

        public static final float STAR_DIFFICULTY_DEFINED_COLOUR_CUTOFF = 6.5f;

        private static final float[] STAR_POINTS = {
                0.1f, 1.25f, 2.0f, 2.5f, 3.3f, 4.2f, 4.9f, 5.8f, 6.7f, 7.7f, 9.0f
        };
        private static final int[] STAR_COLORS = {
                0xFF4290FB, // 0.1
                0xFF4FC0FF, // 1.25
                0xFF4FFFD5, // 2.0
                0xFF7CFF4F, // 2.5
                0xFFF6F05C, // 3.3
                0xFFFF8068, // 4.2
                0xFFFF4E6F, // 4.9
                0xFFC645B8, // 5.8
                0xFF6563DE, // 6.7
                0xFF18158E, // 7.7
                0xFF000000  // 9.0
        };

        public static int forStarDifficulty(double starDifficulty) {
            float sr = Math.round(starDifficulty * 100f) / 100f;
            if (sr < 0.1f) return 0xFFAAAAAA;
            for (int i = 0; i < STAR_POINTS.length - 1; i++) {
                if (sr <= STAR_POINTS[i + 1]) {
                    float t = (sr - STAR_POINTS[i]) / (STAR_POINTS[i + 1] - STAR_POINTS[i]);
                    return interpolateRgb(STAR_COLORS[i], STAR_COLORS[i + 1], t);
                }
            }
            return 0xFF000000;
        }

        public static int forStarDifficultyText(double starDifficulty) {
            if (starDifficulty < STAR_DIFFICULTY_DEFINED_COLOUR_CUTOFF) {
                return 0xBF000000;
            }
            return ORANGE_1;
        }

        public static int foregroundTextColour(double starDifficulty) {
            return starDifficulty >= STAR_DIFFICULTY_DEFINED_COLOUR_CUTOFF
                    ? forStarDifficultyText(starDifficulty)
                    : forStarDifficulty(starDifficulty);
        }

        private static final Pattern BRACKET_LEVEL = Pattern.compile("\\[(\\d{1,2})\\]|lv\\.?\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);

        public static float estimateStarRating(String version, double bpm, int indexInSong) {
            String v = version == null ? "" : version.trim();
            Matcher m = BRACKET_LEVEL.matcher(v);
            if (m.find()) {
                String g = m.group(1) != null ? m.group(1) : m.group(2);
                try {
                    int lv = Integer.parseInt(g);
                    float mapped = 1.15f + lv * 0.31f + (Math.abs(v.hashCode() % 19) * 0.01f);
                    return Math.max(0.80f, Math.min(8.20f, mapped));
                } catch (Exception ignored) {}
            }
            String lower = v.toLowerCase(Locale.ROOT);
            float base;
            if (lower.contains("easy") || lower.contains("ez") || lower.contains("basic") || lower.contains("beginner")) {
                base = 1.55f;
            } else if (lower.contains("normal") || lower.contains("nm") || lower.contains("novice")) {
                base = 2.35f;
            } else if (lower.contains("hard") || lower.contains("hd") || lower.contains("hyper") || lower.contains("advanced")) {
                base = 3.45f;
            } else if (lower.contains("insane") || lower.contains("mx") || lower.contains("another") || lower.contains("exhaust")) {
                base = 4.65f;
            } else if (lower.contains("extra") || lower.contains("ultra") || lower.contains("leggendaria")
                    || lower.contains("overjoy") || lower.contains("cataclysm") || lower.contains("four dimensions")) {
                base = 6.15f;
            } else {
                int h = Math.abs(v.hashCode() % 300);
                base = 2.15f + (indexInSong % 6) * 0.58f + (h / 180.0f);
            }
            if (bpm > 190) base += (float) Math.min(0.75, (bpm - 190) / 110.0);
            return Math.max(1.10f, Math.min(7.95f, base));
        }

        public static int interpolateRgb(int c1, int c2, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
            int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
            int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
            int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
            return Color.argb(a, r, g, b);
        }

        public static int lighten(int color, float amount) {
            float scale = 1f + Math.max(0f, amount);
            int r = Math.min(255, Math.round(Color.red(color) * scale));
            int g = Math.min(255, Math.round(Color.green(color) * scale));
            int b = Math.min(255, Math.round(Color.blue(color) * scale));
            return Color.argb(Color.alpha(color), r, g, b);
        }

        public static int darken(int color, float amount) {
            float scale = 1f / (1f + Math.max(0f, amount));
            int r = Math.max(0, Math.round(Color.red(color) * scale));
            int g = Math.max(0, Math.round(Color.green(color) * scale));
            int b = Math.max(0, Math.round(Color.blue(color) * scale));
            return Color.argb(Color.alpha(color), r, g, b);
        }

        public static int withAlpha(int color, float alphaFraction) {
            int a = Math.max(0, Math.min(255, Math.round(255f * alphaFraction)));
            return (color & 0x00FFFFFF) | (a << 24);
        }
    }

    // =========================================================================
    // 2. Canvas Shear Geometry Helpers
    // =========================================================================
    public static void buildShearedPath(RectF b, float shearX, float r, Path out) {
        out.reset();
        float w = b.width();
        float h = b.height();
        if (w <= 0 || h <= 0) return;

        // Cap maximum horizontal shear shift to 14dp so even taller controls never distort into a slash
        float shift = Math.min(w * 0.28f, h * Math.abs(shearX));
        float leftTopX  = b.left + (shearX >= 0 ? shift : 0f);
        float rightTopX = b.right - (shearX < 0 ? shift : 0f);
        float rightBotX = b.right - (shearX >= 0 ? shift : 0f);
        float leftBotX  = b.left + (shearX < 0 ? shift : 0f);
        float topY = b.top;
        float botY = b.bottom;

        float cr = Math.min(r, Math.min(w - shift, h) * 0.45f);
        if (cr < 0.5f) cr = 0.5f;

        out.moveTo(leftTopX + cr, topY);
        out.lineTo(rightTopX - cr, topY);
        out.quadTo(rightTopX, topY, rightTopX - cr * shearX, topY + cr);
        out.lineTo(rightBotX + cr * shearX, botY - cr);
        out.quadTo(rightBotX, botY, rightBotX - cr, botY);
        out.lineTo(leftBotX + cr, botY);
        out.quadTo(leftBotX, botY, leftBotX + cr * shearX, botY - cr);
        out.lineTo(leftTopX - cr * shearX, topY + cr);
        out.quadTo(leftTopX, topY, leftTopX + cr, topY);
        out.close();
    }

    // =========================================================================
    // 3. OverlayPanelDrawable (Upright rounded card for TaskOverlayCard,
    //    TaskOverlayCard, and Task items — NEVER distorted into a parallelogram!)
    // =========================================================================
    public static final class OverlayPanelDrawable extends Drawable {
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint topBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF rf = new RectF();
        private final float cornerRadius;
        private final float borderWidth;
        private final float headerHeight;
        private final int bgColor;
        private final int accentColor;

        public OverlayPanelDrawable(Context ctx, int bgColor, int accentColor, float headerDp) {
            this.cornerRadius = dp(ctx, 11f);
            this.borderWidth = dp(ctx, 1.5f);
            this.headerHeight = headerDp > 0 ? dp(ctx, headerDp) : 0f;
            this.bgColor = bgColor;
            this.accentColor = accentColor;

            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(bgColor);
            headerPaint.setStyle(Paint.Style.FILL);
            headerPaint.setColor(OsuColour.BACKGROUND_4);
            topBarPaint.setStyle(Paint.Style.FILL);
            topBarPaint.setColor(accentColor);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(borderWidth);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            rf.set(b);
            clipPath.reset();
            clipPath.addRoundRect(rf, cornerRadius, cornerRadius, Path.Direction.CW);

            borderPaint.setShader(new LinearGradient(
                    0, b.top, 0, b.bottom,
                    OsuColour.withAlpha(accentColor, 0.75f),
                    OsuColour.BACKGROUND_2,
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.save();
            canvas.clipPath(clipPath);
            canvas.drawRect(b, bgPaint);
            if (headerHeight > 0) {
                canvas.drawRect(b.left, b.top, b.right, b.top + headerHeight, headerPaint);
            }
            // Top 3dp accent bar
            canvas.drawRect(b.left, b.top, b.right, b.top + borderWidth * 1.8f, topBarPaint);
            canvas.restore();

            rf.set(b);
            rf.inset(borderWidth * 0.5f, borderWidth * 0.5f);
            canvas.drawRoundRect(rf, cornerRadius, cornerRadius, borderPaint);
        }

        @Override public void setAlpha(int alpha) { bgPaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { bgPaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // =========================================================================
    // ProgressNotification.cs: 底部 5dp 进度条, 进度 resize 100ms OutQuad
    // =========================================================================
    public static final class OsuProgressBarView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();

        private float progress = 0f;
        private ValueAnimator progressAnim;

        public OsuProgressBarView(Context context) {
            this(context, null);
        }

        public OsuProgressBarView(Context context, AttributeSet attrs) {
            super(context, attrs);
            trackPaint.setColor(OsuColour.BACKGROUND_5);
            fillPaint.setColor(OsuColour.HIGHLIGHT_1);
        }

        /** ProgressNotification.cs: 进度 ResizeTo(..., 100, Easing.OutQuad) */
        public void setProgress(int percent, int fillColor) {
            float target = Math.max(0f, Math.min(1f, percent / 100f));
            fillPaint.setColor(fillColor);
            if (progressAnim != null) progressAnim.cancel();
            progressAnim = ValueAnimator.ofFloat(progress, target);
            progressAnim.setDuration(100);
            progressAnim.setInterpolator(LazerEasing.OUT_QUAD);
            progressAnim.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            progressAnim.start();
        }

        public void setProgressImmediate(int percent, int fillColor) {
            progress = Math.max(0f, Math.min(1f, percent / 100f));
            fillPaint.setColor(fillColor);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float r = h * 0.5f;
            rf.set(0, 0, w, h);
            canvas.drawRoundRect(rf, r, r, trackPaint);

            if (progress > 0.001f) {
                float fw = Math.max(h, w * progress);
                rf.set(0, 0, fw, h);
                canvas.drawRoundRect(rf, r, r, fillPaint);
            }
        }
    }

    // =========================================================================
    // 4. ShearedDrawable (For low-height sheared strips & banners)
    // =========================================================================
    public static final class ShearedDrawable extends Drawable {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outerPath = new Path();
        private final Path innerStrokePath = new Path();
        private final RectF rf = new RectF();
        private final float shearX;
        private final float cornerRadius;
        private final float borderWidth;
        private final int darkerColour;
        private final int lighterColour;

        public ShearedDrawable(float shearX, float cornerRadius, float borderWidth,
                               int darkerColour, int lighterColour) {
            this.shearX = shearX;
            this.cornerRadius = cornerRadius;
            this.borderWidth = borderWidth;
            this.darkerColour = darkerColour;
            this.lighterColour = lighterColour;
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(darkerColour);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(borderWidth);
        }

        public static ShearedDrawable create(Context ctx, int darkerColour, int lighterColour) {
            return new ShearedDrawable(
                    0.2f, dp(ctx, 7f), dp(ctx, 2f),
                    darkerColour, lighterColour);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            rf.set(bounds);
            buildShearedPath(rf, shearX, cornerRadius, outerPath);

            float inset = borderWidth * 0.5f;
            rf.inset(inset, inset);
            buildShearedPath(rf, shearX, Math.max(1f, cornerRadius - inset), innerStrokePath);

            if (borderWidth > 0) {
                borderPaint.setShader(new LinearGradient(
                        0, bounds.top, 0, bounds.bottom,
                        darkerColour, lighterColour, Shader.TileMode.CLAMP));
            }
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawPath(outerPath, fillPaint);
            if (borderWidth > 0) {
                canvas.drawPath(innerStrokePath, borderPaint);
            }
        }

        @Override public void setAlpha(int alpha) { fillPaint.setAlpha(alpha); borderPaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { fillPaint.setColorFilter(cf); borderPaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // =========================================================================
    // 5. WedgeDrawable (Exact 1:1 port of WedgeBackground.cs)
    // =========================================================================
    public static final class WedgeDrawable extends Drawable {
        private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path wedgePath = new Path();
        private final float cornerRadius;
        private final float startAlpha;
        private final float finalAlpha;
        private final boolean flipHorizontal;

        public WedgeDrawable(Context ctx) {
            this(ctx, 0.92f, 0.62f, false);
        }

        public WedgeDrawable(Context ctx, float startAlpha, float finalAlpha, boolean flipHorizontal) {
            this.cornerRadius = dp(ctx, 10f);
            this.startAlpha = startAlpha;
            this.finalAlpha = finalAlpha;
            this.flipHorizontal = flipHorizontal;
            basePaint.setStyle(Paint.Style.FILL);
            glowPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            wedgePath.reset();
            float w = b.width();
            float h = b.height();
            if (w <= 0 || h <= 0) return;

            float shearX = 0.2f;
            float shift = h * shearX;
            float cr = Math.min(cornerRadius, h * 0.35f);

            wedgePath.moveTo(b.left, b.top);
            wedgePath.lineTo(b.right - cr, b.top);
            wedgePath.quadTo(b.right, b.top, b.right - cr * shearX, b.top + cr);
            wedgePath.lineTo(b.right - shift + cr * shearX, b.bottom - cr);
            wedgePath.quadTo(b.right - shift, b.bottom, b.right - shift - cr, b.bottom);
            wedgePath.lineTo(b.left, b.bottom);
            wedgePath.close();

            int bg5Start = OsuColour.withAlpha(OsuColour.BACKGROUND_5, startAlpha);
            int bg5End   = OsuColour.withAlpha(OsuColour.BACKGROUND_5, finalAlpha);

            if (!flipHorizontal) {
                basePaint.setShader(new LinearGradient(
                        b.left, 0, b.right, 0,
                        new int[]{bg5Start, bg5Start, bg5End},
                        new float[]{0f, 0.7f, 1f},
                        Shader.TileMode.CLAMP));
                glowPaint.setShader(new LinearGradient(
                        b.left, 0, b.left + w * 0.6f, 0,
                        OsuColour.withAlpha(OsuColour.BACKGROUND_2, 0.45f),
                        0x00455054,
                        Shader.TileMode.CLAMP));
            } else {
                basePaint.setShader(new LinearGradient(
                        b.left, 0, b.right, 0,
                        new int[]{bg5End, bg5Start, bg5Start},
                        new float[]{0f, 0.3f, 1f},
                        Shader.TileMode.CLAMP));
                glowPaint.setShader(new LinearGradient(
                        b.right, 0, b.right - w * 0.6f, 0,
                        OsuColour.withAlpha(OsuColour.BACKGROUND_2, 0.45f),
                        0x00455054,
                        Shader.TileMode.CLAMP));
            }
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawPath(wedgePath, basePaint);
            canvas.drawPath(wedgePath, glowPaint);
        }

        @Override public void setAlpha(int alpha) { basePaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { basePaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // =========================================================================
    // 6. ShearedSearchBoxDrawable (Exact 1:1 port of ShearedSearchTextBox.cs)
    // =========================================================================
    public static final class ShearedSearchBoxDrawable extends Drawable {
        private final Paint outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outerPath = new Path();
        private final Path innerPath = new Path();
        private final RectF rf = new RectF();
        private final Context ctx;
        private final float rightCapWidth;

        public ShearedSearchBoxDrawable(Context ctx) {
            this.ctx = ctx;
            this.rightCapWidth = dp(ctx, 40f);
            outerPaint.setStyle(Paint.Style.FILL);
            outerPaint.setColor(OsuColour.BACKGROUND_3);
            innerPaint.setStyle(Paint.Style.FILL);
            innerPaint.setColor(OsuColour.BACKGROUND_4);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(dp(ctx, 2f));
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setColor(OsuColour.CONTENT_1);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            float cr = dp(ctx, 7f);
            rf.set(b);
            buildShearedPath(rf, 0.2f, cr, outerPath);

            rf.set(b.left, b.top, Math.max(b.left + dp(ctx, 40f), b.right - rightCapWidth), b.bottom);
            buildShearedPath(rf, 0.2f, cr, innerPath);
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawPath(outerPath, outerPaint);
            canvas.drawPath(innerPath, innerPaint);

            Rect b = getBounds();
            float cx = b.right - rightCapWidth * 0.52f;
            float cy = b.exactCenterY();
            float r = dp(ctx, 5.0f);
            canvas.drawCircle(cx - dp(ctx, 1.5f), cy - dp(ctx, 1.5f), r, iconPaint);
            canvas.drawLine(cx + r * 0.65f - dp(ctx, 1.5f), cy + r * 0.65f - dp(ctx, 1.5f),
                    cx + r * 1.55f - dp(ctx, 1.5f), cy + r * 1.55f - dp(ctx, 1.5f), iconPaint);
        }

        @Override public void setAlpha(int alpha) { outerPaint.setAlpha(alpha); innerPaint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { outerPaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // =========================================================================
    // 7. PanelSetDrawable (PanelBeatmapSet.cs + PanelSetBackground.cs + ActivationFlash)
    // =========================================================================
    public static final class PanelSetDrawable extends Drawable {
        private final Context ctx;
        private final boolean expanded;
        private final boolean hasSelected;
        private final Bitmap coverBitmap;
        private final long flashStartElapsedMs;

        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chevronBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint chevronIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint baseGradPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint coverPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint diagMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Path outerPath = new Path();
        private final Path contentPath = new Path();
        private final Path chevronGlyph = new Path();
        private final Matrix shaderMatrix = new Matrix();

        public PanelSetDrawable(Context ctx, boolean expanded, boolean hasSelected,
                                Bitmap coverBitmap, long flashStartElapsedMs) {
            this.ctx = ctx;
            this.expanded = expanded;
            this.hasSelected = hasSelected;
            this.coverBitmap = coverBitmap;
            this.flashStartElapsedMs = flashStartElapsedMs;

            chevronBgPaint.setStyle(Paint.Style.FILL);
            chevronBgPaint.setColor(expanded ? Color.WHITE : OsuColour.BACKGROUND_2);

            chevronIconPaint.setStyle(Paint.Style.STROKE);
            chevronIconPaint.setStrokeWidth(dp(ctx, 2f));
            chevronIconPaint.setStrokeCap(Paint.Cap.ROUND);
            chevronIconPaint.setStrokeJoin(Paint.Join.ROUND);
            chevronIconPaint.setColor(OsuColour.BACKGROUND_5);
            flashPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            float r = dp(ctx, 10f);
            float[] radii = {r, r, 0f, 0f, 0f, 0f, r, r};

            outerPath.reset();
            outerPath.addRoundRect(new RectF(b.left, b.top, b.right + r, b.bottom), radii, Path.Direction.CW);

            float leftTabW = expanded ? dp(ctx, 18f) : 0f;
            contentPath.reset();
            contentPath.addRoundRect(new RectF(b.left + leftTabW, b.top, b.right + r, b.bottom), radii, Path.Direction.CW);

            baseGradPaint.setShader(new LinearGradient(
                    b.left + leftTabW, 0, b.right, 0,
                    OsuColour.BACKGROUND_3, OsuColour.BACKGROUND_4, Shader.TileMode.CLAMP));

            if (coverBitmap != null && !coverBitmap.isRecycled()) {
                BitmapShader bs = new BitmapShader(coverBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float vw = Math.max(1f, b.width() - leftTabW);
                float vh = Math.max(1f, b.height());
                float bw = coverBitmap.getWidth();
                float bh = coverBitmap.getHeight();
                float scale = Math.max(vw / bw, vh / bh);
                float dx = b.left + leftTabW + (vw - bw * scale) * 0.5f;
                float dy = b.top + (vh - bh * scale) * 0.5f;
                shaderMatrix.reset();
                shaderMatrix.setScale(scale, scale);
                shaderMatrix.postTranslate(dx, dy);
                bs.setLocalMatrix(shaderMatrix);
                coverPaint.setShader(bs);
                coverPaint.setAlpha(215);
            } else {
                coverPaint.setShader(null);
            }

            float diagShift = b.height() * 0.8f;
            float splitX = b.left + leftTabW + (b.width() - leftTabW) * 0.46f;
            diagMaskPaint.setShader(new LinearGradient(
                    splitX - diagShift, b.top,
                    splitX + diagShift, b.bottom,
                    new int[]{0xF2171A1C, 0xD4171A1C, 0x66171A1C, 0x1A171A1C},
                    new float[]{0f, 0.35f, 0.70f, 1f},
                    Shader.TileMode.CLAMP));

            if (expanded || hasSelected) {
                int glowCol = hasSelected ? OsuColour.HIGHLIGHT_1 : Color.WHITE;
                glowPaint.setShader(new LinearGradient(
                        b.left + b.width() * 0.25f, 0, b.right, 0,
                        0x00FFFFFF, OsuColour.withAlpha(glowCol, expanded ? 0.22f : 0.16f),
                        Shader.TileMode.CLAMP));
            } else {
                glowPaint.setShader(null);
            }

            chevronGlyph.reset();
            if (expanded) {
                float cx = b.left + leftTabW * 0.5f;
                float cy = b.exactCenterY();
                float s = dp(ctx, 3.2f);
                chevronGlyph.moveTo(cx - s * 0.6f, cy - s);
                chevronGlyph.lineTo(cx + s * 0.6f, cy);
                chevronGlyph.lineTo(cx - s * 0.6f, cy + s);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.save();
            canvas.clipRect(b);

            if (expanded) {
                canvas.drawPath(outerPath, chevronBgPaint);
                canvas.drawPath(chevronGlyph, chevronIconPaint);
            }

            canvas.save();
            canvas.clipPath(contentPath);
            canvas.drawPath(contentPath, baseGradPaint);
            if (coverPaint.getShader() != null) {
                canvas.drawPath(contentPath, coverPaint);
            }
            canvas.drawPath(contentPath, diagMaskPaint);
            if (glowPaint.getShader() != null) {
                canvas.drawPath(contentPath, glowPaint);
            }
            canvas.restore();

            // Panel.cs ActivationFlash: 800ms OutQuint white flash on click
            long elapsed = SystemClock.uptimeMillis() - flashStartElapsedMs;
            if (elapsed >= 0 && elapsed < 800) {
                float t = elapsed / 800f;
                float flashAlpha = 0.90f * (1f - LazerEasing.outQuint(t));
                flashPaint.setColor(OsuColour.withAlpha(Color.WHITE, flashAlpha));
                canvas.drawPath(outerPath, flashPaint);
                invalidateSelf();
            }

            if (expanded || hasSelected) {
                shadowPaint.setStyle(Paint.Style.STROKE);
                shadowPaint.setStrokeWidth(dp(ctx, 1.2f));
                shadowPaint.setColor(OsuColour.withAlpha(
                        hasSelected ? OsuColour.HIGHLIGHT_1 : Color.WHITE, 0.55f));
                canvas.drawPath(outerPath, shadowPaint);
            }

            canvas.restore();
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // =========================================================================
    // 8. PanelBeatmapDrawable (PanelBeatmap.cs + PulsatingBox BPM Sync + ActivationFlash)
    // =========================================================================
    public static final class PanelBeatmapDrawable extends Drawable {
        private final Context ctx;
        private final int diffColour;
        private final int iconTextColour;
        private final boolean selected;
        private final int seed;
        private final long flashStartElapsedMs;

        private final Paint borderCrescentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rulesetIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint baseGradPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint diffTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint triPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectionGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Path outerPath = new Path();
        private final Path contentPath = new Path();
        private final Path triPath = new Path();

        public PanelBeatmapDrawable(Context ctx, double starRating, boolean selected,
                                    int seed, long flashStartElapsedMs) {
            this.ctx = ctx;
            this.diffColour = OsuColour.forStarDifficulty(starRating);
            this.iconTextColour = OsuColour.forStarDifficultyText(starRating);
            this.selected = selected;
            this.seed = seed;
            this.flashStartElapsedMs = flashStartElapsedMs;

            borderCrescentPaint.setStyle(Paint.Style.FILL);
            borderCrescentPaint.setColor(diffColour);

            rulesetIconPaint.setStyle(Paint.Style.STROKE);
            rulesetIconPaint.setStrokeWidth(dp(ctx, 1.5f));
            rulesetIconPaint.setColor(iconTextColour);

            triPaint.setStyle(Paint.Style.FILL);
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(dp(ctx, 1.5f));
            edgePaint.setColor(OsuColour.withAlpha(diffColour, 0.85f));
            flashPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            float r = dp(ctx, 10f);
            float[] radii = {r, r, 0f, 0f, 0f, 0f, r, r};

            outerPath.reset();
            outerPath.addRoundRect(new RectF(b.left, b.top, b.right + r, b.bottom), radii, Path.Direction.CW);

            float crescentW = dp(ctx, 14f);
            contentPath.reset();
            contentPath.addRoundRect(new RectF(b.left + crescentW, b.top, b.right + r, b.bottom), radii, Path.Direction.CW);

            baseGradPaint.setShader(new LinearGradient(
                    b.left + crescentW, 0, b.right, 0,
                    OsuColour.BACKGROUND_3, OsuColour.BACKGROUND_4, Shader.TileMode.CLAMP));

            diffTintPaint.setShader(new LinearGradient(
                    b.left + crescentW, 0, b.right, 0,
                    OsuColour.withAlpha(diffColour, 0.26f),
                    OsuColour.withAlpha(diffColour, 0.0f),
                    Shader.TileMode.CLAMP));

            triPaint.setShader(new LinearGradient(
                    0, b.top, 0, b.bottom,
                    OsuColour.withAlpha(diffColour, 0.22f),
                    OsuColour.withAlpha(diffColour, 0.0f),
                    Shader.TileMode.CLAMP));

            if (selected) {
                selectionGlowPaint.setShader(new LinearGradient(
                        b.left + b.width() * 0.2f, 0, b.right, 0,
                        OsuColour.withAlpha(diffColour, 0.0f),
                        OsuColour.withAlpha(diffColour, 0.50f),
                        Shader.TileMode.CLAMP));
            } else {
                selectionGlowPaint.setShader(null);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            canvas.save();
            canvas.clipRect(b);

            canvas.drawPath(outerPath, borderCrescentPaint);

            float crescentW = dp(ctx, 14f);
            float icx = b.left + crescentW * 0.5f;
            float icy = b.exactCenterY();
            canvas.drawCircle(icx, icy, dp(ctx, 3.6f), rulesetIconPaint);

            canvas.save();
            canvas.clipPath(contentPath);
            canvas.drawPath(contentPath, baseGradPaint);
            canvas.drawPath(contentPath, diffTintPaint);

            int w = b.width();
            int h = b.height();
            long now = SystemClock.uptimeMillis();
            for (int i = 0; i < 6; i++) {
                int hHash = Math.abs((seed * 31 + i * 97) % 1000);
                float tx = b.left + crescentW + (hHash / 1000f) * (w - crescentW);
                float drift = selected ? ((now / 45f + i * 17f) % h) : 0f;
                float ty = b.top + ((((hHash * 7) % 1000) / 1000f * h - drift + h) % Math.max(1, h));
                float sz = dp(ctx, 14f + (i % 3) * 8f);
                triPath.reset();
                triPath.moveTo(tx, ty - sz * 0.4f);
                triPath.lineTo(tx - sz * 0.5f, ty + sz * 0.46f);
                triPath.lineTo(tx + sz * 0.5f, ty + sz * 0.46f);
                triPath.close();
                canvas.drawPath(triPath, triPaint);
            }

            // Panel.cs PulsatingBox: 40ms attack to 0.8f, OutQuint decay to 0.4f over remaining beat.
            // 共享 BeatClock（原曲 BPM，非变速后）→ 所有选中面板同步，且改倍率不会让相位抖动。
            if (selected && selectionGlowPaint.getShader() != null) {
                // 1:1 osu Panel.PulsatingBox.getFlashInterval(TimeSignature=4/4)：
                //   嵌套在歌单下的难度面板 DepthLayer = -1 → selectionLayer.DepthLayer = 1 → 每 2 拍闪一次。
                //   每次闪烁：40ms 升到 0.8，随后 beatLength（翻倍至 >=250ms）内 OutQuint 衰减到 0.4。
                double beatLenMs = BeatClock.beatLengthMs();
                double tSinceFlash = (BeatClock.beats() % 2.0) * beatLenMs;
                double decayLen = beatLenMs;
                while (decayLen < 250.0) decayLen *= 2.0;

                float pulseAlpha;
                if (tSinceFlash < 40.0) {
                    pulseAlpha = 0.40f + 0.40f * (float) (tSinceFlash / 40.0);
                } else if (tSinceFlash < 40.0 + decayLen) {
                    float decayT = (float) ((tSinceFlash - 40.0) / decayLen);
                    pulseAlpha = 0.80f - 0.40f * LazerEasing.outQuint(decayT);
                } else {
                    pulseAlpha = 0.40f;
                }
                selectionGlowPaint.setAlpha(Math.round(255 * pulseAlpha));
                canvas.drawPath(contentPath, selectionGlowPaint);
                invalidateSelf();
            }
            canvas.restore();

            // Panel.cs ActivationFlash: 800ms OutQuint white flash on click
            long elapsed = now - flashStartElapsedMs;
            if (elapsed >= 0 && elapsed < 800) {
                float t = elapsed / 800f;
                float flashAlpha = 0.90f * (1f - LazerEasing.outQuint(t));
                flashPaint.setColor(OsuColour.withAlpha(Color.WHITE, flashAlpha));
                canvas.drawPath(outerPath, flashPaint);
                invalidateSelf();
            }

            if (selected) {
                canvas.drawPath(outerPath, edgePaint);
            }

            canvas.restore();
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // =========================================================================
    // 9. StarRatingPillAndCounterView (StarRatingDisplay.cs + StarCounter.cs)
    //    Includes staggered OutElasticHalf 10-star wave animation & number roll!
    // =========================================================================
    public static final class StarRatingPillAndCounterView extends View {
        private float targetStars = 4.65f;
        /** StarCounter.animate() 里的 current（上一次的值），用于计算每颗星的 80ms 延迟。 */
        private float previousStars = 4.65f;
        private boolean showStarCounter = true;
        private long animStartMs = 0L;
        private final Paint pillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint pillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint starCounterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pillRect = new RectF();
        private final Path starPath = new Path();

        public StarRatingPillAndCounterView(Context context) {
            this(context, null);
        }

        public StarRatingPillAndCounterView(Context context, AttributeSet attrs) {
            super(context, attrs);
            pillTextPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            pillTextPaint.setTextAlign(Paint.Align.CENTER);
            starCounterPaint.setStyle(Paint.Style.FILL);
        }

        public void setStars(float starRating, boolean withStarCounter) {
            setStars(starRating, withStarCounter, false);
        }

        public void setStars(float starRating, boolean withStarCounter, boolean triggerWaveAnim) {
            boolean changed = Math.abs(this.targetStars - starRating) > 0.01f;
            if (changed) this.previousStars = this.targetStars;
            // 新面板（展开/刷新）从 0 开始做星星波，等价于 osu 新建 StarCounter 时 current = 0
            if (triggerWaveAnim) this.previousStars = 0f;
            this.targetStars = starRating;
            this.showStarCounter = withStarCounter;
            if (triggerWaveAnim || (changed && animStartMs == 0L)) {
                this.animStartMs = SystemClock.uptimeMillis();
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int h = getHeight();
            if (h <= 0) return;

            long elapsed = (animStartMs > 0L) ? (SystemClock.uptimeMillis() - animStartMs) : 2000L;
            long rollDuration = Math.min(1000L, Math.max(300L, (long) (targetStars * 120L)));
            boolean stillAnimating = elapsed < (1000L + 10 * 80L);

            // StarRatingDisplay.cs: animate displayedStars with dynamic OutQuint
            float numProgress = (elapsed < rollDuration) ? LazerEasing.outQuint(elapsed / (float) rollDuration) : 1f;
            float shownStars = targetStars * numProgress;

            int bgCol = OsuColour.forStarDifficulty(shownStars);
            int txtCol = OsuColour.forStarDifficultyText(shownStars);

            float pillW = dp(getContext(), 48f);
            float pillH = Math.min(h, dp(getContext(), 17.5f));
            float pillTop = (h - pillH) * 0.5f;
            pillRect.set(0, pillTop, pillW, pillTop + pillH);

            pillBgPaint.setColor(bgCol);
            canvas.drawRoundRect(pillRect, pillH * 0.5f, pillH * 0.5f, pillBgPaint);

            pillTextPaint.setTextSize(sp(getContext(), 10.5f));
            pillTextPaint.setColor(txtCol);
            Paint.FontMetrics fm = pillTextPaint.getFontMetrics();
            float textY = pillRect.centerY() - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(String.format(Locale.ROOT, "★ %.2f", shownStars), pillRect.centerX(), textY, pillTextPaint);

            if (!showStarCounter) {
                if (stillAnimating) postInvalidateOnAnimation();
                return;
            }

            // StarCounter.cs: 10 stars with 80ms stagger per star & 1000ms OutElasticHalf scaling
            int starCol = targetStars >= 6.5f ? OsuColour.ORANGE_1 : OsuColour.forStarDifficulty(targetStars);
            float startX = pillW + dp(getContext(), 7f);
            float cy = h * 0.5f;
            float maxR = dp(getContext(), 4.3f);
            float stepX = dp(getContext(), 9.5f);

            // StarCounter.animate():
            //   delay = max(current <= newValue ? i - current : min(current, 10) - 1 - i, 0) * 80
            //   DefaultStar.DisplayAt: scale = Lerp(0.4, 1, clamp(fill)); Icon.ScaleTo(scale, 1000, OutElasticHalf);
            //   FadeTo(scale, 100)
            boolean filling = previousStars <= targetStars;
            for (int i = 0; i < 10; i++) {
                if (startX + maxR > getWidth()) break;

                float targetFill = Math.max(0f, Math.min(1f, targetStars - i));
                float prevFill = Math.max(0f, Math.min(1f, previousStars - i));

                float starDelayMs = Math.max(filling
                        ? i - previousStars
                        : Math.min(previousStars, 10f) - 1f - i, 0f) * 80f;
                float starT = stillAnimating
                        ? Math.max(0f, Math.min(1f, (elapsed - starDelayMs) / 1000f))
                        : 1f;
                float elastic = stillAnimating ? LazerEasing.outElasticHalf(starT) : 1f;

                float targetScale = 0.4f + 0.6f * targetFill;
                float prevScale = 0.4f + 0.6f * prevFill;
                float scale = prevScale + (targetScale - prevScale) * elastic;

                // FadeTo(scale, 100)：alpha 目标即 scale
                float alpha = targetScale;
                starCounterPaint.setColor(OsuColour.withAlpha(starCol, alpha));
                drawFivePointStar(canvas, startX, cy, maxR * scale, starCounterPaint);
                startX += stepX;
            }

            if (stillAnimating) {
                postInvalidateOnAnimation();
            }
        }

        private void drawFivePointStar(Canvas canvas, float cx, float cy, float outerR, Paint paint) {
            starPath.reset();
            float innerR = outerR * 0.382f;
            for (int pt = 0; pt < 10; pt++) {
                double angle = -Math.PI / 2.0 + pt * (Math.PI / 5.0);
                float r = (pt % 2 == 0) ? outerR : innerR;
                float x = cx + (float) (Math.cos(angle) * r);
                float y = cy + (float) (Math.sin(angle) * r);
                if (pt == 0) starPath.moveTo(x, y);
                else starPath.lineTo(x, y);
            }
            starPath.close();
            canvas.drawPath(starPath, paint);
        }
    }

    // =========================================================================
    // 10. StarSpectrumDotsView (PanelBeatmapSet.SpreadDisplay.cs)
    // =========================================================================
    public static final class StarSpectrumDotsView extends View {
        private final List<Integer> pillColors = new ArrayList<>();
        private int totalCount = 0;
        private String statusTag = "MALODY";
        private int statusBgColor = OsuColour.GREEN_RANKED;
        private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();

        public StarSpectrumDotsView(Context context) {
            this(context, null);
        }

        public StarSpectrumDotsView(Context context, AttributeSet attrs) {
            super(context, attrs);
            pillPaint.setStyle(Paint.Style.FILL);
            textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }

        public void setSpread(String statusText, int statusColor, float[] stars) {
            this.statusTag = statusText != null ? statusText : "LOCAL";
            this.statusBgColor = statusColor;
            pillColors.clear();
            if (stars != null) {
                totalCount = stars.length;
                for (float s : stars) {
                    pillColors.add(OsuColour.forStarDifficulty(s));
                }
            } else {
                totalCount = 0;
            }
            invalidate();
        }

        public void setStarRatings(float[] stars) {
            setSpread(statusTag, statusBgColor, stars);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int h = getHeight();
            if (h <= 0) return;

            float x = 0f;
            textPaint.setTextSize(sp(getContext(), 9.5f));
            float tagW = textPaint.measureText(statusTag) + dp(getContext(), 10f);
            float tagH = Math.min(h, dp(getContext(), 14f));
            float tagTop = (h - tagH) * 0.5f;
            rf.set(x, tagTop, x + tagW, tagTop + tagH);
            pillPaint.setColor(statusBgColor);
            canvas.drawRoundRect(rf, tagH * 0.5f, tagH * 0.5f, pillPaint);

            textPaint.setColor(OsuColour.BACKGROUND_5);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(statusTag, x + dp(getContext(), 5f),
                    rf.centerY() - (fm.ascent + fm.descent) * 0.5f, textPaint);

            x += tagW + dp(getContext(), 6f);

            float iconR = dp(getContext(), 4.5f);
            pillPaint.setStyle(Paint.Style.STROKE);
            pillPaint.setStrokeWidth(dp(getContext(), 1.4f));
            pillPaint.setColor(OsuColour.CONTENT_2);
            canvas.drawCircle(x + iconR, h * 0.5f, iconR, pillPaint);
            pillPaint.setStyle(Paint.Style.FILL);
            x += iconR * 2f + dp(getContext(), 5f);

            float dotW = dp(getContext(), 5.5f);
            float dotH = Math.min(h - dp(getContext(), 2f), dp(getContext(), 11f));
            float dotTop = (h - dotH) * 0.5f;
            float gap = dp(getContext(), 1.8f);

            int maxShow = Math.min(pillColors.size(), 12);
            for (int i = 0; i < maxShow; i++) {
                pillPaint.setColor(pillColors.get(i));
                rf.set(x, dotTop, x + dotW, dotTop + dotH);
                canvas.drawRoundRect(rf, dotW * 0.5f, dotW * 0.5f, pillPaint);
                x += dotW + gap;
            }

            if (totalCount > maxShow) {
                textPaint.setColor(OsuColour.CONTENT_2);
                textPaint.setTextSize(sp(getContext(), 10f));
                canvas.drawText("+" + (totalCount - maxShow), x + dp(getContext(), 3f),
                        h * 0.5f - (fm.ascent + fm.descent) * 0.5f, textPaint);
            }
        }
    }

    // =========================================================================
    // 11. TrianglesBackgroundView (TrianglesV2.cs)
    // =========================================================================
    public static final class TrianglesBackgroundView extends View {
        private static final float EQUILATERAL_RATIO = 0.866f;
        private static final int COUNT = 24;
        private final float[] tx = new float[COUNT];
        private final float[] ty = new float[COUNT];
        private final float[] tSize = new float[COUNT];
        private final float[] tSpeed = new float[COUNT];
        private final int[] tColor = new int[COUNT];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triPath = new Path();
        private final Random rng = new Random(20260727L);
        private boolean initialized = false;
        private long lastFrameMs = 0L;

        public TrianglesBackgroundView(Context context) {
            this(context, null);
        }

        public TrianglesBackgroundView(Context context, AttributeSet attrs) {
            super(context, attrs);
            paint.setStyle(Paint.Style.FILL);
        }

        private void initParticles(int w, int h) {
            int[] palette = {
                    OsuColour.withAlpha(OsuColour.BACKGROUND_3, 0.32f),
                    OsuColour.withAlpha(OsuColour.BACKGROUND_2, 0.22f),
                    OsuColour.withAlpha(OsuColour.HIGHLIGHT_1, 0.06f),
                    OsuColour.withAlpha(OsuColour.OSU_PINK, 0.05f)
            };
            for (int i = 0; i < COUNT; i++) {
                tx[i] = rng.nextFloat() * w;
                ty[i] = rng.nextFloat() * h;
                tSize[i] = dp(getContext(), 26f + rng.nextFloat() * 64f);
                tSpeed[i] = dp(getContext(), 8f + rng.nextFloat() * 14f);
                tColor[i] = palette[i % palette.length];
            }
            initialized = true;
            lastFrameMs = System.currentTimeMillis();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (!initialized) initParticles(w, h);

            long now = System.currentTimeMillis();
            float dt = Math.min(0.1f, Math.max(0.008f, (now - lastFrameMs) / 1000f));
            lastFrameMs = now;

            for (int i = 0; i < COUNT; i++) {
                ty[i] -= tSpeed[i] * dt;
                float triH = tSize[i] * EQUILATERAL_RATIO;
                if (ty[i] + triH < 0) {
                    ty[i] = h + triH;
                    tx[i] = rng.nextFloat() * w;
                }
                paint.setColor(tColor[i]);
                triPath.reset();
                triPath.moveTo(tx[i], ty[i]);
                triPath.lineTo(tx[i] - tSize[i] * 0.5f, ty[i] + triH);
                triPath.lineTo(tx[i] + tSize[i] * 0.5f, ty[i] + triH);
                triPath.close();
                canvas.drawPath(triPath, paint);
            }
            postInvalidateOnAnimation();
        }
    }

    // =========================================================================
    // 12. ShearedButtonView (ShearedButton.cs & ShearedToggleButton.cs)
    //     - Auto-fits text size so text NEVER overflows narrow buttons
    //     - Uses internal Canvas scale + OutElasticHalf bounce so parent ScrollViews
    //       never clip the button edges
    // =========================================================================
    public static final class ShearedButtonView extends View {
        private String text = "";
        private int normalDarker = OsuColour.BACKGROUND_3;
        private int normalLighter = OsuColour.BACKGROUND_1;
        private int activeDarker = OsuColour.HIGHLIGHT_1;
        private int activeLighter = OsuColour.COLOUR_0;
        private int normalText = OsuColour.CONTENT_1;
        private int activeText = OsuColour.BACKGROUND_6;
        private boolean active = false;
        private boolean pressed = false;
        private float textSizeSp = 11f;
        private float flashAlpha = 0f;
        private float currentScale = 1f;
        private float shearAmount = 0.2f;
        private float cornerRadiusDp = 7f;
        private ValueAnimator scaleAnim;
        /** ShearedButton.updateState(): FadeColour(..., 150, OutQuint) */
        private float pressT = 0f;
        private ValueAnimator pressAnim;
        /** ShearedButton.updateState() 的 hover 提亮（Lighten 0.2f, 150ms OutQuint） */
        private float hoverT = 0f;
        private ValueAnimator hoverAnim;

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path outerPath = new Path();
        private final Path borderPath = new Path();
        private final RectF rf = new RectF();

        public ShearedButtonView(Context context) {
            this(context, null);
        }

        public ShearedButtonView(Context context, AttributeSet attrs) {
            super(context, attrs);
            setClickable(true);
            textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(context, 2f));
            flashPaint.setStyle(Paint.Style.FILL);
        }

        public void configure(String label, int normalDark, int activeDark, int normTxt, int actTxt, float spSize) {
            this.text = label != null ? label : "";
            this.normalDarker = normalDark;
            this.normalLighter = (normalDark == OsuColour.BACK_DARKER)
                    ? OsuColour.BACK_LIGHTER
                    : OsuColour.BACKGROUND_1;
            this.activeDarker = activeDark;
            this.activeLighter = OsuColour.lighten(activeDark, 0.25f);
            this.normalText = normTxt;
            this.activeText = actTxt;
            this.textSizeSp = spSize;
            invalidate();
        }

        public void setBorderGradient(int darkerTop, int lighterBottom) {
            this.normalDarker = darkerTop;
            this.normalLighter = lighterBottom;
            invalidate();
        }

        public void setText(String label) {
            this.text = label != null ? label : "";
            invalidate();
        }

        public String getText() {
            return text;
        }

        public void setActive(boolean a) {
            if (this.active != a) {
                this.active = a;
                invalidate();
            }
        }

        public boolean isActive() {
            return active;
        }

        /** Flat (un-sheared, larger corner) mode — matches osu!lazer settings controls. */
        public void setFlat(boolean flat) {
            this.shearAmount = flat ? 0f : 0.2f;
            this.cornerRadiusDp = flat ? 9f : 7f;
            invalidate();
        }

        private void animateInternalScale(float target, long duration, TimeInterpolator interp) {
            if (scaleAnim != null) scaleAnim.cancel();
            scaleAnim = ValueAnimator.ofFloat(currentScale, target);
            scaleAnim.setDuration(duration);
            scaleAnim.setInterpolator(interp);
            scaleAnim.addUpdateListener(a -> {
                currentScale = (float) a.getAnimatedValue();
                invalidate();
            });
            scaleAnim.start();
        }

        private void animatePress(float target) {
            if (pressAnim != null) pressAnim.cancel();
            pressAnim = ValueAnimator.ofFloat(pressT, target);
            pressAnim.setDuration(150);
            pressAnim.setInterpolator(LazerEasing.OUT_QUINT);
            pressAnim.addUpdateListener(a -> {
                pressT = (float) a.getAnimatedValue();
                invalidate();
            });
            pressAnim.start();
        }

        private void animateHover(float target) {
            if (hoverAnim != null) hoverAnim.cancel();
            hoverAnim = ValueAnimator.ofFloat(hoverT, target);
            hoverAnim.setDuration(150);
            hoverAnim.setInterpolator(LazerEasing.OUT_QUINT);
            hoverAnim.addUpdateListener(a -> {
                hoverT = (float) a.getAnimatedValue();
                invalidate();
            });
            hoverAnim.start();
        }

        @Override
        public boolean onHoverEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    animateHover(1f);
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    animateHover(0f);
                    break;
                default:
                    break;
            }
            return super.onHoverEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            canvas.save();
            if (Math.abs(currentScale - 1f) > 0.001f) {
                canvas.scale(currentScale, currentScale, w * 0.5f, h * 0.5f);
            }

            float cr = dp(getContext(), cornerRadiusDp);
            float bw = dp(getContext(), 2.0f);
            // Leave 1dp inset so anti-aliased sheared corners are never clipped by View bounds
            float pad = dp(getContext(), 0.8f);

            rf.set(pad, pad, w - pad, h - pad);
            buildShearedPath(rf, shearAmount, cr, outerPath);

            rf.inset(bw * 0.5f, bw * 0.5f);
            buildShearedPath(rf, shearAmount, Math.max(1f, cr - bw * 0.5f), borderPath);

            int cDark = active ? activeDarker : normalDarker;
            int cLight = active ? activeLighter : normalLighter;
            if (hoverT > 0.001f) {
                cDark = OsuColour.lighten(cDark, 0.20f * hoverT);
                cLight = OsuColour.lighten(cLight, 0.20f * hoverT);
            }
            if (pressT > 0.001f) {
                cDark = OsuColour.lighten(cDark, 0.18f * pressT);
                cLight = OsuColour.lighten(cLight, 0.18f * pressT);
            }

            fillPaint.setColor(cDark);
            canvas.drawPath(outerPath, fillPaint);

            borderPaint.setShader(new LinearGradient(
                    0, 0, 0, h, cDark, cLight, Shader.TileMode.CLAMP));
            canvas.drawPath(borderPath, borderPaint);

            // Auto-fit text size so text NEVER overflows the parallelogram width
            float pxSize = sp(getContext(), textSizeSp);
            textPaint.setTextSize(pxSize);
            float maxTextW = Math.max(dp(getContext(), 16f), w - h * shearAmount - dp(getContext(), 8f));
            float measured = textPaint.measureText(text);
            if (measured > maxTextW && measured > 0) {
                textPaint.setTextSize(Math.max(sp(getContext(), 8.5f), pxSize * (maxTextW / measured)));
            }

            textPaint.setColor(active ? activeText : normalText);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float cy = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(text, w * 0.5f, cy, textPaint);

            if (flashAlpha > 0.01f) {
                flashPaint.setColor(OsuColour.withAlpha(Color.WHITE, flashAlpha));
                canvas.drawPath(outerPath, flashPaint);
            }

            canvas.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    animatePress(1f);
                    // ShearedButton.OnMouseDown: Content.ScaleTo(0.9f, 2000, OutQuint)
                    animateInternalScale(0.90f, 2000, LazerEasing.OUT_QUINT);
                    invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                    pressed = false;
                    animatePress(0f);
                    flashAlpha = 0.90f;
                    ValueAnimator va = ValueAnimator.ofFloat(0.90f, 0f);
                    va.setDuration(800);
                    va.setInterpolator(LazerEasing.OUT_QUINT);
                    va.addUpdateListener(a -> {
                        flashAlpha = (float) a.getAnimatedValue();
                        invalidate();
                    });
                    va.start();
                    // ShearedButton.OnMouseUp: ScaleTo(1, 1000, OutElastic)
                    animateInternalScale(1f, 1000, LazerEasing.OUT_ELASTIC);
                    invalidate();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    animatePress(0f);
                    animateInternalScale(1f, 250, LazerEasing.OUT_QUINT);
                    invalidate();
                    break;
            }
            return super.onTouchEvent(event);
        }
    }

    // =========================================================================
    // 12b. RoundedButtonView (osu.Game RoundedButton.cs)
    //      40dp 高、圆角 5、无描边/剪切；hover Lighten(0.2) 300ms OutQuint；
    //      按压 ScaleTo(0.9, 4000, OutQuint)，释放 ScaleTo(1, 1000, OutElastic)。
    // =========================================================================
    public static final class RoundedButtonView extends View {
        private String text = "";
        private int bgColor = OsuColour.SET_HL1;
        private float hoverT = 0f;
        private float pressScale = 1f;
        private float flashAlpha = 0f;
        private ValueAnimator hoverAnim;
        private ValueAnimator scaleAnim;
        private ValueAnimator flashAnim;

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();

        public RoundedButtonView(Context context) {
            this(context, null);
        }

        public RoundedButtonView(Context context, AttributeSet attrs) {
            super(context, attrs);
            setClickable(true);
            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(Color.WHITE);
            flashPaint.setColor(Color.WHITE);
        }

        public void configure(String label, int color, float spSize) {
            this.text = label != null ? label : "";
            this.bgColor = color;
            textPaint.setTextSize(sp(getContext(), spSize));
            invalidate();
        }

        private void animateHover(float target) {
            if (hoverAnim != null) hoverAnim.cancel();
            hoverAnim = ValueAnimator.ofFloat(hoverT, target);
            hoverAnim.setDuration(300); // RoundedButton: 300ms OutQuint
            hoverAnim.setInterpolator(LazerEasing.OUT_QUINT);
            hoverAnim.addUpdateListener(a -> {
                hoverT = (float) a.getAnimatedValue();
                invalidate();
            });
            hoverAnim.start();
        }

        private void animateScale(float target, long duration, TimeInterpolator interp) {
            if (scaleAnim != null) scaleAnim.cancel();
            scaleAnim = ValueAnimator.ofFloat(pressScale, target);
            scaleAnim.setDuration(duration);
            scaleAnim.setInterpolator(interp);
            scaleAnim.addUpdateListener(a -> {
                pressScale = (float) a.getAnimatedValue();
                invalidate();
            });
            scaleAnim.start();
        }

        private void playFlash() {
            if (flashAnim != null) flashAnim.cancel();
            flashAlpha = 0.5f;
            flashAnim = ValueAnimator.ofFloat(0.5f, 0f);
            flashAnim.setDuration(500);
            flashAnim.setInterpolator(LazerEasing.OUT_QUINT);
            flashAnim.addUpdateListener(a -> {
                flashAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            flashAnim.start();
        }

        @Override
        public boolean onHoverEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    animateHover(1f);
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    animateHover(0f);
                    break;
                default:
                    break;
            }
            return super.onHoverEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    animateScale(0.90f, 4000, LazerEasing.OUT_QUINT);
                    break;
                case MotionEvent.ACTION_UP:
                    playFlash();
                    animateScale(1f, 1000, LazerEasing.OUT_ELASTIC);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    animateScale(1f, 250, LazerEasing.OUT_QUINT);
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            canvas.save();
            if (Math.abs(pressScale - 1f) > 0.001f) {
                canvas.scale(pressScale, pressScale, w * 0.5f, h * 0.5f);
            }

            int c = hoverT > 0.001f
                    ? OsuColour.lighten(bgColor, 0.20f * hoverT)
                    : bgColor;
            float cr = dp(getContext(), 5f);
            rf.set(0, 0, w, h);
            bgPaint.setColor(c);
            canvas.drawRoundRect(rf, cr, cr, bgPaint);

            if (flashAlpha > 0.01f) {
                flashPaint.setColor(OsuColour.withAlpha(Color.WHITE, flashAlpha));
                canvas.drawRoundRect(rf, cr, cr, flashPaint);
            }

            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(text, w * 0.5f, baseline, textPaint);

            canvas.restore();
        }
    }

    // =========================================================================
    // 13. ShearedSliderView (ShearedSliderBar.cs + ShearedNub.cs + Elastic Nub Expansion)
    // =========================================================================
    public static final class ShearedSliderView extends View {
        public interface OnValueChangeListener {
            void onValueChanged(float value, boolean fromUser);
        }

        private float minValue = 0.50f;
        private float maxValue = 2.00f;
        private float step = 0.05f;
        private float value = 1.25f;
        private int accentColor = OsuColour.HIGHLIGHT_1;
        private float nubScale = 1f;
        private ValueAnimator nubAnim;
        private OnValueChangeListener listener;

        private final Paint rightBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint leftBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nubFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nubBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float nubFlashAlpha = 0f;

        private final Path barClipPath = new Path();
        private final Path leftPath = new Path();
        private final Path nubOuterPath = new Path();
        private final Path nubBorderPath = new Path();
        private final RectF rf = new RectF();

        public ShearedSliderView(Context context) {
            this(context, null);
        }

        public ShearedSliderView(Context context, AttributeSet attrs) {
            super(context, attrs);
            rightBoxPaint.setStyle(Paint.Style.FILL);
            rightBoxPaint.setColor(OsuColour.BACKGROUND_5);
            leftBoxPaint.setStyle(Paint.Style.FILL);
            nubFillPaint.setStyle(Paint.Style.FILL);
            nubBorderPaint.setStyle(Paint.Style.STROKE);
            flashPaint.setStyle(Paint.Style.FILL);
        }

        public void configure(float min, float max, float stepSize, float initial, int accent) {
            this.minValue = min;
            this.maxValue = max;
            this.step = stepSize;
            this.accentColor = accent;
            setValue(initial, false);
        }

        public void setOnValueChangeListener(OnValueChangeListener l) {
            this.listener = l;
        }

        public float getValue() {
            return value;
        }

        public void setValue(float v, boolean fromUser) {
            float clamped = Math.max(minValue, Math.min(maxValue, v));
            if (step > 0) {
                clamped = Math.round((clamped - minValue) / step) * step + minValue;
                clamped = Math.max(minValue, Math.min(maxValue, clamped));
            }
            if (Math.abs(this.value - clamped) > 1e-4f || !fromUser) {
                this.value = clamped;
                invalidate();
                if (listener != null) listener.onValueChanged(this.value, fromUser);
            }
        }

        private void animateNub(float target) {
            if (nubAnim != null) nubAnim.cancel();
            nubAnim = ValueAnimator.ofFloat(nubScale, target);
            nubAnim.setDuration(260);
            nubAnim.setInterpolator(LazerEasing.OUT_ELASTIC_HALF);
            nubAnim.addUpdateListener(a -> {
                nubScale = (float) a.getAnimatedValue();
                invalidate();
            });
            nubAnim.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float cr = dp(getContext(), 5f);
            float baseNubW = dp(getContext(), 48f);
            float nubW = baseNubW * nubScale;
            float barH = Math.min(h, dp(getContext(), 28f));
            float topY = (h - barH) * 0.5f;
            float rangePad = baseNubW * 0.5f + dp(getContext(), 2f);

            rf.set(0, topY, w, topY + barH);
            buildShearedPath(rf, 0.2f, cr, barClipPath);
            canvas.drawPath(barClipPath, rightBoxPaint);

            float frac = (maxValue > minValue) ? (value - minValue) / (maxValue - minValue) : 0f;
            frac = Math.max(0f, Math.min(1f, frac));
            float nubCenterX = rangePad + frac * (w - 2f * rangePad);

            leftBoxPaint.setColor(OsuColour.darken(accentColor, 0.10f));
            rf.set(0, topY, Math.max(cr * 2f, nubCenterX), topY + barH);
            buildShearedPath(rf, 0.2f, cr, leftPath);
            canvas.save();
            canvas.clipPath(barClipPath);
            canvas.drawPath(leftPath, leftBoxPaint);
            canvas.restore();

            float bw = dp(getContext(), 7.5f);
            rf.set(nubCenterX - nubW * 0.5f, topY, nubCenterX + nubW * 0.5f, topY + barH);
            buildShearedPath(rf, 0.2f, cr, nubOuterPath);
            nubFillPaint.setColor(accentColor);
            canvas.drawPath(nubOuterPath, nubFillPaint);

            if (nubFlashAlpha > 0.01f) {
                flashPaint.setColor(OsuColour.withAlpha(Color.WHITE, nubFlashAlpha));
                canvas.drawPath(nubOuterPath, flashPaint);
            }

            rf.inset(bw * 0.5f, bw * 0.5f);
            buildShearedPath(rf, 0.2f, Math.max(1f, cr - bw * 0.5f), nubBorderPath);
            nubBorderPaint.setStrokeWidth(bw);
            nubBorderPaint.setColor(Color.WHITE);
            canvas.drawPath(nubBorderPath, nubBorderPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                animateNub(1.12f);
                nubFlashAlpha = 0.80f;
                ValueAnimator va = ValueAnimator.ofFloat(0.80f, 0f);
                va.setDuration(800);
                va.setInterpolator(LazerEasing.OUT_QUINT);
                va.addUpdateListener(a -> {
                    nubFlashAlpha = (float) a.getAnimatedValue();
                    invalidate();
                });
                va.start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                animateNub(1.0f);
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                float rangePad = dp(getContext(), 24f);
                float usable = Math.max(1f, getWidth() - 2f * rangePad);
                float frac = (event.getX() - rangePad) / usable;
                frac = Math.max(0f, Math.min(1f, frac));
                setValue(minValue + frac * (maxValue - minValue), true);
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    // =========================================================================
    // 14. FooterButtonView (ScreenFooterButton.cs + FooterButtonMods.cs)
    // =========================================================================
    public static final class FooterButtonView extends View {
        public static final int ICON_MODS = 0;
        public static final int ICON_FOLDER = 1;
        public static final int ICON_QUEUE = 2;
        public static final int ICON_GEAR = 3;

        private String title = "Mods";
        private String modLeftTag = "";
        private String modRightVal = "";
        private int iconType = ICON_MODS;
        private int accentColor = OsuColour.LIME_1;
        private boolean active = false;
        private boolean pressed = false;
        private float flashAlpha = 0f;
        /** ScreenFooterButton.UpdateDisplay(): FadeColour(..., 150, OutQuint) */
        private float pressT = 0f;
        private ValueAnimator pressAnim;

        private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint pillTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        private final Path bodyPath = new Path();
        private final Path barPath = new Path();
        private final Path modBarOuterPath = new Path();
        private final Path modBarLeftPath = new Path();
        private final RectF rf = new RectF();

        public FooterButtonView(Context context) {
            this(context, null);
        }

        public FooterButtonView(Context context, AttributeSet attrs) {
            super(context, attrs);
            setClickable(true);
            titlePaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            titlePaint.setTextAlign(Paint.Align.CENTER);
            pillTextPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            pillTextPaint.setTextAlign(Paint.Align.CENTER);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            flashPaint.setStyle(Paint.Style.FILL);
        }

        public void configure(String titleText, String badgeText, int accent) {
            this.title = titleText != null ? titleText : "";
            this.accentColor = accent;
            if (accent == OsuColour.LIME_1) this.iconType = ICON_MODS;
            else if (accent == OsuColour.PURPLE_1) this.iconType = ICON_FOLDER;
            else this.iconType = ICON_QUEUE;
            setBadge(badgeText);
        }

        public void setIconType(int type) {
            this.iconType = type;
            invalidate();
        }

        public void setBadge(String badgeText) {
            if (badgeText != null && badgeText.contains("·")) {
                String[] parts = badgeText.split("·");
                this.modRightVal = parts[0].trim();
                this.modLeftTag = parts[1].trim();
            } else {
                this.modLeftTag = badgeText != null ? badgeText : "";
                this.modRightVal = "";
            }
            invalidate();
        }

        public void setActive(boolean a) {
            this.active = a;
            invalidate();
        }

        public boolean isActive() {
            return active;
        }

        private void animatePress(float target) {
            if (pressAnim != null) pressAnim.cancel();
            pressAnim = ValueAnimator.ofFloat(pressT, target);
            pressAnim.setDuration(150);
            pressAnim.setInterpolator(LazerEasing.OUT_QUINT);
            pressAnim.addUpdateListener(a -> {
                pressT = (float) a.getAnimatedValue();
                invalidate();
            });
            pressAnim.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float topBarH = dp(getContext(), 20f);
            float gap = dp(getContext(), 3f);
            float btnTop = topBarH + gap;
            // ScreenFooterButton 基于 ShearedButton: CornerRadius = 7
            float cr = dp(getContext(), 7f);

            // 1. Floating modDisplayBar above the button
            if (!modLeftTag.isEmpty() || !modRightVal.isEmpty()) {
                rf.set(dp(getContext(), 2f), 0, w - dp(getContext(), 2f), topBarH);
                buildShearedPath(rf, 0.2f, dp(getContext(), 7f), modBarOuterPath);

                if (!modRightVal.isEmpty()) {
                    bodyPaint.setColor(OsuColour.BACKGROUND_4);
                    canvas.drawPath(modBarOuterPath, bodyPaint);

                    float splitX = rf.left + rf.width() * 0.65f;
                    RectF leftRf = new RectF(rf.left, rf.top, splitX, rf.bottom);
                    buildShearedPath(leftRf, 0.2f, dp(getContext(), 7f), modBarLeftPath);
                    bodyPaint.setColor(OsuColour.BACKGROUND_3);
                    canvas.drawPath(modBarLeftPath, bodyPaint);

                    pillTextPaint.setTextSize(sp(getContext(), 9.0f));
                    Paint.FontMetrics pfm = pillTextPaint.getFontMetrics();
                    float pcy = rf.centerY() - (pfm.ascent + pfm.descent) * 0.5f;

                    pillTextPaint.setColor(accentColor);
                    canvas.drawText(modLeftTag, (rf.left + splitX) * 0.5f, pcy, pillTextPaint);

                    pillTextPaint.setColor(OsuColour.RED_1);
                    canvas.drawText(modRightVal, (splitX + rf.right) * 0.5f, pcy, pillTextPaint);
                } else {
                    bodyPaint.setColor(OsuColour.BACKGROUND_4);
                    canvas.drawPath(modBarOuterPath, bodyPaint);

                    pillTextPaint.setTextSize(sp(getContext(), 9.5f));
                    pillTextPaint.setColor(accentColor);
                    Paint.FontMetrics pfm = pillTextPaint.getFontMetrics();
                    float pcy = rf.centerY() - (pfm.ascent + pfm.descent) * 0.5f;
                    canvas.drawText(modLeftTag, rf.centerX(), pcy, pillTextPaint);
                }
            }

            // 2. Main ScreenFooterButton body
            rf.set(0, btnTop, w, h + cr);
            buildShearedPath(rf, 0.2f, cr, bodyPath);

            int bgCol = active ? accentColor : OsuColour.BACKGROUND_3;
            if (pressT > 0.001f) bgCol = OsuColour.lighten(bgCol, 0.2f * pressT);
            int fgTextCol = active ? OsuColour.BACKGROUND_6 : OsuColour.CONTENT_1;
            int fgAccentCol = active ? OsuColour.BACKGROUND_6 : accentColor;

            bodyPaint.setColor(bgCol);
            canvas.drawPath(bodyPath, bodyPaint);

            if (!active) {
                glowPaint.setShader(new LinearGradient(
                        0, btnTop, 0, h,
                        0x00000000, OsuColour.withAlpha(accentColor, 0.22f),
                        Shader.TileMode.CLAMP));
                canvas.drawPath(bodyPath, glowPaint);
            }

            float iconCy = btnTop + dp(getContext(), 14f);
            float iconCx = w * 0.5f + dp(getContext(), 2f);
            iconPaint.setStrokeWidth(dp(getContext(), 1.8f));
            iconPaint.setColor(fgAccentCol);
            drawFooterIcon(canvas, iconCx, iconCy, dp(getContext(), 5.5f));

            titlePaint.setTextSize(sp(getContext(), 12f));
            titlePaint.setColor(fgTextCol);
            canvas.drawText(title, w * 0.5f - dp(getContext(), 1f), btnTop + dp(getContext(), 34f), titlePaint);

            // Counter-sheared horizontal rounded pill accent bar
            float barH = dp(getContext(), 4.0f);
            float barBottom = h - dp(getContext(), 3.5f);
            float barW = Math.max(dp(getContext(), 24f), w * 0.62f);
            float barLeft = (w - barW) * 0.5f;
            rf.set(barLeft, barBottom - barH, barLeft + barW, barBottom);
            barPaint.setColor(fgAccentCol);
            canvas.drawRoundRect(rf, dp(getContext(), 2f), dp(getContext(), 2f), barPaint);

            if (flashAlpha > 0.01f) {
                flashPaint.setColor(OsuColour.withAlpha(Color.WHITE, flashAlpha));
                canvas.drawPath(bodyPath, flashPaint);
            }
        }

        private void drawFooterIcon(Canvas canvas, float cx, float cy, float r) {
            if (iconType == ICON_MODS) {
                canvas.drawLine(cx - r, cy - r * 0.45f, cx + r, cy - r * 0.45f, iconPaint);
                canvas.drawLine(cx + r * 0.4f, cy - r, cx + r, cy - r * 0.45f, iconPaint);
                canvas.drawLine(cx - r, cy + r * 0.45f, cx + r, cy + r * 0.45f, iconPaint);
                canvas.drawLine(cx - r * 0.4f, cy + r, cx - r, cy + r * 0.45f, iconPaint);
            } else if (iconType == ICON_FOLDER) {
                RectF fr = new RectF(cx - r, cy - r * 0.65f, cx + r, cy + r * 0.75f);
                canvas.drawRoundRect(fr, dp(getContext(), 1.5f), dp(getContext(), 1.5f), iconPaint);
                canvas.drawLine(cx - r, cy - r * 0.2f, cx + r, cy - r * 0.2f, iconPaint);
            } else if (iconType == ICON_GEAR) {
                // FontAwesome Cog (简化): 中心圆 + 8 个齿
                float ring = r * 0.52f;
                canvas.drawCircle(cx, cy, ring, iconPaint);
                float toothInner = ring;
                float toothOuter = r;
                for (int i = 0; i < 8; i++) {
                    double ang = (Math.PI / 4.0) * i;
                    float x1 = cx + (float) Math.cos(ang) * toothInner;
                    float y1 = cy + (float) Math.sin(ang) * toothInner;
                    float x2 = cx + (float) Math.cos(ang) * toothOuter;
                    float y2 = cy + (float) Math.sin(ang) * toothOuter;
                    canvas.drawLine(x1, y1, x2, y2, iconPaint);
                }
            } else {
                canvas.drawLine(cx - r, cy - r * 0.6f, cx + r, cy - r * 0.6f, iconPaint);
                canvas.drawLine(cx - r, cy, cx + r, cy, iconPaint);
                canvas.drawLine(cx - r, cy + r * 0.6f, cx + r, cy + r * 0.6f, iconPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    animatePress(1f);
                    invalidate();
                    break;
                case MotionEvent.ACTION_UP:
                    pressed = false;
                    animatePress(0f);
                    flashAlpha = 0.90f;
                    ValueAnimator va = ValueAnimator.ofFloat(0.90f, 0f);
                    va.setDuration(800);
                    va.setInterpolator(LazerEasing.OUT_QUINT);
                    va.addUpdateListener(a -> {
                        flashAlpha = (float) a.getAnimatedValue();
                        invalidate();
                    });
                    va.start();
                    invalidate();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    animatePress(0f);
                    invalidate();
                    break;
            }
            return super.onTouchEvent(event);
        }
    }

    // =========================================================================
    // 15. OsuCookieButtonView (OsuLogo.cs — BPM-Synced Beat Ripple & Triangles)
    // =========================================================================
    public static final class OsuCookieButtonView extends View {
        private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint triPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clickFlashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipCircle = new Path();
        private final Path triPath = new Path();

        private String mainLabel = "osu!";
        private String subLabel = "开始变速";
        private double bpm = 160.0;
        private long clickFlashStartMs = 0L;

        public OsuCookieButtonView(Context context) {
            this(context, null);
        }

        public OsuCookieButtonView(Context context, AttributeSet attrs) {
            super(context, attrs);
            setClickable(true);
            ripplePaint.setStyle(Paint.Style.STROKE);
            ripplePaint.setStrokeWidth(dp(context, 2.2f));
            outerPaint.setStyle(Paint.Style.FILL);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(context, 3.4f));
            ringPaint.setColor(0xFFFFFFFF);
            triPaint.setStyle(Paint.Style.FILL);
            clickFlashPaint.setStyle(Paint.Style.FILL);

            titlePaint.setTypeface(Typeface.create("sans-serif-black", Typeface.ITALIC));
            titlePaint.setTextAlign(Paint.Align.CENTER);
            titlePaint.setColor(Color.WHITE);

            subPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            subPaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setColor(0xFFFFE4F2);
        }

        public void setLabels(String main, String sub) {
            this.mainLabel = main != null ? main : "osu!";
            this.subLabel = sub != null ? sub : "";
            invalidate();
        }

        public void setBpm(double beatmapBpm) {
            this.bpm = beatmapBpm > 30 ? beatmapBpm : 160.0;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            long now = SystemClock.uptimeMillis();
            double beatPeriodMs = Math.max(260.0, 60000.0 / bpm);
            double beatTime = now % (long) beatPeriodMs;

            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float baseR = Math.min(w, h) * 0.42f;

            // OsuLogo.cs: 60ms contraction (-2%), then slow OutQuint recovery over beat period
            float scaleRatio;
            if (beatTime < 60.0) {
                float t = (float) (beatTime / 60.0);
                scaleRatio = 1.0f - 0.02f * t;
            } else {
                float t = (float) ((beatTime - 60.0) / (beatPeriodMs - 60.0));
                scaleRatio = 0.98f + 0.02f * LazerEasing.outQuint(t);
            }
            float r = baseR * scaleRatio;

            // OsuLogo.cs Ripple: +4% expansion, 0.15 alpha, 1 beat OutQuint fade
            float beatPhase = (float) (beatTime / beatPeriodMs);
            float rippleR = baseR * (1.0f + 0.04f * beatPhase);
            float rippleAlpha = 0.15f * (1f - LazerEasing.outQuint(beatPhase));
            ripplePaint.setColor(OsuColour.withAlpha(OsuColour.OSU_PINK, rippleAlpha));
            canvas.drawCircle(cx, cy, rippleR, ripplePaint);

            // 90° pure vertical gradient
            outerPaint.setShader(new LinearGradient(
                    cx, cy - r, cx, cy + r,
                    OsuColour.OSU_PINK, 0xFFCC5289, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, outerPaint);

            canvas.save();
            clipCircle.reset();
            clipCircle.addCircle(cx, cy, r - dp(getContext(), 2f), Path.Direction.CW);
            canvas.clipPath(clipCircle);

            float slowPhase = (now % 2400L) / 2400f;
            for (int i = 0; i < 6; i++) {
                float p = (slowPhase + i * 0.17f) % 1f;
                float ty = (cy + r) - p * (2f * r + dp(getContext(), 16f));
                float tx = cx + (i - 2.5f) * dp(getContext(), 10f);
                float sz = dp(getContext(), 13f + (i % 3) * 5f);
                triPaint.setColor(OsuColour.withAlpha(Color.WHITE, 0.16f));
                triPath.reset();
                triPath.moveTo(tx, ty);
                triPath.lineTo(tx - sz * 0.5f, ty + sz * 0.866f);
                triPath.lineTo(tx + sz * 0.5f, ty + sz * 0.866f);
                triPath.close();
                canvas.drawPath(triPath, triPaint);
            }
            canvas.restore();

            canvas.drawCircle(cx, cy, r - dp(getContext(), 1.8f), ringPaint);

            titlePaint.setTextSize(sp(getContext(), 15.5f));
            canvas.drawText(mainLabel, cx, cy - dp(getContext(), 1f), titlePaint);

            subPaint.setTextSize(sp(getContext(), 9.2f));
            canvas.drawText(subLabel, cx, cy + dp(getContext(), 11.5f), subPaint);

            // Click flash: 1500ms OutExpo flash
            long flashElapsed = now - clickFlashStartMs;
            if (flashElapsed >= 0 && flashElapsed < 1500) {
                float t = flashElapsed / 1500f;
                float flashAlpha = 0.85f * (1f - LazerEasing.outExpo(t));
                clickFlashPaint.setColor(OsuColour.withAlpha(Color.WHITE, flashAlpha));
                canvas.drawCircle(cx, cy, r, clickFlashPaint);
            }

            postInvalidateOnAnimation();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // OsuLogo.OnMouseDown: ScaleTo(0.9f, 1000, Easing.Out)
                    animate().scaleX(0.90f).scaleY(0.90f).setDuration(1000).setInterpolator(LazerEasing.OUT_QUAD).start();
                    break;
                case MotionEvent.ACTION_UP:
                    clickFlashStartMs = SystemClock.uptimeMillis();
                    // OsuLogo.OnMouseUp: ScaleTo(1, 500, OutElastic)
                    animate().scaleX(1.0f).scaleY(1.0f).setDuration(500).setInterpolator(LazerEasing.OUT_ELASTIC).start();
                    invalidate();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).setInterpolator(LazerEasing.OUT_QUINT).start();
                    break;
            }
            return super.onTouchEvent(event);
        }
    }

    // =========================================================================
    // 19. LazerSettingsSliderView (1:1 SettingsSlider.cs + RoundedSliderBar.cs)
    // =========================================================================
    public static final class LazerSettingsSliderView extends View {
        public interface OnValueChangeListener {
            void onValueChanged(float value, boolean fromUser);
        }

        private String label = "变速倍率";
        private String valueFormat = "%.2fx";
        private float minValue = 0.50f;
        private float maxValue = 2.00f;
        private float step = 0.05f;
        private float defaultValue = 1.00f;
        private float value = 1.25f;
        private int accentColor = OsuColour.LIME_1;
        private int trackBgColor = 0xFF14191D;
        private int trackBorderColor = 0xFF25333B;

        // Official Nub specifications (Nub.cs: HEIGHT = 15, DEFAULT_EXPANDED_SIZE = 50)
        private float nubWidthScale = 1.0f;
        private ValueAnimator nubWidthAnim;
        /** RoundedSliderBar.UpdateValue(): Nub.MoveToX(value, 250, OutQuint) */
        private float displayedValue = 0f;
        private ValueAnimator valueMoveAnim;
        private float glowAlpha = 0f;
        private ValueAnimator glowAnim;
        private float flashAlpha = 0f;
        private ValueAnimator flashAnim;

        // Floating Tooltip Bubble (OsuSliderBar.cs: TooltipText)
        private float tooltipAlpha = 0f;
        private ValueAnimator tooltipAnim;
        private final Runnable hideTooltipRunnable = () -> animateTooltip(0f);

        // Official SettingsRevertToDefaultButton animation
        private float revertAlpha = 0f;
        private ValueAnimator revertAnim;

        private long lastDownTime = 0;
        private boolean isDragging = false;
        private OnValueChangeListener listener;

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint nubFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint revertBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint revertIconPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        // Tooltip paints & path
        private final Paint tooltipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tooltipBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint tooltipTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path tooltipArrowPath = new Path();

        private final RectF rfTrack = new RectF();
        private final RectF rfFill = new RectF();
        private final RectF rfNub = new RectF();
        private final RectF rfGlow = new RectF();
        private final RectF rfRevert = new RectF();
        private final RectF rfTooltip = new RectF();

        public LazerSettingsSliderView(Context context) {
            this(context, null);
        }

        public LazerSettingsSliderView(Context context, AttributeSet attrs) {
            super(context, attrs);
            trackPaint.setStyle(Paint.Style.FILL);
            trackFillPaint.setStyle(Paint.Style.FILL);
            trackBorderPaint.setStyle(Paint.Style.STROKE);
            trackBorderPaint.setStrokeWidth(dp(context, 1f));
            nubFillPaint.setStyle(Paint.Style.FILL);
            glowPaint.setStyle(Paint.Style.FILL);
            flashPaint.setStyle(Paint.Style.FILL);
            revertBgPaint.setStyle(Paint.Style.FILL);

            labelPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            labelPaint.setColor(0xFFCFD6DC);

            revertIconPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));

            tooltipBgPaint.setStyle(Paint.Style.FILL);
            tooltipBorderPaint.setStyle(Paint.Style.STROKE);
            tooltipBorderPaint.setStrokeWidth(dp(context, 1f));
            tooltipTextPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            tooltipTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        public void configure(float min, float max, float stepSize, float initial, float defVal, int accent) {
            this.minValue = min;
            this.maxValue = max;
            this.step = stepSize;
            this.defaultValue = defVal;
            this.accentColor = accent;
            this.displayedValue = Math.max(min, Math.min(max, initial));
            setValue(initial, false);
            updateRevertIndicator(false);
        }

        public void setLabel(String text) {
            this.label = (text != null) ? text : "";
            invalidate();
        }

        public void setValueFormat(String format) {
            this.valueFormat = (format != null) ? format : "%.2fx";
            invalidate();
        }

        public void setAccentColor(int color) {
            this.accentColor = color;
            invalidate();
        }

        public void setOnValueChangeListener(OnValueChangeListener l) {
            this.listener = l;
        }

        public float getValue() {
            return value;
        }

        public void setValue(float v, boolean fromUser) {
            float clamped = Math.max(minValue, Math.min(maxValue, v));
            if (step > 0) {
                clamped = Math.round((clamped - minValue) / step) * step + minValue;
                clamped = Math.max(minValue, Math.min(maxValue, clamped));
            }
            if (Math.abs(this.value - clamped) > 1e-4f || !fromUser) {
                this.value = clamped;
                animateNubTo(clamped);
                updateRevertIndicator(true);
                invalidate();
                if (listener != null) listener.onValueChanged(this.value, fromUser);
            }
        }

        /** RoundedSliderBar.UpdateValue(): Nub.MoveToX(value, 250, OutQuint) */
        private void animateNubTo(float target) {
            if (valueMoveAnim != null) valueMoveAnim.cancel();
            valueMoveAnim = ValueAnimator.ofFloat(displayedValue, target);
            valueMoveAnim.setDuration(250);
            valueMoveAnim.setInterpolator(LazerEasing.OUT_QUINT);
            valueMoveAnim.addUpdateListener(a -> {
                displayedValue = (float) a.getAnimatedValue();
                invalidate();
            });
            valueMoveAnim.start();
        }

        private void updateRevertIndicator(boolean animated) {
            boolean isDefault = Math.abs(this.value - defaultValue) <= 1e-4f;
            float targetAlpha = isDefault ? 0f : 1f;
            if (Math.abs(revertAlpha - targetAlpha) < 0.01f) return;
            if (!animated) {
                revertAlpha = targetAlpha;
                invalidate();
                return;
            }
            if (revertAnim != null) revertAnim.cancel();
            revertAnim = ValueAnimator.ofFloat(revertAlpha, targetAlpha);
            revertAnim.setDuration(220);
            revertAnim.setInterpolator(LazerEasing.OUT_QUINT);
            revertAnim.addUpdateListener(a -> {
                revertAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            revertAnim.start();
        }

        private void animateNubExpand(float targetScale) {
            if (nubWidthAnim != null) nubWidthAnim.cancel();
            nubWidthAnim = ValueAnimator.ofFloat(nubWidthScale, targetScale);
            nubWidthAnim.setDuration(220);
            nubWidthAnim.setInterpolator(targetScale > 1f ? LazerEasing.OUT_ELASTIC_HALF : LazerEasing.OUT_ELASTIC);
            nubWidthAnim.addUpdateListener(a -> {
                nubWidthScale = (float) a.getAnimatedValue();
                invalidate();
            });
            nubWidthAnim.start();
        }

        private void animateGlow(float target) {
            if (glowAnim != null) glowAnim.cancel();
            glowAnim = ValueAnimator.ofFloat(glowAlpha, target);
            glowAnim.setDuration(250);
            glowAnim.setInterpolator(LazerEasing.OUT_QUINT);
            glowAnim.addUpdateListener(a -> {
                glowAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            glowAnim.start();
        }

        private void animateTooltip(float target) {
            removeCallbacks(hideTooltipRunnable);
            if (tooltipAnim != null) tooltipAnim.cancel();
            tooltipAnim = ValueAnimator.ofFloat(tooltipAlpha, target);
            tooltipAnim.setDuration(160);
            tooltipAnim.setInterpolator(LazerEasing.OUT_QUINT);
            tooltipAnim.addUpdateListener(a -> {
                tooltipAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            tooltipAnim.start();
        }

        private void triggerFlash() {
            if (flashAnim != null) flashAnim.cancel();
            flashAlpha = 0.85f;
            flashAnim = ValueAnimator.ofFloat(0.85f, 0f);
            flashAnim.setDuration(600);
            flashAnim.setInterpolator(LazerEasing.OUT_QUINT);
            flashAnim.addUpdateListener(a -> {
                flashAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            flashAnim.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int desiredH = Math.round(dp(getContext(), 54f));
            int desiredW = Math.round(dp(getContext(), 200f));
            int w = resolveSize(desiredW, widthMeasureSpec);
            int h = resolveSize(desiredH, heightMeasureSpec);
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float sidePad = dp(getContext(), 8f);

            // 1. Top row: RevertToDefaultButton + Label (left) + Value (right)
            float revertW = dp(getContext(), 18f);
            float revertH = dp(getContext(), 18f);
            float headerCenterY = dp(getContext(), 11f);

            if (revertAlpha > 0.01f) {
                float revTop = headerCenterY - revertH * 0.5f;
                rfRevert.set(sidePad, revTop, sidePad + revertW, revTop + revertH);
                revertBgPaint.setColor(OsuColour.withAlpha(0xFF24343B, revertAlpha));
                canvas.drawRoundRect(rfRevert, dp(getContext(), 4f), dp(getContext(), 4f), revertBgPaint);

                revertIconPaint.setColor(OsuColour.withAlpha(Color.WHITE, revertAlpha));
                revertIconPaint.setTextSize(sp(getContext(), 12f));
                revertIconPaint.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm = revertIconPaint.getFontMetrics();
                float cy = rfRevert.centerY() - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText("↺", rfRevert.centerX(), cy, revertIconPaint);
            }

            labelPaint.setTextSize(sp(getContext(), 11.5f));
            Paint.FontMetrics lfm = labelPaint.getFontMetrics();
            float labelY = headerCenterY - (lfm.ascent + lfm.descent) * 0.5f;
            float labelX = sidePad + (revertAlpha * (revertW + dp(getContext(), 5f)));
            canvas.drawText(label, labelX, labelY, labelPaint);

            String valFormatted = String.format(Locale.ROOT, valueFormat, value);
            labelPaint.setColor(accentColor);
            labelPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(valFormatted, w - sidePad, labelY, labelPaint);
            labelPaint.setColor(0xFFCFD6DC);
            labelPaint.setTextAlign(Paint.Align.LEFT);

            // 2. Track & Nub Geometry (exact RoundedSliderBar.cs + Nub.cs)
            float trackH = dp(getContext(), 5f);
            float trackCenterY = h - dp(getContext(), 12f);
            float trackR = trackH * 0.5f;

            float baseNubW = dp(getContext(), 42f);
            float expNubW = dp(getContext(), 50f);
            float curNubW = baseNubW + (expNubW - baseNubW) * (nubWidthScale - 1.0f);
            float curNubH = dp(getContext(), 15f);
            float curNubR = curNubH * 0.5f;

            float rangePadding = sidePad + baseNubW * 0.5f;
            float usableW = Math.max(1f, w - 2f * rangePadding);
            float fracD = (maxValue > minValue) ? (displayedValue - minValue) / (maxValue - minValue) : 0f;
            float frac = Math.max(0f, Math.min(1f, fracD));
            float nubCenterX = rangePadding + frac * usableW;

            float nubLeft = nubCenterX - curNubW * 0.5f;
            float nubRight = nubCenterX + curNubW * 0.5f;

            // Left track: Accent colour, ends seamlessly at nubLeft (RoundedSliderBar.cs LeftBox)
            if (nubLeft > sidePad) {
                rfFill.set(sidePad, trackCenterY - trackR, nubLeft + dp(getContext(), 1f), trackCenterY + trackR);
                trackFillPaint.setColor(accentColor);
                canvas.drawRoundRect(rfFill, trackR, trackR, trackFillPaint);
            }

            // Right track: Background colour, starts from nubRight to end (RoundedSliderBar.cs RightBox)
            if (nubRight < w - sidePad) {
                rfTrack.set(nubRight - dp(getContext(), 1f), trackCenterY - trackR, w - sidePad, trackCenterY + trackR);
                trackPaint.setColor(trackBgColor);
                canvas.drawRoundRect(rfTrack, trackR, trackR, trackPaint);
                trackBorderPaint.setColor(trackBorderColor);
                canvas.drawRoundRect(rfTrack, trackR, trackR, trackBorderPaint);
            }

            // Nub Outer Glow (when dragging / active)
            if (glowAlpha > 0.01f) {
                rfGlow.set(nubCenterX - curNubW * 0.5f - dp(getContext(), 6f),
                           trackCenterY - curNubH * 0.5f - dp(getContext(), 4f),
                           nubCenterX + curNubW * 0.5f + dp(getContext(), 6f),
                           trackCenterY + curNubH * 0.5f + dp(getContext(), 4f));
                glowPaint.setColor(OsuColour.withAlpha(accentColor, (int) (glowAlpha * 75)));
                canvas.drawRoundRect(rfGlow, curNubR + dp(getContext(), 4f), curNubR + dp(getContext(), 4f), glowPaint);
            }

            // Nub: 1:1 Solid Vibrant Pill (Nub.cs: CircularContainer filled with AccentColour, NO white border ring!)
            rfNub.set(nubLeft, trackCenterY - curNubH * 0.5f,
                      nubRight, trackCenterY + curNubH * 0.5f);

            nubFillPaint.setColor(accentColor);
            canvas.drawRoundRect(rfNub, curNubR, curNubR, nubFillPaint);

            // Subtle flash highlight on click/snap
            if (flashAlpha > 0.01f) {
                flashPaint.setColor(OsuColour.withAlpha(Color.WHITE, flashAlpha));
                canvas.drawRoundRect(rfNub, curNubR, curNubR, flashPaint);
            }

            // 3. Floating Tooltip Bubble (OsuSliderBar.cs TooltipText): pops up directly above the Nub!
            if (tooltipAlpha > 0.01f) {
                tooltipTextPaint.setTextSize(sp(getContext(), 11f));
                float textW = tooltipTextPaint.measureText(valFormatted);
                float ttW = textW + dp(getContext(), 14f);
                float ttH = dp(getContext(), 18f);

                float ttCenterY = trackCenterY - curNubH * 0.5f - dp(getContext(), 6f) - ttH * 0.5f;
                float ttCenterX = Math.max(sidePad + ttW * 0.5f, Math.min(w - sidePad - ttW * 0.5f, nubCenterX));

                rfTooltip.set(ttCenterX - ttW * 0.5f, ttCenterY - ttH * 0.5f,
                              ttCenterX + ttW * 0.5f, ttCenterY + ttH * 0.5f);

                // Tooltip background & border
                tooltipBgPaint.setColor(OsuColour.withAlpha(0xFF141A1E, tooltipAlpha));
                canvas.drawRoundRect(rfTooltip, dp(getContext(), 4f), dp(getContext(), 4f), tooltipBgPaint);

                tooltipBorderPaint.setColor(OsuColour.withAlpha(0xFF2C3E47, tooltipAlpha));
                canvas.drawRoundRect(rfTooltip, dp(getContext(), 4f), dp(getContext(), 4f), tooltipBorderPaint);

                // Pointer triangle
                float arrowY = ttCenterY + ttH * 0.5f;
                tooltipArrowPath.reset();
                tooltipArrowPath.moveTo(nubCenterX - dp(getContext(), 3.5f), arrowY);
                tooltipArrowPath.lineTo(nubCenterX + dp(getContext(), 3.5f), arrowY);
                tooltipArrowPath.lineTo(nubCenterX, arrowY + dp(getContext(), 3f));
                tooltipArrowPath.close();
                canvas.drawPath(tooltipArrowPath, tooltipBgPaint);
                canvas.drawPath(tooltipArrowPath, tooltipBorderPaint);

                // Text
                tooltipTextPaint.setColor(OsuColour.withAlpha(Color.WHITE, tooltipAlpha));
                Paint.FontMetrics tfm = tooltipTextPaint.getFontMetrics();
                float tty = ttCenterY - (tfm.ascent + tfm.descent) * 0.5f;
                canvas.drawText(valFormatted, ttCenterX, tty, tooltipTextPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            float x = event.getX();
            float y = event.getY();

            // Revert button click check
            if (revertAlpha > 0.5f && action == MotionEvent.ACTION_UP && y < dp(getContext(), 24f) && x < dp(getContext(), 34f)) {
                setValue(defaultValue, true);
                triggerFlash();
                animateNubExpand(1.20f);
                animateTooltip(1.0f);
                postDelayed(() -> {
                    animateNubExpand(1.0f);
                    animateTooltip(0f);
                }, 400);
                return true;
            }

            if (action == MotionEvent.ACTION_DOWN) {
                long now = SystemClock.uptimeMillis();
                // Double tap on slider/nub to reset default (SliderNub.OnDoubleClick)
                if (now - lastDownTime < 320) {
                    setValue(defaultValue, true);
                    triggerFlash();
                    animateNubExpand(1.25f);
                    animateTooltip(1.0f);
                    postDelayed(() -> {
                        animateNubExpand(1.0f);
                        animateTooltip(0f);
                    }, 400);
                    lastDownTime = 0;
                    return true;
                }
                lastDownTime = now;
                isDragging = true;
                animateNubExpand(1.20f);
                animateGlow(1.0f);
                animateTooltip(1.0f);
                triggerFlash();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                isDragging = false;
                animateNubExpand(1.0f);
                animateGlow(0f);
                postDelayed(hideTooltipRunnable, 300);
            }

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                getParent().requestDisallowInterceptTouchEvent(true);
                animateTooltip(1.0f);
                float sidePad = dp(getContext(), 8f);
                float baseNubW = dp(getContext(), 42f);
                float rangePadding = sidePad + baseNubW * 0.5f;
                float usableW = Math.max(1f, getWidth() - 2f * rangePadding);
                float frac = (x - rangePadding) / usableW;
                frac = Math.max(0f, Math.min(1f, frac));
                setValue(minValue + frac * (maxValue - minValue), true);
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    // =========================================================================
    // 21. osu!lazer Context Menu System (全部自绘，不使用 ScrollView / LinearLayout / TextView)
    //     1:1 来源: osu-framework Menu.cs / osu.Game OsuMenu.cs / OsuContextMenu.cs /
    //     DrawableOsuMenuItem.cs / OsuScrollContainer(OsuScrollbar) / CursorEffectContainer
    // =========================================================================

    public enum MenuItemType {
        Standard,
        Highlighted,
        Destructive
    }

    public static class OsuMenuItem {
        public final String text;
        public final MenuItemType type;
        public final Runnable action;

        public OsuMenuItem(String text) {
            this(text, MenuItemType.Standard, null);
        }

        public OsuMenuItem(String text, MenuItemType type, Runnable action) {
            this.text = text != null ? text : "";
            this.type = type != null ? type : MenuItemType.Standard;
            this.action = action;
        }
    }

    public static final class OsuMenuItemSpacer extends OsuMenuItem {
        public OsuMenuItemSpacer() {
            super(" ", MenuItemType.Standard, null);
        }
    }

    // =========================================================================
    // 21a. DrawableOsuMenuItem (osu.Game/Graphics/UserInterface/DrawableOsuMenuItem.cs)
    //      MARGIN_HORIZONTAL = 10, MARGIN_VERTICAL = 4, TEXT_SIZE = 17,
    //      TRANSITION_LENGTH = 80；hover 背景 #172023；normal/bold 双层文字交叉淡化。
    // =========================================================================
    public static final class DrawableOsuMenuItem extends View {
        public static final int MARGIN_HORIZONTAL = 10;
        public static final int MARGIN_VERTICAL = 4;
        public static final float TEXT_SIZE_SP = 17f;
        public static final int TRANSITION_LENGTH = 80;
        private static final int HOVER_BACKGROUND = 0xFF172023;

        private final OsuMenuItem item;
        private final boolean spacer;
        private final int textColor;

        private boolean highlighted = false;
        private float boldAlpha = 0f;
        private ValueAnimator boldAnim;

        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint normalPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint boldPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics fm = new Paint.FontMetrics();

        private final int contentWidth;
        private final int itemHeight;

        public DrawableOsuMenuItem(Context context, OsuMenuItem item) {
            super(context);
            this.item = item;
            this.spacer = item instanceof OsuMenuItemSpacer;

            switch (item.type) {
                case Destructive:
                    textColor = 0xFFFF6666; // colours.Red1
                    break;
                case Highlighted:
                    textColor = 0xFFFFCC22; // FromHex("ffcc22")
                    break;
                default:
                    textColor = Color.WHITE;
                    break;
            }

            // osu 使用 Torus（几何无衬线）；Android 侧 sans-serif(Roboto) 比 condensed 明显更接近。
            normalPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            boldPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            normalPaint.setTextSize(sp(context, TEXT_SIZE_SP));
            boldPaint.setTextSize(sp(context, TEXT_SIZE_SP));
            normalPaint.getFontMetrics(fm);

            int textHeight = (int) Math.ceil(fm.descent - fm.ascent);
            contentWidth = (int) Math.ceil(Math.max(normalPaint.measureText(item.text), boldPaint.measureText(item.text)));
            itemHeight = textHeight + 2 * (int) dp(context, MARGIN_VERTICAL);
            linePaint.setColor(HOVER_BACKGROUND);

            // DrawableOsuMenuItem.updateState(): Alpha = IsActionable ? 1 : 0.2
            if (!spacer && item.action == null) setAlpha(0.2f);
        }

        public OsuMenuItem getItem() {
            return item;
        }

        public boolean isSpacer() {
            return spacer;
        }

        /** 文本宽 + 左右 padding（对应 DrawableOsuMenuItem.TextContainer 的完整占用宽度）。 */
        public int getContentWidth() {
            if (spacer) return (int) dp(getContext(), 20f);
            // 菜单里全是普通 OsuMenuItem（无 StatefulMenuItem），ShowCheckbox=false，
            // 不保留 CheckboxContainer(10) + Spacing(10) 的隐藏占位：
            // TextContainer.Padding.Horizontal 10 + 文本 + Padding.Horizontal 10
            return contentWidth + (int) dp(getContext(), 20f);
        }

        public void setHighlighted(boolean value) {
            if (spacer || highlighted == value) return;
            highlighted = value;
            if (boldAnim != null) boldAnim.cancel();
            boldAnim = ValueAnimator.ofFloat(boldAlpha, value ? 1f : 0f);
            boldAnim.setDuration(TRANSITION_LENGTH);
            boldAnim.setInterpolator(LazerEasing.OUT_QUINT);
            boldAnim.addUpdateListener(a -> {
                boldAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            boldAnim.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            // DrawableSpacer.Scale = (1, 0.6f) 只是视觉缩放，不改变布局高度（FillFlow 仍按整行高度排布）
            setMeasuredDimension(w, itemHeight);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            if (spacer) {
                // DrawableSpacer: Box Colour = BackgroundColourHover, Height = 2, Width = 0.9, Centred
                float lh = Math.max(1f, dp(getContext(), 2f));
                float inset = w * 0.05f;
                float y = (h - lh) * 0.5f;
                canvas.drawRect(inset, y, w - inset, y + lh, linePaint);
                return;
            }

            // Background.FadeColour(BackgroundColourHover) 为瞬时
            if (highlighted) {
                bgPaint.setColor(HOVER_BACKGROUND);
                canvas.drawRect(0, 0, w, h, bgPaint);
            }

            // TextContainer.Padding.Left = 10
            float x = dp(getContext(), 10f);
            float baseline = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;

            normalPaint.setColor(textColor);
            normalPaint.setAlpha(Math.round(255 * (1f - boldAlpha)));
            canvas.drawText(item.text, x, baseline, normalPaint);

            if (boldAlpha > 0.01f) {
                boldPaint.setColor(textColor);
                boldPaint.setAlpha(Math.round(255 * boldAlpha));
                canvas.drawText(item.text, x, baseline, boldPaint);
            }
        }
    }

    // =========================================================================
    // 21b. OsuContextMenu 已独立为 OsuContextMenuView.java
    //      （Menu.cs + ScrollContainer.cs + OsuScrollContainer.cs 全量移植）
    // =========================================================================
    // =========================================================================
    // 21c. OsuContextMenuContainer (osu.Game OsuContextMenuContainer.cs +
    //      framework ContextMenuContainer.cs) —— 定位/越界夹取算法 1:1；
    //      长按反馈使用 CircularLongPressFeedbackView（1:1 TouchLongPressFeedback）。
    // =========================================================================
    public static class OsuContextMenuContainer extends FrameLayout {
        private final OsuContextMenuView menu;
        private final CircularLongPressFeedbackView longPressFeedback;
        private boolean menuOpen = false;
        private boolean menuTracking = false;
        private int shownWidth = 0;
        private int shownHeight = 0;

        public OsuContextMenuContainer(Context context) {
            this(context, null);
        }

        public OsuContextMenuContainer(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public OsuContextMenuContainer(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
            setClipChildren(false);
            setClipToPadding(false);

            // 长按反馈在后（下层），菜单在前（上层）—— Corresponds to CursorContainer.Depth = float.MinValue
            longPressFeedback = new CircularLongPressFeedbackView(context);
            addView(longPressFeedback, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            menu = new OsuContextMenuView(context);
            menu.setVisibility(View.GONE);
            addView(menu, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

            setVisibility(View.GONE);
        }

        public boolean isMenuOpen() {
            return menuOpen;
        }

        /** 开始长按（1:1 touch_right_click_delay = 750ms）；完成后回调 onComplete（通常用于弹菜单）。 */
        public void startTouchHold(float rawX, float rawY, Runnable onComplete) {
            if (menuOpen) return;
            setVisibility(View.VISIBLE);
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            longPressFeedback.start(rawX - loc[0], rawY - loc[1], 750L, onComplete);
        }

        public void cancelTouchHold() {
            longPressFeedback.cancel();
            hideIfIdle();
        }

        private void hideIfIdle() {
            if (menuOpen) return;
            postDelayed(() -> {
                if (!menuOpen && (longPressFeedback == null || !longPressFeedback.isActive())) {
                    setVisibility(View.GONE);
                }
            }, 440);
        }

        public void showMenu(float rawX, float rawY, List<OsuMenuItem> items) {
            if (items == null || items.isEmpty()) {
                closeMenu();
                return;
            }
            if (menuOpen && menu.getVisibility() == View.VISIBLE) {
                return; // 防止重复弹出的闪烁
            }

            longPressFeedback.cancel();
            menu.setItems(items, this::closeMenu);
            setVisibility(View.VISIBLE);
            menuOpen = true;

            int fitWidth = menu.computeFitWidth();
            LayoutParams lp = (LayoutParams) menu.getLayoutParams();
            lp.width = fitWidth;
            menu.setLayoutParams(lp);
            menu.measure(View.MeasureSpec.makeMeasureSpec(fitWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(menu.getMaxHeightPx(), View.MeasureSpec.AT_MOST));
            int menuW = fitWidth;
            int menuH = menu.getMeasuredHeight();
            shownWidth = menuW;
            shownHeight = menuH;

            int[] loc = new int[2];
            getLocationOnScreen(loc);
            float posX = rawX - loc[0];
            float posY = rawY - loc[1];

            float cw = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
            float ch = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;

            // 1:1 framework ContextMenuContainer.UpdateAfterChildren 的越界夹取
            float overflowX = posX + menuW - cw;
            float overflowY = posY + menuH - ch;
            if (overflowX > 0) posX -= Math.min(overflowX, menuW);
            if (overflowY > 0) posY -= Math.min(overflowY, menuH);
            if (posX < 0) posX += Math.min(-posX, menuW);
            if (posY < 0) posY += Math.min(-posY, menuH);

            menu.setX(posX);
            menu.setY(posY);
            menu.animateOpen();
        }

        public void closeMenu() {
            if (!menuOpen) return;
            menuOpen = false;
            menuTracking = false;
            longPressFeedback.cancel();
            menu.animateClose(this::hideIfIdle);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (!menuOpen) {
                // 菜单关闭时不拦截任何触摸，交给下层 carousel/UI
                return false;
            }

            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                float mx = event.getX() - menu.getX();
                float my = event.getY() - menu.getY();
                // 菜单展开动画期间也按最终尺寸判定命中，避免点菜单内部却被当成“外部”
                boolean inside = mx >= 0 && mx <= shownWidth && my >= 0 && my <= shownHeight;

                if (!inside) {
                    closeMenu();
                    return true;
                }
                // 按下即捕获：之后手指滑出菜单 bounds 也要继续把事件交给菜单，
                // 否则 ScrollContainer 收不到 UP、无法结束拖拽/触发惯性（表现为滚动中途停住）。
                menuTracking = true;
            }

            if (!menuTracking) {
                return true;
            }

            MotionEvent local = MotionEvent.obtain(event);
            local.offsetLocation(-menu.getX(), -menu.getY());
            boolean handled = menu.handleMenuTouch(local);
            local.recycle();

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                menuTracking = false;
            }
            return handled;
        }
    }

}
