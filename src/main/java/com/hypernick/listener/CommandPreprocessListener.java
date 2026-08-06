package com.hypernick.listener;

import com.hypernick.HyperNick;
import com.hypernick.manager.NickManager;
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
        if (!plugin.getConfig().getBoolean("resolve-nicknames-in-commands", true)) {
            return;
        }

        String command = event.getMessage();
        if (command == null || command.isEmpty() || command.length() < 2) {
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
}
