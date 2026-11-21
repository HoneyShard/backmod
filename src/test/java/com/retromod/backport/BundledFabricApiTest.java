package com.retromod.backport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A backported mod must not carry its own copy of a Fabric API module, but other nested jars stay. */
class BundledFabricApiTest {

    private static final String API_BASE = "META-INF/jars/fabric-api-base-2.0.4.jar";
    private static final String LIFECYCLE = "META-INF/jars/fabric-lifecycle-events-v1-4.1.3.jar";
    private static final String KOTLIN = "META-INF/jars/fabric-language-kotlin-1.13.jar";
    private static final String LIBRARY = "META-INF/jars/somelib-1.0.jar";

    private static byte[] zip(String[] names, byte[][] contents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (int i = 0; i < names.length; i++) {
                out.putNextEntry(new ZipEntry(names[i]));
                out.write(contents[i]);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] nested(String id, String version, boolean fabricApiMarker) throws IOException {
        String custom = fabricApiMarker ? ",\"custom\":{\"fabric-api:module-lifecycle\":\"stable\"}" : "";
        return zip(new String[] {"fabric.mod.json"}, new byte[][] {text(
                "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"" + custom + "}")});
    }

    private Path modJar(Path dir) throws IOException {
        String metadata = "{\"schemaVersion\":1,\"id\":\"themod\",\"version\":\"1\","
                + "\"depends\":{\"fabric-lifecycle-events-v1\":\">=4\",\"minecraft\":\"26.1.2\"},"
                + "\"jars\":[{\"file\":\"" + API_BASE + "\"},{\"file\":\"" + LIFECYCLE + "\"},"
                + "{\"file\":\"" + KOTLIN + "\"},{\"file\":\"" + LIBRARY + "\"}]}";
        Path jar = dir.resolve("themod.jar");
        Files.write(jar, zip(
                new String[] {"fabric.mod.json", "a/Main.class", API_BASE, LIFECYCLE, KOTLIN, LIBRARY},
                new byte[][] {text(metadata), new byte[] {1, 2, 3},
                        nested("fabric-api-base", "2.0.4", true),
                        nested("fabric-lifecycle-events-v1", "4.1.3", true),
                        nested("fabric-language-kotlin", "1.13", false),
                        nested("somelib", "1.0", false)}));
        return jar;
    }

    @Test
    void removesFabricApiModulesAndKeepsEverythingElse(@TempDir Path dir) throws IOException {
        Path jar = modJar(dir);

        List<BundledFabricApi.Module> removed = BundledFabricApi.strip(jar);

        assertEquals(List.of("fabric-api-base", "fabric-lifecycle-events-v1"),
                removed.stream().map(BundledFabricApi.Module::id).toList());
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertNull(zip.getEntry(API_BASE));
            assertNull(zip.getEntry(LIFECYCLE));
            assertNotNull(zip.getEntry(KOTLIN), "a mod named fabric-* but not a Fabric API module stays");
            assertNotNull(zip.getEntry(LIBRARY));
            assertNotNull(zip.getEntry("a/Main.class"));

            JsonObject json = JsonParser.parseString(new String(
                    zip.getInputStream(zip.getEntry("fabric.mod.json")).readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String jars = json.get("jars").toString();
            assertFalse(jars.contains("fabric-api-base"), jars);
            assertFalse(jars.contains("fabric-lifecycle-events-v1"), jars);
            assertTrue(jars.contains("fabric-language-kotlin") && jars.contains("somelib"), jars);

            JsonObject depends = json.getAsJsonObject("depends");
            assertEquals("*", depends.get("fabric-api-base").getAsString(), "a removed module is now required");
            assertEquals(">=4", depends.get("fabric-lifecycle-events-v1").getAsString(),
                    "an existing requirement is not loosened");
            assertEquals("26.1.2", depends.get("minecraft").getAsString());
        }
    }

    @Test
    void dropsTheJarsKeyWhenNothingElseIsNested(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("only.jar");
        Files.write(jar, zip(new String[] {"fabric.mod.json", API_BASE}, new byte[][] {
                text("{\"schemaVersion\":1,\"id\":\"only\",\"version\":\"1\",\"jars\":[{\"file\":\"" + API_BASE + "\"}]}"),
                nested("fabric-api-base", "2.0.4", true)}));

        assertEquals(1, BundledFabricApi.strip(jar).size());
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            JsonObject json = JsonParser.parseString(new String(
                    zip.getInputStream(zip.getEntry("fabric.mod.json")).readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertFalse(json.has("jars"));
        }
    }

    @Test
    void aJarWithNothingToStripIsNotRewritten(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("plain.jar");
        Files.write(jar, zip(new String[] {"fabric.mod.json", LIBRARY}, new byte[][] {
                text("{\"schemaVersion\":1,\"id\":\"plain\",\"version\":\"1\"}"), nested("somelib", "1.0", false)}));
        byte[] before = Files.readAllBytes(jar);

        assertTrue(BundledFabricApi.strip(jar).isEmpty());
        assertEquals(before.length, Files.readAllBytes(jar).length);
    }

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }
}
