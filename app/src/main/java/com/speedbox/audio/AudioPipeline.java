package com.speedbox.audio;

import android.content.ContentResolver;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 音频管线：输入(mp3/ogg/m4a/wav…) → 解码为 16-bit PCM WAV 临时文件 →
 * （外部调用 SoundTouch processFile 得到变速后的 WAV）→ 按原格式编码：
 * mp3 → jump3r；ogg → biniu/vorbis；wav → 保留；其它 → WAV 兜底。
 */
public final class AudioPipeline {

    private AudioPipeline() {}

    public static final class WavData {
        public final byte[] pcm;      // 16-bit interleaved little-endian
        public final int sampleRate;
        public final int channels;
        public WavData(byte[] pcm, int sampleRate, int channels) {
            this.pcm = pcm; this.sampleRate = sampleRate; this.channels = channels;
        }
    }

    // ------------------------------------------------------------------
    // 解码
    // ------------------------------------------------------------------

    /** File 直读重载。 */
    public static WavData decodeToWav(File input, File tmpWav) throws IOException {
        return decodeFd(input.getAbsolutePath(), tmpWav);
    }

    /** 任意输入解码为 PCM WAV 临时文件（供 SoundTouch processFile 使用）。 */
    public static WavData decodeToWav(Uri input, ContentResolver resolver, File tmpWav)
            throws IOException {
        return decodeFd(resolver.openFileDescriptor(input, "r").getFileDescriptor(), tmpWav);
    }

    private static WavData decodeFd(Object dataSource, File tmpWav) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            if (dataSource instanceof java.io.FileDescriptor) {
                extractor.setDataSource((java.io.FileDescriptor) dataSource);
            } else {
                extractor.setDataSource(String.valueOf(dataSource));
            }
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i; fmt = f; break;
                }
            }
            if (track < 0) throw new IOException("未找到音频轨");
            extractor.selectTrack(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long timeout = 10_000;
            boolean inputEos = false;
            int loops = 0;
            while (!outputEos(info) && loops++ < 1_000_000) {
                if (!inputEos) {
                    int inIdx = codec.dequeueInputBuffer(timeout);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        int n = extractor.readSampleData(ib, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, timeout);
                if (outIdx >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        byte[] chunk = new byte[info.size];
                        ob.position(info.offset);
                        ob.get(chunk);
                        pcm.write(chunk, 0, info.size);
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            codec.stop();
            return new WavData(pcm.toByteArray(), sampleRate, channels);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("解码失败: " + e.getMessage(), e);
        } finally {
            if (codec != null) { try { codec.release(); } catch (Exception ignored) {} }
            extractor.release();
        }
    }

    private static boolean outputEos(MediaCodec.BufferInfo info) {
        return (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
    }

    // ------------------------------------------------------------------
    // WAV 读写
    // ------------------------------------------------------------------

    public static void writeWav(File out, byte[] pcm, int sampleRate, int channels)
            throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(wavHeader(pcm.length, sampleRate, channels));
            os.write(pcm);
        }
    }

    private static byte[] wavHeader(int pcmLen, int rate, int channels) {
        int blockAlign = channels * 2;
        int byteRate = rate * blockAlign;
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes());
        h.putInt(36 + pcmLen);
        h.put("WAVE".getBytes());
        h.put("fmt ".getBytes());
        h.putInt(16);
        h.putShort((short) 1);          // PCM
        h.putShort((short) channels);
        h.putInt(rate);
        h.putInt(byteRate);
        h.putShort((short) blockAlign);
        h.putShort((short) 16);         // bits
        h.put("data".getBytes());
        h.putInt(pcmLen);
        return h.array();
    }

    /** 读取 16-bit PCM WAV（SoundTouch 输出），兼容 18/40 字节扩展 fmt。 */
    public static WavData readWav(File f) throws IOException {
        byte[] all = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int pos = 0, n;
            while (pos < all.length && (n = in.read(all, pos, all.length - pos)) > 0) pos += n;
        }
        if (all.length < 44 || !new String(all, 0, 4).equals("RIFF"))
            throw new IOException("SoundTouch 输出不是有效 WAV");
        int rate = ByteBuffer.wrap(all, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int channels = ByteBuffer.wrap(all, 22, 2).order(ByteOrder.LITTLE_ENDIAN)
                .getShort() & 0xFFFF;
        // 找 data 块
        int pos = 12;
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4);
            int size = ByteBuffer.wrap(all, pos + 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (id.equals("data")) {
                int len = Math.min(size, all.length - pos - 8);
                byte[] pcm = new byte[len];
                System.arraycopy(all, pos + 8, pcm, 0, len);
                return new WavData(pcm, rate, channels);
            }
            pos += 8 + size + (size & 1);
        }
        throw new IOException("WAV 中未找到 data 块");
    }

    // ------------------------------------------------------------------
    // 编码（按原格式分发）
    // ------------------------------------------------------------------

    /** 按原文件名后缀编码；无法支持的原格式回退为同名 .wav。返回实际输出文件。 */
    public static File encode(WavData wav, String originalName, File dir) throws IOException {
        String lower = originalName.toLowerCase();
        try {
            if (lower.endsWith(".wav")) {
                File out = new File(dir, originalName);
                writeWav(out, wav.pcm, wav.sampleRate, wav.channels);
                return out;
            } else if (lower.endsWith(".mp3")) {
                File out = new File(dir, originalName);
                Mp3Codec.encode(wav, out);
                return out;
            } else if (lower.endsWith(".ogg")) {
                File out = new File(dir, originalName);
                OggCodec.encode(wav, out);
                return out;
            }
        } catch (Exception e) {
            // 编码失败回退 WAV
        }
        String fallback = stripExt(originalName) + ".wav";
        File out = new File(dir, fallback);
        writeWav(out, wav.pcm, wav.sampleRate, wav.channels);
        return out;
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }
}
