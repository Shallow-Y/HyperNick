package com.hypernick.listener;

import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.manager.NickManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tab 补全拦截监听器.
 * <p>
 * 拦截所有指令的 Tab 补全请求, 将补全列表中匿名玩家的<b>真实名</b>替换为<b>昵称</b>,
 * 确保玩家在任何指令 (如 /tp, /msg, /give 等) 的 Tab 补全中都无法看到被匿名者的真实 ID.
 * <p>
 * 同时处理反向匹配: 当玩家输入的文本恰好是某个昵称的前缀时,
 * 该昵称也会出现在补全列表中 (正常情况下 Bukkit 只按真实名匹配).
 */
public class TabCompleteListener implements Listener {

    private final HyperNick plugin;
    private final NickManager nickManager;

    public TabCompleteListener(HyperNick plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTabComplete(TabCompleteEvent event) {
        List<String> completions = event.getCompletions();
        if (completions.isEmpty()) {
            return;
        }

        String buffer = event.getBuffer();
        String lowerBuffer = buffer.toLowerCase();

        // 跳过 /nick 系列命令: 这些命令的补全由 NickCommand 的 TabCompleter 自行处理,
        // 不需要也不应该补全玩家名 (避免泄露真实名)
        if (lowerBuffer.startsWith("/nick ") || lowerBuffer.startsWith("/nickname ") || lowerBuffer.startsWith("/disguise ")) {
            return;
        }

        // 构建真实名 -> 昵称映射 (仅匿名玩家)
        Map<String, String> realToNick = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (nickManager.isNicked(player.getUniqueId())) {
                realToNick.put(player.getName(), nickManager.getDisplayName(player));
            }
        }

        if (realToNick.isEmpty()) {
            return;
        }

        // 提取玩家正在输入的最后一个词 (用于昵称前缀匹配)
        String lastArg = extractLastArg(buffer);
        String lowerLastArg = lastArg.toLowerCase();

        // 构建替换后的补全列表
        List<String> modified = new ArrayList<>(completions.size());

        for (String completion : completions) {
            String nick = realToNick.get(completion);
            if (nick != null) {
                // 这个补全项是某个匿名玩家的真实名 → 替换为昵称
                // 仅当玩家输入的前缀能匹配到昵称时才加入
                if (nick.toLowerCase().startsWith(lowerLastArg)) {
                    modified.add(nick);
                }
                // 真实名被拦截, 不加入列表
            } else {
                // 非匿名玩家名或非玩家名补全项, 原样保留
                modified.add(completion);
            }
        }

        // 额外: 检查是否有匿名玩家的昵称匹配输入前缀, 但不在原始补全列表中
        // (因为 Bukkit 只按真实名生成补全, 昵称不会被自动补全)
        for (Map.Entry<String, String> entry : realToNick.entrySet()) {
            String nick = entry.getValue();
            if (nick.toLowerCase().startsWith(lowerLastArg) && !modified.contains(nick)) {
                modified.add(nick);
            }
        }

        completions.clear();
        completions.addAll(modified);
    }

    /** 从指令缓冲区中提取最后一个参数 (用于前缀匹配) */
    private String extractLastArg(String buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return "";
        }
        int lastSpace = buffer.lastIndexOf(' ');
        if (lastSpace < 0) {
            return "";
        }
        return buffer.substring(lastSpace + 1);
    }
}
