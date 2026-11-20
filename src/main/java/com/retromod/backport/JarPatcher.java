package com.retromod.backport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Rewrites a jar with some entries replaced, through a temporary file so a failure leaves it intact. */
public final class JarPatcher {

    private JarPatcher() {
    }

    /** Replaces each named entry with new bytes. Names that are not in the jar are ignored. */
    public static void replace(Path jar, Map<String, byte[]> replacements) throws IOException {
        if (replacements.isEmpty()) {
            return;
        }
        Path temp = Files.createTempFile(jar.toAbsolutePath().getParent(), "backmod-", ".tmp");
        try (ZipFile in = new ZipFile(jar.toFile()); OutputStream raw = Files.newOutputStream(temp);
             ZipOutputStream out = new ZipOutputStream(raw)) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);
                byte[] replacement = replacements.get(entry.getName());
                if (replacement != null) {
                    out.write(replacement);
                } else if (!entry.isDirectory()) {
                    try (InputStream data = in.getInputStream(entry)) {
                        data.transferTo(out);
                    }
                }
                out.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
    }
}
