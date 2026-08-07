package com.hypernick.manager;

import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.data.NickStorage;
import com.hypernick.packet.PlayerInfoPacketListener;
import com.hypernick.scoreboard.ScoreboardManager;
import com.hypernick.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 匿名核心管理器.
 * <p>
 * 协调数据持久化、LuckPerms 前缀、计分板名牌、数据包刷新与名称校验.
 * <p>
 * 核心设计: 玩家真实 UUID 始终不变, 服务端所有数据 (背包/权限/经济) 完全继承.
 * 在数据包层面使用 fakeUuid (version 5 格式) 替换 GameProfile 的 UUID 与名称,
 * 让客户端认为这是一个全新的玩家身份.
 */
public class NickManager {

    private final HyperNick plugin;
    private final NickStorage storage;
    private final NameGenerator nameGenerator;
    private final PrefixManager prefixManager;
    private final ScoreboardManager scoreboardManager;

    /** 每日修改次数: [0]=epochDay, [1]=已用次数 */
    private final Map<UUID, long[]> dailyUsage = new ConcurrentHashMap<>();

    public NickManager(HyperNick plugin, NickStorage storage, NameGenerator nameGenerator,
                       PrefixManager prefixManager, ScoreboardManager scoreboardManager) {
        this.plugin = plugin;
        this.storage = storage;
        this.nameGenerator = nameGenerator;
        this.prefixManager = prefixManager;
        this.scoreboardManager = scoreboardManager;
    }

    // ==================== Fake UUID 生成 ====================

    /**
     * 生成 HyperNick 专属的伪装 UUID.
     * <p>
     * 使用 version 5 格式 (SHA-1 name-based 的版本标识), 输入为 "HyperNick|真实UUID|昵称".
     * <ul>
     *   <li>v4 = 正版在线模式 (随机) — 不会冲突</li>
     *   <li>v3 = 离线盗版模式 (MD5("OfflinePlayer:"+name)) — 不会冲突</li>
     *   <li>v5 = HyperNick 专属伪装 UUID — 全新格式, 可视觉辨识</li>
     * </ul>
     * 同一玩家 + 同一昵称 → 同一 fakeUuid (确定性, 可持久化).
     * <p>
     * UUID 布局: MSB 的 bits 12-15 是版本 nibble (byte 6 的高 4 位).
     * {@link UUID#nameUUIDFromBytes} 生成 v3, 这里将版本位改为 5.
     */
    public static UUID generateFakeUuid(UUID realUuid, String nickName) {
        String input = "HyperNick|" + realUuid.toString() + "|" + nickName;
        UUID base = UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8));
        long msb = base.getMostSignificantBits();
        // 清除版本 nibble (bits 12-15) 并设为 5
        // UUID MSB 布局: bits 63-32=time_low, bits 31-16=time_mid,
        //               bits 15-12=version, bits 11-0=time_hi
        // 0xFFFFFFFFFFFF0FFF = 全 1, 仅 bits 12-15 为 0 (清除版本位)
        // 0x5000 = 0101 << 12, 即版本 5
        msb = (msb & 0xFFFFFFFFFFFF0FFFL) | 0x5000L;
        return new UUID(msb, base.getLeastSignificantBits());
    }

    // ==================== 查询 ====================

    public NickData getData(UUID uuid) {
        return storage.get(uuid);
    }

    public boolean isNicked(UUID uuid) {
        NickData data = storage.get(uuid);
        return data != null && data.getNickName() != null;
    }

    /** 返回玩家对外显示名 (已匿名则返回昵称, 否则真实名) */
    public String getDisplayName(Player player) {
        NickData data = storage.get(player.getUniqueId());
        if (data != null && data.getNickName() != null) {
            return data.getNickName();
        }
        return player.getName();
    }

    /**
     * 获取玩家的有效前缀 (统一入口, 适用于所有场景).
     * <p>
     * 无论玩家是否匿名, 始终返回 HyperNick 配置的 Rank 前缀 (含 & 颜色代码):
     * <ul>
     *   <li>已匿名玩家: 使用 Nick Rank (NickData 中存储的 rankKey)</li>
     *   <li>未匿名玩家: 使用 LuckPerms 主组映射的 Rank (需开启 enable-group-prefix)</li>
     * </ul>
     * 完全替代 LuckPerms 原生前缀, 保证所有场景前缀一致.
     *
     * @param player 目标玩家
     * @return 带 & 颜色代码的 Rank 前缀; 无前缀时返回空字符串
     */
    public String getEffectivePrefix(Player player) {
        NickData data = storage.get(player.getUniqueId());
        if (data != null && data.getNickName() != null) {
            return getRankPrefix(data.getRankKey());
        }
        if (plugin.getRanksConfig().getBoolean("enable-group-prefix", true)) {
            return getRankPrefix(getGroupRankKey(player));
        }
        return "";
    }

    /**
     * 构建带 Rank 前缀和颜色继承的玩家名称组件.
     * <p>
     * 用于系统消息 (进退服/死亡/进度/tp/kill 等) 中的玩家名称显示.
     * 名称颜色自动继承前缀中最后一个颜色代码, 与聊天格式一致.
     * <p>
     * 已匿名玩家: 使用 Nick Rank 前缀 + 昵称.
     * 未匿名玩家: 使用 LuckPerms 组别映射的 Rank 前缀 + 真实名 (需开启 enable-group-prefix).
     * <p>
     * 例如: Rank prefix 为 {@code &b[MVP] }, 则返回组件为
     * {@code §b[MVP] §bSwiftFox} (前缀青色 + 名称继承青色).
     *
     * @param uuid 玩家真实 UUID
     * @return 带前缀和颜色的名称组件; 无法构建时返回 null
     */
    public Component buildPrefixedNameComponent(UUID uuid) {
        NickData data = storage.get(uuid);
        if (data != null && data.getNickName() != null) {
            // 已匿名: 使用 Nick Rank 前缀 + 昵称
            String rankPrefix = getRankPrefix(data.getRankKey());
            String prefixSection = ColorUtil.color(rankPrefix);

            Component nameComponent = Component.text(data.getNickName());
            net.kyori.adventure.text.format.TextColor color = ColorUtil.getLastColor(prefixSection);
            if (color != null) {
                nameComponent = nameComponent.color(color);
            }

            return Component.empty()
                    .append(ColorUtil.sectionToComponent(prefixSection))
                    .append(nameComponent);
        }

        // 未匿名: 使用 LuckPerms 组别映射的 Rank 前缀 + 真实名
        if (!plugin.getRanksConfig().getBoolean("enable-group-prefix", true)) {
            return null;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return null;
        }
        String rankKey = getGroupRankKey(player);
        String rankPrefix = getRankPrefix(rankKey);
        String prefixSection = ColorUtil.color(rankPrefix);

        Component nameComponent = Component.text(player.getName());
        net.kyori.adventure.text.format.TextColor color = ColorUtil.getLastColor(prefixSection);
        if (color != null) {
            nameComponent = nameComponent.color(color);
        }

        return Component.empty()
                .append(ColorUtil.sectionToComponent(prefixSection))
                .append(nameComponent);
    }

    public String getRealName(UUID uuid) {
        NickData data = storage.get(uuid);
        if (data != null && data.getOriginalName() != null) {
            return data.getOriginalName();
        }
        Player player = Bukkit.getPlayer(uuid);
        return player != null ? player.getName() : "Unknown";
    }

    /** 获取玩家的伪装 UUID (若已匿名) */
    public UUID getFakeUuid(UUID realUuid) {
        NickData data = storage.get(realUuid);
        return data != null ? data.getFakeUuid() : null;
    }

    /** 根据 fakeUuid 反查 NickData (供数据包监听器使用) */
    public NickData getDataByFakeUuid(UUID fakeUuid) {
        UUID realUuid = storage.getRealUuidByFakeUuid(fakeUuid);
        return realUuid != null ? storage.get(realUuid) : null;
    }

    /** 获取所有已匿名的在线玩家 UUID (供系统消息监听器使用) */
    public Set<UUID> getNickedPlayers() {
        Set<UUID> result = new HashSet<>();
        for (NickData data : storage.all()) {
            if (data.getNickName() != null) {
                result.add(data.getUuid());
            }
        }
        return result;
    }

    /**
     * 根据显示名 (昵称) 反查真实玩家.
     * 用于其他插件/命令通过昵称查找玩家的场景.
     */
    public Player getPlayerByDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            NickData data = storage.get(player.getUniqueId());
            if (data != null && data.getNickName() != null
                    && data.getNickName().equalsIgnoreCase(displayName)) {
                return player;
            }
            if ((data == null || data.getNickName() == null)
                    && player.getName().equalsIgnoreCase(displayName)) {
                return player;
            }
        }
        return null;
    }

    // ==================== Rank 配置 ====================

    public ConfigurationSection getRankSection(String rankKey) {
        ConfigurationSection ranks = plugin.getRanksConfig().getConfigurationSection("ranks");
        return ranks != null ? ranks.getConfigurationSection(rankKey) : null;
    }

    public String getRankPrefix(String rankKey) {
        ConfigurationSection section = getRankSection(rankKey);
        if (section == null) {
            return "";
        }
        return section.getString("prefix", "");
    }

    public String getRankColor(String rankKey) {
        ConfigurationSection section = getRankSection(rankKey);
        if (section == null) {
            return "WHITE";
        }
        return section.getString("color", "WHITE");
    }

    public int getRankPriority(String rankKey) {
        ConfigurationSection section = getRankSection(rankKey);
        if (section == null) {
            return 0;
        }
        return section.getInt("priority", 0);
    }

    public Set<String> getAvailableRanks() {
        ConfigurationSection ranks = plugin.getRanksConfig().getConfigurationSection("ranks");
        return ranks != null ? ranks.getKeys(false) : Set.of();
    }

    /**
     * 根据玩家的 LuckPerms 主组匹配 HyperNick Rank 键名.
     * <p>
     * 读取 config.yml 的 group-mapping 映射表, 将 LuckPerms 组名转为 Rank 键名.
     * 未配置的组回退到 "default".
     *
     * @param player 目标玩家
     * @return 匹配的 Rank 键名 (如 "mvp_plus"); group-prefix 未启用或无匹配时返回 "default"
     */
    public String getGroupRankKey(Player player) {
        if (!plugin.getRanksConfig().getBoolean("enable-group-prefix", true)) {
            return "default";
        }
        String primaryGroup = prefixManager.getPrimaryGroup(player);
        ConfigurationSection mapping = plugin.getRanksConfig().getConfigurationSection("group-mapping");
        if (mapping != null) {
            String rankKey = mapping.getString(primaryGroup);
            if (rankKey != null && getRankSection(rankKey) != null) {
                return rankKey;
            }
        }
        return "default";
    }

    /**
     * 刷新未匿名玩家的组别显示层 (权限组变更时调用).
     * <p>
     * 先清除旧的瞬态前缀和计分板队伍, 再重新套用基于新主组的 Rank 前缀.
     * 确保旧前缀不会残留, 避免 [VIP] [MVP] 双前缀问题.
     *
     * @param player 目标玩家
     */
    public void refreshGroupDisplay(Player player) {
        if (isNicked(player.getUniqueId())) {
            return; // 已匿名玩家不处理
        }
        // 1. 清除旧瞬态前缀 (防止旧组前缀残留)
        prefixManager.clearPrefix(player);
        // 2. 移除旧计分板队伍 (防止旧前缀残留)
        scoreboardManager.removeTeam(player);
        // 3. 重新套用基于新主组的显示层
        applyGroupDisplay(player);
    }

    /**
     * 刷新所有在线玩家的显示层 (配置重载后调用).
     * <p>
     * 遍历所有在线玩家, 根据其匿名状态重新套用显示层:
     * <ul>
     *   <li>已匿名玩家: 重新套用匿名显示层 (Rank 前缀 + 昵称 + 计分板队伍)</li>
     *   <li>未匿名玩家: 重新套用组别显示层 (LuckPerms 组别对应 Rank 前缀)</li>
     * </ul>
     * 用于 /nick reload 后立即应用新配置, 无需玩家重新登录.
     */
    public void refreshAllDisplays() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            NickData data = storage.get(player.getUniqueId());
            if (data != null && data.getNickName() != null) {
                // 已匿名: 重新套用匿名显示层
                applyDisplay(player, data);
            } else {
                // 未匿名: 清除旧前缀后重新套用组别显示层
                prefixManager.clearPrefix(player);
                scoreboardManager.removeTeam(player);
                applyGroupDisplay(player);
            }
        }
    }

    /**
     * 为未匿名玩家套用基于 LuckPerms 组别的显示层.
     * <p>
     * 完全套用 HyperNick 的 Rank 前缀, 包括:
     * <ul>
     *   <li>LuckPerms 瞬态前缀 (覆盖原生 LuckPerms 前缀, 用于聊天)</li>
     *   <li>Tab 列表名 = Rank 前缀 + 真实名</li>
     *   <li>计分板名牌 (条目=真实名, 前缀+颜色)</li>
     * </ul>
     * 与 {@link #applyDisplay} 的区别仅在于使用真实名而非昵称.
     *
     * @param player 未匿名玩家
     */
    public void applyGroupDisplay(Player player) {
        if (!plugin.getRanksConfig().getBoolean("enable-group-prefix", true)) {
            return;
        }
        String rankKey = getGroupRankKey(player);
        String rankPrefix = getRankPrefix(rankKey);
        String rankColor = getRankColor(rankKey);
        int priority = getRankPriority(rankKey);

        // 1. LuckPerms 瞬态前缀 (覆盖原生 LuckPerms 前缀, 聊天使用 HyperNick Rank 前缀)
        prefixManager.setTransientPrefix(player, ColorUtil.color(rankPrefix), priority);

        // 2. 聊天显示名 (颜色从前缀最后一个颜色代码继承, 支持 HEX)
        //    与 applyDisplay() 和 buildPrefixedNameComponent() 保持一致
        String prefixSection = ColorUtil.color(rankPrefix);
        net.kyori.adventure.text.format.TextColor nameColor = ColorUtil.getLastColor(prefixSection);
        if (nameColor == null) {
            nameColor = ColorUtil.parseTextColor(rankColor);
        }
        net.kyori.adventure.text.Component displayName = nameColor != null
                ? net.kyori.adventure.text.Component.text(player.getName(), nameColor)
                : net.kyori.adventure.text.Component.text(player.getName());
        player.displayName(displayName);

        // 3. Tab 列表名 (前缀 + 真实名, 前缀颜色自然延续)
        Component listName = ColorUtil.toComponent(rankPrefix + player.getName());
        player.playerListName(listName);

        // 4. 计分板名牌 (条目=真实名, 支持 HEX 颜色)
        if (plugin.getConfig().getBoolean("scoreboard-nametag", true)) {
            scoreboardManager.applyTeam(player, player.getName(), rankPrefix, rankColor);
        }
    }

    public String pickRandomRank() {
        List<String> pool = plugin.getRanksConfig().getStringList("random-rank-pool");
        if (pool.isEmpty()) {
            return "default";
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    // ==================== 名称校验 ====================

    public enum ValidationResult {
        OK, TOO_SHORT, TOO_LONG, INVALID_CHARS, BLOCKED, TAKEN
    }

    public ValidationResult validateNick(String nick, Player player) {
        int min = plugin.getConfig().getInt("nick-settings.min-length", 3);
        int max = plugin.getConfig().getInt("nick-settings.max-length", 16);
        if (nick.length() < min) {
            return ValidationResult.TOO_SHORT;
        }
        if (nick.length() > max) {
            return ValidationResult.TOO_LONG;
        }
        boolean allowNumbers = plugin.getConfig().getBoolean("nick-settings.allow-numbers", true);
        boolean allowUnderscore = plugin.getConfig().getBoolean("nick-settings.allow-underscore", true);
        String regex = plugin.getConfig().getString("nick-settings.name-regex", "");
        if (regex != null && !regex.isBlank()) {
            if (!nick.matches(regex)) {
                return ValidationResult.INVALID_CHARS;
            }
        } else {
            for (char c : nick.toCharArray()) {
                boolean ok = Character.isLetter(c)
                        || (allowNumbers && Character.isDigit(c))
                        || (allowUnderscore && c == '_');
                if (!ok) {
                    return ValidationResult.INVALID_CHARS;
                }
            }
        }
        List<String> blocked = plugin.getConfig().getStringList("nick-settings.blocked-names");
        for (String b : blocked) {
            if (b != null && b.equalsIgnoreCase(nick)) {
                return ValidationResult.BLOCKED;
            }
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(nick)) {
                return ValidationResult.TAKEN;
            }
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(nick);
        if (cached != null && cached.hasPlayedBefore()) {
            return ValidationResult.TAKEN;
        }
        if (storage.isNameTaken(nick, player.getUniqueId())) {
            return ValidationResult.TAKEN;
        }
        return ValidationResult.OK;
    }

    // ==================== 每日限额 ====================

    private boolean consumeDaily(Player player) {
        int limit = plugin.getConfig().getInt("nick-settings.daily-limit", -1);
        if (limit < 0) {
            return true;
        }
        long today = LocalDate.now().toEpochDay();
        long[] usage = dailyUsage.compute(player.getUniqueId(), (uuid, old) -> {
            if (old == null || old[0] != today) {
                return new long[]{today, 0};
            }
            return old;
        });
        if (usage[1] >= limit) {
            return false;
        }
        usage[1]++;
        return true;
    }

    public int getDailyLimit() {
        return plugin.getConfig().getInt("nick-settings.daily-limit", -1);
    }

    /**
     * 获取玩家今日已使用的修改次数.
     * <p>
     * 用于 PlaceholderAPI 变量 {@code %hypernick_daily_used%} 查询.
     *
     * @param player 目标玩家
     * @return 今日已用次数 (无限制时返回 0)
     */
    public int getDailyUsed(Player player) {
        int limit = getDailyLimit();
        if (limit < 0) {
            return 0;
        }
        long today = LocalDate.now().toEpochDay();
        long[] usage = dailyUsage.get(player.getUniqueId());
        if (usage == null || usage[0] != today) {
            return 0;
        }
        return (int) usage[1];
    }

    // ==================== 核心操作 ====================

    public boolean nickPlayer(Player player, String nick, String rankKey) {
        ValidationResult result = validateNick(nick, player);
        if (result != ValidationResult.OK) {
            sendValidationError(player, result);
            return false;
        }
        if (!consumeDaily(player)) {
            plugin.msg(player, "daily-limit", Map.of("limit", String.valueOf(getDailyLimit())));
            return false;
        }
        if (getRankSection(rankKey) == null) {
            rankKey = "default";
        }
        applyNick(player, nick, rankKey);
        plugin.msg(player, "nick-set", Map.of("nick", nick, "rank", rankKey));
        return true;
    }

    public boolean nickRandom(Player player) {
        if (!consumeDaily(player)) {
            plugin.msg(player, "daily-limit", Map.of("limit", String.valueOf(getDailyLimit())));
            return false;
        }
        String nick;
        int attempts = 0;
        do {
            nick = nameGenerator.generate();
            attempts++;
        } while (validateNick(nick, player) != ValidationResult.OK && attempts < 20);
        if (validateNick(nick, player) != ValidationResult.OK) {
            plugin.msg(player, "name-taken", Map.of());
            return false;
        }
        String rankKey = pickRandomRank();
        applyNick(player, nick, rankKey);
        plugin.msg(player, "random-generated", Map.of("nick", nick));
        return true;
    }

    public boolean setRank(Player player, String rankKey) {
        NickData data = storage.get(player.getUniqueId());
        if (data == null || data.getNickName() == null) {
            plugin.msg(player, "not-nicked", Map.of());
            return false;
        }
        if (getRankSection(rankKey) == null) {
            plugin.msg(player, "invalid-rank", Map.of("ranks", String.join(", ", getAvailableRanks())));
            return false;
        }
        data.setRankKey(rankKey);
        storage.put(data);
        storage.save();
        applyDisplay(player, data);
        refresh(player);
        plugin.msg(player, "rank-set", Map.of("rank", rankKey));
        return true;
    }

    public void reset(Player player) {
        NickData data = storage.get(player.getUniqueId());
        if (data == null) {
            plugin.msg(player, "not-nicked", Map.of());
            return;
        }
        // 1. 在清除数据前, 先向所有玩家 (含自身) 发送 PLAYER_INFO_REMOVE 移除旧 fakeUuid 条目
        //    使用 withListeners=false 绕过监听器, 确保 UUID 不被修改
        //    (清除数据后, 监听器将无法找到 fakeUuid 来替换, 导致客户端残留幽灵条目)
        UUID oldFakeUuid = data.getFakeUuid();
        UUID oldUuid = oldFakeUuid != null ? oldFakeUuid : player.getUniqueId();
        PlayerInfoPacketListener.sendRemovePacketToAll(player, oldUuid);
        // 2. 清除服务端显示层 (前缀/显示名/计分板)
        removeDisplay(player);
        // 3. 清除持久化数据
        storage.remove(player.getUniqueId());
        storage.save();
        // 4. 恢复基于 LuckPerms 组别的前缀显示 (未匿名状态)
        applyGroupDisplay(player);
        // 5. 刷新 (hidePlayer/showPlayer + 自身Tab刷新) — 此时数据已清除, 监听器不会替换
        //    客户端收到真实 UUID + 真实名, 正确恢复原始身份
        refresh(player);
        plugin.msg(player, "nick-reset", Map.of());
    }

    public void applyOnJoin(Player player) {
        NickData data = storage.get(player.getUniqueId());
        if (data == null || data.getNickName() == null) {
            // 未匿名: 套用基于 LuckPerms 组别的前缀显示
            applyGroupDisplay(player);
            return;
        }
        // 为旧数据补生成 fakeUuid
        if (data.getFakeUuid() == null) {
            data.setFakeUuid(generateFakeUuid(player.getUniqueId(), data.getNickName()));
            storage.put(data);
            storage.save();
        }
        // 迁移旧版 v3 fakeUuid → v5 (版本位修复)
        UUID currentFake = data.getFakeUuid();
        if (currentFake != null) {
            long version = (currentFake.getMostSignificantBits() >> 12) & 0xF;
            if (version != 5) {
                data.setFakeUuid(generateFakeUuid(player.getUniqueId(), data.getNickName()));
                storage.put(data);
                storage.save();
            }
        }
        // 刷新原名为当前真实名 (防止改名)
        if (data.getOriginalName() == null || !data.getOriginalName().equals(player.getName())) {
            NickData updated = new NickData(player.getUniqueId(), player.getName(),
                    data.getNickName(), data.getRankKey(), data.getSetAt(), data.getFakeUuid());
            storage.put(updated);
            data = updated;
        }
        applyDisplay(player, data);
        Bukkit.getScheduler().runTaskLater(plugin, () -> refresh(player), 5L);
    }

    public void cleanupOnQuit(Player player) {
        scoreboardManager.removeTeam(player);
        // 清除主组跟踪缓存, 确保下次加入时重新记录但不触发刷新
        prefixManager.clearTrackedGroup(player.getUniqueId());
    }

    // ==================== 名称冲突处理 ====================

    /**
     * 处理名称冲突: 当真实玩家加入时, 检查是否有其他玩家的昵称与此玩家真实名相同.
     * <p>
     * 场景: 玩家 ABC nick 成 "DEF" (DEF 此前从未加入服务器, 验证通过),
     * 随后真实玩家 DEF 加入 → 计分板队伍 entry "DEF" 匹配到真实 DEF,
     * 导致真实 DEF 被错误应用 ABC 的 Rank 前缀, 且 reset 后客户端可能不刷新.
     * <p>
     * 解决方案:
     * <ol>
     *   <li>若 ABC 在线: 自动 reset ABC 的 nick, 并通知双方</li>
     *   <li>若 ABC 离线: 清除 ABC 的持久化匿名数据</li>
     *   <li>清理计分板中与新玩家真实名冲突的 HyperNick 队伍 entry</li>
     * </ol>
     *
     * @param joiningPlayer 新加入的真实玩家
     */
    public void handleNameConflict(Player joiningPlayer) {
        String joiningName = joiningPlayer.getName();
        if (joiningName == null) {
            return;
        }

        // 先收集所有冲突的玩家数据, 避免遍历时修改导致 ConcurrentModificationException
        java.util.List<NickData> conflicts = new java.util.ArrayList<>();
        for (NickData data : storage.all()) {
            // 跳过自己 (joiningPlayer 自己的匿名数据, 不存在自我冲突)
            if (data.getUuid().equals(joiningPlayer.getUniqueId())) {
                continue;
            }
            if (data.getNickName() != null && data.getNickName().equalsIgnoreCase(joiningName)) {
                conflicts.add(data);
            }
        }

        for (NickData data : conflicts) {
            Player nickedPlayer = Bukkit.getPlayer(data.getUuid());
            if (nickedPlayer != null && nickedPlayer.isOnline()) {
                // ABC 在线: 通知后自动 reset (reset 会清除显示层 + 持久化 + 刷新)
                plugin.msg(nickedPlayer, "name-conflict-reset", Map.of("name", joiningName));
                reset(nickedPlayer);
            } else {
                // ABC 离线: 直接清除持久化数据 (队伍已在 cleanupOnQuit 中移除)
                storage.remove(data.getUuid());
                storage.save();
            }
        }

        // 安全网: 清理计分板中可能残留的 entry (即使上述 reset 已注销队伍,
        // 也确保新玩家的真实名不在任何 hn_ 队伍中, 防止客户端缓存导致前缀残留)
        scoreboardManager.purgeEntry(joiningName);

        if (!conflicts.isEmpty()) {
            plugin.getLogger().info("检测到名称冲突: 真实玩家 " + joiningName
                    + " 加入, 已自动清除 " + conflicts.size() + " 个冲突的昵称.");
        }
    }

    // ==================== 内部实现 ====================

    private void applyNick(Player player, String nick, String rankKey) {
        // 1. 若玩家已有旧昵称, 先向所有玩家 (含自身) 移除客户端的旧 UUID 条目
        //    使用 sendRemovePacketToAll 绕过监听器, 确保旧 UUID 被正确移除
        NickData oldData = storage.get(player.getUniqueId());
        UUID oldUuid = (oldData != null && oldData.getFakeUuid() != null)
                ? oldData.getFakeUuid()
                : player.getUniqueId();
        PlayerInfoPacketListener.sendRemovePacketToAll(player, oldUuid);
        // 2. 生成新 fakeUuid 并保存
        UUID fakeUuid = generateFakeUuid(player.getUniqueId(), nick);
        NickData data = new NickData(player.getUniqueId(), player.getName(), nick, rankKey,
                System.currentTimeMillis(), fakeUuid);
        storage.put(data);
        storage.save();
        // 3. 套用服务端显示层
        applyDisplay(player, data);
        // 4. 刷新 (hidePlayer/showPlayer) — 数据包监听器会用新 fakeUuid + 昵称替换
        refresh(player);
    }

    /**
     * 套用全部显示层.
     * <p>
     * 服务端层 (真实 UUID, 数据继承):
     * <ul>
     *   <li>LuckPerms 瞬态前缀 (Rank)</li>
     *   <li>Bukkit displayName / playerListName (聊天与 Tab)</li>
     *   <li>计分板队伍 (头顶名牌前缀, 条目=昵称)</li>
     * </ul>
     * 客户端层 (fakeUuid, 全新身份):
     * <ul>
     *   <li>GameProfile UUID + name 由数据包监听器替换</li>
     * </ul>
     */
    public void applyDisplay(Player player, NickData data) {
        String rankPrefix = getRankPrefix(data.getRankKey());
        String rankColor = getRankColor(data.getRankKey());
        int priority = getRankPriority(data.getRankKey());

        // 1. LuckPerms 瞬态前缀 (含 HEX 颜色转换)
        prefixManager.setTransientPrefix(player, ColorUtil.color(rankPrefix), priority);

        // 2. 聊天显示名 (颜色从前缀最后一个颜色代码继承, 支持 HEX)
        //    与 buildPrefixedNameComponent() 和 ChatListener 保持一致
        String prefixSection = ColorUtil.color(rankPrefix);
        net.kyori.adventure.text.format.TextColor nameColor = ColorUtil.getLastColor(prefixSection);
        if (nameColor == null) {
            // 前缀无颜色代码时回退到 rank color 配置
            nameColor = ColorUtil.parseTextColor(rankColor);
        }
        net.kyori.adventure.text.Component displayName = nameColor != null
                ? net.kyori.adventure.text.Component.text(data.getNickName(), nameColor)
                : net.kyori.adventure.text.Component.text(data.getNickName());
        player.displayName(displayName);

        // 3. Tab 列表名 (前缀 + 昵称, 前缀颜色自然延续到昵称)
        Component listName = ColorUtil.toComponent(rankPrefix + data.getNickName());
        player.playerListName(listName);

        // 4. 计分板名牌 (条目=昵称, 支持 HEX 颜色)
        if (plugin.getConfig().getBoolean("scoreboard-nametag", true)) {
            scoreboardManager.applyTeam(player, data.getNickName(), rankPrefix, rankColor);
        }
    }

    public void removeDisplay(Player player) {
        prefixManager.clearPrefix(player);
        player.displayName(Component.text(player.getName()));
        player.playerListName(Component.text(player.getName()));
        scoreboardManager.removeTeam(player);
    }

    /**
     * 刷新玩家信息包: 模拟"退出重进"效果.
     * <p>
     * 分两阶段执行, 确保客户端正确刷新 Tab 列表和头顶名牌:
     * <ol>
     *   <li>阶段1 (立即): 对其他玩家执行 hidePlayer (发送 PLAYER_INFO_REMOVE + 实体销毁)</li>
     *   <li>阶段2 (2刻后): 对其他玩家执行 showPlayer (发送 PLAYER_INFO_ADD + 实体生成),
     *       同时向玩家自身发送 PLAYER_INFO_ADD 刷新自身 Tab 条目</li>
     * </ol>
     * 延迟2刻确保移除包先于添加包到达客户端, 避免客户端因处理顺序问题残留旧条目.
     * <p>
     * 数据包监听器会拦截 PLAYER_INFO_ADD, 将真实 UUID + 真实名替换为 fakeUuid + 昵称.
     */
    @SuppressWarnings("deprecation")
    public void refresh(Player player) {
        // 阶段1: 对其他在线玩家隐藏 (触发 PLAYER_INFO_REMOVE + 实体销毁)
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            try {
                viewer.hidePlayer(plugin, player);
            } catch (Throwable ignored) {
            }
        }

        // 阶段2: 延迟2刻后重新显示 (确保移除包先到达客户端)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // 对其他在线玩家显示 (触发 PLAYER_INFO_ADD + 实体生成, 监听器替换为 fakeUuid + 昵称)
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                try {
                    viewer.showPlayer(plugin, player);
                } catch (Throwable ignored) {
                }
            }
            // 向玩家自身发送 PLAYER_INFO_ADD 刷新其自身 Tab 条目
            // (Paper 的 hidePlayer/showPlayer 跳过自身, 需手动刷新)
            PlayerInfoPacketListener.sendSelfInfoAdd(player, plugin);
        }, 2L);
    }

    private void sendValidationError(Player player, ValidationResult result) {
        int min = plugin.getConfig().getInt("nick-settings.min-length", 3);
        int max = plugin.getConfig().getInt("nick-settings.max-length", 16);
        switch (result) {
            case TOO_SHORT -> plugin.msg(player, "name-too-short", Map.of("min", String.valueOf(min)));
            case TOO_LONG -> plugin.msg(player, "name-too-long", Map.of("max", String.valueOf(max)));
            case INVALID_CHARS -> plugin.msg(player, "invalid-name", Map.of());
            case BLOCKED -> plugin.msg(player, "name-blocked", Map.of());
            case TAKEN -> plugin.msg(player, "name-taken", Map.of());
            default -> { /* OK */ }
        }
    }
}
