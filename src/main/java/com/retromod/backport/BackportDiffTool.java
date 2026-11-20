package com.retromod.backport;

import com.retromod.backport.ClassSet.Member;
import com.retromod.backport.MemberDiff.Finding;
import com.retromod.backport.MemberDiff.Kind;

import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Command-line entry: diffs two game jars and writes the reverse member table and a gap report. */
public final class BackportDiffTool {

    private static final String[] GAME_PREFIXES = {"net/minecraft/", "com/mojang/"};

    private BackportDiffTool() {
    }

    /** Usage: {@code <older.jar> <newer.jar> <class-table.tsv> <out-dir>}. */
    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println("Usage: BackportDiffTool <older.jar> <newer.jar> <class-table.tsv> <out-dir>");
            System.exit(2);
        }
        Path olderJar = Path.of(args[0]);
        Path newerJar = Path.of(args[1]);
        Map<String, String> classMap = readClassTable(Path.of(args[2]));
        Path out = Path.of(args[3]);
        Files.createDirectories(out);

        Remapper toOlder = new Remapper() {
            @Override
            public String map(String internalName) {
                return classMap.getOrDefault(internalName, internalName);
            }
        };
        ClassSet older = ClassSet.fromJar(olderJar, GAME_PREFIXES);
        ClassSet newer = ClassSet.fromJar(newerJar, toOlder, GAME_PREFIXES);
        MemberDiff.Result result = MemberDiff.diff(older, newer, classMap);

        writeTables(result.findings(), out);
        writeSplits(SplitFinder.find(older, newer, classMap), out);
        List<HopFinder.Hop> hops = HopFinder.find(older, newer, classMap, result.findings());
        try (PrintWriter w = writer(out.resolve("hops.tsv"),
                "NEW_OWNER\tNAME\tDESC\tOLD_OWNER\tKIND\tOLD_STATIC")) {
            for (HopFinder.Hop h : hops) {
                w.println(String.join("\t", h.newOwner(), h.name(), h.desc(), h.oldOwner(),
                        h.method() ? "method" : "field", String.valueOf(h.oldIsStatic())));
            }
        }
        System.out.println("member hops: " + hops.size());
        verifyBridges(older, newer);
        summarize(result);
    }

    /** Reads {@code NEW<TAB>OLD<TAB>sources} rows, skipping comments. */
    static Map<String, String> readClassTable(Path file) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t");
            if (cols.length < 2) {
                throw new IOException("Bad class table row in " + file + ": " + line);
            }
            if (map.put(cols[0], cols[1]) != null) {
                throw new IOException("Duplicate class in " + file + ": " + cols[0]);
            }
        }
        return map;
    }

    /** A rename seen in at least this many classes counts as a mechanical API rename. */
    static final int PATTERN_MIN_CLASSES = 3;

    private static void writeTables(List<Finding> findings, Path out) throws IOException {
        // Count the classes that share each rename, so repeated renames can be told from one-offs.
        Map<String, java.util.Set<String>> classesPerRename = new LinkedHashMap<>();
        for (Finding f : findings) {
            if (f.kind() == Kind.RENAME) {
                classesPerRename.computeIfAbsent(renameKey(f), k -> new java.util.HashSet<>())
                        .add(f.newOwner());
            }
        }
        String renameHeader = "NEW_OWNER\tNAME\tDESC\tOLD_OWNER\tOLD_NAME\tOLD_DESC\tCONFIDENCE";
        try (PrintWriter methods = writer(out.resolve("methods.tsv"), renameHeader);
             PrintWriter fields = writer(out.resolve("fields.tsv"), renameHeader);
             PrintWriter review = writer(out.resolve("review.tsv"), renameHeader + "\tKIND_OF_MEMBER");
             PrintWriter gaps = writer(out.resolve("non-invertible.tsv"),
                     "KIND\tNEW_OWNER\tKIND_OF_MEMBER\tNAME\tDESC\tOLD_OWNER\tCANDIDATES")) {
            for (Finding f : findings) {
                Member m = f.newMember();
                if (f.kind() == Kind.RENAME) {
                    Member old = f.oldCandidates().get(0);
                    MemberDiff.Confidence confidence = f.confidence();
                    if (confidence == MemberDiff.Confidence.DESCRIPTOR_ONLY
                            && classesPerRename.get(renameKey(f)).size() >= PATTERN_MIN_CLASSES) {
                        confidence = MemberDiff.Confidence.PATTERN;
                    }
                    String row = String.join("\t", f.newOwner(), m.name(), m.desc(), f.oldOwner(),
                            old.name(), old.desc(), confidence.name());
                    if (confidence == MemberDiff.Confidence.DESCRIPTOR_ONLY) {
                        review.println(row + "\t" + (m.method() ? "method" : "field"));
                    } else {
                        (m.method() ? methods : fields).println(row);
                    }
                } else if (f.kind() == Kind.CLASS_NO_COUNTERPART) {
                    gaps.println(String.join("\t", f.kind().name(), f.newOwner(), "class", "", "", "", ""));
                } else {
                    String candidates = f.oldCandidates().stream()
                            .map(c -> c.name() + c.desc()).collect(Collectors.joining(" | "));
                    gaps.println(String.join("\t", f.kind().name(), f.newOwner(),
                            m.method() ? "method" : "field", m.name(), m.desc(), f.oldOwner(), candidates));
                }
            }
        }
    }

    private static void writeSplits(List<SplitFinder.Split> splits, Path out) throws IOException {
        try (PrintWriter w = writer(out.resolve("splits.tsv"),
                "NEW_CLASS\tOLD_CLASS\tCONTAINMENT\tMEMBERS\tHOLDER_OWNER\tHOLDER_FIELD")) {
            for (SplitFinder.Split sp : splits) {
                w.println(String.join("\t", sp.newClass(), sp.oldClass(),
                        String.format(java.util.Locale.ROOT, "%.3f", sp.containment()),
                        String.valueOf(sp.members()), sp.holderOwner(), sp.holderField()));
            }
        }
        System.out.println("class splits: " + splits.size());
    }

    /** Re-checks bridges.tsv against both jars so a stale row is caught when the data is regenerated. */
    private static void verifyBridges(ClassSet older, ClassSet newer) {
        int bad = 0;
        for (String[] row : BackportRegistration.rows("bridges.tsv", 7)) {
            BridgeHelper.Bridge b = BridgeHelper.Bridge.of(row);
            boolean onNew = has(newer, b.newOwner(), b.name(), b.desc(), true);
            boolean goneFromNew = !has(newer, b.oldOwner(), b.oldName(), b.oldDesc(), !b.field());
            boolean onOld = has(older, b.oldOwner(), b.oldName(), b.oldDesc(), !b.field());
            boolean absentOnOldOwner = !has(older, b.newOwner(), b.name(), b.desc(), true);
            boolean singleton = has(older, b.oldOwner(), "getInstance", "()L" + b.oldOwner() + ";", true);
            if (!(onNew && goneFromNew && onOld && absentOnOldOwner && singleton)) {
                bad++;
                System.err.println("BRIDGE CHECK FAILED: " + b + " new=" + onNew + " goneFromNew=" + goneFromNew
                        + " old=" + onOld + " absentOld=" + absentOnOldOwner + " singleton=" + singleton);
            }
        }
        System.out.println("bridge rows failing the jar check: " + bad);
    }

    private static boolean has(ClassSet set, String owner, String name, String desc, boolean method) {
        ClassSet.Entry cls = set.get(owner);
        return cls != null && cls.members().containsKey((method ? "M:" : "F:") + name + desc);
    }

    private static String renameKey(Finding f) {
        Member old = f.oldCandidates().get(0);
        return (f.newMember().method() ? "M:" : "F:") + f.newMember().name() + ">" + old.name()
                + f.newMember().desc();
    }

    private static PrintWriter writer(Path file, String header) throws IOException {
        PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8));
        w.println("# " + header);
        return w;
    }

    private static void summarize(MemberDiff.Result result) {
        EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Finding f : result.findings()) {
            counts.merge(f.kind(), 1, Integer::sum);
        }
        System.out.println("classes compared: " + result.comparedClasses());
        System.out.println("members already matching: " + result.matchingMembers());
        for (Kind k : Kind.values()) {
            System.out.println(k + ": " + counts.getOrDefault(k, 0));
        }
    }
}
