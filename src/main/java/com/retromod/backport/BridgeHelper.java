package com.retromod.backport;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;

/**
 * Builds static helpers for members 26.2 moved onto Gui. Each takes the Gui receiver and ignores it,
 * because the only Gui a client has is the one on the Minecraft singleton.
 */
public final class BridgeHelper {

    /** Internal name the helpers are registered and embedded under. */
    public static final String NAME = "com/retromod/generated/BackmodBridges";

    /** The singleton accessor 26.1.2 has on its Minecraft class. */
    private static final String SINGLETON = "getInstance";

    private BridgeHelper() {
    }

    /** One row of bridges.tsv. */
    public record Bridge(String newOwner, String name, String desc, String oldOwner, boolean field,
                         String oldName, String oldDesc) {

        /** Name of the static helper, unique per bridge. */
        public String helperName() {
            return newOwner.substring(newOwner.lastIndexOf('/') + 1) + "$" + name;
        }

        /** Descriptor of the static helper: the receiver first, then the original arguments. */
        public String helperDesc() {
            return "(L" + newOwner + ";" + desc.substring(1);
        }

        static Bridge of(String[] row) {
            return new Bridge(row[0], row[1], row[2], row[3], row[4].equals("field"), row[5], row[6]);
        }
    }

    public static byte[] build(List<Bridge> bridges) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, NAME, null,
                "java/lang/Object", null);
        for (Bridge b : bridges) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, b.helperName(),
                    b.helperDesc(), null, null);
            mv.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, b.oldOwner(), SINGLETON, "()L" + b.oldOwner() + ";",
                    false);
            if (b.field()) {
                mv.visitFieldInsn(Opcodes.GETFIELD, b.oldOwner(), b.oldName(), b.oldDesc());
            } else {
                int slot = 1;
                for (Type arg : Type.getArgumentTypes(b.desc())) {
                    mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
                    slot += arg.getSize();
                }
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, b.oldOwner(), b.oldName(), b.oldDesc(), false);
            }
            mv.visitInsn(Type.getReturnType(b.desc()).getOpcode(Opcodes.IRETURN));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
