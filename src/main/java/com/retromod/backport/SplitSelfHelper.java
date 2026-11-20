package com.retromod.backport;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Collection;

/** Builds the helper that stands in for a field 26.2 added to hold a class split out of its owner. */
public final class SplitSelfHelper {

    /** Internal name the helper is registered and embedded under. */
    public static final String NAME = "com/retromod/generated/BackmodSplitSelf";

    /** Name of every method in the helper. Overloads differ by descriptor. */
    public static final String METHOD = "self";

    private SplitSelfHelper() {
    }

    /** The helper's method descriptor for a class: it takes the object and returns it unchanged. */
    public static String descriptorFor(String internalName) {
        return "(L" + internalName + ";)L" + internalName + ";";
    }

    /** One {@code static T self(T)} per class, so a rewritten field read keeps its exact type. */
    public static byte[] build(Collection<String> classes) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, NAME, null,
                "java/lang/Object", null);
        for (String cls : classes) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, METHOD,
                    descriptorFor(cls), null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
