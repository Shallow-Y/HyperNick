package com.hypernick.listener;

import com.hypernick.HyperNick;
import com.hypernick.manager.NickManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

/**
 * 死亡和进度消息监听器 — 仅替换已匿名玩家的真实名为带前缀颜色的昵称.
 * <p>
 * 拦截 {@link PlayerDeathEvent} 和 {@link PlayerAdvancementDoneEvent},
 * 将消息中已匿名玩家的真实名替换为带 Rank 前缀和颜色继承的昵称组件.
 * <p>
 * 已匿名玩家: 真实名 → 前缀 + 昵称 (如 "Steve" → "§b[MVP] §bSwiftFox")
 * <p>
 * 注意: 未匿名玩家不由本监听器处理. 未匿名玩家的前缀统一由
 * {@link com.hypernick.packet.SystemMessagePacketListener} 在 SYSTEM_CHAT 数据包层处理,
 * 避免事件层与数据包层同时添加前缀导致重复.
 */
public class DeathAdvancementListener implements Listener {

    private final HyperNick plugin;
    private final NickManager nickManager;

    public DeathAdvancementListener(HyperNick plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Component deathMessage = event.deathMessage();
        if (deathMessage == null) {
            return;
        }

        Component replaced = replaceNickedPlayerNames(deathMessage);
        if (replaced != null) {
            event.deathMessage(replaced);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        Component message = event.message();
        if (message == null) {
            return;
        }

        Component replaced = replaceNickedPlayerNames(message);
        if (replaced != null) {
            event.message(replaced);
        }
    }

    /**
     * 将组件中所有已匿名玩家的真实名替换为带 Rank 前缀和颜色的昵称组件.
     * <p>
     * 仅处理已匿名玩家. 未匿名玩家的前缀由 {@link com.hypernick.packet.SystemMessagePacketListener}
     * 在数据包层统一处理, 避免事件层与数据包层重复添加前缀.
     * <p>
     * 使用 matchLiteral 确保精确匹配 (避免正则特殊字符问题).
     *
     * @param component 原始组件
     * @return 替换后的组件, 若无替换则返回 null
     */
    private Component replaceNickedPlayerNames(Component component) {
        Component result = component;
        boolean changed = false;

        // 仅替换已匿名玩家的真实名 → 前缀 + 昵称
        for (java.util.UUID uuid : nickManager.getNickedPlayers()) {
            com.hypernick.data.NickData data = nickManager.getData(uuid);
            if (data == null || data.getNickName() == null) {
                continue;
            }
            String realName = data.getOriginalName();
            String nick = data.getNickName();
            if (realName == null || realName.equals(nick)) {
                continue;
            }

            Component nameComponent = nickManager.buildPrefixedNameComponent(uuid);
            if (nameComponent == null) {
                nameComponent = Component.text(nick);
            }

            Component before = result;
            result = result.replaceText(TextReplacementConfig.builder()
                    .matchLiteral(realName)
                    .replacement(nameComponent)
                    .build());
            if (!result.equals(before)) {
                changed = true;
            }
        }

        return changed ? result : null;
    }
}
