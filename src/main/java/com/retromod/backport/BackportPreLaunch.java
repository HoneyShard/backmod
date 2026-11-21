package com.retromod.backport;

import com.retromod.cli.RetromodCli;
import com.retromod.core.RetromodPreLaunch;
import com.retromod.core.RetromodTransformer;
import com.retromod.gui.RestartPrompt;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Backports the 26.2 mods in {@code mods-26.2/} into {@code mods/} when the game starts, so Backmod works as a mod
 * jar and needs no command line.
 *
 * <p>Fabric has already scanned {@code mods/} when pre-launch runs, so a jar written now loads on the next launch.
 * The {@code 26.2} originals stay out of {@code mods/}, where Fabric would refuse them for needing a newer game.
 */
public final class BackportPreLaunch implements net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint {

    private static final Logger LOGGER = LoggerFactory.getLogger("Backmod");

    /** Folder the user drops 26.2 mod jars into, beside {@code mods/}. */
    public static final String INPUT_FOLDER = "mods-26.2";

    /** Holds the sync index and one report per mod. Not a mod folder, so Fabric Loader never scans it. */
    public static final String STATE_FOLDER = "backmod";

    @Override
    public void onPreLaunch() {
        try {
            run();
        } catch (Throwable failed) {
            // A failed backport must never keep the game from starting.
            LOGGER.error("Backmod could not backport the mods in {}: {}", INPUT_FOLDER, failed.toString(), failed);
        }
    }

    private void run() throws Exception {
        Optional<ModContainer> minecraft = FabricLoader.getInstance().getModContainer("minecraft");
        if (minecraft.isEmpty()) {
            return;
        }
        String host = minecraft.get().getMetadata().getVersion().getFriendlyString();
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path from = gameDir.resolve(INPUT_FOLDER);

        if (!BackportRegistration.TARGET.equals(host)) {
            // Only 26.1.2 has verified tables. Say so when the user clearly expects it to work.
            if (Files.isDirectory(from) && hasJars(from)) {
                LOGGER.warn("Backmod only backports to Minecraft {}, and this is {}. The mods in {} stay unused.",
                        BackportRegistration.TARGET, host, INPUT_FOLDER);
            }
            return;
        }
        if (!Files.isDirectory(from)) {
            Files.createDirectories(from);
            LOGGER.info("Created {}. Put Fabric mods built for Minecraft {} in it, and Backmod will convert them "
                    + "for {} the next time the game starts.", INPUT_FOLDER, BackportRegistration.SOURCE, host);
            return;
        }

        Path mcJar = gameJar(minecraft.get());
        if (mcJar == null) {
            LOGGER.warn("Backmod could not find the Minecraft {} jar, so it cannot check mixins against it. "
                    + "Nothing was backported.", host);
            return;
        }

        if (FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER) {
            // A server jar has no client classes, so the reports list every client reference as missing.
            LOGGER.info("This is a dedicated server, so the reports in {}/reports cannot check client classes.",
                    STATE_FOLDER);
        }

        Path to = gameDir.resolve("mods");
        RetromodCli.SyncResult result = RetromodCli.syncForGame(from, to, gameDir.resolve(STATE_FOLDER), mcJar);

        // The sync registered the reverse tables on the shared transformer. Put the forward shims back
        // so the rest of this launch sees what Retromod set up before.
        RetromodTransformer.getInstance().resetOfflineRegistrations();
        RetromodPreLaunch.registerShimsForTransform(host);

        for (String problem : result.problems()) {
            LOGGER.warn("Backmod skipped {}", problem);
        }
        for (String verdict : result.verdicts()) {
            LOGGER.info("Backmod: {}", verdict);
        }
        int changed = result.backported() + result.removed();
        if (changed > 0) {
            LOGGER.info("Backmod changed {} mod(s) in mods/. Restart Minecraft to load them. Reports are in {}/reports.",
                    changed, STATE_FOLDER);
            RestartPrompt.markPending(RetromodPreLaunch.getTotalTransformed() + changed);
        } else if (result.problems().isEmpty()) {
            LOGGER.debug("Backmod: {} mod(s) in {} are up to date", result.unchanged(), INPUT_FOLDER);
        }
    }

    private static boolean hasJars(Path folder) throws java.io.IOException {
        try (var listing = Files.list(folder)) {
            return listing.anyMatch(p -> p.getFileName().toString().endsWith(".jar"));
        }
    }

    /** The jar the loader reads Minecraft from, or null when it is not a plain jar file. */
    private static Path gameJar(ModContainer minecraft) {
        for (Path root : minecraft.getOrigin().getPaths()) {
            if (Files.isRegularFile(root) && root.getFileName().toString().endsWith(".jar")) {
                return root;
            }
        }
        return null;
    }
}
