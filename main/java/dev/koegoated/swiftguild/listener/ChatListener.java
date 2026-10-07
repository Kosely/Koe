package dev.koegoated.swiftguild.listener;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.manager.DisplayManager;
import dev.koegoated.swiftguild.manager.GuildManager;
import dev.koegoated.swiftguild.model.Alliance;
import dev.koegoated.swiftguild.model.Guild;
import dev.koegoated.swiftguild.util.Text;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chat de Guild, chat d'Alliance et préfixe de tags dans le chat public. */
public final class ChatListener implements Listener {
    public enum Mode { GUILD, ALLIANCE }

    private final SwiftGuildPlugin plugin;
    private final GuildManager gm;
    private final DisplayManager dm;
    private final Map<UUID, Mode> modes = new ConcurrentHashMap<>();

    public ChatListener(SwiftGuildPlugin plugin, GuildManager gm, DisplayManager dm) {
        this.plugin = plugin;
        this.gm = gm;
        this.dm = dm;
    }

    public Mode mode(UUID p) { return modes.get(p); }
    public void setMode(UUID p, Mode m) { if (m == null) modes.remove(p); else modes.put(p, m); }
    public void clear() { modes.clear(); }

    public boolean hasAllianceChatAccess(Player p) {
        Guild g = gm.guildOf(p.getUniqueId());
        return g != null && g.allianceChat && gm.allianceOf(g) != null;
    }

    public void send(Player p, Mode mode, String message) {
        Guild g = gm.guildOf(p.getUniqueId());
        if (g == null) return;
        if (mode == Mode.GUILD && g.chatMuted) { plugin.send(p, "guild-chat-muted"); return; }
        String fmt = plugin.text(mode == Mode.GUILD ? "chat-guild-format" : "chat-alliance-format", "player", p.getName());
        java.util.List<Player> targets = new java.util.ArrayList<>();
        if (mode == Mode.GUILD) {
            for (UUID u : g.members.keySet()) {
                Player t = Bukkit.getPlayer(u);
                if (t != null) targets.add(t);
            }
        } else {
            Alliance a = gm.allianceOf(g);
            if (a == null || !g.allianceChat) return;
            for (UUID gid : a.guilds) {
                Guild og = gm.guilds.get(gid);
                if (og == null || !og.allianceChat) continue;
                for (UUID u : og.members.keySet()) {
                    Player t = Bukkit.getPlayer(u);
                    if (t != null) targets.add(t);
                }
            }
        }
        if (fmt == null) return; // format désactivé (false) dans messages.yml
        Component line = Text.c(fmt)
                .replaceText(b -> b.matchLiteral("{message}").replacement(Component.text(message)));
        for (Player t : targets) t.sendMessage(line);
        Bukkit.getConsoleSender().sendMessage(line);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!gm.isEnabled()) return;
        Player p = e.getPlayer();
        Mode m = modes.get(p.getUniqueId());
        if (m != null) {
            if (gm.guildOf(p.getUniqueId()) == null || (m == Mode.ALLIANCE && !hasAllianceChatAccess(p))) {
                modes.remove(p.getUniqueId());
            } else {
                e.setCancelled(true);
                String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
                Bukkit.getScheduler().runTask(plugin, () -> send(p, m, msg));
                return;
            }
        }
        if (plugin.getConfig().getBoolean("tags.chat-prefix", true)) {
            ChatRenderer prev = e.renderer();
            e.renderer((source, name, msg, viewer) -> {
                Component base = prev.render(source, name, msg, viewer);
                Player vp = viewer instanceof Player pl ? pl : null;
                return dm.prefix(source, vp).append(base);
            });
        }
    }
}
