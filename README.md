# Backmod

Backmod is an experimental tool that rewrites a Minecraft mod built for a newer version so it can load on an older one. It is an unofficial fork of [Retromod](https://github.com/Bownlux/Retromod) by Bownlux, which does the opposite (old mod, new game). It is not published, endorsed, or supported by the Retromod authors.

## Status

Work in progress. Nothing is backported yet. The first milestone covers one case only:

- Direction: newer to older.
- Versions: Minecraft 26.2 mods on 26.1.2.
- Loader: Fabric.
- Interface: a Fabric mod jar that backports at launch, or an offline CLI, `in.jar` to `out.jar`, plus a report.

Mods with their own renderer, heavy mixins, native code, or shaders are out of scope. Quilt, Forge, NeoForge, and other version pairs come later, if at all. Read [docs-dev/BACKPORT_FINDINGS.md](docs-dev/BACKPORT_FINDINGS.md) for what the upstream code can and cannot do in this direction.

## Build

Backmod needs JDK 25 and Maven. The build targets Java 17 bytecode.

```bash
mvn package -q -DskipTests -Dexec.skip=true
java -jar target/backmod-1.4.0-snapshot.4-all.jar --help
```

Always pass `-Dexec.skip=true`, or Maven runs the CLI entry point.

## Use It As A Mod

Put `target/backmod-1.4.0-snapshot.4.jar` in `mods/` on a 26.1.2 instance with Fabric Loader 0.16.14 or newer. Do not use the `-all` jar, which bundles ASM and conflicts with the loader's copy.

Put the 26.2 mod jars in `<game dir>/mods-26.2/`. Backmod creates the folder on its first launch. At the next start it writes the backported copies into `mods/` and logs `Restart Minecraft to load them`. Fabric has already scanned `mods/` by then, so the converted mods load on the launch after that.

Later launches only rebuild a jar whose contents changed, and they remove a backported jar when its source leaves `mods-26.2/`. A jar that declares a Minecraft version older than 26.2 is refused and left alone. A jar already in `mods/` under the same name is never overwritten. Reports and the sync index go to `<game dir>/backmod/`.

Backmod does nothing on any host except 26.1.2, because the tables were checked against that version only. On a dedicated server the reports cannot check client classes, because the server jar has none.

## Use It From The Command Line

The CLI does the same work without launching the game. It writes to a folder you choose, which Fabric Loader reads through one Java argument.

```bash
java -jar target/backmod-1.4.0-snapshot.4-all.jar sync --mc-jar <26.1.2 client.jar> \
  --from <game dir>/mods-26.2 --to <game dir>/mods-backported
```

Add `-Dfabric.addMods=mods-backported` to the instance's Java arguments. The path is relative to the game directory. In Prism Launcher, open **Edit > Settings > Java > Java arguments** and tick the override box.

`sync` skips a jar whose contents have not changed since the last run, and it removes a backported jar when its source leaves `mods-26.2/`. Pass `--force` to rebuild everything. Reports and the sync index go to `mods-backported-info/`, because Fabric Loader warns about non-jar files in a mod folder.

## What Changed From Upstream

- The project name, CLI banner, usage text, and jar manifest title say Backmod.
- The self-hash is empty and the authenticity check expects the title `Backmod`, so builds report an unknown hash instead of being labeled as a modified Retromod release.
- The Java package is still `com.retromod`, and folders such as `retromod-input/` keep their names. This keeps upstream fixes mergeable. Treat those names as internal.
- The Forge and NeoForge bootstrap scripts (`scripts/loader_bootstrap.py`, `build-all.sh`) still write the title `Retromod`. They are unused here and not updated.
- The original README is kept at [docs-dev/UPSTREAM_README.md](docs-dev/UPSTREAM_README.md).

## Credits and License

Backmod builds on Retromod, Copyright (c) 2026 Bownlux, under the MIT License. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The upstream docs live at https://bownlux.github.io/Retromod/.
