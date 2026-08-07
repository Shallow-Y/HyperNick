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
 *   <li>所有玩家 (已匿名/未匿名) 统一使用 buildPrefixedNameComponent() 直接构建带前缀的名称</li>
 *   <li>退服玩家已不在 Bukkit.getOnlinePlayers() 中, SystemMessagePacketListener 无法为其添加前缀,
 *       因此进退服消息必须在此处直接构建完整名称组件</li>
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
        // 已匿名和未匿名都使用 buildPrefixedNameComponent 直接构建带前缀的名称
        // 退服玩家已不在 Bukkit.getOnlinePlayers() 中, SystemMessagePacketListener 无法为其添加前缀
        // 因此进退服消息必须在此处直接构建完整名称组件, 不依赖数据包监听器
        Component component = nickManager.buildPrefixedNameComponent(uuid);
        if (component != null) {
            return component;
        }
        // fallback: 纯名称 (enable-group-prefix 为 false 或无法获取玩家时)
        return Component.text(nickManager.getRealName(uuid));
    }
}
