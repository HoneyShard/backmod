package com.retromod.backport;

import com.retromod.backport.ClassSet.Entry;
import com.retromod.backport.ClassSet.Member;
import com.retromod.backport.ReferenceAudit.Missing;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Finds mod classes whose game supertypes changed in a way a plain reference check cannot see. */
public final class HierarchyAudit {

    private HierarchyAudit() {
    }

    /** Checks every mod class that has a game class or interface among its supertypes. */
    public static Set<Missing> scan(ClassSet mod, HostIndex host) {
        Set<Missing> found = new LinkedHashSet<>();
        for (Entry cls : mod.all().values()) {
            if (cls.name().startsWith("META-INF/")) {
                continue;
            }
            List<Entry> hostTypes = new ArrayList<>();
            List<Entry> allTypes = new ArrayList<>();
            collectAncestors(cls, mod, host, hostTypes, allTypes);
            if (hostTypes.isEmpty()) {
                continue;
            }
            boolean concrete = (cls.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0;
            if (concrete) {
                unimplemented(cls, hostTypes, allTypes, found);
            }
            if ((cls.access() & Opcodes.ACC_INTERFACE) == 0) {
                changedOverrides(cls, hostTypes, found);
            }
        }
        return found;
    }

    /** Walks the supertypes of a class, splitting game types from the mod's own. */
    private static void collectAncestors(Entry cls, ClassSet mod, HostIndex host, List<Entry> hostTypes,
                                         List<Entry> allTypes) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(cls.superName());
        queue.addAll(cls.interfaces());
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (name == null || !seen.add(name)) {
                continue;
            }
            // The mod's own copy wins, because a mod never shadows a game class by name.
            Entry modType = mod.get(name);
            Entry type = modType != null ? modType : host.entry(name);
            if (type == null) {
                continue;
            }
            allTypes.add(type);
            if (modType == null) {
                hostTypes.add(type);
            }
            queue.add(type.superName());
            queue.addAll(type.interfaces());
        }
    }

    /** Reports game abstract methods that nothing in the mod's class chain implements. */
    private static void unimplemented(Entry cls, List<Entry> hostTypes, List<Entry> allTypes,
                                      Set<Missing> found) {
        Set<String> implemented = new HashSet<>();
        for (Entry type : concat(cls, allTypes)) {
            for (Member m : type.members().values()) {
                boolean abstractMethod = (m.access() & Opcodes.ACC_ABSTRACT) != 0;
                if (m.method() && !abstractMethod && (m.access() & Opcodes.ACC_STATIC) == 0) {
                    implemented.add(m.key());
                }
            }
        }
        for (Entry type : hostTypes) {
            for (Member m : type.members().values()) {
                boolean abstractMethod = (m.access() & Opcodes.ACC_ABSTRACT) != 0;
                if (m.method() && abstractMethod && !implemented.contains(m.key())
                        && !HostIndex.isObjectMethod(m.name(), m.desc())) {
                    found.add(new Missing("abstract method " + type.name() + "." + m.key()
                            + " has no implementation in this mod", cls.name(), true));
                }
            }
        }
    }

    /** Reports mod methods that share a name and arity with an inherited game method but not its descriptor. */
    private static void changedOverrides(Entry cls, List<Entry> hostTypes, Set<Missing> found) {
        for (Member m : cls.members().values()) {
            if (!m.method() || m.name().startsWith("<") || (m.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE
                    | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
                continue;
            }
            int arity = Type.getArgumentTypes(m.desc()).length;
            List<String> sameShape = new ArrayList<>();
            boolean exact = false;
            for (Entry type : hostTypes) {
                for (Member h : type.members().values()) {
                    if (!h.method() || !h.name().equals(m.name()) || (h.access() & (Opcodes.ACC_STATIC
                            | Opcodes.ACC_PRIVATE)) != 0) {
                        continue;
                    }
                    // A covariant or generic override carries a bridge with the game's exact descriptor.
                    if (h.desc().equals(m.desc()) || cls.members().containsKey("M:" + h.key())) {
                        exact = true;
                    } else if (Type.getArgumentTypes(h.desc()).length == arity) {
                        sameShape.add(type.name() + "." + h.key());
                    }
                }
            }
            // An exact match is a real override. Without one, the game never calls this method.
            if (!exact && !sameShape.isEmpty()) {
                found.add(new Missing("method " + cls.name() + "." + m.key() + " may override "
                        + sameShape.get(0) + ", which the game calls with a different signature", cls.name()));
            }
        }
    }

    private static List<Entry> concat(Entry first, List<Entry> rest) {
        List<Entry> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }
}
