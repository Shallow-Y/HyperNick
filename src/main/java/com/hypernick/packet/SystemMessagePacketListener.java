package com.hypernick.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.manager.NickManager;
import com.hypernick.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统消息数据包拦截器 — 统一为所有玩家添加 Rank 前缀.
 * <p>
 * 拦截 {@code ClientboundSystemChatPacket} (SYSTEM_CHAT), 将消息中的玩家真实名
 * 替换为带 Rank 前缀和颜色继承的名称组件.
 * <p>
 * 这是前缀显示的统一处理层:
 * <ul>
 *   <li>已匿名玩家: 真实名 → 前缀 + 昵称 (如 "Steve" → "§b[MVP] §bSwiftFox")</li>
 *   <li>未匿名玩家: 真实名 → 前缀 + 真实名 (如 "Steve" → "§7Steve", 需开启 enable-group-prefix)</li>
 * </ul>
 * <p>
 * 防重复机制:
 * <ol>
 *   <li>ChatListener 发送聊天消息前调用 {@link #bypassNext(UUID)} 标记目标玩家,
 *       本监听器检测到标记后跳过该玩家的下一个 SYSTEM_CHAT 包, 防止前缀重复</li>
 *   <li>DeathAdvancementListener 仅处理已匿名玩家 (真实名 → 昵称), 替换后真实名不在消息中,
 *       本监听器不会找到匹配, 不会二次处理</li>
 *   <li>PlayerConnectionListener 对未匿名玩家使用纯名称 (无前缀), 由本监听器统一添加前缀;
 *       对已匿名玩家使用前缀+昵称, 真实名不在消息中, 不会二次处理</li>
 * </ol>
 * <p>
 * 未匿名玩家防双前缀策略 (扁平化纯文本检测):
 * <p>
 * PaperMC 的系统消息 (死亡/进度等) 使用 playerListName (已含前缀, 如 "[VIP] Steve")
 * 而非 player.getName() ("Steve"). Adventure 的 replaceText(matchLiteral) 不跨组件
 * 边界匹配, 无法用占位符策略 (前缀和名称在不同子组件节点中, 占位符匹配失败).
 * <p>
 * 解决方案: 在替换前先将消息扁平化为纯文本, 检查是否已包含 playerListName 纯文本.
 * 如果已包含 → 消息已含前缀, 跳过该玩家; 否则替换真实名为 nameComponent.
 */
public class SystemMessagePacketListener {

    /** 标记下一帧内不需要处理的玩家 UUID (ChatListener 发送聊天时设置) */
    private static final Set<UUID> BYPASS_NEXT = ConcurrentHashMap.newKeySet();

    /**
     * 标记指定玩家的下一个 SYSTEM_CHAT 包跳过处理.
     * <p>
     * 由 {@link com.hypernick.listener.ChatListener} 在发送聊天消息前调用,
     * 防止已包含前缀的聊天消息被本监听器二次处理导致前缀重复.
     *
     * @param uuid 目标玩家 UUID
     */
    public static void bypassNext(UUID uuid) {
        BYPASS_NEXT.add(uuid);
    }

    public static void register(HyperNick plugin, NickManager nickManager) {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.NORMAL,
                PacketType.Play.Server.SYSTEM_CHAT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }

                // 检查是否被 ChatListener 标记为跳过
                if (BYPASS_NEXT.remove(event.getPlayer().getUniqueId())) {
                    return;
                }

                PacketContainer packet = event.getPacket();

                // 尝试读取消息内容
                String json = null;
                boolean isChatComponent = false;

                // 方式1: 通过 WrappedChatComponent 读取
                try {
                    WrappedChatComponent wrapped = packet.getChatComponents().read(0);
                    if (wrapped != null) {
                        json = wrapped.getJson();
                        isChatComponent = true;
                    }
                } catch (Throwable ignored) {
                }

                // 方式2: 通过字符串读取 (某些版本的 SYSTEM_CHAT 使用 JSON 字符串)
                if (json == null || json.isEmpty()) {
                    try {
                        json = packet.getStrings().read(0);
                    } catch (Throwable ignored) {
                    }
                }

                if (json == null || json.isEmpty()) {
                    return;
                }

                // 反序列化 JSON → Adventure Component
                Component message;
                try {
                    message = GsonComponentSerializer.gson().deserialize(json);
                } catch (Throwable e) {
                    // JSON 解析失败, 跳过
                    return;
                }

                // 替换所有在线玩家的真实名 → 带前缀颜色的名称组件
                Component replaced = message;
                boolean changed = false;

                // 1. 替换已匿名玩家的真实名 → 前缀 + 昵称
                for (UUID uuid : nickManager.getNickedPlayers()) {
                    NickData data = nickManager.getData(uuid);
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

                    Component before = replaced;
                    replaced = replaced.replaceText(TextReplacementConfig.builder()
                            .matchLiteral(realName)
                            .replacement(nameComponent)
                            .build());
                    if (!replaced.equals(before)) {
                        changed = true;
                    }
                }

                // 2. 替换未匿名玩家的真实名 → 前缀 + 真实名 (需开启 enable-group-prefix)
                if (plugin.getConfig().getBoolean("enable-group-prefix", true)) {
                    // 扁平化当前消息为纯文本, 用于检测是否已含前缀
                    // PaperMC 的系统消息 (死亡/进度等) 可能使用 playerListName (已含前缀),
                    // 此时消息纯文本中已包含 "[VIP] Steve", 不需要再添加前缀.
                    // Adventure 的 replaceText(matchLiteral) 不跨组件边界匹配,
                    // 无法用占位符策略, 改为直接检查扁平化纯文本.
                    String messagePlain = PlainTextComponentSerializer.plainText().serialize(replaced);

                    for (Player player : Bukkit.getOnlinePlayers()) {
                        // 跳过已匿名玩家 (已在上面处理)
                        if (nickManager.isNicked(player.getUniqueId())) {
                            continue;
                        }

                        String name = player.getName();
                        if (name == null || name.isEmpty()) {
                            continue;
                        }

                        // 检查消息纯文本中是否已包含 playerListName (含前缀, 如 "[VIP] Steve")
                        // 如果包含, 说明 PaperMC 已用 playerListName 构建消息, 前缀已存在 → 跳过
                        String listNamePlain = PlainTextComponentSerializer.plainText()
                                .serialize(player.playerListName());
                        if (!listNamePlain.isEmpty() && !listNamePlain.equals(name)
                                && messagePlain.contains(listNamePlain)) {
                            continue; // 消息已含前缀, 跳过
                        }

                        // 消息中只有真实名 (无前缀), 替换为 前缀 + 真实名
                        Component nameComponent = nickManager.buildPrefixedNameComponent(player.getUniqueId());
                        if (nameComponent == null) {
                            continue;
                        }

                        Component before = replaced;
                        replaced = replaced.replaceText(TextReplacementConfig.builder()
                                .matchLiteral(name)
                                .replacement(nameComponent)
                                .build());
                        if (!replaced.equals(before)) {
                            changed = true;
                        }
                    }
                }

                if (!changed) {
                    return;
                }

                // 序列化回 JSON 并写回数据包
                String newJson = GsonComponentSerializer.gson().serialize(replaced);

                if (isChatComponent) {
                    try {
                        packet.getChatComponents().write(0, WrappedChatComponent.fromJson(newJson));
                    } catch (Throwable ignored) {
                    }
                } else {
                    try {
                        packet.getStrings().write(0, newJson);
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }
}
