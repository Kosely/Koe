package dev.koegoated.swiftguild.gui;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interface de création de guilde (/guild sans guilde, /guild create).
 * Tag = name tag, nom = papier, bannière = verte (tout est bon) / jaune (un des deux) / rouge (aucun).
 */
public final class CreateMenu implements Listener {
    private static final String MENU = "create";

    private enum St { UNSET, INVALID, TAKEN, OK }

    static final class Holder implements MenuHolder {
        Inventory inv;
        final Map<Integer, String> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final SwiftGuildPlugin plugin;
    private final Map<UUID, String[]> drafts = new HashMap<>();   // [nom, tag]
    private final Map<UUID, String> prompts = new ConcurrentHashMap<>();    // "name" | "tag"

    public CreateMenu(SwiftGuildPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ états
    private St nameState(String v) {
        if (v == null) return St.UNSET;
        if (!v.matches(plugin.getConfig().getString("guild.name-pattern", "[A-Za-z0-9]{3,16}"))) return St.INVALID;
        return plugin.guilds().guildByName(v) != null ? St.TAKEN : St.OK;
    }

    private St tagState(String v) {
        if (v == null) return St.UNSET;
        if (!v.matches(plugin.getConfig().getString("guild.tag-pattern", "[A-Za-z0-9]{3,5}"))) return St.INVALID;
        return plugin.guilds().guildByName(v) != null ? St.TAKEN : St.OK;
    }

    // ------------------------------------------------------------------ rendu
    public void open(Player p) {
        if (plugin.guilds().guildOf(p.getUniqueId()) != null) { plugin.send(p, "already-in-guild"); return; }
        String[] d = drafts.computeIfAbsent(p.getUniqueId(), k -> new String[2]);
        var ui = plugin.ui();
        St sn = nameState(d[0]), st = tagState(d[1]);
        int ok = (sn == St.OK ? 1 : 0) + (st == St.OK ? 1 : 0);
        String banner = ok == 2 ? "confirm-ok" : ok == 1 ? "confirm-warn" : "confirm-error";
        String unset = ui.text(MENU, "unset");
        String[] kv = {
                "name", d[0] == null ? unset : d[0], "tag", d[1] == null ? unset : d[1],
                "name_status", ui.text(MENU, "status-" + sn.name().toLowerCase(Locale.ROOT)),
                "tag_status", ui.text(MENU, "status-" + st.name().toLowerCase(Locale.ROOT)),
                "player", p.getName()
        };
        Holder h = new Holder();
        h.inv = ui.inventory(h, MENU, kv);
        place(h, "tag", "tag", p, kv);
        place(h, "name", "name", p, kv);
        place(h, banner, "confirm", p, kv);
        p.openInventory(h.inv);
    }

    private void place(Holder h, String id, String action, Player p, String... kv) {
        ItemStack it = plugin.ui().itemWith(MENU, id, new dev.koegoated.swiftguild.manager.Interfaces.Opts(null, p, null), kv);
        int slot = plugin.ui().slot(MENU, id);
        if (it == null || slot < 0 || slot >= h.inv.getSize()) return;
        h.inv.setItem(slot, it);
        h.actions.put(slot, action);
    }

    // ------------------------------------------------------------------ clics
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
        if (!plugin.guilds().isEnabled()) { p.closeInventory(); return; }
        String action = h.actions.get(e.getRawSlot());
        if (action == null) return;
        UUID u = p.getUniqueId();
        String[] d = drafts.computeIfAbsent(u, k -> new String[2]);

        switch (action) {
            case "name", "tag" -> {
                prompts.put(u, action);
                p.closeInventory();
                plugin.send(p, action.equals("name") ? "create-prompt-name" : "create-prompt-tag",
                        "cancel", plugin.getConfig().getString("creation.cancel-word", "cancel"));
            }
            case "confirm" -> {
                if (nameState(d[0]) != St.OK || tagState(d[1]) != St.OK) {
                    plugin.send(p, "create-incomplete");
                    open(p); // rafraîchit (ex. un nom devenu indisponible)
                    return;
                }
                if (plugin.command().tryCreate(p, d[0], d[1])) {
                    drafts.remove(u);
                    plugin.guildMenu().open(p);
                }
            }
            default -> {}
        }
    }

    // ------------------------------------------------------- saisie dans le chat
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String field = prompts.get(p.getUniqueId());
        if (field == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> handleInput(p, field, text));
    }

    private void handleInput(Player p, String field, String text) {
        UUID u = p.getUniqueId();
        if (!prompts.containsKey(u)) return;
        if (text.equalsIgnoreCase(plugin.getConfig().getString("creation.cancel-word", "cancel"))) {
            prompts.remove(u);
            plugin.send(p, "create-prompt-cancelled");
            open(p);
            return;
        }
        boolean isName = field.equals("name");
        String pattern = plugin.getConfig().getString(isName ? "guild.name-pattern" : "guild.tag-pattern",
                isName ? "[A-Za-z0-9]{3,16}" : "[A-Za-z0-9]{3,5}");
        if (!text.matches(pattern)) {
            // caractères spéciaux / trop court : refusé, on reste en attente d'une saisie valide
            plugin.send(p, isName ? "invalid-name" : "invalid-tag");
            return;
        }
        drafts.computeIfAbsent(u, k -> new String[2])[isName ? 0 : 1] = text;
        prompts.remove(u);
        if (plugin.guilds().guildByName(text) != null) {
            plugin.send(p, isName ? "create-name-taken" : "create-tag-taken", "value", text);
        }
        open(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        drafts.remove(e.getPlayer().getUniqueId());
        prompts.remove(e.getPlayer().getUniqueId());
    }
}
