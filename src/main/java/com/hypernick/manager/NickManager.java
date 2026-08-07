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

    public String getDisplayName(Player player) {
        NickData data = storage.get(player.getUniqueId());
        if (data != null && data.getNickName() != null) {
            return data.getNickName();
        }
        return player.getName();
    }

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

    public Component buildPrefixedNameComponent(UUID uuid) {
        NickData data = storage.get(uuid);
        if (data != null && data.getNickName() != null) {
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

    public UUID getFakeUuid(UUID realUuid) {
        NickData data = storage.get(realUuid);
        return data != null ? data.getFakeUuid() : null;
    }

    public NickData getDataByFakeUuid(UUID fakeUuid) {
        UUID realUuid = storage.getRealUuidByFakeUuid(fakeUuid);
        return realUuid != null ? storage.get(realUuid) : null;
    }

    public Set<UUID> getNickedPlayers() {
        Set<UUID> result = new HashSet<>();
        for (NickData data : storage.all()) {
            if (data.getNickName() != null) {
                result.add(data.getUuid());
            }
        }
        return result;
    }

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

    public void refreshGroupDisplay(Player player) {
        if (isNicked(player.getUniqueId())) {
            return;
        }
        prefixManager.clearPrefix(player);
        scoreboardManager.removeTeam(player);
        applyGroupDisplay(player);
    }

    public void refreshAllDisplays() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            NickData data = storage.get(player.getUniqueId());
            if (data != null && data.getNickName() != null) {
                applyDisplay(player, data);
            } else {
                prefixManager.clearPrefix(player);
                scoreboardManager.removeTeam(player);
                applyGroupDisplay(player);
            }
        }
    }

    public void applyGroupDisplay(Player player) {
        if (!plugin.getRanksConfig().getBoolean("enable-group-prefix", true)) {
            return;
        }
        String rankKey = getGroupRankKey(player);
        String rankPrefix = getRankPrefix(rankKey);
        String rankColor = getRankColor(rankKey);
        int priority = getRankPriority(rankKey);

        prefixManager.setTransientPrefix(player, ColorUtil.color(rankPrefix), priority);

        String prefixSection = ColorUtil.color(rankPrefix);
        net.kyori.adventure.text.format.TextColor nameColor = ColorUtil.getLastColor(prefixSection);
        if (nameColor == null) {
            nameColor = ColorUtil.parseTextColor(rankColor);
        }
        net.kyori.adventure.text.Component displayName = nameColor != null
                ? net.kyori.adventure.text.Component.text(player.getName(), nameColor)
                : net.kyori.adventure.text.Component.text(player.getName());
        player.displayName(displayName);

        Component listName = ColorUtil.toComponent(rankPrefix + player.getName());
        player.playerListName(listName);

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

    /**
     * 使用自定义昵称 + 指定 Rank + 指定皮肤模式进行匿名 (GUI 调用).
     */
    public boolean nickPlayerWithSkin(Player player, String nick, String rankKey, NickData.SkinMode skinMode) {
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
        applyNick(player, nick, rankKey, skinMode);
        plugin.msg(player, "nick-set", Map.of("nick", nick, "rank", rankKey));
        return true;
    }

    /**
     * 随机昵称 + 指定 Rank + 指定皮肤模式 (GUI 调用).
     */
    public boolean nickRandomWithSkin(Player player, String rankKey, NickData.SkinMode skinMode) {
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
        if (getRankSection(rankKey) == null) {
            rankKey = pickRandomRank();
        }
        applyNick(player, nick, rankKey, skinMode);
        plugin.msg(player, "random-generated", Map.of("nick", nick));
        return true;
    }

    /**
     * 重新使用上次昵称 (/nick reuse).
     *
     * @param player  目标玩家
     * @param rankKey 指定 Rank (null 则使用上次的 Rank)
     * @param skinMode 指定皮肤模式 (null 则使用上次的模式)
     */
    public boolean nickReuse(Player player, String rankKey, NickData.SkinMode skinMode) {
        NickData data = storage.get(player.getUniqueId());
        String lastNick = data != null ? data.getLastNick() : null;
        if (lastNick == null || lastNick.isEmpty()) {
            plugin.msg(player, "no-last-nick", Map.of());
            return false;
        }
        if (rankKey == null || getRankSection(rankKey) == null) {
            rankKey = data != null ? data.getLastRank() : null;
            if (rankKey == null || getRankSection(rankKey) == null) {
                rankKey = pickRandomRank();
            }
        }
        if (skinMode == null) {
            skinMode = data != null ? data.getSkinMode() : NickData.SkinMode.REAL;
        }
        if (!consumeDaily(player)) {
            plugin.msg(player, "daily-limit", Map.of("limit", String.valueOf(getDailyLimit())));
            return false;
        }
        ValidationResult result = validateNick(lastNick, player);
        if (result != ValidationResult.OK) {
            sendValidationError(player, result);
            return false;
        }
        applyNick(player, lastNick, rankKey, skinMode);
        plugin.msg(player, "nick-reused", Map.of("nick", lastNick));
        return true;
    }

    /**
     * 设置当前匿名玩家的皮肤模式 (/nick skin).
     */
    public boolean setSkinMode(Player player, NickData.SkinMode mode) {
        NickData data = storage.get(player.getUniqueId());
        if (data == null || data.getNickName() == null) {
            plugin.msg(player, "not-nicked", Map.of());
            return false;
        }
        data.setSkinMode(mode);
        storage.put(data);
        storage.save();
        applyDisplay(player, data);
        refresh(player);
        plugin.msg(player, "skin-set", Map.of("mode", mode.name()));
        return true;
    }

    /** 获取玩家上次使用的昵称 (供 GUI 显示) */
    public String getLastNick(UUID uuid) {
        NickData data = storage.get(uuid);
        return data != null ? data.getLastNick() : null;
    }

    public void reset(Player player) {
        NickData data = storage.get(player.getUniqueId());
        if (data == null) {
            plugin.msg(player, "not-nicked", Map.of());
            return;
        }
        UUID oldFakeUuid = data.getFakeUuid();
        UUID oldUuid = oldFakeUuid != null ? oldFakeUuid : player.getUniqueId();
        PlayerInfoPacketListener.sendRemovePacketToAll(player, oldUuid);
        removeDisplay(player);
        // 3. 清除持久化数据, 但保留 lastNick/lastRank 供 /nick reuse 使用
        String lastNick = data.getNickName();
        String lastRank = data.getRankKey();
        NickData.SkinMode oldSkinMode = data.getSkinMode();
        storage.remove(player.getUniqueId());
        if (lastNick != null) {
            NickData history = new NickData(player.getUniqueId(), player.getName(), null, null, 0, null);
            history.setLastNick(lastNick);
            history.setLastRank(lastRank);
            history.setSkinMode(oldSkinMode);
            storage.put(history);
        }
        storage.save();
        applyGroupDisplay(player);
        refresh(player);
        plugin.msg(player, "nick-reset", Map.of());
    }

    public void applyOnJoin(Player player) {
        NickData data = storage.get(player.getUniqueId());
        if (data == null || data.getNickName() == null) {
            applyGroupDisplay(player);
            return;
        }
        if (data.getFakeUuid() == null) {
            data.setFakeUuid(generateFakeUuid(player.getUniqueId(), data.getNickName()));
            storage.put(data);
            storage.save();
        }
        UUID currentFake = data.getFakeUuid();
        if (currentFake != null) {
            long version = (currentFake.getMostSignificantBits() >> 12) & 0xF;
            if (version != 5) {
                data.setFakeUuid(generateFakeUuid(player.getUniqueId(), data.getNickName()));
                storage.put(data);
                storage.save();
            }
        }
        if (data.getOriginalName() == null || !data.getOriginalName().equals(player.getName())) {
            NickData updated = new NickData(player.getUniqueId(), player.getName(),
                    data.getNickName(), data.getRankKey(), data.getSetAt(), data.getFakeUuid());
            updated.setSkinMode(data.getSkinMode());
            updated.setLastNick(data.getLastNick());
            updated.setLastRank(data.getLastRank());
            storage.put(updated);
            data = updated;
        }
        applyDisplay(player, data);
        Bukkit.getScheduler().runTaskLater(plugin, () -> refresh(player), 5L);
    }

    public void cleanupOnQuit(Player player) {
        scoreboardManager.removeTeam(player);
        prefixManager.clearTrackedGroup(player.getUniqueId());
    }

    // ==================== 名称冲突处理 ====================

    public void handleNameConflict(Player joiningPlayer) {
        String joiningName = joiningPlayer.getName();
        if (joiningName == null) {
            return;
        }
        java.util.List<NickData> conflicts = new java.util.ArrayList<>();
        for (NickData data : storage.all()) {
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
                plugin.msg(nickedPlayer, "name-conflict-reset", Map.of("name", joiningName));
                reset(nickedPlayer);
            } else {
                storage.remove(data.getUuid());
                storage.save();
            }
        }
        scoreboardManager.purgeEntry(joiningName);
        if (!conflicts.isEmpty()) {
            plugin.getLogger().info("检测到名称冲突: 真实玩家 " + joiningName
                    + " 加入, 已自动清除 " + conflicts.size() + " 个冲突的昵称.");
        }
    }

    // ==================== 内部实现 ====================

    private void applyNick(Player player, String nick, String rankKey, NickData.SkinMode skinMode) {
        NickData oldData = storage.get(player.getUniqueId());
        UUID oldUuid = (oldData != null && oldData.getFakeUuid() != null)
                ? oldData.getFakeUuid()
                : player.getUniqueId();
        PlayerInfoPacketListener.sendRemovePacketToAll(player, oldUuid);
        UUID fakeUuid = generateFakeUuid(player.getUniqueId(), nick);
        NickData data = new NickData(player.getUniqueId(), player.getName(), nick, rankKey,
                System.currentTimeMillis(), fakeUuid);
        data.setSkinMode(skinMode);
        data.setLastNick(nick);
        data.setLastRank(rankKey);
        storage.put(data);
        storage.save();
        applyDisplay(player, data);
        refresh(player);
    }

    private void applyNick(Player player, String nick, String rankKey) {
        applyNick(player, nick, rankKey, NickData.SkinMode.REAL);
    }

    public void applyDisplay(Player player, NickData data) {
        String rankPrefix = getRankPrefix(data.getRankKey());
        String rankColor = getRankColor(data.getRankKey());
        int priority = getRankPriority(data.getRankKey());

        prefixManager.setTransientPrefix(player, ColorUtil.color(rankPrefix), priority);

        String prefixSection = ColorUtil.color(rankPrefix);
        net.kyori.adventure.text.format.TextColor nameColor = ColorUtil.getLastColor(prefixSection);
        if (nameColor == null) {
            nameColor = ColorUtil.parseTextColor(rankColor);
        }
        net.kyori.adventure.text.Component displayName = nameColor != null
                ? net.kyori.adventure.text.Component.text(data.getNickName(), nameColor)
                : net.kyori.adventure.text.Component.text(data.getNickName());
        player.displayName(displayName);

        Component listName = ColorUtil.toComponent(rankPrefix + data.getNickName());
        player.playerListName(listName);

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

    @SuppressWarnings("deprecation")
    public void refresh(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            try {
                viewer.hidePlayer(plugin, player);
            } catch (Throwable ignored) {
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                try {
                    viewer.showPlayer(plugin, player);
                } catch (Throwable ignored) {
                }
            }
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
