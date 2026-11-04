package com.speedbox;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 任务状态中心：支持多任务并发入队、独立进度百分比、取消控制与输出文件关联。
 */
public final class TaskHub {

    public static final class Task {
        public final String id;
        public final String name;
        public final List<Library.Chart> charts;
        public final String speedSpec;
        public final boolean bpmUnit;
        public final int mode;
        public final boolean lockPitch;
        public final int pitchSemitones;
        public final long createdAt;

        public volatile String status;
        /** 状态枚举：QUEUED / RUN / DONE / FAIL / CANCELED */
        public volatile String state;
        public volatile int progress; // 0 ~ 100
        public volatile boolean canceled;
        public volatile boolean refreshLibrary;
        public final List<File> outputs = Collections.synchronizedList(new ArrayList<>());
        /** 可直接分享的完整包（.osz 优先；含 .mc 时才是 .mcz）。 */
        public final List<File> shareFiles = Collections.synchronizedList(new ArrayList<>());

        Task(String name, List<Library.Chart> charts, String speedSpec,
             boolean bpmUnit, int mode, boolean lockPitch, int pitchSemitones) {
            this.id = "T" + System.nanoTime();
            this.name = name;
            this.charts = new ArrayList<>(charts);
            this.speedSpec = speedSpec;
            this.bpmUnit = bpmUnit;
            this.mode = mode;
            this.lockPitch = lockPitch;
            this.pitchSemitones = pitchSemitones;
            this.createdAt = System.currentTimeMillis();
            this.status = "排队等待中…";
            this.state = "QUEUED";
            this.progress = 0;
        }
    }

    /** UI 展示用的任务历史列表（最新在最前）。 */
    public static final List<Task> TASKS = Collections.synchronizedList(new ArrayList<>());

    /** 待后台服务消费的 FIFO 队列。 */
    private static final ConcurrentLinkedQueue<Task> QUEUE = new ConcurrentLinkedQueue<>();

    public static synchronized Task submit(String name, List<Library.Chart> charts,
                                           String speedSpec, boolean bpmUnit, int mode) {
        return submit(name, charts, speedSpec, bpmUnit, mode, true, 0);
    }

    public static synchronized Task submit(String name, List<Library.Chart> charts,
                                           String speedSpec, boolean bpmUnit, int mode,
                                           boolean lockPitch, int pitchSemitones) {
        Task t = new Task(name, charts, speedSpec, bpmUnit, mode, lockPitch, pitchSemitones);
        TASKS.add(0, t);
        QUEUE.offer(t);
        return t;
    }

    public static Task pollNext() {
        while (true) {
            Task t = QUEUE.poll();
            if (t == null) return null;
            if (!t.canceled) return t;
            t.state = "CANCELED";
            t.status = "已取消";
        }
    }

    public static boolean hasPending() {
        return !QUEUE.isEmpty();
    }

    public static void cancelTask(Task t) {
        if (t == null) return;
        t.canceled = true;
        if ("QUEUED".equals(t.state)) {
            t.state = "CANCELED";
            t.status = "已在排队中取消";
        } else if ("RUN".equals(t.state)) {
            t.status = "正在取消当前任务…";
        }
    }

    public static int countActive() {
        int c = 0;
        synchronized (TASKS) {
            for (Task t : TASKS) {
                if ("RUN".equals(t.state) || "QUEUED".equals(t.state)) c++;
            }
        }
        return c;
    }

    public static void clearFinished() {
        synchronized (TASKS) {
            TASKS.removeIf(t -> "DONE".equals(t.state)
                    || "FAIL".equals(t.state)
                    || "CANCELED".equals(t.state));
        }
    }

    private TaskHub() {}
}
