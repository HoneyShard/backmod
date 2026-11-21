package com.retromod.backport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Remembers which jars in a folder were backported, so a sync rebuilds only what changed. */
public final class SyncIndex {

    /** Name of the index file. It lives outside the mod folder, where Fabric Loader would warn about it. */
    public static final String FILE = ".backmod-sync.json";

    /** What a sync does with one input jar. */
    public enum Action { BACKPORT, UP_TO_DATE }

    /** One input jar and what to do with it. */
    public record Item(Path input, String sha256, Action action) {
    }

    /** The whole decision: jars to handle, and outputs whose input is gone. */
    public record Plan(List<Item> items, List<String> staleOutputs) {
    }

    private final Path file;
    private final String stamp;
    private final Map<String, String> hashes = new TreeMap<>();
    /** Outputs Backmod wrote. Unlike the hashes, a changed stamp does not drop them, so no output is orphaned. */
    private final java.util.Set<String> owned = new java.util.TreeSet<>();

    private SyncIndex(Path file, String stamp) {
        this.file = file;
        this.stamp = stamp;
    }

    /**
     * Opens the index in {@code stateDir}. An index written under a different stamp is dropped, because
     * the tables or the Backmod version changed and every output may differ.
     */
    public static SyncIndex open(Path stateDir, String stamp) throws IOException {
        SyncIndex index = new SyncIndex(stateDir.resolve(FILE), stamp);
        if (Files.isRegularFile(index.file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(index.file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (root.has("owned")) {
                    root.getAsJsonArray("owned").forEach(e -> index.owned.add(e.getAsString()));
                }
                if (stamp.equals(root.get("stamp").getAsString())) {
                    root.getAsJsonObject("jars").entrySet()
                            .forEach(e -> index.hashes.put(e.getKey(), e.getValue().getAsString()));
                }
            } catch (RuntimeException unreadable) {
                // A damaged index only costs a full rebuild.
                index.hashes.clear();
                index.owned.clear();
            }
        }
        return index;
    }

    /** Decides what to do with each jar in {@code inputDir}. {@code force} rebuilds all of them. */
    public Plan plan(Path inputDir, Path outputDir, boolean force) throws IOException {
        List<Path> jars;
        try (Stream<Path> listing = Files.list(inputDir)) {
            jars = listing.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
                    .sorted().toList();
        }
        List<Item> items = new ArrayList<>();
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            String sha = sha256(jar);
            boolean current = !force && sha.equals(hashes.get(name)) && Files.isRegularFile(outputDir.resolve(name));
            items.add(new Item(jar, sha, current ? Action.UP_TO_DATE : Action.BACKPORT));
        }
        List<String> present = jars.stream().map(p -> p.getFileName().toString()).toList();
        List<String> stale = owned.stream().filter(name -> !present.contains(name)).toList();
        return new Plan(items, stale);
    }

    /** True when Backmod wrote the output with this name. */
    public boolean knows(String name) {
        return owned.contains(name);
    }

    /** Records a jar as backported from the input with this hash. */
    public void markDone(String name, String sha256) {
        hashes.put(name, sha256);
        owned.add(name);
    }

    /** Forgets a jar, so the next sync tries it again. */
    public void forget(String name) {
        hashes.remove(name);
        owned.remove(name);
    }

    public void save() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("stamp", stamp);
        root.add("jars", new Gson().toJsonTree(hashes));
        root.add("owned", new Gson().toJsonTree(owned));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, root + "\n", StandardCharsets.UTF_8);
    }

    static String sha256(Path jar) throws IOException {
        try (InputStream in = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
