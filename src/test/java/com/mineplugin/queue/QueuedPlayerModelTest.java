package com.mineplugin.queue;

import com.mineplugin.queue.model.QueuePosition;
import com.mineplugin.queue.model.QueuedPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test cho QueuedPlayer và QueuePosition models.
 *
 * @author BlastSama
 */
public class QueuedPlayerModelTest {

    @Test
    @DisplayName("Model Test 1: QueuedPlayer compareTo sắp xếp đúng theo Priority và Sequence Number (FIFO)")
    public void testQueuedPlayerComparison() throws InterruptedException {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();

        QueuedPlayer playerNormal1 = new QueuedPlayer(u1, "Normal1", "127.0.0.1", 0);
        QueuedPlayer playerNormal2 = new QueuedPlayer(u2, "Normal2", "127.0.0.1", 0);
        QueuedPlayer playerVip = new QueuedPlayer(u3, "VIPUser", "127.0.0.1", 10);

        // Same reference
        assertEquals(0, playerNormal1.compareTo(playerNormal1));

        // VIP (priority 10) phải đứng trước Normal (priority 0)
        assertTrue(playerVip.compareTo(playerNormal1) < 0);
        assertTrue(playerNormal1.compareTo(playerVip) > 0);

        // Cùng priority 0: Normal1 vào trước Normal2 -> Normal1 đứng trước
        assertTrue(playerNormal1.compareTo(playerNormal2) < 0);
        assertTrue(playerNormal2.compareTo(playerNormal1) > 0);

        // Null comparison
        assertTrue(playerNormal1.compareTo(null) < 0);
    }

    @Test
    @DisplayName("Model Test 2: Quản lý Slot Reservation và thời hạn hết hạn")
    public void testSlotReservation() throws InterruptedException {
        UUID u = UUID.randomUUID();
        MutableClock clock = new MutableClock();
        QueuedPlayer player = new QueuedPlayer(u, "TestUser", "127.0.0.1", 0, clock);

        assertFalse(player.hasActiveReservation());
        assertFalse(player.isReservationExpired());

        // Giữ slot 100ms
        player.reserveSlot(100L);
        assertTrue(player.hasActiveReservation());
        assertFalse(player.isReservationExpired());

        clock.advance(150);
        assertFalse(player.hasActiveReservation(), "Sau 150ms thì reservation không còn active");
        assertTrue(player.isReservationExpired(), "Sau 150ms thì reservation đã hết hạn");

        // Clear reservation
        player.clearReservation();
        assertFalse(player.hasActiveReservation());
        assertFalse(player.isReservationExpired());
    }

    @Test
    @DisplayName("Model Test 3: Quản lý Keep-Alive Heartbeat và gia hạn ping")
    public void testKeepAliveHeartbeat() throws InterruptedException {
        UUID u = UUID.randomUUID();
        MutableClock clock = new MutableClock();
        QueuedPlayer player = new QueuedPlayer(u, "TestUser", "127.0.0.1", 0, clock);

        // Chưa hết hạn
        assertFalse(player.isKeepAliveExpired(1000L));
        assertTrue(player.getRemainingKeepAliveMs(1000L) > 800L);

        // Đợi 150ms
        clock.advance(150);
        assertTrue(player.isKeepAliveExpired(100L));

        // Người chơi ping lại (recordPing) -> reset heartbeat
        player.recordPing();
        assertFalse(player.isKeepAliveExpired(1000L));
    }

    @Test
    @DisplayName("Model Test 4: Định dạng chuỗi hiển thị của QueuePosition")
    public void testQueuePositionFormatting() {
        // Hết hạn
        QueuePosition posExpired = new QueuePosition(1, 10, 0L, false, 0);
        assertEquals("0s (Hết hạn)", posExpired.getFormattedTimeLeft());

        // Dưới 1 phút
        QueuePosition posSec = new QueuePosition(1, 10, 45L, false, 0);
        assertEquals("45s", posSec.getFormattedTimeLeft());

        // Trên 1 phút
        QueuePosition posMin = new QueuePosition(1, 10, 298L, false, 0);
        assertEquals("4m 58s", posMin.getFormattedTimeLeft());

        // ETA khi slot ready hoặc vị trí #1
        QueuePosition posReady = new QueuePosition(1, 10, 300L, true, 0);
        assertEquals("Ngay lập tức / Tiếp theo", posReady.getFormattedEta());

        // ETA vị trí #2 (40s)
        QueuePosition pos2 = new QueuePosition(2, 10, 300L, false, 0);
        assertEquals("~40 giây", pos2.getFormattedEta());

        // ETA vị trí #5 (100s = 1m 40s)
        QueuePosition pos5 = new QueuePosition(5, 10, 300L, false, 0);
        assertEquals("~1 phút 40 giây", pos5.getFormattedEta());
    }

    @Test
    @DisplayName("Model Test 5: equals và hashCode theo UUID")
    public void testEqualsAndHashCode() {
        UUID u = UUID.randomUUID();
        QueuedPlayer p1 = new QueuedPlayer(u, "Name1", "1.1.1.1", 0);
        QueuedPlayer p2 = new QueuedPlayer(u, "Name2", "2.2.2.2", 10);

        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
        assertNotEquals(p1, new QueuedPlayer(UUID.randomUUID(), "Name1", "1.1.1.1", 0));
    }
}
