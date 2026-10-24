package io.sakurammo.util;

/**
 * 毫秒 → 人类可读时长（用于冷却提示）。
 */
public final class TimeFormat {

    private TimeFormat() {
    }

    public static String humanize(long millis) {
        if (millis <= 0) {
            return "0s";
        }
        long totalSeconds = (long) Math.ceil(millis / 1000.0);
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes < 60) {
            return seconds == 0 ? minutes + "m" : minutes + "m " + seconds + "s";
        }
        long hours = minutes / 60;
        long restMinutes = minutes % 60;
        return restMinutes == 0 ? hours + "h" : hours + "h " + restMinutes + "m";
    }
}
