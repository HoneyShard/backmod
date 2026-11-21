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

        // Hand-written rows for changes the diff cannot pair. After the generated tables, so they win a tie.
        for (String[] row : rows("manual.tsv", 7)) {
            boolean isClass = row[0].equals("class");
            boolean complete = !row[1].isBlank() && !row[4].isBlank()
                    && (isClass || (!row[2].isBlank() && !row[3].isBlank() && !row[5].isBlank() && !row[6].isBlank()));
            if (!complete) {
                throw new IllegalStateException("Incomplete row in manual.tsv: " + String.join(" | ", row));
            }
            if (isClass) {
                transformer.registerClassRedirect(row[1], row[4]);
                classes++;
            } else if (row[0].equals("method")) {
                transformer.registerMethodRedirect(row[1], row[2], row[3], row[4], row[5], row[6]);
                methods++;
            } else if (row[0].equals("iface-method")) {
                transformer.registerInterfaceMethodRename(row[1], row[2], row[5]);
                methods++;
            } else {
                throw new IllegalStateException("Unknown kind in manual.tsv: " + row[0]);
            }
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

        // Builder.tooltip(Component) is new in 26.2. The helper sets the same private field 26.1.2 reads.
        transformer.registerMethodRedirect(SpriteTooltipHelper.BUILDER, SpriteTooltipHelper.METHOD,
                "(Lnet/minecraft/network/chat/Component;)L" + SpriteTooltipHelper.BUILDER + ";",
                SpriteTooltipHelper.NAME, SpriteTooltipHelper.METHOD, SpriteTooltipHelper.DESC, true);
        transformer.registerSyntheticClass(SpriteTooltipHelper.NAME, SpriteTooltipHelper.build());
        methods++;

        // Rendering API 26.2 reshaped: pipeline builders, bind groups, blend factors, draw modes.
        methods += RenderBackport.register(transformer);
        return new Counts(classes, methods, fields, splits, bridges.size());
    }

    /**
     * A short hash of every shipped table plus the Backmod version. A sync that finds a different stamp
     * rebuilds all its jars, because the same input can now produce a different output.
     */
    public static String tablesStamp() {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(com.retromod.core.RetromodVersion.RETROMOD_VERSION.getBytes(StandardCharsets.UTF_8));
            // The registration code and the rebuilt render classes change output as much as the tables do.
            // Without this, a Backmod update that only changes code keeps every old conversion.
            digest.update(codeIdentity().getBytes(StandardCharsets.UTF_8));
            for (String table : List.of("classes.tsv", "methods.tsv", "fields.tsv", "splits.tsv", "bridges.tsv",
                    "manual.tsv", "colors.tsv")) {
                try (InputStream in = BackportRegistration.class.getResourceAsStream(DIR + table)) {
                    if (in != null) {
                        digest.update(in.readAllBytes());
                    }
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Backmod could not read its own tables: " + e.getMessage(), e);
        }
    }

    /** The self-hash of the running Backmod jar, or empty on an unpackaged development run. */
    static String codeIdentity() {
        try {
            String hash = com.retromod.security.SignatureVerifier.verify().selfHash();
            return hash == null ? "" : hash;
        } catch (Throwable t) {
            // A development classpath has no jar to hash. The stamp then follows the version and tables only.
            return "";
        }
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
