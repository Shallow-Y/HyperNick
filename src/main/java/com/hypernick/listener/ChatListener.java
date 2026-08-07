package com.hypernick.listener;

import com.hypernick.HyperNick;
import com.hypernick.manager.NickManager;
import com.hypernick.packet.SystemMessagePacketListener;
import com.hypernick.util.ColorUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 聊天渲染监听器.
 * <p>
 * 取消原版 {@link AsyncChatEvent} 的签名聊天机制, 改为手动以系统消息
 * (ClientboundSystemChatPacket, 无签名) 发送给所有观看者.
 * <p>
 * 这样做的根本原因: 数据包监听器把客户端看到的 UUID 从 realUuid 改成了 fakeUuid,
 * 而原版签名聊天包 (ClientboundPlayerChatPacket) 中的 sender 仍然是 realUuid,
 * 客户端找不到匹配的玩家条目 → "聊天信息验证错误".
 * 系统消息不需要签名验证, 彻底绕过此问题.
 * <p>
 * 前缀来源: 统一使用 {@link NickManager#getEffectivePrefix(Player)} 获取 HyperNick Rank 前缀,
 * 完全不读取 LuckPerms 原生前缀, 保证匿名/非匿名玩家前缀逻辑一致.
 * <p>
 * 防重复机制: 发送聊天消息前调用 {@link SystemMessagePacketListener#bypassNext(UUID)} 标记
 * 目标玩家, {@link SystemMessagePacketListener} 检测到标记后跳过该玩家的下一个 SYSTEM_CHAT 包,
 * 防止已包含前缀的聊天消息被二次处理导致前缀重复.
 * <p>
 * 渲染格式: HyperNick Rank 前缀 + 昵称 + 消息. 昵称颜色自动继承前缀中最后一个颜色代码.
 * <p>
 * 支持传统颜色代码 ({@code &a &b &l}) 和 HEX 颜色代码 ({@code &#FF55FF}).
 */
public class ChatListener implements Listener {

    private final HyperNick plugin;
    private final NickManager nickManager;

    public ChatListener(HyperNick plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfig().getBoolean("override-chat", true)) {
            return;
        }

        final Player source = event.getPlayer();
        final Component message = event.message();

        // 取消原版签名聊天 (阻止 ClientboundPlayerChatPacket 发送)
        // 改为手动以系统消息发送, 绕过 fake UUID 与签名 UUID 不一致的问题
        event.setCancelled(true);

        // 逐个观看者渲染并发送
        for (Audience viewer : event.viewers()) {
            Component rendered = renderChat(source, message, viewer);
            if (viewer instanceof Player playerViewer) {
                // Player 观看者: 标记跳过 SystemMessagePacketListener, 防止前缀重复
                SystemMessagePacketListener.bypassNext(playerViewer.getUniqueId());
                playerViewer.sendMessage(rendered);
            } else {
                // 非 Player 观看者 (控制台等): 普通发送
                viewer.sendMessage(rendered);
            }
        }
    }

    /**
     * 为单个观看者渲染聊天消息.
     * <p>
     * 名称组件会继承前缀中最后一个颜色代码, 使昵称与前缀颜色一致.
     * 例如前缀 {@code &b[MVP] } → 昵称显示为青色.
     */
    private Component renderChat(Player source, Component message, Audience viewer) {
        String nick = nickManager.getDisplayName(source);
        // 统一使用 HyperNick Rank 前缀, 不读取 LuckPerms 原生前缀
        String prefix = nickManager.getEffectivePrefix(source);

        Component nameComponent = Component.text(nick);
        return buildChat(prefix, nameComponent, message);
    }

    /**
     * 依据 config.yml 的 chat-format 拼装聊天组件.
     * <p>
     * 模板使用 & 代码 (含 HEX), HyperNick Rank 前缀也为 & 代码, 这里统一转换后解析.
     * 名称组件的颜色从前缀中最后一个颜色代码继承.
     */
    private Component buildChat(String prefix, Component nameComponent, Component message) {
        String format = plugin.getConfig().getString("chat-format", "{prefix}{name}&r&7: &r{message}");
        int msgIdx = format.indexOf("{message}");
        String headerTemplate = msgIdx >= 0 ? format.substring(0, msgIdx) : format + "&7: &r";
        String tail = msgIdx >= 0 ? format.substring(msgIdx + "{message}".length()) : "";

        // 代入前缀 (无 suffix, 完全使用 HyperNick Rank 前缀), 再将模板中的 & 代码 (含 HEX) 统一转为 §
        headerTemplate = ColorUtil.color(headerTemplate.replace("{prefix}", prefix).replace("{suffix}", ""));
        tail = ColorUtil.color(tail.replace("{prefix}", prefix).replace("{suffix}", ""));

        int nameIdx = headerTemplate.indexOf("{name}");
        Component header;
        if (nameIdx >= 0) {
            String before = headerTemplate.substring(0, nameIdx);
            String after = headerTemplate.substring(nameIdx + "{name}".length());

            // 从 "before" 部分提取最后一个颜色, 应用到名称组件
            // 这样昵称会继承前缀的颜色 (如 &b[MVP] → 昵称为青色)
            TextColor nameColor = ColorUtil.getLastColor(before);
            if (nameColor != null) {
                nameComponent = nameComponent.color(nameColor);
            }

            header = Component.empty()
                    .append(ColorUtil.sectionToComponent(before))
                    .append(nameComponent)
                    .append(ColorUtil.sectionToComponent(after));
        } else {
            header = ColorUtil.sectionToComponent(headerTemplate).append(nameComponent);
        }
        return header.append(message).append(ColorUtil.sectionToComponent(tail));
    }
}