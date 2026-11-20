package com.retromod.backport;

import com.retromod.backport.ClassSet.Member;
import org.objectweb.asm.commons.Remapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds members 26.2 moved onto another class: same name and descriptor, present on one older class
 * and gone from it, new on a different class.
 */
public final class HopFinder {

    /** A member that 26.1.2 reached on {@code oldOwner} and 26.2 reaches on {@code newOwner}. */
    public record Hop(String newOwner, String name, String desc, String oldOwner, boolean method,
                      boolean oldIsStatic) {
    }

    private HopFinder() {
    }

    public static List<Hop> find(ClassSet older, ClassSet newer, Map<String, String> classMap,
                                 List<MemberDiff.Finding> findings) {
        Remapper toOlder = new Remapper() {
            @Override
            public String map(String internalName) {
                return classMap.getOrDefault(internalName, internalName);
            }
        };
        Map<String, String> olderToNewer = new HashMap<>();
        classMap.forEach((n, o) -> olderToNewer.put(o, n));

        Map<String, List<ClassSet.Entry>> declaredBy = new HashMap<>();
        for (ClassSet.Entry cls : older.all().values()) {
            for (Map.Entry<String, Member> m : cls.members().entrySet()) {
                declaredBy.computeIfAbsent(m.getKey(), k -> new ArrayList<>()).add(cls);
            }
        }

        List<Hop> hops = new ArrayList<>();
        for (MemberDiff.Finding f : findings) {
            if (f.kind() != MemberDiff.Kind.NO_COUNTERPART || f.newMember() == null) {
                continue;
            }
            Member m = f.newMember();
            String desc = m.method() ? toOlder.mapMethodDesc(m.desc()) : toOlder.mapDesc(m.desc());
            String key = (m.method() ? "M:" : "F:") + m.name() + desc;
            List<ClassSet.Entry> sources = new ArrayList<>();
            for (ClassSet.Entry cand : declaredBy.getOrDefault(key, List.of())) {
                Member old = cand.members().get(key);
                String candNew = olderToNewer.getOrDefault(cand.name(), cand.name());
                ClassSet.Entry now = newer.get(candNew);
                boolean goneThere = now != null && !hasKeyMapped(now, key, toOlder);
                if (!cand.name().equals(f.oldOwner()) && old.visibleToMods() && goneThere) {
                    sources.add(cand);
                }
            }
            // Only an unambiguous source counts. Two classes that both lost it are not a move.
            if (sources.size() == 1) {
                Member old = sources.get(0).members().get(key);
                hops.add(new Hop(f.newOwner(), m.name(), desc, sources.get(0).name(), m.method(),
                        (old.access() & org.objectweb.asm.Opcodes.ACC_STATIC) != 0));
            }
        }
        return hops;
    }

    private static boolean hasKeyMapped(ClassSet.Entry cls, String key, Remapper toOlder) {
        for (Member m : cls.members().values()) {
            String d = m.method() ? toOlder.mapMethodDesc(m.desc()) : toOlder.mapDesc(m.desc());
            if (((m.method() ? "M:" : "F:") + m.name() + d).equals(key)) {
                return true;
            }
        }
        return false;
    }
}
