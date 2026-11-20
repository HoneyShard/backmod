package com.retromod.backport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.backport.MemberDiff.Confidence;
import com.retromod.backport.MemberDiff.Finding;
import com.retromod.backport.MemberDiff.Kind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Builds tiny old and new class sets and checks how each kind of change is classified. */
class MemberDiffTest {

    /** Adds a public no-arg method that returns void after {@code nops} NOPs, so bodies can differ. */
    private static void voidMethod(ClassWriter cw, String name, String desc, int nops) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        for (int i = 0; i < nops; i++) {
            mv.visitInsn(Opcodes.NOP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 4);
        mv.visitEnd();
    }

    private static byte[] clazz(String name, String superName, Object[][] methods) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        for (Object[] m : methods) {
            voidMethod(cw, (String) m[0], (String) m[1], (Integer) m[2]);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassSet set(byte[]... classes) {
        ClassSet s = new ClassSet();
        for (byte[] c : classes) {
            s.add(c);
        }
        return s;
    }

    private static Finding only(MemberDiff.Result result) {
        assertEquals(1, result.findings().size(), result.findings().toString());
        return result.findings().get(0);
    }

    @Test
    void identicalMembersProduceNoFinding() {
        ClassSet older = set(clazz("a/A", "java/lang/Object", new Object[][] {{"run", "()V", 0}}));
        ClassSet newer = set(clazz("a/A", "java/lang/Object", new Object[][] {{"run", "()V", 0}}));
        MemberDiff.Result result = MemberDiff.diff(older, newer, Map.of());
        assertTrue(result.findings().isEmpty());
        assertEquals(1, result.matchingMembers());
    }

    @Test
    void renamedMethodWithSameBodyIsBodyMatched() {
        ClassSet older = set(clazz("a/A", "java/lang/Object", new Object[][] {{"oldName", "()V", 2}}));
        ClassSet newer = set(clazz("a/A", "java/lang/Object", new Object[][] {{"newName", "()V", 2}}));
        Finding f = only(MemberDiff.diff(older, newer, Map.of()));
        assertEquals(Kind.RENAME, f.kind());
        assertEquals(Confidence.BODY_MATCH, f.confidence());
        assertEquals("oldName", f.oldCandidates().get(0).name());
    }

    @Test
    void renamedMethodWithDifferentBodyIsOnlyDescriptorMatched() {
        ClassSet older = set(clazz("a/A", "java/lang/Object", new Object[][] {{"tick", "()V", 1}}));
        ClassSet newer = set(clazz("a/A", "java/lang/Object", new Object[][] {{"reset", "()V", 3}}));
        Finding f = only(MemberDiff.diff(older, newer, Map.of()));
        assertEquals(Kind.RENAME, f.kind());
        assertEquals(Confidence.DESCRIPTOR_ONLY, f.confidence(), "an unrelated method must not look verified");
    }

    @Test
    void twoNewMethodsCompetingForOneRemovedAreAmbiguous() {
        ClassSet older = set(clazz("a/A", "java/lang/Object", new Object[][] {{"old", "()V", 0}}));
        ClassSet newer = set(clazz("a/A", "java/lang/Object",
                new Object[][] {{"x", "()V", 0}, {"y", "()V", 0}}));
        MemberDiff.Result result = MemberDiff.diff(older, newer, Map.of());
        assertEquals(2, result.findings().size());
        assertTrue(result.findings().stream().allMatch(f -> f.kind() == Kind.AMBIGUOUS_RENAME));
    }

    @Test
    void sameNameWithNewDescriptorIsASignatureChange() {
        ClassSet older = set(clazz("a/A", "java/lang/Object", new Object[][] {{"run", "()V", 0}}));
        ClassSet newer = set(clazz("a/A", "java/lang/Object", new Object[][] {{"run", "(I)V", 0}}));
        assertEquals(Kind.SIGNATURE_CHANGE, only(MemberDiff.diff(older, newer, Map.of())).kind());
    }

    @Test
    void memberWithNothingToMatchHasNoCounterpart() {
        ClassSet older = set(clazz("a/A", "java/lang/Object", new Object[][] {}));
        ClassSet newer = set(clazz("a/A", "java/lang/Object", new Object[][] {{"brandNew", "()V", 0}}));
        assertEquals(Kind.NO_COUNTERPART, only(MemberDiff.diff(older, newer, Map.of())).kind());
    }

    @Test
    void classMissingFromOlderJarIsReported() {
        ClassSet older = set();
        ClassSet newer = set(clazz("a/Fresh", "java/lang/Object", new Object[][] {}));
        assertEquals(Kind.CLASS_NO_COUNTERPART, only(MemberDiff.diff(older, newer, Map.of())).kind());
    }

    @Test
    void movedClassIsComparedWithItsOlderName() {
        ClassSet older = set(clazz("old/A", "java/lang/Object", new Object[][] {{"run", "()V", 0}}));
        ClassSet newer = set(clazz("moved/A", "java/lang/Object", new Object[][] {{"run", "()V", 0}}));
        MemberDiff.Result result = MemberDiff.diff(older, newer, Map.of("moved/A", "old/A"));
        assertTrue(result.findings().isEmpty(), result.findings().toString());
    }

    @Test
    void memberInheritedFromOlderSuperclassCountsAsPresent() {
        ClassSet older = set(
                clazz("a/Base", "java/lang/Object", new Object[][] {{"run", "()V", 0}}),
                clazz("a/Child", "a/Base", new Object[][] {}));
        ClassSet newer = set(
                clazz("a/Base", "java/lang/Object", new Object[][] {}),
                clazz("a/Child", "a/Base", new Object[][] {{"run", "()V", 0}}));
        assertTrue(MemberDiff.diff(older, newer, Map.of()).findings().isEmpty());
    }

    @Test
    void movedClassNamesInDescriptorsAreMappedBeforeComparing() {
        ClassSet older = set(
                clazz("old/T", "java/lang/Object", new Object[][] {}),
                clazz("a/A", "java/lang/Object", new Object[][] {{"take", "(Lold/T;)V", 0}}));
        ClassSet newer = set(
                clazz("new/T", "java/lang/Object", new Object[][] {}),
                clazz("a/A", "java/lang/Object", new Object[][] {{"take", "(Lnew/T;)V", 0}}));
        assertTrue(MemberDiff.diff(older, newer, Map.of("new/T", "old/T")).findings().isEmpty());
        assertEquals(List.of(), MemberDiff.diff(older, newer, Map.of("new/T", "old/T")).findings());
    }
}
