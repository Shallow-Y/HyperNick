package com.hypernick;

import com.hypernick.command.NickCommand;
import com.hypernick.command.UnnickCommand;
import com.hypernick.data.DatabaseManager;
import com.hypernick.data.NickStorage;
import com.hypernick.gui.NickGuiManager;
import com.hypernick.listener.ChatListener;
import com.hypernick.listener.CommandPreprocessListener;
import com.hypernick.listener.DeathAdvancementListener;
import com.hypernick.listener.PlayerConnectionListener;
import com.hypernick.listener.TabCompleteListener;
import com.hypernick.manager.NameGenerator;
import com.hypernick.manager.NickManager;
import com.hypernick.manager.PrefixManager;
import com.hypernick.packet.ChatPacketListener;
import com.hypernick.packet.PlayerInfoPacketListener;
import com.hypernick.packet.SystemMessagePacketListener;
import com.hypernick.packet.TabCompletePacketListener;
import com.hypernick.placeholder.HyperNickPlaceholder;
import com.hypernick.scoreboard.ScoreboardManager;
import com.hypernick.task.ActionBarTask;
import com.hypernick.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;

public class HyperNick extends JavaPlugin {

    private NickStorage storage;
    private DatabaseManager databaseManager;
    private NameGenerator nameGenerator;
    private PrefixManager prefixManager;
    private ScoreboardManager scoreboardManager;
    private NickManager nickManager;
    private NickGuiManager nickGuiManager;

    private int actionBarTaskId = -1;

    private FileConfiguration langConfig;
    private FileConfiguration ranksConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRanks();
        loadLang();

        // 初始化数据库
        DatabaseManager databaseManager = new DatabaseManager(this);
        try {
            databaseManager.connect();
        } catch (SQLException e) {
            getLogger().severe("数据库连接失败: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.databaseManager = databaseManager;
        this.storage = new NickStorage(databaseManager);
        storage.load();
        // 迁移旧版 data.yml 数据 (如有)
        storage.migrateFromYaml(this);

        this.nameGenerator = new NameGenerator(this);
        nameGenerator.reload();

        this.prefixManager = new PrefixManager(this);
        if (!prefixManager.hook()) {
            getLogger().warning("未检测到 LuckPerms, Rank 前缀功能不可用 (聊天将无前缀).");
        }

        this.scoreboardManager = new ScoreboardManager(this);
        scoreboardManager.cleanupOrphanedTeams();

        this.nickManager = new NickManager(this, storage, nameGenerator, prefixManager, scoreboardManager);

        prefixManager.subscribeGroupChanges((uuid, player) -> {
            nickManager.refreshGroupDisplay(player);
        });

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this, nickManager), this);
        getServer().getPluginManager().registerEvents(new TabCompleteListener(this, nickManager), this);
        getServer().getPluginManager().registerEvents(new CommandPreprocessListener(this, nickManager), this);
        getServer().getPluginManager().registerEvents(new DeathAdvancementListener(this, nickManager), this);
        if (getConfig().getBoolean("override-chat", true)) {
            getServer().getPluginManager().registerEvents(new ChatListener(this, nickManager), this);
        }

        if (getConfig().getBoolean("packet-disguise", true)) {
            if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
                PlayerInfoPacketListener.register(this, nickManager);
                TabCompletePacketListener.register(this, nickManager);
                ChatPacketListener.register(this, nickManager);
                SystemMessagePacketListener.register(this, nickManager);
                getLogger().info("已注册 ProtocolLib 数据包监听器.");
            } else {
                getLogger().warning("未检测到 ProtocolLib, 数据包伪装不可用.");
            }
        }

        this.nickGuiManager = new NickGuiManager(this, nickManager);
        getServer().getPluginManager().registerEvents(nickGuiManager, this);

        NickCommand nickCommand = new NickCommand(this, nickManager, nickGuiManager);
        if (getCommand("nick") != null) {
            getCommand("nick").setExecutor(nickCommand);
            getCommand("nick").setTabCompleter(nickCommand);
        }

        UnnickCommand unnickCommand = new UnnickCommand(this, nickManager);
        if (getCommand("unnick") != null) {
            getCommand("unnick").setExecutor(unnickCommand);
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new HyperNickPlaceholder(this).register();
            getLogger().info("已注册 PlaceholderAPI 变量扩展.");
        } else {
            getLogger().info("未检测到 PlaceholderAPI, 变量扩展不可用.");
        }

        // ActionBar 匿名提示任务 (每 5 秒)
        ActionBarTask actionBarTask = new ActionBarTask(this, nickManager);
        actionBarTaskId = Bukkit.getScheduler().runTaskTimer(this, actionBarTask, 20L, 100L).getTaskId();

        getLogger().info("HyperNick v" + getPluginMeta().getVersion() + " 已启用.");
    }

    @Override
    public void onDisable() {
        if (actionBarTaskId != -1) {
            Bukkit.getScheduler().cancelTask(actionBarTaskId);
            actionBarTaskId = -1;
        }
        if (storage != null) {
            storage.save();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("HyperNick 已禁用.");
    }

    public void reloadAll() {
        reloadConfig();
        loadRanks();
        loadLang();
        nameGenerator.reload();
        if (nickManager != null) {
            nickManager.refreshAllDisplays();
        }
        // 重启 ActionBar 任务 (语言可能已变更)
        if (actionBarTaskId != -1) {
            Bukkit.getScheduler().cancelTask(actionBarTaskId);
        }
        ActionBarTask actionBarTask = new ActionBarTask(this, nickManager);
        actionBarTaskId = Bukkit.getScheduler().runTaskTimer(this, actionBarTask, 20L, 100L).getTaskId();
    }

    private void loadLang() {
        String lang = getConfig().getString("lang", "zh_hans");
        File langFile = new File(getDataFolder(), "messages" + File.separator + lang + ".yml");
        File langDir = new File(getDataFolder(), "messages");
        if (!langDir.exists()) {
            langDir.mkdirs();
        }
        if (!langFile.exists()) {
            String resourcePath = "messages/" + lang + ".yml";
            try (Reader reader = new InputStreamReader(getResource(resourcePath), StandardCharsets.UTF_8)) {
                if (reader != null) {
                    YamlConfiguration defaultLang = YamlConfiguration.loadConfiguration(reader);
                    defaultLang.save(langFile);
                }
            } catch (Exception e) {
                getLogger().warning("无法加载语言文件 messages/" + lang + ".yml: " + e.getMessage());
            }
        }
        if (langFile.exists()) {
            langConfig = YamlConfiguration.loadConfiguration(langFile);
            getLogger().info("已加载语言文件: messages/" + lang + ".yml");
        } else {
            try (Reader reader = new InputStreamReader(getResource("messages/zh_hans.yml"), StandardCharsets.UTF_8)) {
                if (reader != null) {
                    langConfig = YamlConfiguration.loadConfiguration(reader);
                    getLogger().warning("语言文件 messages/" + lang + ".yml 不存在, 回退到 zh_hans.");
                }
            } catch (Exception e) {
                getLogger().warning("语言文件加载失败, 消息将使用键名: " + e.getMessage());
            }
        }
    }

    private void loadRanks() {
        File ranksFile = new File(getDataFolder(), "ranks.yml");
        if (!ranksFile.exists()) {
            saveResource("ranks.yml", false);
        }
        ranksConfig = YamlConfiguration.loadConfiguration(ranksFile);
        getLogger().info("已加载 Rank 配置: ranks.yml");
    }

    public FileConfiguration getRanksConfig() {
        return ranksConfig;
    }

    public FileConfiguration getLangConfig() {
        return langConfig;
    }

    public void msg(CommandSender sender, String key, Map<String, String> replacements) {
        String prefix = "";
        String message = key;
        if (langConfig != null) {
            prefix = langConfig.getString("prefix", "");
            message = langConfig.getString(key, key);
        }
        message = ColorUtil.replace(message, "prefix", prefix);
        if (replacements != null) {
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                message = ColorUtil.replace(message, entry.getKey(), entry.getValue());
            }
        }
        sender.sendMessage(ColorUtil.toComponent(message));
    }

    public NickManager getNickManager() {
        return nickManager;
    }

    public NickGuiManager getNickGuiManager() {
        return nickGuiManager;
    }

    public PrefixManager getPrefixManager() {
        return prefixManager;
    }
}
