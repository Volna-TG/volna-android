package app.volna;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

public final class UpdateCheckJob extends JobService {
    static final String OPEN_UPDATE = "app.volna.OPEN_UPDATE";
    private static final String CHANNEL = "app_updates";
    private static final int JOB_ID = 4172;
    private static final int NOTIFICATION_ID = 4173;
    private static final long INTERVAL = 12 * 60 * 60 * 1000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;

    static void schedule(Context context) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Обновления Волны", NotificationManager.IMPORTANCE_DEFAULT));
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs.getPendingJob(JOB_ID) != null) return;
        jobs.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateCheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(INTERVAL)
                .setPersisted(true)
                .build());
    }

    static void clearNotification(Context context) {
        context.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    @Override public boolean onStartJob(JobParameters params) {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = notifications.getNotificationChannel(CHANNEL);
        if (!notifications.areNotificationsEnabled() || channel == null ||
                channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        String site = Updates.site(this);
        worker = new Thread(() -> {
            Thread current = Thread.currentThread();
            try {
                Updates.Release release = Updates.check(this);
                main.post(() -> complete(params, current, site, release, false));
            } catch (Updates.NoPatch unavailable) {
                main.post(() -> complete(params, current, site, null, false));
            } catch (Exception failed) {
                main.post(() -> complete(params, current, site, null, true));
            }
        }, "volna-update-check");
        worker.start();
        return true;
    }

    private void complete(JobParameters params, Thread current, String site, Updates.Release release, boolean failed) {
        if (worker != current) return;
        worker = null;
        try {
            if (!failed && site.equals(Updates.site(this))) {
                if (release == null) clearNotification(this);
                else notifyUpdate(site, release);
            }
        } finally {
            jobFinished(params, false);
        }
    }

    private void notifyUpdate(String site, Updates.Release release) {
        SharedPreferences prefs = Prefs.get(this);
        String key = site + "/" + release.code;
        if (key.equals(prefs.getString("notified_update", ""))) return;
        Intent view = new Intent(this, MainActivity.class).setAction(OPEN_UPDATE)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, JOB_ID, view,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Доступна Волна " + release.version)
                .setContentText("Открыть экран обновления")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (!notifications.areNotificationsEnabled()) return;
        notifications.notify(NOTIFICATION_ID, notice);
        prefs.edit().putString("notified_update", key).apply();
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopWorker();
        return true;
    }

    @Override public void onDestroy() {
        stopWorker();
        super.onDestroy();
    }

    private void stopWorker() {
        if (worker != null) worker.interrupt();
        worker = null;
        main.removeCallbacksAndMessages(null);
    }
}
