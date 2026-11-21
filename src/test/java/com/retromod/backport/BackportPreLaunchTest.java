package com.retromod.backport;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The mod jar runs the backport at launch only if its metadata lists the entrypoint. */
class BackportPreLaunchTest {

    @Test
    void fabricModJsonListsTheBackportEntrypointAfterRetromods() throws IOException {
        JsonObject mod;
        try (var in = BackportPreLaunchTest.class.getResourceAsStream("/fabric.mod.json")) {
            mod = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var preLaunch = mod.getAsJsonObject("entrypoints").getAsJsonArray("preLaunch");

        assertTrue(preLaunch.size() == 2, "expected the forward and the backport entrypoint: " + preLaunch);
        // The forward pass registers shims first. The backport resets and restores them, so it must come second.
        assertTrue(preLaunch.get(0).getAsString().endsWith("RetromodPreLaunch"), preLaunch.toString());
        assertTrue(preLaunch.get(1).getAsString().equals(BackportPreLaunch.class.getName()), preLaunch.toString());
        assertTrue(mod.get("name").getAsString().equals("Backmod"), "the visible name is Backmod");
    }
}
