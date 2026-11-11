package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.security.MessageDigest;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Accessibility-independent MCP runtime.
 *
 * This deliberately exposes only capabilities that can be implemented without an
 * AccessibilityNodeInfo tree. Screen interaction that needs element/risk inspection
 * remains unavailable here until an equivalent guarded confirmation flow exists.
 */
public final class RootMcpService extends Service implements McpServer.Handler {
    private static final String CHANNEL = "buddy_root_runtime";
    private static final int NOTIFICATION_ID = 8765;
    private McpServer server;

    /** Start the standalone MCP server only when Buddy's Accessibility service is off. */
    static void ensure(Context context) {
        Intent intent = new Intent(context, RootMcpService.class);
        if (SetupState.accessibilityEnabled(context)) {
            context.stopService(intent);
            return;
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        RootApproval.ensureChannel(this);
        startForeground(NOTIFICATION_ID, runtimeNotification("Root MCP aktif · Accessibility tidak digunakan"));
        server = new McpServer(Prefs.token(this), this);
        server.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (SetupState.accessibilityEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Buddy root runtime", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Local MCP server for Accessibility-independent screen inspection.");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification runtimeNotification(String status) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(status)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    @Override
    public String instructions() {
        return "You are connected to Buddy's standalone KernelSU runtime. Accessibility is OFF. "
                + "Available tools: read_screen/screenshot (visual image only), root_status, list_apps, "
                + "open_app, open_url, press, wait, tap, long_press, swipe, scroll, type_text, confirm, ask, notify, "
                + "wait_for (settled-only), wait_for_install, install_apk, update_buddy, schedule_message, "
                + "list_scheduled, cancel_scheduled, phone_shell and reply_notification (the last two require their user settings/rule context). "
                + "Every tap, long_press, swipe and scroll requires a one-time Approve/Deny notification; "
                + "type_text requires append=true and a separate notification showing the exact text. "
                + "If notifications are disabled, these actions are refused. Screen actions are also blocked while locked or off. "
                + "read_screen has no UI element ids "
                + "and no OCR text: inspect its image visually. find/collect are not exposed because they need OCR; "
                + "wait_for supports only settled=true (700 ms without visual changes), not text/gone/app matching. "
                + "do_steps is available for up to 12 actions; coordinate and text steps still require approval. "
                + "point_out is unavailable without the overlay/element map; reply_notification is not migrated; "
                + "phone_shell is intentionally not exposed in root mode. Never enter passwords, one-time codes, "
                + "payment details or secrets.";
    }

    @Override
    public JSONArray listTools() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("read_screen",
                "Capture the real phone display as an image using KernelSU screencap. The model must inspect "
                        + "the returned image visually. No Accessibility tree, element ids or OCR text are provided.",
                new JSONObject()));
        tools.put(tool("screenshot",
                "Capture the real phone display as a PNG image using KernelSU screencap.",
                new JSONObject()));
        tools.put(tool("root_status",
                "Check whether Buddy has an authorized KernelSU root session. The first call may show a KernelSU prompt.",
                new JSONObject()));
        tools.put(tool("list_apps",
                "List launchable apps and their package names.",
                new JSONObject()));
        tools.put(tool("open_app",
                "Open an installed app by its visible name or package name.",
                new JSONObject().put("app", stringProperty("App name or package")), "app"));
        tools.put(tool("open_url",
                "Open a web URL or supported Android deep link in its registered app. Allowed schemes: https, http, "
                        + "mailto, tel, geo and market. Opening a link does not submit a message or place a call.",
                new JSONObject().put("url", stringProperty("URL or supported deep link")), "url"));
        tools.put(tool("tap",
                "Tap a screen coordinate. Buddy sends a notification showing the requested coordinate and waits for "
                        + "the user to approve this one tap. Check the latest read_screen image before approving.",
                new JSONObject().put("x", integerProperty("X screen coordinate"))
                        .put("y", integerProperty("Y screen coordinate")), "x", "y"));
        tools.put(tool("long_press",
                "Long-press a screen coordinate. Buddy requires a separate one-time notification approval.",
                new JSONObject().put("x", integerProperty("X screen coordinate"))
                        .put("y", integerProperty("Y screen coordinate"))
                        .put("duration_ms", integerProperty("Duration, 250 to 5000 ms")) , "x", "y"));
        tools.put(tool("swipe",
                "Swipe between two screen coordinates. Buddy requires a separate one-time notification approval.",
                new JSONObject().put("x1", integerProperty("Start X")).put("y1", integerProperty("Start Y"))
                        .put("x2", integerProperty("End X")).put("y2", integerProperty("End Y"))
                        .put("duration_ms", integerProperty("Duration, 50 to 5000 ms")), "x1", "y1", "x2", "y2"));
        tools.put(tool("scroll",
                "Scroll in a direction from the center of the display. Buddy requires a one-time notification approval.",
                new JSONObject().put("direction", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("up").put("down").put("left").put("right"))), "direction"));
        tools.put(tool("type_text",
                "Type non-sensitive text at the currently focused cursor. In this Accessibility-off runtime it is "
                        + "insertion-only: append must be true. Buddy shows the exact text in a notification and waits "
                        + "for one-time user approval. Never use for passwords, one-time codes, payment details or secrets.",
                new JSONObject().put("text", stringProperty("Non-sensitive text, max 200 characters"))
                        .put("append", new JSONObject().put("type", "boolean")
                                .put("description", "Must be true: insert at the current cursor; replacement is not supported.")),
                "text", "append"));
        tools.put(tool("press",
                "Use a fixed Android system navigation action: back, home, recents, notifications or quick_settings.",
                new JSONObject().put("button", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("back").put("home").put("recents")
                                .put("notifications").put("quick_settings"))), "button"));
        tools.put(tool("do_steps",
                "Run up to 12 sequential actions. Every tap, long_press, swipe, scroll and type_text still requires "
                        + "its own one-time approval. The batch stops at the first denied or failed action.",
                new JSONObject().put("steps", new JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 12)
                        .put("items", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("action", new JSONObject().put("type", "string").put("enum", new JSONArray()
                                        .put("tap").put("long_press").put("swipe").put("scroll").put("type_text")
                                        .put("press").put("open_app").put("open_url").put("wait")))
                                .put("x", integerProperty("Tap/long_press X"))
                                .put("y", integerProperty("Tap/long_press Y"))
                                .put("x1", integerProperty("Swipe start X")).put("y1", integerProperty("Swipe start Y"))
                                .put("x2", integerProperty("Swipe end X")).put("y2", integerProperty("Swipe end Y"))
                                .put("duration_ms", integerProperty("Long-press/swipe duration"))
                                .put("direction", stringProperty("Scroll direction: up, down, left or right"))
                                .put("text", stringProperty("Text for type_text (non-sensitive, max 200 chars)"))
                                .put("append", new JSONObject().put("type", "boolean").put("description", "Must be true for type_text"))
                                .put("button", stringProperty("System button"))
                                .put("app", stringProperty("App name or package"))
                                .put("url", stringProperty("URL or supported deep link"))
                                .put("ms", integerProperty("Wait duration"))).put("required", new JSONArray().put("action")))), "steps"));
        tools.put(tool("wait",
                "Wait for a short interval, up to 10 seconds.",
                new JSONObject().put("ms", integerProperty("Milliseconds, 0 to 10000")), "ms"));
        tools.put(tool("wait_for",
                "Wait until the display stops changing for at least 700 ms. This root-only version supports settled=true only; "
                        + "text/gone/app matching requires OCR or foreground-app access and is not available.",
                new JSONObject().put("settled", new JSONObject().put("type", "boolean")
                                .put("description", "Must be true: wait for the screen to settle"))
                        .put("timeout_ms", integerProperty("Default 8000, maximum 30000 ms")), "settled"));
        tools.put(tool("confirm",
                "Ask the user to approve an outward-facing or hard-to-undo action. The exact summary is shown in a one-shot notification.",
                new JSONObject().put("summary", stringProperty("Exactly what the user is approving")), "summary"));
        tools.put(tool("ask",
                "Ask the user a question through a notification with 2 to 4 answer buttons and an optional typed reply. "
                        + "This root-only version does not support screen highlights.",
                new JSONObject().put("question", stringProperty("Short, clear question"))
                        .put("options", new JSONObject().put("type", "array").put("minItems", 2).put("maxItems", 4)
                                .put("items", stringProperty("One short answer choice"))), "question", "options"));
        tools.put(tool("notify",
                "Update the persistent Buddy root-runtime notification with a short status message.",
                new JSONObject().put("text", stringProperty("Status text, max 180 characters")), "text"));
        tools.put(tool("wait_for_install",
                "Wait until a package is installed or updated to min_version_code. This checks PackageManager only; "
                        + "it does not inspect the installer screen and returns TIMED OUT if the user has not confirmed.",
                new JSONObject().put("package", stringProperty("Android package name"))
                        .put("min_version_code", integerProperty("Minimum versionCode; if omitted, wait for a new version"))
                        .put("timeout_s", integerProperty("Default 120, minimum 10, maximum 300")), "package"));
        tools.put(tool("install_apk",
                "Open Android's package installer for an APK from Buddy's Linux or a localhost HTTP URL. Linux tools must be enabled for this chat; "
                        + "the user still confirms installation in Android.",
                new JSONObject().put("path", stringProperty("Absolute APK path in Buddy's Linux, or http://127.0.0.1:PORT/path")), "path"));
        tools.put(tool("update_buddy",
                "Check the official Buddy GitHub release. With install=true, download a newer APK and open Android's installer; "
                        + "the user must approve and tap Update.",
                new JSONObject().put("install", new JSONObject().put("type", "boolean")
                        .put("description", "Download and open the installer only when true"))));
        tools.put(tool("phone_shell",
                "Run a command as Android shell user through Buddy Wireless debugging helper, not as root. "
                        + "Requires Settings → Background mode → Phone commands and Linux tools. Screen-control commands, "
                        + "root escalation and commands targeting Buddy/accessibility settings are blocked; user approval follows the Phone commands setting.",
                new JSONObject().put("command", stringProperty("Android shell command, max 4000 characters"))
                        .put("timeout_s", integerProperty("Default 60, maximum 300")), "command"));
        tools.put(tool("reply_notification",
                "Send one reply to the notification that started this notification-rule chat. The exact text requires one-time user approval; only one reply is allowed per run.",
                new JSONObject().put("text", stringProperty("Exact reply text to send")), "text"));
        tools.put(tool("schedule_message",
                "Schedule a one-time message or reminder in the current Buddy chat. Give in_minutes or at.",
                new JSONObject().put("message", stringProperty("What to do or remind the user about"))
                        .put("in_minutes", integerProperty("Minutes from now"))
                        .put("at", stringProperty("Phone local time, e.g. 2026-11-12 09:00 or 09:00"))
                        .put("remind", new JSONObject().put("type", "boolean"))
                        .put("quiet", new JSONObject().put("type", "boolean")), "message"));
        tools.put(tool("list_scheduled",
                "List one-time messages scheduled in the current Buddy chat.", new JSONObject()));
        tools.put(tool("cancel_scheduled",
                "Cancel a scheduled message in the current Buddy chat.",
                new JSONObject().put("id", stringProperty("Scheduled message id")), "id"));
        return tools;
    }

    @Override
    public JSONObject callTool(String name, JSONObject args) throws Exception {
        switch (name) {
            case "read_screen":
            case "screenshot":
                return screenImage();
            case "root_status":
                return text(RootControl.available()
                        ? "KernelSU root is available to Buddy."
                        : "KernelSU root is not available. Open KernelSU and grant Buddy root access.");
            case "list_apps":
                return text(listApps());
            case "open_app":
                return text(openApp(args.getString("app")));
            case "open_url":
                return text(openUrl(args.getString("url")));
            case "tap": {
                int x = args.getInt("x"), y = args.getInt("y");
                requireUnlocked();
                requirePoint(x, y);
                String approval = RootApproval.request(this, "Tap screen coordinate (" + x + ", " + y + ")?");
                if (!"APPROVED".equals(approval)) return text("Tap not performed: " + approval + ".");
                requireUnlocked();
                RootControl.tap(this, x, y);
                return text("Tapped (" + x + ", " + y + ") using KernelSU.");
            }
            case "long_press": {
                int x = args.getInt("x"), y = args.getInt("y");
                int duration = (int) Math.max(250, Math.min(5000, args.optLong("duration_ms", 700)));
                requireUnlocked();
                requirePoint(x, y);
                String approval = RootApproval.request(this, "Long-press (" + x + ", " + y + ") for " + duration + " ms?");
                if (!"APPROVED".equals(approval)) return text("Long-press not performed: " + approval + ".");
                requireUnlocked();
                RootControl.longPress(this, x, y, duration);
                return text("Long-pressed (" + x + ", " + y + ") using KernelSU.");
            }
            case "swipe": {
                int x1 = args.getInt("x1"), y1 = args.getInt("y1");
                int x2 = args.getInt("x2"), y2 = args.getInt("y2");
                int duration = (int) Math.max(50, Math.min(5000, args.optLong("duration_ms", 300)));
                requireUnlocked();
                requirePoint(x1, y1);
                requirePoint(x2, y2);
                String approval = RootApproval.request(this, "Swipe from (" + x1 + ", " + y1 + ") to ("
                        + x2 + ", " + y2 + ") over " + duration + " ms?");
                if (!"APPROVED".equals(approval)) return text("Swipe not performed: " + approval + ".");
                requireUnlocked();
                RootControl.swipe(this, x1, y1, x2, y2, duration);
                return text("Swiped using KernelSU.");
            }
            case "scroll": {
                String direction = args.getString("direction");
                requireUnlocked();
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int cx = dm.widthPixels / 2, cy = dm.heightPixels / 2;
                int x1 = cx, y1 = cy, x2 = cx, y2 = cy;
                switch (direction) {
                    case "up": y1 = dm.heightPixels * 3 / 4; y2 = dm.heightPixels / 4; break;
                    case "down": y1 = dm.heightPixels / 4; y2 = dm.heightPixels * 3 / 4; break;
                    case "left": x1 = dm.widthPixels * 3 / 4; x2 = dm.widthPixels / 4; break;
                    case "right": x1 = dm.widthPixels / 4; x2 = dm.widthPixels * 3 / 4; break;
                    default: throw new IllegalArgumentException("Unsupported scroll direction: " + direction);
                }
                String approval = RootApproval.request(this, "Scroll " + direction + " across the center of the screen?");
                if (!"APPROVED".equals(approval)) return text("Scroll not performed: " + approval + ".");
                requireUnlocked();
                RootControl.swipe(this, x1, y1, x2, y2, 350);
                return text("Scrolled " + direction + " using KernelSU.");
            }
            case "type_text": {
                String value = args.getString("text");
                requireUnlocked();
                if (!args.optBoolean("append", false)) {
                    throw new IllegalArgumentException("Accessibility-off typing is insertion-only. Set append=true after placing the cursor.");
                }
                if (value.length() > 200) throw new IllegalArgumentException("Text is limited to 200 characters per approved action.");
                String lower = value.toLowerCase(java.util.Locale.ROOT);
                if (value.matches("[0-9]{3,8}") || lower.contains("password") || lower.contains("passcode")
                        || lower.contains("one-time code") || lower.contains("otp") || lower.contains("cvv") || lower.equals("pin")
                        || lower.startsWith("pin ") || lower.endsWith(" pin") || lower.contains(" pin ")) {
                    throw new IllegalArgumentException("Sensitive credential-like text is not accepted by root typing.");
                }
                String approval = RootApproval.request(this, "Type exactly: " + value);
                if (!"APPROVED".equals(approval)) return text("Text not entered: " + approval + ".");
                requireUnlocked();
                RootControl.typeText(value);
                return text("Inserted " + value.length() + " characters using KernelSU.");
            }
            case "press":
                requireUnlocked();
                RootControl.pressSystemButton(args.getString("button"));
                return text("Pressed " + args.getString("button") + " using KernelSU.");
            case "do_steps":
                return doSteps(args.getJSONArray("steps"));
            case "wait": {
                long ms = Math.max(0, Math.min(10000, args.optLong("ms", 1000)));
                Thread.sleep(ms);
                return text("Waited " + ms + " ms.");
            }
            case "wait_for":
                return waitFor(args);
            case "confirm": {
                String summary = args.getString("summary").trim();
                if (summary.isEmpty()) throw new IllegalArgumentException("Give the exact action to approve.");
                String approval = RootApproval.request(this, "Approve this action?\\n" + summary);
                if ("APPROVED".equals(approval)) return text("APPROVED by the user. Go ahead.");
                if ("DENIED".equals(approval)) return text("DENIED by the user. Do not do it.");
                return text("NO ANSWER: approval " + approval.toLowerCase(java.util.Locale.ROOT) + ". Do not proceed.");
            }
            case "ask": {
                String question = args.getString("question").trim();
                JSONArray choices = args.getJSONArray("options");
                if (question.isEmpty()) throw new IllegalArgumentException("Give a question.");
                if (choices.length() < 2 || choices.length() > 4) throw new IllegalArgumentException("Give 2 to 4 answer choices.");
                if (args.has("highlight")) throw new IllegalArgumentException("Screen highlights are not available with Accessibility off.");
                java.util.ArrayList<String> options = new java.util.ArrayList<>();
                for (int i = 0; i < choices.length(); i++) {
                    String option = choices.optString(i).trim();
                    if (option.isEmpty() || option.length() > 40) throw new IllegalArgumentException("Each answer must contain 1 to 40 characters.");
                    options.add(option);
                }
                String answer = RootApproval.requestChoice(this, question, options);
                return text(answer == null || answer.isEmpty()
                        ? "NO ANSWER: notifications are unavailable or the user did not answer within 5 minutes."
                        : "ANSWER: “" + answer + "”");
            }
            case "notify": {
                String status = args.getString("text").trim();
                if (status.isEmpty()) throw new IllegalArgumentException("Give a status message.");
                if (status.length() > 180) status = status.substring(0, 180);
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (!manager.areNotificationsEnabled()) return text("Not shown: notifications are disabled.");
                manager.notify(NOTIFICATION_ID, runtimeNotification(status));
                return text("Updated Buddy's persistent runtime notification.");
            }
            case "wait_for_install":
                return waitForInstall(args);
            case "install_apk":
                return installApk(args.getString("path"));
            case "update_buddy":
                return updateBuddy(args.optBoolean("install", false));
            case "phone_shell":
                return phoneShell(args);
            case "reply_notification":
                return replyNotification(args.getString("text"));
            case "schedule_message":
            case "list_scheduled":
            case "cancel_scheduled":
                return text(Schedules.agentTool(this, McpServer.CALLER_CHAT.get(), name, args));
            default:
                throw new IllegalArgumentException("Tool unavailable while Accessibility is off: " + name
                        + ". This runtime currently supports screen images, app launch, URL launch and fixed navigation.");
        }
    }

    private JSONObject replyNotification(String value) throws Exception {
        String chat = McpServer.CALLER_CHAT.get();
        Triggers.Note note = Triggers.note(chat);
        if (note == null) return text("There is no notification to answer in this chat; reply_notification only works in chats started by a notification rule.");
        if (note.reply == null) return text("This notification has no reply field. Open " + note.app + " to answer instead.");
        String reply = value == null ? "" : value.trim();
        if (reply.isEmpty()) throw new IllegalArgumentException("Give the exact reply text.");
        String already = Triggers.alreadyReplied(chat);
        if (already != null) return text("Not sent: this run already replied with “" + already + "”. One reply per run is allowed.");
        if (Triggers.userAnswered(chat)) return text("Not sent: the user has already replied in " + note.app + ".");
        if (Triggers.wroteAgain(chat)) {
            Triggers.carryOver(chat);
            return text("Not sent: a newer message arrived in " + note.app + ". A new rule run should handle the latest messages.");
        }
        requireUnlocked();
        String approval = RootApproval.request(this, "Send this exact reply to " + note.who() + " in " + note.app + "?\\n" + reply);
        if (!"APPROVED".equals(approval)) return text("Not sent: " + approval + ".");
        requireUnlocked();
        if (Triggers.userAnswered(chat)) return text("Not sent: the user replied while approval was pending.");
        if (Triggers.wroteAgain(chat)) {
            Triggers.carryOver(chat);
            return text("Not sent: a newer message arrived while approval was pending. A new rule run should handle it.");
        }
        if (!Triggers.sendReply(this, note.reply, reply)) {
            return text("Not sent: " + note.app + " no longer accepts replies from this notification. Check the app before retrying.");
        }
        Triggers.replied(this, chat, reply);
        return text("Sent to " + note.who() + " through " + note.app + " notification reply: “" + reply + "”. Do not send it again.");
    }
    private JSONObject phoneShell(JSONObject args) throws Exception {
        String chat = McpServer.CALLER_CHAT.get();
        if (Triggers.ruleRun(chat)) return text("Not available in a notification rule run.");
        if (!Prefs.phoneShell(this) || !Background.setUp(this)) {
            return text("Phone commands are off. The user must enable Settings → Background mode → Phone commands and set up Background mode first.");
        }
        if (!ChatHub.get(this).devFor(chat)) {
            return text("Phone commands also need Linux tools enabled in this Buddy chat. Enable them, then retry.");
        }
        String command = args.getString("command").trim();
        if (command.isEmpty()) throw new IllegalArgumentException("Give the command.");
        if (command.length() > 4000) throw new IllegalArgumentException("Command is limited to 4000 characters.");
        java.util.regex.Pattern blocked = java.util.regex.Pattern.compile(
                "(?:^|[\\s;&|(`])(?:/system/bin/)?(?:input|sendevent|monkey|uiautomator|su|ksud|magisk|sudo)(?:\\s|$)"
                        + "|cmd\\s+input|app\\.buddy\\.assistant|enabled_accessibility_services|accessibility_enabled");
        if (blocked.matcher(command).find()) {
            return text("BLOCKED: phone_shell cannot control the screen, escalate to root, or change Buddy/accessibility settings. Use the approved phone tools for screen actions.");
        }
        int seconds = (int) Math.max(1, Math.min(300, args.optInt("timeout_s", 60)));
        if (Prefs.phoneShellAsk(this)) {
            String approval = RootApproval.request(this, "Run this command as Android shell user?\\n" + command);
            if (!"APPROVED".equals(approval)) return text("Not run: " + approval + ".");
        }
        try {
            String[] result = Background.exec(this, command, seconds);
            String output = result[1].length() > 20000 ? "…" + result[1].substring(result[1].length() - 20000) : result[1];
            return text(("-1".equals(result[0]) ? "Stopped after " + seconds + " s without finishing." : "Exit code " + result[0] + ".")
                    + (output.trim().isEmpty() ? " No output." : "\\n" + output));
        } catch (Background.Unavailable e) {
            return text("The command failed: " + e.getMessage() + ".");
        }
    }
    private JSONObject installApk(String path) throws Exception {
        requireUnlocked();
        String chat = McpServer.CALLER_CHAT.get();
        if (!ChatHub.get(this).devFor(chat)) {
            return text(chat != null
                    ? "Installing apps needs Linux tools enabled in this Buddy chat. Enable them, then retry."
                    : "Installing apps needs the Settings option 'Linux tools in new chats' enabled, then retry.");
        }
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("Give an APK path or localhost URL.");
        path = path.trim();
        File output = FilesProvider.apkFile(this);
        if (path.matches("http://(127\\.0\\.0\\.1|localhost):\\d+/.*")) {
            HttpURLConnection connection = (HttpURLConnection) new URL(path).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            try {
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) return text("Local APK download failed: HTTP " + code + ".");
                long length = connection.getContentLengthLong();
                if (length > 512L * 1024 * 1024) return text("APK exceeds the 512 MiB safety limit.");
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(output)) {
                    byte[] buffer = new byte[32 * 1024];
                    long total = 0;
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > 512L * 1024 * 1024) throw new java.io.IOException("APK exceeds the 512 MiB safety limit.");
                        out.write(buffer, 0, n);
                    }
                }
            } catch (java.io.IOException e) {
                return text("Couldn't download " + path + ": " + e.getMessage());
            } finally {
                connection.disconnect();
            }
        } else {
            if (!path.startsWith("/") || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                throw new IllegalArgumentException("Give a full .apk path inside Buddy's Linux or a localhost HTTP URL.");
            }
            File root = Linux.root(this).getCanonicalFile();
            File source = new File(root, path.substring(1)).getCanonicalFile();
            if (!source.getPath().startsWith(root.getPath() + File.separator) || !source.isFile()) {
                return text("No APK at " + path + " inside Buddy's Linux.");
            }
            if (source.length() > 512L * 1024 * 1024) return text("APK exceeds the 512 MiB safety limit.");
            try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(output)) {
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        }
        if (!output.isFile() || output.length() < 4) return text("The APK download/copy was empty.");
        try (FileInputStream in = new FileInputStream(output)) {
            if (in.read() != 'P' || in.read() != 'K') return text("The selected file does not look like an APK/ZIP archive.");
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return text("Android's 'Allow from this source' settings are open. Enable installs for Buddy, then call install_apk again.");
        }
        String approval = RootApproval.request(this, "Open Android's installer for this APK?\n" + path
                + "\nYou must review and confirm the installation in Android.");
        if (!"APPROVED".equals(approval)) return text("Installer not opened: " + approval + ".");
        requireUnlocked();
        startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FilesProvider.apkUri(), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
        return text("Android's installer is open. The user must tap Install/Update and confirm. Call wait_for_install next.");
    }

    private JSONObject updateBuddy(boolean install) throws Exception {
        requireUnlocked();
        if (Flavor.FDROID) {
            return text("This is the F-Droid build. Update Buddy through the F-Droid app that installed it.");
        }
        Updates.Release release = Updates.latest();
        if (!release.newer()) return text("Buddy is up to date (version " + BuildInfo.VERSION + ").");
        if (!install) {
            return text("Buddy " + release.version + " is available. Ask the user whether to download it and open Android's installer.");
        }
        String approval = RootApproval.request(this, "Download Buddy " + release.version
                + " from the official GitHub release and open Android's installer?");
        if (!"APPROVED".equals(approval)) return text("Buddy update not started: " + approval + ".");
        String result = Updates.install(this, release);
        if (Updates.OPENED.equals(result)) {
            return text("Buddy " + release.version + " was downloaded and Android's installer is open. The user must tap Update.");
        }
        if (Updates.NEEDS_PERMISSION.equals(result)) {
            return text("Android's 'Allow from this source' settings are open. Enable installs for Buddy, then retry update_buddy with install=true.");
        }
        return text("Buddy update not installed: " + result + " " + Updates.MANUAL);
    }

    private JSONObject waitForInstall(JSONObject args) throws Exception {
        String pkg = args.getString("package").trim();
        if (pkg.isEmpty()) throw new IllegalArgumentException("Give the Android package name.");
        long before = installedVersion(pkg);
        long requested = Math.max(0, args.optLong("min_version_code", 0));
        long need = requested > 0 ? requested : (before < 0 ? 0 : before + 1);
        int timeout = (int) Math.max(10, Math.min(300, args.optInt("timeout_s", 120)));
        long start = android.os.SystemClock.elapsedRealtime();
        while (true) {
            long version = installedVersion(pkg);
            if (version >= 0 && version >= need) {
                return text("Installed " + pkg + " (versionCode " + version + "). Android PackageManager confirms the package is present.");
            }
            long elapsed = android.os.SystemClock.elapsedRealtime() - start;
            if (elapsed >= timeout * 1000L) {
                return text("TIMED OUT after " + timeout + " s: " + pkg + " has not reached versionCode " + need
                        + ". The user may not have confirmed the install.");
            }
            Thread.sleep(1000);
        }
    }

    private long installedVersion(String packageName) {
        try {
            return getPackageManager().getPackageInfo(packageName, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private JSONObject waitFor(JSONObject args) throws Exception {
        if (!args.optBoolean("settled", false)) {
            throw new IllegalArgumentException("Set settled=true. Root mode cannot match text, gone text or foreground app.");
        }
        if (args.has("text") || args.has("gone") || args.has("app")) {
            throw new IllegalArgumentException("Root mode wait_for supports settled=true only; text/gone/app matching needs OCR or foreground-app access.");
        }
        requireUnlocked();
        long timeout = Math.max(500, Math.min(30000, args.optLong("timeout_ms", 8000)));
        long start = android.os.SystemClock.elapsedRealtime();
        long stableSince = start;
        String last = screenFingerprint();
        while (true) {
            Thread.sleep(350);
            requireUnlocked();
            String current = screenFingerprint();
            long now = android.os.SystemClock.elapsedRealtime();
            if (!current.equals(last)) {
                last = current;
                stableSince = now;
            }
            if (now - stableSince >= 700) {
                return text(String.format(java.util.Locale.ROOT, "OK after %.1f s; display was visually stable for at least 700 ms.",
                        (now - start) / 1000.0));
            }
            if (now - start >= timeout) {
                return text(String.format(java.util.Locale.ROOT, "TIMED OUT after %.1f s: the screen kept changing.",
                        (now - start) / 1000.0));
            }
        }
    }

    private String screenFingerprint() throws Exception {
        byte[] png = RootControl.screenshot(this);
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(png), Base64.NO_WRAP);
    }

    private void requirePoint(int x, int y) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (x < 0 || y < 0 || x >= dm.widthPixels || y >= dm.heightPixels) {
            throw new IllegalArgumentException("Coordinates are outside the current display bounds ("
                    + dm.widthPixels + "x" + dm.heightPixels + ").");
        }
    }

    private void requireUnlocked() {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (!BuddyService.screenOn(this) || (keyguard != null && keyguard.isKeyguardLocked())) {
            throw new IllegalStateException("The screen is off or the phone is locked. Turn the screen on and unlock it before using root screen tools.");
        }
    }

    private JSONObject doSteps(JSONArray steps) throws Exception {
        if (steps.length() < 1 || steps.length() > 12) {
            throw new IllegalArgumentException("Give between 1 and 12 steps.");
        }
        StringBuilder out = new StringBuilder();
        int completed = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            String action = step.optString("action", "");
            if ("do_steps".equals(action) || "read_screen".equals(action) || "screenshot".equals(action)
                    || "list_apps".equals(action) || "root_status".equals(action)) {
                out.append(i + 1).append(". stopped: unsupported action in a step (").append(action).append(").");
                break;
            }
            try {
                JSONObject result = callTool(action, step);
                JSONArray content = result.optJSONArray("content");
                JSONObject first = content == null ? null : content.optJSONObject(0);
                String message = first == null ? "No result returned." : first.optString("text");
                out.append(i + 1).append(". ").append(action).append(": ").append(message).append('\n');
                String lower = message.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("not performed") || lower.contains("not entered")
                        || lower.contains("timed out") || lower.contains("denied")
                        || lower.contains("unavailable")) break;
                completed++;
            } catch (Exception e) {
                out.append(i + 1).append(". ").append(action).append(": FAILED: ")
                        .append(e.getMessage()).append('\n');
                break;
            }
        }
        out.append("Done ").append(completed).append(" of ").append(steps.length()).append(" steps.");
        return text(out.toString());
    }

    private JSONObject screenImage() throws Exception {
        requireUnlocked();
        byte[] png = RootControl.screenshot(this);
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new java.io.IOException("KernelSU returned a screenshot that Android could not decode.");
        }
        JSONObject image = new JSONObject()
                .put("type", "image")
                .put("mimeType", "image/png")
                .put("data", Base64.encodeToString(png, Base64.NO_WRAP));
        int width = bounds.outWidth;
        int height = bounds.outHeight;
        return new JSONObject().put("content", new JSONArray()
                .put(image)
                .put(new JSONObject().put("type", "text").put("text",
                        "Real display screenshot: " + width + "x" + height
                                + " pixels. This is a visual-only screen read; there are no element ids.")));
    }

    private String listApps() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(query, 0);
        Collections.sort(apps, (left, right) -> String.CASE_INSENSITIVE_ORDER.compare(
                String.valueOf(left.loadLabel(pm)), String.valueOf(right.loadLabel(pm))));
        StringBuilder out = new StringBuilder();
        for (ResolveInfo r : apps) {
            if (r.activityInfo == null) continue;
            if (out.length() > 0) out.append('\n');
            out.append(r.loadLabel(getPackageManager())).append(" · ").append(r.activityInfo.packageName);
            if (out.length() > 24000) {
                out.append("\n(list truncated)");
                break;
            }
        }
        return out.length() == 0 ? "No launchable apps were found." : out.toString();
    }

    private String openApp(String query) {
        requireUnlocked();
        String wanted = query == null ? "" : query.trim();
        if (wanted.isEmpty()) throw new IllegalArgumentException("App name or package is required.");
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        ResolveInfo match = null;
        for (ResolveInfo r : apps) {
            if (r.activityInfo == null) continue;
            String pkg = r.activityInfo.packageName;
            String label = String.valueOf(r.loadLabel(pm));
            if (pkg.equalsIgnoreCase(wanted) || label.equalsIgnoreCase(wanted)) {
                match = r;
                break;
            }
        }
        if (match == null) {
            for (ResolveInfo r : apps) {
                if (r.activityInfo == null) continue;
                String label = String.valueOf(r.loadLabel(pm));
                if (label.toLowerCase(java.util.Locale.ROOT).contains(wanted.toLowerCase(java.util.Locale.ROOT))) {
                    if (match != null) throw new IllegalArgumentException("More than one app matches. Use its package name.");
                    match = r;
                }
            }
        }
        if (match == null || match.activityInfo == null) throw new IllegalArgumentException("No launchable app matches: " + wanted);
        Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(match.activityInfo.packageName, match.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        return "Opened " + match.loadLabel(pm) + " (" + match.activityInfo.packageName + ").";
    }

    private String openUrl(String value) {
        requireUnlocked();
        Uri uri = Uri.parse(value == null ? "" : value.trim());
        String scheme = uri.getScheme();
        if (scheme == null) throw new IllegalArgumentException("The URL must include an allowed scheme.");
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme) || "mailto".equals(scheme)
                || "tel".equals(scheme) || "geo".equals(scheme) || "market".equals(scheme))) {
            throw new IllegalArgumentException("Unsupported URL scheme: " + scheme);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            throw new IllegalArgumentException("No installed app can open this link.");
        }
        return "Opened " + uri;
    }

    private static JSONObject tool(String name, String description, JSONObject properties, String... required)
            throws JSONException {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", properties);
        if (required.length > 0) {
            JSONArray req = new JSONArray();
            for (String item : required) req.put(item);
            schema.put("required", req);
        }
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", schema);
    }

    private static JSONObject stringProperty(String description) throws JSONException {
        return new JSONObject().put("type", "string").put("description", description);
    }

    private static JSONObject integerProperty(String description) throws JSONException {
        return new JSONObject().put("type", "integer").put("description", description);
    }

    private static JSONObject text(String value) throws JSONException {
        return new JSONObject().put("content", new JSONArray()
                .put(new JSONObject().put("type", "text").put("text", value)));
    }

    @Override
    public void onEvent(JSONObject event) {
        if (!"buddy_pong".equals(event.optString("type"))) return;
        Prefs.setHelperStatus(this, event.optString("text"));
        Prefs.setHelperVersion(this, event.optInt("helper", 1));
        if (event.has("signed")) Prefs.setSignedAgents(this, event.optString("signed"));
        JSONArray claude = event.optJSONArray("claude_models");
        if (claude != null && claude.length() > 0) {
            Prefs.setClaudeModels(this, claude.toString(), event.optString("claude_default"));
            Models.set("claude", claude.toString());
            Models.setClaudeDefault(event.optString("claude_default"));
        }
        JSONArray codex = event.optJSONArray("codex_models");
        if (codex != null && codex.length() > 0) {
            Prefs.setCodexModels(this, codex.toString());
            Models.set("codex", codex.toString());
        }
        JSONArray opencode = event.optJSONArray("opencode_models");
        if (opencode != null && opencode.length() > 0) {
            Prefs.setOpencodeModels(this, opencode.toString());
            Models.set("opencode", opencode.toString());
        }
        JSONArray agy = event.optJSONArray("agy_models");
        if (agy != null && agy.length() > 0) {
            Prefs.setAgyModels(this, agy.toString());
            Models.set("agy", agy.toString());
        }
        JSONArray grok = event.optJSONArray("grok_models");
        if (grok != null && grok.length() > 0) {
            Prefs.setGrokModels(this, grok.toString());
            Models.set("grok", grok.toString());
        }
        // Chat transcript/monitor events need BuddyService's chat UI and are intentionally not consumed here.
    }

    @Override
    public java.io.File attachment(String id) {
        return Attachments.file(this, id);
    }
}
