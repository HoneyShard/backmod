# Backport Data (26.2 to 26.1.2, Fabric)

Everything under `src/main/resources/backmod/fabric/26.2-to-26.1.2/` comes from a diff of the two Mojang client jars, not from upstream's tables alone.

| File | Rows | Source |
|---|---|---|
| `classes.tsv` | 199 | Union of `harvest-mc-diff.py` (both directions) and upstream's `Mc26_1To26_2CoreMoves` class redirects. Each row is checked against both jars. |
| `methods.tsv` | 49 | `BackportDiffTool`. Method bodies identical, or the same rename in 3 or more classes. |
| `fields.tsv` | 11 | `BackportDiffTool`. The same rename in 3 or more classes. |

`review-renames.tsv` holds pairings that only share a descriptor. They are not applied. `non-invertible.tsv` and `non-invertible-report.md` list what a 26.2 mod can use that 26.1.2 cannot answer.

## Regenerate

Download both client jars from the URLs in Mojang's `version_manifest_v2.json` (ids `26.1.2` and `26.2`) and check each SHA-1. Put them in a directory of their own.

```bash
# class-level diff, both directions (needs python3)
python3 -I scripts/harvest-mc-diff.py client-26.1.2.jar client-26.2.jar diff/fwd
python3 -I scripts/harvest-mc-diff.py client-26.2.jar client-26.1.2.jar diff/rev
# list classes per jar, then build the verified class table
unzip -Z1 client-26.1.2.jar | grep '\.class$' | sed 's/\.class$//' | sort > diff/classes-26.1.2.txt
unzip -Z1 client-26.2.jar   | grep '\.class$' | sed 's/\.class$//' | sort > diff/classes-26.2.txt
python3 -I scripts/backport/build_class_table.py      # run from the directory that holds diff/ and upstream/
# member-level diff
java -cp target/classes:<asm, asm-tree, asm-commons 9.11.1> com.retromod.backport.BackportDiffTool \
  client-26.1.2.jar client-26.2.jar classes.tsv out/
```

`build_class_table.py` and `compare_moves.py` use paths relative to the directory that holds `diff/` and `upstream/`. Adjust them if you lay it out differently.

## Findings That Changed The Plan

- 3 of upstream's 196 literal class redirects describe moves that happened in 26.1, so inverting them blindly would emit names that do not exist in 26.1.2. The build script rejects them.
- A descriptor-only rename match is wrong often enough to be unsafe (for example `Gui.tick` paired with `setClientLevelTeardownInProgress`). Only body-identical or repeated renames are applied.
- The differ does not pair members whose name and descriptor both changed.
