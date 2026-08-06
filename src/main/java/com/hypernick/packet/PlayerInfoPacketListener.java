package com.hypernick.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.NetworkMarker;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.Converters;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import com.google.common.collect.Multimap;
import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.manager.NickManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ProtocolLib 数据包监听器 — 核心伪装层.
 * <p>
 * 拦截两类出站数据包, 将匿名玩家的真实 UUID + 真实名替换为 fakeUuid + 昵称:
 * <ul>
 *   <li>{@code ClientboundPlayerInfoUpdatePacket} (PLAYER_INFO):
 *       在 ADD_PLAYER 阶段替换 GameProfile 的 UUID 与 name, 保留皮肤属性.
 *       客户端看到的是一个全新身份的玩家 (不同 UUID + 不同名).</li>
 *   <li>{@code ClientboundPlayerInfoRemovePacket} (PLAYER_INFO_REMOVE):
 *       将移除列表中的真实 UUID 替换为 fakeUuid, 确保客户端能正确移除伪装条目.</li>
 * </ul>
 * <p>
 * 服务端始终使用真实 UUID, 背包/权限/经济等数据完全继承, 不受影响.
 */
public class PlayerInfoPacketListener {

    public static void register(HyperNick plugin, NickManager nickManager) {
        final HyperNick pluginInstance = plugin;

        // 监听 PLAYER_INFO (添加/更新) 和 PLAYER_INFO_REMOVE (移除)
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                pluginInstance, ListenerPriority.NORMAL,
                PacketType.Play.Server.PLAYER_INFO,
                PacketType.Play.Server.PLAYER_INFO_REMOVE
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }
                PacketContainer packet = event.getPacket();
                PacketType type = packet.getType();

                if (type == PacketType.Play.Server.PLAYER_INFO) {
                    handlePlayerInfoUpdate(packet, nickManager, pluginInstance);
                } else if (type == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
                    handlePlayerInfoRemove(packet, nickManager, pluginInstance);
                }
            }
        });

        // 监听实体生成包: 替换玩家实体 UUID 为 fakeUuid.
        // <p>
        // 关键: showPlayer 发送实体生成包时使用真实 UUID, 但 Tab 列表已被替换为 fakeUuid.
        // 客户端通过 UUID 关联实体与 Tab 条目, UUID 不匹配会导致玩家实体不可见.
        // <p>
        // MC 1.20.2+ 移除了 NAMED_ENTITY_SPAWN, 玩家生成统一使用 SPAWN_ENTITY (ClientboundAddEntityPacket).
        // 此处监听 SPAWN_ENTITY, 读取实体 UUID, 若属于匿名玩家则替换为 fakeUuid.
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                pluginInstance, ListenerPriority.NORMAL,
                PacketType.Play.Server.SPAWN_ENTITY
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }
                PacketContainer packet = event.getPacket();
                try {
                    UUID uuid = packet.getUUIDs().read(0);
                    if (uuid == null) {
                        return;
                    }
                    NickData data = nickManager.getData(uuid);
                    if (data != null && data.getNickName() != null && data.getFakeUuid() != null) {
                        packet.getUUIDs().write(0, data.getFakeUuid());
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * 手动向所有在线玩家 (包括自身) 发送 PLAYER_INFO_REMOVE 包, 移除指定 UUID 的 Tab 条目.
     * <p>
     * 用于 reset/re-nick 时, 在清除数据前先移除客户端的旧 fakeUuid 条目,
     * 避免因数据已清空导致数据包监听器无法替换 UUID, 残留幽灵条目.
     * <p>
     * 关键: 使用 withListeners=false 绕过数据包监听器, 确保发送的 UUID 不被修改.
     * 因为此时发送的 UUID 可能是 fakeUuid (监听器无法通过 fakeUuid 反查到 NickData,
     * 但如果恰好与某个 realUuid 相同则会被错误替换).
     *
     * @param sourcePlayer 源玩家 (也向自己发送, 用于刷新自身 Tab 条目)
     * @param uuidToRemove 要从客户端 Tab 列表中移除的 UUID
     */
    public static void sendRemovePacketToAll(Player sourcePlayer, UUID uuidToRemove) {
        if (uuidToRemove == null) {
            return;
        }
        try {
            PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            List<UUID> uuids = new ArrayList<>(Collections.singletonList(uuidToRemove));
            packet.getLists(Converters.passthrough(UUID.class)).write(0, uuids);

            for (Player viewer : Bukkit.getOnlinePlayers()) {
                try {
                    ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, packet, null, false);
                } catch (Throwable e) {
                    try {
                        ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, packet);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 向玩家自身发送 PLAYER_INFO_ADD 包, 刷新其自身的 Tab 列表条目.
     * <p>
     * 当玩家 nick/reset 后, 其他玩家的 Tab 列表通过 hidePlayer/showPlayer 刷新,
     * 但玩家自身的 Tab 条目不会被 hidePlayer/showPlayer 更新 (Paper 跳过自身).
     * 此方法手动构造 PLAYER_INFO_ADD 包发送给玩家自身, 数据包监听器会拦截并替换
     * GameProfile 的 UUID + name 为 fakeUuid + 昵称 (若已匿名).
     * <p>
     * 保留玩家原有的皮肤属性, 避免刷新后皮肤丢失.
     *
     * @param player 目标玩家
     * @param plugin 插件实例
     */
    public static void sendSelfInfoAdd(Player player, HyperNick plugin) {
        try {
            PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);

            // 设置所有动作 (与服务器原始 ADD_PLAYER 一致)
            Set<EnumWrappers.PlayerInfoAction> actions = EnumSet.of(
                    EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                    EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
                    EnumWrappers.PlayerInfoAction.UPDATE_LATENCY,
                    EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE,
                    EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME,
                    EnumWrappers.PlayerInfoAction.UPDATE_HAT,
                    EnumWrappers.PlayerInfoAction.UPDATE_LIST_ORDER
            );
            packet.getPlayerInfoActions().write(0, actions);

            // 构造 GameProfile: 使用真实 UUID + 真实名 (数据包监听器会替换为 fakeUuid + 昵称)
            WrappedGameProfile gameProfile = new WrappedGameProfile(player.getUniqueId(), player.getName());

            // 复制皮肤属性 (保留原皮肤, 避免刷新后变成 Steve/Alex)
            try {
                var paperProfile = player.getPlayerProfile();
                if (paperProfile != null) {
                    for (var prop : paperProfile.getProperties()) {
                        gameProfile.getProperties().put(prop.getName(),
                                new WrappedSignedProperty(prop.getName(), prop.getValue(), prop.getSignature()));
                    }
                }
            } catch (Throwable ignored) {
                // 获取皮肤属性失败时使用无皮肤的基础 Profile
            }

            // 构造 PlayerInfoData
            int latency = 0;
            try {
                latency = player.getPing();
            } catch (Throwable ignored) {
            }

            PlayerInfoData data = new PlayerInfoData(
                    player.getUniqueId(),
                    latency,
                    true,
                    EnumWrappers.NativeGameMode.fromBukkit(player.getGameMode()),
                    gameProfile,
                    null,
                    true,
                    0,
                    null
            );

            List<PlayerInfoData> dataList = new ArrayList<>(Collections.singletonList(data));
            packet.getPlayerInfoDataLists().write(0, dataList);

            // 发送给玩家自身 (经过数据包监听器, 会替换为 fakeUuid + 昵称)
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (Throwable t) {
            plugin.getLogger().warning("发送自身 PlayerInfo 刷新包失败: " + t.getMessage());
        }
    }

    /**
     * 处理 PLAYER_INFO 更新包: 在 ADD_PLAYER 时替换 GameProfile UUID + name.
     */
    private static void handlePlayerInfoUpdate(PacketContainer packet, NickManager nickManager, HyperNick plugin) {
        // 仅在包含 ADD_PLAYER 动作时, GameProfile 才存在
        Set<EnumWrappers.PlayerInfoAction> actions = packet.getPlayerInfoActions().read(0);
        if (actions == null || !actions.contains(EnumWrappers.PlayerInfoAction.ADD_PLAYER)) {
            return;
        }

        List<PlayerInfoData> entries = packet.getPlayerInfoDataLists().read(0);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        boolean changed = false;
        List<PlayerInfoData> modified = new ArrayList<>(entries.size());

        for (PlayerInfoData entry : entries) {
            WrappedGameProfile profile = entry.getProfile();
            if (profile == null) {
                modified.add(entry);
                continue;
            }

            UUID realUuid = entry.getProfileId();
            if (realUuid == null) {
                realUuid = profile.getUUID();
            }
            NickData data = nickManager.getData(realUuid);
            if (data == null || data.getNickName() == null || data.getFakeUuid() == null) {
                modified.add(entry);
                continue;
            }

            PlayerInfoData replaced = replaceProfileIdentity(entry, profile, data, plugin);
            modified.add(replaced != null ? replaced : entry);
            if (replaced != null) {
                changed = true;
            }
        }

        if (changed) {
            packet.getPlayerInfoDataLists().write(0, modified);
        }
    }

    /**
     * 处理 PLAYER_INFO_REMOVE 移除包: 将真实 UUID 替换为 fakeUuid.
     * <p>
     * 客户端持有的是 fakeUuid 条目, 若不移除包中的 UUID, 客户端将残留幽灵条目.
     */
    @SuppressWarnings("unchecked")
    private static void handlePlayerInfoRemove(PacketContainer packet, NickManager nickManager, HyperNick plugin) {
        List<UUID> uuids;
        try {
            uuids = packet.getLists(Converters.passthrough(UUID.class)).read(0);
        } catch (Throwable t) {
            // 回退: 尝试通用 List 读取
            try {
                List<?> raw = (List<?>) packet.getSpecificModifier(List.class).read(0);
                if (raw == null || raw.isEmpty()) {
                    return;
                }
                uuids = new ArrayList<>();
                for (Object item : raw) {
                    if (item instanceof UUID u) {
                        uuids.add(u);
                    } else if (item != null) {
                        uuids.add(UUID.fromString(item.toString()));
                    }
                }
            } catch (Throwable t2) {
                return;
            }
        }

        if (uuids == null || uuids.isEmpty()) {
            return;
        }

        boolean changed = false;
        List<UUID> modified = new ArrayList<>(uuids.size());

        for (UUID uuid : uuids) {
            NickData data = nickManager.getData(uuid);
            if (data != null && data.getFakeUuid() != null) {
                // 真实 UUID → 替换为 fakeUuid
                modified.add(data.getFakeUuid());
                changed = true;
            } else {
                modified.add(uuid);
            }
        }

        if (changed) {
            try {
                packet.getLists(Converters.passthrough(UUID.class)).write(0, modified);
            } catch (Throwable t) {
                try {
                    packet.getSpecificModifier(List.class).write(0, modified);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 构造新的 PlayerInfoData, 同时替换 GameProfile UUID + name 为伪装身份.
     * 保留原皮肤属性 (textures), 仅替换 UUID 与名称.
     */
    private static PlayerInfoData replaceProfileIdentity(PlayerInfoData entry, WrappedGameProfile profile,
                                                         NickData data, HyperNick plugin) {
        try {
            UUID fakeUuid = data.getFakeUuid();
            String nick = data.getNickName();

            // 创建全新的 GameProfile: fakeUuid + 昵称
            WrappedGameProfile newProfile = new WrappedGameProfile(fakeUuid, nick);

            // 复制皮肤属性 (保留原皮肤)
            Multimap<String, WrappedSignedProperty> originalProps = profile.getProperties();
            if (originalProps != null && !originalProps.isEmpty()) {
                newProfile.getProperties().putAll(originalProps);
            }

            // 使用 fakeUuid 作为 profileId, RemoteChatSessionData 设为 null
            // 设为 null 可避免 fakeUuid 与 chatSession 中的 realUuid 不一致
            // 导致客户端聊天签名验证失败 ("聊天信息验证错误")
            return new PlayerInfoData(
                    fakeUuid,
                    entry.getLatency(),
                    entry.isListed(),
                    entry.getGameMode(),
                    newProfile,
                    entry.getDisplayName(),
                    entry.isShowHat(),
                    entry.getListOrder(),
                    null
            );
        } catch (Throwable t) {
            plugin.getLogger().warning("替换 GameProfile 身份失败 (UUID=" + entry.getProfileId()
                    + ", nick=" + data.getNickName() + "): " + t.getMessage());
            return null;
        }
    }
}
