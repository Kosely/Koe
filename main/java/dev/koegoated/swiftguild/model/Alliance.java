package dev.koegoated.swiftguild.model;

import java.util.*;

public class Alliance {
    public final UUID id;
    public String name;
    public String tag;
    public final Set<UUID> guilds = new LinkedHashSet<>();
    public final Map<String, Set<String>> roles = new LinkedHashMap<>();
    /** joueur -> rôle d'alliance (absent = rôle par défaut) */
    public final Map<UUID, String> memberRoles = new LinkedHashMap<>();

    public Alliance(UUID id, String name, String tag) {
        this.id = id;
        this.name = name;
        this.tag = tag;
    }
}
