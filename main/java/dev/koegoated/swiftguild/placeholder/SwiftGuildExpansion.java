package dev.koegoated.swiftguild.placeholder;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.manager.StatsManager;
import dev.koegoated.swiftguild.model.Guild;
import dev.koegoated.swiftguild.util.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;

/** %swiftguild_...% */
public final class SwiftGuildExpansion extends PlaceholderExpansion {
    private final SwiftGuildPlugin plugin;

    public SwiftGuildExpansion(SwiftGuildPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "swiftguild"; }
    @Override public String getAuthor() { return "koegoated"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (!plugin.guilds().isEnabled()) return "";
        String none = plugin.getConfig().getString("placeholders.no-guild", "-");
        String empty = plugin.getConfig().getString("placeholders.empty-rank", "-");
        String p = params.toLowerCase();

        if (p.startsWith("alliance_ranking_")) {
            String[] parts = p.substring("alliance_ranking_".length()).split("_", 2);
            Integer pos = pos(parts);
            if (pos == null) return null;
            List<StatsManager.RankedAlliance> r = plugin.stats().allianceRanking();
            if (pos < 1 || pos > r.size()) return empty;
            var x = r.get(pos - 1);
            return switch (parts[1]) {
                case "name" -> x.alliance().name;
                case "members" -> Text.num(x.agg().members());
                default -> agg(x.agg(), parts[1]);
            };
        }
        if (p.startsWith("ranking_")) {
            String[] parts = p.substring("ranking_".length()).split("_", 2);
            Integer pos = pos(parts);
            if (pos == null) return null;
            List<StatsManager.RankedGuild> r = plugin.stats().guildRanking();
            if (pos < 1 || pos > r.size()) return empty;
            var x = r.get(pos - 1);
            return switch (parts[1]) {
                case "name" -> x.guild().name;
                case "tag" -> x.guild().tag;
                case "members" -> Text.num(x.agg().members());
                default -> agg(x.agg(), parts[1]);
            };
        }

        if (player == null) return "";
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) return none;
        var info = plugin.guilds().info(g, player.getUniqueId());
        if (info.containsKey(p)) return info.get(p);
        return agg(plugin.stats().guild(g), p);
    }

    private Integer pos(String[] parts) {
        if (parts.length != 2) return null;
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String agg(StatsManager.Agg a, String field) {
        return switch (field) {
            case "active" -> Text.num(a.active());
            case "kills" -> Text.num(a.kills());
            case "deaths" -> Text.num(a.deaths());
            case "kdr" -> Text.kdr(a.kdr());
            case "playtime" -> Text.hours(a.seconds());
            default -> null;
        };
    }
}
