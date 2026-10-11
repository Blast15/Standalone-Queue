package com.mineplugin.queue.staging;

import com.mineplugin.queue.StandaloneQueuePlugin;
import com.mineplugin.queue.manager.QueueManager;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.config.QueueSettings;
import com.mineplugin.queue.permission.OperatorPermissionResolver;
import com.mineplugin.queue.permission.LuckPermsPermissionResolver;
import com.mineplugin.queue.storage.QueueSnapshotStore;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.plugin.java.JavaPlugin;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real installed LuckPerms/H2 checks. UUID fixtures are not Mojang-authenticated network clients. */
final class RealLuckPermsChecks {
    private static final UUID VIP = id("vip"), STAFF = id("staff"), CONTEXT = id("context"), HOLDER = id("holder");
    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(("StandaloneQueue-staging-" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
    private static void save(LuckPerms api, UUID uuid, PermissionNode... nodes) throws Exception {
        var user = api.getUserManager().loadUser(uuid).get(20, TimeUnit.SECONDS);
        try {
            user.data().clear();
            for (var node : nodes) user.data().add(node);
            api.getUserManager().saveUser(user).get(20, TimeUnit.SECONDS);
        } finally { api.getUserManager().cleanupUser(user); }
    }
    private static PermissionNode permission(String name, boolean value) {
        return PermissionNode.builder(name).value(value).build();
    }
    static void run(JavaPlugin probe, String action) {
        try {
            require(probe.getServer().getIp().equals("127.0.0.1"), "Loopback staging only");
            require(probe.getServer().getOnlineMode(), "Real server online-mode flag must be true");
            var plugin = (StandaloneQueuePlugin) probe.getServer().getPluginManager().getPlugin("StandaloneQueue");
            var api = LuckPermsProvider.get();
            require(probe.getServer().getPluginManager().isPluginEnabled("LuckPerms"), "Actual LuckPerms plugin required");
            if (action.equals("lp-seed")) {
                save(api, VIP, permission("queue.priority.vip", true), permission("queue.bypass", true));
                save(api, STAFF, permission("queue.priority.staff", true));
                probe.getLogger().info("SQLP lp-seed PASS storage-saved");
                return;
            }
            var clock = Clock.systemUTC();
            var config = plugin.getQueueConfig();
            var operators = new OperatorPermissionResolver(clock);
            var resolver = new LuckPermsPermissionResolver(api, config, operators, clock);
            operators.refresh(Set.of(), true);
            var persisted = resolver.resolve(VIP, "UntrustedName").get(20, TimeUnit.SECONDS);
            require(persisted.bypass() && persisted.priorityWeight() == 10, "H2 rights must survive restart");
            require(persisted.sourceVersion().equals("luckperms-storage"), "Actual storage resolver required");
            probe.getLogger().info("SQLP persisted-h2 PASS");

            operators.refresh(Set.of(), false);
            var offline = resolver.resolve(VIP, "UntrustedName").get(20, TimeUnit.SECONDS);
            require(!offline.bypass() && offline.priorityWeight() == 0, "Offline UUID/name must not grant rights");
            probe.getLogger().info("SQLP offline-identity-guard PASS");

            save(api, VIP, permission("queue.bypass", false));
            operators.refresh(Set.of(VIP), true);
            var deniedOp = resolver.resolve(VIP, "UntrustedName").get(20, TimeUnit.SECONDS);
            require(!deniedOp.bypass(), "Explicit LuckPerms deny must override OP fallback");
            save(api, VIP);
            operators.refresh(Set.of(VIP), true);
            require(!resolver.resolve(VIP, "UntrustedName").get(20, TimeUnit.SECONDS).bypass(), "Registered Bukkit default false must override OP fallback");
            probe.getLogger().info("SQLP explicit-deny-and-bukkit-default PASS");

            save(api, CONTEXT, PermissionNode.builder("queue.priority.staff").value(true).withContext("world", "staging").build());
            operators.refresh(Set.of(), true);
            require(resolver.resolve(CONTEXT, "Context").get(20, TimeUnit.SECONDS).priorityWeight() == 0, "World context must not leak into static pre-login context");
            save(api, CONTEXT, PermissionNode.builder("queue.priority.vip").value(true).withContext("server", "sqstaging").build());
            operators.refresh(Set.of(), true);
            require(resolver.resolve(CONTEXT, "Context").get(20, TimeUnit.SECONDS).priorityWeight() == 10, "Matching static server context must resolve");
            save(api, CONTEXT, PermissionNode.builder("queue.priority.staff").value(true).withContext("server", "different").build());
            operators.refresh(Set.of(), true);
            require(resolver.resolve(CONTEXT, "Context").get(20, TimeUnit.SECONDS).priorityWeight() == 0, "Nonmatching static server context must not grant");
            probe.getLogger().info("SQLP static-contexts PASS");

            save(api, VIP, permission("queue.priority.vip", true));
            var queue = plugin.getQueueManager();
            queue.clearQueue();
            require(queue.handlePreLogin(HOLDER, "HolderFixture", "192.0.2.1").type() == QueueManager.LoginResultType.ALLOWED_DIRECT, "Holder admission");
            require(queue.handlePreLogin(VIP, "VipFixture", "192.0.2.2").type() == QueueManager.LoginResultType.IN_QUEUE, "VIP queued");
            require(queue.handlePreLogin(STAFF, "StaffFixture", "192.0.2.3").type() == QueueManager.LoginResultType.IN_QUEUE, "Staff queued");
            require(queue.getQueueList().stream().map(p -> p.getUuid()).toList().equals(List.of(STAFF, VIP)), "Real backend tiers determine queue order");
            require(queue.getQueueList().get(0).getPriority() == 50 && queue.getQueueList().get(1).getPriority() == 10, "Weights from stored rights");
            probe.getLogger().info("SQLP backend-priority-order PASS");

            save(api, VIP, permission("queue.bypass", true));
            require(queue.handlePreLogin(VIP, "VipFixture", "192.0.2.2").type() == QueueManager.LoginResultType.ALLOWED_BYPASS, "Fresh bypass grant");
            require(!queue.isInQueue(VIP), "Bypass removes queued identity");
            queue.onConnectionClose(VIP, "192.0.2.2");
            save(api, VIP);
            require(queue.handlePreLogin(VIP, "VipFixture", "192.0.2.2").type() == QueueManager.LoginResultType.IN_QUEUE, "Revoke must be observed without plugin restart");
            require(queue.getQueueList().stream().filter(p -> p.getUuid().equals(VIP)).findFirst().orElseThrow().getPriority() == 0, "Revoked priority must not remain cached");
            probe.getLogger().info("SQLP live-grant-revoke PASS");

            save(api, STAFF);
            var changed = new HashMap<>(config.getPermissionPriorities());
            queue.reload(config.snapshot()).get(20, TimeUnit.SECONDS);
            require(queue.getQueueList().stream().allMatch(p -> p.getPriority() == 0), "Reload recalculates real backend revoked tiers");
            probe.getLogger().info("SQLP reload-reconciliation PASS");

            save(api, STAFF, permission("queue.priority.staff", true));
            queue.reload(config.snapshot()).get(20, TimeUnit.SECONDS);
            var entries = queue.persistenceSnapshot();
            var directory = probe.getDataFolder().toPath().resolve("real-lp-recovery");
            try (var store = new QueueSnapshotStore(directory)) { store.write(entries); }
            save(api, STAFF); // downtime revoke, before recovery transaction
            operators.refresh(Set.of(), true);
            var restored = new QueueManager(plugin, config, resolver, clock);
            restored.refreshServerCapacity();
            restored.beginRecovery();
            try (var store = new QueueSnapshotStore(directory)) {
                require(restored.restoreEntries(store.read()) == 2, "WAITING identities survive snapshot");
            }
            restored.finishRecovery();
            require(restored.getQueueList().stream().allMatch(p -> p.getPriority() == 0), "Recovery resolves revoked backend rights");
            require(restored.getInFlightCount() == 0, "In-flight does not cross recovery");
            restored.stopAndSnapshot(); restored.clearRuntimeState();
            queue.onConnectionClose(HOLDER, "192.0.2.1");
            queue.clearQueue();
            probe.getLogger().info("SQLP recovery-revocation PASS");
            probe.getLogger().info("SQLP lp-check PASS all-real-backend-checks");
        } catch (Throwable error) {
            probe.getLogger().log(java.util.logging.Level.SEVERE, "SQLP " + action + " FAIL " + error, error);
        }
    }
}
