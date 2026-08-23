package com.mineplugin.queue.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Đại diện cho một người chơi đang nằm trong hàng chờ.
 * Thread-safe và hỗ trợ theo dõi heartbeat (lastPing), ưu tiên (priority) và giữ chỗ (slot reservation).
 */
public class QueuedPlayer implements Comparable<QueuedPlayer> {

    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE_GENERATOR = new java.util.concurrent.atomic.AtomicLong(0);

    private final UUID uuid;
    private final String username;
    private final String ipAddress;
    private final long sequenceNumber;
    private final long firstJoinedTimestamp;

    private volatile long lastPingTimestamp;
    private volatile int priority;
    private volatile long slotReservedUntilTimestamp;
    private volatile long lastAttemptTimestamp;

    public QueuedPlayer(UUID uuid, String username, String ipAddress, int priority) {
        this.uuid = Objects.requireNonNull(uuid, "UUID cannot be null");
        this.username = username != null ? username : "Unknown";
        this.ipAddress = ipAddress != null ? ipAddress : "0.0.0.0";
        this.priority = priority;
        this.sequenceNumber = SEQUENCE_GENERATOR.incrementAndGet();
        long now = System.currentTimeMillis();
        this.firstJoinedTimestamp = now;
        this.lastPingTimestamp = now;
        this.lastAttemptTimestamp = now;
        this.slotReservedUntilTimestamp = 0L;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getUsername() {
        return username;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public long getFirstJoinedTimestamp() {
        return firstJoinedTimestamp;
    }

    public long getLastPingTimestamp() {
        return lastPingTimestamp;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public long getSlotReservedUntilTimestamp() {
        return slotReservedUntilTimestamp;
    }

    public long getLastAttemptTimestamp() {
        return lastAttemptTimestamp;
    }

    /**
     * Cập nhật thời gian ping (heartbeat) khi người chơi thử kết nối lại.
     */
    public void recordPing() {
        long now = System.currentTimeMillis();
        this.lastPingTimestamp = now;
        this.lastAttemptTimestamp = now;
    }

    /**
     * Cập nhật thời điểm kết nối gần nhất (dùng để kiểm tra rate limit).
     */
    public void recordAttempt() {
        this.lastAttemptTimestamp = System.currentTimeMillis();
    }

    /**
     * Đặt thời hạn giữ slot cho người chơi này.
     * @param durationMs Thời gian giữ slot (mili-giây)
     */
    public void reserveSlot(long durationMs) {
        this.slotReservedUntilTimestamp = System.currentTimeMillis() + durationMs;
    }

    /**
     * Hủy bỏ việc giữ slot.
     */
    public void clearReservation() {
        this.slotReservedUntilTimestamp = 0L;
    }

    /**
     * Kiểm tra xem người chơi này hiện có đang được giữ slot hợp lệ hay không.
     */
    public boolean hasActiveReservation() {
        return slotReservedUntilTimestamp > 0 && System.currentTimeMillis() <= slotReservedUntilTimestamp;
    }

    /**
     * Kiểm tra xem thời gian giữ slot đã hết hạn chưa.
     */
    public boolean isReservationExpired() {
        return slotReservedUntilTimestamp > 0 && System.currentTimeMillis() > slotReservedUntilTimestamp;
    }

    /**
     * Kiểm tra xem người chơi có bị quá hạn keep-alive (5 phút không ping) hay không.
     * @param maxKeepAliveMs Thời gian timeout tối đa (mili-giây)
     */
    public boolean isKeepAliveExpired(long maxKeepAliveMs) {
        return (System.currentTimeMillis() - lastPingTimestamp) > maxKeepAliveMs;
    }

    /**
     * Tính toán thời gian giữ hàng chờ còn lại (tính bằng mili-giây).
     */
    public long getRemainingKeepAliveMs(long maxKeepAliveMs) {
        long elapsed = System.currentTimeMillis() - lastPingTimestamp;
        return Math.max(0, maxKeepAliveMs - elapsed);
    }

    /**
     * So sánh thứ tự trong hàng chờ:
     * 1. Ưu tiên cao hơn (Priority lớn hơn) đứng trước.
     * 2. Nếu cùng ưu tiên, người vào trước (firstJoinedTimestamp nhỏ hơn) đứng trước (FIFO).
     */
    @Override
    public int compareTo(QueuedPlayer other) {
        if (this == other) return 0;
        if (other == null) return -1;
        if (this.uuid.equals(other.uuid)) return 0;

        // So sánh độ ưu tiên (descending: 50 -> 20 -> 10 -> 0)
        int priorityComparison = Integer.compare(other.priority, this.priority);
        if (priorityComparison != 0) {
            return priorityComparison;
        }

        // So sánh thứ tự vào hàng chờ theo sequenceNumber (FIFO tuyệt đối)
        int seqComparison = Long.compare(this.sequenceNumber, other.sequenceNumber);
        if (seqComparison != 0) {
            return seqComparison;
        }

        return this.uuid.compareTo(other.uuid);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QueuedPlayer that)) return false;
        return Objects.equals(uuid, that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid);
    }

    @Override
    public String toString() {
        return "QueuedPlayer{" +
                "uuid=" + uuid +
                ", username='" + username + '\'' +
                ", priority=" + priority +
                ", lastPing=" + (System.currentTimeMillis() - lastPingTimestamp) + "ms ago" +
                ", isReserved=" + hasActiveReservation() +
                '}';
    }
}
