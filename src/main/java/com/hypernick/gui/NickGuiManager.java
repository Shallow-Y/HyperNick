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
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nick GUI 管理器 — 模仿 Hypixel Nickname 系统的 Book + Sign GUI.
 * <p>
 * GUI 文本全部从 GUI/*.yml 配置文件加载, 支持自定义编辑.
 * 通过书本书籍 GUI 实现分步选择 (Rank → Skin → Name), 通过告示牌 GUI 实现自定义昵称输入.
 * <p>
 * 流程:
 * <ol>
 *   <li>/nick → 打开主菜单 (GUI/main.yml)</li>
 *   <li>主菜单 → Rank 选择页 (GUI/rank.yml)</li>
 *   <li>Rank 选择 → Skin 选择页 (GUI/skin.yml)</li>
 *   <li>Skin 选择 → Name 选择页 (GUI/name.yml)</li>
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
    /** 随机昵称待确认会话: 玩家选择使用或重新随机 */
    private final Map<UUID, PendingRandom> pendingRandoms = new ConcurrentHashMap<>();

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

    /** 随机昵称待确认会话 (保存生成的昵称和 GUI 状态, 供玩家确认) */
    private static class PendingRandom {
        final String nick;
        final GuiState state;

        PendingRandom(String nick, GuiState state) {
            this.nick = nick;
            this.state = state;
        }
    }

    // ==================== GUI 配置加载 ====================

    /**
     * 加载 GUI 配置文件.
     * <p>
     * 优先从 plugins/HyperNick/GUI/ 加载外部文件, 若不存在则从 JAR 内资源文件释放.
     *
     * @param fileName 文件名 (如 "main.yml")
     * @return YamlConfiguration, 加载失败时返回 null
     */
    private YamlConfiguration loadGuiConfig(String fileName) {
        File guiDir = new File(plugin.getDataFolder(), "GUI");
        if (!guiDir.exists()) {
            guiDir.mkdirs();
        }
        File guiFile = new File(guiDir, fileName);
        if (!guiFile.exists()) {
            // 从 JAR 内释放
            String resourcePath = "GUI/" + fileName;
            try (Reader reader = new InputStreamReader(plugin.getResource(resourcePath), StandardCharsets.UTF_8)) {
                if (reader != null) {
                    YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(reader);
                    defaultConfig.save(guiFile);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("无法加载 GUI 配置 " + fileName + ": " + e.getMessage());
            }
        }
        if (guiFile.exists()) {
            return YamlConfiguration.loadConfiguration(guiFile);
        }
        return null;
    }

    // ==================== 页面构建 ====================

    /**
     * 从 YAML lines 列表构建书本页面 Component.
     * <p>
     * 每行是一个 map, 包含:
     * <ul>
     *   <li>text: 文本内容 (支持 & 颜色代码和占位符替换)</li>
     *   <li>click: 点击触发的指令 (可选, 留空则为普通文本)</li>
     *   <li>condition: 显示条件 (可选, 如 "has_last_nick")</li>
     * </ul>
     *
     * @param config       YAML 配置
     * @param placeholders 占位符替换 (可为 null)
     * @return 书本页面 Component
     */
    private Component buildPage(YamlConfiguration config, Map<String, String> placeholders) {
        Component page = Component.empty();

        List<?> lines = config.getList("lines");
        if (lines == null) {
            return page;
        }

        for (Object entry : lines) {
            if (entry instanceof Map<?, ?> lineMap) {
                Component lineComponent = buildLine(lineMap, placeholders);
                if (lineComponent != null) {
                    page = page.append(lineComponent);
                    page = page.append(Component.text("\n"));
                }
            } else if (entry instanceof String text) {
                // 简单字符串行
                String replaced = replacePlaceholders(text, placeholders);
                page = page.append(ColorUtil.toComponent(replaced));
                page = page.append(Component.text("\n"));
            }
        }

        return page;
    }

    /**
     * 构建单行 Component (支持 click 和 condition).
     *
     * @param lineMap      行数据 (text, click, condition)
     * @param placeholders 占位符替换
     * @return Component, 若条件不满足则返回 null
     */
    private Component buildLine(Map<?, ?> lineMap, Map<String, String> placeholders) {
        // 检查 condition
        String condition = lineMap.get("condition") != null ? lineMap.get("condition").toString() : null;
        if (condition != null && !checkCondition(condition, placeholders)) {
            return null;
        }

        String text = lineMap.get("text") != null ? lineMap.get("text").toString() : "";
        text = replacePlaceholders(text, placeholders);

        Component component = ColorUtil.toComponent(text);

        // 添加点击事件
        String click = lineMap.get("click") != null ? lineMap.get("click").toString() : null;
        if (click != null && !click.isEmpty()) {
            click = replacePlaceholders(click, placeholders);
            component = component.clickEvent(ClickEvent.runCommand(click));
        }

        return component;
    }

    /**
     * 检查显示条件.
     *
     * @param condition    条件名 (如 "has_last_nick")
     * @param placeholders 占位符 (包含条件状态)
     * @return true 表示条件满足, 应显示该行
     */
    private boolean checkCondition(String condition, Map<String, String> placeholders) {
        if (placeholders == null) {
            return true;
        }
        String value = placeholders.get("__condition_" + condition);
        return "true".equals(value);
    }

    /**
     * 替换占位符.
     *
     * @param text         原始文本
     * @param placeholders 占位符映射 (如 {"last_nick" -> "SwiftFox"})
     * @return 替换后的文本
     */
    private String replacePlaceholders(String text, Map<String, String> placeholders) {
        if (text == null) {
            return null;
        }
        // 1. 解析 {lang:xxx} 占位符 — 从语言文件中查找
        int idx = 0;
        while ((idx = text.indexOf("{lang:", idx)) != -1) {
            int end = text.indexOf("}", idx + 6);
            if (end == -1) {
                break;
            }
            String langKey = text.substring(idx + 6, end);
            String langValue = plugin.getLangConfig().getString(langKey, "");
            text = text.substring(0, idx) + langValue + text.substring(end + 1);
            idx += langValue.length();
        }
        // 2. 解析普通占位符 {key}
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (!entry.getKey().startsWith("__")) {
                    text = text.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }
        }
        return text;
    }

    /**
     * 构建 Rank 选择页 (动态: 遍历可用 Rank).
     *
     * @param config rank.yml 配置
     * @return 书本页面 Component
     */
    private Component buildRankPage(YamlConfiguration config) {
        Component page = Component.empty();

        // 渲染 header
        List<?> header = config.getList("header");
        if (header != null) {
            for (Object entry : header) {
                if (entry instanceof Map<?, ?> lineMap) {
                    Component line = buildLine(lineMap, null);
                    if (line != null) {
                        page = page.append(line);
                        page = page.append(Component.text("\n"));
                    }
                } else if (entry instanceof String text) {
                    text = replacePlaceholders(text, null);
                    page = page.append(ColorUtil.toComponent(text));
                    page = page.append(Component.text("\n"));
                }
            }
        }

        // 渲染 rank-template (为每个 Rank 重复)
        List<?> template = config.getList("rank-template");
        if (template != null) {
            for (String rank : nickManager.getAvailableRanks()) {
                String prefix = nickManager.getRankPrefix(rank);
                String display = rank.toUpperCase();
                // [新增] 读取 rank: 字段作为 GUI 显示名称, 未设置时回退到 display
                ConfigurationSection rankSection = nickManager.getRankSection(rank);
                String rankDisplay = rankSection != null ? rankSection.getString("rank", display) : display;
                Map<String, String> placeholders = Map.of(
                        "prefix", prefix,
                        "display", display,
                        "rank", rankDisplay,
                        "key", rank
                );
                for (Object entry : template) {
                    if (entry instanceof Map<?, ?> lineMap) {
                        Component line = buildLine(lineMap, placeholders);
                        if (line != null) {
                            page = page.append(line);
                            page = page.append(Component.text("\n"));
                        }
                    }
                }
            }
        }

        return page;
    }

    /**
     * 从配置创建书本并打开.
     */
    private void openBookFromConfig(Player player, YamlConfiguration config, Component page) {
        String title = config.getString("title", "HyperNick");
        String author = config.getString("author", "HyperNick");
        Book book = Book.book(Component.text(title), Component.text(author), List.of(page));
        player.openBook(book);
    }

    // ==================== ProtocolLib 告示牌监听 ====================

    /**
     * 注册 ProtocolLib 数据包监听器, 拦截客户端发送的告示牌更新包.
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
                event.setCancelled(true);

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
     * 打开主菜单 (说明页), 从 GUI/main.yml 加载.
     */
    public void openMainMenu(Player player) {
        YamlConfiguration config = loadGuiConfig("main.yml");
        if (config == null) {
            return;
        }
        Component page = buildPage(config, null);
        openBookFromConfig(player, config, page);
    }

    /**
     * 打开 Rank 选择页, 从 GUI/rank.yml 加载.
     */
    public void openRankMenu(Player player) {
        YamlConfiguration config = loadGuiConfig("rank.yml");
        if (config == null) {
            return;
        }
        Component page = buildRankPage(config);
        openBookFromConfig(player, config, page);
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
     * 打开 Skin 选择页, 从 GUI/skin.yml 加载.
     */
    public void openSkinMenu(Player player) {
        YamlConfiguration config = loadGuiConfig("skin.yml");
        if (config == null) {
            return;
        }
        Component page = buildPage(config, null);
        openBookFromConfig(player, config, page);
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
     * 打开 Name 选择页, 从 GUI/name.yml 加载.
     */
    public void openNameMenu(Player player) {
        YamlConfiguration config = loadGuiConfig("name.yml");
        if (config == null) {
            return;
        }
        // 构建占位符 (含条件状态)
        String lastNick = nickManager.getLastNick(player.getUniqueId());
        boolean hasLastNick = lastNick != null && !lastNick.isEmpty();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("last_nick", hasLastNick ? lastNick : "");
        placeholders.put("__condition_has_last_nick", hasLastNick ? "true" : "false");

        Component page = buildPage(config, placeholders);
        openBookFromConfig(player, config, page);
    }

    /**
     * 打开错误提示页 (从 GUI/error.yml 加载).
     *
     * @param player 玩家
     * @param nick   被拒绝的昵称
     * @param reason 拒绝原因 (含颜色代码)
     */
    public void openErrorPage(Player player, String nick, String reason) {
        YamlConfiguration config = loadGuiConfig("error.yml");
        if (config == null) {
            return;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("nick", nick != null ? nick : "");
        placeholders.put("reason", reason != null ? reason : "");
        Component page = buildPage(config, placeholders);
        openBookFromConfig(player, config, page);
    }

    /**
     * 打开随机昵称结果页 (从 GUI/random-result.yml 加载).
     *
     * @param player 玩家
     * @param nick   随机生成的昵称
     */
    public void openRandomResultPage(Player player, String nick) {
        YamlConfiguration config = loadGuiConfig("random-result.yml");
        if (config == null) {
            return;
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("nick", nick != null ? nick : "");
        Component page = buildPage(config, placeholders);
        openBookFromConfig(player, config, page);
    }

    // ==================== Name 操作 ====================

    /**
     * 生成随机昵称并显示结果页 (GUI 流程).
     * <p>
     * 不直接应用昵称, 而是生成后保存到 pendingRandoms, 显示 Book GUI 让玩家选择:
     * 使用该昵称 / 重新随机 / 返回.
     */
    public void applyRandomName(Player player) {
        GuiState state = guiStates.get(player.getUniqueId());
        if (state == null) {
            state = new GuiState(nickManager.pickRandomRank(), NickData.SkinMode.REAL);
        }
        String nick = nickManager.generateRandomNick(player);
        if (nick == null) {
            // 生成失败 (如名称全被占用), 显示错误页
            openErrorPage(player, "", "&c无法生成可用的随机昵称, 请稍后重试.");
            return;
        }
        // 保存待确认的随机昵称
        pendingRandoms.put(player.getUniqueId(), new PendingRandom(nick, state));
        // 显示结果页
        openRandomResultPage(player, nick);
    }

    /**
     * 确认使用随机生成的昵称 (由 Book GUI 点击触发).
     */
    public void confirmRandomName(Player player) {
        PendingRandom pending = pendingRandoms.remove(player.getUniqueId());
        guiStates.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        nickManager.applyRandomNickWithSkin(player, pending.nick, pending.state.rankKey, pending.state.skinMode);
    }

    /**
     * 确认使用指定的随机昵称 (由 Book GUI 点击带昵称参数的 confirm 触发).
     *
     * @param player 玩家
     * @param nick   要确认使用的昵称
     */
    public void confirmRandomNameWithNick(Player player, String nick) {
        PendingRandom pending = pendingRandoms.remove(player.getUniqueId());
        guiStates.remove(player.getUniqueId());
        GuiState state = pending != null ? pending.state : null;
        if (state == null) {
            state = new GuiState(nickManager.pickRandomRank(), NickData.SkinMode.REAL);
        }
        nickManager.applyRandomNickWithSkin(player, nick, state.rankKey, state.skinMode);
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
     */
    public void openSignInput(Player player) {
        GuiState state = guiStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            plugin.msg(player, "protocollib-missing", Map.of());
            return;
        }

        Location loc = player.getLocation();
        Block block = loc.getBlock();
        BlockData originalData = block.getBlockData();

        player.sendBlockChange(loc, Material.OAK_SIGN.createBlockData());

        try {
            player.sendSignChange(loc, new String[]{"", "", "", ""});
        } catch (Throwable ignored) {
        }

        try {
            PacketContainer openSign = new PacketContainer(PacketType.Play.Server.OPEN_SIGN_EDITOR);
            openSign.getBlockPositionModifier().write(0,
                    new BlockPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            try {
                openSign.getBooleans().write(0, true);
            } catch (Throwable ignored) {
            }
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, openSign);
        } catch (Throwable t) {
            plugin.getLogger().warning("无法打开告示牌GUI: " + t.getMessage());
            player.sendBlockChange(loc, originalData);
            return;
        }

        signSessions.put(player.getUniqueId(), new SignSession(state, loc, originalData));
    }

    /**
     * 处理告示牌输入结果.
     */
    public void handleSignResult(Player player, String name) {
        GuiState state = guiStates.get(player.getUniqueId());
        if (state == null || name == null || name.isBlank()) {
            return;
        }
        // 验证昵称, 失败时显示错误页而非直接应用
        NickManager.ValidationResult result = nickManager.validateNick(name, player);
        if (result != NickManager.ValidationResult.OK) {
            String reason = getValidationErrorText(result);
            openErrorPage(player, name, reason);
            return;
        }
        guiStates.remove(player.getUniqueId());
        nickManager.nickPlayerWithSkin(player, name, state.rankKey, state.skinMode);
    }

    /**
     * 获取验证错误的中文描述 (含颜色代码).
     */
    private String getValidationErrorText(NickManager.ValidationResult result) {
        switch (result) {
            case TOO_SHORT:
                int min = plugin.getConfig().getInt("nick-settings.min-length", 3);
                return "&c长度不足, 最少 &f" + min + "&c 个字符";
            case TOO_LONG:
                int max = plugin.getConfig().getInt("nick-settings.max-length", 16);
                return "&c长度超出, 最多 &f" + max + "&c 个字符";
            case INVALID_CHARS:
                return "&c包含非法字符";
            case BLOCKED:
                return "&c该昵称已被禁用";
            case TAKEN:
                return "&c该昵称已被其他玩家使用";
            default:
                return "&c未知错误";
        }
    }

    // ==================== 会话管理 ====================

    public boolean hasSignSession(UUID uuid) {
        return signSessions.containsKey(uuid);
    }

    private void restoreBlock(Player player, SignSession session) {
        try {
            player.sendBlockChange(session.location, session.originalBlockData);
        } catch (Throwable ignored) {
        }
    }

    public void cleanup(UUID uuid) {
        SignSession session = signSessions.remove(uuid);
        if (session != null) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                restoreBlock(player, session);
            }
        }
        guiStates.remove(uuid);
        pendingRandoms.remove(uuid);
    }

    // ==================== 事件处理 ====================

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer().getUniqueId());
    }
}
