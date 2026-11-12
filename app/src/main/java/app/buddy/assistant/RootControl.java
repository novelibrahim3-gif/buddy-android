package app.buddy.assistant;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Narrow, allow-listed root bridge for screen I/O.
 *
 * Commands are serialized through one persistent KernelSU shell. On KernelSU Next
 * and official KernelSU, prefer the manager's own libksud entry point; fall back
 * to the standard "su" executable for other root implementations.
 */
final class RootControl {
    private static final long START_TIMEOUT_SECONDS = 15;
    private static final long COMMAND_TIMEOUT_SECONDS = 10;
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final int MAX_OUTPUT_LENGTH = 32 * 1024;
    private static final String[] KSU_PACKAGES = {
            "com.rifsxd.ksunext", "me.weishu.kernelsu"
    };
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
            "BACK", "HOME", "APP_SWITCH", "ENTER", "DEL", "TAB",
            "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
            "MOVE_HOME", "MOVE_END", "PAGE_UP", "PAGE_DOWN",
            "VOLUME_UP", "VOLUME_DOWN", "POWER", "MENU"));

    private static final Object ROOT_LOCK = new Object();
    private static volatile Context appContext;
    private static Process rootProcess;
    private static BufferedWriter rootInput;
    private static BlockingQueue<OutputLine> rootOutput;
    private static String rootSource = "unknown";

    private RootControl() {
    }

    static void configure(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    /** Requests/checks the real root shell and includes the selected launch path in the result. */
    static String status() {
        try {
            String uid = run("id -u").trim();
            return "0".equals(uid)
                    ? "KernelSU: GRANTED (UID 0; " + rootSource + ")"
                    : "KernelSU: NOT GRANTED (shell returned UID " + uid + ")";
        } catch (Exception e) {
            String detail = e.getMessage();
            if (detail == null || detail.trim().isEmpty()) detail = e.getClass().getSimpleName();
            return "KernelSU: NOT GRANTED — " + detail;
        }
    }

    static boolean available() {
        return status().startsWith("KernelSU: GRANTED");
    }

    static void tap(Context context, int x, int y) throws IOException {
        configure(context);
        checkPoint(context, x, y);
        run("input tap " + x + " " + y);
    }

    static void longPress(Context context, int x, int y, int durationMs) throws IOException {
        configure(context);
        checkPoint(context, x, y);
        int duration = Math.max(250, Math.min(5000, durationMs));
        run("input swipe " + x + " " + y + " " + x + " " + y + " " + duration);
    }

    static void swipe(Context context, int x1, int y1, int x2, int y2, int durationMs) throws IOException {
        configure(context);
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
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("Text must not contain line breaks, tabs or control characters.");
            }
        }
        if (value.contains("%s") || value.contains("%S")) {
            throw new IllegalArgumentException("Text contains the reserved Android input sequence %s.");
        }
        run("input text " + quote(value.replace(" ", "%s")));
    }

    /** Read the foreground package from Android system diagnostics. The command is fixed. */
    static String foregroundPackage() throws IOException {
        String output = run("dumpsys activity activities | grep -m 1 -E 'topResumedActivity=ActivityRecord|mResumedActivity: ActivityRecord' "
                + "|| dumpsys window windows | grep -m 1 -E 'mCurrentFocus=Window|mFocusedApp=ActivityRecord' || true");
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b(?:u\\d+\\s+)?([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/")
                .matcher(output);
        return matcher.find() ? matcher.group(1) : "";
    }

    static byte[] screenshot(Context context) throws IOException {
        configure(context);
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

    /**
     * Run one fixed/allow-listed command over the same long-lived root shell.
     * The random completion marker frames each command and preserves its exit code.
     */
    private static String run(String command) throws IOException {
        synchronized (ROOT_LOCK) {
            ensureRootShellLocked();
            String marker = "__BUDDY_ROOT_DONE_" + UUID.randomUUID().toString().replace("-", "");
            try {
                rootInput.write("( " + command + " ) 2>&1\n");
                rootInput.write("echo " + marker + " $?\n");
                rootInput.flush();
            } catch (IOException e) {
                stopRootShellLocked();
                throw new IOException("Could not write to the persistent root shell: " + e.getMessage(), e);
            }

            StringBuilder captured = new StringBuilder();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS);
            while (true) {
                OutputLine line;
                try {
                    line = pollLine(rootOutput, deadline, "Root command timed out.");
                } catch (IOException timeout) {
                    stopRootShellLocked(); // discard a desynchronized shell before the next command
                    throw timeout;
                }
                if (!line.stdout) continue; // command stderr is merged into stdout above
                if (line.text == null) {
                    stopRootShellLocked();
                    throw new IOException("KernelSU root shell exited while a command was running.");
                }
                String value = line.text.trim();
                String prefix = marker + " ";
                if (value.startsWith(prefix)) {
                    int code;
                    try {
                        code = Integer.parseInt(value.substring(prefix.length()).trim());
                    } catch (NumberFormatException e) {
                        stopRootShellLocked();
                        throw new IOException("Invalid completion status from the root shell: " + value, e);
                    }
                    String output = captured.toString().trim();
                    if (code != 0) throw new IOException("Root command failed (exit " + code + ")"
                            + (output.isEmpty() ? "." : ": " + output));
                    return output;
                }
                if (captured.length() < MAX_OUTPUT_LENGTH) {
                    if (captured.length() > 0) captured.append('\n');
                    int remaining = MAX_OUTPUT_LENGTH - captured.length();
                    captured.append(value, 0, Math.min(value.length(), remaining));
                }
            }
        }
    }

    private static void ensureRootShellLocked() throws IOException {
        if (rootProcess != null && rootProcess.isAlive() && rootInput != null && rootOutput != null) return;
        stopRootShellLocked();

        Context context = appContext;
        if (context == null) throw new IOException("Buddy application context is not initialized.");

        List<RootCommand> candidates = rootCommands(context);
        StringBuilder failures = new StringBuilder();
        for (RootCommand candidate : candidates) {
            Process process = null;
            BlockingQueue<OutputLine> output = new LinkedBlockingQueue<>();
            BufferedWriter input = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(candidate.args);
                builder.environment().put("LC_ALL", "C");
                process = builder.start();
                startReader(process.getInputStream(), true, output, "buddy-root-stdout");
                startReader(process.getErrorStream(), false, output, "buddy-root-stderr");
                input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

                String ready = "__BUDDY_ROOT_READY_" + UUID.randomUUID().toString().replace("-", "");
                input.write("id -u\n");
                input.write("echo " + ready + "\n");
                input.flush();

                String uid = null;
                StringBuilder startupErrors = new StringBuilder();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(START_TIMEOUT_SECONDS);
                boolean readySeen = false;
                while (!readySeen) {
                    OutputLine line = pollLine(output, deadline, "Timed out waiting for the KernelSU root shell.");
                    if (!line.stdout) {
                        appendLimited(startupErrors, line.text);
                        continue;
                    }
                    if (line.text == null) break;
                    String value = line.text.trim();
                    if (ready.equals(value)) {
                        readySeen = true;
                    } else if (!value.isEmpty()) {
                        uid = value;
                    }
                }

                if (readySeen && "0".equals(uid)) {
                    rootProcess = process;
                    rootInput = input;
                    rootOutput = output;
                    rootSource = candidate.label;
                    return;
                }

                String reason = !readySeen ? "root shell closed or did not answer"
                        : "root shell returned UID " + String.valueOf(uid);
                if (startupErrors.length() > 0) reason += "; stderr: " + startupErrors;
                failures.append(candidate.label).append(": ").append(reason).append(". ");
            } catch (Exception e) {
                failures.append(candidate.label).append(": ")
                        .append(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).append(". ");
            } finally {
                if (process != null && process != rootProcess) {
                    try { if (input != null) input.close(); } catch (IOException ignored) { }
                    process.destroyForcibly();
                }
            }
        }
        throw new IOException("Unable to open a UID 0 shell. " + failures.toString().trim());
    }

    private static List<RootCommand> rootCommands(Context context) {
        List<RootCommand> commands = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        for (String packageName : KSU_PACKAGES) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                if (info.nativeLibraryDir == null) continue;
                File daemon = new File(info.nativeLibraryDir, "libksud.so");
                if (daemon.isFile()) {
                    commands.add(new RootCommand(new String[]{daemon.getAbsolutePath(), "debug", "su"},
                            packageName + " libksud.so debug su"));
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // This manager is not installed; try the next supported root entry point.
            }
        }
        commands.add(new RootCommand(new String[]{"su"}, "su from app PATH"));
        return commands;
    }

    private static void startReader(InputStream stream, boolean stdout,
                                    BlockingQueue<OutputLine> output, String name) {
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) output.offer(new OutputLine(stdout, line));
            } catch (IOException ignored) {
            } finally {
                output.offer(new OutputLine(stdout, null));
            }
        }, name);
        reader.setDaemon(true);
        reader.start();
    }

    private static OutputLine pollLine(BlockingQueue<OutputLine> output, long deadline,
                                       String timeoutMessage) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException(timeoutMessage);
        try {
            OutputLine line = output.poll(remaining, TimeUnit.NANOSECONDS);
            if (line == null) throw new IOException(timeoutMessage);
            return line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the root shell.", e);
        }
    }

    private static void appendLimited(StringBuilder target, String value) {
        if (value == null || target.length() >= 2048) return;
        if (target.length() > 0) target.append(' ');
        int remaining = 2048 - target.length();
        target.append(value, 0, Math.min(value.length(), remaining));
    }

    private static void stopRootShellLocked() {
        try { if (rootInput != null) rootInput.close(); } catch (IOException ignored) { }
        if (rootProcess != null) rootProcess.destroyForcibly();
        rootProcess = null;
        rootInput = null;
        rootOutput = null;
        rootSource = "unknown";
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final class RootCommand {
        final String[] args;
        final String label;
        RootCommand(String[] args, String label) {
            this.args = args;
            this.label = label;
        }
    }

    private static final class OutputLine {
        final boolean stdout;
        final String text;
        OutputLine(boolean stdout, String text) {
            this.stdout = stdout;
            this.text = text;
        }
    }
}
