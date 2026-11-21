package com.retromod.backport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Removes Fabric API modules that a 26.2 mod carries as jar-in-jar.
 *
 * <p>Fabric Loader keeps the highest version of a duplicate mod id. A bundled 26.2 build of a Fabric API
 * module is usually newer than the 26.1.2 build in the host's Fabric API, so it replaces it, and its mixins
 * do not match the older game. Dropping the bundled copy lets the host's module load instead. The host must
 * then provide the module, so each dropped id is added to {@code depends} and the loader says so if it does not.
 */
public final class BundledFabricApi {

    private static final String NESTED_PREFIX = "META-INF/jars/";
    private static final String METADATA = "fabric.mod.json";
    /** Key prefix Fabric API's build puts in {@code custom} of every module it publishes. */
    private static final String MODULE_MARKER = "fabric-api:";

    private BundledFabricApi() {
    }

    /** One bundled module that was removed. */
    public record Module(String entry, String id, String version) {
        @Override
        public String toString() {
            return id + " " + version;
        }
    }

    /**
     * Returns the module's id and version when {@code nestedJar} is a Fabric API module, or null. The id must
     * start with {@code fabric-} and the metadata must carry Fabric API's module marker, so other mods that
     * happen to be named {@code fabric-...} (such as the Kotlin language adapter) are left alone.
     */
    static Module identify(String entry, byte[] nestedJar) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(nestedJar))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (!METADATA.equals(e.getName())) {
                    continue;
                }
                JsonElement parsed = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    return null;
                }
                JsonObject root = parsed.getAsJsonObject();
                String id = string(root, "id");
                if (id == null || !id.startsWith("fabric-") || !hasMarker(root)) {
                    return null;
                }
                String version = string(root, "version");
                return new Module(entry, id, version == null ? "" : version);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static boolean hasMarker(JsonObject root) {
        JsonElement custom = root.get("custom");
        if (custom == null || !custom.isJsonObject()) {
            return false;
        }
        return custom.getAsJsonObject().keySet().stream().anyMatch(k -> k.startsWith(MODULE_MARKER));
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /**
     * Removes every bundled Fabric API module from the jar, drops it from {@code jars} and requires its id.
     * A jar with no fabric.mod.json is left alone. Returns the modules removed, in jar order.
     */
    public static List<Module> strip(Path jar) throws IOException {
        List<Module> found = new ArrayList<>();
        byte[] metadata = null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry meta = zip.getEntry(METADATA);
            if (meta == null) {
                return found;
            }
            try (InputStream in = zip.getInputStream(meta)) {
                metadata = in.readAllBytes();
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.startsWith(NESTED_PREFIX) || !name.endsWith(".jar")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(e)) {
                    Module module = identify(name, in.readAllBytes());
                    if (module != null) {
                        found.add(module);
                    }
                }
            }
        }
        if (found.isEmpty()) {
            return found;
        }
        byte[] rewritten = dropFromMetadata(metadata, found);
        Set<String> removals = new LinkedHashSet<>();
        found.forEach(m -> removals.add(m.entry()));
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        replacements.put(METADATA, rewritten);
        JarPatcher.patch(jar, replacements, removals);
        return found;
    }

    /** Removes the modules from {@code jars} and adds a {@code depends} entry for each id that has none. */
    static byte[] dropFromMetadata(byte[] fabricModJson, List<Module> modules) {
        JsonElement parsed = JsonParser.parseString(new String(fabricModJson, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            return fabricModJson;
        }
        JsonObject root = parsed.getAsJsonObject();

        Set<String> entries = new LinkedHashSet<>();
        modules.forEach(m -> entries.add(m.entry()));
        JsonElement jars = root.get("jars");
        if (jars != null && jars.isJsonArray()) {
            JsonArray kept = new JsonArray();
            for (JsonElement jar : jars.getAsJsonArray()) {
                String file = jar.isJsonObject() ? string(jar.getAsJsonObject(), "file") : null;
                if (file == null || !entries.contains(file)) {
                    kept.add(jar);
                }
            }
            if (kept.isEmpty()) {
                root.remove("jars");
            } else {
                root.add("jars", kept);
            }
        }

        JsonElement existing = root.get("depends");
        JsonObject depends = existing != null && existing.isJsonObject()
                ? existing.getAsJsonObject() : new JsonObject();
        for (Module m : modules) {
            if (!depends.has(m.id())) {
                depends.addProperty(m.id(), "*");
            }
        }
        root.add("depends", depends);
        return (root + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
