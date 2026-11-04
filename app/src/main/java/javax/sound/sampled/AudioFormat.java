package javax.sound.sampled;

/**
 * 极简 AudioFormat shim：Android 没有 javax.sound，但 jump3r 的 API 需要。
 * 仅提供 jump3r 实际用到的字段与访问器。
 */
public class AudioFormat {

    public static class Encoding {
        public static final Encoding PCM_SIGNED = new Encoding("PCM_SIGNED");
        public static final Encoding PCM_UNSIGNED = new Encoding("PCM_UNSIGNED");
        private final String name;
        public Encoding(String name) { this.name = name; }
        public String toString() { return name; }
        public boolean equals(Object o) {
            return o instanceof Encoding && ((Encoding) o).name.equals(this.name);
        }
        public int hashCode() { return name.hashCode(); }
    }

    public static final Encoding PCM_SIGNED = new Encoding("PCM_SIGNED");
    public static final Encoding PCM_UNSIGNED = new Encoding("PCM_UNSIGNED");

    private final Encoding encoding;
    private final float sampleRate;
    private final int sampleSizeInBits;
    private final int channels;
    private final int frameSize;
    private final float frameRate;
    private final boolean bigEndian;

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits,
                       int channels, int frameSize, float frameRate, boolean bigEndian) {
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.frameSize = frameSize;
        this.frameRate = frameRate;
        this.bigEndian = bigEndian;
    }

    public AudioFormat(float sampleRate, int sampleSizeInBits, int channels,
                       boolean signed, boolean bigEndian) {
        this(signed ? PCM_SIGNED : PCM_UNSIGNED, sampleRate, sampleSizeInBits,
                channels, (sampleSizeInBits / 8) * channels, sampleRate, bigEndian);
    }

    public Encoding getEncoding() { return encoding; }
    public float getSampleRate() { return sampleRate; }
    public int getSampleSizeInBits() { return sampleSizeInBits; }
    public int getChannels() { return channels; }
    public int getFrameSize() { return frameSize; }
    public float getFrameRate() { return frameRate; }
    public boolean isBigEndian() { return bigEndian; }
}
