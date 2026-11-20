package com.retromod.backport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

/** Rewrites fabric.mod.json so a backported mod names the one Minecraft version it was checked on. */
public final class BackportMetadata {

    private BackportMetadata() {
    }

    /**
     * Sets {@code depends.minecraft} to the target and records the backport under {@code custom.backmod}.
     * A file that is not a JSON object is returned unchanged.
     */
    public static byte[] apply(byte[] fabricModJson, String source, String target) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(fabricModJson, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return fabricModJson;
        }
        if (!parsed.isJsonObject()) {
            return fabricModJson;
        }
        JsonObject root = parsed.getAsJsonObject();

        // A mod with no minecraft dependency still gets one, so it will not load on an untested version.
        JsonElement existing = root.get("depends");
        JsonObject depends = existing != null && existing.isJsonObject()
                ? existing.getAsJsonObject() : new JsonObject();
        depends.addProperty("minecraft", target);
        root.add("depends", depends);

        JsonElement customElement = root.get("custom");
        JsonObject custom = customElement != null && customElement.isJsonObject()
                ? customElement.getAsJsonObject() : new JsonObject();
        JsonObject stamp = new JsonObject();
        stamp.addProperty("backported_from", source);
        stamp.addProperty("backported_to", target);
        custom.add("backmod", stamp);
        root.add("custom", custom);

        return (root + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
