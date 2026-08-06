package com.hypernick.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.Converters;
import com.comphenix.protocol.wrappers.WrappedMessageSignature;
import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.manager.NickManager;

import java.util.Optional;
import java.util.UUID;

/**
 * 聊天数据包拦截器 — 签名聊天的最后防线.
 * <p>
 * 当 {@code override-chat: false} 或其他插件直接发送签名聊天包时,
 * {@link com.hypernick.listener.ChatListener} 无法拦截. 此监听器在数据包
 * 层面拦截 {@code ClientboundPlayerChatPacket} (PLAYER_INFO):
 * <ul>
 *   <li>将 sender UUID 从 realUuid 替换为 fakeUuid (让客户端能找到对应玩家条目)</li>
 *   <li>清除消息签名 (signature), 避免 fake UUID 与 real UUID 签名不匹配导致验证失败</li>
 * </ul>
 * <p>
 * 如果 {@code override-chat: true}, ChatListener 已经取消了原版事件并以系统消息发送,
 * 此监听器不会收到任何 CHAT 包 (安全网, 无副作用).
 */
public class ChatPacketListener {

    public static void register(HyperNick plugin, NickManager nickManager) {
        final HyperNick pluginInstance = plugin;

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                pluginInstance, ListenerPriority.NORMAL, PacketType.Play.Server.CHAT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }
                PacketContainer packet = event.getPacket();

                // 读取 sender UUID
                UUID senderUuid;
                try {
                    senderUuid = packet.getUUIDs().read(0);
                } catch (Throwable t) {
                    return;
                }
                if (senderUuid == null) {
                    return;
                }

                NickData data = nickManager.getData(senderUuid);
                if (data == null || data.getFakeUuid() == null) {
                    return;
                }

                // 1. 替换 sender UUID 为 fakeUuid
                try {
                    packet.getUUIDs().write(0, data.getFakeUuid());
                } catch (Throwable ignored) {
                }

                // 2. 清除消息签名 (签名绑定 realUuid, 与 fakeUuid 不匹配会导致验证失败)
                stripSignature(packet, pluginInstance);
            }
        });
    }

    /**
     * 尝试多种方式清除聊天包中的签名数据.
     * 不同 Minecraft 版本的包结构可能不同, 这里用 try-catch 逐个尝试.
     */
    @SuppressWarnings("unchecked")
    private static void stripSignature(PacketContainer packet, HyperNick plugin) {
        // 方式 1: 通过 WrappedMessageSignature converter 清除签名
        try {
            packet.getOptionals(Converters.passthrough(WrappedMessageSignature.class)).write(0, Optional.empty());
            return;
        } catch (Throwable ignored) {
        }

        // 方式 2: 直接设置 byte[] 签名为 null
        try {
            packet.getByteArrays().write(0, null);
            return;
        } catch (Throwable ignored) {
        }

        // 方式 3: 通过通用 Optional 清除
        try {
            packet.getSpecificModifier(Optional.class).write(0, Optional.empty());
        } catch (Throwable ignored) {
        }
    }
}
