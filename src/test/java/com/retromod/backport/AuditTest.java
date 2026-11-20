package com.retromod.backport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.backport.BackportReport.Verdict;
import com.retromod.backport.HostIndex.Presence;
import com.retromod.backport.MixinAudit.Finding;
import com.retromod.backport.MixinAudit.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Checks the mixin and reference audits against a tiny fake game jar. */
class AuditTest {

    private static final String TARGET = "net/minecraft/Target";

    private static ClassSet host() {
        ClassSet set = new ClassSet();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "count", "I", null, null).visitEnd();
        cw.visitEnd();
        set.add(cw.toByteArray());
        return set;
    }

    /** A mixin class with one @Inject on {@code selector} and one @Shadow field. */
    private static byte[] mixin(String target, String selector, String shadowField) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "m/Mix", null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(target));
        values.visitEnd();
        mixin.visitEnd();
        if (shadowField != null) {
            cw.visitField(Opcodes.ACC_PRIVATE, shadowField, "I", null, null)
                    .visitAnnotation("Lorg/spongepowered/asm/mixin/Shadow;", true).visitEnd();
        }
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
        AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor method = inject.visitArray("method");
        method.visit(null, selector);
        method.visitEnd();
        inject.visitEnd();
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void mixinWithAllTargetsPresentIsOk() {
        Finding f = MixinAudit.auditClass("m.Mix", "c.json", mixin(TARGET, "tick", "count"), new HostIndex(host()));
        assertEquals(Status.OK, f.status(), f.problems().toString());
    }

    @Test
    void mixinWhoseTargetClassIsGoneIsMarkedForRemoval() {
        Finding f = MixinAudit.auditClass("m.Mix", "c.json", mixin("net/minecraft/Gone", "tick", null),
                new HostIndex(host()));
        assertEquals(Status.TARGET_MISSING, f.status());
    }

    @Test
    void missingSelectorAndShadowAreReportedButTheMixinStays() {
        Finding f = MixinAudit.auditClass("m.Mix", "c.json", mixin(TARGET, "gone(I)V", "ghost"),
                new HostIndex(host()));
        assertEquals(Status.MEMBER_MISSING, f.status());
        assertEquals(2, f.problems().size(), f.problems().toString());
    }

    @Test
    void selectorsWithOwnerAndDescriptorAreParsedAndPatternsSkipped() {
        assertEquals("tick", MixinAudit.parseSelector("Lnet/minecraft/Target;tick()V")[0]);
        assertEquals("()V", MixinAudit.parseSelector("tick()V")[1]);
        assertEquals("()V", MixinAudit.parseSelector("Lnet/minecraft/Target;tick()V")[1]);
        assertEquals(null, MixinAudit.parseSelector("/ti.k/"));
        assertEquals(null, MixinAudit.parseSelector("ti*"));
    }

    @Test
    void objectMethodsResolveOnInterfacesAndMissingOnesDoNot() {
        HostIndex index = new HostIndex(host());
        assertEquals(Presence.FOUND, index.member(TARGET, "equals", "(Ljava/lang/Object;)Z", true));
        assertEquals(Presence.MISSING, index.member(TARGET, "nope", "()V", true));
        assertEquals(Presence.CLASS_MISSING, index.member("net/minecraft/Gone", "tick", "()V", true));
    }

    @Test
    void stripFromConfigRemovesOnlyTheNamedClasses() {
        String json = "{\"package\":\"m\",\"mixins\":[\"A\",\"B\"],\"client\":[\"C\"]}";
        String out = MixinAudit.stripFromConfig(json, null, Set.of("m.B", "m.C"));
        assertTrue(out.contains("\"A\"") && !out.contains("\"B\"") && !out.contains("\"C\""), out);
    }

    @Test
    void referenceAuditFlagsOnlyGameReferencesTheHostLacks() throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "m/User", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET, "tick", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET, "vanished", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/Gone", "x", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/mojang/datafixers/util/Either", "left", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        Path jar = Files.createTempFile("audit", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("m/User.class"));
            out.write(cw.toByteArray());
            out.closeEntry();
        }
        try {
            List<String> missing = ReferenceAudit.scan(jar, new HostIndex(host())).stream()
                    .map(ReferenceAudit.Missing::what).toList();
            assertEquals(2, missing.size(), missing.toString());
            assertTrue(missing.stream().anyMatch(m -> m.contains("vanished")));
            assertTrue(missing.stream().anyMatch(m -> m.equals("class net/minecraft/Gone")));
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    @Test
    void verdictFollowsTheShareOfMixinsThatCannotApply() {
        BackportRegistration.Counts counts = new BackportRegistration.Counts(0, 0, 0, 0, 0);
        Finding ok = new Finding("a", "c", Status.OK, List.of(), List.of());
        Finding gone = new Finding("b", "c", Status.TARGET_MISSING, List.of(), List.of("x"));
        assertEquals(Verdict.LIKELY_OK, new BackportReport("m", "26.2", "26.1.2", counts,
                List.of(ok, ok), Set.of(), 0.5).verdict());
        assertEquals(Verdict.PARTIAL, new BackportReport("m", "26.2", "26.1.2", counts,
                List.of(ok, ok, gone), Set.of(), 0.5).verdict());
        assertEquals(Verdict.LIKELY_BROKEN, new BackportReport("m", "26.2", "26.1.2", counts,
                List.of(ok, gone), Set.of(), 0.5).verdict());
        assertEquals(Verdict.PARTIAL, new BackportReport("m", "26.2", "26.1.2", counts,
                List.of(), Set.of(new ReferenceAudit.Missing("class x", "y")), 0.5).verdict());
    }
}
