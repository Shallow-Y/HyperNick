package com.hypernick.manager;

import com.hypernick.HyperNick;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PrefixNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * LuckPerms 前缀管理.
 * <p>
 * 使用 transientData (会话级瞬态节点) 设置伪装 Rank 前缀:
 * <ul>
 *   <li>不写入数据库, 玩家下线自动消失, 不污染玩家真实权限数据;</li>
 *   <li>跨服不生效 (仅当前服务器会话), 重连后由本插件在 onJoin 重新套用.</li>
 * </ul>
 * <p>
 * 同时订阅 LuckPerms 事件总线, 当玩家权限组变更时触发回调,
 * 让 NickManager 刷新未匿名玩家的前缀显示.
 * <p>
 * 关键优化: UserDataRecalculateEvent 在玩家加入时也会触发 (LuckPerms 加载用户数据),
 * 通过跟踪上次主组, 仅在主组实际变更时才触发刷新, 避免加入时重复执行
 * 昂贵的计分板队伍操作 (updateTeamWaypoints 遍历所有在线玩家).
 */
public class PrefixManager {

    private final HyperNick plugin;
    private LuckPerms luckPerms;
    private boolean available;

    /** 跟踪每个玩家上次已知的主组, 用于判断是否实际变更 */
    private final Map<UUID, String> lastKnownGroups = new ConcurrentHashMap<>();

    public PrefixManager(HyperNick plugin) {
        this.plugin = plugin;
    }

    /** 挂钩 LuckPerms API, 返回是否成功 */
    public boolean hook() {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            this.available = false;
            return false;
        }
        RegisteredServiceProvider<LuckPerms> provider =
                Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            this.available = false;
            return false;
        }
        this.luckPerms = provider.getProvider();
        this.available = true;
        plugin.getLogger().info("已挂钩 LuckPerms API.");
        return true;
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * 订阅 LuckPerms 事件总线, 监听玩家权限数据重新计算事件.
     * <p>
     * 当玩家的权限组变更 (如管理员通过 /lp user <player> parent set <group> 修改主组)、
     * 或权限节点被添加/移除时, LuckPerms 会触发 UserDataRecalculateEvent.
     * <p>
     * <b>关键优化</b>: UserDataRecalculateEvent 在玩家加入时也会触发 (LuckPerms 加载用户数据).
     * 如果不加以区分, 每次玩家加入都会触发 refreshGroupDisplay → applyTeam → setOption,
     * 而 setOption(COLLISION_RULE) 会调用 updateTeamWaypoints 遍历所有在线玩家,
     * 在多人服务器上会导致主线程卡顿 (10-15秒无响应).
     * <p>
     * 解决方案: 跟踪每个玩家上次已知的主组, 仅在主组<b>实际变更</b>时才触发刷新.
     * 首次事件 (玩家加入) 会记录主组但不触发刷新, 由 PlayerConnectionListener.onJoin 处理初始前缀.
     * <p>
     * 回调在 LuckPerms 的异步线程上执行, 需通过 BukkitScheduler 切换到主线程操作 Bukkit API.
     *
     * @param callback 回调: (玩家UUID, 在线玩家) — 由 NickManager 处理刷新逻辑
     */
    public void subscribeGroupChanges(BiConsumer<UUID, Player> callback) {
        if (!available) {
            return;
        }
        luckPerms.getEventBus().subscribe(UserDataRecalculateEvent.class, event -> {
            UUID uuid = event.getUser().getUniqueId();
            String currentGroup = event.getUser().getPrimaryGroup();

            // 原子更新并获取旧值
            String lastGroup = lastKnownGroups.put(uuid, currentGroup);

            // 首次记录 (玩家加入) 或组未变更 → 不刷新
            if (lastGroup == null || lastGroup.equals(currentGroup)) {
                return;
            }

            // 主组已变更 → 切换到主线程刷新
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    callback.accept(uuid, player);
                }
            });
        });
        plugin.getLogger().info("已订阅 LuckPerms UserDataRecalculateEvent (权限组变更自动刷新前缀, 跳过加入事件).");
    }

    /**
     * 清除玩家的主组跟踪缓存.
     * <p>
     * 在玩家退出时调用, 确保下次加入时会重新记录主组 (但不触发刷新).
     */
    public void clearTrackedGroup(UUID uuid) {
        lastKnownGroups.remove(uuid);
    }

    /**
     * 设置会话级临时前缀.
     *
     * @param player   目标玩家
     * @param prefix   前缀文本 (含 & 颜色代码, 已转换为 §)
     * @param priority 权重 (越大越优先)
     */
    public void setTransientPrefix(Player player, String prefix, int priority) {
        if (!available) {
            return;
        }
        User user = luckPerms.getPlayerAdapter(Player.class).getUser(player);
        // 清除旧瞬态前缀, 避免堆叠
        user.transientData().clear(node -> node.getType() == NodeType.PREFIX);
        PrefixNode node = PrefixNode.builder(prefix, priority).build();
        user.transientData().add(node);
    }

    /** 清除玩家所有瞬态前缀 */
    public void clearPrefix(Player player) {
        if (!available) {
            return;
        }
        User user = luckPerms.getPlayerAdapter(Player.class).getUser(player);
        user.transientData().clear(node -> node.getType() == NodeType.PREFIX);
    }

    /** 获取玩家当前生效前缀 (考虑继承与上下文) */
    public String getPrefix(Player player) {
        if (!available) {
            return "";
        }
        CachedMetaData metaData = luckPerms.getPlayerAdapter(Player.class).getMetaData(player);
        String prefix = metaData.getPrefix();
        return prefix != null ? prefix : "";
    }

    /** 获取玩家当前生效后缀 */
    public String getSuffix(Player player) {
        if (!available) {
            return "";
        }
        CachedMetaData metaData = luckPerms.getPlayerAdapter(Player.class).getMetaData(player);
        String suffix = metaData.getSuffix();
        return suffix != null ? suffix : "";
    }

    /**
     * 获取玩家的 LuckPerms 主组 (primary group).
     * <p>
     * 用于未匿名玩家的前缀映射: 根据 LuckPerms 主组匹配 HyperNick Rank.
     *
     * @param player 目标玩家
     * @return 主组名称; LuckPerms 不可用时返回 "default"
     */
    public String getPrimaryGroup(Player player) {
        if (!available) {
            return "default";
        }
        User user = luckPerms.getPlayerAdapter(Player.class).getUser(player);
        return user.getPrimaryGroup();
    }
}
