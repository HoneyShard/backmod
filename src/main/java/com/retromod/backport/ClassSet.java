package com.retromod.backport;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** The classes of one game jar, keyed by internal name, with a hash of each method body. */
public final class ClassSet {

    /**
     * A field or method as the differ sees it. {@code body} is a hash of the method's instructions
     * with class names mapped, or null for fields and methods without code.
     */
    public record Member(String name, String desc, int access, boolean method, String body) {
        public boolean visibleToMods() {
            boolean exposed = (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
            boolean compilerMade = (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
            return exposed && !compilerMade;
        }

        public String key() {
            return name + desc;
        }
    }

    /** One class with its supertypes and members. */
    public record Entry(String name, String superName, java.util.List<String> interfaces, int access,
                        Map<String, Member> members) {
    }

    private static final Remapper IDENTITY = new Remapper() { };

    private final Map<String, Entry> classes = new LinkedHashMap<>();

    /** Reads every class in a jar whose name starts with one of the given prefixes. */
    public static ClassSet fromJar(Path jar, String... prefixes) throws IOException {
        return fromJar(jar, IDENTITY, prefixes);
    }

    /** Reads a jar and maps class names in method bodies, so bodies from two jars can be compared. */
    public static ClassSet fromJar(Path jar, Remapper bodyNames, String... prefixes) throws IOException {
        ClassSet set = new ClassSet();
        try (JarFile file = new JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String path = entry.getName();
                if (!path.endsWith(".class") || !hasPrefix(path, prefixes)) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    set.add(in.readAllBytes(), bodyNames);
                }
            }
        }
        return set;
    }

    /** Adds one class from its bytes, without class-name mapping in bodies. */
    public void add(byte[] bytes) {
        add(bytes, IDENTITY);
    }

    /** Adds one class from its bytes. Debug info and frames are skipped. */
    public void add(byte[] bytes, Remapper bodyNames) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Map<String, Member> members = new LinkedHashMap<>();
        for (FieldNode f : node.fields) {
            Member m = new Member(f.name, f.desc, f.access, false, null);
            members.put("F:" + m.key(), m);
        }
        for (MethodNode m : node.methods) {
            Member member = new Member(m.name, m.desc, m.access, true, bodyHash(m, bodyNames));
            members.put("M:" + member.key(), member);
        }
        classes.put(node.name, new Entry(node.name, node.superName,
                node.interfaces == null ? java.util.List.of() : node.interfaces, node.access, members));
    }

    /** Hashes a method's instructions, mapping class names, so equal logic gives equal hashes. */
    static String bodyHash(MethodNode method, Remapper names) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return null;
        }
        Map<AbstractInsnNode, Integer> index = new java.util.IdentityHashMap<>();
        int i = 0;
        for (AbstractInsnNode insn : method.instructions) {
            index.put(insn, i++);
        }
        StringBuilder sb = new StringBuilder();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() < 0) {
                continue;
            }
            sb.append(insn.getOpcode());
            if (insn instanceof MethodInsnNode m) {
                sb.append(' ').append(names.map(m.owner)).append('.').append(m.name)
                        .append(names.mapMethodDesc(m.desc));
            } else if (insn instanceof FieldInsnNode f) {
                sb.append(' ').append(names.map(f.owner)).append('.').append(f.name)
                        .append(names.mapDesc(f.desc));
            } else if (insn instanceof TypeInsnNode t) {
                sb.append(' ').append(names.map(t.desc));
            } else if (insn instanceof LdcInsnNode l) {
                sb.append(' ').append(l.cst instanceof org.objectweb.asm.Type t
                        ? names.mapDesc(t.getDescriptor()) : String.valueOf(l.cst));
            } else if (insn instanceof IntInsnNode n) {
                sb.append(' ').append(n.operand);
            } else if (insn instanceof VarInsnNode v) {
                sb.append(' ').append(v.var);
            } else if (insn instanceof IincInsnNode n) {
                sb.append(' ').append(n.var).append(',').append(n.incr);
            } else if (insn instanceof JumpInsnNode j) {
                sb.append(' ').append(index.get(j.label));
            } else if (insn instanceof InvokeDynamicInsnNode d) {
                sb.append(' ').append(d.name).append(names.mapMethodDesc(d.desc));
            } else if (insn instanceof MultiANewArrayInsnNode a) {
                sb.append(' ').append(names.mapDesc(a.desc)).append(',').append(a.dims);
            } else if (insn instanceof TableSwitchInsnNode ts) {
                sb.append(' ').append(ts.min).append(',').append(ts.max).append(',')
                        .append(index.get(ts.dflt));
                ts.labels.forEach(l -> sb.append(',').append(index.get(l)));
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                sb.append(' ').append(index.get(ls.dflt)).append(ls.keys);
                ls.labels.forEach(l -> sb.append(',').append(index.get(l)));
            }
            sb.append(';');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Entry get(String name) {
        return classes.get(name);
    }

    public boolean contains(String name) {
        return classes.containsKey(name);
    }

    public Map<String, Entry> all() {
        return Collections.unmodifiableMap(classes);
    }

    private static boolean hasPrefix(String path, String[] prefixes) {
        if (prefixes.length == 0) {
            return true;
        }
        for (String p : prefixes) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
