package com.hypernick.listener;

import com.hypernick.HyperNick;
import com.hypernick.manager.NickManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家连接监听器.
 * <p>
 * 上线时: ① 检测名称冲突 (若有其他玩家的昵称=新玩家真实名, 自动清除)
 *         ② 恢复已保存的匿名 (并延迟刷新数据包), 或套用基于 LuckPerms 组别的前缀
 * 下线时: 清理计分板队伍. LuckPerms 瞬态前缀会随会话自动消失, 无需手动清除.
 * <p>
 * 进退服消息:
 * <ul>
 *   <li>已匿名玩家: 使用 buildPrefixedNameComponent() (前缀 + 昵称), 因真实名不在消息中,
 *       SystemMessagePacketListener 不会二次处理</li>
 *   <li>未匿名玩家: 使用纯名称组件 (无前缀), 由 SystemMessagePacketListener 在数据包层
 *       统一添加前缀, 避免事件层与数据包层重复添加前缀</li>
 * </ul>
 */
public class PlayerConnectionListener implements Listener {

    private final HyperNick plugin;
    private final NickManager nickManager;

    public PlayerConnectionListener(HyperNick plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        // 1. 检测名称冲突: 若有其他玩家的昵称=新玩家真实名, 自动清除 (防止计分板 entry 冲突)
        nickManager.handleNameConflict(event.getPlayer());
        // 2. 恢复此玩家已保存的匿名 (如有), 或套用基于 LuckPerms 组别的前缀
        nickManager.applyOnJoin(event.getPlayer());

        // 替换进服消息
        Component nameComponent = buildJoinQuitNameComponent(event.getPlayer().getUniqueId());
        event.joinMessage(Component.translatable("multiplayer.player.joined",
                nameComponent).color(NamedTextColor.YELLOW));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        nickManager.cleanupOnQuit(event.getPlayer());

        // 替换退服消息
        Component nameComponent = buildJoinQuitNameComponent(event.getPlayer().getUniqueId());
        event.quitMessage(Component.translatable("multiplayer.player.left",
                nameComponent).color(NamedTextColor.YELLOW));
    }

    /**
     * 构建进退服消息中使用的玩家名称组件.
     * <p>
     * 已匿名玩家: 使用 buildPrefixedNameComponent() (前缀 + 昵称).
     * 真实名不在消息中, SystemMessagePacketListener 不会二次处理, 无前缀重复.
     * <p>
     * 未匿名玩家: 使用纯名称 (无前缀).
     * SystemMessagePacketListener 会在 SYSTEM_CHAT 数据包层统一添加 Rank 前缀,
     * 避免事件层与数据包层同时添加前缀导致重复.
     *
     * @param uuid 玩家真实 UUID
     * @return 名称组件
     */
    private Component buildJoinQuitNameComponent(java.util.UUID uuid) {
        if (nickManager.isNicked(uuid)) {
            // 已匿名: 前缀 + 昵称 (真实名不在消息中, 不会被 SystemMessagePacketListener 二次处理)
            Component component = nickManager.buildPrefixedNameComponent(uuid);
            if (component != null) {
                return component;
            }
            // fallback: 使用显示名
            return Component.text(nickManager.getDisplayName(
                    org.bukkit.Bukkit.getPlayer(uuid)));
        }
        // 未匿名: 纯名称 (无前缀), 由 SystemMessagePacketListener 统一添加前缀
        return Component.text(nickManager.getRealName(uuid));
    }
}
