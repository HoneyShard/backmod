package com.retromod.backport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.backport.HostIndex.Presence;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/** Checks every mixin in a jar against the target game jar and says which ones cannot apply. */
public final class MixinAudit {

    /** What the audit concluded about one mixin class. */
    public enum Status {
        /** Every checked target and member exists. */
        OK,
        /** No target class exists in the game jar, so the mixin does nothing and can be removed. */
        TARGET_MISSING,
        /** The target exists but a method or member the mixin names does not. */
        MEMBER_MISSING
    }

    /** One mixin class, the config that lists it, and the problems found. */
    public record Finding(String mixinClass, String config, Status status, List<String> targets,
                          List<String> problems) {
    }

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String[] INJECTOR_PACKAGES = {
        "Lorg/spongepowered/asm/mixin/injection/", "Lcom/llamalad7/mixinextras/injector/"};

    private MixinAudit() {
    }

    /** Reads the mixin configs fabric.mod.json lists, then audits each listed class. */
    public static List<Finding> audit(Path jar, HostIndex host) throws IOException {
        List<Finding> findings = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (Map.Entry<String, List<String>> config : mixinClasses(file).entrySet()) {
                for (String mixin : config.getValue()) {
                    ZipEntry entry = file.getEntry(mixin.replace('.', '/') + ".class");
                    if (entry == null) {
                        continue;
                    }
                    try (InputStream in = file.getInputStream(entry)) {
                        findings.add(auditClass(mixin, config.getKey(), in.readAllBytes(), host));
                    }
                }
            }
        }
        return findings;
    }

    /** Config file name to the fully qualified mixin classes it lists. */
    static Map<String, List<String>> mixinClasses(JarFile file) throws IOException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        ZipEntry modJson = file.getEntry("fabric.mod.json");
        if (modJson == null) {
            return result;
        }
        JsonObject mod = parse(file, modJson);
        JsonElement listed = mod == null ? null : mod.get("mixins");
        if (listed == null || !listed.isJsonArray()) {
            return result;
        }
        for (JsonElement item : listed.getAsJsonArray()) {
            String name = item.isJsonPrimitive() ? item.getAsString()
                    : item.isJsonObject() && item.getAsJsonObject().has("config")
                            ? item.getAsJsonObject().get("config").getAsString() : null;
            ZipEntry cfg = name == null ? null : file.getEntry(name);
            JsonObject config = cfg == null ? null : parse(file, cfg);
            if (config == null) {
                continue;
            }
            String pkg = config.has("package") ? config.get("package").getAsString() + "." : "";
            List<String> classes = new ArrayList<>();
            for (String key : new String[] {"mixins", "client", "server"}) {
                JsonElement arr = config.get(key);
                if (arr != null && arr.isJsonArray()) {
                    for (JsonElement c : arr.getAsJsonArray()) {
                        classes.add(pkg + c.getAsString());
                    }
                }
            }
            result.put(name, classes);
        }
        return result;
    }

    private static JsonObject parse(JarFile file, ZipEntry entry) throws IOException {
        try (InputStream in = file.getInputStream(entry)) {
            JsonElement e = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static Finding auditClass(String mixin, String config, byte[] bytes, HostIndex host) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                | ClassReader.SKIP_FRAMES);

        List<String> targets = targetsOf(node);
        List<String> gameTargets = targets.stream().filter(t -> t.startsWith("net/minecraft/")
                || t.startsWith("com/mojang/blaze3d/") || t.startsWith("com/mojang/math/")
                || t.startsWith("com/mojang/realmsclient/")).toList();
        List<String> present = gameTargets.stream().filter(host::hasClass).toList();
        if (!gameTargets.isEmpty() && present.isEmpty()) {
            return new Finding(mixin, config, Status.TARGET_MISSING, targets,
                    List.of("no target class exists in the game jar: " + String.join(", ", gameTargets)));
        }

        List<String> problems = new ArrayList<>();
        for (String t : gameTargets) {
            if (!host.hasClass(t)) {
                problems.add("target class missing: " + t);
            }
        }
        for (MethodNode m : node.methods) {
            for (AnnotationNode a : all(m.visibleAnnotations, m.invisibleAnnotations)) {
                checkMethodAnnotation(m, a, present, host, problems);
            }
        }
        for (FieldNode f : node.fields) {
            for (AnnotationNode a : all(f.visibleAnnotations, f.invisibleAnnotations)) {
                if (SHADOW.equals(a.desc) && !anyHas(present, host, f.name, f.desc, false)) {
                    problems.add("@Shadow field " + f.name + " not found in " + present);
                }
            }
        }
        return new Finding(mixin, config, problems.isEmpty() ? Status.OK : Status.MEMBER_MISSING, targets,
                problems);
    }

    private static void checkMethodAnnotation(MethodNode m, AnnotationNode a, List<String> targets,
                                              HostIndex host, List<String> problems) {
        if (targets.isEmpty()) {
            return;
        }
        if (SHADOW.equals(a.desc)) {
            if (!anyHas(targets, host, m.name, m.desc, true)) {
                problems.add("@Shadow method " + m.name + m.desc + " not found in " + targets);
            }
        } else if (OVERWRITE.equals(a.desc)) {
            if (!anyHas(targets, host, m.name, m.desc, true)) {
                problems.add("@Overwrite " + m.name + m.desc + " not found in " + targets);
            }
        } else if (ACCESSOR.equals(a.desc) || INVOKER.equals(a.desc)) {
            boolean invoker = INVOKER.equals(a.desc);
            String name = stringValue(a, "value");
            if (name == null) {
                name = derive(m.name, invoker);
            }
            if (name != null && !anyHas(targets, host, name, null, invoker)) {
                problems.add((invoker ? "@Invoker " : "@Accessor ") + m.name + " wants " + name
                        + ", not found in " + targets);
            }
        } else if (isInjector(a.desc)) {
            for (String selector : stringList(a, "method")) {
                String[] parsed = parseSelector(selector);
                if (parsed != null && !anyHas(targets, host, parsed[0], parsed[1], true)) {
                    problems.add(shortName(a.desc) + " selector " + selector + " not found in " + targets);
                }
            }
        }
    }

    /** Splits {@code Lowner;name(desc)ret} into name and descriptor, or null when it is a pattern. */
    static String[] parseSelector(String selector) {
        String s = selector.trim();
        if (s.isEmpty() || s.startsWith("/") || s.contains("*")) {
            return null;
        }
        // An owner prefix such as Lnet/minecraft/Foo;bar(I)V is dropped, the target is already known.
        java.util.regex.Matcher owner = java.util.regex.Pattern.compile("^L[^;(]+;").matcher(s);
        if (owner.find()) {
            s = s.substring(owner.end());
        }
        int paren = s.indexOf('(');
        return paren < 0 ? new String[] {s, null} : new String[] {s.substring(0, paren), s.substring(paren)};
    }

    private static String derive(String methodName, boolean invoker) {
        String[] prefixes = invoker ? new String[] {"call", "invoke"} : new String[] {"get", "set", "is"};
        for (String p : prefixes) {
            if (methodName.startsWith(p) && methodName.length() > p.length()) {
                String rest = methodName.substring(p.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return null;
    }

    /** True when at least one target (or what it inherits) has the member, or the jar cannot say. */
    private static boolean anyHas(List<String> targets, HostIndex host, String name, String desc,
                                  boolean method) {
        for (String t : targets) {
            Presence p = host.member(t, name, desc, method);
            if (p == Presence.FOUND || p == Presence.UNKNOWN) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInjector(String desc) {
        for (String p : INJECTOR_PACKAGES) {
            if (desc.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String shortName(String desc) {
        return "@" + desc.substring(desc.lastIndexOf('/') + 1, desc.length() - 1);
    }

    private static List<String> targetsOf(ClassNode node) {
        List<String> targets = new ArrayList<>();
        for (AnnotationNode a : all(node.visibleAnnotations, node.invisibleAnnotations)) {
            if (!MIXIN.equals(a.desc) || a.values == null) {
                continue;
            }
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                String key = (String) a.values.get(i);
                Object value = a.values.get(i + 1);
                if (value instanceof List<?> list) {
                    for (Object o : list) {
                        if (key.equals("value") && o instanceof Type t) {
                            targets.add(t.getInternalName());
                        } else if (key.equals("targets") && o instanceof String s) {
                            targets.add(s.replace('.', '/'));
                        }
                    }
                }
            }
        }
        return targets;
    }

    private static String stringValue(AnnotationNode a, String key) {
        if (a.values != null) {
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                if (key.equals(a.values.get(i)) && a.values.get(i + 1) instanceof String s && !s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    private static List<String> stringList(AnnotationNode a, String key) {
        List<String> out = new ArrayList<>();
        if (a.values != null) {
            for (int i = 0; i + 1 < a.values.size(); i += 2) {
                if (key.equals(a.values.get(i)) && a.values.get(i + 1) instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof String s) {
                            out.add(s);
                        }
                    }
                }
            }
        }
        return out;
    }

    @SafeVarargs
    private static List<AnnotationNode> all(List<AnnotationNode>... lists) {
        List<AnnotationNode> out = new ArrayList<>();
        for (List<AnnotationNode> l : lists) {
            if (l != null) {
                out.addAll(l);
            }
        }
        return out;
    }

    /** Removes the given mixin classes from the config JSON text. Returns the text unchanged if none match. */
    public static String stripFromConfig(String configJson, String packageName, java.util.Set<String> classes) {
        JsonObject config = JsonParser.parseString(configJson).getAsJsonObject();
        String prefix = config.has("package") ? config.get("package").getAsString() + "." : "";
        for (String key : new String[] {"mixins", "client", "server"}) {
            JsonElement arr = config.get(key);
            if (arr == null || !arr.isJsonArray()) {
                continue;
            }
            JsonArray kept = new JsonArray();
            for (JsonElement c : arr.getAsJsonArray()) {
                if (!classes.contains(prefix + c.getAsString())) {
                    kept.add(c);
                }
            }
            config.add(key, kept);
        }
        return config + "\n";
    }
}
