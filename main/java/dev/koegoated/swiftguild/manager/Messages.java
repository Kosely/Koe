package dev.koegoated.swiftguild.manager;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.util.YamlMerge;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * messages.yml : tous les messages du plugin.
 * Un message est désactivé si sa valeur est {@code false} ou s'il est écrit en section avec {@code deny: true}.
 * Une clé supprimée du fichier est automatiquement rajoutée (valeur par défaut) au prochain chargement.
 */
public final class Messages {
    private final SwiftGuildPlugin plugin;
    private final File file;
    private YamlConfiguration cfg = new YamlConfiguration();

    public Messages(SwiftGuildPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
    }

    /** messages.yml a disparu (supprimé à la main). */
    public boolean fileMissing() {
        return !file.exists();
    }

    public void load() {
        if (!file.exists()) plugin.saveDefault("messages.yml");
        cfg = YamlConfiguration.loadConfiguration(file);
        if (YamlMerge.merge(plugin, "messages.yml", cfg)) {
            try {
                cfg.save(file);
            } catch (IOException ex) {
                plugin.getLogger().warning("Impossible de mettre à jour messages.yml : " + ex.getMessage());
            }
        }
    }

    /** Valeur brute du message, ou null s'il est désactivé. */
    private Object value(String key) {
        Object o = cfg.get(key);
        if (o instanceof ConfigurationSection s) {
            if (s.getBoolean("deny", false)) return null;
            o = s.get("text");
        }
        if (o == null || (o instanceof Boolean b && !b)) return null;
        return o;
    }

    private String rep(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    /** Message texte, ou null s'il est désactivé / vide. */
    public String get(String key, String... kv) {
        Object o = value(key);
        if (o == null) return null;
        String s = o instanceof List<?> l ? String.join("\n", l.stream().map(String::valueOf).toList()) : String.valueOf(o);
        return s.isEmpty() ? null : rep(s, kv);
    }

    /** Message multi-lignes ; liste vide s'il est désactivé. */
    public List<String> list(String key, String... kv) {
        List<String> out = new ArrayList<>();
        Object o = value(key);
        if (o == null) return out;
        if (o instanceof List<?> l) for (Object x : l) out.add(rep(String.valueOf(x), kv));
        else out.add(rep(String.valueOf(o), kv));
        return out;
    }
}
