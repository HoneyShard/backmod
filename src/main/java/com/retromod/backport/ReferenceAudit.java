package com.retromod.backport;

import com.retromod.backport.HostIndex.Presence;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Lists game classes and members a jar uses that the target game jar does not have. */
public final class ReferenceAudit {

    private static final String[] GAME = {"net/minecraft/", "com/mojang/blaze3d/", "com/mojang/realmsclient/",
        "com/mojang/math/"};

    private ReferenceAudit() {
    }

    /**
     * One missing reference, as the report shows it. A structural one means a class that extends a
     * game type cannot be built or used as written, so it fails when the class loads, not when a call runs.
     */
    public record Missing(String what, String usedBy, boolean structural) {
        public Missing(String what, String usedBy) {
            this(what, usedBy, false);
        }
    }

    /** Scans every class in {@code jar} that is not under a Fabric, Mixin or embedded helper path. */
    public static Set<Missing> scan(Path jar, HostIndex host) throws IOException {
        Set<Missing> missing = new LinkedHashSet<>();
        // Look at what the mod's own classes inherit from the game before the per-reference scan.
        missing.addAll(HierarchyAudit.scan(ClassSet.fromJar(jar), host));
        try (JarFile file = new JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    new ClassReader(in.readAllBytes()).accept(new Scanner(host, missing),
                            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
        return missing;
    }

    private static boolean isGame(String internalName) {
        for (String p : GAME) {
            if (internalName.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** Visits every type, field and method reference in one class. */
    private static final class Scanner extends ClassVisitor {
        private final HostIndex host;
        private final Set<Missing> missing;
        private String current = "?";
        private String superName;

        Scanner(HostIndex host, Set<Missing> missing) {
            super(Opcodes.ASM9);
            this.host = host;
            this.missing = missing;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            current = name;
            this.superName = superName;
            type(superName);
            if (interfaces != null) {
                for (String i : interfaces) {
                    type(i);
                }
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
            descriptor(desc);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
            descriptor(desc);
            boolean constructor = name.equals("<init>");
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String t) {
                    type(t);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String n, String d) {
                    member(owner, n, null, false, false);
                    descriptor(d);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                    // A super constructor the game lacks stops the class from loading, so it is structural.
                    boolean superCall = constructor && opcode == Opcodes.INVOKESPECIAL && n.equals("<init>")
                            && owner.equals(superName);
                    member(owner, n, d, true, superCall);
                    descriptor(d);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type t && t.getSort() == Type.OBJECT) {
                        type(t.getInternalName());
                    }
                }
            };
        }

        private void descriptor(String desc) {
            if (desc == null) {
                return;
            }
            for (Type t : desc.startsWith("(") ? argsAndReturn(desc) : new Type[] {Type.getType(desc)}) {
                Type element = t.getSort() == Type.ARRAY ? t.getElementType() : t;
                if (element.getSort() == Type.OBJECT) {
                    type(element.getInternalName());
                }
            }
        }

        private Type[] argsAndReturn(String methodDesc) {
            Type[] args = Type.getArgumentTypes(methodDesc);
            Type[] all = java.util.Arrays.copyOf(args, args.length + 1);
            all[args.length] = Type.getReturnType(methodDesc);
            return all;
        }

        private void type(String internalName) {
            if (internalName == null) {
                return;
            }
            String name = internalName.startsWith("[") ? Type.getType(internalName).getElementType()
                    .getInternalName() : internalName;
            if (isGame(name) && !host.hasClass(name)) {
                missing.add(new Missing("class " + name, current));
            }
        }

        private void member(String owner, String name, String desc, boolean method, boolean structural) {
            if (owner.startsWith("[") || !isGame(owner)) {
                return;
            }
            Presence p = host.member(owner, name, desc, method);
            if (p == Presence.CLASS_MISSING) {
                missing.add(new Missing("class " + owner, current));
            } else if (p == Presence.MISSING) {
                missing.add(new Missing((method ? "method " : "field ") + owner + "." + name
                        + (desc == null ? "" : desc), current, structural));
            }
        }
    }
}
