package com.retromod.backport;

import com.retromod.backport.MixinAudit.Finding;
import com.retromod.backport.MixinAudit.Status;

import java.util.List;
import java.util.Set;

/** The human-readable result of one backport: what changed, what was dropped, and a verdict. */
public final class BackportReport {

    /** Overall judgement. A static check, not a launch test. */
    public enum Verdict { LIKELY_OK, PARTIAL, LIKELY_BROKEN }

    private final String modFile;
    private final String source;
    private final String target;
    private final BackportRegistration.Counts counts;
    private final List<Finding> mixins;
    private final Set<ReferenceAudit.Missing> missing;
    private final double lossThreshold;

    public BackportReport(String modFile, String source, String target, BackportRegistration.Counts counts,
                          List<Finding> mixins, Set<ReferenceAudit.Missing> missing, double lossThreshold) {
        this.modFile = modFile;
        this.source = source;
        this.target = target;
        this.counts = counts;
        this.mixins = mixins;
        this.missing = missing;
        this.lossThreshold = lossThreshold;
    }

    /** Mixins removed from their config because no target class exists in the older game. */
    public List<Finding> stripped() {
        return mixins.stream().filter(f -> f.status() == Status.TARGET_MISSING).toList();
    }

    private List<Finding> memberMissing() {
        return mixins.stream().filter(f -> f.status() == Status.MEMBER_MISSING).toList();
    }

    /** Fraction of the mod's mixins that cannot apply as written. */
    public double lostFraction() {
        return mixins.isEmpty() ? 0 : (stripped().size() + memberMissing().size()) / (double) mixins.size();
    }

    public Verdict verdict() {
        if (!mixins.isEmpty() && lostFraction() >= lossThreshold) {
            return Verdict.LIKELY_BROKEN;
        }
        return stripped().isEmpty() && memberMissing().isEmpty() && missing.isEmpty()
                ? Verdict.LIKELY_OK : Verdict.PARTIAL;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Backmod report: ").append(modFile).append("\n\n");
        sb.append("Backported from Minecraft ").append(source).append(" to ").append(target).append(".\n");
        sb.append("Verdict: ").append(verdict()).append("\n\n");
        sb.append("This is a static check against the ").append(target).append(" game jar. It does not launch the game.\n\n");

        sb.append("## Registered\n\n");
        sb.append("- ").append(counts.classMoves()).append(" class moves, ").append(counts.methodRenames())
                .append(" method renames, ").append(counts.fieldRenames()).append(" field renames, ")
                .append(counts.classSplits()).append(" class splits, ").append(counts.bridges())
                .append(" bridges (the tables available, not the ones this mod used).\n\n");

        sb.append("## Mixins\n\n");
        long ok = mixins.stream().filter(f -> f.status() == Status.OK).count();
        sb.append("- ").append(mixins.size()).append(" listed, ").append(ok).append(" fully resolved, ")
                .append(stripped().size()).append(" removed from the config, ").append(memberMissing().size())
                .append(" with missing members.\n");
        sb.append("- Share that cannot apply: ").append(String.format(java.util.Locale.ROOT, "%.0f%%", lostFraction() * 100))
                .append(" (the verdict is LIKELY_BROKEN at ")
                .append(String.format(java.util.Locale.ROOT, "%.0f%%", lossThreshold * 100)).append(" or more).\n\n");
        for (Finding f : stripped()) {
            sb.append("Removed `").append(f.mixinClass()).append("` from `").append(f.config()).append("`\n");
            f.problems().forEach(p -> sb.append("  - ").append(p).append("\n"));
        }
        for (Finding f : memberMissing()) {
            sb.append("Kept but cannot fully apply `").append(f.mixinClass()).append("`\n");
            f.problems().forEach(p -> sb.append("  - ").append(p).append("\n"));
        }
        if (stripped().isEmpty() && memberMissing().isEmpty()) {
            sb.append("None.\n");
        }

        sb.append("\n## Game references with no ").append(target).append(" counterpart\n\n");
        if (missing.isEmpty()) {
            sb.append("None found. Every class, method and field the mod uses from Minecraft exists in ")
                    .append(target).append(".\n");
        } else {
            for (ReferenceAudit.Missing m : missing) {
                sb.append("- ").append(m.what()).append(" (used by ").append(m.usedBy()).append(")\n");
            }
            sb.append("\nA missing reference fails only when that code runs. See docs-dev/backport/non-invertible-report.md for the known gaps.\n");
        }
        return sb.toString();
    }
}
