package com.hypernick.gui;

import com.hypernick.HyperNick;
import com.hypernick.data.NickData;
import com.hypernick.manager.NickManager;
import com.hypernick.util.ColorUtil;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NickGuiManager implements Listener {

    private final HyperNick plugin;
    private final NickManager nickManager;
    private final Map<UUID, GuiState> guiStates = new ConcurrentHashMap<>();
    private final Map<UUID, GuiState> anvilSessions = new ConcurrentHashMap<>();

    public NickGuiManager(HyperNick plugin, NickManager nickManager) {
        this.plugin = plugin;
        this.nickManager = nickManager;
    }

    private static class GuiState {
        String rankKey;
        NickData.SkinMode skinMode;

        GuiState(String rankKey, NickData.SkinMode skinMode) {
            this.rankKey = rankKey;
            this.skinMode = skinMode;
        }
    }

    public void openMainMenu(Player player) {
        Component page = Component.text("HyperNick 匿名系统\n\n", NamedTextColor.AQUA)
                .append(Component.text("通过匿名系统, 你可以伪装你的身份", NamedTextColor.GRAY))
                .append(Component.text(" (名称、等级和皮肤).\n\n", NamedTextColor.GRAY))
                .append(clickable("» 我已了解, 开始设置昵称 «", "/nick gui rank", NamedTextColor.GREEN));
        player.openBook(createBook(page));
    }

    public void openRankMenu(Player player) {
        var page = Component.text("选择伪装等级\n\n", NamedTextColor.AQUA).toBuilder();
        for (String rank : nickManager.getAvailableRanks()) {
            String prefix = nickManager.getRankPrefix(rank);
            String display = rank.toUpperCase();
            Component rankLine = Component.text("» ", NamedTextColor.GRAY)
                    .append(ColorUtil.toComponent(prefix + display))
                    .clickEvent(ClickEvent.runCommand("/nick gui selectrank " + rank))
                    .append(Component.text("\n"));
            page.append(rankLine);
        }
        player.openBook(createBook(page.build()));
    }

    public void selectRank(Player player, String rankKey) {
        if (nickManager.getRankSection(rankKey) == null) {
            return;
        }
        GuiState state = guiStates.computeIfAbsent(player.getUniqueId(),
                k -> new GuiState(nickManager.pickRandomRank(), NickData.SkinMode.REAL));
        state.rankKey = rankKey;
        openSkinMenu(player);
    }

    public void openSkinMenu(Player player) {
        Component page = Component.text("选择皮肤\n\n", NamedTextColor.AQUA)
                .append(clickable("» 使用真实皮肤 «", "/nick gui selectskin REAL", NamedTextColor.GREEN))
                .append(Component.text("\n"))
                .append(clickable("» 随机皮肤 «", "/nick gui selectskin RANDOM", NamedTextColor.YELLOW))
                .append(Component.text("\n"))
                .append(clickable("» 默认皮肤 (Steve/Alex) «", "/nick gui selectskin RESET", NamedTextColor.GRAY));
        player.openBook(createBook(page));
    }

    public void selectSkin(Player player, NickData.SkinMode mode) {
        GuiState state = guiStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.skinMode = mode;
        openNameMenu(player);
    }

    public void openNameMenu(Player player) {
        var page = Component.text("选择昵称\n\n", NamedTextColor.AQUA).toBuilder();
        page.append(clickable("» 随机昵称 «", "/nick gui name random", NamedTextColor.GREEN))
                .append(Component.text("\n"));
        String lastNick = nickManager.getLastNick(player.getUniqueId());
        if (lastNick != null && !lastNick.isEmpty()) {
            page.append(Component.text("» 使用上次昵称: ", NamedTextColor.YELLOW)
                            .append(Component.text(lastNick, NamedTextColor.WHITE))
                            .clickEvent(ClickEvent.runCommand("/nick gui name reuse")))
                    .append(Component.text("\n"));
        }
        page.append(clickable("» 自定义昵称 (铁砧输入) «", "/nick gui name custom", NamedTextColor.AQUA));
        player.openBook(createBook(page.build()));
    }

    public void applyRandomName(Player player) {
        GuiState state = guiStates.remove(player.getUniqueId());
        String rankKey = state != null ? state.rankKey : nickManager.pickRandomRank();
        NickData.SkinMode skinMode = state != null ? state.skinMode : NickData.SkinMode.REAL;
        nickManager.nickRandomWithSkin(player, rankKey, skinMode);
    }

    public void applyReuseName(Player player) {
        GuiState state = guiStates.remove(player.getUniqueId());
        String rankKey = state != null ? state.rankKey : null;
        NickData.SkinMode skinMode = state != null ? state.skinMode : NickData.SkinMode.REAL;
        nickManager.nickReuse(player, rankKey, skinMode);
    }

    public void openAnvilInput(Player player) {
        GuiState state = guiStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        try {
            InventoryView view = player.openAnvil(null, false);
            if (view != null) {
                AnvilInventory anvil = (AnvilInventory) view.getTopInventory();
                ItemStack input = new ItemStack(Material.PAPER);
                ItemMeta meta = input.getItemMeta();
                meta.displayName(Component.text("输入昵称", NamedTextColor.WHITE));
                input.setItemMeta(meta);
                anvil.setItem(0, input);
                anvilSessions.put(player.getUniqueId(), state);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("无法打开铁砧GUI: " + t.getMessage());
        }
    }

    public void handleAnvilResult(Player player, String name) {
        GuiState state = anvilSessions.remove(player.getUniqueId());
        guiStates.remove(player.getUniqueId());
        if (state == null || name == null || name.isBlank()) {
            return;
        }
        nickManager.nickPlayerWithSkin(player, name, state.rankKey, state.skinMode);
    }

    public boolean hasAnvilSession(UUID uuid) {
        return anvilSessions.containsKey(uuid);
    }

    public void removeAnvilSession(UUID uuid) {
        anvilSessions.remove(uuid);
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        if (!hasAnvilSession(player.getUniqueId())) return;
        event.getInventory().setRepairCost(0);
        event.getInventory().setMaximumRepairCost(0);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!hasAnvilSession(player.getUniqueId())) return;
        event.setCancelled(true);
        if (event.getRawSlot() != 2) return;
        ItemStack result = event.getCurrentItem();
        if (result == null || !result.hasItemMeta()) return;
        Component displayName = result.getItemMeta().displayName();
        if (displayName == null) return;
        String name = PlainTextComponentSerializer.plainText().serialize(displayName);
        player.closeInventory();
        handleAnvilResult(player, name);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        removeAnvilSession(player.getUniqueId());
    }

    private Book createBook(Component... pages) {
        return Book.book(Component.text("HyperNick"), Component.text("HyperNick"), List.of(pages));
    }

    private Component clickable(String text, String command, NamedTextColor color) {
        return Component.text(text)
                .color(color)
                .clickEvent(ClickEvent.runCommand(command));
    }
}