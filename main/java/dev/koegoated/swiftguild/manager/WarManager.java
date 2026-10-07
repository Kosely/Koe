package dev.koegoated.swiftguild.manager;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.model.Alliance;
import dev.koegoated.swiftguild.model.Guild;
import dev.koegoated.swiftguild.model.War;
import dev.koegoated.swiftguild.util.DataFile;
import dev.koegoated.swiftguild.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Guerres : démarrage, points (kills uniquement), fin, persistance (wars.yml). */
public final class WarManager {
    private static final int MAX_ENDED_KEPT = 20;

    private final SwiftGuildPlugin plugin;
    private final GuildManager gm;
    private final File file;
    public final Map<UUID, War> wars = new LinkedHashMap<>();
    private DataFile df;

    /** wars.yml a été supprimé à la main pendant que le serveur tournait. */
    public boolean externallyDeleted() { return df.wasDeleted(); }

    /** wars.yml a été modifié / remplacé à la main pendant que le serveur tournait. */
    public boolean externallyModified() { return df.wasModified(); }

    public synchronized void wipe() {
        wars.clear();
        df.forget();
    }

    /** Termine (égalité) les guerres actives dont un camp n'a plus aucune guilde existante (ex. fichier de guilde supprimé). */
    public synchronized void cleanup(GuildManager gm) {
        for (War war : new ArrayList<>(wars.values())) {
            if (!war.active) continue;
            boolean a = false, b = false;
            for (UUID g : war.sideA) if (gm.guilds.containsKey(g)) a = true;
            for (UUID g : war.sideB) if (gm.guilds.containsKey(g)) b = true;
            if (!a || !b) {
                war.winner = 3;
                end(war);
            }
        }
    }

    public synchronized boolean reloadFromDisk() {
        YamlConfiguration y = df.read();
        if (y == null) return false;
        populate(y);
        return true;
    }

    public WarManager(SwiftGuildPlugin plugin, GuildManager gm) {
        this.plugin = plugin;
        this.gm = gm;
        this.file = new File(plugin.getDataFolder(), "wars.yml");
        this.df = new DataFile(plugin, file);
    }

    public War activeWarOf(UUID guild) {
        for (War w : wars.values()) if (w.active && w.involves(guild)) return w;
        return null;
    }

    /** Guerres d'une guilde : actives d'abord, puis terminées (récentes d'abord). */
    public List<War> warsOf(UUID guild) {
        List<War> active = new ArrayList<>(), ended = new ArrayList<>();
        for (War w : wars.values()) if (w.involves(guild)) (w.active ? active : ended).add(w);
        ended.sort((a, b) -> Long.compare(b.startedAt, a.startedAt));
        active.addAll(ended);
        return active;
    }

    /** @return null si la guerre a démarré, sinon la clé du message d'erreur. */
    public String start(Player starter, boolean asAlliance, Collection<UUID> guildTargets,
                        Collection<UUID> allianceTargets, int minutes, int objective) {
        UUID uid = starter.getUniqueId();
        Guild mine = gm.guildOf(uid);
        if (mine == null) return "no-guild";
        if (!gm.guildPerm(uid, "START_WAR")) return "no-role-permission";
        Alliance myAlliance = gm.allianceOf(mine);

        LinkedHashSet<UUID> a = new LinkedHashSet<>(), b = new LinkedHashSet<>();
        if (asAlliance && myAlliance != null) a.addAll(myAlliance.guilds); else a.add(mine.id);
        for (UUID g : guildTargets) if (gm.guilds.containsKey(g)) b.add(g);
        for (UUID al : allianceTargets) {
            Alliance x = gm.alliances.get(al);
            if (x != null) b.addAll(x.guilds);
        }
        if (b.isEmpty()) return "war-err-no-target";
        for (UUID g : b) if (a.contains(g)) return "war-err-overlap";
        for (UUID g : a) if (activeWarOf(g) != null) return "war-err-busy";
        for (UUID g : b) if (activeWarOf(g) != null) return "war-err-busy";

        War w = new War(UUID.randomUUID());
        w.sideA.addAll(a);
        w.sideB.addAll(b);
        for (UUID g : a) w.tags.put(g, gm.guilds.get(g).tag);
        for (UUID g : b) w.tags.put(g, gm.guilds.get(g).tag);
        w.pointsPerKill = plugin.getConfig().getInt("war.points-per-kill", 10);
        w.objective = objective;
        w.durationMinutes = minutes;
        w.secondsLeft = minutes * 60L;
        wars.put(w.id, w);
        broadcast(w, "war-started", "a", w.label(w.sideA), "b", w.label(w.sideB),
                "objective", Text.num(objective), "minutes", String.valueOf(minutes));
        save();
        return null;
    }

    /** Appelé chaque seconde (uniquement quand le système est activé). */
    public void tick() {
        for (War w : new ArrayList<>(wars.values())) {
            if (!w.active) continue;
            if (--w.secondsLeft <= 0) end(w);
        }
    }

    public void handleKill(Player victim, Player killer) {
        if (killer == null || killer.equals(victim)) return;
        Guild gv = gm.guildOf(victim.getUniqueId()), gk = gm.guildOf(killer.getUniqueId());
        if (gv == null || gk == null) return;
        for (War w : wars.values()) {
            if (!w.active) continue;
            boolean kA = w.sideA.contains(gk.id), kB = w.sideB.contains(gk.id);
            boolean vA = w.sideA.contains(gv.id), vB = w.sideB.contains(gv.id);
            if (!((kA && vB) || (kB && vA))) continue;
            w.stat(gk.id)[0]++;
            w.stat(gv.id)[1]++;
            List<UUID> side = kA ? w.sideA : w.sideB;
            if (w.sidePoints(side) >= w.objective) {
                w.winner = kA ? 1 : 2;
                end(w);
            }
            return;
        }
    }

    public void end(War w) {
        if (!w.active) return;
        w.active = false;
        if (w.winner == 0) {
            int pa = w.sidePoints(w.sideA), pb = w.sidePoints(w.sideB);
            w.winner = pa > pb ? 1 : pb > pa ? 2 : 3;
        }
        if (w.winner == 3) {
            broadcast(w, "war-ended-draw", "a", w.label(w.sideA), "b", w.label(w.sideB));
        } else {
            List<UUID> win = w.winner == 1 ? w.sideA : w.sideB;
            List<UUID> lose = w.winner == 1 ? w.sideB : w.sideA;
            broadcast(w, "war-ended-win", "winner", w.label(win), "loser", w.label(lose),
                    "points", Text.num(w.sidePoints(win)));
        }
        List<War> ended = new ArrayList<>();
        for (War x : wars.values()) if (!x.active) ended.add(x);
        ended.sort((a, b) -> Long.compare(a.startedAt, b.startedAt));
        while (ended.size() > MAX_ENDED_KEPT) wars.remove(ended.remove(0).id);
        save();
    }

    public void broadcast(War w, String key, String... kv) {
        Set<UUID> all = new LinkedHashSet<>(w.sideA);
        all.addAll(w.sideB);
        for (UUID gid : all) {
            Guild g = gm.guilds.get(gid);
            if (g == null) continue;
            for (UUID m : g.members.keySet()) {
                Player p = Bukkit.getPlayer(m);
                if (p != null) plugin.send(p, key, kv);
            }
        }
    }

    // ---------------------------------------------------------- persistance
    public synchronized void save() {
        if (df.wasDeleted()) {
            wipe();
            return;
        }
        if (df.wasModified()) {
            reloadFromDisk();
            return;
        }
        YamlConfiguration y = new YamlConfiguration();
        for (War w : wars.values()) {
            String b = "wars." + w.id + ".";
            y.set(b + "side-a", w.sideA.stream().map(UUID::toString).toList());
            y.set(b + "side-b", w.sideB.stream().map(UUID::toString).toList());
            for (Map.Entry<UUID, String> e : w.tags.entrySet()) y.set(b + "tags." + e.getKey(), e.getValue());
            for (Map.Entry<UUID, int[]> e : w.stats.entrySet()) {
                y.set(b + "stats." + e.getKey() + ".kills", e.getValue()[0]);
                y.set(b + "stats." + e.getKey() + ".deaths", e.getValue()[1]);
            }
            y.set(b + "points-per-kill", w.pointsPerKill);
            y.set(b + "objective", w.objective);
            y.set(b + "duration-minutes", w.durationMinutes);
            y.set(b + "seconds-left", w.secondsLeft);
            y.set(b + "active", w.active);
            y.set(b + "winner", w.winner);
            y.set(b + "started-at", w.startedAt);
        }
        df.write(y);
    }

    public synchronized void load() {
        YamlConfiguration y = df.read();
        if (y == null) return;
        populate(y);
    }

    private void populate(YamlConfiguration y) {
        wars.clear();
        ConfigurationSection ws = y.getConfigurationSection("wars");
        if (ws == null) return;
        for (String k : ws.getKeys(false)) {
            try {
                ConfigurationSection s = ws.getConfigurationSection(k);
                if (s == null) continue;
                War w = new War(UUID.fromString(k));
                for (String u : s.getStringList("side-a")) w.sideA.add(UUID.fromString(u));
                for (String u : s.getStringList("side-b")) w.sideB.add(UUID.fromString(u));
                ConfigurationSection ts = s.getConfigurationSection("tags");
                if (ts != null) for (String u : ts.getKeys(false)) w.tags.put(UUID.fromString(u), ts.getString(u, "?"));
                ConfigurationSection ss = s.getConfigurationSection("stats");
                if (ss != null) for (String u : ss.getKeys(false))
                    w.stats.put(UUID.fromString(u), new int[]{ss.getInt(u + ".kills"), ss.getInt(u + ".deaths")});
                w.pointsPerKill = s.getInt("points-per-kill", 10);
                w.objective = s.getInt("objective", 1000);
                w.durationMinutes = s.getInt("duration-minutes", 60);
                w.secondsLeft = s.getLong("seconds-left", 0);
                w.active = s.getBoolean("active");
                w.winner = s.getInt("winner");
                w.startedAt = s.getLong("started-at", System.currentTimeMillis());
                wars.put(w.id, w);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Guerre invalide ignorée : " + k);
            }
        }
    }
}
