package app.buddy.assistant;

import android.content.Context;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Narrow, allow-listed root bridge for screen I/O.
 *
 * This deliberately does not expose a general shell. Callers can request only
 * bounded input events or a screenshot, so MCP arguments can never become
 * arbitrary commands passed to KernelSU.
 */
final class RootControl {
    private static final String SU = "/system/bin/su";
    private static final long COMMAND_TIMEOUT_SECONDS = 10;
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            "BACK", "HOME", "APP_SWITCH", "ENTER", "DEL", "TAB",
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
            "MOVE_HOME", "MOVE_END", "PAGE_UP", "PAGE_DOWN",
            "VOLUME_UP", "VOLUME_DOWN", "POWER", "MENU"));

    private RootControl() {
    }

    static boolean available() {
        try {
            return "0".equals(run("id -u").trim());
        } catch (Exception e) {
            return false;
        }
    }

    static void tap(Context context, int x, int y) throws IOException {
        checkPoint(context, x, y);
        run("input tap " + x + " " + y);
    }

    static void longPress(Context context, int x, int y, int durationMs) throws IOException {
        checkPoint(context, x, y);
        int duration = Math.max(250, Math.min(5000, durationMs));
        run("input swipe " + x + " " + y + " " + x + " " + y + " " + duration);
    }

    static void swipe(Context context, int x1, int y1, int x2, int y2, int durationMs) throws IOException {
        checkPoint(context, x1, y1);
        checkPoint(context, x2, y2);
        int duration = Math.max(50, Math.min(5000, durationMs));
        run("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration);
    }

    static void press(String key) throws IOException {
        if (key == null) throw new IllegalArgumentException("A key is required.");
        String normalized = key.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.startsWith("KEYCODE_")) normalized = normalized.substring(8);
        if (!KEYS.contains(normalized)) throw new IllegalArgumentException("Unsupported key: " + key);
        run("input keyevent KEYCODE_" + normalized);
    }

    /** Fixed system-navigation actions; no command text comes from an MCP argument. */
    static void pressSystemButton(String button) throws IOException {
        if (button == null) throw new IllegalArgumentException("A system button is required.");
        switch (button) {
            case "back": press("BACK"); return;
            case "home": press("HOME"); return;
            case "recents": press("APP_SWITCH"); return;
            case "notifications": run("cmd statusbar expand-notifications"); return;
            case "quick_settings": run("cmd statusbar expand-settings"); return;
            default: throw new IllegalArgumentException("Unsupported system button: " + button);
        }
    }

    static void typeText(String value) throws IOException {
        if (value == null || value.length() == 0) return;
        if (value.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException("Text is too long (max 2000 characters).");
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Text must not contain line breaks or control characters.");
        }
        // Android's input command reserves %s as its space escape. Reject the ambiguous literal sequence.
        if (value.contains("%s") || value.contains("%S")) {
            throw new IllegalArgumentException("Text contains the reserved Android input sequence %s.");
        }
        run("input text " + quote(value.replace(" ", "%s")));
    }

    /**
     * Read the foreground package from Android system diagnostics. The command is fixed;
     * no MCP-supplied text is ever passed to the root shell.
     */
    static String foregroundPackage() throws IOException {
        String output = run("dumpsys activity activities | grep -m 1 -E 'topResumedActivity|mResumedActivity' "
                + "|| dumpsys window windows | grep -m 1 'mCurrentFocus' || true");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b(?:u\\d+\\s+)?([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/")
                .matcher(output);
        return matcher.find() ? matcher.group(1) : "";
    }

    static byte[] screenshot(Context context) throws IOException {
        File target = File.createTempFile("buddy-root-screen-", ".png", context.getCacheDir());
        try {
            run("screencap -p > " + quote(target.getAbsolutePath()) + " && chmod 644 "
                    + quote(target.getAbsolutePath()));
            if (!target.isFile() || target.length() < 8) throw new IOException("Root screencap returned an empty image.");
            try (FileInputStream in = new FileInputStream(target);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
    }

    private static void checkPoint(Context context, int x, int y) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        if (x < 0 || y < 0 || x >= dm.widthPixels || y >= dm.heightPixels) {
            throw new IllegalArgumentException("Coordinates are outside the current display bounds.");
        }
    }

    private static String run(String command) throws IOException {
        Process process;
        try {
            process = new ProcessBuilder(SU, "-c", command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("KernelSU/su could not be started. Grant Buddy root access in KernelSU, then retry.", e);
        }
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (java.io.InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    synchronized (captured) {
                        int remaining = 32 * 1024 - captured.size();
                        if (remaining > 0) captured.write(buffer, 0, Math.min(n, remaining));
                    }
                    // Keep draining even after the diagnostic cap so the child cannot block on a full pipe.
                }
            } catch (IOException ignored) {
            }
        }, "buddy-root-output");
        drain.setDaemon(true);
        drain.start();
        try {
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Root command timed out.");
            }
            drain.join(1000);
            byte[] output;
            synchronized (captured) {
                output = captured.toByteArray();
            }
            String message = new String(output, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                if (message.isEmpty()) message = "exit " + process.exitValue();
                throw new IOException("Root command failed: " + message);
            }
            return message;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Root command was interrupted.", e);
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
