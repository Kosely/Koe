package dev.koegoated.swiftguild.manager;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.model.Alliance;
import dev.koegoated.swiftguild.model.Guild;
import dev.koegoated.swiftguild.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.*;

/** Tags de Guild / Alliance, ennemis en rouge, glowing. */
public final class DisplayManager {
    private final SwiftGuildPlugin plugin;
    private final GuildManager gm;
    private final Set<UUID> glowed = new HashSet<>();
    private boolean pending;

    public DisplayManager(SwiftGuildPlugin plugin, GuildManager gm) {
        this.plugin = plugin;
        this.gm = gm;
    }

    public boolean isEnemy(Player viewer, Player target) {
        if (viewer == null || target == null) return false;
        Guild vg = gm.guildOf(viewer.getUniqueId());
        Guild tg = gm.guildOf(target.getUniqueId());
        return vg != null && tg != null && vg.enemies.contains(tg.id);
    }

    /** Préfixe "[SWIFT] [ALLY] " (vide si pas de guilde). */
    public Component prefix(Player target, Player viewer) {
        Guild g = gm.guildOf(target.getUniqueId());
        if (g == null) return Component.empty();
        var cfg = plugin.getConfig();
        boolean enemy = cfg.getBoolean("tags.enemy-tag-red", true) && isEnemy(viewer, target);
        String gf = enemy ? cfg.getString("tags.enemy-format", "&c[{tag}]&r")
                : cfg.getString("tags.guild-format", "&b[{tag}]&r");
        StringBuilder sb = new StringBuilder(gf.replace("{tag}", g.tag).replace("{color}", "&" + g.tagColor)).append(" ");
        Alliance a = gm.allianceOf(g);
        if (a != null && gm.showAllianceTag(target.getUniqueId())) {
            sb.append(cfg.getString("tags.alliance-format", "&d[{tag}]&r").replace("{tag}", a.tag)).append(" ");
        }
        return Text.c(sb.toString());
    }

    // --------------------------------------------------------------- refresh
    public void requestRefresh() {
        if (pending) return;
        pending = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            pending = false;
            refreshAll();
        });
    }

    private String teamName(Player p) {
        return "sg" + Integer.toHexString(p.getUniqueId().hashCode());
    }

    private Scoreboard board(Player viewer) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        if (viewer.getScoreboard() == main) viewer.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        return viewer.getScoreboard();
    }

    public void refreshAll() {
        if (!gm.isEnabled()) {
            clearAll();
            return;
        }
        var cfg = plugin.getConfig();
        boolean nametag = cfg.getBoolean("tags.nametag", true);
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        if (nametag) {
            Set<String> valid = new HashSet<>();
            for (Player t : online) valid.add(teamName(t));
            for (Player viewer : online) {
                Scoreboard sb = board(viewer);
                for (Team team : new ArrayList<>(sb.getTeams()))
                    if (team.getName().startsWith("sg") && !valid.contains(team.getName())) team.unregister();
                for (Player target : online) {
                    Guild tg = gm.guildOf(target.getUniqueId());
                    String tn = teamName(target);
                    Team team = sb.getTeam(tn);
                    if (tg == null) {
                        if (team != null) team.unregister();
                        continue;
                    }
                    if (team == null) team = sb.registerNewTeam(tn);
                    boolean enemy = cfg.getBoolean("tags.enemy-tag-red", true) && isEnemy(viewer, target);
                    team.prefix(prefix(target, viewer));
                    team.color(enemy ? NamedTextColor.RED : NamedTextColor.WHITE);
                    if (!team.hasEntry(target.getName())) team.addEntry(target.getName());
                }
            }
        } else {
            removeTeams();
        }
        updateGlowing(online);
    }

    private void updateGlowing(Collection<? extends Player> online) {
        Set<UUID> shouldGlow = new HashSet<>();
        if (plugin.getConfig().getBoolean("tags.enemy-glowing", true)) {
            Set<UUID> enemyGuilds = new HashSet<>();
            for (Player p : online) {
                Guild g = gm.guildOf(p.getUniqueId());
                if (g != null) enemyGuilds.addAll(g.enemies);
            }
            for (Player p : online) {
                Guild g = gm.guildOf(p.getUniqueId());
                if (g != null && enemyGuilds.contains(g.id)) shouldGlow.add(p.getUniqueId());
            }
        }
        for (Player p : online) {
            if (shouldGlow.contains(p.getUniqueId())) {
                p.setGlowing(true);
                glowed.add(p.getUniqueId());
            } else if (glowed.remove(p.getUniqueId())) {
                p.setGlowing(false);
            }
        }
        glowed.removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    private void removeTeams() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = viewer.getScoreboard();
            for (Team team : new ArrayList<>(sb.getTeams()))
                if (team.getName().startsWith("sg")) team.unregister();
        }
    }

    /** Retire tout ce que le plugin a posé (désactivation / arrêt). */
    public void clearAll() {
        removeTeams();
        for (UUID u : new ArrayList<>(glowed)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.setGlowing(false);
        }
        glowed.clear();
    }
}
