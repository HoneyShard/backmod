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

    private static final String BASE = "net/minecraft/Base";

    /** A game class with one constructor taking an int, one abstract method and one concrete method. */
    private static ClassSet hostWithBase() {
        ClassSet set = host();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, BASE, null, "java/lang/Object",
                new String[] {"java/lang/AutoCloseable"});
        cw.visitMethod(Opcodes.ACC_PROTECTED, "<init>", "(I)V", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "render", "(I)V", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC, "prepare", "(I)V", null, null).visitEnd();
        cw.visitEnd();
        set.add(cw.toByteArray());
        return set;
    }

    /** A mod class extending {@link #BASE}, with the given constructor call and extra methods. */
    private static byte[] subclass(String superCtorDesc, String renderDesc, String prepareDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "m/Sub", null, BASE, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        if (superCtorDesc.equals("(I)V")) {
            init.visitInsn(Opcodes.ICONST_0);
        }
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, BASE, "<init>", superCtorDesc, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (String[] m : new String[][] {{"render", renderDesc}, {"prepare", prepareDesc}}) {
            if (m[1] != null) {
                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m[0], m[1], null, null);
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Set<ReferenceAudit.Missing> hierarchy(byte[] modClass) {
        ClassSet mod = new ClassSet();
        mod.add(modClass);
        return HierarchyAudit.scan(mod, new HostIndex(hostWithBase()));
    }

    @Test
    void constructorLookupIgnoresSupertypesOutsideTheJar() {
        HostIndex index = new HostIndex(hostWithBase());
        assertEquals(Presence.FOUND, index.member(BASE, "<init>", "(I)V", true));
        // Base implements AutoCloseable, which is outside the jar. A constructor still cannot come from it.
        assertEquals(Presence.MISSING, index.member(BASE, "<init>", "()V", true));
    }

    @Test
    void superConstructorTheGameLacksIsStructural() throws Exception {
        Path jar = Files.createTempFile("audit", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("m/Sub.class"));
            out.write(subclass("()V", "(I)V", null));
            out.closeEntry();
        }
        try {
            var found = ReferenceAudit.scan(jar, new HostIndex(hostWithBase()));
            assertTrue(found.stream().anyMatch(m -> m.structural() && m.what().contains("<init>()V")),
                    found.toString());
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    @Test
    void abstractMethodWithNoImplementationIsStructural() {
        var found = hierarchy(subclass("(I)V", "(II)V", null));
        assertTrue(found.stream().anyMatch(m -> m.structural() && m.what().contains("Base.render(I)V")),
                found.toString());
    }

    @Test
    void implementedAbstractMethodIsNotReported() {
        // AutoCloseable.close is abstract and outside the jar, so only render is a candidate.
        assertTrue(hierarchy(subclass("(I)V", "(I)V", null)).isEmpty());
    }

    @Test
    void overrideWithAnotherDescriptorIsReportedButNotStructural() {
        var found = hierarchy(subclass("(I)V", "(I)V", "(J)V"));
        assertEquals(1, found.size(), found.toString());
        var only = found.iterator().next();
        assertTrue(!only.structural() && only.what().contains("prepare(J)V"), only.toString());
    }

    @Test
    void bridgeMethodCountsAsAnExactOverride() {
        // The mod narrows the return type, so javac adds a bridge with the game's descriptor.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "m/Sub", null, BASE, null);
        cw.visitMethod(Opcodes.ACC_PUBLIC, "prepare", "(J)V", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC, "prepare", "(I)V", null,
                null).visitEnd();
        cw.visitEnd();
        assertTrue(hierarchy(cw.toByteArray()).stream().noneMatch(m -> m.what().contains("prepare(J)V")));
    }

    @Test
    void structuralFindingMakesTheVerdictLikelyBroken() {
        BackportRegistration.Counts counts = new BackportRegistration.Counts(0, 0, 0, 0, 0);
        assertEquals(Verdict.LIKELY_BROKEN, new BackportReport("m", "26.2", "26.1.2", counts, List.of(),
                Set.of(new ReferenceAudit.Missing("abstract method x", "y", true)), 0.5).verdict());
    }

    @Test
    void methodMissingOnAClassWithAJdkInterfaceIsMissingNotUnknown() {
        // Base implements AutoCloseable, which is outside the fake jar. close() is a real AutoCloseable method.
        HostIndex index = new HostIndex(hostWithBase());
        assertEquals(Presence.FOUND, index.member(BASE, "close", "()V", true));
        assertEquals(Presence.MISSING, index.member(BASE, "renderAll", "(I)V", true));
    }

    /** A mixin with one @Inject on tick whose @At names the given call. */
    private static byte[] mixinWithAt(String atKind, String atTarget) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "m/Mix", null, "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor values = mixin.visitArray("value");
        values.visit(null, Type.getObjectType(TARGET));
        values.visitEnd();
        mixin.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
        AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor method = inject.visitArray("method");
        method.visit(null, "tick");
        method.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", atKind);
        at.visit("target", atTarget);
        at.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void atTargetThatTheGameLacksIsReportedAndOneItHasIsNot() {
        HostIndex host = new HostIndex(host());
        Finding present = MixinAudit.auditClass("m.Mix", "c.json", mixinWithAt("INVOKE", "L" + TARGET + ";tick()V"), host);
        assertEquals(Status.OK, present.status(), present.problems().toString());
        Finding gone = MixinAudit.auditClass("m.Mix", "c.json",
                mixinWithAt("INVOKE", "L" + TARGET + ";vanished(I)V"), host);
        assertEquals(Status.MEMBER_MISSING, gone.status());
        assertTrue(gone.problems().get(0).contains("@At(INVOKE) target Target.vanished(I)V"), gone.problems().toString());
    }

    @Test
    void atTargetOutsideTheGameIsNotChecked() {
        Finding other = MixinAudit.auditClass("m.Mix", "c.json",
                mixinWithAt("INVOKE", "Lcom/example/Lib;call()V"), new HostIndex(host()));
        assertEquals(Status.OK, other.status(), other.problems().toString());
    }
}
