package com.mineplugin.queue.model;

/**
 * Record chứa thông tin vị trí và thời gian chờ của người chơi.
 *
 * @author BlastSama
 */
public record QueuePosition(
        int position,
        int total,
        long remainingKeepAliveSeconds,
        boolean isSlotReady,
        int priority,
        String estimatedWait
) {

    /**
     * Định dạng thời gian giữ vị trí còn lại (ví dụ: "4m 58s" hoặc "35s").
     */
    public String getFormattedTimeLeft() {
        if (remainingKeepAliveSeconds <= 0) {
            return "0s (Hết hạn)";
        }
        long minutes = remainingKeepAliveSeconds / 60;
        long seconds = remainingKeepAliveSeconds % 60;

        if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }

    public QueuePosition(int position, int total, long remainingKeepAliveSeconds, boolean isSlotReady, int priority) {
        this(position, total, remainingKeepAliveSeconds, isSlotReady, priority, "Đang cập nhật");
    }

    public String getFormattedEta() {
        return isSlotReady ? "Ngay lập tức / Tiếp theo" : estimatedWait;
    }
}
