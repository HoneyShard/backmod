/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;

import java.lang.reflect.Field;

/** Draws a chosen submit storage through 26.1.2's feature dispatcher, which only draws the one it was built with. */
public final class FeatureRenderDispatcherBridge {

    private static volatile Field storageField;

    private FeatureRenderDispatcherBridge() {}

    /** The dispatcher's own storage field, found once. Its four draw phases each read it when they run. */
    private static Field storageField() {
        Field field = storageField;
        if (field == null) {
            try {
                field = FeatureRenderDispatcher.class.getDeclaredField("submitNodeStorage");
                field.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Backmod: Minecraft 26.1.2's feature dispatcher has no "
                        + "submitNodeStorage field to draw another storage through.", e);
            }
            storageField = field;
        }
        return field;
    }

    /** {@code renderAllFeatures(storage)}: 26.2 draws and clears the storage it is given. */
    public static void renderAllFeatures(FeatureRenderDispatcher dispatcher, SubmitNodeStorage storage) {
        SubmitNodeStorage own = dispatcher.getSubmitNodeStorage();
        if (storage == null || storage == own) {
            dispatcher.renderAllFeatures();
            return;
        }
        // The feature renderers take their collection as an argument, so pointing the dispatcher at another
        // storage for the length of one call draws that storage and clears it, as 26.2 does.
        Field field = storageField();
        try {
            field.set(dispatcher, storage);
            try {
                dispatcher.renderAllFeatures();
            } finally {
                field.set(dispatcher, own);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Backmod: could not point the feature dispatcher at another "
                    + "submit storage.", e);
        }
    }
}
