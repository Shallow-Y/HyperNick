package com.hypernick.data;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 匿名数据持久化 (data.yml).
 * <p>
 * 内存缓存 + 文件落盘. 真实 UUID -> NickData (含 fakeUuid).
 */
public class NickStorage {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, NickData> cache = new ConcurrentHashMap<>();
    /** fakeUuid -> 真实 UUID 的反查映射, 供数据包监听器快速查找 */
    private final Map<UUID, UUID> fakeToReal = new ConcurrentHashMap<>();

    public NickStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    /** 加载数据文件到内存缓存 */
    public void load() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        if (!file.exists()) {
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        for (String key : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String nick = config.getString(key + ".nick");
                String rank = config.getString(key + ".rank");
                String original = config.getString(key + ".original", key);
                long setAt = config.getLong(key + ".setAt", System.currentTimeMillis());
                String fakeUuidStr = config.getString(key + ".fakeUuid", null);
                UUID fakeUuid = fakeUuidStr != null ? UUID.fromString(fakeUuidStr) : null;
                if (nick == null) {
                    continue;
                }
                NickData data = new NickData(uuid, original, nick, rank, setAt, fakeUuid);
                cache.put(uuid, data);
                if (fakeUuid != null) {
                    fakeToReal.put(fakeUuid, uuid);
                }
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("跳过无效的 UUID 数据: " + key);
            }
        }
        plugin.getLogger().info("已加载 " + cache.size() + " 条匿名数据.");
    }

    /** 将内存缓存写入文件 */
    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, NickData> entry : cache.entrySet()) {
            NickData data = entry.getValue();
            String path = entry.getKey().toString();
            config.set(path + ".nick", data.getNickName());
            config.set(path + ".rank", data.getRankKey());
            config.set(path + ".original", data.getOriginalName());
            config.set(path + ".setAt", data.getSetAt());
            if (data.getFakeUuid() != null) {
                config.set(path + ".fakeUuid", data.getFakeUuid().toString());
            }
        }
        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("无法保存 data.yml: " + ex.getMessage());
        }
    }

    public NickData get(UUID uuid) {
        return cache.get(uuid);
    }

    public void put(NickData data) {
        cache.put(data.getUuid(), data);
        if (data.getFakeUuid() != null) {
            fakeToReal.put(data.getFakeUuid(), data.getUuid());
        }
    }

    public void remove(UUID uuid) {
        NickData data = cache.remove(uuid);
        if (data != null && data.getFakeUuid() != null) {
            fakeToReal.remove(data.getFakeUuid());
        }
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
}
