package com.codex.autolark;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootActions {
    static final class Result {
        final int code;
        final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }

    private RootActions() {}

    static Result openLark(int originalTimeout) {
        int timeout = Math.max(originalTimeout, 60_000);
        String command = "/system/bin/settings put system screen_brightness_mode 0"
                + " && /system/bin/settings put system screen_brightness 1"
                + " && /system/bin/settings put system screen_off_timeout " + timeout
                + " && /system/bin/input keyevent 224"
                + " && /system/bin/sleep 1"
                + " && /system/bin/wm dismiss-keyguard"
                + " && /system/bin/sleep 1"
                + " && /system/bin/monkey -p com.ss.android.lark"
                + " -c android.intent.category.LAUNCHER 1";
        return run(command, 15);
    }

    static Result closeLark(int originalTimeout) {
        // Restore the screen timeout even if force-stop has an error.
        String command = "/system/bin/am force-stop com.ss.android.lark;"
                + " /system/bin/input keyevent 3;"
                + " /system/bin/settings put system screen_off_timeout " + originalTimeout;
        return run(command, 12);
    }

    static Result run(String command, int timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(124, "timeout");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            InputStream stream = process.getInputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = stream.read(buffer)) != -1 && bytes.size() < 4096) {
                bytes.write(buffer, 0, read);
            }
            return new Result(process.exitValue(),
                    new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return new Result(125, e.toString());
        } finally {
            if (process != null) process.destroy();
        }
    }
}
