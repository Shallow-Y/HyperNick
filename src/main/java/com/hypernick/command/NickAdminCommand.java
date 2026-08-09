package com.hypernick.command;

import com.hypernick.HyperNick;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * /nickadmin 指令 — 管理员专用指令.
 * <pre>
 * /nickadmin reload   重载配置 (权限: hypernick.admin)
 * </pre>
 */
public class NickAdminCommand implements CommandExecutor, TabCompleter {

    private final HyperNick plugin;

    public NickAdminCommand(HyperNick plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hypernick.admin")) {
            plugin.msg(sender, "no-permission", Map.of());
            return true;
        }

        if (args.length < 1 || !args[0].equalsIgnoreCase("reload")) {
            plugin.msg(sender, "usage", Map.of());
            return true;
        }

        plugin.reloadAll();
        plugin.msg(sender, "config-reloaded", Map.of());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("hypernick.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            String prefix = args[0].toLowerCase();
            if ("reload".startsWith(prefix)) {
                result.add("reload");
            }
            return result;
        }
        return Collections.emptyList();
    }
}