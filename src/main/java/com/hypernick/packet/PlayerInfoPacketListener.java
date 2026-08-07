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
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedSignedProperty;
import com.google.common.collect.Multimap;
import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.manager.NickManager;
import com.hypernick.util.ColorUtil;
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

    public static void sendSelfInfoAdd(Player player, HyperNick plugin) {
        try {
            PacketContainer packet = new PacketContainer(PacketType.Play.Server.PLAYER_INFO);

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

            WrappedGameProfile gameProfile = new WrappedGameProfile(player.getUniqueId(), player.getName());

            try {
                NickData nickData = plugin.getNickManager().getData(player.getUniqueId());
                boolean shouldCopySkin = (nickData == null)
                        || (nickData.getSkinMode() == NickData.SkinMode.REAL);
                if (shouldCopySkin) {
                    var paperProfile = player.getPlayerProfile();
                    if (paperProfile != null) {
                        for (var prop : paperProfile.getProperties()) {
                            gameProfile.getProperties().put(prop.getName(),
                                    new WrappedSignedProperty(prop.getName(), prop.getValue(), prop.getSignature()));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            int latency = 0;
            try {
                latency = player.getPing();
            } catch (Throwable ignored) {
            }

            WrappedChatComponent displayName = null;
            try {
                net.kyori.adventure.text.Component listName = player.playerListName();
                if (listName != null) {
                    String json = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                            .serialize(listName);
                    displayName = WrappedChatComponent.fromJson(json);
                }
            } catch (Throwable ignored) {
            }

            PlayerInfoData data = new PlayerInfoData(
                    player.getUniqueId(),
                    latency,
                    true,
                    EnumWrappers.NativeGameMode.fromBukkit(player.getGameMode()),
                    gameProfile,
                    displayName,
                    true,
                    0,
                    null
            );

            List<PlayerInfoData> dataList = new ArrayList<>(Collections.singletonList(data));
            packet.getPlayerInfoDataLists().write(0, dataList);

            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (Throwable t) {
            plugin.getLogger().warning("发送自身 PlayerInfo 刷新包失败: " + t.getMessage());
        }
    }

    private static void handlePlayerInfoUpdate(PacketContainer packet, NickManager nickManager, HyperNick plugin) {
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

    @SuppressWarnings("unchecked")
    private static void handlePlayerInfoRemove(PacketContainer packet, NickManager nickManager, HyperNick plugin) {
        List<UUID> uuids;
        try {
            uuids = packet.getLists(Converters.passthrough(UUID.class)).read(0);
        } catch (Throwable t) {
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

    private static PlayerInfoData replaceProfileIdentity(PlayerInfoData entry, WrappedGameProfile profile,
                                                         NickData data, HyperNick plugin) {
        try {
            UUID fakeUuid = data.getFakeUuid();
            String nick = data.getNickName();

            String displayName = nick;
            String rankKey = data.getRankKey();
            if (rankKey != null) {
                String rankPrefix = plugin.getNickManager().getRankPrefix(rankKey);
                if (ColorUtil.hasHexColor(rankPrefix)) {
                    displayName = "\u00A7r";
                }
            }

            WrappedGameProfile newProfile = new WrappedGameProfile(fakeUuid, displayName);

            if (data.getSkinMode() == NickData.SkinMode.REAL) {
                Multimap<String, WrappedSignedProperty> originalProps = profile.getProperties();
                if (originalProps != null && !originalProps.isEmpty()) {
                    newProfile.getProperties().putAll(originalProps);
                }
            }

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
