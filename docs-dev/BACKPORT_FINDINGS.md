# Backport Findings (Step 1)

Scope: Fabric, Minecraft 26.2 mod -> 26.1.2 host, offline CLI. Checked against upstream commit `5dda8cb` (Retromod 1.4.0-snapshot.4). Everything below comes from reading the source, not from running a backport. Items marked "unverified" are inferences that Step 2 must confirm.

## Verdict

The plan holds, with six corrections. Nothing blocks milestone 1, but Step 2 and Step 5 are larger than the plan assumed.

1. The version pair is confirmed. `26.2` and `26.1.2` are real stable Fabric game versions (meta.fabricmc.net). `1.26.2` does not exist. Upstream names versions the same way.
2. Upstream's 26.1 -> 26.2 data is Java code, not tables. It lives in `shim/common/Mc26_1To26_2CoreMoves.java` as `registerX(...)` calls. It cannot be inverted by swapping columns. Inversion needs either capture of the registrations or a fresh jar diff.
3. The chain finder returns an empty list for a downgrade, silently. Without changes, `transform --target 26.1.2` on a 26.2 mod runs and emits an unchanged-looking jar that still references 26.2 names.
4. The "skip mixin whose target is gone" fallback exists, but offline it needs `--host-jar`, and no report of stripped mixins exists. The report in Step 7 is new work.
5. There is no headless launch tooling. Step 8's launch check has to be built.
6. Fabric API is a second moving part. A 26.2 mod can call Fabric API methods that the Fabric API build for 26.1.2 lacks. The plan does not cover this.

## 1. Visitor chain

Entry: `RetromodTransformer.transformClass(byte[], String)` (`core/RetromodTransformer.java:4383`). It runs `singleTransformPass` (`:5213`) in a fixpoint loop of up to 5 passes (`MAX_TRANSFORM_ITERATIONS`, `:1074`) and ships the original bytes when the result is byte-identical to a plain re-serialization.

Per pass, reading input to output:

1. `ClassReader` with `EXPAND_FRAMES`.
2. `CtorRedirectPrePass` (`:6929`): `new X(..)` and `X::new` to static factories. First pass only.
3. `ClassRemapper`: class moves, intermediary `method_*`/`field_*`, SRG, Mojang member renames, indy SAM renames.
4. `RetromodClassVisitor` (`:6629`): interface and superclass rewrites, bridges, inner-class cleanup.
5. `RetromodMethodVisitor` (`:7529`): method and field redirects, argument drops and conversions, super-constructor redirects, lambda and indy adapters.
6. `SafeClassWriter` with `COMPUTE_FRAMES`, with a fallback to `COMPUTE_MAXS` plus frame recompute, and a final fallback to the original bytes.

After the loop, `postProcess` (`:4531`) runs a long list of `Legacy*Adapter.apply` calls, each gated on a flag or a registered synthetic. Last is the reflection string remap.

The CLI wraps this (`cli/RetromodCli.java:1385-1437`): jar type renames, `MixinCompatibilityTransformer.transformMixinClass`, `transformClass`, `applyPostRemapRepairs`, constructor hooks, then refmap retargeting and `ModResourceMigrations.standard().run`.

## 2. Mapping data

| Source | Format | Direction |
|---|---|---|
| `resources/intermediary-to-mojang.tsv` | `TYPE\tINTERMEDIARY\tMOJANG`, flat ids, no owner | intermediary -> 26.x names |
| `resources/mojang-class-moves-26.1.tsv` | `OLD\tNEW` internal names, 983 rows | 1.x names -> 26.1 names |
| `resources/retromod/mojang-renames.tsv` | 11 columns with mod-version and host-version windows | Forge and NeoForge only; Fabric gets `setMojangRenames(null)` (`shim/OfflineRegistration.java:99-105`) |
| `resources/retromod/loaderapi/fabric-26x/` | Fabric API `renames.tsv` and per-line `surface-26.{1,2,3,4}.tsv` | forward, plus useful surface snapshots |
| Version shims (`shim/**`, 137 registered) | Java calling `RetromodTransformer.register*` | forward |

Registration: shims are listed in `META-INF/services/com.retromod.core.VersionShim` and loaded through `ServiceLoader`. Each has `getSourceVersion`, `getTargetVersion`, `getModLoaderType` and `registerRedirects(RetromodTransformer)`. The calls are `registerClassRedirect`, `registerMethodRedirect`, `registerMethodRename`, `registerFieldRedirect`, `registerConvertingRedirect`, constructor and super-constructor redirects, synthetic classes, and `limitRedirectToModsBefore`.

26.1 -> 26.2 coverage:

- `shim/fabric/Fabric_26_1_to_26_2.java` (service file line 41) delegates to `shim/common/Mc26_1To26_2CoreMoves.java`, 821 lines.
- It holds 197 `registerClassRedirect` calls, 5 method redirects, 5 field redirects, 3 static-field accessors, and about 18 bridges and synthetics.
- The class list came from `scripts/harvest-mc-diff.py` on the 26.1.2 and 26.2-rc-1 jars, hand-checked (`:77-79`).
- Many entries are redesigns, not renames: per-colour `Blocks`/`Items` constants folded into `ColorCollection`, tag constants into `BlockItemTags`, `Gui` -> `Hud` member hops, widened JOML parameter types, removed classes rebuilt as stand-ins (`MultiBufferSource`, `Tesselator`, `VertexFormat$Mode`), and `EntityPredicate$LocationWrapper`, which upstream says has no successor.
- No shim names 26.1.1 or 26.1.2. `ShimRegistry` aliases `26.1.0`, `26.1.1`, `26.1.2` to `26.1`, and `26.2.0` to `26.2.3` to `26.2` (`shim/ShimRegistry.java:82-100`). The class-move table notes the 26.1, 26.1.1 and 26.1.2 jars agree.

`scripts/harvest-mc-diff.py` takes `<old.jar> <new.jar> <outdir>` and writes `moves.txt`, `renames.txt` (fingerprint matches, marked "VERIFY before use"), `removed.txt` and `added.txt`. Its code does not care about order, so passing 26.2 first is a candidate for the reverse diff (unverified).

## 3. Namespace

Fabric mods built for 26.1.x and 26.2 use official (Mojang) names. Intermediary is gone from 26.1 on. So a 26.2 -> 26.1.2 backport stays inside one namespace, and the intermediary -> Mojang pass has nothing to remap.

Gates key on the host, not the mod: `RetromodVersion.isUnobfuscatedTarget(host)` is true for major >= 26 (`core/RetromodVersion.java:43-52`), and also for a null or unparseable version. For target 26.1.2:

- The intermediary remap is registered and does nothing, since the mod has no `class_N` ids.
- `edgePasses` (`shim/OfflineRegistration.java:148-168`) registers the forward 1.21.11 -> 26.1 class moves and skips 26.1 -> 26.2 and 26.2 -> 26.3. The 1.21.11 -> 26.1 keys are pre-26 names, so they do not touch a 26.2 mod. A scan found no collision between the 26.1 table keys and the 26.2 move targets.
- Result today: the mod keeps its 26.2 names (`advancements/triggers/*`, `EntityTypes`, `Hud`, `BlockItemTags`) and fails on 26.1.2 with `NoClassDefFoundError`.

## 4. Direction-agnostic and hard-wired parts

Direction-agnostic:

- ASM plumbing: reader, remapper, class and method visitors, `SafeClassWriter`, frame helpers, the fixpoint loop.
- Jar IO: `transformJar` (`cli/RetromodCli.java:1206`), zip limits, atomic replace, `JarSignatureSanitizer`.
- Nested jars: `transformNestedJar`, `MAX_JIJ_DEPTH = 4` (`:1882`, `:2103`).
- `--mc-jar` indexing and `configureTargetFromMinecraftJar` (it checks milestone equality, not order).
- `AccessWidenerRemapper.remapToOfficial` and refmap handling are inert on already-official content.

Hard-wired old -> new:

| Where | Guard |
|---|---|
| `shim/ShimRegistry.findShimChain` (`:173-229`) | Edges follow `getTargetVersion()`. From 26.2 the only edge leads to 26.3. Downgrade returns `emptyList()` with no warning. |
| `ShimRegistry.isAvailableOnHost` (`:322-331`) | Drops any shim whose target exceeds the host. Reverse shims need a different gate. |
| `embedder/ModVersionInfo.needsTransformation` (`:28-50`) | True only if the mod's version is below the host. `batch` uses it (`RetromodCli.java:872`). `transform` does not. |
| `shim/OfflineRegistration.edgePasses` (`:148-168`) | Forward passes only. |
| `core/ModVersionDetector.parseFabricVersionRange` (`:264-282`) | Source version is the lower bound of the declared range. A 26.2 mod declaring `>=26.1` reads as 26.1. |
| `limitRedirectToModsBefore`, `skipsCurrentMod` (`core/RetromodTransformer.java:1250-1375`) | Redirects limited to "mods built before X" skip a 26.2 mod. Reverse registrations must set their own window or none. |
| Mixin repairs (`mixin/*`) | Gates read `compareMcVersions(TARGET, X) >= 0`, assuming the host gained the change. Mostly irrelevant when the host is older. |
| `resources/*` migrators | All gated `atLeast(target, X)`. Pack transformers throw on a newer format (`DataPackTransformer.java:82-93`, `ResourcePackTransformer.java:83-97`). |
| `RetromodTransformer.MAX_TRANSFORM_ITERATIONS` | `registerClassRedirect` rejects only `A -> A`. An inverse pair would cycle until the cap of 5 and warn. `ROADMAP.md:136-139` says so. |

## 5. Mixins

Orchestrator: `mixin/MixinCompatibilityTransformer.java` (2,395 lines). Around it: `AutomaticMixinTranslator` and `MixinHostDriftRepair` (index-proven signature repair), `MixinHandlerResignature` (curated parameter insertions), `MixinRefmapTargetRepair`, `MixinLinkCheck` (reports the first class or member a mixin would load that the host lacks), `MixinTargetKindRepair`, `MixinBlocklist` (13 bundled rules, user file `config/retromod/mixin-blocklist.json`), and a set of 26.x-specific adapters. `MixinTargetRedirector` is dead code.

Facts that matter for reuse:

- `--mc-jar` builds a `FuzzyMethodResolver` index (`RetromodCli.java:359-374`). Any jar works, so the older game jar is a valid index. The index-proven repairs only handle host drift where the host gained parameters. A host that is older usually lost them. Reverse drift is unhandled.
- The "target class gone" check (`mixinTargetsRemovedClass`, `:554-665`) bails out unless `Blocks` is visible to `hostClassExists`. That probe reads the classpath and `--host-jar` jars, not `--mc-jar`. Offline, a Fabric run with only `--mc-jar` never fires it. `applyHostJars` (`RetromodCli.java:3309-3321`) prepends `--mc-jar` to host jars, so passing `--host-jar` works. Unverified in a run. Step 5 should make `--mc-jar` sufficient.
- `neutralizeMixin` (`:484-512`) rewrites targets to `retromod/stripped/<Name>`. The config entry stays and Mixin finds nothing to apply. Config-level stripping (`transformMixinConfig`, `:1724`) is called from `FabricModTransformer` and `AotCompiler`, not from `RetromodCli`, and decides "broken" from static `KNOWN_REMOVED_*` sets, not from the jar.
- `CAPTURE_FAILHARD` -> `CAPTURE_FAILSOFT` (`:1036-1053`), `require=0` on injectors, and non-fatal configs (`required=false`, `defaultRequire=0`) already exist. `RetromodCli.makeMixinConfigNonFatal` (`:1690`).
- No collection or file records which mixins were stripped. Messages are `debug` level except one warning at `:1806`. `--verify` does not check mixin targets (`core/TransformVerifier.java:46-47`).

## 6. Fabric metadata

Runtime path (`core/FabricModTransformer.updateFabricModJson`, `:1389-1431`): pins `minecraft` to the exact target string, sets `fabricloader` to `>=0.14.0`, relaxes `fabric-api` to `*`, moves all other dependencies except `minecraft`, `fabricloader`, `java` and `fabric-language-*` to `suggests`, strips `breaks` and `conflicts`, rewrites `java` to `>=<lowest>`, drops bundled `fabric-*` jars and mismatched access wideners, and stamps `custom.retromod_transformed`.

Offline CLI path (`RetromodCli.relaxFabricModDependencies`, `:1656-1685`) is thinner. It sets `minecraft` to `"*"` (not the target), relaxes `fabricloader` and `fabric-*`, and leaves other dependencies, `breaks` and `conflicts` alone.

For backport, pin `minecraft` to the older target instead of `"*"`. A `"*"` jar would also load on 26.2, where the original works natively and the backported copy would carry inverse renames that 26.2 does not have. The "java" floor needs checking against the 26.1.2 requirement (Step 4). Fabric API remains a separate concern (see below).

Not found: any handling of `compatibilityLevel`, `minVersion` or a mixin `plugin`, and any handling of a refmap that is already `official`.

## 7. CLI wiring

`transformCommand` (`RetromodCli.java:588`): parse options, `configureTargetFromMinecraftJar`, `detectVersion`, `initializeMixinTargetIndex`, set source version, choose a chain (`findTranslationShims`, `:638`), `registerForTarget` (`:690` -> `OfflineRegistration.register`, `shim/OfflineRegistration.java:50`), early exit if nothing applies (`:652-656`), `transformJar`, then embed, `ModuleInfoRepair`, and `--verify`. It never writes a backup. It writes `<name>-transformed.jar` beside the input through a temp file and leaves the input alone.

Where `--direction backport` plugs in:

1. `main` (`:98-108`): parse and strip the flag, next to `--target`.
2. `transformCommand`: chain selection (`:631-644`), `registerForTarget`, the early-exit test (`:652-656`).
3. `ShimRegistry`: a backport lookup, or a direction flag on `VersionShim`.
4. `OfflineRegistration.register` and `edgePasses`: register the reverse set, skip forward passes.
5. `ShimRegistry.isAvailableOnHost` and `findApiShimsForLoader`: reverse-aware gates.
6. `ModVersionInfo.needsTransformation` and `batchCommand`.
7. `printUsage` (`:3331`).

`transformJar` and `transformNestedJar` need no change.

## 8. Tests

- JUnit 5, about 1,281 test files and 5,600 test methods. CI runs `mvn package -q -DskipTests` then `mvn test` on JDK 25.
- Pattern for a transform regression: build a `ClassNode`, call the package-private method, or use `loaderapi/CliHarness.transform(input, output, target, flags...)` with `LoaderApiFixtures.fabricJar()`. `HostVersionIsolation` isolates `TARGET_MC_VERSION`. `clearRedirectsForTesting()` resets the transformer.
- `--verify` checks bytecode references against the `--mc-jar` index and writes reports to `config/retromod/verify-reports/`. It is the best offline signal we have, and it is the check to run against the 26.1.2 jar.
- `test-mods/` holds Gradle projects with in-game `TestNN` cases, run by hand.
- No headless launch script exists. `AGENTS.md` mentions `./run.sh nogui` as a tip only.

## Authenticity and branding

`security/SignatureVerifier.java`: `EXPECTED_SELF_HASH` is embedded in this checkout. Any code change gives `MODIFIED` (a warning plus a fork notice that points at Bownlux/Retromod). A manifest `Implementation-Title` other than "Retromod" gives `IMPOSTOR` (`:50`, `:87-95`). An empty hash gives `UNKNOWN`. No status disables features. The Fabric entry point calls it (`core/Retromod.java:131`). The CLI does not, except through `AotCacheStamp`.

Files that must change together for a rename: `SignatureVerifier` (`EXPECTED_IMPL_TITLE`, `FORK_NOTICE_TEMPLATE`, `HASH_DOMAIN`), `pom.xml` name (feeds `Implementation-Title`), `build-all.sh:894`, `build-all.bat:446`, `scripts/loader_bootstrap.py:229,304`, `scripts/compute-self-hash.py`, `inplace/DiscoveryEngine.java:375`, `docs/authenticity.md`. `build-all.sh --require-self-hash` fails on an empty hash, so a fork build needs its own hash or the flag dropped.

Branding surface: the `com.retromod` package is in 1,162 of 1,197 main files and 1,274 of 1,281 test files. The name appears about 6,200 times in main Java, plus 96 `Retromod-*` manifest attributes, 38 `retromod.*` system properties, `retromod-input/`, `retromod-cache/`, `config/retromod/`, `fabric.mod.json`, resource paths, and generated class namespaces such as `com/retromod/generated/`.

## Proposed approach (for Steps 2 to 3)

- Build a new `backport` package with its own shim set registered only in backport mode, so forward behavior stays byte-identical to upstream.
- Derive the reverse data from a real jar diff of 26.1.2 and 26.2, using `harvest-mc-diff.py` as the starting point. Use inversion of `Mc26_1To26_2CoreMoves` only as a cross-check, and only for entries that are 1:1 and injective. Capture those entries by running the shim against a recording transformer (unverified: whether the redirect maps can be read back).
- Report everything that is not 1:1 (colour and tag folds, Gui -> Hud hops, removed classes, widened types, `LocationWrapper`) in the non-invertible report. Do not guess.
- Keep the reverse registrations in their own pass, with a guard so they cannot run alongside the forward `Mc26_1To26_2CoreMoves`.
- Source version in backport mode should come from a `--source` flag, with detection as a fallback, because declared floors lag real builds.

## Open questions for the owner

1. Rename depth. Renaming the Java package touches about 2,400 files and makes merging upstream fixes hard. The user-visible identity (artifact name, CLI banner, `Implementation-Title`, verifier, fork notice, README) is small and is what the authenticity policy cares about. Recommended: rename the visible identity now, keep `com.retromod` internally, and revisit. Caveat: generated classes `com/retromod/generated/` are embedded into output mods.
2. Fabric API scope. Should milestone 1 diff the Fabric API surfaces for 26.1.2 and 26.2 (`surface-26.1.tsv`, `surface-26.2.tsv` already exist) and report calls with no equivalent? Recommended: yes, report only, no shims.
3. Where the 26.2 and 26.1.2 game jars come from. They need to be downloaded from Mojang's version manifest into the work directory. The user's `.minecraft` is not touched.
