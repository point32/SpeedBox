package com.speedbox.audio;

import biniu.ogg.Packet;
import biniu.ogg.Page;
import biniu.ogg.StreamState;
import biniu.vorbis.Block;
import biniu.vorbis.Comment;
import biniu.vorbis.DspState;
import biniu.vorbis.Info;
import biniu.vorbis.VorbisEnc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Random;

/**
 * OGG Vorbis 编码：biniu / JOrbis 纯 Java libvorbis 移植。
 * 许可：GNU Library General Public License v2+（LGPL），
 * Copyright (C) 2000 ymnk, JCraft, Inc.；算法源自 Xiph.Org libvorbis（BSD）。
 * 完整许可证见 assets/licenses/ 与 THIRD_PARTY_LICENSES.md。
 */
public final class OggCodec {

    private static final int READ = 1024;

    private OggCodec() {}

    public static void encode(AudioPipeline.WavData wav, File out) throws Exception {
        final int channels = wav.channels;
        final int rate = wav.sampleRate;
        final byte[] pcm = wav.pcm;

        Info info = new Info();
        Comment comment = new Comment();
        DspState dsp = new DspState();
        Block block;
        StreamState state = new StreamState();
        Page page = new Page();
        Packet packet = new Packet();

        try (OutputStream os = new FileOutputStream(out)) {
            info.init();
            VorbisEnc encoder = new VorbisEnc();
            int ret = encoder.initVBR(info, channels, rate, 0.1f);
            if (ret != 0) throw new IllegalStateException("initVBR 失败: " + ret);

            comment.init();
            comment.addTag("ENCODER", "SpeedBox (biniu jVorbisEnc)");

            dsp.analysisInit(info);
            block = new Block(dsp);
            block.blockInit(dsp);

            Random rand = new Random(System.currentTimeMillis());
            state.init(rand.nextInt());

            boolean eos = false;
            {
                Packet h = new Packet(), hc = new Packet(), hk = new Packet();
                dsp.analysisHeaderOut(comment, h, hc, hk);
                state.packetIn(h);
                state.packetIn(hc);
                state.packetIn(hk);
                while (state.flush(page)) {
                    os.write(page.header_base, page.header, page.header_len);
                    os.write(page.body_base, page.body, page.body_len);
                }
            }

            final int frameBytes = channels * 2;
            int pos = 0;
            while (!eos) {
                int bytes;
                if (pos < pcm.length) {
                    bytes = Math.min(READ * frameBytes, pcm.length - pos);
                    float[][] buffer = dsp.analysisBuffer(READ);
                    for (int i = 0, l = dsp.pcm_current; i < bytes / frameBytes; i++, l++) {
                        for (int c = 0; c < channels; c++) {
                            int b = pos + i * frameBytes + c * 2;
                            buffer[c][l] =
                                    ((pcm[b + 1] << 8) | (0x00ff & pcm[b])) / 32768.f;
                        }
                    }
                    pos += bytes;
                    dsp.analysisWrote(bytes / frameBytes);
                } else {
                    dsp.analysisWrote(0);
                }

                while (block.analysisBlockOut()) {
                    block.analysis(null);
                    block.bitrateAddBlock();
                    while (dsp.bitrateFlushPacket(packet)) {
                        state.packetIn(packet);
                        while (!eos) {
                            if (!state.pageOut(page)) break;
                            os.write(page.header_base, page.header, page.header_len);
                            os.write(page.body_base, page.body, page.body_len);
                            if (page.eos()) eos = true;
                        }
                    }
                }
            }

            state.clear();
            block.clear();
            dsp.clear();
            comment.clear();
            info.clear();
        }
    }
}
