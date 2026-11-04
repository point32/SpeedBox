package com.speedbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 谱面变速核心：按倍率缩放 .osu / .mc 谱面的时间轴，其余内容原样保留。
 * 语义移植自 OSU-Toolbox (Python) 的 speed_change.transform_osu_text / transform_mc_text。
 *
 * 时间缩放规则：音乐被重采样为原来的 1/speed 长度，因此谱面里每个绝对时间
 * 都除以 speed；.osu 的 beat length（正值）随之缩小以保持音符落在原节拍上；
 * .mc 的 bpm 乘以 speed（Malody 用 bpm 表达速度，beat 位置不动）。
 */
public final class ChartSpeed {

    public static final double MIN_SPEED = 0.05;
    public static final double MAX_SPEED = 20.0;

    /** osu! storyboard 命令首字母（这些行的第 3、4 字段是起止时间）。 */
    private static final Set<String> SB_COMMANDS = new LinkedHashSet<>(
            java.util.Arrays.asList("F", "M", "MX", "MY", "S", "V", "R", "C", "P"));

    private static final Pattern MD_LINE =
            Pattern.compile("^([A-Za-z][A-Za-z0-9]*)\\s*:\\s*(.*)$");

    /** 变速结果信息。 */
    public static final class Info {
        public String audioFilename = "";
        public String version = "";
        public String songTitle = "";
    }

    public static final class SpeedException extends IllegalArgumentException {
        public SpeedException(String msg) { super(msg); }
    }

    private ChartSpeed() {}

    /** 校验并归一化倍率，拒绝 1.0（无操作）和越界值。 */
    public static double normalizedSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < MIN_SPEED || speed > MAX_SPEED) {
            throw new SpeedException("变速倍率必须在 " + MIN_SPEED + " 到 " + MAX_SPEED + " 之间");
        }
        if (Math.abs(speed - 1.0) < 1e-9) {
            throw new SpeedException("变速倍率不能是 1.0");
        }
        return speed;
    }

    // ------------------------------------------------------------------
    // 数值格式化（与 Python 版一致）
    // ------------------------------------------------------------------

    /** 缩放一个绝对毫秒值。 */
    private static double scale(double value, double speed) {
        return value / speed;
    }

    /** 缩放后的毫秒值，渲染为去掉尾随 0 的字符串（用于 beat length 等非时间戳）。 */
    private static String number(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.4f", value);
        // 去尾 0 与小数点
        if (text.indexOf('.') >= 0) {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == '0') end--;
            if (end > 0 && text.charAt(end - 1) == '.') end--;
            text = text.substring(0, end);
        }
        return text.isEmpty() ? "0" : text;
    }

    /** 缩放一个绝对时间并渲染为四舍五入的整毫秒。 */
    private static String milliseconds(String value, double speed) {
        double v;
        try {
            v = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return value; // 无法解析则原样返回
        }
        return String.valueOf(Math.round(scale(v, speed)));
    }

    private static boolean isFinite(double d) { return !Double.isNaN(d) && !Double.isInfinite(d); }

    // ------------------------------------------------------------------
    // 行处理工具（保留原有换行风格）
    // ------------------------------------------------------------------

    private static final class Lines {
        final List<String> list;
        final String ending;
        final boolean trailing;
        Lines(List<String> l, String e, boolean t) { list = l; ending = e; trailing = t; }
        String join() {
            return String.join(ending, list) + (trailing ? ending : "");
        }
    }

    private static Lines splitLines(String text) {
        String ending = text.contains("\r\n") ? "\r\n" : "\n";
        boolean trailing = text.endsWith("\n") || text.endsWith("\r");
        if (trailing && text.endsWith(ending)) {
            text = text.substring(0, text.length() - ending.length());
        }
        return new Lines(new ArrayList<>(java.util.Arrays.asList(text.split(ending, -1))), ending, trailing);
    }

    // ------------------------------------------------------------------
    // .osu 变速
    // ------------------------------------------------------------------

    private static String scaleLeadingTime(String value, double speed) {
        // "4000:0:0:0:0:" 只有第一个 token 是时间
        int idx = value.indexOf(':');
        if (idx < 0) return milliseconds(value, speed);
        return milliseconds(value.substring(0, idx), speed) + value.substring(idx);
    }

    private static String scaleOsuHitObject(String line, double speed) {
        String[] parts = line.split(",", -1);
        if (parts.length < 5) return line;
        int objectType;
        try { objectType = (int) Double.parseDouble(parts[3].trim()); }
        catch (NumberFormatException e) { return line; }
        parts[2] = milliseconds(parts[2], speed);
        if ((objectType & 128) != 0 && parts.length > 5) {
            parts[5] = scaleLeadingTime(parts[5], speed);   // mania 长条尾
        } else if ((objectType & 8) != 0 && parts.length > 5) {
            parts[5] = scaleLeadingTime(parts[5], speed);   // spinner 结束
        }
        return String.join(",", parts);
    }

    private static String scaleOsuTimingPoint(String line, double speed) {
        String[] parts = line.split(",", -1);
        if (parts.length < 2) return line;
        double beatLength;
        try { beatLength = Double.parseDouble(parts[1].trim()); }
        catch (NumberFormatException e) { return line; }
        if (!isFinite(beatLength) || beatLength == 0.0) return line;
        parts[0] = milliseconds(parts[0], speed);
        if (beatLength > 0) {
            // 只有正值（红线的 beat length）缩放；负值是滑条速度倍率，不动
            parts[1] = number(scale(beatLength, speed));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append(',');
            sb.append(i < parts.length ? parts[i] : "0");
        }
        return sb.toString();
    }

    private static String scaleEventField(String field, double speed) {
        String text = field.trim();
        if (text.isEmpty()) return field;
        int lead = field.length() - field.stripLeading().length();
        int trail = field.length() - field.stripTrailing().length();
        return field.substring(0, lead) + milliseconds(text, speed)
                + (trail > 0 ? field.substring(field.length() - trail) : "");
    }

    private static String scaleOsuEventLine(String line, double speed) {
        String stripped = line.trim();
        if (stripped.isEmpty() || stripped.startsWith("//")) return line;
        String[] parts = line.split(",", -1);
        String head = parts[0].trim();
        int[] indexes;
        switch (head) {
            case "0": case "Background": return line;
            case "1": case "Video": case "Sample": case "L":
                indexes = new int[]{1}; break;
            case "2": case "Break": case "T":
                indexes = new int[]{1, 2}; break;
            default:
                if (SB_COMMANDS.contains(head)) indexes = new int[]{2, 3};
                else return line;
        }
        for (int i : indexes) {
            if (parts.length > i) parts[i] = scaleEventField(parts[i], speed);
        }
        return String.join(",", parts);
    }

    private static final Pattern MD_KEY_LINE =
            Pattern.compile("^(\\s*[A-Za-z][A-Za-z0-9]*\\s*:\\s*)(.*)$", Pattern.DOTALL);

    private static boolean appendOsuFieldValue(List<String> lines, String key, String suffix) {
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            Matcher m = MD_LINE.matcher(trimmed);
            if (!m.matches() || !m.group(1).equals(key)) continue;
            Matcher km = Pattern.compile("^(\\s*" + Pattern.quote(key) + "\\s*:\\s*)(.*)$", Pattern.DOTALL)
                    .matcher(lines.get(i));
            if (km.matches()) {
                lines.set(i, km.group(1) + km.group(2) + suffix);
                return true;
            }
        }
        return false;
    }

    private static boolean prependOsuFieldValue(List<String> lines, String key, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            Matcher m = MD_LINE.matcher(trimmed);
            if (!m.matches() || !m.group(1).equals(key)) continue;
            Matcher km = Pattern.compile("^(\\s*" + Pattern.quote(key) + "\\s*:\\s*)(.*)$", Pattern.DOTALL)
                    .matcher(lines.get(i));
            if (km.matches()) {
                lines.set(i, km.group(1) + prefix + km.group(2));
                return true;
            }
        }
        return false;
    }

    private static String replaceMetadataValue(String line, String key, String newValue) {
        Pattern p = Pattern.compile("^(\\s*" + Pattern.quote(key) + "\\s*:\\s*).*$", Pattern.DOTALL);
        Matcher m = p.matcher(line);
        if (m.matches()) return m.group(1) + newValue;
        return line;
    }

    /** 变速一个 .osu 谱面文本。difficultyMarker/songMarker 可为 "" 表示不标记。 */
    public static Object[] transformOsuText(String text, double speed,
                                            String difficultyMarker, String songMarker) {
        if (songMarker == null) songMarker = difficultyMarker;
        Lines lines = splitLines(text);
        // 找出各 section 的行号
        Map<String, List<Integer>> sections = new LinkedHashMap<>();
        String current = null;
        for (int i = 0; i < lines.list.size(); i++) {
            String stripped = lines.list.get(i).trim();
            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                current = stripped.substring(1, stripped.length() - 1);
                sections.computeIfAbsent(current, k -> new ArrayList<>()).add(i);
            } else if (current != null) {
                sections.get(current).add(i);
            }
        }
        Info info = new Info();

        List<Integer> idx = sections.get("TimingPoints");
        if (idx != null) for (int i : idx)
            if (!lines.list.get(i).trim().isEmpty())
                lines.list.set(i, scaleOsuTimingPoint(lines.list.get(i), speed));

        idx = sections.get("HitObjects");
        if (idx != null) for (int i : idx)
            if (!lines.list.get(i).trim().isEmpty())
                lines.list.set(i, scaleOsuHitObject(lines.list.get(i), speed));

        idx = sections.get("Events");
        if (idx != null) for (int i : idx)
            lines.list.set(i, scaleOsuEventLine(lines.list.get(i), speed));

        idx = sections.get("General");
        if (idx != null) for (int i : idx) {
            String line = lines.list.get(i);
            Matcher m = MD_LINE.matcher(line.trim());
            if (!m.matches()) continue;
            String key = m.group(1), value = m.group(2).trim();
            if (key.equals("AudioFilename")) { info.audioFilename = value; continue; }
            if (key.equals("AudioLeadIn")) {
                lines.list.set(i, replaceMetadataValue(line, key, milliseconds(value, speed)));
                continue;
            }
            if (key.equals("PreviewTime")) {
                double preview;
                try { preview = Double.parseDouble(value); } catch (NumberFormatException e) { continue; }
                if (isFinite(preview) && preview >= 0) {
                    lines.list.set(i, replaceMetadataValue(line, key, milliseconds(value, speed)));
                }
            }
        }

        idx = sections.get("Metadata");
        String version = "";
        if (idx != null) for (int i : idx) {
            Matcher m = MD_LINE.matcher(lines.list.get(i).trim());
            if (m.matches() && m.group(1).equals("Version")) {
                version = m.group(2).trim();
            }
        }
        if (!difficultyMarker.isEmpty() && !version.isEmpty()) {
            String markerWithSpace = difficultyMarker.endsWith(" ") ? difficultyMarker : (difficultyMarker + " ");
            prependOsuFieldValue(lines.list, "Version", markerWithSpace);
            version = markerWithSpace + version;
        }
        if (!songMarker.isEmpty()) {
            appendOsuFieldValue(lines.list, "Title", songMarker);
            appendOsuFieldValue(lines.list, "TitleUnicode", songMarker);
        }
        info.version = version;
        return new Object[]{lines.join(), info};
    }

    // ------------------------------------------------------------------
    // .mc 变速
    // ------------------------------------------------------------------

    private static Object mcScaleOffset(Object value, double speed) {
        // 与原版一致：bool/None/空串/无法解析的值原样保留
        if (value instanceof Boolean || value == null || value instanceof JSONObject
                || value instanceof JSONArray) {
            return value;
        }
        double v;
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.isEmpty()) return value;
            try { v = Double.parseDouble(s); }
            catch (NumberFormatException e) { return value; }
        } else if (value instanceof Number) {
            v = ((Number) value).doubleValue();
        } else {
            return value;
        }
        return (int) Math.round(v / speed);
    }

    private static Object mcScaleTime(Object value, double speed) {
        // beat 元组（JSONArray）不动；毫秒时间缩放
        if (value instanceof JSONObject) return value;
        if (value instanceof JSONArray) return value;
        if (value instanceof Number) {
            return (int) Math.round(((Number) value).doubleValue() / speed);
        }
        if (value instanceof String) {
            try {
                return (int) Math.round(Double.parseDouble((String) value) / speed);
            } catch (NumberFormatException ignored) {}
        }
        return value;
    }

    private static String mcAudioReference(JSONObject root, JSONObject meta, JSONObject song) {
        JSONObject[] containers = {song, meta, root};
        String[] keys = {"file", "music", "audio", "sound", "audio_file", "audioFilename"};
        for (JSONObject c : containers) {
            if (c == null) continue;
            for (String k : keys) {
                String v = c.optString(k, "").trim();
                if (!v.isEmpty()) return v;
            }
        }
        JSONArray notes = root.optJSONArray("note");
        if (notes == null) notes = root.optJSONArray("notes");
        if (notes != null) {
            for (int i = 0; i < notes.length(); i++) {
                JSONObject item = notes.optJSONObject(i);
                if (item == null) continue;
                for (String k : new String[]{"sound", "music", "audio"}) {
                    String v = item.optString(k, "").trim();
                    if (!v.isEmpty()) return v;
                }
            }
        }
        return "";
    }

    /** 变速一个 .mc 谱面文本，返回 [json 文本, Info]。 */
    public static Object[] transformMcText(String text, double speed,
                                           String difficultyMarker, String songMarker)
            throws org.json.JSONException {
        if (songMarker == null) songMarker = difficultyMarker;
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (Exception e) {
            throw new SpeedException("无法解析 .mc 谱面：" + e.getMessage()
                    + "；该文件可能不是有效的 Malody 谱面，或其编码/语法不受支持");
        }
        JSONObject meta = root.optJSONObject("meta") == null ? new JSONObject() : root.optJSONObject("meta");
        JSONObject song = meta.optJSONObject("song") == null ? new JSONObject() : meta.optJSONObject("song");
        String reference = mcAudioReference(root, meta, song);
        String version = meta.optString("version", meta.optString("difficulty", ""));

        // BPM 乘 speed：beat 位置不动，更快的 BPM = 更快的播放
        for (String key : new String[]{"time", "timing"}) {
            JSONArray entries = root.optJSONArray(key);
            if (entries == null) continue;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null) continue;
                double bpm = item.optDouble("bpm", 0.0);
                if (!Double.isNaN(bpm) && bpm > 0) item.put("bpm", bpm * speed);
            }
        }

        JSONArray notes = root.optJSONArray("note");
        if (notes == null) notes = root.optJSONArray("notes");
        if (notes != null) {
            for (int i = 0; i < notes.length(); i++) {
                JSONObject item = notes.optJSONObject(i);
                if (item == null) continue;
                if (item.has("time")) item.put("time", mcScaleTime(item.get("time"), speed));
                if (item.has("end_time")) item.put("end_time", mcScaleTime(item.get("end_time"), speed));
                if (item.has("offset")) item.put("offset", mcScaleOffset(item.get("offset"), speed));
            }
        }

        for (JSONObject c : new JSONObject[]{meta, song}) {
            for (String key : new String[]{"audio_offset", "audioOffset", "audio_delay", "audioDelay", "offset"}) {
                if (c.has(key)) c.put(key, mcScaleOffset(c.get(key), speed));
            }
        }

        if (!difficultyMarker.isEmpty() && !version.isEmpty()) {
            String markerWithSpace = difficultyMarker.endsWith(" ") ? difficultyMarker : (difficultyMarker + " ");
            meta.put("version", markerWithSpace + version);
            version = markerWithSpace + version;
        }
        String songTitle = "";
        if (!songMarker.isEmpty() && song.optString("title", "").length() > 0) {
            songTitle = song.getString("title");
            song.put("title", songTitle + songMarker);
        }
        Info info = new Info();
        info.audioFilename = reference;
        info.version = version;
        info.songTitle = songTitle;
        String out;
        try {
            // indent=2 输出，结尾换行（键顺序不保证与原文一致，Malody 不依赖键序）
            out = root.toString(2) + "\n";
        } catch (org.json.JSONException e) {
            throw new SpeedException("序列化 .mc 谱面失败：" + e.getMessage());
        }
        return new Object[]{out, info};
    }

    // ------------------------------------------------------------------
    // 分发
    // ------------------------------------------------------------------

    /** 按扩展名自动分发：返回 [输出文本, Info]。 */
    public static Object[] transform(String text, String extension, double speed,
                                     String difficultyMarker, String songMarker)
            throws org.json.JSONException {
        normalizedSpeed(speed);
        String ext = extension.toLowerCase();
        if (ext.endsWith(".osu")) return transformOsuText(text, speed, difficultyMarker, songMarker);
        if (ext.endsWith(".mc")) return transformMcText(text, speed, difficultyMarker, songMarker);
        throw new SpeedException("不支持的谱面格式: " + extension);
    }
}
