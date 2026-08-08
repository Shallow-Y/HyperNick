package com.hypernick.placeholder;

import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.manager.NickManager;
import com.hypernick.util.ColorUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * PlaceholderAPI 变量扩展.
 * <p>
 * 将 HyperNick 的关键匿名信息暴露为 PlaceholderAPI 变量, 供其他插件
 * (计分板/聊天/Tab 列表/经济等) 通过占位符读取.
 * <p>
 * 使用方式: 在其他插件中输入 {@code %hypernick_<变量>%} 即可获取对应值.
 * <p>
 * 采用内嵌类 (Internal Class) 模式: persist() 返回 true, 确保 PlaceholderAPI
 * 重载时不会注销本扩展. 扩展在 HyperNick.onEnable() 中手动注册.
 *
 * <h3>可用变量</h3>
 * <pre>
 * %hypernick_isnicked%     是否已匿名 ("true" / "false")
 * %hypernick_nickname%     当前昵称 (未匿名时返回空字符串)
 * %hypernick_displayname%  对外显示名 (昵称或真实名)
 * %hypernick_prefix%       前缀 (含 § 颜色代码, 匿名时为 Rank 前缀, 未匿名时为 LuckPerms 组前缀)
 * %hypernick_prefix_plain% 前缀 (纯文本, 无颜色代码)
 * %hypernick_rank%         Rank 键名 (如 "mvp" / "default")
 * %hypernick_color%        Rank 颜色 (名称或 #HEX)
 * %hypernick_fakeuuid%     伪装 UUID (未匿名时返回空字符串)
 * %hypernick_realuuid%     真实 UUID
 * </pre>
 */
public class HyperNickPlaceholder extends PlaceholderExpansion {

    private final HyperNick plugin;

    public HyperNickPlaceholder(HyperNick plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getAuthor() {
        return "ShallowY_";
    }

    @Override
    public @NotNull String getIdentifier() {
        return "HyperNick";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    /**
     * 返回 true, 确保 PlaceholderAPI 重载时不会注销本扩展.
     * (内嵌类模式必须覆写此方法)
     */
    @Override
    public boolean persist() {
        return true;
    }

    /**
     * 处理变量请求.
     * <p>
     * 支持离线玩家 (OfflinePlayer).
     *
     * @param player 离线玩家 (可能为 null)
     * @param params 变量参数 (不含 identifier 前缀, 如 "nickname")
     * @return 变量值, 未知变量返回 null
     */
    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return null;
        }
        NickManager nickManager = plugin.getNickManager();
        UUID uuid = player.getUniqueId();
        NickData data = nickManager.getData(uuid);
        boolean nicked = data != null && data.getNickName() != null;

        switch (params.toLowerCase()) {
            case "isnicked":
                return String.valueOf(nicked);

            case "nickname":
                return nicked ? data.getNickName() : "";

            case "displayname":
                return nicked ? data.getNickName() : player.getName();

            case "prefix": {
                if (nicked) {
                    String prefix = nickManager.getRankPrefix(data.getRankKey());
                    return ColorUtil.color(prefix);
                }
                // 未匿名: 返回 LuckPerms 组前缀
                Player onlinePlayer = Bukkit.getPlayer(uuid);
                if (onlinePlayer != null) {
                    return ColorUtil.color(nickManager.getEffectivePrefix(onlinePlayer));
                }
                return "";
            }

            case "prefix_plain": {
                if (nicked) {
                    String prefix = nickManager.getRankPrefix(data.getRankKey());
                    return ColorUtil.stripColor(ColorUtil.color(prefix));
                }
                // 未匿名: 返回 LuckPerms 组前缀 (纯文本)
                Player onlinePlayer = Bukkit.getPlayer(uuid);
                if (onlinePlayer != null) {
                    return ColorUtil.stripColor(ColorUtil.color(nickManager.getEffectivePrefix(onlinePlayer)));
                }
                return "";
            }

            case "rank": {
                if (nicked && data.getRankKey() != null) {
                    return data.getRankKey();
                }
                Player onlinePlayer = Bukkit.getPlayer(uuid);
                if (onlinePlayer != null) {
                    String groupRank = nickManager.getGroupRankKey(onlinePlayer);
                    return groupRank != null ? groupRank : "default";
                }
                return "default";
            }

            case "color": {
                String rankKey = nicked ? data.getRankKey() : null;
                return nickManager.getRankColor(rankKey);
            }

            case "fakeuuid":
                return nicked && data.getFakeUuid() != null ? data.getFakeUuid().toString() : "";

            case "realuuid":
                return uuid.toString();

            default:
                return null;
        }
    }
}