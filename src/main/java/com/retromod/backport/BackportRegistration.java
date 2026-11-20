package com.retromod.backport;

import com.retromod.core.RetromodTransformer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Registers the verified 26.2 to 26.1.2 reverse tables as ordinary transformer redirects. */
public final class BackportRegistration {

    /** Minecraft version the backported mods were built for. */
    public static final String SOURCE = "26.2";

    /** The one host this table was checked against. 26.1 and 26.1.1 are not covered. */
    public static final String TARGET = "26.1.2";

    private static final String DIR = "/backmod/fabric/26.2-to-26.1.2/";

    /** What one registration added, for the CLI summary. */
    public record Counts(int classMoves, int methodRenames, int fieldRenames, int classSplits, int bridges) {
    }

    private BackportRegistration() {
    }

    /** True for the only pair the tables were checked for: a Fabric or Quilt mod, 26.2 to 26.1.2. */
    public static boolean supports(String loader, String source, String target) {
        String l = loader == null ? "" : loader.toLowerCase(Locale.ROOT);
        return (l.equals("fabric") || l.equals("quilt")) && TARGET.equals(target)
                && source != null && (source.equals(SOURCE) || source.startsWith(SOURCE + "."));
    }

    /** Registers class moves first, because member redirects look up the moved class names. */
    public static Counts register(RetromodTransformer transformer) {
        int classes = 0;
        for (String[] row : rows("classes.tsv", 2)) {
            transformer.registerClassRedirect(row[0], row[1]);
            classes++;
        }

        int methods = 0;
        for (String[] row : rows("methods.tsv", 6)) {
            // Keyed on the 26.2 owner and descriptor the mod was built with. The transformer adds the
            // aliases for names the class moves rewrite first.
            transformer.registerMethodRedirect(row[0], row[1], row[2], row[3], row[4], row[5]);
            methods++;
        }

        int fields = 0;
        for (String[] row : rows("fields.tsv", 6)) {
            // Field keys have no alias step, so a field on a moved class is registered under both
            // owner names. The descriptor is the 26.1.2 one on both sides so no cast is added.
            transformer.registerFieldRedirect(row[0], row[1], row[5], row[3], row[4], row[5]);
            if (!row[0].equals(row[3])) {
                transformer.registerFieldRedirect(row[3], row[1], row[5], row[3], row[4], row[5]);
            }
            fields++;
        }

        // A class 26.2 split out of an older one is the older class again. If 26.2 reaches it through
        // a field on that class, the read becomes the object itself.
        int splits = 0;
        java.util.Set<String> selfTypes = new java.util.LinkedHashSet<>();
        for (String[] row : rows("splits.tsv", 6)) {
            String newClass = row[0];
            String oldClass = row[1];
            transformer.registerClassRedirect(newClass, oldClass);
            String holderOwner = row[4];
            String holderField = row[5];
            if (!holderField.isEmpty()) {
                if (!holderOwner.equals(oldClass)) {
                    throw new IllegalStateException("Split " + newClass + " is held by " + holderOwner
                            + "." + holderField + ", not by " + oldClass + ". Review splits.tsv.");
                }
                selfTypes.add(oldClass);
                transformer.registerFieldRedirect(holderOwner, holderField, "L" + newClass + ";",
                        SplitSelfHelper.NAME, SplitSelfHelper.METHOD, SplitSelfHelper.descriptorFor(oldClass));
            }
            splits++;
        }
        if (!selfTypes.isEmpty()) {
            transformer.registerSyntheticClass(SplitSelfHelper.NAME, SplitSelfHelper.build(selfTypes));
        }

        // Members 26.2 put on Gui become calls through the Minecraft singleton.
        List<BridgeHelper.Bridge> bridges = new ArrayList<>();
        for (String[] row : rows("bridges.tsv", 7)) {
            BridgeHelper.Bridge b = BridgeHelper.Bridge.of(row);
            transformer.registerMethodRedirect(b.newOwner(), b.name(), b.desc(), BridgeHelper.NAME,
                    b.helperName(), b.helperDesc(), true);
            bridges.add(b);
        }
        if (!bridges.isEmpty()) {
            transformer.registerSyntheticClass(BridgeHelper.NAME, BridgeHelper.build(bridges));
        }
        return new Counts(classes, methods, fields, splits, bridges.size());
    }

    /** Reads a tab-separated table, skipping comments and requiring at least {@code columns} columns. */
    static List<String[]> rows(String file, int columns) {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = BackportRegistration.class.getResourceAsStream(DIR + file)) {
            if (in == null) {
                throw new IllegalStateException("Backmod is missing its table " + DIR + file
                        + ". Rebuild the jar from a complete checkout.");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cols = line.split("\t", -1);
                if (cols.length < columns) {
                    throw new IllegalStateException("Bad row in " + DIR + file + ": " + line);
                }
                rows.add(cols);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }
}
