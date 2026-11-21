package com.retromod.backport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplementalMixinsTest {

    @TempDir
    Path dir;

    @Test
    void addsTheHooksToJacaranda() throws IOException {
        Path jar = jacaranda(true, "\"jacaranda.mixins.json\"");

        assertTrue(SupplementalMixins.apply(jar), "Jacaranda with every member the hooks call should take them");

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertNotNull(zip.getEntry("backmod-jacaranda.mixins.json"), "the new config should be in the jar");
            assertNotNull(zip.getEntry(SupplementalMixins.KEPT_CLASS + ".class"), "the direct draw switch should be in the jar");
            ClassNode mixin = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(SupplementalMixins.MIXIN_CLASS + ".class")).readAllBytes())
                    .accept(mixin, 0);
            List<String> injected = mixin.methods.stream().filter(SupplementalMixinsTest::isInject)
                    .map(m -> m.name).sorted().toList();
            assertEquals(List.of("backmod$afterHand", "backmod$afterWorld", "backmod$beforeWorld"), injected,
                    "the world is wrapped by a before and an after hook, and the hand has one");

            JsonObject metadata = JsonParser.parseString(new String(
                    zip.getInputStream(zip.getEntry("fabric.mod.json")).readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray mixins = metadata.getAsJsonArray("mixins");
            assertEquals(2, mixins.size(), "the mod's own config stays listed beside the new one");
            assertEquals("jacaranda.mixins.json", mixins.get(0).getAsString());
            assertEquals("backmod-jacaranda.mixins.json", mixins.get(1).getAsJsonObject().get("config").getAsString());
        }
    }

    @Test
    void leavesAJacarandaWithoutTheMembersAlone() throws IOException {
        Path jar = jacaranda(false, "\"jacaranda.mixins.json\"");
        long before = Files.size(jar);

        assertFalse(SupplementalMixins.apply(jar), "a build missing WorldPass.afterHand is not the one the hooks fit");
        assertEquals(before, Files.size(jar), "the jar should not change");
    }

    @Test
    void leavesOtherModsAlone() throws IOException {
        Path jar = dir.resolve("other.jar");
        write(jar, "fabric.mod.json", "{\"id\":\"other\"}".getBytes(StandardCharsets.UTF_8));

        assertFalse(SupplementalMixins.apply(jar));
    }

    @Test
    void directDrawSwitchTargetsUsableAndReturnsFalse() {
        ClassNode kept = new ClassNode();
        new ClassReader(SupplementalMixins.keptClass()).accept(kept, 0);

        MethodNode handler = kept.methods.stream().filter(SupplementalMixinsTest::isInject).findFirst().orElseThrow();
        assertEquals("backmod$noDirectDraw", handler.name);
        assertTrue((handler.access & Opcodes.ACC_STATIC) != 0, "usable is static, so its handler must be too");
    }

    @Test
    void generatedClassIsReadableAndShadowsTheRendererState() {
        ClassNode mixin = new ClassNode();
        new ClassReader(SupplementalMixins.mixinClass()).accept(mixin, 0);

        assertEquals("net/minecraft/client/renderer/GameRenderer",
                ((org.objectweb.asm.Type) ((List<?>) mixin.invisibleAnnotations.get(0).values.get(1)).get(0))
                        .getInternalName());
        assertTrue(mixin.fields.stream().anyMatch(f -> f.name.equals("gameRenderState")
                && f.invisibleAnnotations != null
                && f.invisibleAnnotations.stream().anyMatch(a -> a.desc.endsWith("/Shadow;"))));
    }

    private static boolean isInject(MethodNode m) {
        if (m.visibleAnnotations == null) {
            return false;
        }
        for (AnnotationNode a : m.visibleAnnotations) {
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                return true;
            }
        }
        return false;
    }

    /** A jar shaped like Jacaranda: the metadata and the two classes the hooks call. */
    private Path jacaranda(boolean withAfterHand, String mixins) throws IOException {
        Path jar = dir.resolve("jacaranda.jar");
        String metadata = "{\"id\":\"jacaranda\",\"version\":\"1.0.4\",\"mixins\":[" + mixins + "]}";
        try (OutputStream raw = Files.newOutputStream(jar); ZipOutputStream out = new ZipOutputStream(raw)) {
            entry(out, "fabric.mod.json", metadata.getBytes(StandardCharsets.UTF_8));
            entry(out, "dev/jacaranda/mc/WorldPass.class", worldPass(withAfterHand));
            entry(out, "dev/jacaranda/mc/ArmorHook.class", armorHook());
            entry(out, "dev/jacaranda/mc/GpuKept.class", gpuKept());
        }
        return jar;
    }

    private static byte[] worldPass(boolean withAfterHand) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/jacaranda/mc/WorldPass", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "inLevel", "Z", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "levelCameraState",
                "Lnet/minecraft/client/renderer/state/level/CameraRenderState;", null, null).visitEnd();
        emptyStatic(cw, "beforeWorld", "(Lorg/joml/Matrix4f;)V");
        emptyStatic(cw, "afterWorld", "(Lnet/minecraft/client/Camera;)V");
        if (withAfterHand) {
            emptyStatic(cw, "afterHand", "()V");
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] gpuKept() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/jacaranda/mc/GpuKept", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "usable",
                "(Lnet/minecraft/client/renderer/rendertype/RenderType;)Z", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] armorHook() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/jacaranda/mc/ArmorHook", null, "java/lang/Object", null);
        emptyStatic(cw, "levelStart", "()V");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emptyStatic(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 2);
        mv.visitEnd();
    }

    private static void write(Path jar, String name, byte[] bytes) throws IOException {
        try (OutputStream raw = Files.newOutputStream(jar); ZipOutputStream out = new ZipOutputStream(raw)) {
            entry(out, name, bytes);
        }
    }

    private static void entry(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }
}
