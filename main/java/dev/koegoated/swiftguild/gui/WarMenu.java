package dev.koegoated.swiftguild.gui;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.manager.GuildManager;
import dev.koegoated.swiftguild.manager.Interfaces;
import dev.koegoated.swiftguild.manager.WarManager;
import dev.koegoated.swiftguild.model.Alliance;
import dev.koegoated.swiftguild.model.Guild;
import dev.koegoated.swiftguild.model.War;
import dev.koegoated.swiftguild.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Interfaces de guerre (liste, création, sélection des adversaires, suivi). Tout est défini dans interfaces/*.yml. */
public final class WarMenu implements Listener {
    private static final String MAIN = "war-main", SETUP = "war-setup", TARGETS = "war-targets", VIEW = "war-view";

    static final class Draft {
        boolean asAlliance;
        final Set<UUID> guildTargets = new LinkedHashSet<>();
        final Set<UUID> allianceTargets = new LinkedHashSet<>();
        int minutes, points;
    }

    record Entry(boolean alliance, UUID id, String tag, String name) {}

    static final class Holder implements MenuHolder {
        String menu;
        Draft draft;
        int page;
        UUID warId;
        Inventory inv;
        final Map<Integer, String> actions = new HashMap<>();
        final Map<Integer, Object> payload = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private final SwiftGuildPlugin plugin;
    private final GuildManager gm;
    private final WarManager wm;

    public WarMenu(SwiftGuildPlugin plugin, GuildManager gm, WarManager wm) {
        this.plugin = plugin;
        this.gm = gm;
        this.wm = wm;
    }

    private Interfaces ui() { return plugin.ui(); }

    // --------------------------------------------------------------- ouvertures
    public void open(Player p) {
        show(p, MAIN, null, 0, null);
    }

    private void show(Player p, String menu, Draft draft, int page, UUID warId) {
        Holder h = new Holder();
        h.menu = menu;
        h.draft = draft;
        h.page = page;
        h.warId = warId;
        h.inv = ui().inventory(h, menu);
        render(p, h);
        p.openInventory(h.inv);
    }

    private Draft newDraft() {
        var cfg = plugin.getConfig();
        Draft d = new Draft();
        d.minutes = cfg.getInt("war.default-duration-minutes", 60);
        d.points = cfg.getInt("war.default-objective-points", 1000);
        return d;
    }

    /** Rafraîchit chaque seconde les vues de guerre ouvertes. */
    public void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h && h.menu.equals(VIEW)) render(p, h);
        }
    }

    // ------------------------------------------------------------------ rendu
    private void render(Player p, Holder h) {
        h.inv.clear();
        ui().fillBackground(h.inv, h.menu);
        h.actions.clear();
        h.payload.clear();
        switch (h.menu) {
            case MAIN -> renderMain(p, h);
            case SETUP -> renderSetup(p, h);
            case TARGETS -> renderTargets(p, h);
            case VIEW -> renderView(h);
            default -> {}
        }
    }

    /** Pose un item fixe défini dans interfaces/*.yml (action = identifiant de l'action au clic). */
    private void put(Holder h, String id, String action, Boolean glow, String... kv) {
        ItemStack it = ui().item(h.menu, id, glow, kv);
        int slot = ui().slot(h.menu, id);
        if (it == null || slot < 0 || slot >= h.inv.getSize()) return;
        h.inv.setItem(slot, it);
        h.actions.put(slot, action);
    }

    private void putAt(Holder h, int slot, String id, String action, Object data, Boolean glow, String... kv) {
        ItemStack it = ui().item(h.menu, id, glow, kv);
        if (it == null || slot < 0 || slot >= h.inv.getSize()) return;
        h.inv.setItem(slot, it);
        h.actions.put(slot, action);
        if (data != null) h.payload.put(slot, data);
    }

    private void renderMain(Player p, Holder h) {
        Guild g = gm.guildOf(p.getUniqueId());
        if (g != null) {
            List<Integer> slots = ui().slots(MAIN, "war-slots");
            int i = 0;
            for (War w : wm.warsOf(g.id)) {
                if (i >= slots.size()) break;
                String time = w.active ? ui().text(MAIN, "time-left", "time", Text.time(w.secondsLeft)) : "";
                putAt(h, slots.get(i++), w.active ? "war-active" : "war-ended", "war", w.id, null,
                        "a", w.label(w.sideA), "b", w.label(w.sideB),
                        "state", ui().text(MAIN, w.active ? "active" : "ended"),
                        "objective", Text.num(w.objective), "minutes", String.valueOf(w.durationMinutes), "time", time);
            }
        }
        if (g != null && gm.guildPerm(p.getUniqueId(), "START_WAR")) put(h, "new-war", "new-war", null);
        else put(h, "new-war-locked", "locked", null);
    }

    private List<UUID> sideA(Player p, Draft d) {
        Guild g = gm.guildOf(p.getUniqueId());
        if (g == null) return List.of();
        Alliance a = gm.allianceOf(g);
        return d.asAlliance && a != null ? new ArrayList<>(a.guilds) : List.of(g.id);
    }

    private String tags(Collection<UUID> ids) {
        StringJoiner j = new StringJoiner(" + ");
        for (UUID id : ids) {
            Guild g = gm.guilds.get(id);
            if (g != null) j.add(g.tag);
        }
        return j.toString();
    }

    private void renderSetup(Player p, Holder h) {
        Draft d = h.draft;
        Alliance a = gm.allianceOf(gm.guildOf(p.getUniqueId()));
        if (a == null) d.asAlliance = false;
        var cfg = plugin.getConfig();
        int dStep = cfg.getInt("war.duration-step-minutes", 5), pStep = cfg.getInt("war.points-step", 100);

        List<String> sel = new ArrayList<>();
        for (UUID id : d.guildTargets) {
            Guild x = gm.guilds.get(id);
            if (x != null) sel.add(ui().text(SETUP, "target-guild", "tag", x.tag));
        }
        for (UUID id : d.allianceTargets) {
            Alliance x = gm.alliances.get(id);
            if (x != null) sel.add(ui().text(SETUP, "target-alliance", "tag", x.tag));
        }
        String[] kv = {
                "side", tags(sideA(p, d)),
                "hint", ui().text(SETUP, a == null ? "hint-no-alliance" : "hint-toggle"),
                "minutes", String.valueOf(d.minutes), "points", Text.num(d.points),
                "dstep", String.valueOf(dStep), "pstep", Text.num(pStep),
                "targets", sel.isEmpty() ? ui().text(SETUP, "none") : String.join("\n", sel),
                "ppk", String.valueOf(cfg.getInt("war.points-per-kill", 10))
        };
        put(h, d.asAlliance ? "side-alliance" : "side-guild", "side", null, kv);
        for (String id : new String[]{"minus-duration", "duration", "plus-duration", "minus-points", "points",
                "plus-points", "targets", "start", "back"}) put(h, id, id, null, kv);
    }

    private List<Entry> entries(Player p, Draft d) {
        Set<UUID> mine = new HashSet<>(sideA(p, d));
        Alliance myAlliance = gm.allianceOf(gm.guildOf(p.getUniqueId()));
        List<Entry> out = new ArrayList<>();
        for (Guild x : gm.guilds.values()) if (!mine.contains(x.id)) out.add(new Entry(false, x.id, x.tag, x.name));
        for (Alliance x : gm.alliances.values()) {
            if (myAlliance != null && x.id.equals(myAlliance.id)) continue;
            out.add(new Entry(true, x.id, x.tag, x.name));
        }
        out.sort(Comparator.comparing(Entry::tag, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private void renderTargets(Player p, Holder h) {
        List<Entry> all = entries(p, h.draft);
        List<Integer> slots = ui().slots(TARGETS, "entry-slots");
        int per = Math.max(1, slots.size());
        int pages = Math.max(1, (all.size() + per - 1) / per);
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        for (int i = 0; i < slots.size(); i++) {
            int idx = h.page * per + i;
            if (idx >= all.size()) break;
            Entry e = all.get(idx);
            boolean on = e.alliance() ? h.draft.allianceTargets.contains(e.id()) : h.draft.guildTargets.contains(e.id());
            putAt(h, slots.get(i), e.alliance() ? "entry-alliance" : "entry-guild", "entry", e, on ? Boolean.TRUE : null,
                    "tag", e.tag(), "name", e.name(), "state", ui().text(TARGETS, on ? "selected" : "unselected"));
        }
        String[] kv = {"page", String.valueOf(h.page + 1), "pages", String.valueOf(pages)};
        if (h.page > 0) put(h, "prev", "prev", null, kv);
        if (h.page < pages - 1) put(h, "next", "next", null, kv);
        put(h, "done", "done", null, kv);
    }

    private void renderView(Holder h) {
        War w = wm.wars.get(h.warId);
        if (w == null) {
            put(h, "missing", "none", null);
            put(h, "back", "back", null);
            return;
        }
        int pa = w.sidePoints(w.sideA), pb = w.sidePoints(w.sideB);
        String result = "";
        if (!w.active) {
            result = w.winner == 3 ? ui().text(VIEW, "draw")
                    : ui().text(VIEW, "winner", "winner", w.label(w.winner == 1 ? w.sideA : w.sideB));
        }
        put(h, "info", "none", null,
                "state", ui().text(VIEW, w.active ? "active" : "ended"),
                "objective", Text.num(w.objective),
                "time", w.active ? ui().text(VIEW, "time-left", "time", Text.time(w.secondsLeft))
                        : ui().text(VIEW, "duration", "minutes", String.valueOf(w.durationMinutes)),
                "label-a", w.label(w.sideA), "label-b", w.label(w.sideB),
                "pa", Text.num(pa), "pb", Text.num(pb), "result", result);
        put(h, "versus", "none", null, "label-a", w.label(w.sideA), "label-b", w.label(w.sideB));
        fillSide(h, w, w.sideA, ui().slots(VIEW, "side-a-slots"), "side-a-entry");
        fillSide(h, w, w.sideB, ui().slots(VIEW, "side-b-slots"), "side-b-entry");
        put(h, "back", "back", null);
    }

    private void fillSide(Holder h, War w, List<UUID> side, List<Integer> slots, String id) {
        for (int i = 0; i < Math.min(slots.size(), side.size()); i++) {
            UUID gid = side.get(i);
            int[] s = w.stat(gid);
            putAt(h, slots.get(i), id, "none", null, null,
                    "tag", w.tags.getOrDefault(gid, "?"),
                    "points", Text.num(w.points(gid)), "kills", Text.num(s[0]), "deaths", Text.num(s[1]),
                    "kdr", Text.kdr((double) s[0] / Math.max(1, s[1])));
        }
    }

    // ----------------------------------------------------------------- clics
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
        int slot = e.getRawSlot();
        String action = h.actions.get(slot);
        if (action == null) return;
        var cfg = plugin.getConfig();

        switch (h.menu) {
            case MAIN -> {
                if (action.equals("new-war") && gm.guildPerm(p.getUniqueId(), "START_WAR")) show(p, SETUP, newDraft(), 0, null);
                else if (action.equals("war") && h.payload.get(slot) instanceof UUID id) show(p, VIEW, null, 0, id);
            }
            case SETUP -> {
                Draft d = h.draft;
                int dStep = cfg.getInt("war.duration-step-minutes", 5), pStep = cfg.getInt("war.points-step", 100);
                int dMin = cfg.getInt("war.min-duration-minutes", 5), dMax = cfg.getInt("war.max-duration-minutes", 1440);
                int pMin = cfg.getInt("war.min-objective-points", 100), pMax = cfg.getInt("war.max-objective-points", 100000);
                switch (action) {
                    case "side" -> { if (gm.allianceOf(gm.guildOf(p.getUniqueId())) != null) d.asAlliance = !d.asAlliance; }
                    case "minus-duration" -> d.minutes = Math.max(dMin, d.minutes - dStep);
                    case "plus-duration" -> d.minutes = Math.min(dMax, d.minutes + dStep);
                    case "minus-points" -> d.points = Math.max(pMin, d.points - pStep);
                    case "plus-points" -> d.points = Math.min(pMax, d.points + pStep);
                    case "targets" -> { show(p, TARGETS, d, 0, null); return; }
                    case "start" -> {
                        String err = wm.start(p, d.asAlliance, d.guildTargets, d.allianceTargets, d.minutes, d.points);
                        if (err != null) plugin.send(p, err); else p.closeInventory();
                        return;
                    }
                    case "back" -> { show(p, MAIN, null, 0, null); return; }
                    default -> { return; }
                }
                render(p, h);
            }
            case TARGETS -> {
                switch (action) {
                    case "done" -> show(p, SETUP, h.draft, 0, null);
                    case "prev" -> { h.page--; render(p, h); }
                    case "next" -> { h.page++; render(p, h); }
                    case "entry" -> {
                        if (h.payload.get(slot) instanceof Entry en) {
                            Set<UUID> set = en.alliance() ? h.draft.allianceTargets : h.draft.guildTargets;
                            if (!set.remove(en.id())) set.add(en.id());
                            render(p, h);
                        }
                    }
                    default -> {}
                }
            }
            case VIEW -> { if (action.equals("back")) show(p, MAIN, null, 0, null); }
            default -> {}
        }
    }
}
