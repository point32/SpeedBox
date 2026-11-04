package com.speedbox.audio;

import de.sciss.jump3r.lowlevel.LameEncoder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import javax.sound.sampled.AudioFormat;

/**
 * MP3 编码：jump3r（LAME 的纯 Java 移植，LGPL）。
 */
public final class Mp3Codec {

    private Mp3Codec() {}

    public static void encode(AudioPipeline.WavData wav, File out) throws Exception {
        AudioFormat src = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                wav.sampleRate, 16, wav.channels,
                wav.channels * 2, wav.sampleRate, false);
        LameEncoder encoder = new LameEncoder(src, 192, LameEncoder.CHANNEL_MODE_AUTO,
                LameEncoder.QUALITY_MIDDLE, false);
        byte[] pcm = wav.pcm;
        int pcmBuf = encoder.getPCMBufferSize();
        byte[] mp3Buf = new byte[encoder.getMP3BufferSize()];
        try (OutputStream os = new FileOutputStream(out)) {
            int pos = 0;
            while (pos < pcm.length) {
                int chunk = Math.min(pcmBuf, pcm.length - pos);
                int written = encoder.encodeBuffer(pcm, pos, chunk, mp3Buf);
                if (written > 0) os.write(mp3Buf, 0, written);
                pos += chunk;
            }
            int tail = encoder.encodeFinish(mp3Buf);
            if (tail > 0) os.write(mp3Buf, 0, tail);
        }
        encoder.close();
    }
}
