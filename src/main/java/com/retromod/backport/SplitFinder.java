package com.retromod.backport;

import com.retromod.backport.ClassSet.Member;
import org.objectweb.asm.commons.Remapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds classes that 26.2 split out of an older class. The new class has no 26.1.2 namesake, but
 * almost all its members already live on one surviving 26.1.2 class.
 */
public final class SplitFinder {

    /** A new class whose members are a subset of one older class, with the field that holds it. */
    public record Split(String newClass, String oldClass, double containment, int members,
                        String holderOwner, String holderField) {
    }

    /**
     * A class needs this many members before a subset match means anything. At 5 to 10, small record
     * classes match unrelated classes by accident (equals, hashCode, accessors).
     */
    static final int MIN_MEMBERS = 20;

    /** Share of the new class's members that must exist on the older class. */
    static final double MIN_CONTAINMENT = 0.9;

    private SplitFinder() {
    }

    /**
     * Looks at every newer class with no older counterpart (not in {@code classMap} and absent from
     * {@code older}) and returns those that fit inside one older class.
     */
    public static List<Split> find(ClassSet older, ClassSet newer, Map<String, String> classMap) {
        Remapper toOlder = new Remapper() {
            @Override
            public String map(String internalName) {
                return classMap.getOrDefault(internalName, internalName);
            }
        };

        // Index: member key -> older classes that declare it.
        Map<String, List<String>> declaredBy = new HashMap<>();
        for (ClassSet.Entry cls : older.all().values()) {
            for (String key : cls.members().keySet()) {
                declaredBy.computeIfAbsent(key, k -> new ArrayList<>()).add(cls.name());
            }
        }

        List<Split> splits = new ArrayList<>();
        for (ClassSet.Entry cls : newer.all().values()) {
            String mapped = classMap.getOrDefault(cls.name(), cls.name());
            if (older.contains(mapped)) {
                continue;
            }
            Set<String> keys = memberKeys(cls, toOlder);
            if (keys.size() < MIN_MEMBERS) {
                continue;
            }
            Map<String, Integer> hits = new HashMap<>();
            for (String key : keys) {
                for (String owner : declaredBy.getOrDefault(key, List.of())) {
                    hits.merge(owner, 1, Integer::sum);
                }
            }
            hits.entrySet().stream().max(Map.Entry.comparingByValue()).ifPresent(best -> {
                double containment = best.getValue() / (double) keys.size();
                if (containment >= MIN_CONTAINMENT) {
                    String[] holder = holderOf(newer, cls.name(), best.getKey(), classMap);
                    splits.add(new Split(cls.name(), best.getKey(), containment, keys.size(),
                            holder[0], holder[1]));
                }
            });
        }
        return splits;
    }

    /** Keys of a class's own members, constructors and class initializers excluded, names mapped. */
    private static Set<String> memberKeys(ClassSet.Entry cls, Remapper toOlder) {
        Set<String> keys = new HashSet<>();
        for (Member m : cls.members().values()) {
            if (m.name().startsWith("<") || (m.access() & org.objectweb.asm.Opcodes.ACC_SYNTHETIC) != 0) {
                continue;
            }
            String desc = m.method() ? toOlder.mapMethodDesc(m.desc()) : toOlder.mapDesc(m.desc());
            keys.add((m.method() ? "M:" : "F:") + m.name() + desc);
        }
        return keys;
    }

    /** The field, on the newer image of {@code oldClass}, whose type is the split-out class. */
    private static String[] holderOf(ClassSet newer, String newClass, String oldClass,
                                     Map<String, String> classMap) {
        String wanted = "L" + newClass + ";";
        for (ClassSet.Entry cls : newer.all().values()) {
            String mapped = classMap.getOrDefault(cls.name(), cls.name());
            if (!mapped.equals(oldClass)) {
                continue;
            }
            for (Member m : cls.members().values()) {
                if (!m.method() && m.desc().equals(wanted)) {
                    return new String[] {cls.name(), m.name()};
                }
            }
        }
        return new String[] {"", ""};
    }
}
