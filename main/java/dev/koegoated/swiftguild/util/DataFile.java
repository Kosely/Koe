package dev.koegoated.swiftguild.util;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Fichier de données dont le contenu sur disque fait foi : le plugin sait détecter qu'il a été
 * supprimé, modifié ou restauré à la main pendant que le serveur tourne, et n'écrase jamais
 * un changement externe avec sa mémoire.
 */
public final class DataFile {
    private final SwiftGuildPlugin plugin;
    private final File file;
    private volatile boolean known;   // lu ou écrit au moins une fois par le plugin
    private volatile long stamp;
    private volatile long size;

    public DataFile(SwiftGuildPlugin plugin, File file) {
        this.plugin = plugin;
        this.file = file;
    }

    private void markSeen() {
        known = true;
        stamp = file.lastModified();
        size = file.length();
    }

    /** Oublie l'état connu (après une remise à zéro). */
    public void forget() {
        known = false;
    }

    /** Le fichier existait (lu/écrit par nous) et a disparu. */
    public boolean wasDeleted() {
        return known && !file.exists();
    }

    /** Le fichier existe mais n'est plus celui que nous avons lu/écrit en dernier. */
    public boolean wasModified() {
        return known && file.exists() && (file.lastModified() != stamp || file.length() != size);
    }

    /** Lit le fichier. null s'il n'existe pas ou s'il est illisible (une copie .broken-xxx est alors faite). */
    public YamlConfiguration read() {
        if (!file.exists()) return null;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.load(file);
            markSeen();
            return y;
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().severe(file.getName() + " est illisible (" + ex.getMessage()
                    + "). Une copie de sauvegarde .broken-xxx a été créée, le fichier sera réécrit.");
            try {
                Files.copy(file.toPath(), new File(file.getParentFile(), file.getName() + ".broken-" + System.currentTimeMillis()).toPath());
            } catch (IOException ignored) {}
            markSeen();
            return null;
        }
    }

    /** Écriture atomique (fichier temporaire puis déplacement) : jamais de fichier à moitié écrit. */
    public boolean write(YamlConfiguration y) {
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(dir, file.getName() + ".tmp");
            y.save(tmp);
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            markSeen();
            return true;
        } catch (IOException ex) {
            plugin.getLogger().severe("Impossible d'écrire " + file.getName() + " : " + ex.getMessage());
            return false;
        }
    }
}
