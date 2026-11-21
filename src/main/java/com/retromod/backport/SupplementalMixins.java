package com.retromod.backport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Adds mixins to a converted mod where 26.1.2 lacks the call site the mod hooks in 26.2.
 *
 * <p>Jacaranda draws its queued effects from two hooks that wrap 26.2-only calls, so on 26.1.2 Mixin skips
 * them and the effects never draw while the item is at rest. The same hooks are written here against 26.1.2.
 * Its direct GPU draw (`GpuKept`) needs the 26.2 render pass, so it is switched off and the effects take the
 * slower submit path that the settings preview already uses.
 */
public final class SupplementalMixins {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private static final String METADATA = "fabric.mod.json";
    private static final String CONFIG = "backmod-jacaranda.mixins.json";
    private static final String PACKAGE = "backmod/supplement/jacaranda";
    static final String MIXIN_CLASS = PACKAGE + "/GameRendererSupplement";
    static final String KEPT_CLASS = PACKAGE + "/GpuKeptSupplement";

    private static final String WORLD_PASS = "dev/jacaranda/mc/WorldPass";
    private static final String ARMOR_HOOK = "dev/jacaranda/mc/ArmorHook";
    private static final String GPU_KEPT = "dev/jacaranda/mc/GpuKept";
    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String CAMERA_STATE = "net/minecraft/client/renderer/state/level/CameraRenderState";
    private static final String CAMERA = "net/minecraft/client/Camera";
    private static final String MATRIX = "org/joml/Matrix4f";

    // 26.1.2's LevelRenderer.renderLevel, the call Jacaranda wraps as LevelRenderer.render in 26.2.
    private static final String LEVEL_CALL = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel("
            + "Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;Z"
            + "L" + CAMERA_STATE + ";Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
            + "Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V";
    private static final String WORLD_METHOD = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V";
    private static final String HAND_METHOD = "renderItemInHand(L" + CAMERA_STATE + ";FLorg/joml/Matrix4fc;)V";

    private SupplementalMixins() {
    }

    /** Adds the Jacaranda hooks to a converted jar when it is Jacaranda and still has the members they call. */
    public static boolean apply(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry metadata = zip.getEntry(METADATA);
            if (metadata == null || !"jacaranda".equals(modId(zip.getInputStream(metadata).readAllBytes()))) {
                return false;
            }
            String missing = missingHookTarget(zip);
            if (missing != null) {
                LOGGER.warn("Backmod did not add the Jacaranda world and hand hooks: {} is missing, so this "
                        + "Jacaranda build is not the one they were written for.", missing);
                return false;
            }
            Map<String, byte[]> additions = new LinkedHashMap<>();
            additions.put(MIXIN_CLASS + ".class", mixinClass());
            additions.put(KEPT_CLASS + ".class", keptClass());
            additions.put(CONFIG, config().getBytes(StandardCharsets.UTF_8));
            additions.put(METADATA, registerConfig(zip.getInputStream(metadata).readAllBytes()));
            zip.close();
            JarPatcher.add(jar, additions);
            LOGGER.info("Backmod added the Jacaranda world and hand hooks that 26.1.2 cannot take from the mod.");
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Backmod could not add the Jacaranda world and hand hooks: {}", e.toString());
            return false;
        }
    }

    private static String modId(byte[] metadata) {
        JsonElement id = JsonParser.parseString(new String(metadata, StandardCharsets.UTF_8))
                .getAsJsonObject().get("id");
        return id != null && id.isJsonPrimitive() ? id.getAsString() : null;
    }

    /** The first member the hooks call that the mod lacks, or null when all are there. */
    static String missingHookTarget(ZipFile zip) throws IOException {
        String[][] required = {
                {WORLD_PASS, "m:beforeWorld:(L" + MATRIX + ";)V"},
                {WORLD_PASS, "m:afterWorld:(L" + CAMERA + ";)V"},
                {WORLD_PASS, "m:afterHand:()V"},
                {WORLD_PASS, "f:inLevel:Z"},
                {WORLD_PASS, "f:levelCameraState:L" + CAMERA_STATE + ";"},
                {ARMOR_HOOK, "m:levelStart:()V"},
                {GPU_KEPT, "m:usable:(Lnet/minecraft/client/renderer/rendertype/RenderType;)Z"},
        };
        for (String[] row : required) {
            ZipEntry entry = zip.getEntry(row[0] + ".class");
            if (entry == null) {
                return row[0];
            }
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            String[] part = row[1].split(":", 3);
            boolean found = false;
            if (part[0].equals("m")) {
                for (MethodNode m : node.methods) {
                    found |= m.name.equals(part[1]) && m.desc.equals(part[2])
                            && (m.access & Opcodes.ACC_STATIC) != 0;
                }
            } else {
                for (FieldNode f : node.fields) {
                    found |= f.name.equals(part[1]) && f.desc.equals(part[2])
                            && (f.access & Opcodes.ACC_STATIC) != 0 && (f.access & Opcodes.ACC_PUBLIC) != 0;
                }
            }
            if (!found) {
                return row[0] + "." + part[1];
            }
        }
        return null;
    }

    static String config() {
        JsonObject json = new JsonObject();
        json.addProperty("required", false);
        json.addProperty("minVersion", "0.8");
        json.addProperty("package", PACKAGE.replace('/', '.'));
        json.addProperty("compatibilityLevel", "JAVA_17");
        JsonArray client = new JsonArray();
        client.add("GameRendererSupplement");
        client.add("GpuKeptSupplement");
        json.add("client", client);
        JsonObject injectors = new JsonObject();
        injectors.addProperty("defaultRequire", 0);
        json.add("injectors", injectors);
        return json.toString();
    }

    /** The metadata with the config listed, whether the mod declared no mixins, one, or several. */
    static byte[] registerConfig(byte[] metadata) {
        JsonObject root = JsonParser.parseString(new String(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray mixins = new JsonArray();
        JsonElement existing = root.get("mixins");
        if (existing != null && existing.isJsonArray()) {
            mixins = existing.getAsJsonArray();
        } else if (existing != null && !existing.isJsonNull()) {
            mixins.add(existing);
        }
        JsonObject client = new JsonObject();
        client.addProperty("config", CONFIG);
        client.addProperty("environment", "client");
        mixins.add(client);
        root.add("mixins", mixins);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** The mixin class, written with ASM because it only calls the mod and a few 26.1.2 members. */
    static byte[] mixinClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC,
                MIXIN_CLASS, null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(GAME_RENDERER));
        targets.visitEnd();
        mixin.visitEnd();

        // The renderer's own state, which holds the camera the level is drawn with.
        FieldVisitor state = cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "gameRenderState",
                "Lnet/minecraft/client/renderer/state/GameRenderState;", null, null);
        state.visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", false).visitEnd();
        state.visitAnnotation("Lorg/spongepowered/asm/mixin/Final;", false).visitEnd();
        state.visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        before(cw);
        after(cw);
        hand(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Turns off Jacaranda's direct GPU draw, which calls the 26.2 render pass, so its effects use the submit path. */
    static byte[] keptClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC,
                KEPT_CLASS, null, "java/lang/Object", null);
        // The target class is not public, so it is named by string.
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("targets");
        targets.visit(null, GPU_KEPT.replace('/', '.'));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        String callback = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "backmod$noDirectDraw",
                "(Lnet/minecraft/client/renderer/rendertype/RenderType;L" + callback + ";)V", null, null);
        AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor method = inject.visitArray("method");
        method.visit(null, "usable(Lnet/minecraft/client/renderer/rendertype/RenderType;)Z");
        method.visitEnd();
        AnnotationVisitor at = inject.visitArray("at");
        AnnotationVisitor head = at.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        head.visit("value", "HEAD");
        head.visitEnd();
        at.visitEnd();
        inject.visit("cancellable", Boolean.TRUE);
        inject.visit("remap", Boolean.FALSE);
        inject.visit("require", 0);
        inject.visitEnd();
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, callback, "setReturnValue", "(Ljava/lang/Object;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Before the level draws: what Jacaranda's world wrapper did before calling the original. */
    private static void before(ClassWriter cw) {
        MethodVisitor mv = handler(cw, "backmod$beforeWorld", WORLD_METHOD, invoke(false));
        mv.visitCode();
        // The camera state the level draws with, kept in a local for the three reads below.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, MIXIN_CLASS, "gameRenderState",
                "Lnet/minecraft/client/renderer/state/GameRenderState;");
        mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/renderer/state/GameRenderState",
                "levelRenderState", "Lnet/minecraft/client/renderer/state/level/LevelRenderState;");
        mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/renderer/state/level/LevelRenderState",
                "cameraRenderState", "L" + CAMERA_STATE + ";");
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.GETFIELD, CAMERA_STATE, "projectionMatrix", "L" + MATRIX + ";");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, WORLD_PASS, "beforeWorld", "(L" + MATRIX + ";)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ARMOR_HOOK, "levelStart", "()V", false);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, WORLD_PASS, "inLevel", "Z");
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, WORLD_PASS, "levelCameraState", "L" + CAMERA_STATE + ";");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** After the level draws: clear the level flags, then draw the queued effects. */
    private static void after(ClassWriter cw) {
        MethodVisitor mv = handler(cw, "backmod$afterWorld", WORLD_METHOD, invoke(true));
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, WORLD_PASS, "inLevel", "Z");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, WORLD_PASS, "levelCameraState", "L" + CAMERA_STATE + ";");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/Minecraft", "getInstance",
                "()Lnet/minecraft/client/Minecraft;", false);
        mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "gameRenderer", "L" + GAME_RENDERER + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GAME_RENDERER, "getMainCamera", "()L" + CAMERA + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, WORLD_PASS, "afterWorld", "(L" + CAMERA + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** After the hand is drawn and flushed, which 26.1.2 does inside the hand renderer: draw the hand's queued effects. */
    private static void hand(ClassWriter cw) {
        MethodVisitor mv = handler(cw, "backmod$afterHand", HAND_METHOD, returnPoint());
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, WORLD_PASS, "afterHand", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private interface Point {
        void write(AnnotationVisitor at);
    }

    private static Point invoke(boolean afterCall) {
        return at -> {
            at.visit("value", "INVOKE");
            at.visit("target", LEVEL_CALL);
            at.visit("remap", Boolean.FALSE);
            if (afterCall) {
                at.visitEnum("shift", "Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER");
            }
        };
    }

    private static Point returnPoint() {
        return at -> {
            at.visit("value", "RETURN");
            at.visit("remap", Boolean.FALSE);
        };
    }

    /** An {@code @Inject} handler taking only the callback, so no captured locals can drift. */
    private static MethodVisitor handler(ClassWriter cw, String name, String target, Point point) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE,
                name, "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor method = inject.visitArray("method");
        method.visit(null, target);
        method.visitEnd();
        AnnotationVisitor at = inject.visitArray("at");
        AnnotationVisitor one = at.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        point.write(one);
        one.visitEnd();
        at.visitEnd();
        inject.visit("remap", Boolean.FALSE);
        inject.visit("require", 0);
        inject.visitEnd();
        return mv;
    }
}
