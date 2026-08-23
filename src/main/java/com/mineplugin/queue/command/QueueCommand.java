package com.mineplugin.queue.command;

import com.mineplugin.queue.StandaloneQueuePlugin;
import com.mineplugin.queue.config.QueueConfig;
import com.mineplugin.queue.manager.QueueManager;
import com.mineplugin.queue.model.QueuedPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Xử lý lệnh quản trị hàng chờ (/queue hoặc /sq, /hangcho).
 *
 * @author BlastSama
 */
public class QueueCommand implements CommandExecutor, TabCompleter {

    private final StandaloneQueuePlugin plugin;
    private final QueueManager queueManager;
    private final QueueConfig config;

    public QueueCommand(StandaloneQueuePlugin plugin, QueueManager queueManager, QueueConfig config) {
        this.plugin = plugin;
        this.queueManager = queueManager;
        this.config = config;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("queue.admin")) {
            sender.sendMessage(config.getAdminMessage("no-permission"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                plugin.reloadConfig();
                config.load(plugin.getConfig());
                sender.sendMessage(config.getAdminMessage("reloaded"));
            }
            case "status" -> {
                int online = Bukkit.getServer() != null ? Bukkit.getOnlinePlayers().size() : 0;
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
                            "<player>", qp.getUsername(),
                            "<prio>", String.valueOf(qp.getPriority()),
                            "<last_ping>", pingSecAgo + "s",
                            "<status>", status));
                }
            }
            case "clear" -> {
                int count = queueManager.clearQueue();
                sender.sendMessage(config.getAdminMessage("cleared", "<count>", String.valueOf(count)));
            }
            case "kick" -> {
                if (args.length < 2) {
                    sender.sendMessage(config.parseText("<red>Cách dùng: /" + label + " kick <tên người chơi></red>"));
                    return true;
                }
                String targetName = args[1];
                boolean removed = queueManager.kickPlayerFromQueue(targetName);
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

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(config.parseText("<gradient:#00c6ff:#0072ff><b>=== HỆ THỐNG HÀNG CHỜ ===</b></gradient>"));
        sender.sendMessage(config.parseText("<gold>/" + label + " status</gold> <gray>- Xem trạng thái máy chủ & hàng chờ</gray>"));
        sender.sendMessage(config.parseText("<gold>/" + label + " list</gold> <gray>- Xem danh sách người chơi trong hàng chờ</gray>"));
        sender.sendMessage(config.parseText("<gold>/" + label + " clear</gold> <gray>- Xóa toàn bộ người chơi khỏi hàng chờ</gray>"));
        sender.sendMessage(config.parseText("<gold>/" + label + " kick <tên></gold> <gray>- Xóa một người chơi khỏi hàng chờ</gray>"));
        sender.sendMessage(config.parseText("<gold>/" + label + " reload</gold> <gray>- Tải lại file config.yml</gray>"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("queue.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> subs = Arrays.asList("status", "list", "clear", "kick", "reload", "help");
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("kick")) {
            List<QueuedPlayer> queueList = queueManager.getQueueList();
            List<String> names = new ArrayList<>();
            for (QueuedPlayer qp : queueList) {
                if (qp.getUsername().toLowerCase().startsWith(args[1].toLowerCase())) {
                    names.add(qp.getUsername());
                }
            }
            return names;
        }

        return List.of();
    }
}
