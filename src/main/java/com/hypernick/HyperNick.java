package com.hypernick;

import com.hypernick.command.NickCommand;
import com.hypernick.data.NickStorage;
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
import java.util.Map;

/**
 * HyperNick 主类.
 * <p>
 * 模仿 Hypixel Nickname 系统: 通过 LuckPerms 瞬态前缀修改 Rank, 通过 ProtocolLib
 * 数据包拦截替换 GameProfile UUID + name, 实现全新身份伪装.
 * <p>
 * 核心设计: 玩家 nick 后使用 fakeUuid (version 5 格式) 模拟一个全新的玩家身份,
 * 客户端看到的是不同的 UUID + 不同的名称, 但服务端始终使用真实 UUID,
 * 背包/权限/经济等数据完全继承.
 * <p>
 * 七层拦截确保真实身份不泄露:
 * <ol>
 *   <li>数据包层 (PLAYER_INFO): 替换 GameProfile UUID + name (头顶名牌 / Tab 列表)</li>
 *   <li>数据包层 (PLAYER_INFO_REMOVE): 替换移除包中的 UUID (防止幽灵条目)</li>
 *   <li>数据包层 (TAB_COMPLETE): 拦截补全建议包, 替换真实名为昵称</li>
 *   <li>数据包层 (CHAT): 替换签名聊天的 sender UUID, 清除签名 (安全网)</li>
 *   <li>数据包层 (SYSTEM_CHAT): 拦截系统消息, 替换真实名为昵称 (死亡/进度/TP/Kill 等)</li>
 *   <li>事件层: TabCompleteEvent + AsyncChatEvent + CommandPreprocess + DeathAdvancement (四重保障)</li>
 *   <li>指令层: 拦截 /tp /kill 等指令, 将昵称解析为真实玩家名</li>
 * </ol>
 */
public class HyperNick extends JavaPlugin {

    private NickStorage storage;
    private NameGenerator nameGenerator;
    private PrefixManager prefixManager;
    private ScoreboardManager scoreboardManager;
    private NickManager nickManager;

    /** 语言文件配置 (messages/zh_cn.yml 等) */
    private FileConfiguration langConfig;

    /** Rank 配置 (ranks.yml) */
    private FileConfiguration ranksConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 加载 Rank 配置
        loadRanks();

        // 加载语言文件
        loadLang();

        // 数据层
        this.storage = new NickStorage(this);
        storage.load();

        // 管理器
        this.nameGenerator = new NameGenerator(this);
        nameGenerator.reload();

        this.prefixManager = new PrefixManager(this);
        if (!prefixManager.hook()) {
            getLogger().warning("未检测到 LuckPerms, Rank 前缀功能不可用 (聊天将无前缀).");
        }

        this.scoreboardManager = new ScoreboardManager(this);
        // 清理上次运行可能残留的计分板队伍 (插件重载后内存映射为空但队伍残留)
        scoreboardManager.cleanupOrphanedTeams();

        this.nickManager = new NickManager(this, storage, nameGenerator, prefixManager, scoreboardManager);

        // 订阅 LuckPerms 事件: 玩家权限组变更时自动刷新未匿名玩家的前缀
        prefixManager.subscribeGroupChanges((uuid, player) -> {
            nickManager.refreshGroupDisplay(player);
        });

        // 事件监听
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this, nickManager), this);
        getServer().getPluginManager().registerEvents(new TabCompleteListener(this, nickManager), this);
        getServer().getPluginManager().registerEvents(new CommandPreprocessListener(this, nickManager), this);
        getServer().getPluginManager().registerEvents(new DeathAdvancementListener(this, nickManager), this);
        if (getConfig().getBoolean("override-chat", true)) {
            getServer().getPluginManager().registerEvents(new ChatListener(this, nickManager), this);
        }

        // ProtocolLib 数据包监听 (核心: 替换 UUID + name + Tab 补全 + 聊天签名 + 系统消息)
        if (getConfig().getBoolean("packet-disguise", true)) {
            if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
                PlayerInfoPacketListener.register(this, nickManager);
                TabCompletePacketListener.register(this, nickManager);
                ChatPacketListener.register(this, nickManager);
                SystemMessagePacketListener.register(this, nickManager);
                getLogger().info("已注册 ProtocolLib 数据包监听器 "
                        + "(PLAYER_INFO + PLAYER_INFO_REMOVE + SPAWN_ENTITY + TAB_COMPLETE + CHAT + SYSTEM_CHAT).");
            } else {
                getLogger().warning("未检测到 ProtocolLib, 数据包伪装不可用 "
                        + "(头顶名牌/Tab 将显示真实身份, 但聊天仍显示昵称).");
            }
        }

        // 指令
        NickCommand nickCommand = new NickCommand(this, nickManager);
        if (getCommand("nick") != null) {
            getCommand("nick").setExecutor(nickCommand);
            getCommand("nick").setTabCompleter(nickCommand);
        }

        // PlaceholderAPI 变量扩展 (软依赖, 缺少时跳过)
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new HyperNickPlaceholder(this).register();
            getLogger().info("已注册 PlaceholderAPI 变量扩展 (%hypernick_*%).");
        } else {
            getLogger().info("未检测到 PlaceholderAPI, 变量扩展不可用 (其他插件无法使用 %hypernick_*% 变量).");
        }

        getLogger().info("HyperNick v" + getPluginMeta().getVersion() + " 已启用.");
    }

    @Override
    public void onDisable() {
        if (storage != null) {
            storage.save();
        }
        getLogger().info("HyperNick 已禁用.");
    }

    /** 重载配置与缓存 */
    public void reloadAll() {
        reloadConfig();
        loadRanks();
        loadLang();
        nameGenerator.reload();
    }

    /**
     * 加载语言文件.
     * <p>
     * 从 config.yml 的 lang 字段读取语言代码 (如 "zh_cn"),
     * 优先加载外部文件 (plugins/HyperNick/messages/zh_cn.yml),
     * 若不存在则从 JAR 内资源文件加载.
     */
    private void loadLang() {
        String lang = getConfig().getString("lang", "zh_cn");
        File langFile = new File(getDataFolder(), "messages" + File.separator + lang + ".yml");
        File langDir = new File(getDataFolder(), "messages");

        // 确保目录存在
        if (!langDir.exists()) {
            langDir.mkdirs();
        }

        // 如果外部文件不存在, 从 JAR 内释放
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
            // 回退到内置 zh_cn
            try (Reader reader = new InputStreamReader(getResource("messages/zh_cn.yml"), StandardCharsets.UTF_8)) {
                if (reader != null) {
                    langConfig = YamlConfiguration.loadConfiguration(reader);
                    getLogger().warning("语言文件 messages/" + lang + ".yml 不存在, 回退到 zh_cn.");
                }
            } catch (Exception e) {
                getLogger().warning("语言文件加载失败, 消息将使用键名: " + e.getMessage());
            }
        }
    }

    /**
     * 加载 Rank 配置文件.
     * <p>
     * 从 plugins/HyperNick/ranks.yml 加载, 若不存在则从 JAR 内资源文件释放.
     */
    private void loadRanks() {
        File ranksFile = new File(getDataFolder(), "ranks.yml");
        if (!ranksFile.exists()) {
            saveResource("ranks.yml", false);
        }
        ranksConfig = YamlConfiguration.loadConfiguration(ranksFile);
        getLogger().info("已加载 Rank 配置: ranks.yml");
    }

    /**
     * 获取 Rank 配置 (ranks.yml).
     * <p>
     * 包含 enable-group-prefix, group-mapping, ranks, random-rank-pool 等配置项.
     *
     * @return Rank 配置
     */
    public FileConfiguration getRanksConfig() {
        return ranksConfig;
    }

    /**
     * 发送配置消息.
     *
     * @param sender       接收者
     * @param key          语言文件中的消息键
     * @param replacements 占位符替换 (可为空)
     */
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

    public PrefixManager getPrefixManager() {
        return prefixManager;
    }
}
