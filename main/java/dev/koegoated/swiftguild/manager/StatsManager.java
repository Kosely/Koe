package dev.koegoated.swiftguild.manager;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.model.Alliance;
import dev.koegoated.swiftguild.model.Guild;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Statistiques agrégées (joueurs -> guildes -> alliances) et classements. */
public final class StatsManager {
    public record PStats(long kills, long deaths, long seconds) {}

    public record Agg(int guilds, int members, int active, long kills, long deaths, long seconds) {
        public double kdr() { return (double) kills / Math.max(1, deaths); }
    }

    public record RankedGuild(Guild guild, Agg agg) {}
    public record RankedAlliance(Alliance alliance, Agg agg) {}

    private record Cached(PStats stats, long at) {}

    private final SwiftGuildPlugin plugin;
    private final GuildManager gm;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private List<RankedGuild> guildRanking = List.of();
    private List<RankedAlliance> allianceRanking = List.of();
    private long guildRankingAt, allianceRankingAt;

    public StatsManager(SwiftGuildPlugin plugin, GuildManager gm) {
        this.plugin = plugin;
        this.gm = gm;
    }

    public void clearCache() {
        cache.clear();
        guildRankingAt = 0;
        allianceRankingAt = 0;
    }

    // ---------------------------------------------------------------- joueur
    public PStats player(UUID id) {
        long ttl = plugin.getConfig().getLong("stats.cache-seconds", 30) * 1000L;
        Cached c = cache.get(id);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < ttl) return c.stats();
        PStats s = load(id);
        cache.put(id, new Cached(s, now));
        return s;
    }

    private PStats load(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        String source = plugin.getConfig().getString("stats.source", "SWIFTPVP_STATS").toUpperCase(Locale.ROOT);
        if (source.equals("SWIFTPVP_STATS")
                && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                && Bukkit.getPluginManager().isPluginEnabled("SwiftPvP-Stats")) {
            PStats s = Papi.read(plugin, op);
            if (s != null) return s;
        }
        return vanilla(op);
    }

    private PStats vanilla(OfflinePlayer op) {
        try {
            long k = op.getStatistic(Statistic.PLAYER_KILLS);
            long d = op.getStatistic(Statistic.DEATHS);
            long t = op.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
            return new PStats(k, d, t);
        } catch (Exception ex) {
            return new PStats(0, 0, 0);
        }
    }

    /** Isolé pour ne charger PlaceholderAPI que s'il est présent. */
    private static final class Papi {
        static PStats read(SwiftGuildPlugin plugin, OfflinePlayer op) {
            try {
                var cfg = plugin.getConfig();
                Double k = num(me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(op, cfg.getString("stats.placeholders.kills", "")));
                Double d = num(me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(op, cfg.getString("stats.placeholders.deaths", "")));
                Double t = num(me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(op, cfg.getString("stats.placeholders.playtime", "")));
                if (k == null || d == null || t == null) return null;
                double mult = switch (cfg.getString("stats.playtime-unit", "SECONDS").toUpperCase(Locale.ROOT)) {
                    case "TICKS" -> 1.0 / 20.0;
                    case "MINUTES" -> 60;
                    case "HOURS" -> 3600;
                    default -> 1;
                };
                return new PStats(k.longValue(), d.longValue(), (long) (t * mult));
            } catch (Throwable ex) {
                return null;
            }
        }

        static Double num(String s) {
            if (s == null) return null;
            try {
                return Double.parseDouble(s.trim().replace(",", "."));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
    }

    public boolean isActive(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        if (op.isOnline()) return true;
        long last = op.getLastSeen();
        long days = plugin.getConfig().getLong("stats.active-days", 7);
        return last > 0 && System.currentTimeMillis() - last <= days * 86_400_000L;
    }

    // -------------------------------------------------------------- agrégats
    public Agg guild(Guild g) {
        int active = 0;
        long k = 0, d = 0, s = 0;
        for (UUID u : g.members.keySet()) {
            PStats p = player(u);
            k += p.kills();
            d += p.deaths();
            s += p.seconds();
            if (isActive(u)) active++;
        }
        return new Agg(1, g.members.size(), active, k, d, s);
    }

    public Agg alliance(Alliance a) {
        int guilds = 0, members = 0, active = 0;
        long k = 0, d = 0, s = 0;
        for (UUID gid : a.guilds) {
            Guild g = gm.guilds.get(gid);
            if (g == null) continue;
            Agg x = guild(g);
            guilds++;
            members += x.members();
            active += x.active();
            k += x.kills();
            d += x.deaths();
            s += x.seconds();
        }
        return new Agg(guilds, members, active, k, d, s);
    }

    // ------------------------------------------------------------ classements
    private Comparator<Agg> comparator() {
        String key = plugin.getConfig().getString("ranking.sort-by", "kills").toLowerCase(Locale.ROOT);
        Comparator<Agg> c = switch (key) {
            case "kdr" -> Comparator.comparingDouble(Agg::kdr);
            case "playtime" -> Comparator.comparingLong(Agg::seconds);
            case "deaths" -> Comparator.comparingLong(Agg::deaths);
            default -> Comparator.comparingLong(Agg::kills);
        };
        return c.reversed();
    }

    public synchronized List<RankedGuild> guildRanking() {
        long ttl = plugin.getConfig().getLong("ranking.cache-seconds", 10) * 1000L;
        if (System.currentTimeMillis() - guildRankingAt > ttl) {
            List<RankedGuild> l = new ArrayList<>();
            for (Guild g : gm.guilds.values()) l.add(new RankedGuild(g, guild(g)));
            Comparator<Agg> c = comparator();
            l.sort((x, y) -> c.compare(x.agg(), y.agg()));
            guildRanking = l;
            guildRankingAt = System.currentTimeMillis();
        }
        return guildRanking;
    }

    public synchronized List<RankedAlliance> allianceRanking() {
        long ttl = plugin.getConfig().getLong("ranking.cache-seconds", 10) * 1000L;
        if (System.currentTimeMillis() - allianceRankingAt > ttl) {
            List<RankedAlliance> l = new ArrayList<>();
            for (Alliance a : gm.alliances.values()) l.add(new RankedAlliance(a, alliance(a)));
            Comparator<Agg> c = comparator();
            l.sort((x, y) -> c.compare(x.agg(), y.agg()));
            allianceRanking = l;
            allianceRankingAt = System.currentTimeMillis();
        }
        return allianceRanking;
    }
}
