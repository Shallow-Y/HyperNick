package com.hypernick.command;

import com.hypernick.HyperNick;
import com.hypernick.manager.NickManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * /unnick 指令 — 与 /nick reset 功能相同.
 */
public class UnnickCommand implements CommandExecutor {

    private final HyperNick plugin;
    private final NickManager nickManager;

    public UnnickCommand(HyperNick plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.msg(sender, "player-only", Map.of());
            return true;
        }
        if (!player.hasPermission("hypernick.use")) {
            plugin.msg(player, "no-permission", Map.of());
            return true;
        }
        nickManager.reset(player);
        return true;
    }
}
