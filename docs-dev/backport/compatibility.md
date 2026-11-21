# Backport Compatibility (26.2 to 26.1.2, Fabric)

Results for five Fabric mods built for Minecraft 26.2, transformed with `transform <jar> --direction backport --mc-jar client-26.1.2.jar` and checked on 2026-11-21. Verdicts come from the static report next to each output jar. They are not a promise that a mod works in game.

| Mod | Version | License | Verdict | Mixins resolved | Missing references |
|---|---|---|---|---|---|
| AppleSkin | 3.0.10+mc26.2 | Unlicense | `LIKELY_OK` | 8 of 8 | 0 |
| Cloth Config | 26.2.155 | LGPL-3.0 | `PARTIAL` | none listed | 3 |
| Mod Menu | 20.0.3 | MIT | `LIKELY_BROKEN` | 1 of 2 | 1 |
| Placeholder API | 3.1.0-beta.1+26.2 | LGPL-3.0 | `PARTIAL` | none listed | 2 |
| YACL | 3.9.7+26.2-fabric | LGPL-3.0-or-later | `PARTIAL` | 7 of 8 | 3 |

## Notes

- AppleSkin: `HudMixin` is retargeted from `Hud` to `Gui`. Every game class, method, and field it uses exists in 26.1.2.
- Cloth Config: the missing references (`Items.WOOL`, `ColorCollection`, `TextColor.DARK_GRAY`) are in `ClothConfigDemo` and `TextListEntry`, so they fail only if that code runs.
- Mod Menu: client-only. The transform now removes its five bundled `fabric-*` modules, retargets `OptionInstance$ValueUpdateListener` to `Consumer`, and bridges `SpriteIconButton$Builder.tooltip`. Two gaps remain. `MixinTitleScreen` shadows `TitleScreen.getHorizontalPosition(III)I`, which 26.1.2 does not have, so that mixin does not apply and the title screen gets no Mods button. The icon button style builds a `SpriteIconButton$CenteredIcon` with 26.2's three extra constructor arguments, so choosing the `ICON` game menu style fails when the pause screen opens. The verdict stays `LIKELY_BROKEN` because one of its two mixins cannot apply. The classic game menu button and the Mods screen are untested on a client.
- Placeholder API: `PlayerTeam.getColor()` and `TeamColor` do not exist in 26.1.2. Only the team color placeholder is affected.
- YACL: `GuiAccessor` wants `Gui.screen`, which 26.1.2 keeps on `Minecraft`. `MenuTabBar` and `LevelExtractor` are missing. The refmap warning in the server log is expected, because the original 26.2 jar has no refmap either.

## Launch Results

Headless Fabric Loader 0.20.1 server, Minecraft 26.1.2, Fabric API 0.155.3, Java 25, all five backported jars in `mods/`. The server reached `Done` with no errors and no crash report on two runs. A dedicated server skips client mixins, so this shows only that the jars are discovered and their metadata is accepted.

Prism client instance, same game, loader, and Fabric API, 2026-11-21:

- With the first Mod Menu backport, joining a world fails with a `NullPointerException` in `FabricOriginalKnownPacksGetter`. Mod Menu 20.0.3 bundles 26.2 builds of `fabric-resource-loader-v1` (2.0.13) and `fabric-api-base` (2.0.4). Fabric Loader keeps the higher version, so they replaced the 26.1.2 builds inside Fabric API, and their mixins do not match 26.1.2. The transform now removes bundled Fabric API modules and requires them from the installed Fabric API. The server log shows the 26.1.2 builds (2.0.3 and 2.0.10) loading with the new jar present, but a dedicated server does not load Mod Menu, so the fix is not yet confirmed on a client.
- Without Mod Menu, the client reaches the title screen, joins a world twice, and exits normally, with no crash report. AppleSkin shows its overlay in game. The only mixin failure is YACL `GuiAccessor` (`Gui.screen`), as the report predicted.
- YACL, Cloth Config, and Placeholder API load, but no installed mod uses them, so their behavior is untested.

## Limits

Every row is a static check against the 26.1.2 client jar plus the launches above. Only AppleSkin has been seen working in game. Mod Menu is untested apart from the failure above. Check each license before redistributing a backported jar.
