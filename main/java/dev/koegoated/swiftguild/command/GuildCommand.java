package dev.koegoated.swiftguild.command;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.listener.ChatListener;
import dev.koegoated.swiftguild.manager.GuildManager;
import dev.koegoated.swiftguild.manager.StatsManager;
import dev.koegoated.swiftguild.model.Alliance;
import dev.koegoated.swiftguild.model.Guild;
import dev.koegoated.swiftguild.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;

/** Unique commande : /guild (aucun alias). */
public final class GuildCommand implements CommandExecutor, TabCompleter {
    private final SwiftGuildPlugin pl;
    private final GuildManager gm;

    public GuildCommand(SwiftGuildPlugin pl) {
        this.pl = pl;
        this.gm = pl.guilds();
    }

    // =================================================================== main
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission(pl.perm("use"))) { pl.send(s, "no-permission"); return true; }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                if (!s.hasPermission(pl.perm("reload"))) { pl.send(s, "no-permission"); return true; }
                pl.reload();
                pl.send(s, "reloaded");
                return true;
            }
            case "enable" -> {
                if (!s.hasPermission(pl.perm("enable"))) { pl.send(s, "no-permission"); return true; }
                if (gm.isEnabled()) { pl.send(s, "already-enabled"); return true; }
                pl.setSystemEnabled(true);
                pl.send(s, "enabled");
                return true;
            }
            case "disable" -> {
                if (!s.hasPermission(pl.perm("disable"))) { pl.send(s, "no-permission"); return true; }
                if (!gm.isEnabled()) { pl.send(s, "already-disabled"); return true; }
                pl.setSystemEnabled(false);
                pl.send(s, "disabled-done");
                return true;
            }
            default -> {}
        }

        if (!gm.isEnabled()) { pl.send(s, "disabled"); return true; }

        // /guild seul : interface de création (si pas de guilde), sinon l'aide
        if (a.length == 0) {
            if (s instanceof Player p0) {
                if (gm.guildOf(p0.getUniqueId()) == null) pl.createMenu().open(p0);
                else pl.guildMenu().open(p0);
            } else help(s);
            return true;
        }

        // stats avec argument : utilisable par la console
        if (sub.equals("stats") && a.length >= 2 && !(s instanceof Player)) {
            Guild g = gm.guildByName(a[1]);
            if (g == null) pl.send(s, "guild-not-found"); else guildStats(s, g);
            return true;
        }
        if (!(s instanceof Player p)) { pl.send(s, "player-only"); return true; }

        switch (sub) {
            case "create" -> create(p, a);
            case "disband" -> disband(p);
            case "leave" -> leave(p);
            case "invite" -> invite(p, a);
            case "accept" -> accept(p);
            case "kick" -> kick(p, a);
            case "stats" -> stats(p, a);
            case "callout" -> callout(p, a);
            case "chat" -> chat(p, a, ChatListener.Mode.GUILD);
            case "role" -> roles(p, Arrays.copyOfRange(a, 1, a.length), false);
            case "alliance" -> alliance(p, a);
            case "enemy" -> enemy(p, a);
            case "protect" -> protect(p, a);
            case "war" -> {
                if (gm.guildOf(p.getUniqueId()) == null) pl.send(p, "no-guild");
                else pl.warMenu().open(p);
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        for (String l : pl.list("help")) s.sendMessage(Text.c(l));
    }

    /** Ligne sans préfixe (rien si le message est désactivé). */
    private void line(CommandSender s, String key, String... kv) {
        String m = pl.text(key, kv);
        if (m != null) s.sendMessage(Text.c(m));
    }

    /** key = suffixe de la clé "usage-<key>" dans messages.yml. */
    private void usage(CommandSender s, String key) {
        String u = pl.text("usage-" + key);
        if (u != null) pl.send(s, "usage", "usage", u);
    }

    /** Permission de rôle Guild. */
    private boolean need(Player p, String perm) {
        if (gm.guildPerm(p.getUniqueId(), perm)) return true;
        pl.send(p, "no-role-permission");
        return false;
    }

    private Guild myGuild(Player p) {
        Guild g = gm.guildOf(p.getUniqueId());
        if (g == null) pl.send(p, "no-guild");
        return g;
    }

    private void refresh() {
        gm.markDirty();
        pl.display().requestRefresh();
        pl.stats().clearCache();
    }

    private void toGuild(Guild g, String key, String... kv) {
        for (UUID u : g.members.keySet()) {
            Player t = Bukkit.getPlayer(u);
            if (t != null) pl.send(t, key, kv);
        }
    }

    // ================================================================ guilde
    private void create(Player p, String[] a) {
        if (a.length == 1) {
            if (gm.guildOf(p.getUniqueId()) != null) pl.send(p, "already-in-guild");
            else pl.createMenu().open(p);
            return;
        }
        if (a.length < 3) { usage(p, "create"); return; }
        tryCreate(p, a[1], a[2]);
    }

    /** Création de guilde (utilisée par /guild create et par l'interface). @return true si créée. */
    public boolean tryCreate(Player p, String name, String tag) {
        if (!gm.isEnabled()) { pl.send(p, "disabled"); return false; }
        if (gm.guildOf(p.getUniqueId()) != null) { pl.send(p, "already-in-guild"); return false; }
        if (!name.matches(pl.getConfig().getString("guild.name-pattern", "[A-Za-z0-9]{3,16}"))) { pl.send(p, "invalid-name"); return false; }
        if (!tag.matches(pl.getConfig().getString("guild.tag-pattern", "[A-Za-z0-9]{3,5}"))) { pl.send(p, "invalid-tag"); return false; }
        if (gm.guildByName(name) != null || gm.guildByName(tag) != null) { pl.send(p, "name-taken"); return false; }
        Guild g = gm.createGuild(name, tag, p.getUniqueId());
        pl.send(p, "guild-created", "name", g.name, "tag", g.tag);
        refresh();
        return true;
    }

    private void disband(Player p) {
        Guild g = myGuild(p);
        if (g == null) return;
        if (!gm.isOwner(p.getUniqueId(), false)) { pl.send(p, "not-owner"); return; }
        if (pl.wars().activeWarOf(g.id) != null) { pl.send(p, "in-war"); return; }
        Alliance al = gm.allianceOf(g);
        if (al != null && al.guilds.size() > 1 && !gm.allianceKeepsOwner(al, g.members.keySet())) { pl.send(p, "alliance-no-owner"); return; }
        toGuild(g, "guild-disbanded", "name", g.name);
        gm.deleteGuild(g);
        refresh();
    }

    private void leave(Player p) {
        Guild g = myGuild(p);
        if (g == null) return;
        UUID u = p.getUniqueId();
        boolean owner = gm.isOwner(u, false);
        if (owner) {
            long owners = g.members.values().stream().filter(r -> r.equalsIgnoreCase(gm.ownerRole(false))).count();
            if (g.members.size() == 1) { disband(p); return; }
            if (owners <= 1) { pl.send(p, "leader-cannot-leave"); return; }
        }
        Alliance al = gm.allianceOf(g);
        if (al != null && gm.isOwner(u, true) && !gm.allianceKeepsOwner(al, List.of(u))) { pl.send(p, "alliance-no-owner"); return; }
        gm.removeMember(g, u);
        pl.chat().setMode(u, null);
        pl.send(p, "left-guild", "name", g.name);
        toGuild(g, "member-left", "player", p.getName());
        refresh();
    }

    private void invite(Player p, String[] a) {
        if (a.length < 2) { usage(p, "invite"); return; }
        Guild g = myGuild(p);
        if (g == null || !need(p, "INVITE")) return;
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { pl.send(p, "player-not-found"); return; }
        if (gm.guildOf(t.getUniqueId()) != null) { pl.send(p, "target-has-guild"); return; }
        gm.invite(t.getUniqueId(), g);
        pl.send(p, "invite-sent", "player", t.getName());
        pl.send(t, "invite-received", "guild", g.name, "tag", g.tag, "player", p.getName());
    }

    private void accept(Player p) {
        if (gm.guildOf(p.getUniqueId()) != null) { pl.send(p, "already-in-guild"); return; }
        GuildManager.Invite inv = gm.takeInvite(p.getUniqueId());
        if (inv == null) { pl.send(p, "invite-none"); return; }
        Guild g = gm.guilds.get(inv.guildId());
        if (g == null || inv.expires() < System.currentTimeMillis()) { pl.send(p, "invite-expired"); return; }
        gm.addMember(g, p.getUniqueId());
        pl.send(p, "joined-guild", "name", g.name, "tag", g.tag);
        toGuild(g, "member-joined", "player", p.getName());
        refresh();
    }

    private void kick(Player p, String[] a) {
        if (a.length < 2) { usage(p, "kick"); return; }
        Guild g = myGuild(p);
        if (g == null || !need(p, "KICK")) return;
        UUID t = gm.byName(g.members.keySet(), a[1]);
        if (t == null) { pl.send(p, "not-member"); return; }
        String r = g.members.get(t);
        if (t.equals(p.getUniqueId()) || r.equalsIgnoreCase(gm.ownerRole(false))) { pl.send(p, "cannot-kick"); return; }
        Alliance al = gm.allianceOf(g);
        if (al != null) {
            String ar = al.memberRoles.get(t);
            if (ar != null && ar.equalsIgnoreCase(gm.ownerRole(true)) && !gm.allianceKeepsOwner(al, List.of(t))) { pl.send(p, "alliance-no-owner"); return; }
        }
        gm.removeMember(g, t);
        pl.chat().setMode(t, null);
        Player tp = Bukkit.getPlayer(t);
        if (tp != null) pl.send(tp, "kicked-target", "name", g.name);
        pl.send(p, "kicked-sender", "player", a[1]);
        refresh();
    }

    // =============================================================== stats
    private void stats(Player p, String[] a) {
        Guild g = a.length >= 2 ? gm.guildByName(a[1]) : gm.guildOf(p.getUniqueId());
        if (g == null) { pl.send(p, a.length >= 2 ? "guild-not-found" : "no-guild"); return; }
        guildStats(p, g);
    }

    private void guildStats(CommandSender s, Guild g) {
        StatsManager.Agg x = pl.stats().guild(g);
        for (String l : pl.list("stats-guild", "name", g.name, "tag", g.tag,
                "members", Text.num(x.members()), "active", Text.num(x.active()),
                "kills", Text.num(x.kills()), "deaths", Text.num(x.deaths()),
                "kdr", Text.kdr(x.kdr()), "playtime", Text.hours(x.seconds()))) s.sendMessage(Text.c(l));
    }

    private String tagsOf(Alliance a) {
        StringJoiner j = new StringJoiner(" + ");
        for (UUID id : a.guilds) { Guild g = gm.guilds.get(id); if (g != null) j.add(g.tag); }
        return j.toString();
    }

    private void allianceStats(Player p, Alliance a) {
        StatsManager.Agg x = pl.stats().alliance(a);
        for (String l : pl.list("stats-alliance", "name", a.name, "tag", a.tag, "guild-tags", tagsOf(a),
                "guilds", Text.num(x.guilds()), "members", Text.num(x.members()), "active", Text.num(x.active()),
                "kills", Text.num(x.kills()), "deaths", Text.num(x.deaths()),
                "kdr", Text.kdr(x.kdr()), "playtime", Text.hours(x.seconds()))) p.sendMessage(Text.c(l));
    }

    // ============================================================== callout
    private void callout(Player p, String[] a) {
        if (a.length < 2) { usage(p, "callout"); return; }
        Guild g = myGuild(p);
        if (g == null || !need(p, "CALLOUT")) return;
        String msg = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
        var cfg = pl.getConfig();
        if (cfg.getBoolean("callout.uppercase", true)) msg = msg.toUpperCase(Locale.ROOT);
        String fm = msg;
        String[] kv = {"tag", g.tag, "guild", g.name, "player", p.getName()};
        String ts = pl.text("callout-title", kv), ss = pl.text("callout-subtitle", kv);
        Component title = ts == null ? Component.empty()
                : Text.c(ts).replaceText(b -> b.matchLiteral("{message}").replacement(Component.text(fm)));
        Component sub = ss == null ? Component.empty()
                : Text.c(ss).replaceText(b -> b.matchLiteral("{message}").replacement(Component.text(fm)));
        Title.Times times = Title.Times.times(
                Duration.ofMillis(cfg.getLong("callout.fade-in-ticks", 10) * 50),
                Duration.ofMillis(cfg.getLong("callout.stay-ticks", 70) * 50),
                Duration.ofMillis(cfg.getLong("callout.fade-out-ticks", 20) * 50));
        if (ts != null || ss != null) {
            for (UUID u : g.members.keySet()) {
                Player t = Bukkit.getPlayer(u);
                if (t != null) t.showTitle(Title.title(title, sub, times));
            }
        }
        pl.send(p, "callout-sent");
    }

    // ================================================================= chat
    private void chat(Player p, String[] a, ChatListener.Mode mode) {
        Guild g = myGuild(p);
        if (g == null) return;
        if (mode == ChatListener.Mode.ALLIANCE && !pl.chat().hasAllianceChatAccess(p)) { pl.send(p, "alliance-no-chat-access"); return; }
        int from = mode == ChatListener.Mode.GUILD ? 1 : 2;
        if (a.length > from) {
            pl.chat().send(p, mode, String.join(" ", Arrays.copyOfRange(a, from, a.length)));
            return;
        }
        if (pl.chat().mode(p.getUniqueId()) == mode) {
            pl.chat().setMode(p.getUniqueId(), null);
            pl.send(p, mode == ChatListener.Mode.GUILD ? "chat-off" : "chat-alliance-off");
        } else {
            pl.chat().setMode(p.getUniqueId(), mode);
            pl.send(p, mode == ChatListener.Mode.GUILD ? "chat-on" : "chat-alliance-on");
        }
    }

    // ================================================================ rôles
    /** Rôles de Guild (alliance=false) ou d'Alliance (alliance=true). r = arguments après "role". */
    private void roles(Player p, String[] r, boolean al) {
        UUID u = p.getUniqueId();
        Guild g = myGuild(p);
        if (g == null) return;
        Alliance ally = al ? gm.allianceOf(g) : null;
        if (al && ally == null) { pl.send(p, "no-alliance"); return; }
        Map<String, Set<String>> roles = al ? ally.roles : g.roles;
        List<String> allowed = al ? GuildManager.ALLIANCE_PERMS : GuildManager.GUILD_PERMS;
        String owner = gm.ownerRole(al), def = gm.defaultRole(al);
        boolean iAmOwner = gm.isOwner(u, al);
        String rk = al ? "alliance-role-" : "role-";
        String sub = r.length == 0 ? "list" : r[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            pl.send(p, "role-list-header");
            for (Map.Entry<String, Set<String>> e : roles.entrySet()) {
                String perms = e.getKey().equalsIgnoreCase(owner) ? "*" : String.join(", ", e.getValue());
                line(p, "role-list-line", "role", e.getKey(), "perms", perms.isEmpty() ? "-" : perms);
            }
            return;
        }
        boolean manage = al ? gm.alliancePerm(u, "MANAGE_ROLES") : gm.guildPerm(u, "MANAGE_ROLES");
        if (!manage) { pl.send(p, "no-role-permission"); return; }

        switch (sub) {
            case "create" -> {
                if (r.length < 2) { usage(p, rk + "create"); return; }
                if (!r[1].matches("[A-Za-z0-9_]{2,16}")) { pl.send(p, "role-invalid-name"); return; }
                if (GuildManager.findKey(roles, r[1]) != null) { pl.send(p, "role-exists"); return; }
                roles.put(r[1], new LinkedHashSet<>());
                pl.send(p, "role-created", "role", r[1]);
            }
            case "delete" -> {
                if (r.length < 2) { usage(p, rk + "delete"); return; }
                String k = GuildManager.findKey(roles, r[1]);
                if (k == null) { pl.send(p, "role-not-found"); return; }
                if (k.equalsIgnoreCase(owner) || k.equalsIgnoreCase(def)) { pl.send(p, "role-protected"); return; }
                if (al) ally.memberRoles.values().removeIf(v -> v.equalsIgnoreCase(k));
                else g.members.replaceAll((m, v) -> v.equalsIgnoreCase(k) ? def : v);
                roles.remove(k);
                pl.send(p, "role-deleted", "role", k);
            }
            case "give" -> {
                if (r.length < 3) { usage(p, rk + "give"); return; }
                Collection<UUID> pool = al ? gm.allianceMembers(ally) : g.members.keySet();
                UUID t = gm.byName(pool, r[1]);
                if (t == null) { pl.send(p, "not-member"); return; }
                String k = GuildManager.findKey(roles, r[2]);
                if (k == null) { pl.send(p, "role-not-found"); return; }
                String current = al ? ally.memberRoles.getOrDefault(t, def) : g.members.get(t);
                if ((k.equalsIgnoreCase(owner) || current.equalsIgnoreCase(owner)) && !iAmOwner) { pl.send(p, "owner-only"); return; }
                if (al) ally.memberRoles.put(t, k); else g.members.put(t, k);
                pl.send(p, "role-given", "player", r[1], "role", k);
            }
            case "remove" -> {
                if (r.length < 2) { usage(p, rk + "remove"); return; }
                Collection<UUID> pool = al ? gm.allianceMembers(ally) : g.members.keySet();
                UUID t = gm.byName(pool, r[1]);
                if (t == null) { pl.send(p, "not-member"); return; }
                String current = al ? ally.memberRoles.getOrDefault(t, def) : g.members.get(t);
                if (current.equalsIgnoreCase(owner) && !iAmOwner) { pl.send(p, "owner-only"); return; }
                if (al) ally.memberRoles.remove(t); else g.members.put(t, def);
                pl.send(p, "role-removed", "player", r[1], "role", def);
            }
            case "perm" -> {
                if (r.length < 4) { usage(p, rk + "perm"); return; }
                String k = GuildManager.findKey(roles, r[1]);
                if (k == null) { pl.send(p, "role-not-found"); return; }
                if (k.equalsIgnoreCase(owner)) { pl.send(p, "role-protected"); return; }
                String perm = r[2].toUpperCase(Locale.ROOT);
                if (!allowed.contains(perm)) { pl.send(p, "invalid-perm", "perms", String.join(", ", allowed)); return; }
                boolean on = r[3].equalsIgnoreCase("true") || r[3].equalsIgnoreCase("on");
                if (on) roles.get(k).add(perm); else roles.get(k).remove(perm);
                pl.send(p, "role-perm-set", "role", k, "perm", perm, "value", pl.onOff(on));
            }
            default -> usage(p, rk + "main");
        }
        refresh();
    }

    // ============================================================== alliance
    private void alliance(Player p, String[] a) {
        if (a.length < 2) { usage(p, "alliance"); return; }
        UUID u = p.getUniqueId();
        Guild g = myGuild(p);
        if (g == null) return;
        Alliance al = gm.allianceOf(g);
        String sub = a[1].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "create" -> {
                if (a.length < 4) { usage(p, "alliance-create"); return; }
                if (!need(p, "MANAGE_ALLIANCES")) return;
                if (al != null) { pl.send(p, "already-in-alliance"); return; }
                if (!a[2].matches(pl.getConfig().getString("alliance.name-pattern", "[A-Za-z0-9_]{3,16}"))) { pl.send(p, "invalid-name"); return; }
                if (!a[3].matches(pl.getConfig().getString("alliance.tag-pattern", "[A-Za-z0-9]{2,5}"))) { pl.send(p, "invalid-tag"); return; }
                if (gm.allianceByName(a[2]) != null || gm.allianceByName(a[3]) != null) { pl.send(p, "alliance-exists"); return; }
                Alliance n = gm.createAlliance(a[2], a[3], g, u);
                pl.send(p, "alliance-created", "name", n.name, "tag", n.tag);
                refresh();
            }
            case "add" -> {
                if (a.length < 3) { usage(p, "alliance-add"); return; }
                if (al == null) { pl.send(p, "no-alliance"); return; }
                if (!gm.alliancePerm(u, "ADD_GUILD")) { pl.send(p, "no-role-permission"); return; }
                Guild t = gm.guildByName(a[2]);
                if (t == null) { pl.send(p, "guild-not-found"); return; }
                if (t.allianceId != null) { pl.send(p, "target-in-alliance"); return; }
                gm.addGuildToAlliance(al, t);
                pl.send(p, "alliance-guild-added", "guild", t.name);
                toGuild(t, "alliance-joined", "alliance", al.name, "tag", al.tag);
                refresh();
            }
            case "remove" -> {
                if (a.length < 3) { usage(p, "alliance-remove"); return; }
                if (al == null) { pl.send(p, "no-alliance"); return; }
                if (!gm.alliancePerm(u, "REMOVE_GUILD")) { pl.send(p, "no-role-permission"); return; }
                Guild t = gm.guildByName(a[2]);
                if (t == null || !al.guilds.contains(t.id)) { pl.send(p, "target-not-in-alliance"); return; }
                if (t.id.equals(g.id)) { pl.send(p, "cannot-remove-self"); return; }
                if (!gm.allianceKeepsOwner(al, t.members.keySet())) { pl.send(p, "alliance-no-owner"); return; }
                gm.removeGuildFromAlliance(t);
                pl.send(p, "alliance-guild-removed", "guild", t.name);
                toGuild(t, "alliance-kicked", "alliance", al.name);
                refresh();
            }
            case "leave" -> {
                if (al == null) { pl.send(p, "no-alliance"); return; }
                if (!need(p, "MANAGE_ALLIANCES")) return;
                if (al.guilds.size() > 1 && !gm.allianceKeepsOwner(al, g.members.keySet())) { pl.send(p, "alliance-no-owner"); return; }
                for (UUID m : g.members.keySet()) if (pl.chat().mode(m) == ChatListener.Mode.ALLIANCE) pl.chat().setMode(m, null);
                gm.removeGuildFromAlliance(g);
                pl.send(p, "alliance-left", "alliance", al.name);
                refresh();
            }
            case "disband" -> {
                if (al == null) { pl.send(p, "no-alliance"); return; }
                if (!gm.isOwner(u, true)) { pl.send(p, "not-owner"); return; }
                for (UUID gid : al.guilds) if (pl.wars().activeWarOf(gid) != null) { pl.send(p, "in-war"); return; }
                for (UUID m : gm.allianceMembers(al)) if (pl.chat().mode(m) == ChatListener.Mode.ALLIANCE) pl.chat().setMode(m, null);
                String n = al.name;
                gm.deleteAlliance(al);
                pl.send(p, "alliance-disbanded", "alliance", n);
                refresh();
            }
            case "chat" -> chat(p, a, ChatListener.Mode.ALLIANCE);
            case "chataccess" -> {
                if (a.length < 3) { usage(p, "alliance-chataccess"); return; }
                if (al == null) { pl.send(p, "no-alliance"); return; }
                if (!need(p, "MANAGE_ALLIANCES")) return;
                g.allianceChat = a[2].equalsIgnoreCase("on") || a[2].equalsIgnoreCase("true");
                if (!g.allianceChat) for (UUID m : g.members.keySet()) if (pl.chat().mode(m) == ChatListener.Mode.ALLIANCE) pl.chat().setMode(m, null);
                pl.send(p, "alliance-chat-access-set", "value", pl.onOff(g.allianceChat));
                gm.markDirty();
            }
            case "tag" -> {
                if (a.length < 3) { usage(p, "alliance-tag"); return; }
                boolean on = a[2].equalsIgnoreCase("on") || a[2].equalsIgnoreCase("true");
                gm.setShowAllianceTag(u, on);
                pl.send(p, "alliance-tag-set", "value", pl.onOff(on));
                pl.display().requestRefresh();
            }
            case "stats" -> {
                Alliance t = a.length >= 3 ? gm.allianceByName(a[2]) : al;
                if (t == null) { pl.send(p, a.length >= 3 ? "alliance-not-found" : "no-alliance"); return; }
                allianceStats(p, t);
            }
            case "role" -> roles(p, Arrays.copyOfRange(a, 2, a.length), true);
            default -> usage(p, "alliance");
        }
    }

    // ================================================================ ennemis
    private void enemy(Player p, String[] a) {
        if (a.length < 2) { usage(p, "enemy"); return; }
        Guild g = myGuild(p);
        if (g == null) return;
        String sub = a[1].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            pl.send(p, "enemy-list-header");
            for (UUID id : g.enemies) {
                Guild e = gm.guilds.get(id);
                if (e != null) line(p, "enemy-list-line", "name", e.name, "tag", e.tag);
            }
            return;
        }
        if (!need(p, "MANAGE_ENEMIES")) return;
        if (a.length < 3) { usage(p, "enemy-target"); return; }
        Guild t = gm.guildByName(a[2]);
        if (t == null) { pl.send(p, "guild-not-found"); return; }
        switch (sub) {
            case "add" -> {
                if (t.id.equals(g.id)) { pl.send(p, "enemy-self"); return; }
                if (!g.enemies.add(t.id)) { pl.send(p, "enemy-already"); return; }
                pl.send(p, "enemy-added", "guild", t.name);
            }
            case "remove" -> {
                if (!g.enemies.remove(t.id)) { pl.send(p, "enemy-not"); return; }
                pl.send(p, "enemy-removed", "guild", t.name);
            }
            default -> { usage(p, "enemy"); return; }
        }
        refresh();
    }

    // ============================================================== protection
    private void protect(Player p, String[] a) {
        if (a.length < 2) { usage(p, "protect"); return; }
        boolean on = a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("true");
        gm.setAllyProtection(p.getUniqueId(), on);
        pl.send(p, "protect-set", "value", pl.onOff(on));
    }

    // ============================================================ tab complete
    private List<String> filter(Collection<String> opts, String start) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase(Locale.ROOT).startsWith(start.toLowerCase(Locale.ROOT))) out.add(o);
        return out;
    }

    private List<String> onlineNames() {
        List<String> l = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
        return l;
    }

    private List<String> guildNames() {
        List<String> l = new ArrayList<>();
        for (Guild g : gm.guilds.values()) l.add(g.name);
        return l;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission(pl.perm("use"))) return List.of();
        if (a.length == 1) {
            List<String> subs = new ArrayList<>(List.of("create", "disband", "leave", "invite", "accept", "kick", "stats",
                    "callout", "chat", "role", "alliance", "enemy", "protect", "war"));
            if (s.hasPermission(pl.perm("reload"))) subs.add("reload");
            if (s.hasPermission(pl.perm("enable"))) subs.add("enable");
            if (s.hasPermission(pl.perm("disable"))) subs.add("disable");
            return filter(subs, a[0]);
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 2) {
            return switch (sub) {
                case "invite", "kick" -> filter(onlineNames(), a[1]);
                case "stats" -> filter(guildNames(), a[1]);
                case "role" -> filter(List.of("list", "create", "delete", "give", "remove", "perm"), a[1]);
                case "alliance" -> filter(List.of("create", "add", "remove", "leave", "disband", "chat", "chataccess", "tag", "stats", "role"), a[1]);
                case "enemy" -> filter(List.of("add", "remove", "list"), a[1]);
                case "protect" -> filter(List.of("on", "off"), a[1]);
                default -> List.of();
            };
        }
        if (a.length == 3) {
            String sub2 = a[1].toLowerCase(Locale.ROOT);
            if (sub.equals("enemy") && (sub2.equals("add") || sub2.equals("remove"))) return filter(guildNames(), a[2]);
            if (sub.equals("role") && (sub2.equals("give") || sub2.equals("remove"))) return filter(onlineNames(), a[2]);
            if (sub.equals("alliance")) {
                if (sub2.equals("add") || sub2.equals("remove")) return filter(guildNames(), a[2]);
                if (sub2.equals("chataccess") || sub2.equals("tag")) return filter(List.of("on", "off"), a[2]);
                if (sub2.equals("role")) return filter(List.of("list", "create", "delete", "give", "remove", "perm"), a[2]);
            }
        }
        if (a.length == 4 && sub.equals("role") && a[1].equalsIgnoreCase("perm")) return filter(GuildManager.GUILD_PERMS, a[3]);
        if (a.length == 5 && sub.equals("alliance") && a[1].equalsIgnoreCase("role") && a[2].equalsIgnoreCase("perm"))
            return filter(GuildManager.ALLIANCE_PERMS, a[4]);
        return List.of();
    }
}
