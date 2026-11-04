package com.speedbox;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * .mcz 读写：Malody 谱面包（zip 容器）。
 */
public final class Mcz {

    private Mcz() {}

    /** 列出 mcz 内所有条目名。 */
    public static List<String> listEntries(File mcz) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(mcz)) {
            var en = zip.entries();
            while (en.hasMoreElements()) names.add(en.nextElement().getName());
        }
        return names;
    }

    /** 列出 mcz 内的 .mc/.osu 谱面条目。 */
    public static List<String> listMcEntries(File mcz) throws IOException {
        List<String> out = new ArrayList<>();
        for (String n : listEntries(mcz)) {
            if (n.toLowerCase().endsWith(".mc") || n.toLowerCase().endsWith(".osu")) out.add(n);
        }
        return out;
    }

    public static byte[] readEntry(File mcz, String entry) throws IOException {
        try (ZipFile zip = new ZipFile(mcz)) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) throw new IOException("mcz 内无条目: " + entry);
            try (InputStream in = zip.getInputStream(e)) {
                return readAll(in);
            }
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** 待写入 mcz 的一个条目。 */
    public static final class Out {
        public final String name;
        public final byte[] data;   // 二选一
        public final File file;     // 二选一
        public Out(String name, byte[] data) { this.name = name; this.data = data; this.file = null; }
        public Out(String name, File file) { this.name = name; this.data = null; this.file = file; }
    }

    /** 打包 mcz（同名条目后者覆盖前者，保序）。 */
    public static void write(File out, List<Out> entries) throws IOException {
        Map<String, Out> map = new LinkedHashMap<>();
        for (Out o : entries) map.put(o.name, o);
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            for (Out o : map.values()) {
                zos.putNextEntry(new ZipEntry(o.name));
                if (o.data != null) {
                    zos.write(o.data);
                } else {
                    try (InputStream in = new FileInputStream(o.file)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    public static void write(File out, OutputStream unusedSink, List<Out> entries) throws IOException {
        write(out, entries);
    }
}
