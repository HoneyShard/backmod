/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.backport.render;

import net.minecraft.world.item.DyeColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/** The 26.2 holder of one value per dye color, which 26.1.2 spells as sixteen separate fields. */
public record ColorCollection<T>(T white, T orange, T magenta, T lightBlue, T yellow, T lime, T pink, T gray,
                                 T lightGray, T cyan, T purple, T blue, T brown, T green, T red, T black) {

    /** The dye colors themselves, in the same order as the collection. */
    public static final ColorCollection<DyeColor> VALUES = ofList(List.of(DyeColor.values()));

    /** The lower-case name of each dye color, such as {@code light_blue}. */
    public static final ColorCollection<String> NAMES = VALUES.map(color -> color.name().toLowerCase(Locale.ROOT));

    /** Builds a collection from sixteen values in dye order. */
    public static <T> ColorCollection<T> ofList(List<T> values) {
        if (values.size() != 16) {
            throw new IllegalArgumentException("Backmod: a color collection holds 16 values, not " + values.size() + ".");
        }
        return new ColorCollection<>(values.get(0), values.get(1), values.get(2), values.get(3), values.get(4),
                values.get(5), values.get(6), values.get(7), values.get(8), values.get(9), values.get(10),
                values.get(11), values.get(12), values.get(13), values.get(14), values.get(15));
    }

    public List<T> asList() {
        return List.of(white, orange, magenta, lightBlue, yellow, lime, pink, gray, lightGray, cyan, purple, blue,
                brown, green, red, black);
    }

    public void forEach(Consumer<T> action) {
        asList().forEach(action);
    }

    /** The value for one dye color. */
    public T pick(DyeColor color) {
        return asList().get(color.ordinal());
    }

    public <U> ColorCollection<U> map(Function<T, U> mapper) {
        List<U> mapped = new ArrayList<>(16);
        for (T value : asList()) {
            mapped.add(mapper.apply(value));
        }
        return ofList(mapped);
    }

    public static <T, U> void zipApply(ColorCollection<T> first, ColorCollection<U> second, BiConsumer<T, U> action) {
        List<T> left = first.asList();
        List<U> right = second.asList();
        for (int i = 0; i < 16; i++) {
            action.accept(left.get(i), right.get(i));
        }
    }

    public static <T, U, R> ColorCollection<R> zipMap(ColorCollection<T> first, ColorCollection<U> second,
                                                      BiFunction<T, U, R> mapper) {
        List<T> left = first.asList();
        List<U> right = second.asList();
        List<R> mapped = new ArrayList<>(16);
        for (int i = 0; i < 16; i++) {
            mapped.add(mapper.apply(left.get(i), right.get(i)));
        }
        return ofList(mapped);
    }
}
