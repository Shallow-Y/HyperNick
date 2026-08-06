package com.hypernick.scoreboard;

import com.hypernick.HyperNick;
import com.hypernick.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 计分板名牌前缀管理.
 * <p>
 * 使用主计分板 (shared) 为每个匿名玩家注册独立队伍, 设置 Rank 前缀与颜色.
 * <p>
 * 关键点: 数据包监听器把客户端看到的 GameProfile.name 改成了昵称,
 * 因此这里把"昵称字符串"作为队伍条目 (entry) 加入, 客户端才能匹配并应用前缀.
 * <p>
 * 颜色支持: NamedTextColor (GRAY/GREEN/AQUA/...) 和 HEX (#RRGGBB).
 * Bukkit Team.color() 仅接受 NamedTextColor, 对于自定义 HEX 颜色,
 * 会查找最接近的 NamedTextColor 作为队伍颜色 (前缀中的 HEX 颜色不受影响).
 * <p>
 * 局限: 若其它插件为玩家分配了自定义计分板 (per-player scoreboard),
 * 主计分板的队伍不会对该玩家生效, 此时头顶名牌将仅显示昵称文本 (无前缀颜色).
 * 聊天前缀与 Tab 显示名不受影响.
 */
public class ScoreboardManager {

    private final HyperNick plugin;
    private final ConcurrentMap<UUID, String> teamNames = new ConcurrentHashMap<>();

    public ScoreboardManager(HyperNick plugin) {
        this.plugin = plugin;
    }

    private Scoreboard getMainBoard() {
        if (Bukkit.getScoreboardManager() == null) {
            return null;
        }
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    /**
     * 为玩家套用名牌队伍.
     * <p>
     * <b>性能优化</b>: 如果玩家已有队伍且配置 (前缀/颜色/条目) 未变更, 直接跳过.
     * 避免重复调用 setOption(COLLISION_RULE) 触发 updateTeamWaypoints,
     * 该方法会遍历所有在线玩家, 在多人服务器上非常耗时.
     *
     * @param player     目标玩家
     * @param entryName  队伍条目 (应为昵称, 与数据包修改后的 GameProfile.name 一致)
     * @param prefixText 前缀文本 (含 & 颜色代码, 支持 HEX)
     * @param colorName  颜色名称 (GRAY/GREEN/AQUA/...) 或 HEX (#RRGGBB)
     */
    public void applyTeam(Player player, String entryName, String prefixText, String colorName) {
        Scoreboard board = getMainBoard();
        if (board == null) {
            return;
        }

        // 优化: 检查现有队伍配置是否已匹配, 匹配则跳过
        // 避免 removeTeam + registerNewTeam + 5次 setOption/onTeamChanged → updateTeamWaypoints
        String existingTeamName = teamNames.get(player.getUniqueId());
        if (existingTeamName != null) {
            Team existing = board.getTeam(existingTeamName);
            if (existing != null && isTeamConfigUnchanged(existing, entryName, prefixText, colorName)) {
                return; // 配置未变更, 跳过重建
            }
        }

        // 先移除旧队伍 (含内存映射 + 计分板残留)
        removeTeam(player);

        String teamName = "hn_" + Integer.toHexString(player.getUniqueId().hashCode());
        // 哈希碰撞或残留队伍: 若同名队伍已存在, 加 UUID 前缀后缀避免
        Team existing = board.getTeam(teamName);
        if (existing != null) {
            teamName = teamName + "_" + player.getUniqueId().toString().substring(0, 4);
        }

        // 安全网: 如果最终队伍名仍已存在 (插件重载/内存映射丢失导致残留), 先注销
        Team leftover = board.getTeam(teamName);
        if (leftover != null) {
            plugin.getLogger().warning("发现残留队伍 (" + teamName + "), 注销后重新注册.");
            leftover.unregister();
        }

        Team team = board.registerNewTeam(teamName);
        teamNames.put(player.getUniqueId(), teamName);

        // 前缀文本 (支持 HEX 颜色代码)
        // 在前缀末尾追加最后一个颜色代码, 使队伍条目 (玩家名) 能继承前缀的 HEX 颜色
        // Bukkit Team.color() 仅接受 NamedTextColor, 无法精确表示 HEX 颜色
        // 追加颜色代码到前缀末尾, 在部分客户端实现中颜色会延续到条目名
        Component prefixComponent = ColorUtil.toComponent(prefixText);
        TextColor lastColor = ColorUtil.getLastColor(ColorUtil.color(prefixText));
        if (lastColor != null) {
            prefixComponent = prefixComponent.append(Component.empty().color(lastColor));
        }
        team.prefix(prefixComponent);

        // 队伍颜色: 优先从前缀提取最后一个颜色 (支持 HEX → 最接近的 NamedTextColor)
        // 若前缀无颜色, 回退到 colorName 配置字段
        NamedTextColor teamColor = resolveTeamColor(prefixText, colorName);
        if (teamColor != null) {
            team.color(teamColor);
        }

        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);

        try {
            team.addEntry(entryName);
        } catch (IllegalArgumentException ex) {
            // 条目可能已在其它队伍中, 尝试先从原队伍移除
            plugin.getLogger().warning("队伍条目添加失败 (" + entryName + "): " + ex.getMessage()
                    + " - 尝试从原队伍移除后重试.");
            for (Team other : board.getTeams()) {
                if (other.hasEntry(entryName)) {
                    other.removeEntry(entryName);
                }
            }
            try {
                team.addEntry(entryName);
            } catch (IllegalArgumentException ex2) {
                plugin.getLogger().warning("队伍条目重试仍失败: " + ex2.getMessage());
            }
        }
    }

    /**
     * 检查现有队伍配置是否与新配置一致.
     * <p>
     * 用于 {@link #applyTeam} 的性能优化: 如果队伍的前缀、颜色、条目、选项都未变更,
     * 则跳过重建队伍, 避免触发昂贵的 updateTeamWaypoints.
     *
     * @param team       现有队伍
     * @param entryName  期望的条目名
     * @param prefixText 期望的前缀文本 (含 & 颜色代码)
     * @param colorName  期望的颜色名称
     * @return true 如果所有配置都一致
     */
    private boolean isTeamConfigUnchanged(Team team, String entryName, String prefixText, String colorName) {
        // 检查 prefix (与 applyTeam 中的构建方式一致: 前缀 + 末尾颜色继承组件)
        Component expectedPrefix = ColorUtil.toComponent(prefixText);
        TextColor lastColor = ColorUtil.getLastColor(ColorUtil.color(prefixText));
        if (lastColor != null) {
            expectedPrefix = expectedPrefix.append(Component.empty().color(lastColor));
        }
        if (!Objects.equals(team.prefix(), expectedPrefix)) {
            return false;
        }
        // 检查 color
        NamedTextColor expectedColor = resolveTeamColor(prefixText, colorName);
        if (expectedColor != null) {
            if (!Objects.equals(team.color(), expectedColor)) {
                return false;
            }
        }
        // 检查 entry
        if (!team.hasEntry(entryName)) {
            return false;
        }
        // 检查选项
        if (team.getOption(Team.Option.NAME_TAG_VISIBILITY) != Team.OptionStatus.ALWAYS) {
            return false;
        }
        if (team.getOption(Team.Option.COLLISION_RULE) != Team.OptionStatus.NEVER) {
            return false;
        }
        return true;
    }

    /**
     * 解析颜色字符串为 NamedTextColor (用于 Team.color()).
     * <p>
     * 支持两种格式:
     * <ul>
     *   <li>命名颜色: "AQUA", "RED", "GREEN" ... → 直接返回对应的 NamedTextColor</li>
     *   <li>HEX 颜色: "#FF55FF" → 查找最接近的 NamedTextColor</li>
     * </ul>
     */
    private NamedTextColor parseTeamColor(String colorName) {
        if (colorName == null || colorName.isBlank()) {
            return null;
        }
        colorName = colorName.trim();

        // HEX 格式: #RRGGBB → 查找最接近的 NamedTextColor
        if (colorName.startsWith("#")) {
            TextColor hex = ColorUtil.parseTextColor(colorName);
            if (hex != null) {
                return NamedTextColor.nearestTo(hex);
            }
            return null;
        }

        // 命名颜色
        return NamedTextColor.NAMES.value(colorName.toLowerCase());
    }

    /**
     * 从前缀和配置字段解析队伍颜色 (统一入口).
     * <p>
     * 优先从前缀的最后一个颜色代码提取颜色 (确保与前缀一致):
     * <ul>
     *   <li>NamedTextColor → 直接返回</li>
     *   <li>自定义 HEX TextColor → {@link NamedTextColor#nearestTo} 查找最接近的命名颜色</li>
     * </ul>
     * 若前缀无颜色代码, 回退到 {@link #parseTeamColor} 解析 colorName 配置字段.
     * <p>
     * 这样即使 config.yml 的 color 字段与前缀 HEX 不一致,
     * 队伍颜色也会与前缀颜色保持同步.
     *
     * @param prefixText 前缀文本 (含 & 颜色代码, 支持 HEX)
     * @param colorName  配置的 color 字段 (命名颜色或 HEX)
     * @return 最接近的 NamedTextColor, 无颜色时返回 null
     */
    private NamedTextColor resolveTeamColor(String prefixText, String colorName) {
        // 1. 优先从前缀提取最后一个颜色
        TextColor prefixColor = ColorUtil.getLastColor(ColorUtil.color(prefixText));
        if (prefixColor != null) {
            if (prefixColor instanceof NamedTextColor named) {
                return named;
            }
            // 自定义 HEX 颜色 → 查找最接近的 NamedTextColor
            return NamedTextColor.nearestTo(prefixColor);
        }
        // 2. 前缀无颜色 → 回退到 colorName 配置
        return parseTeamColor(colorName);
    }

    /**
     * 移除玩家的名牌队伍.
     * <p>
     * 先从内存映射中查找并注销; 若映射中没有 (插件重载/内存丢失),
     * 再扫描计分板中所有以 "hn_" + 玩家哈希 开头的队伍并注销, 防止残留.
     */
    public void removeTeam(Player player) {
        Scoreboard board = getMainBoard();
        if (board == null) {
            return;
        }
        // 1. 从内存映射中查找并注销
        String teamName = teamNames.remove(player.getUniqueId());
        if (teamName != null) {
            Team team = board.getTeam(teamName);
            if (team != null) {
                team.unregister();
            }
        }
        // 2. 安全网: 扫描计分板中可能残留的队伍 (插件重载后映射丢失但队伍残留)
        String hashPrefix = "hn_" + Integer.toHexString(player.getUniqueId().hashCode());
        String uuidSuffix = player.getUniqueId().toString().substring(0, 4);
        for (Team team : board.getTeams()) {
            String name = team.getName();
            if (name != null && (name.equals(hashPrefix) || name.equals(hashPrefix + "_" + uuidSuffix))) {
                team.unregister();
            }
        }
    }

    /**
     * 清理计分板上所有 HyperNick 残留队伍.
     * <p>
     * 在插件启动时调用, 清除上次运行遗留的 "hn_*" 队伍.
     * 避免插件重载后内存映射为空但计分板队伍残留导致注册冲突.
     */
    public void cleanupOrphanedTeams() {
        Scoreboard board = getMainBoard();
        if (board == null) {
            return;
        }
        int count = 0;
        for (Team team : board.getTeams()) {
            String name = team.getName();
            if (name != null && name.startsWith("hn_")) {
                team.unregister();
                count++;
            }
        }
        if (count > 0) {
            plugin.getLogger().info("已清理 " + count + " 个残留 HyperNick 队伍.");
        }
    }

    /**
     * 从所有 HyperNick 队伍中移除指定 entry.
     * <p>
     * 用于处理名称冲突: 当真实玩家加入时, 其玩家名可能已被某个 HyperNick 队伍
     * 作为 entry 使用 (另一个玩家 nick 了这个名字), 导致该真实玩家被错误应用
     * Rank 前缀. 此方法将 entry 从所有 hn_ 队伍中移除, 让玩家回到默认状态.
     *
     * @param entryName 要移除的 entry 名称 (通常为新加入玩家的真实名)
     */
    public void purgeEntry(String entryName) {
        Scoreboard board = getMainBoard();
        if (board == null) {
            return;
        }
        for (Team team : board.getTeams()) {
            String name = team.getName();
            if (name != null && name.startsWith("hn_") && team.hasEntry(entryName)) {
                team.removeEntry(entryName);
            }
        }
    }
}
