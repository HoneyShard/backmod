# Backmod To-Do (handoff)

State at the end of day 1. Branch `backmod/main` in `~/backmod-work/upstream`. Nothing is pushed and no remote of our own exists. Read `docs-dev/BACKPORT_FINDINGS.md` first, then `docs-dev/backport/README.md` and `non-invertible-report.md`.

## Done

- Step 0: visible identity renamed to Backmod (package stays `com.retromod`). Upstream README is at `docs-dev/UPSTREAM_README.md`.
- Step 1: findings doc.
- Step 2: `com.retromod.backport.BackportDiffTool` plus verified tables in `src/main/resources/backmod/fabric/26.2-to-26.1.2/`: 199 class moves, 49 method renames, 11 field renames, 3 class splits (`Hud` into `Gui`, `EntityTypes`, `BlockEntityTypes`), 5 `Gui` bridges.
- Step 3: `transform <jar> --direction backport --mc-jar <26.1.2 client.jar>`. Pins `depends.minecraft` to `26.1.2`, stamps `custom.backmod`.
- Steps 5 and 7 (first cut): `MixinAudit` strips mixins whose target class is gone, `ReferenceAudit` lists game references 26.1.2 lacks, and a `<output>.report.md` carries a verdict (`--mixin-loss-threshold`, default 0.5).
- Tests: `src/test/java/com/retromod/backport/` (31 tests). Last full suite: 7,156 tests, 1 failure (`RetromodOwnClassesTest`, new package `backport` was not listed in `SyntheticEmbedder.OWN_PACKAGES`). Fixed, and the affected tests (69) pass. The full suite was not re-run after that one-line fix.

## Results so far (run4, before the Object-method fix)

| Mod | Verdict | Missing refs | Notes |
|---|---|---|---|
| AppleSkin 3.0.10+mc26.2 | LIKELY_OK | 0 | `HudMixin` retargeted `Hud` to `Gui`, 8 of 8 mixins resolve |
| Cloth Config 26.2.155 | PARTIAL | 4 (3 after the fix) | `ColorCollection`, `Items.WOOL`, `TextColor.DARK_GRAY`, all in demo code |
| Mod Menu 20.0.3 | LIKELY_BROKEN | 7 | `OptionInstance` and `SpriteIconButton` signatures, `TitleScreen` `@Shadow` |
| Placeholder API 3.1.0-beta.1 | PARTIAL | 2 | `PlayerTeam.getColor`, `TeamColor` |
| YACL 3.9.7 | PARTIAL | 3 | `GuiAccessor` wants `Gui.screen`, `MenuTabBar`, `LevelExtractor` |

On a headless Fabric 26.1.2 server (`~/backmod-work/server`) Fabric Loader 0.20.1 resolved all of them with Fabric API 0.155.3. The server then stopped at the EULA, so no mixin was applied and no content was registered yet.

## Next, in order

1. Decide on the EULA. Accepting it is your call. If you agree, set `eula=true` in `~/backmod-work/server/eula.txt`, run `java -jar fabric-server-launch.jar nogui < /dev/null` there, and read `logs/latest.log` and `crash-reports/`. Then the "3 mods load" milestone can be recorded. Mod Menu is client-only and needs a real client.
2. Rebuild and re-run all five mods with the final code (`mvn package -q -DskipTests -Dexec.skip=true`, then the transform loop). Write the results into `docs-dev/backport/compatibility.md` (mod, version, license, verdict, notes). Not written yet.
3. Step 4: add an explicit check that no class is above class-file major 69 (Java 25, both versions need it) and fail loudly. Not implemented.
4. Step 5: confirm `CAPTURE_FAILHARD` to `CAPTURE_FAILSOFT` and `require=0` still run in backport mode. Unverified. Also check that upstream forward mixin repairs (gated on `TARGET >= X`) do not misfire on a 26.2 mod.
5. Step 6 backlog (only what test mods need): YACL `GuiAccessor` (retarget the accessor from `Gui.screen` to `Minecraft.screen`), `OptionInstance` and `SpriteIconButton` signature changes, `PlayerTeam.getColor`.
6. Fabric API: report-only comparison of `surface-26.1.tsv` and `surface-26.2.tsv` (you chose report-only). Not started.
7. Untested paths: nested jars in backport mode, `--source`, a mod that already targets 26.1.2 (should refuse or no-op), Quilt.
8. Review `docs-dev/backport/review-renames.tsv` (28 descriptor-only pairings, none applied, several visibly wrong).
9. Update the README usage section and the `Retromod` manifest title still written by `build-all.sh` and `scripts/loader_bootstrap.py` (unused here).
10. Regression test for each fixed failure, as the plan asks. Today's fixes have tests; add one per new gap you close.

## Housekeeping

- Scratch directories in `~/backmod-work`: `gamejars` (client jars, SHA-1 checked), `testmods` (5 mod jars), `diff`, `scripts` (python helpers, copies are in `upstream/scripts/backport`), `run`, `run2`, `run3`, `run4` (CLI output; the CLI creates `config/` and `retromod-input/` in its working directory), `server`, `tools` (local Maven 3.9.16). Nothing outside `~/backmod-work` was changed.
- A bulk `rm` of stale output was blocked by a safety check, so the old run directories are still there. Delete them yourself if you want the space back.
- Downloaded mods are for local testing only. Check each license before redistributing anything.
