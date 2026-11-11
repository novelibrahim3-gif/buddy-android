package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.app.RemoteInput;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** One-shot notification approvals and questions for Accessibility-off mode. */
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
        volatile boolean auto;
    }

    private RootApproval() {
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Buddy action approvals", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Approve or deny one Buddy action at a time.");
        manager.createNotificationChannel(channel);
    }

    static String request(Context context, String summary) {
        return request(context, summary, TIMEOUT_MS, 0);
    }

    static String request(Context context, String summary, long timeoutMs) {
        return request(context, summary, timeoutMs, 0);
    }

    /** Optional rule countdown: auto-approve only after the second reviewer has explicitly allowed this action. */
    static String request(Context context, String summary, long timeoutMs, long autoApproveMs) {
        timeoutMs = Math.max(1000, Math.min(QUESTION_TIMEOUT_MS, timeoutMs));
        autoApproveMs = Math.max(0, Math.min(timeoutMs, autoApproveMs));
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
                            + "\nThis approval applies to one action only."
                            + (autoApproveMs > 0 ? "\nAuto-approves in " + Math.max(1, (autoApproveMs + 999) / 1000)
                                    + " seconds unless denied." : "")))
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(new Notification.Builder(context, CHANNEL)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("Buddy needs your approval")
                            .setContentText("Unlock your phone to review this action.")
                            .build())
                    .setAutoCancel(true)
                    .setTimeoutAfter(TIMEOUT_MS)
                    .addAction(new Notification.Action.Builder(null, "Approve once", yes).build())
                    .addAction(new Notification.Action.Builder(null, "Deny", no).build())
                    .build();
            manager.notify(id, notification);
            Handler main = new Handler(Looper.getMainLooper());
            Runnable auto = autoApproveMs > 0 ? () -> {
                Pending current = PENDING.get(id);
                if (current != null) {
                    current.allow = true;
                    current.auto = true;
                    current.latch.countDown();
                }
            } : null;
            if (auto != null) main.postDelayed(auto, autoApproveMs);
            boolean answered = pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!answered) return "TIMED OUT";
            if (pending.auto) return "AUTO_APPROVED";
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
        return requestChoice(context, question, options, null, QUESTION_TIMEOUT_MS);
    }

    static String requestChoice(Context context, String question, List<String> options, long timeoutMs) {
        return requestChoice(context, question, options, null, timeoutMs);
    }

    /** As above, with an optional user-tapped notification destination (used for guided browser steps). */
    static String requestChoice(Context context, String question, List<String> options, Intent contentIntent) {
        return requestChoice(context, question, options, contentIntent, QUESTION_TIMEOUT_MS);
    }

    static String requestChoice(Context context, String question, List<String> options, Intent contentIntent, long timeoutMs) {
        timeoutMs = Math.max(1000, Math.min(QUESTION_TIMEOUT_MS, timeoutMs));
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
                            + "\nChoose an answer or use Reply to type your own."))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(new Notification.Builder(context, CHANNEL)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("Buddy has a question")
                            .setContentText("Unlock your phone to answer.")
                            .build())
                    .setAutoCancel(contentIntent == null)
                    .setTimeoutAfter(timeoutMs);
            if (contentIntent != null) {
                Intent open = new Intent(contentIntent)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent destination = PendingIntent.getActivity(context, id * 8 + 8, open, flags);
                builder.setContentIntent(destination)
                        .setStyle(new Notification.BigTextStyle().bigText(question
                                + "\nTap this notification to open the marked browser page, or choose an answer or use Reply."));
            }
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
            boolean answered = pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
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
