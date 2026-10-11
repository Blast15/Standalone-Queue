package com.mineplugin.queue.staging;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.mineplugin.queue.StandaloneQueuePlugin;
import com.mineplugin.queue.manager.QueueManager;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Optional staging-only companion. Each fixture targets one name and is consumed once. */
public final class StagingProbePlugin extends JavaPlugin implements Listener {
    private record Fixture(String action, String name, long delayMs) {}
    private final java.util.concurrent.atomic.AtomicReference<Fixture> fixture = new java.util.concurrent.atomic.AtomicReference<>();
    private final Map<UUID, UUID> traceTokens = new ConcurrentHashMap<>();
    private QueueManager queue;

    @Override public void onEnable() {
        queue = ((StandaloneQueuePlugin) getServer().getPluginManager().getPlugin("StandaloneQueue")).getQueueManager();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Staging probe active. Fixtures are unarmed; use /sqprobe from console. Remove after testing.");
    }

    @Override public void onDisable() { fixture.set(null); traceTokens.clear(); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("sqprobe.admin")) return true;
        if (args.length == 2 && args[0].equalsIgnoreCase("snapshot")
                && args[1].matches("[A-Za-z0-9_-]{1,64}")) {
            var s = queue.getStatistics();
            String names = queue.getQueueList().stream().map(p -> p.getUsername())
                    .collect(java.util.stream.Collectors.joining(","));
            getLogger().info("SQSNAP " + args[1] + " online=" + s.online() + " capacity=" + s.capacity()
                    + " queued=" + s.queued() + " leases=" + s.leases() + " inFlight=" + s.inFlight()
                    + " joins=" + s.successfulJoins() + " failed=" + s.failedLogins()
                    + " cancelled=" + s.cancelledPreLogins() + " timeouts=" + s.timeouts()
                    + " paused=" + s.paused() + " timeout="
                    + ((StandaloneQueuePlugin) getServer().getPluginManager().getPlugin("StandaloneQueue"))
                        .getQueueConfig().getInFlightTimeoutSeconds() + " names=" + names);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
            fixture.set(null); sender.sendMessage("Fixture reset."); return true;
        }
        if (args.length < 2) return false;
        String action = args[0].toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("delay", "deny-pre", "deny-login").contains(action)) return false;
        long delay = 0;
        if (action.equals("delay")) {
            if (args.length != 3) return false;
            try { delay = Long.parseLong(args[2]); } catch (NumberFormatException error) { return false; }
            if (delay < 1 || delay > 45_000) { sender.sendMessage("Delay must be 1..45000 ms."); return true; }
        }
        fixture.set(new Fixture(action, args[1], delay));
        sender.sendMessage("Armed one " + action + " fixture for " + args[1] + " (" + delay + " ms). Use isolated staging only.");
        return true;
    }

    private Fixture consume(String name, boolean login) {
        Fixture current = fixture.get();
        if (current == null || !current.name().equalsIgnoreCase(name)
                || login != current.action().equals("deny-login")) return null;
        return fixture.compareAndSet(current, null) ? current : null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void injectPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED
                || queue.getAttemptId(event.getUniqueId()) == null) return;
        Fixture selected = consume(event.getName(), false);
        if (selected == null) return;
        if (selected.action().equals("deny-pre")) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("STAGING: late pre-login rejection"));
        } else {
            // This event is asynchronous. Deliberately hold only the selected authentication thread.
            trace(event.getUniqueId(), event.getName(), "delay-start");
            try { Thread.sleep(selected.delayMs()); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            trace(event.getUniqueId(), event.getName(), "delay-end");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void injectLogin(PlayerLoginEvent event) {
        if (consume(event.getPlayer().getName(), true) != null) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, Component.text("STAGING: late login rejection"));
        }
    }

    private void trace(UUID uuid, String name, String action) {
        UUID activeToken = queue.getAttemptId(uuid);
        if (activeToken != null) traceTokens.put(uuid, activeToken);
        var attempt = queue.getAttempt(uuid);
        getLogger().info("uuid=" + uuid + " name=" + name + " attempt=" + traceTokens.get(uuid)
                + " event=" + action + " phase=" + (attempt == null ? "CLOSED" : attempt.phase())
                + " inFlight=" + queue.getInFlightCount() + " online=" + queue.getOnlinePlayerCount()
                + " reserved=" + queue.getReservedSlotCount() + " thread=" + Thread.currentThread().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void preLogin(AsyncPlayerPreLoginEvent event) { trace(event.getUniqueId(), event.getName(), "pre-login:" + event.getLoginResult()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void login(PlayerLoginEvent event) { trace(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "login:" + event.getResult()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { trace(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "join"); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) { trace(event.getPlayer().getUniqueId(), event.getPlayer().getName(), "quit"); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void close(PlayerConnectionCloseEvent event) {
        trace(event.getPlayerUniqueId(), event.getPlayerName(), "close");
        traceTokens.remove(event.getPlayerUniqueId());
    }
}
