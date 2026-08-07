package com.hypernick.command;

import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.gui.NickGuiManager;
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
 * /nick              打开昵称设置 GUI
 * /nick &lt;名称&gt;     设置自定义昵称
 * /nick random       随机昵称 + 随机 Rank
 * /nick rank &lt;等级&gt; 切换伪装 Rank
 * /nick skin &lt;模式&gt;  设置皮肤模式 (REAL/RANDOM/RESET)
 * /nick reuse        重新使用上次昵称
 * /nick info         查看详细信息 (含 Fake UUID)
 * /nick reset        取消匿名
 * /nick reload       重载配置 (管理员)
 * </pre>
 * <p>
 * Tab 补全仅返回子命令与 Rank 列表, <b>绝不补全玩家名</b>, 避免泄露真实 ID.
 */
public class NickCommand implements CommandExecutor, TabCompleter {

    private final HyperNick plugin;
    private final NickManager nickManager;
    private final NickGuiManager guiManager;

    public NickCommand(HyperNick plugin, NickManager nickManager, NickGuiManager guiManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /nick reload 可由控制台执行
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("HyperNick.admin")) {
                plugin.msg(sender, "no-permission", Map.of());
                return true;
            }
            plugin.reloadAll();
            plugin.msg(sender, "config-reloaded", Map.of());
            return true;
        }

        // 其余子命令仅限玩家
        if (!(sender instanceof Player player)) {
            plugin.msg(sender, "player-only", Map.of());
            return true;
        }
        if (!player.hasPermission("HyperNick.use")) {
            plugin.msg(player, "no-permission", Map.of());
            return true;
        }

        if (args.length == 0) {
            guiManager.openMainMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "random" -> nickManager.nickRandom(player);
            case "rank" -> {
                if (args.length < 2) {
                    plugin.msg(player, "usage", Map.of());
                    return true;
                }
                nickManager.setRank(player, args[1].toLowerCase());
            }
            case "reuse" -> {
                if (!player.hasPermission("HyperNick.use")) {
                    plugin.msg(player, "no-permission", Map.of());
                    return true;
                }
                nickManager.nickReuse(player, null, null);
            }
            case "skin" -> {
                if (!player.hasPermission("HyperNick.use")) {
                    plugin.msg(player, "no-permission", Map.of());
                    return true;
                }
                if (args.length < 2) {
                    plugin.msg(player, "skin-usage", Map.of());
                    return true;
                }
                try {
                    NickData.SkinMode mode = NickData.SkinMode.valueOf(args[1].toUpperCase());
                    nickManager.setSkinMode(player, mode);
                } catch (IllegalArgumentException e) {
                    plugin.msg(player, "skin-usage", Map.of());
                }
            }
            case "gui" -> handleGuiCommand(player, args);
            case "reset", "off" -> nickManager.reset(player);
            case "info" -> showInfo(player);
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
     * 处理 /nick gui 隐藏子命令 (供 Book GUI 点击调用).
     */
    private void handleGuiCommand(Player player, String[] args) {
        if (args.length < 2) {
            guiManager.openMainMenu(player);
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "rank" -> guiManager.openRankMenu(player);
            case "selectrank" -> {
                if (args.length < 3) return;
                guiManager.selectRank(player, args[2].toLowerCase());
            }
            case "selectskin" -> {
                if (args.length < 3) return;
                try {
                    NickData.SkinMode mode = NickData.SkinMode.valueOf(args[2].toUpperCase());
                    guiManager.selectSkin(player, mode);
                } catch (IllegalArgumentException ignored) {
                }
            }
            case "name" -> {
                if (args.length < 3) return;
                String nameAction = args[2].toLowerCase();
                switch (nameAction) {
                    case "random" -> guiManager.applyRandomName(player);
                    case "reuse" -> guiManager.applyReuseName(player);
                    case "custom" -> guiManager.openSignInput(player);
                }
            }
            case "confirm" -> {
                // /nick gui confirm [nick] — 确认使用随机生成的昵称
                // 若带 nick 参数则使用该昵称, 否则使用 pendingRandoms 中保存的
                if (args.length >= 3) {
                    // 带参数: 直接确认 (从 random-result.yml 的 click 传入)
                    guiManager.confirmRandomNameWithNick(player, args[2]);
                } else {
                    guiManager.confirmRandomName(player);
                }
            }
            case "back" -> {
                // /nick gui back <page> — 返回指定页面
                if (args.length < 3) {
                    guiManager.openMainMenu(player);
                    return;
                }
                String page = args[2].toLowerCase();
                switch (page) {
                    case "name" -> guiManager.openNameMenu(player);
                    case "rank" -> guiManager.openRankMenu(player);
                    case "skin" -> guiManager.openSkinMenu(player);
                    default -> guiManager.openMainMenu(player);
                }
            }
            default -> guiManager.openMainMenu(player);
        }
    }

    /**
     * 显示详细匿名信息 (含 Fake UUID).
     */
    private void showInfo(Player player) {
        NickData data = nickManager.getData(player.getUniqueId());
        if (data == null || data.getNickName() == null) {
            plugin.msg(player, "not-nicked", Map.of());
            return;
        }
        String rank = data.getRankKey() != null ? data.getRankKey() : "default";
        String realUuid = player.getUniqueId().toString();
        String fakeUuid = data.getFakeUuid() != null ? data.getFakeUuid().toString() : "未生成";
        String nickRankPrefix = nickManager.getRankPrefix(rank);
        String nick = nickRankPrefix + data.getNickName();
        plugin.msg(player, "info-detail", Map.of(
                "nick", nick,
                "rank", rank,
                "realuuid", realUuid,
                "fakeuuid", fakeUuid
        ));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // /nick 命令不补全玩家名, 仅补全子命令和 rank 列表
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("random", "rank", "reset", "info", "reuse", "skin"));
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
        if (args.length == 2 && args[0].equalsIgnoreCase("skin")) {
            List<String> result = new ArrayList<>();
            String prefix = args[1].toLowerCase();
            for (String mode : new String[]{"real", "random", "reset"}) {
                if (mode.startsWith(prefix)) {
                    result.add(mode);
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