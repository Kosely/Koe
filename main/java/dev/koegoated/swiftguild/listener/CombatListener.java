package dev.koegoated.swiftguild.listener;

import dev.koegoated.swiftguild.SwiftGuildPlugin;
import dev.koegoated.swiftguild.manager.GuildManager;
import dev.koegoated.swiftguild.manager.WarManager;
import dev.koegoated.swiftguild.model.Alliance;
import dev.koegoated.swiftguild.model.Guild;
import dev.koegoated.swiftguild.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

/** Protection personnelle contre les dégâts alliés + points de guerre par kill. */
public final class CombatListener implements Listener {
    private final SwiftGuildPlugin plugin;
    private final GuildManager gm;
    private final WarManager wars;

    public CombatListener(SwiftGuildPlugin plugin, GuildManager gm, WarManager wars) {
        this.plugin = plugin;
        this.gm = gm;
        this.wars = wars;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!gm.isEnabled() || !(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;

        Guild ga = gm.guildOf(attacker.getUniqueId()), gv = gm.guildOf(victim.getUniqueId());
        if (ga == null || gv == null || ga.id.equals(gv.id)) return;
        Alliance aa = gm.allianceOf(ga);
        if (aa == null || aa != gm.allianceOf(gv)) return;
        if (gm.allyProtection(attacker.getUniqueId())) {
            e.setCancelled(true);
            String m = plugin.text("ally-hit-blocked");
            if (m != null) attacker.sendActionBar(Text.c(m));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (!gm.isEnabled()) return;
        wars.handleKill(e.getEntity(), e.getEntity().getKiller());
    }
}
