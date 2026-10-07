package dev.koegoated.swiftguild;

import dev.koegoated.swiftguild.command.GuildCommand;
import dev.koegoated.swiftguild.gui.CreateMenu;
import dev.koegoated.swiftguild.gui.GuildMenu;
import dev.koegoated.swiftguild.gui.MenuHolder;
import dev.koegoated.swiftguild.gui.WarMenu;
import dev.koegoated.swiftguild.listener.ChatListener;
import dev.koegoated.swiftguild.listener.CombatListener;
import dev.koegoated.swiftguild.listener.ConnectionListener;
import dev.koegoated.swiftguild.manager.*;
import dev.koegoated.swiftguild.placeholder.SwiftGuildExpansion;
import dev.koegoated.swiftguild.util.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class SwiftGuildPlugin extends JavaPlugin {
    private GuildManager guilds;
    private StatsManager stats;
    private DisplayManager display;
    private WarManager wars;
    private WarMenu warMenu;
    private CreateMenu createMenu;
    private GuildMenu guildMenu;
    private ChatListener chat;
    private Messages messages;
    private Interfaces ui;
    private GuildCommand command;

    @Override
    public void onEnable() {
        saveDefaultConfigSafe();
        messages = new Messages(this);
        messages.load();
        ui = new Interfaces(this);
        ui.load();

        guilds = new GuildManager(this);
        guilds.load();
        java.io.File[] guildFiles = new java.io.File(getDataFolder(), "guilds").listFiles((d, n) -> n.endsWith(".yml"));
        java.io.File[] uiFiles = new java.io.File(getDataFolder(), "interfaces").listFiles((d, n) -> n.endsWith(".yml"));
        getLogger().info("Dossier (v" + getPluginMeta().getVersion() + ") : " + getDataFolder().getAbsolutePath()
                + " | config.yml : " + new java.io.File(getDataFolder(), "config.yml").exists()
                + " | messages.yml : " + new java.io.File(getDataFolder(), "messages.yml").exists()
                + " | interfaces/ : " + (uiFiles == null ? 0 : uiFiles.length) + " fichier(s)"
                + " | guilds/ : " + (guildFiles == null ? 0 : guildFiles.length) + " fichier(s)"
                + " | " + guilds.guilds.size() + " guilde(s), " + guilds.alliances.size() + " alliance(s) chargée(s).");
        stats = new StatsManager(this, guilds);
        display = new DisplayManager(this, guilds);
        wars = new WarManager(this, guilds);
        wars.load();
        warMenu = new WarMenu(this, guilds, wars);
        createMenu = new CreateMenu(this);
        guildMenu = new GuildMenu(this);
        chat = new ChatListener(this, guilds, display);

        var pm = getServer().getPluginManager();
        pm.registerEvents(createMenu, this);   // avant le chat : capture la saisie du nom / tag
        pm.registerEvents(guildMenu, this);
        pm.registerEvents(chat, this);
        pm.registerEvents(new CombatListener(this, guilds, wars), this);
        pm.registerEvents(new ConnectionListener(this), this);
        pm.registerEvents(warMenu, this);

        PluginCommand cmd = getCommand("guild");
        if (cmd != null) {
            command = new GuildCommand(this);
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }

        if (pm.isPluginEnabled("PlaceholderAPI")) new SwiftGuildExpansion(this).register();

        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!guilds.isEnabled()) return;
            wars.tick();
            warMenu.tick();
        }, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            guilds.saveIfDirty();
            wars.save();
        }, 200L, 200L);

        // data.yml / wars.yml font foi : supprimés, modifiés ou restaurés à la main pendant que le serveur tourne, ils sont pris en compte en < 5 s
        getServer().getScheduler().runTaskTimer(this, this::checkExternalChanges, 100L, 100L);

        display.requestRefresh();
    }

    /**
     * Les fichiers data.yml / wars.yml font foi. S'ils sont supprimés, modifiés ou restaurés à la main
     * pendant que le serveur tourne, la mémoire est mise à jour (vidée ou relue) au lieu d'être réécrite
     * par-dessus avec les anciennes données.
     */
    private void checkExternalChanges() {
        // Fichiers de configuration supprimés à la main : recréés avec les valeurs par défaut
        boolean healed = false;
        if (!new java.io.File(getDataFolder(), "config.yml").exists()) {
            saveDefaultConfigSafe();
            reloadConfig();
            healed = true;
        }
        if (messages.fileMissing()) {
            messages.load();
            healed = true;
        }
        if (ui.anyMissing()) {
            ui.load();
            healed = true;
        }
        if (healed) getLogger().info("Fichier(s) de configuration manquant(s) : recréé(s) avec les valeurs par défaut.");

        guilds.applyExternalChanges();
        boolean changed = guilds.consumeExternalChange();
        if (wars.externallyDeleted()) {
            wars.wipe();
            changed = true;
        } else if (wars.externallyModified() && wars.reloadFromDisk()) {
            changed = true;
        }
        if (!changed) return;
        wars.cleanup(guilds);
        chat.clear();
        getServer().getOnlinePlayers().forEach(pl -> {
            if (pl.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) pl.closeInventory();
        });
        stats.clearCache();
        display.requestRefresh();
        guilds.save();
        wars.save();
    }

    @Override
    public void onDisable() {
        if (display != null) display.clearAll();
        if (guilds != null) guilds.save();
        if (wars != null) wars.save();
    }

    public void reload() {
        saveDefaultConfigSafe();
        reloadConfig();
        messages.load();
        ui.load();
        checkExternalChanges(); // applique aussi les modifications faites à la main dans guilds/, data.yml et wars.yml
        stats.clearCache();
        display.requestRefresh();
    }

    /** Copie une ressource du jar dans le dossier du plugin (sans écraser). Message clair si le jar ne la contient pas. */
    public boolean saveDefault(String path) {
        if (getResource(path) == null) {
            getLogger().severe("Le jar ne contient pas '" + path + "' : reconstruis le plugin avec 'mvn clean package' "
                    + "(le dossier src/main/resources doit être inclus).");
            return false;
        }
        saveResource(path, false);
        return true;
    }

    /** Crée config.yml s'il est absent, sans jamais faire planter le démarrage. */
    private void saveDefaultConfigSafe() {
        if (!new java.io.File(getDataFolder(), "config.yml").exists()) saveDefault("config.yml");
    }

    /** Active/désactive proprement le système. */
    public void setSystemEnabled(boolean v) {
        checkExternalChanges();
        guilds.setEnabled(v);
        if (!v) {
            chat.clear();
            guilds.clearInvites();
            display.clearAll();
            getServer().getOnlinePlayers().forEach(p -> {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder) p.closeInventory();
            });
        } else {
            display.requestRefresh();
        }
        guilds.save();
    }

    // ------------------------------------------------------------- messages
    /** Message sans préfixe, ou null s'il est désactivé dans messages.yml. */
    public String raw(String key, String... kv) {
        return messages.get(key, kv);
    }

    /** Alias de {@link #raw}. */
    public String text(String key, String... kv) {
        return messages.get(key, kv);
    }

    public List<String> list(String key, String... kv) {
        return messages.list(key, kv);
    }

    /** Envoie le message avec préfixe (rien n'est envoyé s'il est désactivé). */
    public void send(CommandSender to, String key, String... kv) {
        String m = messages.get(key, kv);
        if (m == null) return;
        String pre = messages.get("prefix");
        to.sendMessage(Text.c((pre == null ? "" : pre) + m));
    }

    /** Envoie le message sans préfixe. */
    public void sendRaw(CommandSender to, String key, String... kv) {
        String m = messages.get(key, kv);
        if (m != null) to.sendMessage(Text.c(m));
    }

    public String onOff(boolean v) {
        String s = messages.get(v ? "value-on" : "value-off");
        return s == null ? (v ? "ON" : "OFF") : s;
    }

    public String perm(String key) {
        return getConfig().getString("permissions." + key, "swiftguild." + key);
    }

    public GuildManager guilds() { return guilds; }
    public StatsManager stats() { return stats; }
    public DisplayManager display() { return display; }
    public WarManager wars() { return wars; }
    public WarMenu warMenu() { return warMenu; }
    public CreateMenu createMenu() { return createMenu; }
    public GuildMenu guildMenu() { return guildMenu; }
    public ChatListener chat() { return chat; }
    public Messages messages() { return messages; }
    public Interfaces ui() { return ui; }
    public GuildCommand command() { return command; }
}
