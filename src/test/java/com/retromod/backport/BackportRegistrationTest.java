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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
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
    void lambdaOverTheRemovedListenerInterfaceImplementsConsumerAccept() {
        // Mod Menu: OptionInstance.createBoolean(name, default, value -> ...) with 26.2's own listener type.
        String listener = "net/minecraft/client/OptionInstance$ValueUpdateListener";
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        Type sam = Type.getMethodType("(Ljava/lang/Object;)V");
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitLdcInsn("modmenu.option");
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInvokeDynamicInsn("valueChanged", "()L" + listener + ";", metafactory, sam,
                    new Handle(Opcodes.H_INVOKESTATIC, MOD, "lambda$run$0", "(Ljava/lang/Object;)V", false), sam);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/OptionInstance", "createBoolean",
                    "(Ljava/lang/String;ZL" + listener + ";)Lnet/minecraft/client/OptionInstance;", false);
            mv.visitInsn(Opcodes.POP);
        }));

        InvokeDynamicInsnNode lambda = out.stream().filter(InvokeDynamicInsnNode.class::isInstance)
                .map(InvokeDynamicInsnNode.class::cast).findFirst().orElseThrow();
        assertEquals("accept", lambda.name, "LambdaMetafactory binds this name against Consumer");
        assertEquals("()Ljava/util/function/Consumer;", lambda.desc);
        MethodInsnNode call = out.stream().filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).findFirst().orElseThrow();
        assertEquals("(Ljava/lang/String;ZLjava/util/function/Consumer;)Lnet/minecraft/client/OptionInstance;",
                call.desc, "26.1.2 has createBoolean(String, boolean, Consumer)");
    }

    @Test
    void spriteButtonTooltipBecomesAHelperThatWritesTheBuildersField() throws Exception {
        String builder = SpriteTooltipHelper.BUILDER;
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, builder);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "tooltip",
                    "(Lnet/minecraft/network/chat/Component;)L" + builder + ";", false);
            mv.visitInsn(Opcodes.POP);
        }));
        MethodInsnNode call = out.stream().filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).filter(m -> !m.owner.equals("java/lang/Object"))
                .findFirst().orElseThrow();
        assertEquals(SpriteTooltipHelper.NAME, call.owner);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals(SpriteTooltipHelper.DESC, call.desc);

        byte[] helper = transformer.getSyntheticClasses().get(SpriteTooltipHelper.NAME);
        assertNotNull(helper, "the helper must be registered so it gets embedded in the mod");

        // Run it against stand-ins for the 26.1.2 classes: a Builder with a private tooltip field.
        String component = "net/minecraft/network/chat/Component";
        ClassWriter iface = new ClassWriter(0);
        iface.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, component, null,
                "java/lang/Object", null);
        iface.visitEnd();
        ClassWriter fake = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        fake.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, builder, null, "java/lang/Object", null);
        fake.visitField(Opcodes.ACC_PRIVATE, "tooltip", "L" + component + ";", null, null).visitEnd();
        MethodVisitor init = fake.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        fake.visitEnd();
        java.util.Map<String, byte[]> classes = java.util.Map.of(
                component.replace('/', '.'), iface.toByteArray(),
                builder.replace('/', '.'), fake.toByteArray(),
                SpriteTooltipHelper.NAME.replace('/', '.'), helper);
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                return classes.containsKey(name) ? findClass(name) : super.loadClass(name, resolve);
            }
        };
        Class<?> builderClass = loader.loadClass(builder.replace('/', '.'));
        Class<?> componentClass = loader.loadClass(component.replace('/', '.'));
        Object instance = builderClass.getDeclaredConstructor().newInstance();
        Object text = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {componentClass},
                (proxy, method, args) -> null);

        Object returned = loader.loadClass(SpriteTooltipHelper.NAME.replace('/', '.'))
                .getMethod("tooltip", builderClass, componentClass).invoke(null, instance, text);

        assertTrue(returned == instance, "the builder is returned so the call chain continues");
        java.lang.reflect.Field field = builderClass.getDeclaredField("tooltip");
        field.setAccessible(true);
        assertTrue(field.get(instance) == text, "the tooltip text is stored where build() reads it");
    }

    @Test
    void manualTableRowsAreComplete() {
        // Registration throws on a blank column, so a bad hand edit fails here and not at transform time.
        assertFalse(BackportRegistration.rows("manual.tsv", 7).isEmpty());
        BackportRegistration.register(transformer);
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
    void guiMembersMovedFromMinecraftBecomeHelperCalls() {
        // gui.setScreen(screen) in 26.2 is minecraft.setScreen(screen) in 26.1.2.
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitTypeInsn(Opcodes.CHECKCAST, GUI);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GUI, "setScreen",
                    "(Lnet/minecraft/client/gui/screens/Screen;)V", false);
        }));
        MethodInsnNode call = out.stream().filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).filter(m -> !m.owner.equals("java/lang/Object"))
                .findFirst().orElseThrow();
        assertEquals(BridgeHelper.NAME, call.owner);
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals("(Lnet/minecraft/client/gui/Gui;Lnet/minecraft/client/gui/screens/Screen;)V", call.desc);

        byte[] helper = transformer.getSyntheticClasses().get(BridgeHelper.NAME);
        assertNotNull(helper);
        ClassNode cn = new ClassNode();
        new ClassReader(helper).accept(cn, 0);
        MethodNode bridge = cn.methods.stream().filter(m -> m.name.equals(call.name)).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : bridge.instructions) {
            if (insn instanceof MethodInsnNode m) {
                calls.add(m.owner + "." + m.name);
            }
        }
        assertEquals(List.of("net/minecraft/client/Minecraft.getInstance", "net/minecraft/client/Minecraft.setScreen"),
                calls);
    }

    @Test
    void fieldBackedBridgeReadsTheOlderField() {
        byte[] helper = transformer.getSyntheticClasses().get(BridgeHelper.NAME);
        ClassNode cn = new ClassNode();
        new ClassReader(helper).accept(cn, 0);
        MethodNode screen = cn.methods.stream().filter(m -> m.name.equals("Gui$screen")).findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(screen.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof FieldInsnNode f && f.owner.equals("net/minecraft/client/Minecraft")
                        && f.name.equals("screen")));
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

    private static final String BUILDER = "com/mojang/blaze3d/pipeline/RenderPipeline$Builder";
    private static final String BRIDGE = "com/retromod/legacyapi/backport/render/RenderPipelineBridge";

    @Test
    void drawModeIsTheOlderVertexFormatMode() {
        List<AbstractInsnNode> out = run(mod(mv -> mv.visitFieldInsn(Opcodes.GETSTATIC,
                "com/mojang/blaze3d/PrimitiveTopology", "QUADS", "Lcom/mojang/blaze3d/PrimitiveTopology;")));
        FieldInsnNode field = (FieldInsnNode) out.get(0);
        assertEquals("com/mojang/blaze3d/vertex/VertexFormat$Mode", field.owner);
        assertEquals("Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;", field.desc);
    }

    @Test
    void pipelineBuilderCallsGoThroughTheBridgeWithTheBuilderFirst() {
        String builder = "L" + BUILDER + ";";
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "withVertexBinding",
                    "(ILcom/mojang/blaze3d/vertex/VertexFormat;)" + builder, false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "withPrimitiveTopology",
                    "(Lcom/mojang/blaze3d/PrimitiveTopology;)" + builder, false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "withBindGroupLayout",
                    "(Lcom/mojang/blaze3d/pipeline/BindGroupLayout;)" + builder, false);
            mv.visitInsn(Opcodes.POP);
        }));
        List<MethodInsnNode> calls = out.stream().filter(i -> i instanceof MethodInsnNode)
                .map(i -> (MethodInsnNode) i).toList();
        assertEquals(3, calls.size());
        for (MethodInsnNode call : calls) {
            assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(), call.name);
            assertEquals(BRIDGE, call.owner, call.name);
            assertTrue(call.desc.startsWith("(" + builder), call.desc);
        }
        assertEquals("(" + builder + "Lcom/mojang/blaze3d/vertex/VertexFormat$Mode;)" + builder, calls.get(1).desc);
        assertEquals("(" + builder + "Lcom/retromod/legacyapi/backport/render/BindGroupLayout;)" + builder,
                calls.get(2).desc);
    }

    @Test
    void blendFunctionConstructorTakesSourceFactorsThroughTheBridge() {
        String factor = "Lcom/mojang/blaze3d/platform/BlendFactor;";
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitTypeInsn(Opcodes.NEW, "com/mojang/blaze3d/pipeline/BlendFunction");
            mv.visitInsn(Opcodes.DUP);
            for (int i = 0; i < 4; i++) {
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/mojang/blaze3d/platform/BlendFactor", "ONE", factor);
            }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/mojang/blaze3d/pipeline/BlendFunction", "<init>",
                    "(" + factor.repeat(4) + ")V", false);
            mv.visitInsn(Opcodes.POP);
        }));
        assertTrue(out.stream().noneMatch(i -> i.getOpcode() == Opcodes.NEW), "the constructor call is gone");
        MethodInsnNode call = out.stream().filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                .findFirst().orElseThrow();
        assertEquals("com/retromod/legacyapi/backport/render/BlendBridge", call.owner);
        assertEquals("(" + "Lcom/mojang/blaze3d/platform/SourceFactor;".repeat(4)
                + ")Lcom/mojang/blaze3d/pipeline/BlendFunction;", call.desc);
    }

    @Test
    void rebuiltRenderClassesAreRegisteredForEmbedding() {
        for (String name : List.of("BindGroupLayouts", "BindGroupLayout", "RenderPipelineBridge", "BlendBridge")) {
            assertTrue(transformer.getSyntheticClasses().containsKey(
                    "com/retromod/legacyapi/backport/render/" + name), name + " is not registered");
        }
    }

    @Test
    void pictureInPictureSubclassExtendsTheCompatBaseAndCallsItsConstructor() {
        String old = "net/minecraft/client/gui/render/pip/PictureInPictureRenderer";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/backport/Preview", null, old, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, old, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/backport/Preview")).accept(out, 0);
        String compat = "com/retromod/legacyapi/backport/render/PictureInPictureRendererCompat";
        assertEquals(compat, out.superName);
        MethodInsnNode superCall = (MethodInsnNode) java.util.stream.StreamSupport
                .stream(out.methods.get(0).instructions.spliterator(), false)
                .filter(i -> i instanceof MethodInsnNode).findFirst().orElseThrow();
        assertEquals(compat, superCall.owner);
        assertEquals("()V", superCall.desc);
    }

    @Test
    void vertexFormatBuilderCallsGoThroughTheBridgeWithTheBuilderFirst() {
        String vertexFormat = "com/mojang/blaze3d/vertex/VertexFormat";
        String builder = "L" + vertexFormat + "$Builder;";
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, vertexFormat, "builder", "(I)" + builder, false);
            mv.visitLdcInsn("Position");
            mv.visitFieldInsn(Opcodes.GETSTATIC, "com/mojang/blaze3d/GpuFormat", "RGB32_FLOAT",
                    "Lcom/mojang/blaze3d/GpuFormat;");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, vertexFormat + "$Builder", "addAttribute",
                    "(Ljava/lang/String;Lcom/mojang/blaze3d/GpuFormat;)" + builder, false);
            mv.visitInsn(Opcodes.POP);
        }));
        List<MethodInsnNode> calls = out.stream().filter(i -> i instanceof MethodInsnNode)
                .map(i -> (MethodInsnNode) i).toList();
        String bridge = "com/retromod/legacyapi/backport/render/VertexFormatBridge";
        assertEquals(2, calls.size());
        assertEquals(bridge, calls.get(0).owner);
        assertEquals("(I)" + builder, calls.get(0).desc);
        assertEquals(Opcodes.INVOKESTATIC, calls.get(1).getOpcode());
        assertEquals(bridge, calls.get(1).owner);
        assertEquals("(" + builder + "Ljava/lang/String;Lcom/retromod/legacyapi/backport/render/GpuFormat;)" + builder,
                calls.get(1).desc);
        FieldInsnNode format = (FieldInsnNode) out.stream().filter(i -> i instanceof FieldInsnNode).findFirst()
                .orElseThrow();
        assertEquals("com/retromod/legacyapi/backport/render/GpuFormat", format.owner);
    }

    @Test
    void colorCollectionFieldBecomesAGetterOnTheShim() {
        String collection = "Lnet/minecraft/world/level/block/ColorCollection;";
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/item/Items", "BANNER", collection);
            mv.visitInsn(Opcodes.POP);
        }));
        MethodInsnNode call = (MethodInsnNode) out.stream().filter(i -> i instanceof MethodInsnNode).findFirst()
                .orElseThrow();
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals("com/retromod/legacyapi/backport/render/ColorCollections", call.owner);
        assertEquals("items_BANNER", call.name);
        assertEquals("()Lcom/retromod/legacyapi/backport/render/ColorCollection;", call.desc);
    }

    @Test
    void everyColorTableRowHasAGetterInTheShim() throws Exception {
        java.util.Set<String> getters = new java.util.HashSet<>();
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(
                "com/retromod/legacyapi/backport/render/ColorCollections.class")) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            node.methods.forEach(m -> getters.add(m.name + m.desc));
        }
        List<String[]> rows = BackportRegistration.rows("colors.tsv", 4);
        assertEquals(29, rows.size());
        for (String[] row : rows) {
            String holder = row[0].substring(row[0].lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
            assertTrue(getters.contains(holder + "_" + row[1]
                    + "()Lcom/retromod/legacyapi/backport/render/ColorCollection;"), "no getter for " + row[1]);
        }
    }

    @Test
    void handRendererSubmitIsCalledByItsOlderName() {
        String owner = "net/minecraft/client/renderer/ItemInHandRenderer";
        List<AbstractInsnNode> out = run(mod(mv -> mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner,
                "submitHandsWithItems", "(FLcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V",
                false)));
        assertEquals("renderHandsWithItems", ((MethodInsnNode) out.get(0)).name);
    }

    @Test
    void renderAllFeaturesWithAStorageGoesThroughTheBridge() {
        String dispatcher = "net/minecraft/client/renderer/feature/FeatureRenderDispatcher";
        String storage = "Lnet/minecraft/client/renderer/SubmitNodeStorage;";
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, dispatcher, "renderAllFeatures", "(" + storage + ")V", false);
        }));
        MethodInsnNode call = (MethodInsnNode) out.stream().filter(i -> i instanceof MethodInsnNode).findFirst()
                .orElseThrow();
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals("com/retromod/legacyapi/backport/render/FeatureRenderDispatcherBridge", call.owner);
        assertEquals("(L" + dispatcher + ";" + storage + ")V", call.desc);
    }

    @Test
    void depthCompareConstantsAreMirroredWithoutChaining() {
        String op = "com/mojang/blaze3d/platform/CompareOp";
        String desc = "L" + op + ";";
        String[][] expected = {{"GREATER_THAN_OR_EQUAL", "LESS_THAN_OR_EQUAL"}, {"LESS_THAN_OR_EQUAL", "GREATER_THAN_OR_EQUAL"},
            {"GREATER_THAN", "LESS_THAN"}, {"LESS_THAN", "GREATER_THAN"}, {"EQUAL", "EQUAL"}};
        for (String[] pair : expected) {
            List<AbstractInsnNode> out = run(mod(mv -> mv.visitFieldInsn(Opcodes.GETSTATIC, op, pair[0], desc)));
            assertEquals(pair[1], ((FieldInsnNode) out.get(0)).name, pair[0]);
        }
    }

    @Test
    void depthStateWithBiasGoesThroughTheBridgeThatTurnsTheBiasOver() {
        String state = "com/mojang/blaze3d/pipeline/DepthStencilState";
        String op = "Lcom/mojang/blaze3d/platform/CompareOp;";
        List<AbstractInsnNode> out = run(mod(mv -> {
            mv.visitTypeInsn(Opcodes.NEW, state);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.FCONST_1);
            mv.visitInsn(Opcodes.FCONST_1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, state, "<init>", "(" + op + "ZFF)V", false);
            mv.visitInsn(Opcodes.POP);
        }));
        MethodInsnNode call = out.stream().filter(i -> i instanceof MethodInsnNode).map(i -> (MethodInsnNode) i)
                .findFirst().orElseThrow();
        assertEquals("com/retromod/legacyapi/backport/render/DepthBridge", call.owner);
        assertEquals("depthStencilState", call.name);
        assertTrue(out.stream().noneMatch(i -> i.getOpcode() == Opcodes.NEW), "the constructor call is gone");
    }
}
