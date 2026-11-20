# Backmod

Backmod is an experimental tool that rewrites a Minecraft mod built for a newer version so it can load on an older one. It is an unofficial fork of [Retromod](https://github.com/Bownlux/Retromod) by Bownlux, which does the opposite (old mod, new game). It is not published, endorsed, or supported by the Retromod authors.

## Status

Work in progress. Nothing is backported yet. The first milestone covers one case only:

- Direction: newer to older.
- Versions: Minecraft 26.2 mods on 26.1.2.
- Loader: Fabric.
- Interface: an offline CLI, `in.jar` to `out.jar`, plus a report.

Mods with their own renderer, heavy mixins, native code, or shaders are out of scope. Quilt, Forge, NeoForge, and other version pairs come later, if at all. Read [docs-dev/BACKPORT_FINDINGS.md](docs-dev/BACKPORT_FINDINGS.md) for what the upstream code can and cannot do in this direction.

## Build

Backmod needs JDK 25 and Maven. The build targets Java 17 bytecode.

```bash
mvn package -q -DskipTests -Dexec.skip=true
java -jar target/backmod-1.4.0-snapshot.4-all.jar --help
```

Always pass `-Dexec.skip=true`, or Maven runs the CLI entry point.

## What Changed From Upstream

- The project name, CLI banner, usage text, and jar manifest title say Backmod.
- The self-hash is empty and the authenticity check expects the title `Backmod`, so builds report an unknown hash instead of being labeled as a modified Retromod release.
- The Java package is still `com.retromod`, and folders such as `retromod-input/` keep their names. This keeps upstream fixes mergeable. Treat those names as internal.
- The Forge and NeoForge bootstrap scripts (`scripts/loader_bootstrap.py`, `build-all.sh`) still write the title `Retromod`. They are unused here and not updated.
- The original README is kept at [docs-dev/UPSTREAM_README.md](docs-dev/UPSTREAM_README.md).

## Credits and License

Backmod builds on Retromod, Copyright (c) 2026 Bownlux, under the MIT License. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The upstream docs live at https://bownlux.github.io/Retromod/.
