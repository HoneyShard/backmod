package com.retromod.backport;

import com.retromod.backport.ClassSet.Member;

import java.util.HashSet;
import java.util.Set;

/** Answers "does the game jar have this class or member", following supertypes inside the jar. */
public final class HostIndex {

    /** Result of a lookup. UNKNOWN means a supertype outside the jar could supply the member. */
    public enum Presence { FOUND, MISSING, UNKNOWN, CLASS_MISSING }

    /** Methods every class has through java.lang.Object, interfaces included. */
    private static final Set<String> OBJECT_METHODS = Set.of("equals(Ljava/lang/Object;)Z", "hashCode()I",
            "toString()Ljava/lang/String;", "getClass()Ljava/lang/Class;", "notify()V", "notifyAll()V",
            "wait()V", "wait(J)V", "wait(JI)V", "clone()Ljava/lang/Object;", "finalize()V");

    private final ClassSet host;

    public HostIndex(ClassSet host) {
        this.host = host;
    }

    public boolean hasClass(String internalName) {
        return host.contains(internalName);
    }

    /** Looks a member up by name, and by descriptor when {@code desc} is not null. */
    public Presence member(String owner, String name, String desc, boolean method) {
        if (!host.contains(owner)) {
            return Presence.CLASS_MISSING;
        }
        if (method && desc != null && OBJECT_METHODS.contains(name + desc)) {
            return Presence.FOUND;
        }
        boolean[] unknownParent = {false};
        return search(owner, name, desc, method, new HashSet<>(), unknownParent)
                ? Presence.FOUND : unknownParent[0] ? Presence.UNKNOWN : Presence.MISSING;
    }

    private boolean search(String owner, String name, String desc, boolean method, Set<String> seen,
                           boolean[] unknownParent) {
        if (owner == null || !seen.add(owner)) {
            return false;
        }
        ClassSet.Entry cls = host.get(owner);
        if (cls == null) {
            // java/lang/Object has no members a game call needs, anything else may hold one.
            if (!owner.equals("java/lang/Object")) {
                unknownParent[0] = true;
            }
            return false;
        }
        for (Member m : cls.members().values()) {
            if (m.method() == method && m.name().equals(name) && (desc == null || m.desc().equals(desc))) {
                return true;
            }
        }
        if (search(cls.superName(), name, desc, method, seen, unknownParent)) {
            return true;
        }
        for (String itf : cls.interfaces()) {
            if (search(itf, name, desc, method, seen, unknownParent)) {
                return true;
            }
        }
        return false;
    }
}
