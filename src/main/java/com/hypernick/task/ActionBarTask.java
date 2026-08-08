package com.hypernick.task;

import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.manager.NickManager;
import com.hypernick.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * ActionBar 提示任务 — 定期向已匿名玩家发送提示.
 * <p>
 * 每 2 秒 (40 tick) 向所有已匿名的在线玩家发送 actionbar 消息,
 * 提醒玩家当前处于匿名状态.
 */
public class ActionBarTask implements Runnable {

    private final HyperNick plugin;
    private final NickManager nickManager;

    public ActionBarTask(HyperNick plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!nickManager.isNicked(player.getUniqueId())) {
                continue;
            }
            String message = plugin.getLangConfig().getString("actionbar-nicked", "&fYou are currently &cNICKED");
            player.sendActionBar(ColorUtil.toComponent(message));
        }
    }
}
