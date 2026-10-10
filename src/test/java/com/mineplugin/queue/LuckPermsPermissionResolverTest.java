package com.mineplugin.queue;

import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.permission.LuckPermsPermissionResolver;
import com.mineplugin.queue.permission.OperatorPermissionResolver;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.util.Tristate;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class LuckPermsPermissionResolverTest {
    @SuppressWarnings("unchecked")
    static <T> T stub(Class<T> type, BiFunction<String, Object[], Object> call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.apply(method.getName(), args));
    }

    @Test
    void loadsUuidOnEveryAttemptHonorsExplicitDenialAndCleansUp() {
        MutableClock clock = new MutableClock();
        UUID uuid = UUID.randomUUID();
        OperatorPermissionResolver operators = new OperatorPermissionResolver(clock);
        operators.refresh(Set.of(uuid), true);
        AtomicReference<Tristate> bypass = new AtomicReference<>(Tristate.TRUE);
        AtomicInteger loads = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        CachedPermissionData data = stub(CachedPermissionData.class, (method, args) -> {
            if (method.equals("checkPermission")) {
                return args[0].equals("queue.bypass") ? bypass.get() : Tristate.TRUE;
            }
            throw new AssertionError(method);
        });
        var container = stub(net.luckperms.api.cacheddata.CachedDataManager.Container.class,
                (method, args) -> data);
        CachedDataManager cache = stub(CachedDataManager.class, (method, args) -> container);
        User user = stub(User.class, (method, args) -> cache);
        UserManager users = stub(UserManager.class, (method, args) -> {
            if (method.equals("loadUser")) {
                assertEquals(uuid, args[0]);
                loads.incrementAndGet();
                return CompletableFuture.completedFuture(user);
            }
            if (method.equals("cleanupUser")) { cleanups.incrementAndGet(); return null; }
            throw new AssertionError(method);
        });
        ContextManager contexts = stub(ContextManager.class, (method, args) -> null);
        LuckPerms api = stub(LuckPerms.class, (method, args) -> switch (method) {
            case "getUserManager" -> users;
            case "getContextManager" -> contexts;
            default -> throw new AssertionError(method);
        });
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("priorities.vip.permission", "queue.priority.vip");
        yaml.set("priorities.vip.weight", 20);
        var resolver = new LuckPermsPermissionResolver(api, new QueueConfig(yaml), operators, clock);
        var first = resolver.resolve(uuid, "AnyName").join();
        assertTrue(first.bypass());
        assertEquals(20, first.priorityWeight());
        bypass.set(Tristate.FALSE);
        assertFalse(resolver.resolve(uuid, "AnyName").join().bypass());
        bypass.set(Tristate.UNDEFINED);
        assertTrue(resolver.resolve(uuid, "AnyName").join().bypass());
        var candidate = resolver.resolve(uuid, "AnyName", java.util.Map.of("queue.priority.vip", 70)).join();
        assertEquals(70, candidate.priorityWeight());
        assertEquals(20, resolver.resolve(uuid, "AnyName").join().priorityWeight());
        assertEquals(5, loads.get());
        assertEquals(5, cleanups.get());
    }

    @Test
    void outstandingStorageCallsAreBoundedAndOfflineIdentitiesNeverLoad() {
        MutableClock clock = new MutableClock();
        var operators = new OperatorPermissionResolver(clock);
        AtomicInteger loads = new AtomicInteger();
        CompletableFuture<User> pending = new CompletableFuture<>();
        UserManager users = stub(UserManager.class, (method, args) -> {
            loads.incrementAndGet();
            return pending;
        });
        LuckPerms api = stub(LuckPerms.class, (method, args) -> users);
        var resolver = new LuckPermsPermissionResolver(api, new QueueConfig(new YamlConfiguration()), operators, clock);
        assertFalse(resolver.resolve(UUID.randomUUID(), "Offline").join().bypass());
        assertEquals(0, loads.get());
        operators.refresh(Set.of(), true);
        for (int i = 0; i < 64; i++) assertFalse(resolver.resolve(UUID.randomUUID(), "Online").isDone());
        assertFalse(resolver.resolve(UUID.randomUUID(), "Overflow").join().bypass());
        assertEquals(64, loads.get());
        pending.completeExceptionally(new IllegalStateException("storage failed"));
        assertTrue(resolver.resolve(UUID.randomUUID(), "Retry").isCompletedExceptionally());
        assertEquals(65, loads.get());
    }
}
