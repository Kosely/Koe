package dev.koegoated.swiftguild.model;

import java.util.*;

public class Guild {
    public final UUID id;
    public String name;
    public String tag;
    public boolean allianceChat;
    public long createdAt = System.currentTimeMillis();
    /** MOTD (null = aucun). */
    public String motd;
    /** Code couleur legacy du tag (ex. 'f', 'b') : sert au tag et à la couleur de la bannière. */
    public char tagColor = 'f';
    /** Chat de guilde muet (aucun message ne passe). */
    public boolean chatMuted;
    public UUID allianceId;
    /** membre -> nom du rôle */
    public final Map<UUID, String> members = new LinkedHashMap<>();
    /** rôle -> permissions */
    public final Map<String, Set<String>> roles = new LinkedHashMap<>();
    public final Set<UUID> enemies = new LinkedHashSet<>();

    public Guild(UUID id, String name, String tag) {
        this.id = id;
        this.name = name;
        this.tag = tag;
    }
}
