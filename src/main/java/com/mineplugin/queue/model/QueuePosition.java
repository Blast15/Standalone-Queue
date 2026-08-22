package com.mineplugin.queue.model;

/**
 * Record chứa thông tin vị trí và thời gian chờ của người chơi để hiển thị lên màn hình kick.
 */
public record QueuePosition(
        int position,
        int total,
        long remainingKeepAliveSeconds,
        boolean isSlotReady,
        int priority
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

    /**
     * Ước tính thời gian chờ dựa trên vị trí (giả định trung bình 15-30s mỗi người).
     */
    public String getFormattedEta() {
        if (isSlotReady || position <= 1) {
            return "Ngay lập tức / Tiếp theo";
        }
        long estimatedSeconds = (long) position * 20L;
        long minutes = estimatedSeconds / 60;
        long seconds = estimatedSeconds % 60;

        if (minutes > 0) {
            return String.format("~%d phút %d giây", minutes, seconds);
        } else {
            return String.format("~%d giây", seconds);
        }
    }
}
