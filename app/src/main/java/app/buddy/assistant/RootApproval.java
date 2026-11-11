package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.app.RemoteInput;
import android.os.Bundle;
import java.util.List;

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
    static final String EXTRA_ANSWER = "answer";
    static final String EXTRA_REPLY = "typed_answer";
    private static final AtomicInteger IDS = new AtomicInteger(20000);
    private static final ConcurrentHashMap<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 90_000;
    private static final long QUESTION_TIMEOUT_MS = 5 * 60_000;

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean allow;
        volatile String answer;
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
                    .setContentTitle("Approve Buddy action?")
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

    /**
     * Ask a question using notification action buttons and an optional typed RemoteInput reply.
     * Returns the exact selected/typed answer, or null when notifications are unavailable or time out.
     */
    static String requestChoice(Context context, String question, List<String> options) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return null;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return null;
        }
        int id = IDS.incrementAndGet();
        Pending pending = new Pending();
        PENDING.put(id, pending);
        try {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Buddy asks")
                    .setContentText(question)
                    .setStyle(new Notification.BigTextStyle().bigText(question
                            + "\\nChoose an answer or use Reply to type your own."))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setTimeoutAfter(QUESTION_TIMEOUT_MS);
            for (int i = 0; i < options.size(); i++) {
                String option = options.get(i);
                Intent choice = new Intent(context, RootApprovalReceiver.class)
                        .setAction(ACTION_ANSWER).putExtra(EXTRA_ID, id).putExtra(EXTRA_ANSWER, option);
                PendingIntent action = PendingIntent.getBroadcast(context, id * 8 + i, choice, flags);
                builder.addAction(new Notification.Action.Builder(null, option, action).build());
            }
            Intent reply = new Intent(context, RootApprovalReceiver.class)
                    .setAction(ACTION_ANSWER).putExtra(EXTRA_ID, id);
            int replyFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent replyAction = PendingIntent.getBroadcast(context, id * 8 + 7, reply, replyFlags);
            RemoteInput input = new RemoteInput.Builder(EXTRA_REPLY).setLabel("Type your answer").build();
            builder.addAction(new Notification.Action.Builder(null, "Reply", replyAction)
                    .addRemoteInput(input).build());
            Intent dismissed = new Intent(context, RootApprovalReceiver.class)
                    .setAction(ACTION_ANSWER).putExtra(EXTRA_ID, id).putExtra(EXTRA_ANSWER, "");
            PendingIntent deleteAction = PendingIntent.getBroadcast(context, id * 8 + 6, dismissed, flags);
            builder.setDeleteIntent(deleteAction);
            manager.notify(id, builder.build());
            boolean answered = pending.latch.await(QUESTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return answered ? pending.answer : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            PENDING.remove(id);
            manager.cancel(id);
        }
    }

    static void resolveAnswer(int id, String answer) {
        Pending pending = PENDING.get(id);
        if (pending == null || answer == null) return;
        pending.answer = answer.trim();
        pending.latch.countDown();
    }

    static void resolve(int id, boolean allow) {
        Pending pending = PENDING.get(id);
        if (pending == null) return;
        pending.allow = allow;
        pending.latch.countDown();
    }
}
