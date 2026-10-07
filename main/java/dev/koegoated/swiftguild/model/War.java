package dev.koegoated.swiftguild.model;

import java.util.*;

public class War {
    public final UUID id;
    public final List<UUID> sideA = new ArrayList<>();
    public final List<UUID> sideB = new ArrayList<>();
    /** guilde -> [kills, morts] */
    public final Map<UUID, int[]> stats = new HashMap<>();
    /** snapshot des tags (les guildes peuvent disparaître) */
    public final Map<UUID, String> tags = new HashMap<>();
    public int pointsPerKill;
    public int objective;
    public int durationMinutes;
    public long secondsLeft;
    public boolean active = true;
    /** 0 = aucun, 1 = camp A, 2 = camp B, 3 = égalité */
    public int winner;
    public long startedAt = System.currentTimeMillis();

    public War(UUID id) {
        this.id = id;
    }

    public int[] stat(UUID guild) {
        return stats.computeIfAbsent(guild, k -> new int[2]);
    }

    public int points(UUID guild) {
        return stat(guild)[0] * pointsPerKill;
    }

    public int sidePoints(List<UUID> side) {
        int p = 0;
        for (UUID g : side) p += points(g);
        return p;
    }

    public String label(List<UUID> side) {
        StringJoiner j = new StringJoiner(" + ");
        for (UUID g : side) j.add(tags.getOrDefault(g, "?"));
        return j.toString();
    }

    public boolean involves(UUID guild) {
        return sideA.contains(guild) || sideB.contains(guild);
    }
}
