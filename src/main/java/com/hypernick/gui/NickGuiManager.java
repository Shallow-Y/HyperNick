package com.hypernick.gui;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.manager.NickManager;
import com.hypernick.util.ColorUtil;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nick GUI 管理器 — 模仿 Hypixel Nickname 系统的 Book + Sign GUI.
 * <p>
 * 通过书本书籍 GUI 实现分步选择 (Rank → Skin → Name), 通过告示牌 GUI 实现自定义昵称输入.
 * 书本中的可点击文本通过 ClickEvent.runCommand 触发隐藏的 /nick gui 子命令.
 * <p>
 * 流程:
 * <ol>
 *   <li>/nick → 打开主菜单 (说明 + "我已了解" 按钮)</li>
 *   <li>主菜单 → Rank 选择页 → 选择 Rank</li>
 *   <li>Rank 选择 → Skin 选择页 → 选择皮肤模式</li>
 *   <li>Skin 选择 → Name 选择页 → 随机/复用/自定义</li>
 *   <li>自定义 → 告示牌 GUI 输入昵称 → 确认</li>
 * </ol>
 */
public class NickGuiManager implements Listener {

    private final HyperNick plugin;
    private final NickManager nickManager;
    /** GUI 流程状态: 玩家选择的 Rank 和 SkinMode */
    private final Map<UUID, GuiState> guiStates = new ConcurrentHashMap<>();
    /** 告示牌输入会话: 玩家正在告示牌中输入昵称 */
    private final Map<UUID, SignSession> signSessions = new ConcurrentHashMap<>();

    public NickGuiManager(HyperNick plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
        registerSignListener();
    }

    /** GUI 流程状态 (玩家在 Book GUI 中选择的 Rank 和 SkinMode) */
    private static class GuiState {
        String rankKey;
        NickData.SkinMode skinMode;

        GuiState(String rankKey, NickData.SkinMode skinMode) {
            this.rankKey = rankKey;
            this.skinMode = skinMode;
        }
    }

    /** 告示牌输入会话 (保存原始方块数据用于恢复) */
    private static class SignSession {
        final GuiState state;
        final Location location;
        final BlockData originalBlockData;

        SignSession(GuiState state, Location location, BlockData originalBlockData) {
            this.state = state;
            this.location = location;
            this.originalBlockData = originalBlockData;
        }
    }

    // ==================== ProtocolLib 告示牌监听 ====================

    /**
     * 注册 ProtocolLib 数据包监听器, 拦截客户端发送的告示牌更新包.
     * <p>
     * 当玩家完成告示牌编辑并点击"完成"时, 客户端发送 UPDATE_SIGN 包.
     * 读取第一行非空文本作为自定义昵称.
     */
    private void registerSignListener() {
        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            plugin.getLogger().warning("未检测到 ProtocolLib, 告示牌 GUI 不可用.");
            return;
        }
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                plugin, ListenerPriority.NORMAL, PacketType.Play.Client.UPDATE_SIGN
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                SignSession session = signSessions.get(player.getUniqueId());
                if (session == null) {
                    return;
                }
                // 取消事件, 防止服务端处理虚假告示牌更新
                event.setCancelled(true);

                // 读取告示牌文本
                String name = null;
                try {
                    String[] lines = event.getPacket().getStringArrays().read(0);
                    if (lines != null) {
                        for (String line : lines) {
                            if (line != null && !line.trim().isEmpty()) {
                                name = line.trim();
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 尝试使用 Component 数组读取 (新版客户端可能使用 Component)
                    try {
                        var components = event.getPacket().getSpecificModifier(
                                net.kyori.adventure.text.Component[].class).read(0);
                        if (components != null) {
                            for (var comp : components) {
                                if (comp != null) {
                                    String text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                            .serialize(comp);
                                    if (!text.trim().isEmpty()) {
                                        name = text.trim();
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored2) {
                    }
                }

                // 恢复方块并处理结果 (在主线程执行)
                final String finalName = name;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    restoreBlock(player, session);
                    signSessions.remove(player.getUniqueId());
                    handleSignResult(player, finalName);
                });
            }
        });
    }

    // ==================== Book GUI 页面 ====================

    /**
     * 打开主菜单 (说明页).
     */
    public void openMainMenu(Player player) {
        Component page = Component.text("HyperNick 匿名系统\n\n", NamedTextColor.AQUA)
                .append(Component.text("通过匿名系统, 你可以伪装你的身份", NamedTextColor.GRAY))
                .append(Component.text(" (名称、等级和皮肤).\n\n", NamedTextColor.GRAY))
                .append(clickable("» 我已了解, 开始设置昵称 «", "/nick gui rank", NamedTextColor.GREEN));
        player.openBook(createBook(page));
    }

    /**
     * 打开 Rank 选择页.
     */
    public void openRankMenu(Player player) {
        var page = Component.text("选择伪装等级\n\n", NamedTextColor.AQUA).toBuilder();
        for (String rank : nickManager.getAvailableRanks()) {
            String prefix = nickManager.getRankPrefix(rank);
            String display = rank.toUpperCase();
            Component rankLine = Component.text("» ", NamedTextColor.GRAY)
                    .append(ColorUtil.toComponent(prefix + display))
                    .clickEvent(ClickEvent.runCommand("/nick gui selectrank " + rank))
                    .append(Component.text("\n"));
            page.append(rankLine);
        }
        player.openBook(createBook(page.build()));
    }

    /**
     * 选择 Rank, 进入 Skin 选择页.
     */
    public void selectRank(Player player, String rankKey) {
        if (nickManager.getRankSection(rankKey) == null) {
            return;
        }
        GuiState state = guiStates.computeIfAbsent(player.getUniqueId(),
                k -> new GuiState(nickManager.pickRandomRank(), NickData.SkinMode.REAL));
        state.rankKey = rankKey;
        openSkinMenu(player);
    }

    /**
     * 打开 Skin 选择页.
     */
    public void openSkinMenu(Player player) {
        Component page = Component.text("选择皮肤\n\n", NamedTextColor.AQUA)
                .append(clickable("» 使用真实皮肤 «", "/nick gui selectskin REAL", NamedTextColor.GREEN))
                .append(Component.text("\n"))
                .append(clickable("» 随机皮肤 «", "/nick gui selectskin RANDOM", NamedTextColor.YELLOW))
                .append(Component.text("\n"))
                .append(clickable("» 默认皮肤 (Steve/Alex) «", "/nick gui selectskin RESET", NamedTextColor.GRAY));
        player.openBook(createBook(page));
    }

    /**
     * 选择 Skin 模式, 进入 Name 选择页.
     */
    public void selectSkin(Player player, NickData.SkinMode mode) {
        GuiState state = guiStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.skinMode = mode;
        openNameMenu(player);
    }

    /**
     * 打开 Name 选择页.
     */
    public void openNameMenu(Player player) {
        var page = Component.text("选择昵称\n\n", NamedTextColor.AQUA).toBuilder();
        page.append(clickable("» 随机昵称 «", "/nick gui name random", NamedTextColor.GREEN))
                .append(Component.text("\n"));
        String lastNick = nickManager.getLastNick(player.getUniqueId());
        if (lastNick != null && !lastNick.isEmpty()) {
            page.append(Component.text("» 使用上次昵称: ", NamedTextColor.YELLOW)
                            .append(Component.text(lastNick, NamedTextColor.WHITE))
                            .clickEvent(ClickEvent.runCommand("/nick gui name reuse")))
                    .append(Component.text("\n"));
        }
        page.append(clickable("» 自定义昵称 (告示牌输入) «", "/nick gui name custom", NamedTextColor.AQUA));
        player.openBook(createBook(page.build()));
    }

    // ==================== Name 操作 ====================

    /** 应用随机昵称 (GUI 流程结束) */
    public void applyRandomName(Player player) {
        GuiState state = guiStates.remove(player.getUniqueId());
        String rankKey = state != null ? state.rankKey : nickManager.pickRandomRank();
        NickData.SkinMode skinMode = state != null ? state.skinMode : NickData.SkinMode.REAL;
        nickManager.nickRandomWithSkin(player, rankKey, skinMode);
    }

    /** 复用上次昵称 (GUI 流程结束) */
    public void applyReuseName(Player player) {
        GuiState state = guiStates.remove(player.getUniqueId());
        String rankKey = state != null ? state.rankKey : null;
        NickData.SkinMode skinMode = state != null ? state.skinMode : NickData.SkinMode.REAL;
        nickManager.nickReuse(player, rankKey, skinMode);
    }

    // ==================== 告示牌 GUI ====================

    /**
     * 打开告示牌 GUI 输入自定义昵称.
     * <p>
     * 在玩家脚下放置一个虚拟告示牌方块 (仅客户端可见), 然后发送 OPEN_SIGN_EDITOR
     * 数据包打开告示牌编辑器. 玩家在告示牌第一行输入昵称, 点击"完成"后由
     * ProtocolLib 监听器捕获 UPDATE_SIGN 包并处理.
     *
     * @param player 目标玩家
     */
    public void openSignInput(Player player) {
        GuiState state = guiStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }

        // 检查 ProtocolLib 是否可用
        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            plugin.msg(player, "protocollib-missing", Map.of());
            return;
        }

        // 获取玩家脚下位置
        Location loc = player.getLocation();
        Block block = loc.getBlock();
        BlockData originalData = block.getBlockData();

        // 1. 向客户端发送虚假告示牌方块
        player.sendBlockChange(loc, Material.OAK_SIGN.createBlockData());

        // 2. 发送告示牌文本 (空文本, 提示在第一行输入)
        try {
            player.sendSignChange(loc, new String[]{"", "", "", ""});
        } catch (Throwable ignored) {
            // sendSignChange 失败时忽略, OPEN_SIGN_EDITOR 仍可打开编辑器
        }

        // 3. 发送 OPEN_SIGN_EDITOR 数据包打开告示牌编辑器
        try {
            PacketContainer openSign = new PacketContainer(PacketType.Play.Server.OPEN_SIGN_EDITOR);
            openSign.getBlockPositionModifier().write(0,
                    new BlockPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            // MC 1.20+ 需要设置 isFrontSide (正面)
            try {
                openSign.getBooleans().write(0, true);
            } catch (Throwable ignored) {
                // 旧版本无需此字段
            }
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, openSign);
        } catch (Throwable t) {
            plugin.getLogger().warning("无法打开告示牌GUI: " + t.getMessage());
            // 恢复方块
            player.sendBlockChange(loc, originalData);
            return;
        }

        // 4. 保存告示牌会话
        signSessions.put(player.getUniqueId(), new SignSession(state, loc, originalData));
    }

    /**
     * 处理告示牌输入结果.
     *
     * @param player 玩家
     * @param name   昵称 (第一行非空文本, null 表示玩家未输入)
     */
    public void handleSignResult(Player player, String name) {
        GuiState state = guiStates.remove(player.getUniqueId());
        if (state == null || name == null || name.isBlank()) {
            return;
        }
        nickManager.nickPlayerWithSkin(player, name, state.rankKey, state.skinMode);
    }

    // ==================== 会话管理 ====================

    public boolean hasSignSession(UUID uuid) {
        return signSessions.containsKey(uuid);
    }

    /**
     * 恢复玩家脚下的原始方块 (仅客户端).
     */
    private void restoreBlock(Player player, SignSession session) {
        try {
            player.sendBlockChange(session.location, session.originalBlockData);
        } catch (Throwable ignored) {
        }
    }

    /** 清理玩家会话 (退出服务器时调用) */
    public void cleanup(UUID uuid) {
        SignSession session = signSessions.remove(uuid);
        if (session != null) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                restoreBlock(player, session);
            }
        }
        guiStates.remove(uuid);
    }

    // ==================== 事件处理 ====================

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer().getUniqueId());
    }

    // ==================== 工具方法 ====================

    private Book createBook(Component... pages) {
        return Book.book(Component.text("HyperNick"), Component.text("HyperNick"), List.of(pages));
    }

    private Component clickable(String text, String command, NamedTextColor color) {
        return Component.text(text)
                .color(color)
                .clickEvent(ClickEvent.runCommand(command));
    }
}
