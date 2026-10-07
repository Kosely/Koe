package dev.koegoated.swiftguild.util;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Ajoute dans le fichier du serveur les clés manquantes présentes dans le fichier par défaut du jar. */
public final class YamlMerge {
    private YamlMerge() {}

    /** @return true si des clés ont été ajoutées */
    public static boolean merge(SwiftGuildPlugin plugin, String resource, YamlConfiguration user) {
        var in = plugin.getResource(resource);
        if (in == null) return false;
        YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        boolean changed = false;
        for (String key : def.getKeys(true)) {
            if (def.isConfigurationSection(key)) continue;
            if (blockedByScalar(user, key)) continue;
            if (!user.contains(key, true)) {
                user.set(key, def.get(key));
                changed = true;
            }
        }
        return changed;
    }

    /** Un parent déjà défini en valeur simple (ex. "message: false") empêche d'ajouter ses sous-clés. */
    private static boolean blockedByScalar(ConfigurationSection user, String key) {
        String[] parts = key.split("\\.");
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (i > 0) p.append('.');
            p.append(parts[i]);
            if (user.contains(p.toString(), true) && !user.isConfigurationSection(p.toString())) return true;
        }
        return false;
    }
}
