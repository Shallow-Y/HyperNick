package com.hypernick.command;

import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.manager.NickManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * /nick 指令处理器.
 * <pre>
 * /nick              查看当前匿名状态
 * /nick &lt;名称&gt;     设置自定义昵称
 * /nick random       随机昵称 + 随机 Rank
 * /nick rank &lt;等级&gt; 切换伪装 Rank
 * /nick info         查看详细信息 (含 Fake UUID, 需透视权限)
 * /nick reset        取消匿名
 * /nick reload       重载配置 (管理员)
 * </pre>
 * <p>
 * Tab 补全仅返回子命令与 Rank 列表, <b>绝不补全玩家名</b>, 避免泄露真实 ID.
 */
public class NickCommand implements CommandExecutor, TabCompleter {

    private final HyperNick plugin;
    private final NickManager nickManager;

    public NickCommand(HyperNick plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.msg(sender, "player-only", Map.of());
            return true;
        }
        if (!player.hasPermission("HyperNick.use")) {
            plugin.msg(player, "no-permission", Map.of());
            return true;
        }

        if (args.length == 0) {
            showStatus(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "random" -> {
                if (!player.hasPermission("HyperNick.random")) {
                    plugin.msg(player, "no-permission", Map.of());
                    return true;
                }
                nickManager.nickRandom(player);
            }
            case "rank" -> {
                if (!player.hasPermission("HyperNick.rank")) {
                    plugin.msg(player, "no-permission", Map.of());
                    return true;
                }
                if (args.length < 2) {
                    plugin.msg(player, "usage", Map.of());
                    return true;
                }
                nickManager.setRank(player, args[1].toLowerCase());
            }
            case "reset", "off" -> nickManager.reset(player);
            case "info" -> showInfo(player);
            case "reload" -> {
                if (!player.hasPermission("HyperNick.admin")) {
                    plugin.msg(player, "no-permission", Map.of());
                    return true;
                }
                plugin.reloadAll();
                plugin.msg(player, "config-reloaded", Map.of());
            }
            default -> {
                // 视为自定义昵称
                if (!plugin.getConfig().getBoolean("nick-settings.allow-custom", true)) {
                    plugin.msg(player, "usage", Map.of());
                    return true;
                }
                NickData existing = nickManager.getData(player.getUniqueId());
                String rankKey = (existing != null && existing.getRankKey() != null)
                        ? existing.getRankKey()
                        : nickManager.pickRandomRank();
                nickManager.nickPlayer(player, args[0], rankKey);
            }
        }
        return true;
    }

    private void showStatus(Player player) {
        NickData data = nickManager.getData(player.getUniqueId());
        if (data == null || data.getNickName() == null) {
            plugin.msg(player, "not-nicked", Map.of());
        } else {
            String rank = data.getRankKey() != null ? data.getRankKey() : "default";
            plugin.msg(player, "already-nicked", Map.of("nick", data.getNickName(), "rank", rank));
        }
    }

    /**
     * 显示详细匿名信息 (含 Fake UUID).
     * <p>
     * 需要 HyperNick.seeidentity 权限, 因为 Fake UUID 是敏感的内部数据.
     * 显示内容: 昵称、Rank、真实 UUID、Fake UUID、设置时间.
     * <p>
     * 原始 ID 使用 LuckPerms 组别对应 Rank 前缀 + 真实名,
     * 昵称使用 Nick Rank 前缀 + 昵称, 直观对比匿名前后身份.
     */
    private void showInfo(Player player) {
        String seePerm = plugin.getConfig().getString("see-real-identity-permission", "HyperNick.seeidentity");
        if (!player.hasPermission(seePerm)) {
            plugin.msg(player, "no-permission", Map.of());
            return;
        }
        NickData data = nickManager.getData(player.getUniqueId());
        if (data == null || data.getNickName() == null) {
            plugin.msg(player, "not-nicked", Map.of());
            return;
        }
        String rank = data.getRankKey() != null ? data.getRankKey() : "default";
        String realUuid = player.getUniqueId().toString();
        String fakeUuid = data.getFakeUuid() != null ? data.getFakeUuid().toString() : "未生成";
        // 原始ID: 带 LuckPerms 组别对应 Rank 前缀的真实名 (显示真实身份的完整前缀)
        String originalPrefix = nickManager.getRankPrefix(nickManager.getGroupRankKey(player));
        String original = originalPrefix + player.getName();
        // 昵称: 带 Nick Rank 前缀的昵称 (显示匿名身份的完整前缀)
        String nickRankPrefix = nickManager.getRankPrefix(rank);
        String nick = nickRankPrefix + data.getNickName();
        plugin.msg(player, "info-detail", Map.of(
                "nick", nick,
                "rank", rank,
                "original", original,
                "realuuid", realUuid,
                "fakeuuid", fakeUuid
        ));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // /nick 命令不补全玩家名, 仅补全子命令和 rank 列表
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("random", "rank", "reset", "info"));
            if (sender.hasPermission("HyperNick.admin")) {
                options.add("reload");
            }
            List<String> result = new ArrayList<>();
            String prefix = args[0].toLowerCase();
            for (String option : options) {
                if (option.startsWith(prefix)) {
                    result.add(option);
                }
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rank")) {
            List<String> result = new ArrayList<>();
            String prefix = args[1].toLowerCase();
            for (String rank : nickManager.getAvailableRanks()) {
                if (rank.startsWith(prefix)) {
                    result.add(rank);
                }
            }
            return result;
        }
        // 不返回玩家名补全, 返回空列表阻止 Bukkit 默认补全
        return Collections.emptyList();
    }
}
