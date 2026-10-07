package dev.koegoated.swiftguild.gui;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.manager.GuildManager;
import dev.koegoated.swiftguild.manager.Interfaces;
import dev.koegoated.swiftguild.model.Guild;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Menu principal de la guilde (/guild avec une guilde).
 * Deux dispositions : "guild-menu" (membres avec au moins une permission de gestion, owner inclus)
 * et "guild-menu-basic" (joueur sans permission). Chaque item peut exiger une permission de rôle.
 */
public final class GuildMenu implements Listener {
    private static final String FULL = "guild-menu", BASIC = "guild-menu-basic";

    static final class Holder implements MenuHolder {
        Inventory inv;
        final Map<Integer, String> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final SwiftGuildPlugin plugin;
    private final GuildManager gm;

    public GuildMenu(SwiftGuildPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.guilds();
    }

    private boolean hasFullLayout(Player p) {
        UUID u = p.getUniqueId();
        if (gm.isOwner(u, false)) return true;
        for (String perm : plugin.ui().stringList(FULL + ".full-layout-permissions")) {
            if (gm.guildPerm(u, perm.toUpperCase())) return true;
        }
        return false;
    }

    public void open(Player p) {
        Guild g = gm.guildOf(p.getUniqueId());
        if (g == null) { plugin.send(p, "no-guild"); return; }
        var ui = plugin.ui();
        String menu = hasFullLayout(p) ? FULL : BASIC;

        Map<String, String> info = gm.info(g, p.getUniqueId());
        String[] kv = new String[info.size() * 2 + 2];
        int i = 0;
        for (Map.Entry<String, String> e : info.entrySet()) {
            kv[i++] = "guild_" + e.getKey();
            kv[i++] = e.getValue();
        }
        kv[i++] = "player";
        kv[i] = p.getName();

        Holder h = new Holder();
        h.inv = ui.inventory(h, menu, kv);
        for (String id : ui.itemIds(menu)) {
            String perm = ui.itemString(menu, id, "permission");
            if (perm != null && !perm.isBlank() && !gm.guildPerm(p.getUniqueId(), perm.toUpperCase())) continue;
            Material override = id.equals("banner")
                    ? ui.material(FULL + ".banner-colors." + g.tagColor, Material.WHITE_BANNER) : null;
            ItemStack it = ui.itemWith(menu, id, new Interfaces.Opts(null, p, override), kv);
            int slot = ui.slot(menu, id);
            if (it == null || slot < 0 || slot >= h.inv.getSize()) continue;
            h.inv.setItem(slot, it);
            h.actions.put(slot, id);
        }
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!gm.isEnabled()) { p.closeInventory(); return; }
        String action = h.actions.get(e.getRawSlot());
        if (action == null || action.equals("banner")) return;
        // Les sous-menus (membres, rôles, stats, boutique, relations, logs, succès, paramètres)
        // seront branchés ici dès que leurs interfaces seront définies.
        String label = plugin.ui().text(FULL, "menu-" + action);
        plugin.send(p, "menu-soon", "menu", label.isEmpty() ? action : label);
    }
}
