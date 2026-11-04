package com.speedbox;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.speedbox.audio.AudioPipeline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 曲库（Malody beatmap 目录）：扫描歌曲文件夹、枚举版本、搜索、执行变速打包。
 * 直接 File I/O，依赖 MANAGE_EXTERNAL_STORAGE 权限。
 */
public final class Library {

    public interface Progress {
        void update(String message);

        /** 带明确百分比（0~100）的更新；默认退化为纯文本更新。 */
        default void update(String message, int percent) {
            update(message);
        }
    }

    /** 最近一次任务的输出文件（任务完成后供分享/打开）。 */
    public static final List<File> LAST_OUTPUTS = new ArrayList<>();

    /** 最近一次任务的可分享完整包（.osz 优先；含 .mc 时为 .mcz）。 */
    public static final List<File> LAST_SHARE_FILES = new ArrayList<>();

    /**
     * 分享格式：Malody 兼容 osu，所以优先 .osz；
     * 只有谱面里出现 .mc（Malody 原生格式）时才用 .mcz。
     */
    public static String shareExtension(List<Chart> charts) {
        if (charts != null) {
            for (Chart c : charts) {
                if (c != null && !c.osu) return "mcz";
            }
        }
        return "osz";
    }

    public static String shareExtension(TaskHub.Task task) {
        return task == null ? "osz" : shareExtension(task.charts);
    }

    /**
     * 把一组谱面（同一首歌的全部难度，或单个难度）即时打包成可分享的完整包。
     * 扩展名按 shareExtension：有 .mc 用 .mcz，否则 .osz。
     */
    public static File buildSharePackage(List<Chart> charts) throws Exception {
        if (charts == null || charts.isEmpty()) throw new IOException("没有可分享的谱面");

        File work = new File(charts.get(0).song.dir, ".speedbox_share");
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("无法创建打包目录");

        String ext = shareExtension(charts);
        try {
            List<Mcz.Out> entries = new ArrayList<>();
            java.util.Set<String> added = new java.util.HashSet<>();

            for (Chart c : charts) {
                File cover = coverFile(c);
                if (cover != null && cover.isFile() && added.add(cover.getName())) {
                    entries.add(new Mcz.Out(cover.getName(), cover));
                }

                String text = readChartText(c);
                String entryName = stripAllExt(c.entry != null ? c.entry : c.file.getName())
                        + (c.osu ? ".osu" : ".mc");
                if (added.add(entryName)) {
                    entries.add(new Mcz.Out(entryName, text.getBytes("UTF-8")));
                }

                if (c.audio != null && !c.audio.isEmpty() && added.add(c.audio)) {
                    File src = extractAudio(c, work);
                    if (src != null) entries.add(new Mcz.Out(c.audio, src));
                }
            }

            String base = safeName(charts.get(0).song.title);
            if (charts.size() == 1) base += " " + safeName(charts.get(0).label());
            File out = uniqueOutInner(bufferDir(), base + "." + ext);
            Mcz.write(out, entries);
            return out;
        } finally {
            deleteR(work);
        }
    }

    /** 把多个包打成一个 zip（用于“分享整个文件夹”）。 */
    public static File zipShareFiles(List<File> files, String baseName) throws IOException {
        File out = uniqueOutInner(bufferDir(), safeName(baseName) + ".zip");
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(new FileOutputStream(out))) {
            java.util.Set<String> used = new java.util.HashSet<>();
            for (File f : files) {
                if (f == null || !f.isFile()) continue;

                String name = f.getName();
                if (!used.add(name)) {
                    String stem = stripAllExt(name);
                    String suffix = name.substring(stem.length());
                    int i = 2;
                    while (!used.add(stem + " (" + i + ")" + suffix)) i++;
                    name = stem + " (" + i + ")" + suffix;
                }

                zos.putNextEntry(new java.util.zip.ZipEntry(name));
                try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
        return out;
    }

    public static File bufferDir() {
        File d = new File("/storage/emulated/0/data/malody/.buffer");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 单目录扫描：导入 mcz / 临时目录用。 */
    public static Song scanSong(File dir) {
        Song song = new Song(dir, findCover(dir));
        File[] files = dir.listFiles();
        if (files == null) return song;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith("._") || n.equals(".ds_store")) continue;
            try {
                if (n.endsWith(".mc")) addMc(song, f, null);
                else if (n.endsWith(".mcz")) {
                    for (String entry : Mcz.listMcEntries(f)) addMc(song, f, entry);
                }
            } catch (Exception e) { log("跳过 " + f.getName() + ": " + e.getMessage()); }
        }
        return song;
    }

    /** 重新扫描指定歌曲目录，更新谱面列表、标题与封面。 */
    public static int rescanSong(Song song) {
        if (song == null || song.dir == null) return 0;
        Song fresh = scanSongDir(song.dir);
        song.charts.clear();
        song.charts.addAll(fresh.charts);
        if (fresh.title != null && !fresh.title.isEmpty()) song.title = fresh.title;
        song.cover = fresh.cover;
        return song.charts.size();
    }

    /** 导出整首歌曲文件夹为标准 .mcz 压缩包（下载目录）。 */
    public static File exportSongToMcz(Song song) throws IOException {
        File downloadDir = new File("/storage/emulated/0/Download");
        if (!downloadDir.isDirectory()) downloadDir.mkdirs();
        String safeTitle = song.title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeTitle.isEmpty()) safeTitle = song.dir.getName();
        File outFile = new File(downloadDir, safeTitle + ".mcz");
        int counter = 1;
        while (outFile.exists()) {
            outFile = new File(downloadDir, safeTitle + " (" + counter++ + ").mcz");
        }
        List<Mcz.Out> entries = new ArrayList<>();
        File[] files = song.dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !f.getName().startsWith(".")) {
                    entries.add(new Mcz.Out(f.getName(), f));
                }
            }
        }
        Mcz.write(outFile, entries);
        LAST_OUTPUTS.add(outFile);
        log("成功导出 .mcz: " + outFile.getAbsolutePath());
        return outFile;
    }

    /** 导出整首歌曲文件夹为标准 .osz 压缩包（下载目录）。 */
    public static File exportSongToOsz(Song song) throws IOException {
        File downloadDir = new File("/storage/emulated/0/Download");
        if (!downloadDir.isDirectory()) downloadDir.mkdirs();
        String safeTitle = song.title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeTitle.isEmpty()) safeTitle = song.dir.getName();
        File outFile = new File(downloadDir, safeTitle + ".osz");
        int counter = 1;
        while (outFile.exists()) {
            outFile = new File(downloadDir, safeTitle + " (" + counter++ + ").osz");
        }
        List<Mcz.Out> entries = new ArrayList<>();
        File[] files = song.dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !f.getName().startsWith(".")) {
                    entries.add(new Mcz.Out(f.getName(), f));
                }
            }
        }
        Mcz.write(outFile, entries);
        LAST_OUTPUTS.add(outFile);
        log("成功导出 .osz: " + outFile.getAbsolutePath());
        return outFile;
    }

    /** 扫描取消标志（扫描期间再次点击"加载曲库"时置位）。 */
    public static volatile boolean CANCEL_SCAN = false;

    /** 应用内日志：扫描跳过/失败原因、处理步骤，可在界面查看与复制。 */
    public static final List<String> LOG = new ArrayList<>();

    public static synchronized void log(String msg) {
        LOG.add(android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                + " " + msg);
        if (LOG.size() > 2000) LOG.remove(0);
    }

    public static synchronized String dumpLog() {
        StringBuilder sb = new StringBuilder();
        for (String l : LOG) sb.append(l).append('\n');
        return sb.toString();
    }

    /** 一个谱面（版本）：散装 .mc 或 mcz 内的 .mc。 */
    public static final class Chart {
        public final Song song;
        public final File file;      // 散装 .mc 或所在 .mcz
        public final String entry;   // mcz 内条目名；散装为 null
        public final String version;
        public final String audio;   // 音频引用名（仅文件名）
        public final double bpm;     // 基准 BPM（首个 time/非继承 TimingPoint；0=未知）
        public final boolean osu;    // true=osu 谱面
        Chart(Song song, File file, String entry, String version, String audio, double bpm) {
            this(song, file, entry, version, audio, bpm, false);
        }
        Chart(Song song, File file, String entry, String version, String audio, double bpm, boolean osu) {
            this.song = song; this.file = file; this.entry = entry;
            this.version = version; this.audio = audio; this.bpm = bpm; this.osu = osu;
        }
        public String label() {
            return (version == null || version.isEmpty()) ? file.getName() : version;
        }
    }

    /** 一首歌：一个谱面文件夹。 */
    public static final class Song {
        public final File dir;
        public volatile String title;
        public volatile File cover;
        public final List<Chart> charts = new ArrayList<>();
        Song(File dir, File cover) {
            this(dir, cover, null);
        }
        Song(File dir, File cover, String titleOverride) {
            this.dir = dir;
            this.title = titleOverride != null ? titleOverride : dir.getName();
            this.cover = cover;
        }
    }

    private Library() {}

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /** 每发现一个完整歌曲文件夹就回调一次（用于边扫边展示）。 */
    public interface SongSink { void onSong(Song s); }

    public static List<Song> scan(File root, Progress progress)
            throws IOException, org.json.JSONException {
        return scan(root, progress, null);
    }

    public static List<Song> scan(File root, Progress progress, SongSink sink)
            throws IOException, org.json.JSONException {
        List<Song> songs = new ArrayList<>();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) {
            log("目录不可读: " + root.getAbsolutePath());
            return songs;
        }
        Arrays.sort(dirs);
        int[] scanned = {0};
        for (File dir : dirs) {
            if (CANCEL_SCAN) { log("扫描已取消"); break; }
            walk(dir, songs, progress, scanned, sink);
        }
        log("扫描完成: " + songs.size() + " 个歌曲文件夹, 检查 " + scanned[0] + " 个目录");
        return songs;
    }

    /** 扫描单个歌曲文件夹（含 _song_xxx/0 歌名规则），不递归；谱面可能为空。 */
    public static Song scanSongDir(File dir) {
        // Malody 惯例：_song_xxx/0/*.mc —— 内层是 "0"/纯数字时歌名取外层
        String t = dir.getName();
        if (t.equals("0") || t.matches("\\d+")) {
            File parent = dir.getParentFile();
            if (parent != null && parent.getName().length() > 0) t = parent.getName();
        }
        Song song = new Song(dir, findCover(dir), t);
        File[] files = dir.listFiles();
        if (files == null) { log("目录不可读: " + dir.getAbsolutePath()); return song; }
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith("._") || n.equals(".ds_store")) continue;
            try {
                if (n.endsWith(".mc")) addMc(song, f, null);
                else if (n.endsWith(".osu")) addOsu(song, f, null);
                else if (n.endsWith(".mcz")) {
                    for (String entry : Mcz.listMcEntries(f)) addMc(song, f, entry);
                }
            } catch (Exception e) { log("跳过 " + f.getName() + ": " + e.getMessage()); }
        }
        return song;
    }

    /** 递归遍历：任何深度下含 .mc/.mcz 的目录都算一个歌曲文件夹。 */
    private static void walk(File dir, List<Song> songs, Progress progress, int[] scanned,
                             SongSink sink) {
        if (CANCEL_SCAN) return;
        scanned[0]++;
        File[] files = dir.listFiles();
        if (files == null) { log("目录不可读: " + dir.getAbsolutePath()); return; }
        List<File> mcs = new ArrayList<>(), mczs = new ArrayList<>(), sub = new ArrayList<>();
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith("._") || n.equals(".ds_store")) continue; // macOS 元数据垃圾
            if (f.isDirectory()) sub.add(f);
            else if (n.endsWith(".mc") || n.endsWith(".osu")) mcs.add(f);
            else if (n.endsWith(".mcz")) mczs.add(f);
        }
        if (!mcs.isEmpty() || !mczs.isEmpty()) {
            Song song = scanSongDir(dir);
            if (!song.charts.isEmpty()) {
                songs.add(song);
                if (sink != null) sink.onSong(song);
            }
            else log("无可读谱面: " + dir.getName());
        }
        for (File d : sub) {
            if (CANCEL_SCAN) return;
            walk(d, songs, progress, scanned, sink);
        }
    }

    private static void addMc(Song song, File file, String entry)
            throws IOException, org.json.JSONException {
        byte[] data = (entry == null)
                ? java.nio.file.Files.readAllBytes(file.toPath())
                : Mcz.readEntry(file, entry);
        String text = decodeText(data);
        if (entry != null && entry.toLowerCase(Locale.ROOT).endsWith(".osu")) {
            addOsu(song, file, entry);
            return;
        }
        // 快路径：只解析开头 meta 的 version/audio（大谱面全量 JSON 很慢）
        String[] fast = fastMeta(text);
        if (fast != null && !fast[0].isEmpty()) {
            if (fast[0].contains("/")) fast[0] = fast[0].substring(fast[0].lastIndexOf('/') + 1);
            applyMetaTitle(song, fast.length > 4 ? fast[3] : "", fast.length > 4 ? fast[4] : "");
            song.charts.add(new Chart(song, file, entry, fast[1], fast[0], parseBpm(fast[2])));
            return;
        }
        // fastMeta 没抓到音频（如 4.3.7 audio note 型）→ 全量解析
        JSONObject root = new JSONObject(text);
        JSONObject meta = root.optJSONObject("meta");
        if (meta == null) meta = new JSONObject();
        JSONObject songObj = meta.optJSONObject("song");
        if (songObj == null) songObj = new JSONObject();
        String version = firstNonEmpty(meta.optString("version", ""), meta.optString("difficulty", ""));
        JSONObject rootSong = root.optJSONObject("song") == null ? new JSONObject() : root.optJSONObject("song");
        String audio = firstNonEmpty(
                str(songObj, "audio"), str(songObj, "music"),
                str(rootSong, "audio"), str(rootSong, "music"),
                str(meta, "audio"), str(meta, "music"),
                str(root, "audio"), str(root, "music"),
                firstNoteSound(root));
        if (audio.contains("/")) audio = audio.substring(audio.lastIndexOf('/') + 1);
        double bpm = 0;
        JSONArray timings = root.optJSONArray("time");
        if (timings == null) timings = root.optJSONArray("timing");
        if (timings != null && timings.length() > 0) {
            double[] pos = new double[timings.length()], bs = new double[timings.length()];
            int n = 0;
            for (int i = 0; i < timings.length(); i++) {
                JSONObject t = timings.optJSONObject(i);
                if (t == null) continue;
                double b = t.optDouble("bpm", 0);
                if (Double.isNaN(b) || b <= 0) continue;
                double pb = mcBeatToBars(t.opt("beat"));
                pos[n] = Double.isNaN(pb) ? 0 : pb;
                bs[n] = b; n++;
            }
            double end = 0;
            JSONArray notes = root.optJSONArray("note");
            if (notes == null) notes = root.optJSONArray("notes");
            if (notes != null) for (int i = 0; i < notes.length(); i++) {
                JSONObject nt = notes.optJSONObject(i);
                if (nt == null) continue;
                double pb = mcBeatToBars(nt.opt("beat"));
                if (!Double.isNaN(pb) && pb > end) end = pb;
            }
            bpm = longestBpm(java.util.Arrays.copyOf(pos, n),
                    java.util.Arrays.copyOf(bs, n), end);
        }
        {
            JSONObject ms = meta.optJSONObject("song");
            if (ms == null) ms = rootSong;
            if (ms != null) applyMetaTitle(song, str(ms, "title"), str(ms, "artist"));
        }
        song.charts.add(new Chart(song, file, entry, version, audio, bpm));
    }

    /** 解析 .osu：Version/AudioFilename/Title/Artist/持续时间最长的非继承 TimingPoint BPM。 */
    private static void addOsu(Song song, File file, String entry) throws IOException {
        String text = entry == null ? readChartText(file)
                : new String(Mcz.readEntry(file, entry), "UTF-8");
        String version = "", audio = "", title = "", artist = "";
        String section = "";
        java.util.List<double[]> tps = new ArrayList<>(); // {offset, beatLength}
        double endMs = 0;
        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            if (t.startsWith("[")) { section = t; continue; }
            int colon = t.indexOf(':');
            if (colon > 0) {
                String k = t.substring(0, colon).trim();
                String v = t.substring(colon + 1).trim();
                if (section.startsWith("[General]") && k.equals("AudioFilename")) audio = v;
                else if (section.startsWith("[Metadata]")) {
                    if (k.equals("Version") && version.isEmpty()) version = v;
                    else if (k.equals("Title") && title.isEmpty()) title = v;
                    else if (k.equals("Artist") && artist.isEmpty()) artist = v;
                }
                continue;
            }
            if (t.isEmpty()) continue;
            if (section.startsWith("[TimingPoints]")) {
                String[] fs = t.split(",");
                if (fs.length >= 2) {
                    try {
                        double bl = Double.parseDouble(fs[1].trim());
                        if (bl > 0) tps.add(new double[]{Double.parseDouble(fs[0].trim()), bl});
                    } catch (NumberFormatException ignored) {}
                }
            } else if (section.startsWith("[HitObjects]")) {
                try {
                    double tm = Double.parseDouble(t.split(",")[2].trim());
                    if (tm > endMs) endMs = tm;
                } catch (Exception ignored) {}
            }
        }
        double bpm = 0;
        if (!tps.isEmpty()) {
            double[] pos = new double[tps.size()], bs = new double[tps.size()];
            for (int i = 0; i < tps.size(); i++) {
                pos[i] = tps.get(i)[0] / 1000.0; // 秒
                bs[i] = 60000.0 / tps.get(i)[1];
            }
            bpm = longestBpm(pos, bs, endMs / 1000.0);
        }
        if (audio.contains("/")) audio = audio.substring(audio.lastIndexOf('/') + 1);
        applyMetaTitle(song, title, artist);
        song.charts.add(new Chart(song, file, entry, version, audio, bpm, true));
    }

    /** BOM/UTF-16 感知解码（与 readChartText 相同规则）。 */
    public static String decodeText(byte[] data)
            throws java.io.UnsupportedEncodingException {
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, "UTF-16LE");
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, "UTF-16BE");
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, "UTF-8");
        }
        return new String(data, "UTF-8");
    }

    /** meta 里没有 audio 时，回落到 audio note：有 sound 且无 column 的 note
     *  （Malody 4.3.7 编辑器默认把 BGM 做成这种 note）。 */
    private static String firstNoteSound(JSONObject root) {
        try {
            JSONArray notes = root.optJSONArray("note");
            if (notes == null) return "";
            String any = "";
            for (int i = 0; i < notes.length(); i++) {
                JSONObject n = notes.optJSONObject(i);
                if (n == null) continue;
                String snd = n.optString("sound", "");
                if (snd.isEmpty()) continue;
                if (!n.has("column")) return snd;  // 标准音频 note
                if (any.isEmpty()) any = snd;      // 兜底：第一个带 sound 的
            }
            return any;
        } catch (Exception ignored) {}
        return "";
    }

    private static final java.util.regex.Pattern P_STR =
            java.util.regex.Pattern.compile("\"(version|difficulty|audio|music|title|artist|titleorg|artistorg)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** 只看开头 64KB 的 meta 字段；返回 {audio, version, bpm, title, artist}。 */
    private static String[] fastMeta(String text) {
        if (text.length() > 65536) text = text.substring(0, 65536);
        String head = text.trim();
        if (head.isEmpty() || head.charAt(0) != '{') return null;
        String version = null, audio = null, title = null, artist = null;
        java.util.regex.Matcher m = P_STR.matcher(head);
        while (m.find()) {
            String k = m.group(1), v = m.group(2);
            if (v.isEmpty()) continue;
            if ((k.equals("version") || k.equals("difficulty")) && version == null) version = v;
            else if ((k.equals("audio") || k.equals("music")) && audio == null) audio = v;
            else if (k.equals("title") && title == null) title = v;
            else if (k.equals("artist") && artist == null) artist = v;
            else if (k.equals("titleorg") && title == null) title = v;
            else if (k.equals("artistorg") && artist == null) artist = v;
        }
        String bpm = null;
        java.util.regex.Matcher mb = P_BPM.matcher(head);
        int bpmCount = 0;
        while (mb.find()) { bpmCount++; if (bpm == null) bpm = mb.group(1); }
        if (bpmCount > 1) return null; // 多 BPM 段：交给全量解析选持续时间最长的
        if (version == null && audio == null && bpm == null) return null;
        return new String[]{audio == null ? "" : audio, version == null ? "" : version,
                bpm == null ? "0" : bpm, title == null ? "" : title, artist == null ? "" : artist};
    }

    /** mc beat → bar 位置（标量=整 bar，[a,b]=a/b bar；非法返回 NaN）。 */
    private static double mcBeatToBars(Object beat) {
        if (beat instanceof JSONArray) {
            JSONArray a = (JSONArray) beat;
            if (a.length() < 2) return Double.NaN;
            double den = a.optDouble(1, 0);
            return a.optDouble(0, 0) / (den == 0 ? 1 : den);
        }
        if (beat instanceof Number) return ((Number) beat).doubleValue();
        return Double.NaN;
    }

    /** 选持续时间最长的 BPM 段；末段无有效终点则不参与，全空退回首段。 */
    private static double longestBpm(double[] pos, double[] bpm, double endPos) {
        if (pos == null || pos.length == 0) return 0;
        if (pos.length == 1) return bpm[0];
        int best = -1;
        double bestDur = 0;
        for (int i = 0; i < pos.length; i++) {
            double d;
            if (i + 1 < pos.length) d = pos[i + 1] - pos[i];
            else d = (endPos > pos[i]) ? endPos - pos[i] : 0;
            if (d > bestDur) { bestDur = d; best = i; }
        }
        return best < 0 ? bpm[0] : bpm[best];
    }

    // ------------------------------------------------------------------
    // 曲库缓存（JSON）
    // ------------------------------------------------------------------

    /**
     * 文件夹级指纹：所有含谱面文件（.mc/.osu/.mcz）的目录 → {文件名: "lastModified|length"}。
     * 只统计直接子项，不读文件内容。用于启动/打开文件夹时的增量对比。
     */
    public static Map<String, Map<String, String>> dirFps(File root) {
        Map<String, Map<String, String>> out = new java.util.LinkedHashMap<>();
        collectDirFps(root, out);
        return out;
    }

    /** 单个文件夹的指纹（仅直接子项；没有谱面文件返回 null）。 */
    public static Map<String, String> dirFp(File dir) {
        return dirFpInner(dir);
    }

    private static Map<String, String> dirFpInner(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        Map<String, String> fp = new java.util.LinkedHashMap<>();
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith("._") || n.equals(".ds_store")) continue; // macOS 元数据垃圾
            if (!f.isDirectory()
                    && (n.endsWith(".mc") || n.endsWith(".osu") || n.endsWith(".mcz"))) {
                fp.put(f.getName(), f.lastModified() + "|" + f.length());
            }
        }
        return fp;
    }

    private static void collectDirFps(File dir, Map<String, Map<String, String>> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Map<String, String> fp = dirFpInner(dir);
        if (fp != null && !fp.isEmpty()) out.put(dir.getAbsolutePath(), fp);
        for (File f : files) {
            if (!f.isDirectory()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.equals(".speedbox_work")) continue; // 自产工作目录不算谱面
            collectDirFps(f, out);
        }
    }

    /** 序列化扫描结果（含根目录与文件夹指纹），用于下次启动秒开与增量更新。 */
    public static String cacheToJson(List<Song> songs, String rootPath) {
        return cacheToJson(songs, rootPath, null);
    }

    public static String cacheToJson(List<Song> songs, String rootPath,
                                     Map<String, Map<String, String>> dirFps) {
        try {
            JSONArray arr = new JSONArray();
            for (Song song : songs) {
                JSONObject so = new JSONObject();
                so.put("dir", song.dir.getAbsolutePath());
                so.put("title", song.title);
                if (song.cover != null) so.put("cover", song.cover.getAbsolutePath());
                JSONArray cs = new JSONArray();
                for (Chart c : song.charts) {
                    JSONObject co = new JSONObject();
                    co.put("f", c.file.getAbsolutePath());
                    if (c.entry != null) co.put("e", c.entry);
                    co.put("v", c.version == null ? "" : c.version);
                    co.put("a", c.audio == null ? "" : c.audio);
                    co.put("b", c.bpm);
                    if (c.osu) co.put("o", 1);
                    cs.put(co);
                }
                so.put("charts", cs);
                arr.put(so);
            }
            JSONObject root = new JSONObject();
            root.put("root", rootPath);
            if (dirFps != null) {
                JSONObject dobj = new JSONObject();
                for (Map.Entry<String, Map<String, String>> de : dirFps.entrySet()) {
                    JSONObject fo = new JSONObject();
                    for (Map.Entry<String, String> e : de.getValue().entrySet())
                        fo.put(e.getKey(), e.getValue());
                    dobj.put(de.getKey(), fo);
                }
                root.put("dirs", dobj);
            }
            root.put("songs", arr);
            return root.toString();
        } catch (Exception e) {
            log("缓存序列化失败: " + e);
            return null;
        }
    }

    /** 从缓存恢复曲库；损坏返回 null。 */
    public static List<Song> cacheFromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            List<Song> out = new ArrayList<>();
            JSONArray arr = root.optJSONArray("songs");
            if (arr == null) return null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject so = arr.optJSONObject(i);
                if (so == null) continue;
                File dir = new File(so.getString("dir"));
                String cover = so.optString("cover", "");
                Song song = new Song(dir, cover.isEmpty() ? null : new File(cover),
                        so.optString("title", dir.getName()));
                JSONArray cs = so.optJSONArray("charts");
                for (int j = 0; cs != null && j < cs.length(); j++) {
                    JSONObject co = cs.optJSONObject(j);
                    if (co == null) continue;
                    String entry = co.optString("e", "");
                    song.charts.add(new Chart(song, new File(co.getString("f")),
                            entry.isEmpty() ? null : entry,
                            co.optString("v", ""), co.optString("a", ""),
                            co.optDouble("b", 0), co.optInt("o", 0) == 1));
                }
                out.add(song);
            }
            return out;
        } catch (Exception e) {
            log("缓存解析失败: " + e);
            return null;
        }
    }

    /** 元数据歌名覆盖文件夹名（首次生效）。 */
    private static void applyMetaTitle(Song song, String title, String artist) {
        if (title == null || title.isEmpty()) return;
        song.title = (artist != null && !artist.isEmpty())
                ? artist + " - " + title : title;
    }

    private static final java.util.regex.Pattern P_BPM =
            java.util.regex.Pattern.compile("\"bpm\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private static double parseBpm(String v) {
        try { return Double.parseDouble(v); } catch (Exception e) { return 0; }
    }

    private static String str(JSONObject o, String k) {
        String v = o == null ? "" : o.optString(k, "");
        return v == null ? "" : v.trim();
    }

    private static String firstNonEmpty(String... vs) {
        for (String v : vs) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static String firstNonEmptyUnused(String a, String b) {
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }

    private static File findCover(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        File fallback = null;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) {
                if (fallback == null) fallback = f;
                if (n.contains("bg") || n.contains("cover") || n.contains("jacket")) return f;
            }
        }
        return fallback;
    }

    /** 生成封面缩略图（失败返回 null）。 */
    public static Bitmap thumbnail(File img, int size) {
        if (img == null || !img.isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(img.getAbsolutePath(), o);
            int sample = 1;
            while (o.outWidth / sample > size * 2 || o.outHeight / sample > size * 2) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(img.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    public static String readChartText(File f) throws IOException {
        return decodeText(java.nio.file.Files.readAllBytes(f.toPath()));
    }

    public static String readChartText(Chart c) throws IOException {
        byte[] data = (c.entry == null)
                ? java.nio.file.Files.readAllBytes(c.file.toPath())
                : Mcz.readEntry(c.file, c.entry);
        // 与原版一致：UTF-8/BOM/UTF-16 头检测
        if (data.length >= 2 && ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE)) {
            return new String(data, 2, data.length - 2, "UTF-16LE");
        }
        if (data.length >= 2 && ((data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF)) {
            return new String(data, 2, data.length - 2, "UTF-16BE");
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, "UTF-8");
        }
        return new String(data, "UTF-8");
    }

    /** 定位音频资源，提取为临时文件（散装直接引用，mcz 解包）。 */
    public static File extractAudio(Chart c, File workDir) throws IOException {
        if (c.audio == null || c.audio.isEmpty()) {
            log("谱面未声明音频: " + c.label());
            return null;
        }
        if (c.entry == null) {
            File f = new File(c.song.dir, c.audio);
            if (f.isFile()) return f;
            // 大小写不敏感回退（Android 区分大小写，谱面常写错）
            File[] ls = c.song.dir.listFiles();
            if (ls != null) {
                for (File x : ls) {
                    if (x.getName().equalsIgnoreCase(c.audio)) {
                        log("音频大小写不一致，已按实际文件匹配: " + x.getName());
                        return x;
                    }
                }
            }
            log("找不到音频: " + new File(c.song.dir, c.audio).getAbsolutePath());
            return null;
        }
        for (String n : Mcz.listEntries(c.file)) {
            String base = n.substring(n.lastIndexOf('/') + 1);
            if (base.equals(c.audio)) {
                File tmp = new File(workDir, "au_" + System.nanoTime() + "_" + c.audio);
                java.nio.file.Files.write(tmp.toPath(), Mcz.readEntry(c.file, n));
                tmp.deleteOnExit();
                return tmp;
            }
        }
        return null;
    }

    public static File coverFile(Chart c) {
        return c.song.cover;
    }

    /** 把变速后的 .mc 文本中的音频引用改为新文件名，返回新文本。 */
    public static String renameMcAudio(String mcText, String oldAudio, String newAudio)
            throws IOException {
        try {
            JSONObject root = new JSONObject(mcText);
            // 1) audio note（note.sound == 旧音频名）→ 全部改写
            JSONArray notes = root.optJSONArray("note");
            if (notes != null && oldAudio != null && !oldAudio.isEmpty()) {
                for (int i = 0; i < notes.length(); i++) {
                    JSONObject n = notes.optJSONObject(i);
                    if (n == null) continue;
                    if (oldAudio.equals(n.optString("sound", ""))) n.put("sound", newAudio);
                }
            }
            // 2) meta/根级引用字段
            JSONObject meta = root.optJSONObject("meta");
            JSONObject song = meta == null ? null : meta.optJSONObject("song");
            JSONObject rootSong = root.optJSONObject("song");
            boolean touched = false;
            for (JSONObject o : new JSONObject[]{song, rootSong, meta, root}) {
                if (o == null) continue;
                for (String k : new String[]{"audio", "music", "file", "audio_file", "audioFilename"}) {
                    String v = o.optString(k, "").trim();
                    if (v.length() > 0 && (oldAudio == null || oldAudio.isEmpty()
                            || oldAudio.equals(v) || v.endsWith("/" + oldAudio))) {
                        o.put(k, newAudio);
                        touched = true;
                    }
                }
            }
            // 3) 完全没有 meta 引用（纯 audio note 谱）→ 补一个 meta.song.audio 保持双保险
            if (!touched) {
                if (meta == null) { meta = new JSONObject(); root.put("meta", meta); }
                if (song == null) { song = new JSONObject(); meta.put("song", song); }
                song.put("audio", newAudio);
            }
            return root.toString(2) + "\n";
        } catch (org.json.JSONException e) {
            throw new IOException("改写 .mc 音频引用失败: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 处理
    // ------------------------------------------------------------------

    /**
     * 执行变速打包。
     * @param charts 选中的版本
     * @param speeds 倍率列表
     * @param withOriginal true=原谱+全部倍速打包为一个 mcz；false=每版本每倍速单独 mcz
     */
    /** 输出模式：0=每倍速单独 mcz；1=原谱+全部倍速一个 mcz；2=散装文件放原文件夹 */
    public static final int MODE_MCZ_SEPARATE = 0;
    public static final int MODE_MCZ_BUNDLE = 1;
    public static final int MODE_LOOSE = 2;

    /** 按谱面自身 BPM 换算倍率；bpm 未知则跳过。 */
    private static double[] speedsFor(Chart c, String speedSpec, boolean bpmUnit) {
        double[] targets = parseNumbers(speedSpec, bpmUnit);
        if (!bpmUnit) return targets;
        if (c.bpm <= 0) return new double[0];
        double[] out = new double[targets.length];
        for (int i = 0; i < targets.length; i++) out[i] = targets[i] / c.bpm;
        return out;
    }

    public interface CancelToken {
        boolean isCanceled();
    }

    public static int processTask(TaskHub.Task task, Progress progress)
            throws IOException, org.json.JSONException {
        int made = processWithOptions(
                task.charts,
                task.speedSpec,
                task.bpmUnit,
                task.mode,
                task.lockPitch,
                task.pitchSemitones,
                () -> task.canceled,
                progress);
        synchronized (LAST_OUTPUTS) {
            task.outputs.clear();
            task.outputs.addAll(LAST_OUTPUTS);
        }
        synchronized (LAST_SHARE_FILES) {
            task.shareFiles.clear();
            task.shareFiles.addAll(LAST_SHARE_FILES);
        }
        return made;
    }

    public static int process(List<Chart> charts, String speedSpec, boolean bpmUnit, int mode,
                              Progress progress) throws IOException, org.json.JSONException {
        return processWithOptions(charts, speedSpec, bpmUnit, mode, true, 0, () -> false, progress);
    }

    public static int processWithOptions(List<Chart> charts, String speedSpec, boolean bpmUnit,
                                         int mode, boolean lockPitch, int pitchSemitones,
                                         CancelToken cancelToken, Progress progress)
            throws IOException, org.json.JSONException {
        File work = new File(charts.get(0).song.dir, ".speedbox_work");
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("无法创建工作目录");

        LAST_OUTPUTS.clear();
        LAST_SHARE_FILES.clear();
        final String pkgExt = shareExtension(charts);
        for (Chart c : charts) {
            if (speedsFor(c, speedSpec, bpmUnit).length == 0)
                log("跳过（BPM 未知，无法按 BPM 换算）: " + c.label());
        }
        try {
            java.util.Set<String> overwrite = null;
            if (mode == MODE_LOOSE && conflictResolver != null) {
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
                List<String> conflicts = new ArrayList<>();
                for (Chart c : charts) {
                    for (double s : speedsFor(c, speedSpec, bpmUnit)) {
                        String[] names = plannedNames(c, s, bpmUnit, lockPitch, pitchSemitones);
                        for (String n : names) {
                            if (seen.add(n) && new File(c.song.dir, n).isFile()) conflicts.add(n);
                        }
                    }
                }
                if (!conflicts.isEmpty()) {
                    overwrite = conflictResolver.resolve(conflicts);
                    if (overwrite != null && overwrite.isEmpty()) log("冲突处理：全部保留已有文件");
                }
            }

            final class Unit {
                final Chart c; final double s; final String origText;
                Unit(Chart c, double s, String t) { this.c = c; this.s = s; this.origText = t; }
            }
            List<Unit> units = new ArrayList<>();
            for (Chart c : charts) {
                String t = readChartText(c);
                for (double s : speedsFor(c, speedSpec, bpmUnit)) units.add(new Unit(c, s, t));
            }

            List<Unit> todo = new ArrayList<>();
            for (Unit u : units) {
                if (mode == MODE_LOOSE && overwrite != null && !overwrite.isEmpty()) {
                    String[] names = plannedNames(u.c, u.s, bpmUnit, lockPitch, pitchSemitones);
                    if (!overwrite.contains(names[0]) && !overwrite.contains(names[1])) {
                        log("保留已有，跳过: " + names[0]);
                        continue;
                    }
                }
                todo.add(u);
            }

            int workers = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));
            java.util.concurrent.ExecutorService pool =
                    todo.size() > 1 && workers > 1
                            ? java.util.concurrent.Executors.newFixedThreadPool(workers,
                                    r -> { Thread t = new Thread(r, "sb-audio"); t.setPriority(Thread.NORM_PRIORITY - 1); return t; })
                            : null;
            log(todo.size() > 1 && pool != null
                    ? "并行音频处理: " + workers + " 工作者 / " + todo.size() + " 个任务"
                    : "音频处理: " + todo.size() + " 个任务（单线程）");
            final int totalUnits = todo.size();
            final float[] unitFractions = new float[totalUnits];
            final java.util.concurrent.atomic.AtomicInteger doneA = new java.util.concurrent.atomic.AtomicInteger();

            // 音频处理是耗时主体：占 3% ~ 97%，其余阶段（谱面文本/打包）只占两端。
            final java.util.function.IntSupplier overallPct = () -> {
                float sum = 0f;
                synchronized (unitFractions) {
                    for (float f : unitFractions) sum += f;
                }
                float fraction = totalUnits > 0 ? sum / totalUnits : 1f;
                return Math.max(3, Math.min(97, 3 + Math.round(94f * fraction)));
            };

            SpeedResult[] arr = new SpeedResult[todo.size()];
            Exception firstErr = null;
            try {
                List<java.util.concurrent.Callable<SpeedResult>> calls = new ArrayList<>();
                for (int ui = 0; ui < totalUnits; ui++) {
                    final int unitIndex = ui;
                    final Unit u = todo.get(ui);
                    calls.add(() -> {
                        if (cancelToken != null && cancelToken.isCanceled()) {
                            throw new IOException("任务已取消");
                        }
                        int d = doneA.incrementAndGet();
                        progress.update("(" + d + "/" + totalUnits + ") 变速 "
                                + u.c.label() + " ×" + trimNum(u.s), overallPct.getAsInt());

                        java.util.function.DoubleConsumer onAudio = frac -> {
                            synchronized (unitFractions) {
                                unitFractions[unitIndex] = Math.max(unitFractions[unitIndex],
                                        (float) Math.max(0.0, Math.min(1.0, frac)));
                            }
                            progress.update("音频处理中… " + u.c.label() + " ×" + trimNum(u.s),
                                    overallPct.getAsInt());
                        };

                        SpeedResult result = speedChart(u.c, u.origText, u.s, bpmUnit,
                                lockPitch, pitchSemitones, work, onAudio);

                        synchronized (unitFractions) {
                            unitFractions[unitIndex] = 1f;
                        }
                        progress.update("(音频完成) " + u.c.label() + " ×" + trimNum(u.s),
                                overallPct.getAsInt());
                        return result;
                    });
                }
                if (pool == null) {
                    for (int i = 0; i < calls.size(); i++) {
                        if (cancelToken != null && cancelToken.isCanceled()) break;
                        try { arr[i] = calls.get(i).call(); }
                        catch (Exception e) { if (firstErr == null) firstErr = e; }
                    }
                } else {
                    List<java.util.concurrent.Future<SpeedResult>> fs = null;
                    try {
                        fs = pool.invokeAll(calls);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("音频处理被中断");
                    }
                    for (int i = 0; i < fs.size(); i++) {
                        try { arr[i] = fs.get(i).get(); }
                        catch (Exception e) {
                            if (firstErr == null)
                                firstErr = e instanceof java.util.concurrent.ExecutionException
                                        && e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                        }
                    }
                }
            } finally {
                if (pool != null) pool.shutdownNow();
            }

            if (cancelToken != null && cancelToken.isCanceled()) {
                return 0;
            }

            int made = 0;
            if (mode == MODE_MCZ_BUNDLE) {
                for (Chart c : charts) {
                    List<Mcz.Out> entries = new ArrayList<>();
                    File cover = coverFile(c);
                    if (cover != null) entries.add(new Mcz.Out(cover.getName(), cover));
                    if (c.audio != null && !c.audio.isEmpty()) {
                        File src = extractAudio(c, work);
                        if (src != null) entries.add(new Mcz.Out(c.audio, src));
                    }
                    String origText = null;
                    for (Unit u : units) if (u.c == c) { origText = u.origText; break; }
                    if (origText != null) {
                        String baseName = stripAllExt(c.entry != null ? c.entry : c.file.getName());
                        String origMcName = "[Original] " + safeName(baseName) + (c.osu ? ".osu" : ".mc");
                        entries.add(new Mcz.Out(origMcName, origText.getBytes("UTF-8")));
                    }
                    for (int i = 0; i < todo.size(); i++) {
                        if (arr[i] == null || todo.get(i).c != c) continue;
                        SpeedResult r = arr[i];
                        entries.add(new Mcz.Out(r.mcName, r.mcData));
                        entries.add(new Mcz.Out(r.audioName, r.audioFile));
                    }
                    File out = uniqueOut(bufferDir(), safeName(c.song.title) + " [含原谱+"
                            + speedSpec + (bpmUnit ? "bpm" : "x") + "]." + pkgExt);
                    Mcz.write(out, entries);
                    LAST_SHARE_FILES.add(out);
                    made++;
                }
            } else if (mode == MODE_LOOSE) {
                for (int i = 0; i < todo.size(); i++) {
                    if (arr[i] == null) continue;
                    SpeedResult r = arr[i];
                    Chart c = todo.get(i).c;
                    File mcOut = new File(c.song.dir, r.mcName);
                    try (FileOutputStream fo = new FileOutputStream(mcOut)) {
                        fo.write(r.mcData);
                    }
                    File audioOut = new File(c.song.dir, r.audioName);
                    try (FileOutputStream fo = new FileOutputStream(audioOut)) {
                        try (java.io.InputStream in = new java.io.FileInputStream(r.audioFile)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                        }
                    }
                    LAST_OUTPUTS.add(mcOut);
                    LAST_OUTPUTS.add(audioOut);

                    // 散装模式也要能“分享完整包”：按同一份产物即时打一个包
                    List<Mcz.Out> shareEntries = new ArrayList<>();
                    File cover = coverFile(c);
                    if (cover != null) shareEntries.add(new Mcz.Out(cover.getName(), cover));
                    shareEntries.add(new Mcz.Out(r.mcName, r.mcData));
                    shareEntries.add(new Mcz.Out(r.audioName, r.audioFile));
                    File pkg = uniqueOutInner(bufferDir(),
                            safeName(c.song.title) + " " + stripAllExt(r.mcName) + "." + pkgExt);
                    Mcz.write(pkg, shareEntries);
                    LAST_SHARE_FILES.add(pkg);
                    made++;
                }
            } else {
                for (int i = 0; i < todo.size(); i++) {
                    if (arr[i] == null) continue;
                    SpeedResult r = arr[i];
                    Chart c = todo.get(i).c;
                    List<Mcz.Out> entries = new ArrayList<>();
                    File cover = coverFile(c);
                    if (cover != null) entries.add(new Mcz.Out(cover.getName(), cover));
                    entries.add(new Mcz.Out(r.mcName, r.mcData));
                    entries.add(new Mcz.Out(r.audioName, r.audioFile));
                    File out = uniqueOut(bufferDir(),
                            safeName(c.song.title) + " " + stripAllExt(r.mcName) + "." + pkgExt);
                    Mcz.write(out, entries);
                    LAST_SHARE_FILES.add(out);
                    made++;
                }
            }
            if (firstErr != null) {
                if (firstErr instanceof IOException) throw (IOException) firstErr;
                if (firstErr instanceof org.json.JSONException) throw (org.json.JSONException) firstErr;
                throw new IOException("音频处理失败: " + firstErr.getMessage(), firstErr);
            }
            progress.update("完成：" + (mode == MODE_LOOSE
                    ? "已将新版本散装放入原谱面文件夹"
                    : "生成 " + made + " 个 mcz → " + bufferDir().getAbsolutePath()), 100);
            return made;
        } finally {
            deleteR(work);
        }
    }

    private static final class SpeedResult {
        String mcName;
        byte[] mcData;
        String audioName;
        File audioFile;
    }

    public interface ConflictResolver {
        java.util.Set<String> resolve(List<String> conflicts);
    }

    public static volatile ConflictResolver conflictResolver;

    static String chartLabel(Chart c, double speed, boolean bpmUnit) {
        return chartLabel(c, speed, bpmUnit, true, 0);
    }

    static String chartLabel(Chart c, double speed, boolean bpmUnit, boolean lockPitch, int pitchSemitones) {
        String base = (bpmUnit && c.bpm > 0)
                ? trimNum(speed * c.bpm) + "bpm"
                : trimNum(speed) + "x";
        String rateTag = lockPitch ? "" : "·NC";
        String pitchTag = pitchSemitones == 0 ? ""
                : (pitchSemitones > 0 ? "+" + pitchSemitones + "st" : pitchSemitones + "st");
        return "[" + base + rateTag + pitchTag + "]";
    }

    static String[] plannedNames(Chart c, double speed, boolean bpmUnit) {
        return plannedNames(c, speed, bpmUnit, true, 0);
    }

    static String[] plannedNames(Chart c, double speed, boolean bpmUnit, boolean lockPitch, int pitchSemitones) {
        String label = chartLabel(c, speed, bpmUnit, lockPitch, pitchSemitones);
        String chartBase = stripAllExt(c.entry != null ? c.entry : c.file.getName());
        String mcName = safeName(chartBase + " " + label) + (c.osu ? ".osu" : ".mc");
        String base = stripAllExt(c.audio == null ? "audio" : c.audio);
        String ext = c.audio == null ? "wav"
                : c.audio.substring(c.audio.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return new String[]{mcName, base + " " + label + "." + ext};
    }

    private static SpeedResult speedChart(Chart c, String origText, double speed, boolean bpmUnit,
                                          boolean lockPitch, int pitchSemitones, File work,
                                          java.util.function.DoubleConsumer audioProgress)
            throws IOException, org.json.JSONException {
        SpeedResult r = new SpeedResult();
        String[] names = plannedNames(c, speed, bpmUnit, lockPitch, pitchSemitones);
        String label = chartLabel(c, speed, bpmUnit, lockPitch, pitchSemitones);
        String newAudioName = names[1];

        String text;
        if (c.osu) {
            Object[] res = ChartSpeed.transformOsuText(origText, speed, "", null);
            text = (String) res[0];
        } else {
            Object[] res = ChartSpeed.transformMcText(origText, speed, "", null);
            text = (String) res[0];
        }

        String aKey = audioKey(c, speed) + "#lp=" + lockPitch + "#st=" + pitchSemitones;
        File cached = audioCache.get(aKey);
        if (cached == null || !cached.isFile()) {
            Object job = audioJobs.computeIfAbsent(aKey, k -> new Object());
            try {
                synchronized (job) {
                    cached = audioCache.get(aKey);
                    if (cached == null || !cached.isFile()) {
                        File src = extractAudio(c, work);
                        if (src == null) throw new IOException("找不到音频文件: " + c.audio);
                        File pcmWav = File.createTempFile("sb_pcm", ".wav", work);
                        File scaledWav = File.createTempFile("sb_scaled", ".wav", work);
                        final java.util.concurrent.atomic.AtomicBoolean watching =
                                new java.util.concurrent.atomic.AtomicBoolean(true);
                        Thread watcher = null;
                        try {
                            AudioPipeline.WavData wav = AudioPipeline.decodeToWav(src, pcmWav);
                            AudioPipeline.writeWav(pcmWav, wav.pcm, wav.sampleRate, wav.channels);
                            net.surina.soundtouch.SoundTouch st = new net.surina.soundtouch.SoundTouch();
                            if (lockPitch) {
                                st.setTempo((float) speed);
                            } else {
                                st.setSpeed((float) speed);
                            }
                            if (pitchSemitones != 0) {
                                st.setPitchSemiTones((float) pitchSemitones);
                            }

                            // SoundTouch.processFile 没有进度回调：轮询输出文件写入量，
                            // 换算成“已处理时长 / 总时长”作为音频内部进度。
                            long pcmBytes = Math.max(1, pcmWav.length() - 44);
                            double ratio = speed > 0 ? 1.0 / speed : 1.0;
                            long expectedBytes = Math.max(1, Math.round(pcmBytes * ratio));
                            watcher = new Thread(() -> {
                                while (watching.get()) {
                                    long written = scaledWav.length() - 44;
                                    if (written > 0) {
                                        audioProgress.accept(Math.min(0.99, (double) written / expectedBytes));
                                    }
                                    try {
                                        Thread.sleep(150);
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                }
                            }, "sb-audio-progress");
                            watcher.setDaemon(true);
                            watcher.start();

                            int rc = st.processFile(pcmWav.getAbsolutePath(),
                                    scaledWav.getAbsolutePath());
                            st.close();
                            if (rc != 0) throw new IOException("SoundTouch 处理失败: "
                                    + net.surina.soundtouch.SoundTouch.getErrorString());
                            AudioPipeline.WavData scaled = AudioPipeline.readWav(scaledWav);
                            String tag = "s" + Long.toHexString(
                                    Double.doubleToLongBits(speed))
                                    + "_" + (lockPitch ? "t" : "r") + "_" + pitchSemitones + "_";
                            cached = AudioPipeline.encode(scaled, tag + c.audio, work);
                        } finally {
                            watching.set(false);
                            if (watcher != null) watcher.interrupt();
                            pcmWav.delete();
                            scaledWav.delete();
                            if (c.entry != null && src != null) src.delete();
                        }
                        audioProgress.accept(1.0);
                        audioCache.put(aKey, cached);
                    }
                }
            } finally {
                audioJobs.remove(aKey);
            }
        }
        audioProgress.accept(1.0);
        r.audioFile = cached;
        r.audioName = newAudioName;

        if (c.osu) {
            text = renameOsuAudio(text, newAudioName);
            text = prependOsuVersion(text, label + " ");
            r.mcName = names[0];
        } else {
            text = renameMcAudio(text, c.audio, newAudioName);
            text = markVersion(text, label + " ");
            r.mcName = names[0];
        }
        r.mcData = text.getBytes("UTF-8");
        return r;
    }

    private static final Map<String, File> audioCache = new java.util.concurrent.ConcurrentHashMap<>();
    /** 在途转码任务锁：并行工作者遇到同 (音频, 倍率) 时只转一次，其余等结果。 */
    private static final Map<String, Object> audioJobs = new java.util.concurrent.ConcurrentHashMap<>();

    private static String audioKey(Chart c, double speed) {
        try {
            File src = (c.entry == null) ? new File(c.song.dir, c.audio) : c.file;
            return src.getAbsolutePath() + "#" + c.entry + "#" + speed + "#" + c.audio;
        } catch (Exception e) {
            return c.file.getAbsolutePath() + "#" + c.entry + "#" + speed;
        }
    }

    /** version 字段前加标记。 */
    /** 在 .osu 的 Version 字段值前面加标记。 */
    private static String prependOsuVersion(String osuText, String prefix) {
        String[] lines = osuText.split("\r?\n", -1);
        String section = "";
        StringBuilder sb = new StringBuilder(osuText.length() + 32);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();
            if (t.startsWith("[") && t.endsWith("]")) section = t;
            if (section.startsWith("[Metadata]") && t.startsWith("Version:")) {
                int colon = line.indexOf(':');
                sb.append(line, 0, colon + 1).append(' ')
                  .append(prefix).append(t.substring("Version:".length()).trim());
            } else {
                sb.append(line);
            }
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /** 改写 .osu 的 AudioFilename 行。 */
    private static String renameOsuAudio(String osuText, String newAudio) {
        StringBuilder sb = new StringBuilder(osuText.length() + 64);
        for (String line : osuText.split("\r?\n", -1)) {
            String t = line.trim();
            if (t.startsWith("AudioFilename:")) {
                sb.append("AudioFilename: ").append(newAudio);
            } else {
                sb.append(line);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String markVersion(String mcText, String marker) throws IOException {
        try {
            JSONObject root = new JSONObject(mcText);
            JSONObject meta = root.optJSONObject("meta");
            if (meta == null) { meta = new JSONObject(); root.put("meta", meta); }
            String v = meta.optString("version", "");
            if (v.isEmpty()) v = meta.optString("difficulty", "");
            meta.put("version", marker + (v.isEmpty() ? "Chart" : v));
            return root.toString(2) + "\n";
        } catch (org.json.JSONException e) {
            throw new IOException("改写 version 失败: " + e.getMessage());
        }
    }

    private static String stripAllExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    private static String safeName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
    }

    private static File uniqueOut(File dir, String name) {
        // 记录到 LAST_OUTPUTS（在返回前由调用处登记）
        File f = uniqueOutInner(dir, name);
        LAST_OUTPUTS.add(f);
        return f;
    }

    private static File uniqueOutInner(File dir, String name) {
        File f = new File(dir, name);
        String base = stripAllExt(name);
        String ext = name.substring(base.length());
        int i = 2;
        while (f.exists()) f = new File(dir, base + " (" + i++ + ")" + ext);
        return f;
    }

    private static void deleteR(File f) {
        File[] ls = f.listFiles();
        if (ls != null) for (File x : ls) { if (x.isDirectory()) deleteR(x); else x.delete(); }
        f.delete();
    }

    private static String trimNum(double v) {
        // 先四舍五入到两位小数，消掉浮点运算噪声（如 220.0000000000003bpm）
        double r = Math.round(v * 100.0) / 100.0;
        if (r == Math.rint(r)) return String.valueOf((long) r);
        return java.math.BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
    }

    public static double[] parseSpeeds(String input) {
        return parseNumbers(input, false);
    }

    /** bpmUnit=true 时校验目标 BPM 范围（20~1000），否则按倍率校验。 */
    public static double[] parseNumbers(String input, boolean bpmUnit) {
        Set<Double> set = new HashSet<>();
        for (String p : input.split("[,，;；\\s]+")) {
            if (p.trim().isEmpty()) continue;
            double v = Double.parseDouble(p.trim());
            if (bpmUnit) {
                if (v < 20 || v > 1000)
                    throw new IllegalArgumentException("BPM 无效: " + p + "（需在 20~1000）");
            } else {
                if (v < ChartSpeed.MIN_SPEED || v > ChartSpeed.MAX_SPEED || Math.abs(v - 1.0) < 1e-9) {
                    throw new IllegalArgumentException("倍率无效: " + p + "（需在 "
                            + ChartSpeed.MIN_SPEED + "~" + ChartSpeed.MAX_SPEED + " 且≠1）");
                }
            }
            set.add(v);
        }
        if (set.isEmpty()) throw new IllegalArgumentException("请至少输入一个倍率");
        double[] out = new double[set.size()];
        int i = 0;
        for (Double d : set) out[i++] = d;
        Arrays.sort(out);
        return out;
    }

    // 抑制未用警告
    @SuppressWarnings("unused")
    private static JSONArray unused(JSONArray a) { return a; }
}
