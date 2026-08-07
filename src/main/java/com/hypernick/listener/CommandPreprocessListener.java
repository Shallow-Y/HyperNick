package com.hypernick.listener;

import com.hypernick.HyperNick;
import com.hypernick.manager.NickManager;
import com.hypernick.packet.SystemMessagePacketListener;
import com.hypernick.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * 指令预处理监听器 — 昵称解析.
 * <p>
 * 拦截玩家发送的指令, 将指令参数中的<b>昵称</b>替换为对应的<b>真实玩家名</b>,
 * 使玩家可以使用 {@code /tp NickName}, {@code /kill NickName} 等指令
 * 操作已匿名的玩家.
 * <p>
 * 工作原理:
 * <ol>
 *   <li>按空格拆分指令 (如 {@code /tp SwiftFox} → ["{@code /tp}", "{@code SwiftFox}"])</li>
 *   <li>跳过指令名 (第一个参数) 和目标选择器 ({@code @p}, {@code @a} 等)</li>
 *   <li>对每个参数调用 {@link NickManager#getPlayerByDisplayName(String)}</li>
 *   <li>若匹配到已匿名玩家, 将参数替换为其真实名</li>
 *   <li>更新 {@link PlayerCommandPreprocessEvent#setMessage(String)}</li>
 * </ol>
 * <p>
 * 跳过 {@code /nick} 系列命令: 这些命令的参数是期望的昵称, 不是目标玩家.
 */
public class CommandPreprocessListener implements Listener {

    private final HyperNick plugin;
    private final NickManager nickManager;

    /** 跳过昵称解析的命令 (参数不是玩家目标) */
    private static final Set<String> SKIP_COMMANDS = new HashSet<>();

    static {
        SKIP_COMMANDS.add("/nick");
        SKIP_COMMANDS.add("/nickname");
        SKIP_COMMANDS.add("/disguise");
    }

    public CommandPreprocessListener(HyperNick plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String command = event.getMessage();
        if (command == null || command.isEmpty() || command.length() < 2) {
            return;
        }

        // 拦截 /say 命令: 统一以系统消息广播, 避免签名验证错误
        // 无论是否已匿名, 都使用同一逻辑 (昵称或真实名 + Rank 前缀)
        if (command.toLowerCase().startsWith("/say ") || command.equalsIgnoreCase("/say")) {
            event.setCancelled(true);
            String message = command.length() > 5 ? command.substring(5) : "";
            if (!message.isEmpty()) {
                broadcastSayMessage(player, message);
            }
            return;
        }

        if (!plugin.getConfig().getBoolean("resolve-nicknames-in-commands", true)) {
            return;
        }

        // 按空格拆分 (保留原始分隔)
        String[] parts = command.split(" ");
        if (parts.length <= 1) {
            return;
        }

        // 跳过 /nick 系列命令
        String cmdLower = parts[0].toLowerCase();
        if (SKIP_COMMANDS.contains(cmdLower)) {
            return;
        }

        boolean changed = false;
        for (int i = 1; i < parts.length; i++) {
            String arg = parts[i];
            if (arg == null || arg.isEmpty()) {
                continue;
            }
            // 跳过目标选择器 (@p, @a, @s, @e, @r)
            if (arg.startsWith("@")) {
                continue;
            }
            // 尝试将昵称解析为真实玩家
            Player target = nickManager.getPlayerByDisplayName(arg);
            if (target != null && !target.getName().equals(arg)) {
                parts[i] = target.getName();
                changed = true;
            }
        }

        if (changed) {
            event.setMessage(String.join(" ", parts));
        }
    }

    /**
     * 以系统消息形式广播 /say 消息, 使用昵称而非真实名.
     * <p>
     * 格式模仿原版 /say: {@code [#] 昵称: 消息}
     * 使用 SystemMessagePacketListener.bypassNext 避免前缀重复.
     *
     * @param player  发送者 (已匿名或未匿名)
     * @param message 消息内容
     */
    private void broadcastSayMessage(Player player, String message) {
        String nick = nickManager.getDisplayName(player);
        String prefix = nickManager.getEffectivePrefix(player);
        // 构建 say 消息: [#] 前缀+昵称: 消息
        String formatted = "&7[&e#&7] " + prefix + nick + "&r&7: &r" + message;
        Component component = ColorUtil.toComponent(formatted);
        // 标记所有在线玩家跳过 SystemMessagePacketListener, 防止前缀重复
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            SystemMessagePacketListener.bypassNext(viewer.getUniqueId());
        }
        Bukkit.getServer().sendMessage(component);
    }
}
