package com.hypernick.packet;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.Converters;
import com.hypernick.HyperNick;
import com.hypernick.manager.NickManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tab 补全数据包拦截器 — 数据包级别的最终防线.
 * <p>
 * 拦截出站 {@code ClientboundCommandSuggestionsPacket} (TAB_COMPLETE),
 * 在补全建议发送给客户端之前, 将其中匿名玩家的真实名替换为昵称.
 * <p>
 * 这是比 {@code TabCompleteEvent} 更底层的拦截, 能捕获所有通过数据包
 * 传输的补全建议, 无论来源是 Bukkit 命令、vanilla 命令还是其他插件.
 * <p>
 * 与 {@link com.hypernick.listener.TabCompleteListener} 配合使用:
 * TabCompleteListener 处理事件层, 本监听器处理数据包层, 双重保障.
 */
public class TabCompletePacketListener {

    public static void register(HyperNick plugin, NickManager nickManager) {
        final HyperNick pluginInstance = plugin;

        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                pluginInstance, ListenerPriority.NORMAL, PacketType.Play.Server.TAB_COMPLETE
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }

                // 构建真实名 → 昵称映射
                Map<String, String> realToNick = new HashMap<>();
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (nickManager.isNicked(player.getUniqueId())) {
                        realToNick.put(player.getName(), nickManager.getDisplayName(player));
                    }
                }
                if (realToNick.isEmpty()) {
                    return;
                }

                PacketContainer packet = event.getPacket();

                // 方式 1: 尝试以 List<String> 读取补全建议
                try {
                    List<String> suggestions = packet.getLists(Converters.passthrough(String.class)).read(0);
                    if (suggestions != null && !suggestions.isEmpty()) {
                        List<String> modified = new ArrayList<>(suggestions.size());
                        boolean changed = false;
                        for (String suggestion : suggestions) {
                            String nick = realToNick.get(suggestion);
                            if (nick != null) {
                                modified.add(nick);
                                changed = true;
                            } else {
                                modified.add(suggestion);
                            }
                        }
                        if (changed) {
                            packet.getLists(Converters.passthrough(String.class)).write(0, modified);
                        }
                        return;
                    }
                } catch (Throwable ignored) {
                    // 此包不支持 List<String> 读取, 尝试方式 2
                }

                // 方式 2: 尝试以通用 List 读取, 逐个检查元素类型
                try {
                    @SuppressWarnings("unchecked")
                    List<Object> rawList = (List<Object>) packet.getSpecificModifier(List.class).read(0);
                    if (rawList == null || rawList.isEmpty()) {
                        return;
                    }

                    // 检查元素是否为 String 类型
                    Object first = rawList.get(0);
                    if (first instanceof String) {
                        List<String> modified = new ArrayList<>(rawList.size());
                        boolean changed = false;
                        for (Object item : rawList) {
                            String str = (String) item;
                            String nick = realToNick.get(str);
                            if (nick != null) {
                                modified.add(nick);
                                changed = true;
                            } else {
                                modified.add(str);
                            }
                        }
                        if (changed) {
                            packet.getSpecificModifier(List.class).write(0, modified);
                        }
                    } else {
                        // 元素是复杂类型 (如 Brigadier Suggestion), 尝试反射获取 text 字段
                        replaceViaReflection(rawList, realToNick, packet);
                    }
                } catch (Throwable ignored) {
                    // 无法访问补全建议, 跳过
                }
            }
        });
    }

    /**
     * 通过反射替换复杂 Suggestion 对象中的 text 字段.
     * 适用于 Brigadier 的 Suggestion 类型 (有 getText() / getTooltip() 方法).
     */
    @SuppressWarnings("unchecked")
    private static void replaceViaReflection(List<Object> rawList, Map<String, String> realToNick,
                                             PacketContainer packet) {
        try {
            List<Object> modified = new ArrayList<>(rawList.size());
            boolean changed = false;

            for (Object item : rawList) {
                if (item == null) {
                    modified.add(null);
                    continue;
                }
                // 尝试调用 getText() 方法
                try {
                    java.lang.reflect.Method getText = item.getClass().getMethod("getText");
                    String text = (String) getText.invoke(item);
                    String nick = realToNick.get(text);
                    if (nick != null) {
                        // 尝试构造新的 Suggestion 对象
                        try {
                            java.lang.reflect.Constructor<?> ctor = item.getClass()
                                    .getConstructor(String.class);
                            modified.add(ctor.newInstance(nick));
                            changed = true;
                        } catch (NoSuchMethodException e) {
                            // 无法构造新对象, 尝试设置字段
                            try {
                                java.lang.reflect.Field textField = item.getClass().getDeclaredField("text");
                                textField.setAccessible(true);
                                textField.set(item, nick);
                                modified.add(item);
                                changed = true;
                            } catch (NoSuchFieldException e2) {
                                modified.add(item);
                            }
                        }
                    } else {
                        modified.add(item);
                    }
                } catch (Exception e) {
                    modified.add(item);
                }
            }

            if (changed) {
                packet.getSpecificModifier(List.class).write(0, modified);
            }
        } catch (Throwable ignored) {
        }
    }
}
