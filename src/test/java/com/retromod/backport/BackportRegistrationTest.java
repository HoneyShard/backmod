package com.retromod.backport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Runs a 26.2-shaped class through the transformer with only the backport tables registered. */
class BackportRegistrationTest {

    private static final String MOD = "test/backport/Mod";
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static final String HUD = "net/minecraft/client/gui/Hud";

    private RetromodTransformer transformer;
    private String savedTarget;

    @BeforeEach
    void setUp() {
        savedTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = BackportRegistration.TARGET;
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        BackportRegistration.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    /** Builds a mod class whose static method {@code run()} executes the given body. */
    private static byte[] mod(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private List<AbstractInsnNode> run(byte[] input) {
        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(input, MOD)).accept(cn, 0);
        MethodNode run = cn.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (AbstractInsnNode insn : run.instructions) {
            if (insn.getOpcode() >= 0) {
                insns.add(insn);
            }
        }
        return insns;
    }

    @Test
    void movedClassIsCalledByItsOlderName() {
        List<AbstractInsnNode> out = run(mod(mv -> mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/minecraft/advancements/triggers/CriteriaTriggers", "anything", "()V", false)));
        MethodInsnNode call = (MethodInsnNode) out.get(0);
        assertEquals("net/minecraft/advancements/CriteriaTriggers", call.owner);
    }

    @Test
    void verifiedMethodRenameIsApplied() {
        // Take the first shipped row, so the test follows the data and not a hard-coded name.
        String[] row = BackportRegistration.rows("methods.tsv", 6).get(0);
        String desc = row[2];
        List<AbstractInsnNode> out = run(mod(mv -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, row[0], row[1],
                desc, false)));
        MethodInsnNode call = out.stream().filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).findFirst().orElseThrow();
        assertEquals(row[4], call.name, "the 26.2 method name must become its 26.1.2 name");
        assertEquals(row[3], call.owner);
    }

    @Test
    void splitHudBecomesGuiAndTheHolderFieldReadDisappears() {
        // Minecraft.gui.hud.getGuiTicks() in 26.2 is gui.getGuiTicks() in 26.1.2.
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, GUI);
            mv.visitFieldInsn(Opcodes.GETFIELD, GUI, "hud", "L" + HUD + ";");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HUD, "getGuiTicks", "()I", false);
            mv.visitInsn(Opcodes.POP);
        }));

        assertTrue(out.stream().noneMatch(i -> i instanceof FieldInsnNode f && f.name.equals("hud")),
                "the hud field does not exist in 26.1.2");
        MethodInsnNode self = out.stream().filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).filter(m -> m.name.equals("self")).findFirst().orElseThrow();
        assertEquals(SplitSelfHelper.NAME, self.owner);
        assertEquals(SplitSelfHelper.descriptorFor(GUI), self.desc);
        MethodInsnNode ticks = out.stream().filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).filter(m -> m.name.equals("getGuiTicks")).findFirst().orElseThrow();
        assertEquals(GUI, ticks.owner);
        assertTrue(out.stream().noneMatch(i -> i instanceof MethodInsnNode m && m.owner.equals(HUD)));
    }

    @Test
    void splitHelperIsRegisteredAsASyntheticClass() {
        byte[] helper = transformer.getSyntheticClasses().get(SplitSelfHelper.NAME);
        assertNotNull(helper, "the helper must be registered so it gets embedded in the mod");
        ClassNode cn = new ClassNode();
        new ClassReader(helper).accept(cn, 0);
        assertTrue(cn.methods.stream().anyMatch(m -> m.desc.equals(SplitSelfHelper.descriptorFor(GUI))));
    }

    @Test
    void staticConstantSplitsRedirectToTheOlderClass() {
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/entity/EntityTypes", "ZOMBIE",
                    "Lnet/minecraft/world/entity/EntityType;");
            mv.visitInsn(Opcodes.POP);
        }));
        FieldInsnNode read = (FieldInsnNode) out.get(0);
        assertEquals("net/minecraft/world/entity/EntityType", read.owner);
        assertEquals("ZOMBIE", read.name);
    }

    @Test
    void onlyFabricAndQuiltFrom262To2612AreSupported() {
        assertTrue(BackportRegistration.supports("fabric", "26.2", "26.1.2"));
        assertTrue(BackportRegistration.supports("quilt", "26.2.1", "26.1.2"));
        assertFalse(BackportRegistration.supports("forge", "26.2", "26.1.2"));
        assertFalse(BackportRegistration.supports("neoforge", "26.2", "26.1.2"));
        assertFalse(BackportRegistration.supports("fabric", "26.3", "26.1.2"));
        assertFalse(BackportRegistration.supports("fabric", "26.2", "26.1"));
        assertFalse(BackportRegistration.supports("fabric", "26.2", "26.1.1"));
        assertFalse(BackportRegistration.supports("fabric", null, "26.1.2"));
    }

    @Test
    void metadataGetsTheTargetPinAndAStampEvenWithoutAMinecraftDependency() {
        String json = "{\"schemaVersion\":1,\"id\":\"m\",\"depends\":{\"fabricloader\":\">=0.15\"}}";
        String out = new String(BackportMetadata.apply(json.getBytes(), "26.2", "26.1.2"));
        assertTrue(out.contains("\"minecraft\":\"26.1.2\""), out);
        assertTrue(out.contains("\"backported_from\":\"26.2\""), out);

        String withOld = "{\"depends\":{\"minecraft\":\">=26.2\"},\"custom\":{\"x\":1}}";
        String out2 = new String(BackportMetadata.apply(withOld.getBytes(), "26.2", "26.1.2"));
        assertTrue(out2.contains("\"minecraft\":\"26.1.2\"") && out2.contains("\"x\":1"), out2);
        assertEquals("not json", new String(BackportMetadata.apply("not json".getBytes(), "26.2", "26.1.2")));
    }
}
