package com.retromod.backport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.backport.SyncIndex.Action;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A sync backports new and changed jars, skips the rest, and drops outputs whose input was removed. */
class SyncIndexTest {

    private static Path jar(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(name), content);
    }

    private static List<Action> actions(SyncIndex.Plan plan) {
        return plan.items().stream().map(SyncIndex.Item::action).toList();
    }

    /** Pretends the backport of every planned jar succeeded, as the command does. */
    private static void backportAll(SyncIndex index, SyncIndex.Plan plan, Path out) throws IOException {
        for (SyncIndex.Item item : plan.items()) {
            if (item.action() == Action.BACKPORT) {
                jar(out, item.input().getFileName().toString(), "output");
                index.markDone(item.input().getFileName().toString(), item.sha256());
            }
        }
        index.save();
    }

    @Test
    void firstSyncBackportsEverythingAndTheNextOneSkipsAll(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in");
        Path out = dir.resolve("out");
        jar(in, "a.jar", "one");
        jar(in, "b.jar", "two");
        Files.write(in.resolve("notes.txt"), new byte[] {1});

        SyncIndex first = SyncIndex.open(out, "s1");
        SyncIndex.Plan plan = first.plan(in, out, false);
        assertEquals(List.of(Action.BACKPORT, Action.BACKPORT), actions(plan), "only .jar files count");
        backportAll(first, plan, out);

        SyncIndex second = SyncIndex.open(out, "s1");
        assertEquals(List.of(Action.UP_TO_DATE, Action.UP_TO_DATE), actions(second.plan(in, out, false)));
    }

    @Test
    void aChangedInputOrAMissingOutputIsBackportedAgain(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in");
        Path out = dir.resolve("out");
        jar(in, "a.jar", "one");
        jar(in, "b.jar", "two");
        SyncIndex index = SyncIndex.open(out, "s1");
        backportAll(index, index.plan(in, out, false), out);

        jar(in, "a.jar", "changed");
        Files.delete(out.resolve("b.jar"));

        List<Action> now = actions(SyncIndex.open(out, "s1").plan(in, out, false));
        assertEquals(List.of(Action.BACKPORT, Action.BACKPORT), now);
    }

    @Test
    void aNewStampOrForceRebuildsEverything(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in");
        Path out = dir.resolve("out");
        jar(in, "a.jar", "one");
        SyncIndex index = SyncIndex.open(out, "s1");
        backportAll(index, index.plan(in, out, false), out);

        assertEquals(List.of(Action.BACKPORT), actions(SyncIndex.open(out, "s2").plan(in, out, false)),
                "the tables or version changed");
        assertEquals(List.of(Action.BACKPORT), actions(SyncIndex.open(out, "s1").plan(in, out, true)),
                "--force");
    }

    @Test
    void anOutputWhoseInputWasRemovedIsReportedStale(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in");
        Path out = dir.resolve("out");
        jar(in, "a.jar", "one");
        jar(in, "gone.jar", "two");
        SyncIndex index = SyncIndex.open(out, "s1");
        backportAll(index, index.plan(in, out, false), out);

        Files.delete(in.resolve("gone.jar"));

        assertEquals(List.of("gone.jar"), SyncIndex.open(out, "s1").plan(in, out, false).staleOutputs());
    }

    @Test
    void aFailedJarIsNotRecordedSoItIsTriedAgain(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in");
        Path out = dir.resolve("out");
        jar(in, "bad.jar", "one");
        SyncIndex index = SyncIndex.open(out, "s1");
        index.plan(in, out, false);
        index.save();

        assertEquals(List.of(Action.BACKPORT), actions(SyncIndex.open(out, "s1").plan(in, out, false)));
    }

    @Test
    void aDamagedIndexOnlyCostsARebuild(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in");
        Path out = dir.resolve("out");
        jar(in, "a.jar", "one");
        Files.createDirectories(out);
        Files.writeString(out.resolve(SyncIndex.FILE), "{not json");

        assertEquals(List.of(Action.BACKPORT), actions(SyncIndex.open(out, "s1").plan(in, out, false)));
    }

    @Test
    void codeIdentityNeverFailsOnAnUnpackagedRun() {
        // Unit tests run from a classes folder, so there is no jar to hash and the answer is empty or a hash.
        String identity = BackportRegistration.codeIdentity();
        assertTrue(identity.isEmpty() || identity.matches("[0-9A-Fa-f]{64}"), identity);
    }

    @Test
    void tablesStampIsStableAndShort() {
        String stamp = BackportRegistration.tablesStamp();
        assertEquals(stamp, BackportRegistration.tablesStamp());
        assertTrue(stamp.matches("[0-9a-f]{16}"), stamp);
    }

    @Test
    void anOutputStaysOursAfterTheStampChanges(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in");
        Path out = dir.resolve("out");
        jar(in, "a.jar", "one");
        SyncIndex index = SyncIndex.open(out, "s1");
        backportAll(index, index.plan(in, out, false), out);

        // A new Backmod version drops the hashes, but the jar it wrote in mods/ must not look like a native mod.
        SyncIndex upgraded = SyncIndex.open(out, "s2");
        assertTrue(upgraded.knows("a.jar"));
        assertFalse(upgraded.knows("native.jar"));

        Files.delete(in.resolve("a.jar"));
        assertEquals(List.of("a.jar"), upgraded.plan(in, out, false).staleOutputs(),
                "an output is still removed after an upgrade when its input is gone");
    }

    @Test
    void forgettingAJarReleasesItsOutput(@TempDir Path dir) throws IOException {
        Path in = dir.resolve("in");
        Path out = dir.resolve("out");
        jar(in, "a.jar", "one");
        SyncIndex index = SyncIndex.open(out, "s1");
        backportAll(index, index.plan(in, out, false), out);

        index.forget("a.jar");
        index.save();

        assertFalse(SyncIndex.open(out, "s1").knows("a.jar"));
    }
}
