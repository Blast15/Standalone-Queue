package com.mineplugin.queue.model;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Đại diện cho một người chơi đang nằm trong hàng chờ.
 *
 * @author BlastSama
 */
public class QueuedPlayer implements Comparable<QueuedPlayer> {

    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE_GENERATOR = new java.util.concurrent.atomic.AtomicLong(0);

    private final Clock clock;
    private static final java.util.concurrent.atomic.AtomicLong LEASE_GENERATOR = new java.util.concurrent.atomic.AtomicLong();
    private volatile long reservationId;
    private boolean reservationExpiredNotice;
    private final UUID uuid;
    private final String username;
    private volatile String ipAddress;
    private volatile long sequenceNumber;
    private final long firstJoinedTimestamp;

    private volatile long lastPingTimestamp;
    private volatile int priority;
    private volatile long slotReservedUntilTimestamp;
    private volatile long lastAttemptTimestamp;

    public QueuedPlayer(UUID uuid, String username, String ipAddress, int priority) {
        this(uuid, username, ipAddress, priority, Clock.systemUTC());
    }

    public QueuedPlayer(UUID uuid, String username, String ipAddress, int priority, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.uuid = Objects.requireNonNull(uuid, "UUID cannot be null");
        this.username = username != null ? username : "Unknown";
        this.ipAddress = ipAddress != null ? ipAddress : "0.0.0.0";
        this.priority = priority;
        this.sequenceNumber = SEQUENCE_GENERATOR.incrementAndGet();
        long now = clock.millis();
        this.firstJoinedTimestamp = now;
        this.lastPingTimestamp = now;
        this.lastAttemptTimestamp = now;
        this.slotReservedUntilTimestamp = 0L;
    }

    /** Waiting-only restoration; historical lease and IP are deliberately absent. */
    public static QueuedPlayer restore(com.mineplugin.queue.storage.QueueEntry entry, int priority, Clock clock) {
        return new QueuedPlayer(entry, priority, clock);
    }

    private QueuedPlayer(com.mineplugin.queue.storage.QueueEntry entry, int priority, Clock clock) {
        this.clock = clock;
        this.uuid = entry.uuid(); this.username = entry.name(); this.ipAddress = "";
        this.sequenceNumber = entry.sequence(); this.priority = priority;
        this.firstJoinedTimestamp = entry.firstJoinedAt(); this.lastPingTimestamp = entry.lastPingAt();
        this.lastAttemptTimestamp = entry.lastPingAt();
        SEQUENCE_GENERATOR.accumulateAndGet(entry.sequence(), Math::max);
    }

    public com.mineplugin.queue.storage.QueueEntry durableEntry() {
        return new com.mineplugin.queue.storage.QueueEntry(uuid, username, sequenceNumber, firstJoinedTimestamp, lastPingTimestamp);
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

    /** Rebind a restored entry on its first real reconnect, under the manager's state lock. */
    public void bindRestoredAddress(String ip) {
        if (ipAddress.isEmpty()) ipAddress = ip == null || ip.isBlank() ? "unknown" : ip;
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
        recordPing(clock.millis());
    }

    public void recordPing(long now) {
        this.lastPingTimestamp = now;
        this.lastAttemptTimestamp = now;
    }

    /**
     * Cập nhật thời điểm kết nối gần nhất (dùng để kiểm tra rate limit).
     */
    public void recordAttempt() {
        this.lastAttemptTimestamp = clock.millis();
    }

    /**
     * Đặt thời hạn giữ slot cho người chơi này.
     * @param durationMs Thời gian giữ slot (mili-giây)
     */
    public void reserveSlot(long durationMs) {
        reserveSlot(durationMs, clock.millis());
    }

    public void reserveSlot(long durationMs, long now) {
        this.reservationId = LEASE_GENERATOR.incrementAndGet();
        this.slotReservedUntilTimestamp = durationMs > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + durationMs;
    }

    /** Monotonic lease token; zero means no lease. */
    public long getReservationId() {
        return reservationId;
    }

    /** Hủy bỏ việc giữ slot. */
    public void clearReservation() {
        this.reservationId = 0L;
        this.slotReservedUntilTimestamp = 0L;
    }

    /** Moves an expired reservation holder behind current peers at the same priority. */
    public void requeueAfterExpiredReservation() {
        clearReservation();
        reservationExpiredNotice = true;
        sequenceNumber = SEQUENCE_GENERATOR.incrementAndGet();
    }

    /** Engine-only administrative reorder; preserve heartbeat, priority and lease. */
    public void refreshSequence() { sequenceNumber = SEQUENCE_GENERATOR.incrementAndGet(); }

    public boolean consumeReservationExpiredNotice() {
        boolean notice = reservationExpiredNotice;
        reservationExpiredNotice = false;
        return notice;
    }

    /**
     * Kiểm tra xem người chơi này hiện có đang được giữ slot hợp lệ hay không.
     */
    public boolean hasActiveReservation() {
        return hasActiveReservation(clock.millis());
    }

    public boolean hasActiveReservation(long now) {
        return reservationId != 0 && now < slotReservedUntilTimestamp;
    }

    /**
     * Kiểm tra xem thời gian giữ slot đã hết hạn chưa.
     */
    public boolean isReservationExpired() {
        return isReservationExpired(clock.millis());
    }

    public boolean isReservationExpired(long now) {
        return reservationId != 0 && now >= slotReservedUntilTimestamp;
    }

    /**
     * Kiểm tra xem người chơi có bị quá hạn keep-alive (5 phút không ping) hay không.
     * @param maxKeepAliveMs Thời gian timeout tối đa (mili-giây)
     */
    public boolean isKeepAliveExpired(long maxKeepAliveMs) {
        return isKeepAliveExpired(maxKeepAliveMs, clock.millis());
    }

    public boolean isKeepAliveExpired(long maxKeepAliveMs, long now) {
        return Math.max(0L, now - lastPingTimestamp) >= maxKeepAliveMs;
    }

    /**
     * Tính toán thời gian giữ hàng chờ còn lại (tính bằng mili-giây).
     */
    public long getRemainingKeepAliveMs(long maxKeepAliveMs) {
        return getRemainingKeepAliveMs(maxKeepAliveMs, clock.millis());
    }

    public long getRemainingKeepAliveMs(long maxKeepAliveMs, long now) {
        long elapsed = Math.max(0L, now - lastPingTimestamp);
        return Math.max(0, maxKeepAliveMs - elapsed);
    }

    /** Detached point-in-time view. Mutators on this copy cannot change queue state. */
    public QueuedPlayer snapshot(long now) {
        return new QueuedPlayer(this, Clock.fixed(java.time.Instant.ofEpochMilli(now), java.time.ZoneOffset.UTC));
    }

    private QueuedPlayer(QueuedPlayer source, Clock clock) {
        this.clock = clock;
        this.uuid = source.uuid;
        this.username = source.username;
        this.ipAddress = source.ipAddress;
        this.sequenceNumber = source.sequenceNumber;
        this.firstJoinedTimestamp = source.firstJoinedTimestamp;
        this.lastPingTimestamp = source.lastPingTimestamp;
        this.lastAttemptTimestamp = source.lastAttemptTimestamp;
        this.priority = source.priority;
        this.slotReservedUntilTimestamp = source.slotReservedUntilTimestamp;
        this.reservationId = source.reservationId;
        this.reservationExpiredNotice = source.reservationExpiredNotice;
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
                ", lastPing=" + (clock.millis() - lastPingTimestamp) + "ms ago" +
                ", isReserved=" + hasActiveReservation() +
                '}';
    }
}
