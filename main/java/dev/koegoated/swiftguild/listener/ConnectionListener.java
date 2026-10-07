package dev.koegoated.swiftguild.listener;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class ConnectionListener implements Listener {
    private final SwiftGuildPlugin plugin;

    public ConnectionListener(SwiftGuildPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.display().requestRefresh();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.chat().setMode(e.getPlayer().getUniqueId(), null);
        plugin.createMenu().forget(e.getPlayer().getUniqueId());
        plugin.display().requestRefresh();
    }
}
