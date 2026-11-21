package com.retromod.backport;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Stands in for {@code SpriteIconButton.Builder.tooltip(Component)}, which 26.2 added.
 *
 * <p>26.1.2's builder keeps the same private {@code tooltip} field and uses it in {@code build()}, but only
 * {@code withTootip()} sets it, and that sets it to the button's own message. A mod that passes a different
 * text would lose it, so the helper writes the field directly.
 *
 * <p>The generated method has no exception handler. If the field is ever missing, the reflection error
 * reaches the caller instead of being swallowed, and the helper is only registered for 26.1.2, where it exists.
 */
public final class SpriteTooltipHelper {

    /** Internal name the helper is registered and embedded under. */
    public static final String NAME = "com/retromod/generated/BackmodSpriteButton";

    public static final String BUILDER = "net/minecraft/client/gui/components/SpriteIconButton$Builder";
    private static final String COMPONENT = "net/minecraft/network/chat/Component";

    /** Name of the 26.2 builder method and of this helper's method. */
    public static final String METHOD = "tooltip";

    /** {@code (Builder, Component) -> Builder}: the receiver first, as for the Gui bridges. */
    public static final String DESC = "(L" + BUILDER + ";L" + COMPONENT + ";)L" + BUILDER + ";";

    private SpriteTooltipHelper() {
    }

    public static byte[] build() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, NAME, null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, METHOD, DESC, null,
                new String[] {"java/lang/Exception"});
        mv.visitCode();
        // Field field = Builder.class.getDeclaredField("tooltip");
        mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(BUILDER));
        mv.visitLdcInsn("tooltip");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        // field.setAccessible(true);
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false);
        // field.set(builder, component);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "set",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
