package com.mineplugin.queue;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.config.QueueSettings;
import com.mineplugin.queue.listener.PlayerConnectionListener;
import com.mineplugin.queue.listener.PreLoginListener;
import com.mineplugin.queue.manager.CapacitySource;
import com.mineplugin.queue.manager.QueueManager;
import com.mineplugin.queue.model.LoginAttempt;
import com.mineplugin.queue.permission.LuckPermsPermissionResolver;
import com.mineplugin.queue.permission.OperatorPermissionResolver;
import com.mineplugin.queue.permission.PermissionResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.util.Tristate;
import org.bukkit.entity.Player;
import org.bukkit.event.player.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.mineplugin.queue.LuckPermsPermissionResolverTest.stub;
import static org.junit.jupiter.api.Assertions.*;

/** Real Paper API event types through the production listeners. No live server or authentication is simulated as proven. */
class PaperLoginPipelineIT {
    private final MutableClock clock = new MutableClock();
    private final QueueConfig config = new QueueConfig(PhaseTwoRegressionTest.settings(1));
    private final Thread mainThread = Thread.currentThread();
    private final AtomicInteger online = new AtomicInteger();
    private final CapacitySource capacity = () -> {
        assertSame(mainThread, Thread.currentThread(), "Only main-thread lifecycle may read server capacity");
        return new CapacitySource.Snapshot(online.get(), 1);
    };
    private QueueManager manager = new QueueManager(null, config, new FakePermissionResolver(clock), clock, capacity);
    private PreLoginListener pre = new PreLoginListener(manager, config);
    private PlayerConnectionListener connection = new PlayerConnectionListener(manager, config);
    private final InetAddress address = InetAddress.getLoopbackAddress();

    private void resolver(PermissionResolver resolver) {
        manager = new QueueManager(null, config, resolver, clock, capacity);
        pre = new PreLoginListener(manager, config);
        connection = new PlayerConnectionListener(manager, config);
    }

    private Player player(UUID uuid, String name) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uuid;
            case "getName" -> name;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> name;
            default -> throw new AssertionError("Unexpected Bukkit player call: " + method.getName());
        });
    }

    private AsyncPlayerPreLoginEvent event(UUID uuid, String name) {
        PlayerProfile profile = stub(PlayerProfile.class, (method, args) -> switch (method) {
            case "getId" -> uuid;
            case "getName" -> name;
            default -> throw new AssertionError(method);
        });
        return new AsyncPlayerPreLoginEvent(name, address, address, uuid, false, profile);
    }

    private void async(Runnable action) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(action).get(5, TimeUnit.SECONDS);
        }
    }

    private AsyncPlayerPreLoginEvent admit(UUID uuid, String name) throws Exception {
        var event = event(uuid, name);
        async(() -> { pre.onAsyncPreLogin(event); pre.onAsyncPreLoginComplete(event); });
        return event;
    }

    private PlayerLoginEvent login(Player player, PlayerLoginEvent.Result result) {
        var event = new PlayerLoginEvent(player, "staging", address, result, Component.text("original rejection"), address);
        connection.onPlayerLogin(event);
        connection.onPlayerLoginComplete(event);
        return event;
    }

    private void join(Player player) {
        online.incrementAndGet();
        connection.onPlayerJoin(new PlayerJoinEvent(player, Component.empty()));
    }

    private void close(UUID uuid, String name) throws Exception {
        async(() -> connection.onConnectionClose(new PlayerConnectionCloseEvent(uuid, name, address, true)));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void onlineLuckPermsAndOfflineOrdinaryRightsRunFullQueueLoginQuitPipeline(boolean onlineMode) throws Exception {
        UUID occupied = UUID.randomUUID(), normal = UUID.randomUUID(), vip = UUID.randomUUID();
        OperatorPermissionResolver operators = new OperatorPermissionResolver(clock);
        operators.refresh(Set.of(vip), onlineMode);
        AtomicInteger storageLoads = new AtomicInteger();
        CachedPermissionData rights = stub(CachedPermissionData.class, (method, args) ->
                args[0].equals("queue.priority.vip") ? Tristate.TRUE : Tristate.FALSE);
        var container = stub(CachedDataManager.Container.class, (method, args) -> rights);
        CachedDataManager cache = stub(CachedDataManager.class, (method, args) -> container);
        User privileged = stub(User.class, (method, args) -> cache);
        CachedPermissionData ordinaryRights = stub(CachedPermissionData.class, (method, args) -> Tristate.FALSE);
        var ordinaryContainer = stub(CachedDataManager.Container.class, (method, args) -> ordinaryRights);
        CachedDataManager ordinaryCache = stub(CachedDataManager.class, (method, args) -> ordinaryContainer);
        User ordinary = stub(User.class, (method, args) -> ordinaryCache);
        UserManager users = stub(UserManager.class, (method, args) -> {
            if (method.equals("loadUser")) { storageLoads.incrementAndGet(); return CompletableFuture.completedFuture(args[0].equals(vip) ? privileged : ordinary); }
            if (method.equals("cleanupUser")) return null;
            throw new AssertionError(method);
        });
        ContextManager contexts = stub(ContextManager.class, (method, args) -> null);
        LuckPerms api = stub(LuckPerms.class, (method, args) -> method.equals("getUserManager") ? users : contexts);
        var yaml = PhaseTwoRegressionTest.settings(1);
        yaml.set("priorities.vip.permission", "queue.priority.vip");
        yaml.set("priorities.vip.weight", 20);
        config.load(yaml);
        resolver(new LuckPermsPermissionResolver(api, config, operators, clock));
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, admit(occupied, "Occupied").getLoginResult());
        Player player = player(occupied, "Occupied");
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player, PlayerLoginEvent.Result.KICK_FULL).getResult());
        join(player);
        assertEquals(0, manager.getInFlightCount());
        assertEquals(1, manager.getOnlinePlayerCount());
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_FULL, admit(normal, "Normal").getLoginResult());
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_FULL, admit(vip, "Vip").getLoginResult());
        assertEquals(onlineMode ? vip : normal, manager.getQueueList().getFirst().getUuid());
        assertEquals(onlineMode ? 3 : 0, storageLoads.get());
        online.decrementAndGet();
        connection.onPlayerQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        awaitCleanup();
        assertEquals(1, manager.getReservedSlotCount());
        close(occupied, "Occupied");
        UUID owner = onlineMode ? vip : normal;
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, admit(owner, "Next").getLoginResult());
        Player next = player(owner, "Next");
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(next, PlayerLoginEvent.Result.KICK_FULL).getResult());
        join(next);
        assertEquals(1, manager.getActiveOccupiedSlots());
        assertEquals(LoginAttempt.Phase.JOINED, manager.getAttempt(owner).phase());
        manager.reload(QueueSettings.parse(yaml)).get(5, TimeUnit.SECONDS);
        assertEquals(1, manager.getQueueSize());
    }

    private void awaitCleanup() throws Exception { async(manager::cleanupTick); }

    @ParameterizedTest @EnumSource(value = PlayerLoginEvent.Result.class, names = {"KICK_BANNED", "KICK_WHITELIST", "KICK_OTHER"})
    void banWhitelistAndOtherPluginRejectionsSurviveAndReleaseCapacity(PlayerLoginEvent.Result result) throws Exception {
        UUID uuid = UUID.randomUUID();
        admit(uuid, "Rejected");
        var login = login(player(uuid, "Rejected"), result);
        assertEquals(result, login.getResult());
        assertEquals("original rejection", PlainTextComponentSerializer.plainText().serialize(login.kickMessage()));
        assertEquals(0, manager.getInFlightCount());
        assertEquals(1, manager.getTrackedConnectionCount());
        close(uuid, "Rejected");
        assertEquals(0, manager.getTrackedConnectionCount());
    }

    @Test void latePreLoginCancellationHasNoCloseEventAndNoLeak() throws Exception {
        UUID uuid = UUID.randomUUID();
        var event = event(uuid, "LateReject");
        async(() -> {
            pre.onAsyncPreLogin(event);
            assertEquals(1, manager.getInFlightCount());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("other plugin"));
            pre.onAsyncPreLoginComplete(event);
        });
        assertEquals(0, manager.getTrackedConnectionCount());
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, admit(uuid, "Retry").getLoginResult());
    }

    @Test void rejectedDuplicatePreLoginCannotCancelExistingAcceptedToken() throws Exception {
        UUID uuid = UUID.randomUUID();
        admit(uuid, "Accepted");
        UUID token = manager.getAttemptId(uuid);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, admit(uuid, "Duplicate").getLoginResult());
        assertEquals(token, manager.getAttemptId(uuid));
        assertEquals(1, manager.getInFlightCount());
    }

    @Test void admissionDeadlineRejectsLateLoginWithoutGivingSlotAwayEarly() throws Exception {
        UUID uuid = UUID.randomUUID();
        admit(uuid, "Delayed");
        clock.advance(30_001);
        awaitCleanup();
        assertEquals(1, manager.getInFlightCount());
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_FULL, admit(UUID.randomUUID(), "Waiting").getLoginResult());
        var login = login(player(uuid, "Delayed"), PlayerLoginEvent.Result.ALLOWED);
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, login.getResult());
        assertTrue(PlainTextComponentSerializer.plainText().serialize(login.kickMessage()).contains("hết hạn"));
        assertEquals(0, manager.getInFlightCount());
        close(uuid, "Delayed");
        assertEquals(0, manager.getTrackedConnectionCount());
    }

    @Test void loginAcceptedBeforeDeadlineMayJoinAfterDeadlineWithoutDoubleCapacity() throws Exception {
        UUID uuid = UUID.randomUUID();
        admit(uuid, "SlowConfiguration");
        Player player = player(uuid, "SlowConfiguration");
        assertEquals(PlayerLoginEvent.Result.ALLOWED, login(player, PlayerLoginEvent.Result.ALLOWED).getResult());
        clock.advance(120_000);
        awaitCleanup();
        assertEquals(1, manager.getInFlightCount());
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_FULL, admit(UUID.randomUUID(), "Waiting").getLoginResult());
        join(player);
        assertEquals(0, manager.getInFlightCount());
        assertEquals(1, manager.getActiveOccupiedSlots());
        assertEquals(1, manager.getInFlightTimeoutCount());
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, admit(uuid, "ReconnectBeforeClose").getLoginResult());
    }

    @Test void missingGrantDoesNotOverrideFullOrAllowedAndTransportFailureClosesCleanly() throws Exception {
        Player missing = player(UUID.randomUUID(), "Missing");
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, login(missing, PlayerLoginEvent.Result.ALLOWED).getResult());
        assertEquals(PlayerLoginEvent.Result.KICK_OTHER, login(missing, PlayerLoginEvent.Result.KICK_FULL).getResult());
        UUID uuid = UUID.randomUUID();
        admit(uuid, "NetworkFailure");
        close(uuid, "NetworkFailure");
        assertEquals(0, manager.getTrackedConnectionCount());
    }

    @Test void monitorRejectionAndStalePlayerIdentityCannotReleaseDifferentConnection() throws Exception {
        UUID uuid = UUID.randomUUID();
        admit(uuid, "Original");
        Player player = player(uuid, "Original");
        var event = new PlayerLoginEvent(player, "staging", address);
        connection.onPlayerLogin(event);
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, Component.text("late plugin rejection"));
        connection.onPlayerLoginComplete(event);
        assertEquals(0, manager.getInFlightCount());
        close(uuid, "Original");
        admit(uuid, "New");
        connection.onPlayerLoginComplete(event);
        connection.onPlayerQuit(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(1, manager.getInFlightCount());
    }

    @Test void expiredReservationUsesNewPositionOnceThroughActualDisconnectEvent() throws Exception {
        var yaml = PhaseTwoRegressionTest.settings(1);
        yaml.set("queue.slot-reservation-seconds", 1);
        yaml.set("messages.kick-reservation-expired", "&cEXPIRED #<position>/<total>");
        config.load(yaml);
        UUID occupied = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        admit(occupied, "Occupied"); admit(first, "First"); admit(second, "Second");
        close(occupied, "Occupied");
        awaitCleanup();
        clock.advance(1000);
        awaitCleanup();
        var expired = admit(first, "First");
        assertEquals("EXPIRED #2/2", PlainTextComponentSerializer.plainText().serialize(expired.kickMessage()));
        assertFalse(PlainTextComponentSerializer.plainText().serialize(admit(first, "First").kickMessage()).contains("EXPIRED"));
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void disabledQueueObservationNeverOverridesPaperFullEvenAfterEnable(boolean enableBeforeLogin) throws Exception {
        var yaml = PhaseTwoRegressionTest.settings(1);
        yaml.set("queue.enabled", false);
        config.load(yaml);
        UUID uuid = UUID.randomUUID();
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, admit(uuid, "Observed").getLoginResult());
        assertFalse(manager.getAttempt(uuid).fullOverrideGranted());
        if (enableBeforeLogin) manager.reload(QueueSettings.parse(PhaseTwoRegressionTest.settings(1))).get(5, TimeUnit.SECONDS);
        assertEquals(PlayerLoginEvent.Result.KICK_FULL, login(player(uuid, "Observed"), PlayerLoginEvent.Result.KICK_FULL).getResult());
        assertEquals(0, manager.getInFlightCount());
        close(uuid, "Observed");
        assertEquals(0, manager.getTrackedConnectionCount());
    }

}
