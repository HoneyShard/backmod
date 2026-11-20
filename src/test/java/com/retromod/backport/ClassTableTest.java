package com.retromod.backport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks the shipped 26.2 to 26.1.2 class table can be inverted without loss or loops. */
class ClassTableTest {

    private static final String TABLE = "/backmod/fabric/26.2-to-26.1.2/classes.tsv";

    private static Map<String, String> loadTable() throws Exception {
        Path temp = Files.createTempFile("classes", ".tsv");
        try (InputStream in = ClassTableTest.class.getResourceAsStream(TABLE)) {
            assertTrue(in != null, "missing resource " + TABLE);
            Files.write(temp, in.readAllBytes());
            return BackportDiffTool.readClassTable(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    void tableIsNotEmptyAndHasNoDuplicateKeys() throws Exception {
        // readClassTable throws on a duplicate 26.2 name.
        assertTrue(loadTable().size() > 100, "the verified table should hold the 26.1.2 to 26.2 moves");
    }

    @Test
    void inverseRecoversEveryRow() throws Exception {
        Map<String, String> reverse = loadTable();
        Map<String, String> forward = new HashMap<>();
        for (Map.Entry<String, String> row : reverse.entrySet()) {
            String clash = forward.put(row.getValue(), row.getKey());
            assertEquals(null, clash, "two 26.2 classes map to " + row.getValue()
                    + ", so the table is not invertible");
        }
        for (Map.Entry<String, String> row : reverse.entrySet()) {
            String viaForward = forward.get(reverse.get(row.getKey()));
            assertEquals(row.getKey(), viaForward, "forward(reverse(x)) must give back " + row.getKey());
        }
    }

    @Test
    void noRowChainsIntoAnother() throws Exception {
        // A chain would make the transformer's fixpoint loop rewrite a name twice.
        Map<String, String> table = loadTable();
        for (Map.Entry<String, String> row : table.entrySet()) {
            assertFalse(table.containsKey(row.getValue()),
                    row.getKey() + " maps to " + row.getValue() + ", which is itself remapped");
        }
    }

    @Test
    void rowsKeepTheirPackageRootsAndNeverMapToThemselves() throws Exception {
        List<String> roots = List.of("net/minecraft/", "com/mojang/");
        for (Map.Entry<String, String> row : loadTable().entrySet()) {
            assertFalse(row.getKey().equals(row.getValue()), "identity row " + row.getKey());
            assertTrue(roots.stream().anyMatch(row.getKey()::startsWith), row.getKey());
            assertTrue(roots.stream().anyMatch(row.getValue()::startsWith), row.getValue());
        }
        assertTrue(new String(ClassTableTest.class.getResourceAsStream(TABLE).readAllBytes(),
                StandardCharsets.UTF_8).startsWith("# Backmod"));
    }
}
