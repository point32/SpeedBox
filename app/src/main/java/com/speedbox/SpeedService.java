package com.speedbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * 前台服务：循环消费 TaskHub 队列中的所有待处理任务，实时同步进度到 Task 与系统通知栏。
 */
public class SpeedService extends Service {

    private static final String CH = "speedbox_tasks";
    private static final int NOTIF_ID = 1;

    private HandlerThread worker;
    private Handler handler;
    private NotificationManager nm;
    private volatile boolean loopRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH, "变速任务", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        worker = new HandlerThread("sb-service");
        worker.start();
        handler = new Handler(worker.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, build("正在准备变速任务…", 0, 100));
        if (!loopRunning) {
            loopRunning = true;
            handler.post(this::consumeQueueLoop);
        }
        return START_NOT_STICKY;
    }

    private void consumeQueueLoop() {
        try {
            TaskHub.Task task;
            while ((task = TaskHub.pollNext()) != null) {
                final TaskHub.Task cur = task;
                if (cur.canceled) {
                    cur.state = "CANCELED";
                    cur.status = "已取消";
                    continue;
                }
                cur.state = "RUN";
                cur.progress = 2;
                cur.status = "正在初始化音频管线…";
                long t0 = System.currentTimeMillis();

                try {
                    int n = Library.processTask(cur, new Library.Progress() {
                        private String lastLogged = "";

                        @Override
                        public void update(String message) {
                            update(message, cur.progress);
                        }

                        @Override
                        public void update(String message, int percent) {
                            cur.status = message;
                            // 进度只增不减（并行任务取聚合值）
                            cur.progress = Math.max(cur.progress, Math.max(0, Math.min(99, percent)));
                            // 音频轮询每 150ms 回调一次，只记录文本真正变化的阶段，避免刷屏
                            if (message != null && !message.equals(lastLogged)) {
                                Library.log(message);
                                lastLogged = message;
                            }
                            updateNotif(cur.name + " · " + message, 100, cur.progress);
                        }
                    });

                    if (cur.canceled) {
                        cur.state = "CANCELED";
                        cur.status = "任务已中止";
                    } else {
                        long sec = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
                        cur.state = "DONE";
                        cur.progress = 100;
                        cur.status = "完成：生成 " + n
                                + (cur.mode == Library.MODE_LOOSE ? " 个新版本" : " 个 mcz")
                                + "（耗时 " + sec + "s）";
                        cur.refreshLibrary = (cur.mode == Library.MODE_LOOSE);
                    }
                    Library.log("任务结束: " + cur.name + " -> " + cur.status);
                } catch (Exception e) {
                    Library.log("任务失败: " + cur.name + " -> " + e);
                    if (cur.canceled) {
                        cur.state = "CANCELED";
                        cur.status = "任务已取消";
                    } else {
                        cur.state = "FAIL";
                        cur.status = "失败: " + e.getMessage();
                    }
                }
                nm.notify(NOTIF_ID, build(cur.status, 100, 100));
            }
        } finally {
            loopRunning = false;
            stopSelf();
        }
    }

    /** 从 "(3/9) 变速..." 解析为 0~95 的进度百分比。 */
    private int parseProgressPercent(String msg, int fallback) {
        try {
            int a = msg.indexOf('('), slash = msg.indexOf('/', a), b = msg.indexOf(')', slash);
            if (a >= 0 && slash > a && b > slash) {
                int done = Integer.parseInt(msg.substring(a + 1, slash).trim());
                int total = Integer.parseInt(msg.substring(slash + 1, b).trim());
                if (total > 0) return Math.min(95, Math.max(5, (done * 90) / total));
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private Notification build(String text, int progress, int max) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("SpeedBox 谱面变速")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (max > 0) b.setProgress(max, progress, false);
        return b.build();
    }

    private void updateNotif(String text, int max, int progress) {
        nm.notify(NOTIF_ID, build(text, max, progress));
    }

    @Override
    public void onDestroy() {
        worker.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
