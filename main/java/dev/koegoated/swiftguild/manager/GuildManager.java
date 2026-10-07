package dev.koegoated.swiftguild.manager;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.model.Alliance;
import dev.koegoated.swiftguild.model.Guild;
import dev.koegoated.swiftguild.util.DataFile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Données des Guilds / Alliances / préférences + persistance (data.yml + un fichier par guilde dans guilds/). */
public final class GuildManager {
    public static final List<String> GUILD_PERMS = List.of(
            "INVITE", "KICK", "MANAGE_ROLES", "MANAGE_ALLIANCES", "MANAGE_ENEMIES", "START_WAR", "CALLOUT");
    public static final List<String> ALLIANCE_PERMS = List.of("ADD_GUILD", "REMOVE_GUILD", "MANAGE_ROLES");

    public record Invite(UUID guildId, long expires) {}

    private final SwiftGuildPlugin plugin;
    private final File file;
    private boolean enabled = true;
    private volatile boolean dirty;
    private final DataFile df;
    private final File guildsDir;
    private final Map<UUID, GuildFile> gfiles = new HashMap<>();
    private final Map<String, String> ignored = new HashMap<>();
    private String dataContent = "";
    private volatile boolean externalChange;

    public final Map<UUID, Guild> guilds = new ConcurrentHashMap<>();
    public final Map<UUID, Alliance> alliances = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> playerGuild = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> allyProtection = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> showAllianceTag = new ConcurrentHashMap<>();
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();

    public GuildManager(SwiftGuildPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.df = new DataFile(plugin, file);
        this.guildsDir = new File(plugin.getDataFolder(), "guilds");
    }

    // ------------------------------------------------------------------ état
    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean v) {
        this.enabled = v;
        if (!v) invites.clear();
        markDirty();
    }

    public void markDirty() { dirty = true; }

    private void clearMaps() {
        guilds.clear();
        alliances.clear();
        playerGuild.clear();
        allyProtection.clear();
        showAllianceTag.clear();
        invites.clear();
        gfiles.clear();
        ignored.clear();
    }

    /** Vrai (une seule fois) si des fichiers ont été modifiés / supprimés / ajoutés à la main depuis le dernier appel. */
    public boolean consumeExternalChange() {
        boolean v = externalChange;
        externalChange = false;
        return v;
    }

    public void saveIfDirty() {
        if (dirty) save();
    }

    // ------------------------------------------------------------ recherches
    public Guild guildOf(UUID player) {
        UUID id = playerGuild.get(player);
        return id == null ? null : guilds.get(id);
    }

    public Guild guildByName(String n) {
        if (n == null) return null;
        for (Guild g : guilds.values())
            if (g.name.equalsIgnoreCase(n) || g.tag.equalsIgnoreCase(n)) return g;
        return null;
    }

    public Alliance allianceOf(Guild g) {
        return g == null || g.allianceId == null ? null : alliances.get(g.allianceId);
    }

    public Alliance allianceByName(String n) {
        if (n == null) return null;
        for (Alliance a : alliances.values())
            if (a.name.equalsIgnoreCase(n) || a.tag.equalsIgnoreCase(n)) return a;
        return null;
    }

    public static String findKey(Map<String, ?> m, String n) {
        for (String k : m.keySet()) if (k.equalsIgnoreCase(n)) return k;
        return null;
    }

    public UUID byName(Collection<UUID> pool, String name) {
        for (UUID u : pool) {
            String n = plugin.getServer().getOfflinePlayer(u).getName();
            if (n != null && n.equalsIgnoreCase(name)) return u;
        }
        return null;
    }

    public Set<UUID> allianceMembers(Alliance a) {
        Set<UUID> s = new LinkedHashSet<>();
        for (UUID gid : a.guilds) {
            Guild g = guilds.get(gid);
            if (g != null) s.addAll(g.members.keySet());
        }
        return s;
    }

    // ---------------------------------------------------------------- rôles
    public String ownerRole(boolean alliance) {
        return plugin.getConfig().getString((alliance ? "alliance" : "guild") + ".roles.owner-role", "Leader");
    }

    public String defaultRole(boolean alliance) {
        return plugin.getConfig().getString((alliance ? "alliance" : "guild") + ".roles.default-role", "Member");
    }

    private Map<String, Set<String>> defaultRoles(boolean alliance) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        List<String> allowed = alliance ? ALLIANCE_PERMS : GUILD_PERMS;
        ConfigurationSection s = plugin.getConfig()
                .getConfigurationSection((alliance ? "alliance" : "guild") + ".roles.definitions");
        if (s != null) {
            for (String role : s.getKeys(false)) {
                Set<String> perms = new LinkedHashSet<>();
                for (String p : s.getStringList(role)) {
                    String up = p.toUpperCase(Locale.ROOT);
                    if (allowed.contains(up)) perms.add(up);
                }
                out.put(role, perms);
            }
        }
        out.putIfAbsent(ownerRole(alliance), new LinkedHashSet<>());
        out.putIfAbsent(defaultRole(alliance), new LinkedHashSet<>());
        return out;
    }

    /** Rôle effectif d'un joueur (guilde ou alliance). */
    public String roleOf(UUID player, boolean alliance) {
        Guild g = guildOf(player);
        if (g == null) return null;
        if (!alliance) return g.members.get(player);
        Alliance a = allianceOf(g);
        if (a == null) return null;
        return a.memberRoles.getOrDefault(player, defaultRole(true));
    }

    public boolean isOwner(UUID player, boolean alliance) {
        String r = roleOf(player, alliance);
        return r != null && r.equalsIgnoreCase(ownerRole(alliance));
    }

    public boolean guildPerm(UUID player, String perm) {
        Guild g = guildOf(player);
        if (g == null) return false;
        String r = g.members.get(player);
        if (r == null) return false;
        if (r.equalsIgnoreCase(ownerRole(false))) return true;
        Set<String> s = g.roles.get(r);
        return s != null && s.contains(perm);
    }

    public boolean alliancePerm(UUID player, String perm) {
        Guild g = guildOf(player);
        Alliance a = allianceOf(g);
        if (a == null) return false;
        String r = a.memberRoles.getOrDefault(player, defaultRole(true));
        if (r.equalsIgnoreCase(ownerRole(true))) return true;
        Set<String> s = a.roles.get(r);
        return s != null && s.contains(perm);
    }

    /** Vrai si, après retrait de ces membres, l'alliance conserve au moins un propriétaire. */
    public boolean allianceKeepsOwner(Alliance a, Collection<UUID> leaving) {
        String owner = ownerRole(true);
        for (Map.Entry<UUID, String> e : a.memberRoles.entrySet())
            if (e.getValue().equalsIgnoreCase(owner) && !leaving.contains(e.getKey())) return true;
        return false;
    }

    // ------------------------------------------------------------- création
    public Guild createGuild(String name, String tag, UUID owner) {
        Guild g = new Guild(UUID.randomUUID(), name, tag);
        g.createdAt = System.currentTimeMillis();
        String dc = plugin.getConfig().getString("tags.default-color", "f");
        g.tagColor = dc.isEmpty() ? 'f' : dc.charAt(0);
        g.roles.putAll(defaultRoles(false));
        guilds.put(g.id, g);
        g.members.put(owner, ownerRole(false));
        playerGuild.put(owner, g.id);
        writeGuild(g);
        markDirty();
        return g;
    }

    public void addMember(Guild g, UUID p) {
        g.members.put(p, defaultRole(false));
        playerGuild.put(p, g.id);
        markDirty();
    }

    public void removeMember(Guild g, UUID p) {
        g.members.remove(p);
        playerGuild.remove(p);
        Alliance a = allianceOf(g);
        if (a != null) a.memberRoles.remove(p);
        markDirty();
    }

    public void deleteGuild(Guild g) {
        if (g.allianceId != null) removeGuildFromAlliance(g);
        for (UUID u : new ArrayList<>(g.members.keySet())) playerGuild.remove(u);
        guilds.remove(g.id);
        removeGuildFile(g.id);
        for (Guild o : guilds.values()) o.enemies.remove(g.id);
        invites.values().removeIf(i -> i.guildId().equals(g.id));
        markDirty();
    }

    public Alliance createAlliance(String name, String tag, Guild g, UUID owner) {
        Alliance a = new Alliance(UUID.randomUUID(), name, tag);
        a.roles.putAll(defaultRoles(true));
        a.guilds.add(g.id);
        g.allianceId = a.id;
        a.memberRoles.put(owner, ownerRole(true));
        alliances.put(a.id, a);
        markDirty();
        return a;
    }

    public void addGuildToAlliance(Alliance a, Guild g) {
        a.guilds.add(g.id);
        g.allianceId = a.id;
        markDirty();
    }

    public void removeGuildFromAlliance(Guild g) {
        Alliance a = allianceOf(g);
        g.allianceId = null;
        g.allianceChat = false;
        if (a == null) return;
        a.guilds.remove(g.id);
        for (UUID u : g.members.keySet()) a.memberRoles.remove(u);
        if (a.guilds.isEmpty()) alliances.remove(a.id);
        markDirty();
    }

    public void deleteAlliance(Alliance a) {
        for (UUID gid : a.guilds) {
            Guild g = guilds.get(gid);
            if (g != null) {
                g.allianceId = null;
                g.allianceChat = false;
            }
        }
        alliances.remove(a.id);
        markDirty();
    }

    // --------------------------------------------------------------- infos
    public String ownerName(Guild g) {
        String owner = ownerRole(false);
        for (Map.Entry<UUID, String> e : g.members.entrySet()) {
            if (e.getValue().equalsIgnoreCase(owner)) {
                String n = plugin.getServer().getOfflinePlayer(e.getKey()).getName();
                return n == null ? "?" : n;
            }
        }
        return "?";
    }

    /**
     * Placeholders propres du plugin pour une guilde : name, tag, color, owner, created, motd, role, chat,
     * members, online. Utilisés dans interfaces/*.yml ({guild_xxx}) et dans PlaceholderAPI (%swiftguild_xxx%).
     */
    public Map<String, String> info(Guild g, UUID viewer) {
        var ui = plugin.ui();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", g.name);
        m.put("tag", g.tag);
        m.put("color", "&" + g.tagColor);
        m.put("owner", ownerName(g));
        String fmt = ui.text("guild-menu", "date-format");
        String date;
        try {
            date = java.time.format.DateTimeFormatter.ofPattern(fmt.isEmpty() ? "dd/MM/yyyy" : fmt)
                    .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(g.createdAt));
        } catch (IllegalArgumentException ex) {
            date = String.valueOf(g.createdAt);
        }
        m.put("created", date);
        m.put("motd", g.motd == null || g.motd.isBlank() ? ui.text("guild-menu", "no-motd") : g.motd);
        m.put("role", viewer == null ? "" : g.members.getOrDefault(viewer, ""));
        m.put("chat", ui.text("guild-menu", g.chatMuted ? "chat-muted" : "chat-active"));
        m.put("members", String.valueOf(g.members.size()));
        int online = 0;
        for (UUID u : g.members.keySet()) if (plugin.getServer().getPlayer(u) != null) online++;
        m.put("online", String.valueOf(online));
        return m;
    }

    // ---------------------------------------------------------- invitations
    public void invite(UUID target, Guild g) {
        long ttl = plugin.getConfig().getLong("guild.invite-expire-seconds", 60) * 1000L;
        invites.put(target, new Invite(g.id, System.currentTimeMillis() + ttl));
    }

    public Invite takeInvite(UUID target) { return invites.remove(target); }

    public void clearInvites() { invites.clear(); }

    // ------------------------------------------------- préférences joueurs
    public boolean allyProtection(UUID p) {
        return allyProtection.getOrDefault(p, plugin.getConfig().getBoolean("allies.damage-protection-default", true));
    }

    public void setAllyProtection(UUID p, boolean v) { allyProtection.put(p, v); markDirty(); }

    public boolean showAllianceTag(UUID p) {
        return showAllianceTag.getOrDefault(p, plugin.getConfig().getBoolean("tags.alliance-tag-default-visible", true));
    }

    public void setShowAllianceTag(UUID p, boolean v) { showAllianceTag.put(p, v); markDirty(); }

    // ---------------------------------------------------------- persistance
    // data.yml          : état (enabled), alliances, préférences des joueurs
    // guilds/<nom>.yml  : une guilde par fichier
    // Les fichiers font foi : supprimés / modifiés / ajoutés à la main pendant que le serveur tourne,
    // ils sont pris en compte (applyExternalChanges) au lieu d'être écrasés par la mémoire.

    private static final class GuildFile {
        final String name;
        final DataFile df;
        String content = "";

        GuildFile(String name, DataFile df) {
            this.name = name;
            this.df = df;
        }
    }

    private YamlConfiguration serialize(Guild g) {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Guilde " + g.name + " - fichier modifiable à la main, pris en compte en moins de 5 secondes (ou avec /guild reload).",
                "id         : identifiant, ne pas modifier.",
                "name, tag  : nom et tag de la guilde (lettres et chiffres).",
                "tag-color  : code couleur du tag (0-9, a-f) ; sert aussi à la couleur de la bannière du menu.",
                "motd       : message du jour (couleurs avec &).",
                "chat-muted : true = le chat de guilde est muet.",
                "members    : <uuid du joueur>: <rôle>. Ajouter / retirer une ligne ajoute / retire un membre.",
                "roles      : <rôle>: [permissions]. Permissions : INVITE, KICK, MANAGE_ROLES, MANAGE_ALLIANCES, MANAGE_ENEMIES, START_WAR, CALLOUT.",
                "enemies    : liste des id des guildes ennemies.",
                "Supprimer ce fichier supprime la guilde."));
        y.set("id", g.id.toString());
        y.set("name", g.name);
        y.set("tag", g.tag);
        y.set("created-at", g.createdAt);
        y.set("motd", g.motd);
        y.set("tag-color", String.valueOf(g.tagColor));
        y.set("chat-muted", g.chatMuted);
        y.set("alliance-chat", g.allianceChat);
        for (Map.Entry<UUID, String> e : g.members.entrySet()) {
            y.set("members." + e.getKey(), e.getValue());
            String pn = plugin.getServer().getOfflinePlayer(e.getKey()).getName();
            if (pn != null) y.setInlineComments("members." + e.getKey(), List.of(pn));
        }
        for (Map.Entry<String, Set<String>> e : g.roles.entrySet()) y.set("roles." + e.getKey(), new ArrayList<>(e.getValue()));
        List<String> en = new ArrayList<>();
        for (UUID u : g.enemies) en.add(u.toString());
        y.set("enemies", en);
        return y;
    }

    /** @param fallbackId identifiant à utiliser si la section n'a pas de clé "id" (ancien format). */
    private Guild parseGuild(ConfigurationSection s, String fallbackId) {
        try {
            String ids = s.getString("id", fallbackId);
            String name = s.getString("name");
            String tag = s.getString("tag");
            if (ids == null || name == null || tag == null) return null;
            Guild g = new Guild(UUID.fromString(ids), name, tag);
            g.allianceChat = s.getBoolean("alliance-chat");
            g.createdAt = s.getLong("created-at", System.currentTimeMillis());
            g.motd = s.getString("motd");
            String tc = s.getString("tag-color", "f");
            g.tagColor = tc == null || tc.isEmpty() ? 'f' : tc.charAt(0);
            g.chatMuted = s.getBoolean("chat-muted", false);
            ConfigurationSection ms = s.getConfigurationSection("members");
            if (ms != null) for (String m : ms.getKeys(false)) {
                try {
                    g.members.put(UUID.fromString(m), ms.getString(m, defaultRole(false)));
                } catch (IllegalArgumentException ignoredId) {
                    plugin.getLogger().warning("Membre invalide ignoré dans la guilde '" + name + "' : " + m);
                }
            }
            ConfigurationSection rs = s.getConfigurationSection("roles");
            if (rs != null) for (String r : rs.getKeys(false)) g.roles.put(r, new LinkedHashSet<>(rs.getStringList(r)));
            if (g.roles.isEmpty()) g.roles.putAll(defaultRoles(false));
            for (String e : s.getStringList("enemies")) {
                try {
                    g.enemies.add(UUID.fromString(e));
                } catch (IllegalArgumentException ignoredId) {}
            }
            return g;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String fileNameFor(Guild g) {
        String base = g.name.replaceAll("[^A-Za-z0-9_-]", "_");
        if (base.isEmpty()) base = "guild";
        String name = base + ".yml";
        boolean clash = new File(guildsDir, name).exists();
        for (Map.Entry<UUID, GuildFile> e : gfiles.entrySet())
            if (!e.getKey().equals(g.id) && e.getValue().name.equalsIgnoreCase(name)) clash = true;
        if (clash) name = base + "-" + g.id.toString().substring(0, 8) + ".yml";
        return name;
    }

    /** Écrit le fichier de la guilde seulement si son contenu a changé. */
    private void writeGuild(Guild g) {
        YamlConfiguration y = serialize(g);
        String content = y.saveToString();
        GuildFile gf = gfiles.get(g.id);
        if (gf == null) {
            guildsDir.mkdirs();
            String n = fileNameFor(g);
            gf = new GuildFile(n, new DataFile(plugin, new File(guildsDir, n)));
            gfiles.put(g.id, gf);
        } else if (content.equals(gf.content)) {
            return;
        }
        if (gf.df.write(y)) gf.content = content;
    }

    private void removeGuildFile(UUID id) {
        GuildFile gf = gfiles.remove(id);
        if (gf != null) new File(guildsDir, gf.name).delete();
    }

    /** Charge un fichier guilds/xxx.yml inconnu. @return true si une guilde a été ajoutée. */
    private boolean loadGuildFile(File f) {
        String key = f.getName().toLowerCase(Locale.ROOT);
        String stamp = f.lastModified() + ":" + f.length();
        if (stamp.equals(ignored.get(key))) return false;
        DataFile d = new DataFile(plugin, f);
        YamlConfiguration y = d.read();
        Guild g = y == null ? null : parseGuild(y, null);
        if (g == null) {
            plugin.getLogger().warning("guilds/" + f.getName() + " ignoré (fichier invalide : id, name et tag sont requis).");
            ignored.put(key, stamp);
            return false;
        }
        if (guilds.containsKey(g.id)) {
            plugin.getLogger().warning("guilds/" + f.getName() + " ignoré : une guilde avec le même id existe déjà.");
            ignored.put(key, stamp);
            return false;
        }
        ignored.remove(key);
        GuildFile gf = new GuildFile(f.getName(), d);
        gf.content = serialize(g).saveToString();
        gfiles.put(g.id, gf);
        guilds.put(g.id, g);
        return true;
    }

    private File[] guildFiles() {
        File[] files = guildsDir.listFiles((dir, n) -> n.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    /**
     * Applique les changements faits à la main sur les fichiers pendant que le serveur tourne :
     * guilde supprimée / modifiée / ajoutée, data.yml supprimé / modifié.
     * @return true si la mémoire a changé (voir aussi consumeExternalChange)
     */
    public synchronized boolean applyExternalChanges() {
        boolean changed = false;

        // 1) guildes dont le fichier a été supprimé ou modifié
        for (Map.Entry<UUID, GuildFile> e : new ArrayList<>(gfiles.entrySet())) {
            UUID id = e.getKey();
            GuildFile gf = e.getValue();
            if (gf.df.wasDeleted()) {
                Guild g = guilds.remove(id);
                gfiles.remove(id);
                plugin.getLogger().info("guilds/" + gf.name + " supprimé : la guilde '" + (g == null ? id : g.name) + "' est supprimée.");
                changed = true;
            } else if (gf.df.wasModified()) {
                YamlConfiguration y = gf.df.read();
                if (y == null) continue; // illisible : on garde la mémoire (copie .broken faite)
                Guild ng = parseGuild(y, null);
                if (ng == null) {
                    plugin.getLogger().warning("guilds/" + gf.name + " modifié mais invalide (id, name et tag requis) : ignoré.");
                    continue;
                }
                if (!ng.id.equals(id)) {
                    guilds.remove(id);
                    gfiles.remove(id);
                    if (guilds.containsKey(ng.id)) {
                        plugin.getLogger().warning("guilds/" + gf.name + " : id déjà utilisé par une autre guilde, fichier ignoré.");
                        changed = true;
                        continue;
                    }
                    gfiles.put(ng.id, gf);
                }
                guilds.put(ng.id, ng);
                gf.content = serialize(ng).saveToString();
                plugin.getLogger().info("guilds/" + gf.name + " modifié : guilde '" + ng.name + "' rechargée.");
                changed = true;
            }
        }

        // 2) nouveaux fichiers dans guilds/
        Set<String> known = new HashSet<>();
        for (GuildFile gf : gfiles.values()) known.add(gf.name.toLowerCase(Locale.ROOT));
        for (File f : guildFiles()) {
            if (known.contains(f.getName().toLowerCase(Locale.ROOT))) continue;
            if (loadGuildFile(f)) {
                plugin.getLogger().info("guilds/" + f.getName() + " ajouté : guilde chargée.");
                changed = true;
            }
        }

        // 3) data.yml (alliances, préférences)
        if (df.wasDeleted()) {
            alliances.clear();
            allyProtection.clear();
            showAllianceTag.clear();
            df.forget();
            plugin.getLogger().info("data.yml supprimé : alliances et préférences réinitialisées.");
            changed = true;
        } else if (df.wasModified()) {
            YamlConfiguration y = df.read();
            if (y != null) {
                readData(y);
                plugin.getLogger().info("data.yml modifié : alliances et préférences rechargées.");
                changed = true;
            }
        }

        if (changed) {
            relink();
            dirty = false;
            externalChange = true;
        }
        return changed;
    }

    /** Remet la cohérence entre guildes, alliances et index des joueurs. */
    private void relink() {
        for (Guild g : guilds.values()) {
            g.allianceId = null;
            g.enemies.removeIf(id -> !guilds.containsKey(id));
        }
        Set<UUID> seen = new HashSet<>();
        for (Alliance a : alliances.values()) {
            for (UUID gid : new ArrayList<>(a.guilds)) {
                Guild g = guilds.get(gid);
                if (g == null || !seen.add(gid)) a.guilds.remove(gid);
                else g.allianceId = a.id;
            }
        }
        alliances.values().removeIf(a -> a.guilds.isEmpty());
        for (Alliance a : alliances.values()) {
            Set<UUID> members = allianceMembers(a);
            a.memberRoles.keySet().removeIf(u -> !members.contains(u));
        }
        // index joueur -> guilde (une seule guilde par joueur ; la plus ancienne gagne)
        List<Guild> sorted = new ArrayList<>(guilds.values());
        sorted.sort(Comparator.comparingLong(g -> g.createdAt));
        Map<UUID, UUID> next = new HashMap<>();
        for (Guild g : sorted) {
            for (Iterator<UUID> it = g.members.keySet().iterator(); it.hasNext(); ) {
                UUID u = it.next();
                if (next.putIfAbsent(u, g.id) != null) {
                    plugin.getLogger().warning("Joueur " + u + " présent dans plusieurs guildes : retiré de '" + g.name + "'.");
                    it.remove();
                }
            }
        }
        playerGuild.keySet().retainAll(next.keySet());
        playerGuild.putAll(next);
    }

    public synchronized void save() {
        dirty = false;
        // Le disque fait foi : on applique d'abord les changements faits à la main, on n'écrase jamais.
        applyExternalChanges();
        guildsDir.mkdirs();
        for (UUID id : new ArrayList<>(gfiles.keySet())) if (!guilds.containsKey(id)) removeGuildFile(id);
        for (Guild g : guilds.values()) writeGuild(g);
        writeData();
    }

    private void writeData() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("enabled", enabled);
        for (Alliance a : alliances.values()) {
            String b = "alliances." + a.id + ".";
            y.set(b + "name", a.name);
            y.set(b + "tag", a.tag);
            List<String> gl = new ArrayList<>();
            for (UUID u : a.guilds) gl.add(u.toString());
            y.set(b + "guilds", gl);
            for (Map.Entry<String, Set<String>> e : a.roles.entrySet()) y.set(b + "roles." + e.getKey(), new ArrayList<>(e.getValue()));
            for (Map.Entry<UUID, String> e : a.memberRoles.entrySet()) y.set(b + "member-roles." + e.getKey(), e.getValue());
        }
        Set<UUID> ps = new HashSet<>(allyProtection.keySet());
        ps.addAll(showAllianceTag.keySet());
        for (UUID u : ps) {
            if (allyProtection.containsKey(u)) y.set("players." + u + ".ally-protection", allyProtection.get(u));
            if (showAllianceTag.containsKey(u)) y.set("players." + u + ".show-alliance-tag", showAllianceTag.get(u));
        }
        String content = y.saveToString();
        if (content.equals(dataContent) && file.exists()) return;
        if (df.write(y)) dataContent = content;
    }

    public synchronized void load() {
        clearMaps();
        guildsDir.mkdirs();
        YamlConfiguration y = df.read();
        if (y != null) readData(y);

        for (File f : guildFiles()) loadGuildFile(f);

        // Migration depuis l'ancien format : toutes les guildes étaient dans data.yml
        boolean migrated = false;
        ConfigurationSection legacy = y == null ? null : y.getConfigurationSection("guilds");
        if (legacy != null) {
            for (String k : legacy.getKeys(false)) {
                ConfigurationSection s = legacy.getConfigurationSection(k);
                if (s == null) continue;
                Guild g = parseGuild(s, k);
                if (g == null) {
                    plugin.getLogger().warning("Guilde invalide ignorée dans data.yml : " + k);
                    continue;
                }
                if (guilds.putIfAbsent(g.id, g) == null) migrated = true;
            }
        }
        relink();
        if (migrated) {
            try {
                Files.copy(file.toPath(), new File(file.getParentFile(), "data.yml.avant-separation").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignoredCopy) {}
            plugin.getLogger().info("Migration : " + guilds.size() + " guilde(s) déplacée(s) de data.yml vers le dossier guilds/ "
                    + "(sauvegarde : data.yml.avant-separation).");
        }
        save(); // écrit guilds/*.yml et un data.yml sans la section "guilds"
    }

    /** Alliances, préférences des joueurs et état activé/désactivé. */
    private void readData(YamlConfiguration y) {
        enabled = y.getBoolean("enabled", true);
        alliances.clear();
        allyProtection.clear();
        showAllianceTag.clear();
        ConfigurationSection as = y.getConfigurationSection("alliances");
        if (as != null) for (String k : as.getKeys(false)) {
            try {
                ConfigurationSection s = as.getConfigurationSection(k);
                if (s == null) continue;
                Alliance a = new Alliance(UUID.fromString(k), s.getString("name", k), s.getString("tag", "?"));
                for (String g : s.getStringList("guilds")) a.guilds.add(UUID.fromString(g));
                ConfigurationSection rs = s.getConfigurationSection("roles");
                if (rs != null) for (String r : rs.getKeys(false)) a.roles.put(r, new LinkedHashSet<>(rs.getStringList(r)));
                ConfigurationSection ms = s.getConfigurationSection("member-roles");
                if (ms != null) for (String m : ms.getKeys(false)) a.memberRoles.put(UUID.fromString(m), ms.getString(m));
                alliances.put(a.id, a);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Alliance invalide ignorée : " + k);
            }
        }
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) for (String k : ps.getKeys(false)) {
            try {
                UUID u = UUID.fromString(k);
                if (ps.contains(k + ".ally-protection")) allyProtection.put(u, ps.getBoolean(k + ".ally-protection"));
                if (ps.contains(k + ".show-alliance-tag")) showAllianceTag.put(u, ps.getBoolean(k + ".show-alliance-tag"));
            } catch (IllegalArgumentException ignoredId) {}
        }
    }
}
