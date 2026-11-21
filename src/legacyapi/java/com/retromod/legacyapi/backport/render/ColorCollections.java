/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads 26.1.2's sixteen per-color fields as one 26.2 color collection. Each getter is named for the field it replaces. */
public final class ColorCollections {

    private static final Map<String, ColorCollection<Object>> CACHE = new ConcurrentHashMap<>();

    private ColorCollections() {}

    /** Builds the collection once, because every read of the 26.2 field gets the same object. */
    private static ColorCollection<Object> of(String holder, String suffix) {
        return CACHE.computeIfAbsent(holder + ":" + suffix, key -> build(holder, suffix));
    }

    private static ColorCollection<Object> build(String holder, String suffix) {
        try {
            Class<?> owner = Class.forName(holder);
            List<Object> values = new ArrayList<>(16);
            for (DyeColor color : DyeColor.values()) {
                values.add(owner.getField(color.name() + "_" + suffix).get(null));
            }
            return ColorCollection.ofList(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Backmod: Minecraft 26.1.2 has no " + holder + ".<COLOR>_" + suffix
                    + " fields to stand in for the 26.2 color collection.", e);
        }
    }

    /** {@code Items.WOOL}, from the sixteen {@code <COLOR>_WOOL} fields. */
    public static ColorCollection<Object> items_WOOL() {
        return of("net.minecraft.world.item.Items", "WOOL");
    }

    /** {@code Items.DYED_TERRACOTTA}, from the sixteen {@code <COLOR>_TERRACOTTA} fields. */
    public static ColorCollection<Object> items_DYED_TERRACOTTA() {
        return of("net.minecraft.world.item.Items", "TERRACOTTA");
    }

    /** {@code Items.CARPET}, from the sixteen {@code <COLOR>_CARPET} fields. */
    public static ColorCollection<Object> items_CARPET() {
        return of("net.minecraft.world.item.Items", "CARPET");
    }

    /** {@code Items.STAINED_GLASS}, from the sixteen {@code <COLOR>_STAINED_GLASS} fields. */
    public static ColorCollection<Object> items_STAINED_GLASS() {
        return of("net.minecraft.world.item.Items", "STAINED_GLASS");
    }

    /** {@code Items.STAINED_GLASS_PANE}, from the sixteen {@code <COLOR>_STAINED_GLASS_PANE} fields. */
    public static ColorCollection<Object> items_STAINED_GLASS_PANE() {
        return of("net.minecraft.world.item.Items", "STAINED_GLASS_PANE");
    }

    /** {@code Items.DYED_SHULKER_BOX}, from the sixteen {@code <COLOR>_SHULKER_BOX} fields. */
    public static ColorCollection<Object> items_DYED_SHULKER_BOX() {
        return of("net.minecraft.world.item.Items", "SHULKER_BOX");
    }

    /** {@code Items.GLAZED_TERRACOTTA}, from the sixteen {@code <COLOR>_GLAZED_TERRACOTTA} fields. */
    public static ColorCollection<Object> items_GLAZED_TERRACOTTA() {
        return of("net.minecraft.world.item.Items", "GLAZED_TERRACOTTA");
    }

    /** {@code Items.CONCRETE}, from the sixteen {@code <COLOR>_CONCRETE} fields. */
    public static ColorCollection<Object> items_CONCRETE() {
        return of("net.minecraft.world.item.Items", "CONCRETE");
    }

    /** {@code Items.CONCRETE_POWDER}, from the sixteen {@code <COLOR>_CONCRETE_POWDER} fields. */
    public static ColorCollection<Object> items_CONCRETE_POWDER() {
        return of("net.minecraft.world.item.Items", "CONCRETE_POWDER");
    }

    /** {@code Items.HARNESS}, from the sixteen {@code <COLOR>_HARNESS} fields. */
    public static ColorCollection<Object> items_HARNESS() {
        return of("net.minecraft.world.item.Items", "HARNESS");
    }

    /** {@code Items.DYED_BUNDLE}, from the sixteen {@code <COLOR>_BUNDLE} fields. */
    public static ColorCollection<Object> items_DYED_BUNDLE() {
        return of("net.minecraft.world.item.Items", "BUNDLE");
    }

    /** {@code Items.DYE}, from the sixteen {@code <COLOR>_DYE} fields. */
    public static ColorCollection<Object> items_DYE() {
        return of("net.minecraft.world.item.Items", "DYE");
    }

    /** {@code Items.BED}, from the sixteen {@code <COLOR>_BED} fields. */
    public static ColorCollection<Object> items_BED() {
        return of("net.minecraft.world.item.Items", "BED");
    }

    /** {@code Items.BANNER}, from the sixteen {@code <COLOR>_BANNER} fields. */
    public static ColorCollection<Object> items_BANNER() {
        return of("net.minecraft.world.item.Items", "BANNER");
    }

    /** {@code Items.DYED_CANDLE}, from the sixteen {@code <COLOR>_CANDLE} fields. */
    public static ColorCollection<Object> items_DYED_CANDLE() {
        return of("net.minecraft.world.item.Items", "CANDLE");
    }

    /** {@code Blocks.BED}, from the sixteen {@code <COLOR>_BED} fields. */
    public static ColorCollection<Object> blocks_BED() {
        return of("net.minecraft.world.level.block.Blocks", "BED");
    }

    /** {@code Blocks.WOOL}, from the sixteen {@code <COLOR>_WOOL} fields. */
    public static ColorCollection<Object> blocks_WOOL() {
        return of("net.minecraft.world.level.block.Blocks", "WOOL");
    }

    /** {@code Blocks.STAINED_GLASS}, from the sixteen {@code <COLOR>_STAINED_GLASS} fields. */
    public static ColorCollection<Object> blocks_STAINED_GLASS() {
        return of("net.minecraft.world.level.block.Blocks", "STAINED_GLASS");
    }

    /** {@code Blocks.DYED_TERRACOTTA}, from the sixteen {@code <COLOR>_TERRACOTTA} fields. */
    public static ColorCollection<Object> blocks_DYED_TERRACOTTA() {
        return of("net.minecraft.world.level.block.Blocks", "TERRACOTTA");
    }

    /** {@code Blocks.STAINED_GLASS_PANE}, from the sixteen {@code <COLOR>_STAINED_GLASS_PANE} fields. */
    public static ColorCollection<Object> blocks_STAINED_GLASS_PANE() {
        return of("net.minecraft.world.level.block.Blocks", "STAINED_GLASS_PANE");
    }

    /** {@code Blocks.CARPET}, from the sixteen {@code <COLOR>_CARPET} fields. */
    public static ColorCollection<Object> blocks_CARPET() {
        return of("net.minecraft.world.level.block.Blocks", "CARPET");
    }

    /** {@code Blocks.BANNER}, from the sixteen {@code <COLOR>_BANNER} fields. */
    public static ColorCollection<Object> blocks_BANNER() {
        return of("net.minecraft.world.level.block.Blocks", "BANNER");
    }

    /** {@code Blocks.WALL_BANNER}, from the sixteen {@code <COLOR>_WALL_BANNER} fields. */
    public static ColorCollection<Object> blocks_WALL_BANNER() {
        return of("net.minecraft.world.level.block.Blocks", "WALL_BANNER");
    }

    /** {@code Blocks.DYED_SHULKER_BOX}, from the sixteen {@code <COLOR>_SHULKER_BOX} fields. */
    public static ColorCollection<Object> blocks_DYED_SHULKER_BOX() {
        return of("net.minecraft.world.level.block.Blocks", "SHULKER_BOX");
    }

    /** {@code Blocks.GLAZED_TERRACOTTA}, from the sixteen {@code <COLOR>_GLAZED_TERRACOTTA} fields. */
    public static ColorCollection<Object> blocks_GLAZED_TERRACOTTA() {
        return of("net.minecraft.world.level.block.Blocks", "GLAZED_TERRACOTTA");
    }

    /** {@code Blocks.CONCRETE}, from the sixteen {@code <COLOR>_CONCRETE} fields. */
    public static ColorCollection<Object> blocks_CONCRETE() {
        return of("net.minecraft.world.level.block.Blocks", "CONCRETE");
    }

    /** {@code Blocks.CONCRETE_POWDER}, from the sixteen {@code <COLOR>_CONCRETE_POWDER} fields. */
    public static ColorCollection<Object> blocks_CONCRETE_POWDER() {
        return of("net.minecraft.world.level.block.Blocks", "CONCRETE_POWDER");
    }

    /** {@code Blocks.DYED_CANDLE}, from the sixteen {@code <COLOR>_CANDLE} fields. */
    public static ColorCollection<Object> blocks_DYED_CANDLE() {
        return of("net.minecraft.world.level.block.Blocks", "CANDLE");
    }

    /** {@code Blocks.DYED_CANDLE_CAKE}, from the sixteen {@code <COLOR>_CANDLE_CAKE} fields. */
    public static ColorCollection<Object> blocks_DYED_CANDLE_CAKE() {
        return of("net.minecraft.world.level.block.Blocks", "CANDLE_CAKE");
    }
}
