package com.retromod.backport;

import com.retromod.backport.ClassSet.Member;
import org.objectweb.asm.commons.Remapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds what a newer game jar exposes that an older one lacks, and matches it to older members. */
public final class MemberDiff {

    /** How a newer member relates to the older jar. */
    public enum Kind {
        /** One removed member in the older class has the same descriptor, and nothing else competes. */
        RENAME,
        /** Several removed members or several new members share the descriptor. */
        AMBIGUOUS_RENAME,
        /** The older class has a member with this name but another descriptor. */
        SIGNATURE_CHANGE,
        /** The older class has nothing that could stand in for this member. */
        NO_COUNTERPART,
        /** The whole class is absent from the older jar. */
        CLASS_NO_COUNTERPART
    }

    /** How strongly a rename is supported. */
    public enum Confidence {
        /** The method bodies are identical once class names are mapped. */
        BODY_MATCH,
        /** The same rename appears, with the same descriptor, in several unrelated classes. */
        PATTERN,
        /** Only the descriptor and uniqueness support it. Reviewed by hand, never applied. */
        DESCRIPTOR_ONLY,
        NOT_APPLICABLE
    }

    /** One newer member and the older members that might answer for it. */
    public record Finding(Kind kind, String newOwner, Member newMember, String oldOwner,
                          List<Member> oldCandidates, Confidence confidence) {
        Finding(Kind kind, String newOwner, Member newMember, String oldOwner, List<Member> oldCandidates) {
            this(kind, newOwner, newMember, oldOwner, oldCandidates, Confidence.NOT_APPLICABLE);
        }
    }

    /** All findings plus the count of members that already match. */
    public record Result(List<Finding> findings, int matchingMembers, int comparedClasses) {
    }

    private MemberDiff() {
    }

    /**
     * Compares each newer class with its older counterpart.
     *
     * @param classMap newer class name to older class name, for classes that moved or were renamed
     */
    public static Result diff(ClassSet older, ClassSet newer, Map<String, String> classMap) {
        Remapper toOlder = new Remapper() {
            @Override
            public String map(String internalName) {
                return classMap.getOrDefault(internalName, internalName);
            }
        };

        List<Finding> findings = new ArrayList<>();
        int matching = 0;
        int compared = 0;

        for (ClassSet.Entry cls : newer.all().values()) {
            String oldName = classMap.getOrDefault(cls.name(), cls.name());
            ClassSet.Entry oldCls = older.get(oldName);
            if (oldCls == null) {
                findings.add(new Finding(Kind.CLASS_NO_COUNTERPART, cls.name(), null, null, List.of()));
                continue;
            }
            compared++;

            Set<String> newKeys = new HashSet<>();
            List<Member> missing = new ArrayList<>();
            Map<Member, String> missingDesc = new LinkedHashMap<>();
            for (Member m : cls.members().values()) {
                String desc = m.method() ? toOlder.mapMethodDesc(m.desc()) : toOlder.mapDesc(m.desc());
                newKeys.add(prefix(m) + m.name() + desc);
                if (!m.visibleToMods() || m.name().equals("<clinit>")) {
                    continue;
                }
                if (existsInOlder(older, oldName, prefix(m) + m.name() + desc, new HashSet<>())) {
                    matching++;
                } else {
                    missing.add(m);
                    missingDesc.put(m, desc);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }

            List<Member> removed = new ArrayList<>();
            for (Member m : oldCls.members().values()) {
                if (m.visibleToMods() && !m.name().equals("<clinit>")
                        && !newKeys.contains(prefix(m) + m.name() + m.desc())) {
                    removed.add(m);
                }
            }

            for (Member m : missing) {
                findings.add(classify(cls.name(), oldName, m, missingDesc, missing, removed));
            }
        }
        return new Result(findings, matching, compared);
    }

    /** Decides how one missing member relates to the removed members of the older class. */
    private static Finding classify(String newOwner, String oldOwner, Member m,
                                    Map<Member, String> missingDesc, List<Member> missing,
                                    List<Member> removed) {
        String desc = missingDesc.get(m);
        List<Member> sameDesc = new ArrayList<>();
        List<Member> sameName = new ArrayList<>();
        for (Member r : removed) {
            if (r.method() != m.method()) {
                continue;
            }
            if (r.desc().equals(desc)) {
                sameDesc.add(r);
            } else if (r.name().equals(m.name())) {
                sameName.add(r);
            }
        }
        long competingNew = missing.stream()
                .filter(o -> o.method() == m.method() && missingDesc.get(o).equals(desc)).count();

        if (sameDesc.size() == 1 && competingNew == 1) {
            Member old = sameDesc.get(0);
            Confidence confidence = m.body() != null && m.body().equals(old.body())
                    ? Confidence.BODY_MATCH : Confidence.DESCRIPTOR_ONLY;
            return new Finding(Kind.RENAME, newOwner, m, oldOwner, sameDesc, confidence);
        }
        if (!sameDesc.isEmpty()) {
            return new Finding(Kind.AMBIGUOUS_RENAME, newOwner, m, oldOwner, sameDesc);
        }
        if (!sameName.isEmpty()) {
            return new Finding(Kind.SIGNATURE_CHANGE, newOwner, m, oldOwner, sameName);
        }
        return new Finding(Kind.NO_COUNTERPART, newOwner, m, oldOwner, List.of());
    }

    /** Looks for a member on a class or anything it extends or implements in the older jar. */
    private static boolean existsInOlder(ClassSet older, String owner, String key, Set<String> seen) {
        if (owner == null || !seen.add(owner)) {
            return false;
        }
        ClassSet.Entry cls = older.get(owner);
        if (cls == null) {
            return false;
        }
        if (cls.members().containsKey(key)) {
            return true;
        }
        if (existsInOlder(older, cls.superName(), key, seen)) {
            return true;
        }
        for (String itf : cls.interfaces()) {
            if (existsInOlder(older, itf, key, seen)) {
                return true;
            }
        }
        return false;
    }

    private static String prefix(Member m) {
        return m.method() ? "M:" : "F:";
    }
}
