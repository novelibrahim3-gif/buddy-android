package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** One-shot, visible notification approval for screen coordinates in Accessibility-off mode. */
final class RootApproval {
    static final String CHANNEL = "buddy_root_approval";
    static final String ACTION_ANSWER = "app.buddy.assistant.ROOT_APPROVAL";
    static final String EXTRA_ID = "approval_id";
    static final String EXTRA_ALLOW = "allow";
    private static final AtomicInteger IDS = new AtomicInteger(20000);
    private static final ConcurrentHashMap<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 90_000;

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean allow;
    }

    private RootApproval() {
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Buddy screen action approvals", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Approve or deny one Buddy action at a time.");
        manager.createNotificationChannel(channel);
    }

    static String request(Context context, String summary) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return "UNAVAILABLE";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return "UNAVAILABLE";
            }
        }
        int id = IDS.incrementAndGet();
        Pending pending = new Pending();
        PENDING.put(id, pending);
        try {
            Intent approve = new Intent(context, RootApprovalReceiver.class)
                    .setAction(ACTION_ANSWER).putExtra(EXTRA_ID, id).putExtra(EXTRA_ALLOW, true);
            Intent deny = new Intent(context, RootApprovalReceiver.class)
                    .setAction(ACTION_ANSWER).putExtra(EXTRA_ID, id).putExtra(EXTRA_ALLOW, false);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent yes = PendingIntent.getBroadcast(context, id * 2, approve, flags);
            PendingIntent no = PendingIntent.getBroadcast(context, id * 2 + 1, deny, flags);
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Approve Buddy screen action?")
                    .setContentText(summary)
                    .setStyle(new Notification.BigTextStyle().bigText(summary
                            + "\nThis approval applies to one action only."))
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setTimeoutAfter(TIMEOUT_MS)
                    .addAction(new Notification.Action.Builder(null, "Approve once", yes).build())
                    .addAction(new Notification.Action.Builder(null, "Deny", no).build())
                    .build();
            manager.notify(id, notification);
            boolean answered = pending.latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!answered) return "TIMED OUT";
            return pending.allow ? "APPROVED" : "DENIED";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        } finally {
            PENDING.remove(id);
            manager.cancel(id);
        }
    }

    static void resolve(int id, boolean allow) {
        Pending pending = PENDING.get(id);
        if (pending == null) return;
        pending.allow = allow;
        pending.latch.countDown();
    }
}
