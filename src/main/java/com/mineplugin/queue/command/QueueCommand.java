package com.mineplugin.queue.command;

import com.mineplugin.queue.StandaloneQueuePlugin;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import com.mineplugin.queue.model.QueuedPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.Consumer;

/**
 * Xử lý lệnh quản trị hàng chờ (/queue hoặc /sq, /hangcho).
 *
 * @author BlastSama
 */
public class QueueCommand implements CommandExecutor, TabCompleter {

    private final StandaloneQueuePlugin plugin;
    private final QueueManager queueManager;
    private final QueueConfig config;
    private final Supplier<CompletableFuture<Void>> reloadAction;
    private final Consumer<Runnable> dispatch;

    public QueueCommand(StandaloneQueuePlugin plugin, QueueManager queueManager, QueueConfig config) {
        this(plugin, queueManager, config,
                () -> plugin.reloadQueueConfiguration(),
                action -> { if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, action); });
    }

    public QueueCommand(StandaloneQueuePlugin plugin, QueueManager queueManager, QueueConfig config,
                        Supplier<CompletableFuture<Void>> reloadAction, Consumer<Runnable> dispatch) {
        this.plugin = plugin;
        this.queueManager = queueManager;
        this.config = config;
        this.reloadAction = reloadAction;
        this.dispatch = dispatch;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String requested = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!authorized(sender, requested)) {
            sender.sendMessage(config.getAdminMessage("no-permission"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                sender.sendMessage(config.getAdminMessage("reloading"));
                reloadAction.get().whenComplete((ignored, error) -> dispatch.accept(() -> {
                    if (error == null) {
                        queueManager.audit(sender.getName(), "reload", "success=true");
                        sender.sendMessage(config.getAdminMessage("reloaded"));
                    }
                    else {
                        Throwable cause = error;
                        while (cause.getCause() != null) cause = cause.getCause();
                        String reason = String.valueOf(cause.getMessage());
                        if (plugin != null) plugin.getLogger().warning("Queue reload rejected: " + reason);
                        sender.sendMessage(config.getAdminMessage("reload-failed", "<reason>",
                                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(reason)));
                    }
                }));
            }
            case "antibot", "anti-bot" -> handleAntiBot(sender, label, args);
            case "stats" -> sendStatistics(sender);
            case "inspect" -> {
                if (args.length != 2) { sender.sendMessage(Component.text("Cách dùng: /" + label + " inspect <player|uuid>")); return true; }
                var info = queueManager.inspect(args[1]);
                sender.sendMessage(Component.text(info == null ? "Không tìm thấy người chơi đang chờ/đăng nhập."
                        : "UUID=" + info.uuid() + " player=" + info.name() + " tier=" + info.priority()
                        + " position=" + info.position() + " TTL=" + info.ttlSeconds() + "s lease=" + info.leaseId()
                        + " leaseTTL=" + info.leaseRemainingSeconds() + "s phase=" + info.phase()));
            }
            case "pause", "resume" -> {
                boolean pause = sub.equals("pause"); queueManager.setPaused(pause);
                queueManager.audit(sender.getName(), sub, "paused=" + pause);
                sender.sendMessage(Component.text(pause ? "Đã tạm dừng cấp slot mới; lease đã cấp và in-flight được giữ."
                        : "Đã tiếp tục phân phối slot."));
            }
            case "move" -> {
                boolean moved = false;
                if (args.length == 3) {
                    try { moved = queueManager.move(args[1], Integer.parseInt(args[2])); }
                    catch (NumberFormatException ignored) { }
                }
                queueManager.audit(sender.getName(), "move", "target=" + (args.length > 1 ? args[1] : "")
                        + " position=" + (args.length > 2 ? args[2] : "") + " success=" + moved);
                sender.sendMessage(Component.text(moved ? "Đã đổi vị trí trong cùng tier."
                        : "Cách dùng: /" + label + " move <player> <position>; không được vượt tier hoặc lease."));
            }
            case "status" -> {
                int online = queueManager.getOnlinePlayerCount();
                int maxCap = queueManager.getEffectiveMaxPlayers();
                int override = config.getMaxPlayersOverride();
                int queueSize = queueManager.getQueueSize();
                int reserved = queueManager.getReservedSlotCount();

                sender.sendMessage(config.getRawAdminMessage("status-header"));
                sender.sendMessage(config.getRawAdminMessage("status-line-online",
                        "<online>", String.valueOf(online),
                        "<max>", String.valueOf(maxCap),
                        "<override>", override > 0 ? String.valueOf(override) : "Theo server.properties"));
                sender.sendMessage(config.getRawAdminMessage("status-line-queue",
                        "<queue_size>", String.valueOf(queueSize),
                        "<reserved_count>", String.valueOf(reserved)));
                sender.sendMessage(config.getRawAdminMessage("status-line-in-flight",
                        "<in_flight>", String.valueOf(queueManager.getInFlightCount()),
                        "<timed_out>", String.valueOf(queueManager.getTimedOutInFlightCount()),
                        "<timeout_count>", String.valueOf(queueManager.getInFlightTimeoutCount())));
                sendStatistics(sender);
            }
            case "list" -> {
                List<QueuedPlayer> queueList = queueManager.getQueueList();
                sender.sendMessage(config.getRawAdminMessage("list-header", "<count>", String.valueOf(queueList.size())));

                if (queueList.isEmpty()) {
                    sender.sendMessage(config.parseText("<gray><i>(Hiện tại hàng chờ đang trống)</i></gray>"));
                    return true;
                }

                long now = System.currentTimeMillis();
                for (int i = 0; i < queueList.size(); i++) {
                    QueuedPlayer qp = queueList.get(i);
                    long pingSecAgo = (now - qp.getLastPingTimestamp()) / 1000L;
                    String status = qp.hasActiveReservation()
                            ? "<green>Đã có slot giữ chỗ</green>"
                            : "<yellow>Đang chờ</yellow>";

                    sender.sendMessage(config.getRawAdminMessage("list-entry",
                            "<pos>", String.valueOf(i + 1),
                            "<player>", net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(qp.getUsername()),
                            "<prio>", String.valueOf(qp.getPriority()),
                            "<last_ping>", pingSecAgo + "s",
                            "<status>", status));
                }
            }
            case "clear" -> {
                int count = queueManager.clearQueue();
                queueManager.audit(sender.getName(), "clear", "removed=" + count);
                sender.sendMessage(config.getAdminMessage("cleared", "<count>", String.valueOf(count)));
            }
            case "kick" -> {
                if (args.length < 2) {
                    sender.sendMessage(config.parseText("<red>Cách dùng: /" + label + " kick <tên người chơi></red>"));
                    return true;
                }
                String targetName = args[1];
                boolean removed = queueManager.kickPlayerFromQueue(targetName);
                queueManager.audit(sender.getName(), "kick", "target=" + targetName + " success=" + removed);
                targetName = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(targetName);
                if (removed) {
                    sender.sendMessage(config.getAdminMessage("kicked-success", "<player>", targetName));
                } else {
                    sender.sendMessage(config.getAdminMessage("player-not-found", "<player>", targetName));
                }
            }
            default -> sendHelp(sender, label);
        }
        return true;
    }

    private static String permission(String sub) {
        return switch (sub) {
            case "inspect", "list" -> "queue.inspect";
            case "status", "stats" -> "queue.stats";
            case "pause", "resume", "move", "clear", "kick" -> "queue.manage";
            default -> "queue.admin";
        };
    }

    private static boolean authorized(CommandSender sender, String sub) {
        if (sub.equals("help")) return sender.hasPermission("queue.admin") || sender.hasPermission("queue.inspect")
                || sender.hasPermission("queue.stats") || sender.hasPermission("queue.manage");
        return sender.hasPermission("queue.admin") || sender.hasPermission(permission(sub));
    }

    private void sendStatistics(CommandSender sender) {
        sender.sendMessage(Component.text("Persistence: " + (plugin == null ? "disabled" : plugin.getPersistenceStatus())));
        var stats = queueManager.getStatistics();
        sender.sendMessage(Component.text("paused=" + stats.paused() + " online=" + stats.online() + "/" + stats.capacity()
                + " queue=" + stats.queued() + " leases=" + stats.leases() + " inFlight=" + stats.inFlight()
                + " JOIN=" + stats.successfulJoins() + " timeout=" + stats.timeouts()
                + " loginFailed=" + stats.failedLogins() + " cancelledPreLogin=" + stats.cancelledPreLogins()));
        sender.sendMessage(Component.text("Queue JOIN 5m=" + stats.joins5m() + " 15m=" + stats.joins15m()
                + " preLogin avg/max ms=" + String.format(Locale.ROOT, "%.2f/%.2f", stats.averagePreLoginMs(), stats.maxPreLoginMs())
                + " tracked UUID/IP=" + stats.trackedUuids() + "/" + stats.trackedIps()));
        sender.sendMessage(Component.text("Lease expired=" + stats.reservationExpired() + " heartbeat expired=" + stats.heartbeatExpired()
                + " permission failures=" + stats.permissionFailures() + " queued wait samples=" + stats.waitBuckets().getLast()));
        stats.decisions().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(e ->
                sender.sendMessage(Component.text(e.getKey() + "=" + e.getValue())));
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(Component.text("=== HỆ THỐNG HÀNG CHỜ ==="));
        for (String sub : List.of("status", "stats", "inspect <player|uuid>", "list", "pause", "resume",
                "move <player> <position>", "clear", "kick <player>", "reload", "antibot <on|off|status>")) {
            if (authorized(sender, sub.split(" ")[0])) sender.sendMessage(Component.text("/" + label + " " + sub));
        }
    }

    private void handleAntiBot(CommandSender sender, String label, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
            String source = config.isAntiBotEnabled() ? "<green>BẬT</green>" : "<red>TẮT</red>";
            sender.sendMessage(config.parseText("<gray>Anti-joinbot: " + source + "</gray>"));
            return;
        }
        if (args[1].equalsIgnoreCase("on")) {
            config.setAntiBotRuntimeOverride(true);
            queueManager.audit(sender.getName(), "antibot", "enabled=true");
            sender.sendMessage(config.parseText("<green>Đã bật bảo vệ anti-joinbot.</green>"));
        } else if (args[1].equalsIgnoreCase("off")) {
            config.setAntiBotRuntimeOverride(false);
            queueManager.audit(sender.getName(), "antibot", "enabled=false");
            sender.sendMessage(config.parseText("<yellow>Đã tắt bảo vệ anti-joinbot (chỉ runtime).</yellow>"));
        } else {
            sender.sendMessage(config.parseText("<red>Cách dùng: /" + label + " antibot <on|off|status></red>"));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!authorized(sender, args.length > 1 ? args[0].toLowerCase(Locale.ROOT) : "help")) return List.of();

        if (args.length == 1) {
            List<String> subs = Arrays.asList("status", "stats", "inspect", "pause", "resume", "move", "list", "clear", "kick", "reload", "antibot", "anti-bot", "help");
            return subs.stream().filter(s -> authorized(sender, s)).filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("kick") || args[0].equalsIgnoreCase("inspect") || args[0].equalsIgnoreCase("move"))) {
            List<QueuedPlayer> queueList = queueManager.getQueueList();
            List<String> names = new ArrayList<>();
            for (QueuedPlayer qp : queueList) {
                if (qp.getUsername().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    names.add(qp.getUsername());
                }
            }
            return names;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("antibot") || args[0].equalsIgnoreCase("anti-bot"))) {
            return Arrays.asList("on", "off", "status").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        return List.of();
    }
}
