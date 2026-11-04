package com.speedbox.ui;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import com.speedbox.ui.LazerUi.LazerEasing;
import com.speedbox.ui.LazerUi.LazerSettingsSliderView;
import com.speedbox.ui.LazerUi.OsuColour;

/**
 * osu!lazer SettingsPanel.cs 的 1:1 移植（OverlayColourScheme.Purple, Hue = 255）：
 * 左侧 SettingsSidebar（图标+标签，选中指示条）+ 右侧内容（SettingsHeader + SettingsSectionsContainer + SettingsFooter）。
 *
 * 本面板承载 SpeedBox 的核心功能：变速不变调 / 变速变调、倍率/BPM、半音、输出方式。
 */
public final class SettingsPanelView extends FrameLayout {

    public interface Listener {
        void onModeChanged(boolean pitchLocked);      // 变速不变调 = true
        void onUnitChanged(boolean bpmUnit);          // true = 目标 BPM
        void onSpeedChanged(float value);             // 当前单位下的数值
        void onSemitonesChanged(int semitones);
        void onOutputChanged(int libraryMode);        // Library.MODE_*（用字面量 2/1/0 避免耦合）
        void onCloseRequested();
    }

    // 侧栏项
    private static final int CAT_SPEED = 0;
    private static final int CAT_OUTPUT = 1;

    // Library.MODE_LOOSE / MODE_MCZ_BUNDLE / MODE_MCZ_SEPARATE
    private static final int[] OUTPUT_MODES = {2, 1, 0};
    private static final String[] OUTPUT_LABELS = {"原目录写入", "导出 .mcz · 合包", "导出 .mcz · 分包"};

    private final View dim;
    private final ScrollView contentScroll;
    private final LinearLayout contentColumn;
    private final LinearLayout sidebar;

    private SidebarButtonView btnSpeed;
    private SidebarButtonView btnOutput;
    private SectionView secSpeed;
    private SectionView secOutput;

    private OsuTabControlView modeTab;
    private OsuTabControlView unitTab;
    private LazerSettingsSliderView speedSlider;
    private LazerSettingsSliderView semitoneSlider;
    private OsuNumberBoxView speedBox;
    private SettingsDropdownView outputDropdown;

    private Listener listener;
    private boolean open = false;
    private boolean suppress = false;
    private int activeCategory = CAT_SPEED;

    // 状态
    private boolean pitchLocked = true;

    private final int sidebarWidth;
    private final Runnable closeOnDim = () -> requestClose();

    public SettingsPanelView(Context context) {
        this(context, null);
    }

    public SettingsPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
        setClipToPadding(false);
        setVisibility(GONE);

        sidebarWidth = (int) LazerUi.dp(context, 110f);

        dim = new View(context);
        dim.setBackgroundColor(OsuColour.withAlpha(OsuColour.SET_BG6, 0.75f));
        dim.setClickable(true);
        dim.setOnClickListener(v -> requestClose());
        addView(dim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        contentScroll = new ScrollView(context);
        contentScroll.setVerticalScrollBarEnabled(false);
        contentScroll.setClipToPadding(true);
        contentScroll.setBackgroundColor(OsuColour.SET_BG6);
        contentScroll.setPadding(sidebarWidth, 0, 0, 0);
        addView(contentScroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        contentColumn = new LinearLayout(context);
        contentColumn.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(contentColumn, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildContent(context);

        sidebar = new LinearLayout(context);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setBackgroundColor(OsuColour.SET_BG5);
        LayoutParams sbLp = new LayoutParams(sidebarWidth, LayoutParams.MATCH_PARENT, Gravity.START);
        addView(sidebar, sbLp);
        buildSidebar(context);
    }

    // ===================== content =====================

    private void buildContent(Context c) {
        // SettingsHeader (TorusAlternate 40 + subheading 18)
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding((int) LazerUi.dp(c, 12), (int) LazerUi.dp(c, 20), (int) LazerUi.dp(c, 22), (int) LazerUi.dp(c, 20));
        TextView title = new TextView(c);
        title.setText("设置");
        title.setTextColor(OsuColour.SET_CONTENT1);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 32f);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD), Typeface.BOLD);
        header.addView(title);
        TextView sub = new TextView(c);
        sub.setText("变速与输出");
        sub.setTextColor(OsuColour.SET_CONTENT2);
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
        header.addView(sub);
        contentColumn.addView(header);

        // SettingsSection 变速
        secSpeed = new SectionView(c, "变速");
        modeTab = new OsuTabControlView(c);
        modeTab.setItems(new String[]{"变速不变调", "变速变调"});
        modeTab.setSelectedIndex(0);
        modeTab.setAccentColor(OsuColour.SET_HL1);
        modeTab.setOnTabSelectedListener(index -> {
            if (suppress) return;
            pitchLocked = index == 0;
            updateSemitoneEnabled();
            if (listener != null) listener.onModeChanged(pitchLocked);
        });
        secSpeed.addItem("音高模式", modeTab);

        LinearLayout speedGroup = new LinearLayout(c);
        speedGroup.setOrientation(LinearLayout.VERTICAL);
        unitTab = new OsuTabControlView(c);
        unitTab.setItems(new String[]{"倍率 x", "目标 BPM"});
        unitTab.setSelectedIndex(0);
        unitTab.setAccentColor(OsuColour.SET_HL1);
        unitTab.setOnTabSelectedListener(index -> {
            if (suppress) return;
            if (listener != null) listener.onUnitChanged(index == 1);
        });
        speedGroup.addView(unitTab);
        speedSlider = new LazerSettingsSliderView(c);
        speedSlider.configure(0.50f, 2.00f, 0.05f, 1.25f, 1.00f, OsuColour.SET_HL1);
        speedSlider.setLabel("变速倍率 (RATE MULTIPLIER)");
        speedSlider.setValueFormat("%.2fx");
        speedSlider.setOnValueChangeListener((value, fromUser) -> {
            if (suppress) return;
            setSpeedBoxText(value);
            if (fromUser && listener != null) listener.onSpeedChanged(value);
        });
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) LazerUi.dp(c, 54f));
        sliderLp.topMargin = (int) LazerUi.dp(c, 4f);
        speedGroup.addView(speedSlider, sliderLp);

        speedBox = new OsuNumberBoxView(c);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                (int) LazerUi.dp(c, 120f), (int) LazerUi.dp(c, 40f));
        boxLp.topMargin = (int) LazerUi.dp(c, 6f);
        speedGroup.addView(speedBox, boxLp);
        speedBox.setOnValueCommittedListener(v -> {
            if (suppress) return;
            speedSlider.setValue(v, true);
        });
        secSpeed.addItem("速度", speedGroup);

        semitoneSlider = new LazerSettingsSliderView(c);
        semitoneSlider.configure(-12f, 12f, 1f, 0f, 0f, OsuColour.SET_HL1);
        semitoneSlider.setLabel("半音微调 (SEMITONE)");
        semitoneSlider.setValueFormat("%+.0f st");
        semitoneSlider.setOnValueChangeListener((value, fromUser) -> {
            if (suppress) return;
            if (fromUser && listener != null) listener.onSemitonesChanged(Math.round(value));
        });
        LinearLayout.LayoutParams semiLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) LazerUi.dp(c, 54f));
        semitoneSlider.setLayoutParams(semiLp);
        secSpeed.addItem("音高偏移", semitoneSlider);

        contentColumn.addView(secSpeed);

        // SettingsSection 输出
        secOutput = new SectionView(c, "输出");
        outputDropdown = new SettingsDropdownView(c);
        outputDropdown.setItems(OUTPUT_LABELS);
        outputDropdown.setSelectedIndex(0);
        outputDropdown.setAccentColor(OsuColour.SET_HL1);
        outputDropdown.setOnItemSelectedListener(index -> {
            if (suppress) return;
            if (listener != null) listener.onOutputChanged(OUTPUT_MODES[index]);
        });
        secOutput.addItem("输出方式", outputDropdown);
        contentColumn.addView(secOutput);

        // SettingsFooter
        TextView footer = new TextView(c);
        footer.setText("SpeedBox · 谱面变速 (osu!mania / Malody Key)");
        footer.setTextColor(OsuColour.SET_FG1);
        footer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        footer.setPadding((int) LazerUi.dp(c, 12), (int) LazerUi.dp(c, 20), (int) LazerUi.dp(c, 22), (int) LazerUi.dp(c, 8));
        contentColumn.addView(footer);

        // 开源许可入口（MIT / LGPL / BSD / Apache-2.0 全文在 assets/licenses）
        TextView licensesLink = new TextView(c);
        licensesLink.setText("开源许可 / Open-source licenses");
        licensesLink.setTextColor(OsuColour.SET_HL1);
        licensesLink.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        licensesLink.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        licensesLink.setPadding((int) LazerUi.dp(c, 12), 0, (int) LazerUi.dp(c, 22), (int) LazerUi.dp(c, 30));
        licensesLink.setOnClickListener(v -> showLicensesDialog());
        contentColumn.addView(licensesLink);
    }

    /** 在应用内展示第三方许可证全文，满足 MIT/LGPL/BSD/Apache 的随附要求。 */
    private void showLicensesDialog() {
        Context c = getContext();
        Dialog dialog = new Dialog(c);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int pad = (int) LazerUi.dp(c, 16f);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(OsuColour.SET_BG5);
        bg.setCornerRadius(LazerUi.dp(c, 10f));
        box.setBackground(bg);

        TextView title = new TextView(c);
        title.setText("开源许可 / Open-source licenses");
        title.setTextColor(OsuColour.SET_CONTENT1);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(c, 15f));
        title.setPadding(0, 0, 0, (int) LazerUi.dp(c, 8f));
        box.addView(title);

        ScrollView scroll = new ScrollView(c);
        TextView text = new TextView(c);
        text.setText(readLicenseAssets());
        text.setTextColor(OsuColour.SET_FG1);
        text.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(c, 8.5f));
        text.setLineSpacing(0f, 1.1f);
        scroll.addView(text);
        box.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) LazerUi.dp(c, 380f)));

        TextView close = new TextView(c);
        close.setText("关闭");
        close.setTextColor(OsuColour.SET_HL1);
        close.setGravity(Gravity.END);
        close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, LazerUi.sp(c, 13f));
        close.setPadding(0, (int) LazerUi.dp(c, 10f), 0, 0);
        close.setOnClickListener(v -> dialog.dismiss());
        box.addView(close);

        dialog.setContentView(box);
        dialog.show();
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setDimAmount(0.65f);
            win.setLayout((int) (c.getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private String readLicenseAssets() {
        StringBuilder sb = new StringBuilder();
        try {
            String[] names = getContext().getAssets().list("licenses");
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String name : names) {
                    sb.append("\n\n===== ").append(name).append(" =====\n\n");
                    try (InputStream in = getContext().getAssets().open("licenses/" + name);
                         BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    }
                }
            }
            if (sb.length() == 0) sb.append("未找到许可证文件");
        } catch (Exception e) {
            sb.append("读取许可证失败: ").append(e);
        }
        return sb.toString();
    }

    private void buildSidebar(Context c) {
        btnSpeed = new SidebarButtonView(c, "变速", SidebarButtonView.ICON_SPEED);
        btnOutput = new SidebarButtonView(c, "输出", SidebarButtonView.ICON_OUTPUT);
        btnSpeed.setOnClickListener(v -> selectCategory(CAT_SPEED));
        btnOutput.setOnClickListener(v -> selectCategory(CAT_OUTPUT));
        sidebar.addView(btnSpeed, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) LazerUi.dp(c, 46f)));
        sidebar.addView(btnOutput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) LazerUi.dp(c, 46f)));

        View spacer = new View(c);
        sidebar.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        SidebarButtonView back = new SidebarButtonView(c, "返回", SidebarButtonView.ICON_BACK);
        back.setOnClickListener(v -> requestClose());
        sidebar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) LazerUi.dp(c, 46f)));

        updateCategoryVisuals();
    }

    private void selectCategory(int cat) {
        activeCategory = cat;
        updateCategoryVisuals();
        View target = cat == CAT_SPEED ? secSpeed : secOutput;
        contentScroll.smoothScrollTo(0, target.getTop());
    }

    private void updateCategoryVisuals() {
        if (btnSpeed != null) btnSpeed.setActive(activeCategory == CAT_SPEED);
        if (btnOutput != null) btnOutput.setActive(activeCategory == CAT_OUTPUT);
    }

    // ===================== public API =====================

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isOpen() {
        return open;
    }

    public void open() {
        if (open) return;
        open = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        contentScroll.setTranslationX(-LazerUi.dp(getContext(), 60f));
        sidebar.setTranslationX(-sidebarWidth);
        animate().alpha(1f).setDuration(300).setInterpolator(LazerEasing.OUT_QUINT).start();
        contentScroll.animate().translationX(0f).setDuration(600).setInterpolator(LazerEasing.OUT_QUINT).start();
        sidebar.animate().translationX(0f).setDuration(600).setInterpolator(LazerEasing.OUT_QUINT).start();
    }

    public void close() {
        if (!open) return;
        open = false;
        animate().alpha(0f).setDuration(300).setInterpolator(LazerEasing.OUT_QUINT).start();
        contentScroll.animate().translationX(-LazerUi.dp(getContext(), 60f)).setDuration(600).setInterpolator(LazerEasing.OUT_QUINT).start();
        sidebar.animate().translationX(-sidebarWidth).setDuration(600).setInterpolator(LazerEasing.OUT_QUINT)
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    private void requestClose() {
        if (listener != null) listener.onCloseRequested();
        else close();
    }

    public void setPitchLocked(boolean locked) {
        suppress = true;
        this.pitchLocked = locked;
        modeTab.setSelectedIndex(locked ? 0 : 1);
        updateSemitoneEnabled();
        suppress = false;
    }

    public boolean isPitchLocked() {
        return pitchLocked;
    }

    public void setBpmUnit(boolean bpm) {
        suppress = true;
        unitTab.setSelectedIndex(bpm ? 1 : 0);
        suppress = false;
    }

    public void setSemitones(int semitones) {
        suppress = true;
        semitoneSlider.setValue(semitones, false);
        suppress = false;
    }

    public void setOutputMode(int libraryMode) {
        suppress = true;
        for (int i = 0; i < OUTPUT_MODES.length; i++) {
            if (OUTPUT_MODES[i] == libraryMode) {
                outputDropdown.setSelectedIndex(i);
                break;
            }
        }
        suppress = false;
    }

    /** 重新配置速度滑块量程（单位变化时调用）。 */
    public void configureSpeedSlider(float min, float max, float step, float value, float def,
                                     String label, String format) {
        suppress = true;
        speedSlider.configure(min, max, step, value, def, OsuColour.SET_HL1);
        speedSlider.setLabel(label);
        speedSlider.setValueFormat(format);
        setSpeedBoxText(value);
        suppress = false;
    }

    public void setSpeedValue(float v) {
        suppress = true;
        speedSlider.setValue(v, false);
        setSpeedBoxText(v);
        suppress = false;
    }

    private void setSpeedBoxText(float v) {
        if (speedBox == null) return;
        speedBox.setFloatValue(v);
    }

    private void updateSemitoneEnabled() {
        if (semitoneSlider == null) return;
        semitoneSlider.setAlpha(1f);
        semitoneSlider.setEnabled(true);
    }

    // ===================== inner views =====================

    /** SettingsSection.cs — 顶部分隔线 + TorusAlternate 大标题 + 若干 SettingsItem。 */
    private static final class SectionView extends LinearLayout {
        private final LinearLayout items;

        SectionView(Context c, String title) {
            super(c);
            setOrientation(VERTICAL);

            View sep = new View(c);
            sep.setBackgroundColor(OsuColour.SET_BG6);
            addView(sep, new LayoutParams(LayoutParams.MATCH_PARENT, (int) LazerUi.dp(c, 2f)));

            TextView header = new TextView(c);
            header.setText(title);
            header.setTextColor(OsuColour.SET_CONTENT1);
            header.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f);
            header.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD), Typeface.BOLD);
            header.setPadding((int) LazerUi.dp(c, 12), (int) LazerUi.dp(c, 16), (int) LazerUi.dp(c, 22), (int) LazerUi.dp(c, 6));
            addView(header);

            items = new LinearLayout(c);
            items.setOrientation(VERTICAL);
            addView(items, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }

        void addItem(String label, View control) {
            Context c = getContext();
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(VERTICAL);
            item.setPadding((int) LazerUi.dp(c, 20), (int) LazerUi.dp(c, 6), (int) LazerUi.dp(c, 20), (int) LazerUi.dp(c, 6));

            if (label != null && !label.isEmpty()) {
                TextView l = new TextView(c);
                l.setText(label);
                l.setTextColor(OsuColour.SET_CONTENT2);
                l.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
                item.addView(l);
            }

            ViewGroup.LayoutParams existingLp = control.getLayoutParams();
            int h = (existingLp != null && existingLp.height > 0) ? existingLp.height : LayoutParams.WRAP_CONTENT;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, h);
            clp.topMargin = (int) LazerUi.dp(c, 5f);
            item.addView(control, clp);

            items.addView(item, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
    }

    /** SidebarIconButton.cs — 图标 + 标签 + 选中指示条。 */
    private static final class SidebarButtonView extends View {
        static final int ICON_SPEED = 0;
        static final int ICON_OUTPUT = 1;
        static final int ICON_BACK = 2;

        private final String label;
        private final int iconType;
        private boolean active = false;

        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final Path path = new Path();

        SidebarButtonView(Context c, String label, int iconType) {
            super(c);
            this.label = label;
            this.iconType = iconType;
            setClickable(true);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(LazerUi.dp(c, 1.8f));
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            textPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            textPaint.setTextSize(LazerUi.sp(c, 14f));
        }

        void setActive(boolean a) {
            this.active = a;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float iconSize = LazerUi.dp(getContext(), 18f);
            float iconX = LazerUi.dp(getContext(), 16f);
            float cy = h * 0.5f;

            int tint = active ? OsuColour.SET_CONTENT1 : OsuColour.SET_LIGHT3;
            iconPaint.setColor(tint);
            textPaint.setColor(tint);

            // selection indicator (CircularContainer width 4, height 18, corner 1.5)
            if (active) {
                float barW = LazerUi.dp(getContext(), 4f);
                float barH = LazerUi.dp(getContext(), 18f);
                rf.set(LazerUi.dp(getContext(), 6f), cy - barH * 0.5f,
                       LazerUi.dp(getContext(), 6f) + barW, cy + barH * 0.5f);
                barPaint.setColor(OsuColour.SET_HL1);
                canvas.drawRoundRect(rf, LazerUi.dp(getContext(), 1.5f), LazerUi.dp(getContext(), 1.5f), barPaint);
            }

            drawIcon(canvas, iconX + iconSize * 0.5f, cy, iconSize * 0.5f);

            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float ty = cy - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(label, LazerUi.dp(getContext(), 46f), ty, textPaint);
        }

        private void drawIcon(Canvas canvas, float cx, float cy, float r) {
            path.reset();
            switch (iconType) {
                case ICON_SPEED: // 时钟/速度 —— 圆 + 指针
                    canvas.drawCircle(cx, cy, r, iconPaint);
                    canvas.drawLine(cx, cy, cx, cy - r * 0.55f, iconPaint);
                    canvas.drawLine(cx, cy, cx + r * 0.45f, cy + r * 0.1f, iconPaint);
                    break;
                case ICON_OUTPUT: // 文件夹/导出
                    rf.set(cx - r, cy - r * 0.6f, cx + r, cy + r * 0.7f);
                    canvas.drawRoundRect(rf, LazerUi.dp(getContext(), 1.5f), LazerUi.dp(getContext(), 1.5f), iconPaint);
                    canvas.drawLine(cx - r, cy - r * 0.15f, cx + r, cy - r * 0.15f, iconPaint);
                    break;
                case ICON_BACK: // chevron-left
                default:
                    path.moveTo(cx + r * 0.4f, cy - r * 0.7f);
                    path.lineTo(cx - r * 0.5f, cy);
                    path.lineTo(cx + r * 0.4f, cy + r * 0.7f);
                    canvas.drawPath(path, iconPaint);
                    break;
            }
        }
    }
}
