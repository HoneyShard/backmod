/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import java.util.List;
import java.util.Locale;

/** Picks which of several vertex elements with the same layout a named attribute stands for. */
public final class ElementChoice {

    private ElementChoice() {}

    /** Lower-case letters and digits only, so {@code LineWidth} and {@code LINE_WIDTH} compare equal. */
    static String normalize(String name) {
        return name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the index of the candidate to use, or -1 when every candidate is taken. Each entry of
     * {@code candidateNames} lists the names one element goes by, separated by {@code |}. A candidate that
     * answers to the attribute's name wins, because UV1 and UV2 share one layout and the mod writes UV2 by name.
     * Otherwise the first candidate not yet used is taken.
     */
    public static int choose(String attribute, List<String> candidateNames, List<Boolean> used) {
        String wanted = normalize(attribute);
        for (int i = 0; i < candidateNames.size(); i++) {
            if (used.get(i)) {
                continue;
            }
            for (String name : candidateNames.get(i).split("\\|")) {
                if (normalize(name).equals(wanted)) {
                    return i;
                }
            }
        }
        for (int i = 0; i < candidateNames.size(); i++) {
            if (!used.get(i)) {
                return i;
            }
        }
        return -1;
    }
}
