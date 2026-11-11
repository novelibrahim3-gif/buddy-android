package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import java.util.Comparator;
import java.util.List;

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
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Root MCP aktif · Accessibility tidak digunakan")
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIFICATION_ID, notification);
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

    @Override
    public String instructions() {
        return "You are connected to Buddy's standalone KernelSU runtime. Accessibility is OFF. "
                + "Available tools are read_screen/screenshot (an image of the real display), root_status, "
                + "list_apps, open_app, open_url and press (fixed Android navigation buttons). "
                + "Interpret read_screen visually; it does not return UI element ids or OCR text. "
                + "Do not invent unavailable tools. tap, long_press, swipe, type_text, find, collect, "
                + "wait_for, do_steps and approval cards are intentionally unavailable in this runtime "
                + "until their safety checks can be preserved without Accessibility. Never enter passwords, "
                + "one-time codes or payment details.";
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
        tools.put(tool("press",
                "Use a fixed Android system navigation action: back, home, recents, notifications or quick_settings.",
                new JSONObject().put("button", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("back").put("home").put("recents")
                                .put("notifications").put("quick_settings"))), "button"));
        tools.put(tool("wait",
                "Wait for a short interval, up to 10 seconds.",
                new JSONObject().put("ms", integerProperty("Milliseconds, 0 to 10000")), "ms"));
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
            case "press":
                RootControl.pressSystemButton(args.getString("button"));
                return text("Pressed " + args.getString("button") + " using KernelSU.");
            case "wait": {
                long ms = Math.max(0, Math.min(10000, args.optLong("ms", 1000)));
                Thread.sleep(ms);
                return text("Waited " + ms + " ms.");
            }
            default:
                throw new IllegalArgumentException("Tool unavailable while Accessibility is off: " + name
                        + ". This runtime currently supports screen images, app launch, URL launch and fixed navigation.");
        }
    }

    private JSONObject screenImage() throws Exception {
        byte[] png = RootControl.screenshot(this);
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bitmap == null) throw new java.io.IOException("KernelSU returned a screenshot that Android could not decode.");
        JSONObject image = new JSONObject()
                .put("type", "image")
                .put("mimeType", "image/png")
                .put("data", Base64.encodeToString(png, Base64.NO_WRAP));
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        bitmap.recycle();
        return new JSONObject().put("content", new JSONArray()
                .put(image)
                .put(new JSONObject().put("type", "text").put("text",
                        "Real display screenshot: " + width + "x" + height
                                + " pixels. This is a visual-only screen read; there are no element ids.")));
    }

    private String listApps() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(query, 0);
        Collections.sort(apps, Comparator.comparing(r -> String.valueOf(r.loadLabel(getPackageManager())),
                String.CASE_INSENSITIVE_ORDER));
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
        Uri uri = Uri.parse(value == null ? "" : value.trim());
        String scheme = uri.getScheme();
        if (scheme == null) throw new IllegalArgumentException("The URL must include an allowed scheme.");
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!("https".equals(scheme) || "http".equals(scheme) || "mailto".equals(scheme)
                || "tel".equals(scheme) || "geo".equals(scheme) || "market".equals(scheme))) {
            throw new IllegalArgumentException("Unsupported URL scheme: " + scheme);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(getPackageManager()) == null) {
            throw new IllegalArgumentException("No installed app can open this link.");
        }
        startActivity(intent);
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
        // The standalone runtime does not own chat UI/session state. Unknown helper events are safely ignored.
    }

    @Override
    public java.io.File attachment(String id) {
        return Attachments.file(this, id);
    }
}
