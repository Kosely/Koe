package dev.koegoated.swiftguild.manager;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.util.Text;
import dev.koegoated.swiftguild.util.YamlMerge;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** interfaces/*.yml : un fichier par interface (titre, taille, items : matériau, nom, lore, slot, glow...). */
public final class Interfaces {
    /** Options de construction d'un item. */
    public record Opts(Boolean glow, Player viewer, Material material) {
        public static final Opts NONE = new Opts(null, null, null);
    }

    /** Les interfaces du plugin : chaque nom correspond au fichier interfaces/<nom>.yml. */
    public static final List<String> MENUS = List.of(
            "create", "guild-menu", "guild-menu-basic", "war-main", "war-setup", "war-targets", "war-view");

    private final SwiftGuildPlugin plugin;
    private final File dir;
    private YamlConfiguration cfg = new YamlConfiguration();

    public Interfaces(SwiftGuildPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "interfaces");
    }

    /** Au moins un fichier d'interface a disparu (supprimé à la main). */
    public boolean anyMissing() {
        for (String menu : MENUS) if (!new File(dir, menu + ".yml").exists()) return true;
        return false;
    }

    /** Charge tous les fichiers du dossier interfaces/ (créés depuis les valeurs par défaut s'ils manquent). */
    public void load() {
        dir.mkdirs();
        migrateLegacyFile();
        YamlConfiguration all = new YamlConfiguration();
        for (String menu : MENUS) {
            String res = "interfaces/" + menu + ".yml";
            File f = new File(dir, menu + ".yml");
            if (!f.exists()) plugin.saveDefault(res);
            YamlConfiguration y = new YamlConfiguration();
            boolean valid = true;
            try {
                if (!f.exists()) throw new IOException("fichier absent et introuvable dans le jar");
                y.load(f);
            } catch (IOException | InvalidConfigurationException ex) {
                // fichier illisible : on utilise les valeurs par défaut sans toucher à ton fichier
                plugin.getLogger().severe("interfaces/" + menu + ".yml est illisible (" + ex.getMessage()
                        + ") : valeurs par défaut utilisées. Corrige le fichier puis /guild reload.");
                valid = false;
                y = defaults(res);
            }
            if (valid && YamlMerge.merge(plugin, res, y)) {
                try {
                    y.save(f);
                } catch (IOException ex) {
                    plugin.getLogger().warning("Impossible de mettre à jour interfaces/" + menu + ".yml : " + ex.getMessage());
                }
            }
            for (Map.Entry<String, Object> e : y.getValues(true).entrySet()) {
                if (!(e.getValue() instanceof ConfigurationSection)) all.set(menu + "." + e.getKey(), e.getValue());
            }
        }
        cfg = all;
    }

    private YamlConfiguration defaults(String resource) {
        var in = plugin.getResource(resource);
        if (in == null) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /** Ancien format : un seul interface.yml. Il est séparé en interfaces/*.yml (modifications conservées). */
    private void migrateLegacyFile() {
        File old = new File(plugin.getDataFolder(), "interface.yml");
        if (!old.exists()) return;
        YamlConfiguration oy = new YamlConfiguration();
        try {
            oy.load(old);
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().warning("interface.yml illisible, migration ignorée : " + ex.getMessage());
            return;
        }
        for (String menu : MENUS) {
            File f = new File(dir, menu + ".yml");
            ConfigurationSection s = oy.getConfigurationSection(menu);
            if (f.exists() || s == null) continue;
            YamlConfiguration ny = new YamlConfiguration();
            for (Map.Entry<String, Object> e : s.getValues(true).entrySet()) {
                if (!(e.getValue() instanceof ConfigurationSection)) ny.set(e.getKey(), e.getValue());
            }
            try {
                ny.save(f);
            } catch (IOException ex) {
                plugin.getLogger().warning("Migration de " + menu + " impossible : " + ex.getMessage());
            }
        }
        File backup = new File(plugin.getDataFolder(), "interface.yml.avant-separation");
        if (old.renameTo(backup)) {
            plugin.getLogger().info("Migration : interface.yml séparé en interfaces/*.yml (sauvegarde : interface.yml.avant-separation).");
        } else {
            plugin.getLogger().warning("interface.yml n'a pas pu être renommé : supprime-le à la main pour éviter toute confusion.");
        }
    }

    private static String rep(String s, String... kv) {
        if (s == null) return "";
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    private static final class Papi {
        static String set(Player p, String s) {
            try {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, s);
            } catch (Throwable t) {
                return s;
            }
        }
    }

    /** Variables {xxx} puis placeholders PlaceholderAPI (%xxx%) si disponible. */
    private String apply(String s, Player viewer, String... kv) {
        s = rep(s, kv);
        if (viewer != null && s.indexOf('%') >= 0 && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            s = Papi.set(viewer, s);
        }
        return s;
    }

    // ------------------------------------------------------------ inventaires
    public int size(String menu) {
        int s = cfg.getInt(menu + ".size", 54);
        s = Math.max(9, Math.min(54, s));
        return ((s + 8) / 9) * 9;
    }

    public Inventory inventory(InventoryHolder holder, String menu, String... kv) {
        Component title = Text.c(rep(cfg.getString(menu + ".title", menu), kv));
        Inventory inv = Bukkit.createInventory(holder, size(menu), title);
        fillBackground(inv, menu);
        return inv;
    }

    /** Remplit le fond (filler) puis pose les décorations (vitres...). À rappeler après inv.clear(). */
    public void fillBackground(Inventory inv, String menu) {
        ConfigurationSection f = cfg.getConfigurationSection(menu + ".filler");
        if (f != null && f.getBoolean("enabled", false)) {
            ItemStack it = build(f, Opts.NONE);
            for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, it);
        }
        ConfigurationSection decos = cfg.getConfigurationSection(menu + ".decorations");
        if (decos == null) return;
        for (String key : decos.getKeys(false)) {
            ConfigurationSection d = decos.getConfigurationSection(key);
            if (d == null || !d.getBoolean("enabled", true)) continue;
            ItemStack it = build(d, Opts.NONE);
            for (int slot : slots(menu + ".decorations." + key, "slots")) {
                if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, it);
            }
        }
    }

    // ---------------------------------------------------------------- lecture
    public String text(String menu, String key, String... kv) {
        return rep(cfg.getString(menu + ".texts." + key, ""), kv);
    }

    public List<String> stringList(String path) {
        return cfg.getStringList(path);
    }

    public int slot(String menu, String id) {
        return cfg.getInt(menu + ".items." + id + ".slot", -1);
    }

    /** Clé "permission" optionnelle d'un item (null = aucune). */
    public String itemString(String menu, String id, String key) {
        return cfg.getString(menu + ".items." + id + "." + key);
    }

    public List<String> itemIds(String menu) {
        ConfigurationSection s = cfg.getConfigurationSection(menu + ".items");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    public Material material(String path, Material def) {
        String m = cfg.getString(path);
        Material mat = m == null ? null : Material.matchMaterial(m.toUpperCase());
        return mat == null ? def : mat;
    }

    /** Slots d'une zone : "0-44", "10,12,14-16" ou liste YAML. */
    public List<Integer> slots(String menu, String key) {
        List<Integer> out = new ArrayList<>();
        String path = menu + "." + key;
        if (cfg.isList(path)) {
            out.addAll(cfg.getIntegerList(path));
            return out;
        }
        for (String part : cfg.getString(path, "").split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                if (part.contains("-")) {
                    String[] r = part.split("-");
                    for (int i = Integer.parseInt(r[0].trim()); i <= Integer.parseInt(r[1].trim()); i++) out.add(i);
                } else out.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    // ------------------------------------------------------------------ items
    /** Item configuré, ou null s'il n'existe pas / est désactivé (enabled: false). */
    public ItemStack item(String menu, String id, Boolean glow, String... kv) {
        return itemWith(menu, id, new Opts(glow, null, null), kv);
    }

    public ItemStack itemWith(String menu, String id, Opts opts, String... kv) {
        ConfigurationSection s = cfg.getConfigurationSection(menu + ".items." + id);
        if (s == null || !s.getBoolean("enabled", true)) return null;
        return build(s, opts, kv);
    }

    private ItemStack build(ConfigurationSection s, Opts o, String... kv) {
        Material m = o.material();
        if (m == null) m = Material.matchMaterial(s.getString("material", "STONE").toUpperCase());
        if (m == null || m.isAir()) m = Material.STONE;
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(64, s.getInt("amount", 1))));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.item(apply(s.getString("name", " "), o.viewer(), kv)));
        List<Component> lore = new ArrayList<>();
        for (String line : s.getStringList("lore")) {
            String r = apply(line, o.viewer(), kv);
            if (line.contains("{") && r.isBlank()) continue;
            for (String part : r.split("\n")) lore.add(Text.item(part));
        }
        meta.lore(lore.isEmpty() ? null : lore);
        if (o.glow() != null ? o.glow() : s.getBoolean("glow", false)) meta.setEnchantmentGlintOverride(true);
        if (s.contains("custom-model-data")) meta.setCustomModelData(s.getInt("custom-model-data"));
        if (s.getBoolean("hide-flags", true)) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
        }
        if (s.getBoolean("hide-tooltip", false)) meta.setHideTooltip(true);
        if (s.getBoolean("skull-viewer", false) && o.viewer() != null && meta instanceof SkullMeta sm) {
            sm.setOwningPlayer(o.viewer());
        }
        it.setItemMeta(meta);
        return it;
    }
}
