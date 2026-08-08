package com.hypernick.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 匿名数据持久化 (SQLite / MySQL).
 * <p>
 * 内存缓存 + 数据库落盘. 真实 UUID -> NickData (含 fakeUuid).
 */
public class NickStorage {

    private final DatabaseManager db;
    private final Map<UUID, NickData> cache = new ConcurrentHashMap<>();
    /** fakeUuid -> 真实 UUID 的反查映射, 供数据包监听器快速查找 */
    private final Map<UUID, UUID> fakeToReal = new ConcurrentHashMap<>();

    public NickStorage(DatabaseManager db) {
        this.db = db;
    }

    /** 从数据库加载数据到内存缓存 */
    public void load() {
        try {
            String sql = "SELECT uuid, nick, rank, original, setAt, fakeUuid, skinMode, lastNick, lastRank FROM hypernick_data";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        String nick = rs.getString("nick");
                        String rank = rs.getString("rank");
                        String original = rs.getString("original");
                        if (original == null) original = uuid.toString();
                        long setAt = rs.getLong("setAt");
                        String fakeUuidStr = rs.getString("fakeUuid");
                        UUID fakeUuid = fakeUuidStr != null ? UUID.fromString(fakeUuidStr) : null;
                        String skinModeStr = rs.getString("skinMode");
                        if (skinModeStr == null) skinModeStr = "REAL";
                        NickData.SkinMode skinMode;
                        try {
                            skinMode = NickData.SkinMode.valueOf(skinModeStr);
                        } catch (IllegalArgumentException e) {
                            skinMode = NickData.SkinMode.REAL;
                        }
                        String lastNick = rs.getString("lastNick");
                        String lastRank = rs.getString("lastRank");

                        NickData data;
                        if (nick != null) {
                            data = new NickData(uuid, original, nick, rank, setAt, fakeUuid);
                        } else {
                            data = new NickData(uuid, original, null, null, setAt, null);
                        }
                        data.setSkinMode(skinMode);
                        data.setLastNick(lastNick);
                        data.setLastRank(lastRank);
                        cache.put(uuid, data);
                        if (fakeUuid != null) {
                            fakeToReal.put(fakeUuid, uuid);
                        }
                    } catch (IllegalArgumentException ex) {
                        // skip invalid UUID
                    }
                }
            }
        } catch (SQLException e) {
            db.getLogger().log(Level.SEVERE, "从数据库加载匿名数据失败", e);
        }
        db.getLogger().info("已加载 " + cache.size() + " 条匿名数据.");
    }

    /** 保存单条数据到数据库 (upsert) */
    private void saveToDb(NickData data) {
        try {
            String sql;
            if (db.isMySQL()) {
                sql = "INSERT INTO hypernick_data (uuid, nick, rank, original, setAt, fakeUuid, skinMode, lastNick, lastRank) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE nick=VALUES(nick), rank=VALUES(rank), original=VALUES(original), " +
                        "setAt=VALUES(setAt), fakeUuid=VALUES(fakeUuid), skinMode=VALUES(skinMode), " +
                        "lastNick=VALUES(lastNick), lastRank=VALUES(lastRank)";
            } else {
                sql = "INSERT OR REPLACE INTO hypernick_data (uuid, nick, rank, original, setAt, fakeUuid, skinMode, lastNick, lastRank) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            }
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, data.getUuid().toString());
                ps.setString(2, data.getNickName());
                ps.setString(3, data.getRankKey());
                ps.setString(4, data.getOriginalName());
                ps.setLong(5, data.getSetAt());
                ps.setString(6, data.getFakeUuid() != null ? data.getFakeUuid().toString() : null);
                ps.setString(7, data.getSkinMode().name());
                ps.setString(8, data.getLastNick());
                ps.setString(9, data.getLastRank());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            db.getLogger().log(Level.SEVERE, "保存匿名数据失败: " + data.getUuid(), e);
        }
    }

    /** 从数据库删除单条数据 */
    private void deleteFromDb(UUID uuid) {
        try {
            String sql = "DELETE FROM hypernick_data WHERE uuid = ?";
            try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            db.getLogger().log(Level.SEVERE, "删除匿名数据失败: " + uuid, e);
        }
    }

    /**
     * 将内存缓存全量写入数据库 (插件禁用时调用).
     * 由于 put/remove 已即时写入, 此方法仅做兜底.
     */
    public void save() {
        // put/remove 已即时落盘, 无需额外操作
    }

    public NickData get(UUID uuid) {
        return cache.get(uuid);
    }

    public void put(NickData data) {
        cache.put(data.getUuid(), data);
        if (data.getFakeUuid() != null) {
            fakeToReal.put(data.getFakeUuid(), data.getUuid());
        }
        saveToDb(data);
    }

    public void remove(UUID uuid) {
        NickData data = cache.remove(uuid);
        if (data != null && data.getFakeUuid() != null) {
            fakeToReal.remove(data.getFakeUuid());
        }
        deleteFromDb(uuid);
    }

    public Collection<NickData> all() {
        return cache.values();
    }

    /** 检查昵称是否已被其他玩家使用 (不区分大小写) */
    public boolean isNameTaken(String nick, UUID excludeUuid) {
        if (nick == null) {
            return false;
        }
        for (NickData data : cache.values()) {
            if (excludeUuid != null && excludeUuid.equals(data.getUuid())) {
                continue;
            }
            if (nick.equalsIgnoreCase(data.getNickName())) {
                return true;
            }
            if (data.getOriginalName() != null && nick.equalsIgnoreCase(data.getOriginalName())) {
                return true;
            }
        }
        return false;
    }

    /** 根据 fakeUuid 反查真实 UUID (供数据包监听器使用) */
    public UUID getRealUuidByFakeUuid(UUID fakeUuid) {
        return fakeToReal.get(fakeUuid);
    }

    /** 根据真实 UUID 反查 NickData */
    public NickData findByUuid(UUID uuid) {
        return cache.get(uuid);
    }

    /**
     * 从旧版 data.yml 迁移数据到数据库 (仅首次升级时执行).
     */
    public void migrateFromYaml(JavaPlugin plugin) {
        File yamlFile = new File(plugin.getDataFolder(), "data.yml");
        if (!yamlFile.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(yamlFile);
        int count = 0;
        for (String key : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                // Skip if already in cache (already in DB)
                if (cache.containsKey(uuid)) continue;

                String nick = config.getString(key + ".nick");
                String rank = config.getString(key + ".rank");
                String original = config.getString(key + ".original", key);
                long setAt = config.getLong(key + ".setAt", System.currentTimeMillis());
                String fakeUuidStr = config.getString(key + ".fakeUuid", null);
                UUID fakeUuid = fakeUuidStr != null ? UUID.fromString(fakeUuidStr) : null;
                String skinModeStr = config.getString(key + ".skinMode", "REAL");
                NickData.SkinMode skinMode;
                try {
                    skinMode = NickData.SkinMode.valueOf(skinModeStr);
                } catch (IllegalArgumentException e) {
                    skinMode = NickData.SkinMode.REAL;
                }
                String lastNick = config.getString(key + ".lastNick", null);
                String lastRank = config.getString(key + ".lastRank", null);

                NickData data;
                if (nick != null) {
                    data = new NickData(uuid, original, nick, rank, setAt, fakeUuid);
                } else {
                    data = new NickData(uuid, original, null, null, setAt, null);
                }
                data.setSkinMode(skinMode);
                data.setLastNick(lastNick);
                data.setLastRank(lastRank);
                put(data);
                count++;
            } catch (IllegalArgumentException ex) {
                // skip invalid UUID
            }
        }

        if (count > 0) {
            db.getLogger().info("已从 data.yml 迁移 " + count + " 条数据到数据库.");
        }
        // Rename to .bak to avoid re-importing
        File bakFile = new File(plugin.getDataFolder(), "data.yml.bak");
        yamlFile.renameTo(bakFile);
    }
}
