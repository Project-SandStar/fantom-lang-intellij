# Implementation Tasks - Fantom Lang IntelliJ plugin

See [report.md](report.md) for the findings and [tree-sitter.md](tree-sitter.md) for the tree-sitter study.

Rules: work on a branch, never push to `master` (it publishes), run `./gradlew verifyPlugin` before a PR, bump `pluginVersion` and `change-notes.html` together.

---

## Phase 0: Confirm the problem

### Task 0.1: Open questions
**Priority:** P1
**Status:** Open (asked 2026-11-11)

1. Which IDE and build shows the problem? IntelliJ IDEA 2026.3 on this Mac loads plugin 1.8.0 with no error. WebStorm here is 2025.3 and PhpStorm is 2024.3; plugin 1.8.0 does not install on those (needs build 263).
2. What is the symptom: plugin will not install, no colours, red errors in a file, IDE exception? Send the file if it is a file.
3. Do we need a release for older IDEs (2025.3 to 2026.2) as well as 2026.3?

### Task 0.2: Reproduce
**Priority:** P1
**Status:** Not Started

Once 0.1 is answered: install the Marketplace build on the named IDE, open the named file, copy the `idea.log` lines that mention `co.anbora`. Add the result to report.md section 3.

---

## Phase 1: Upkeep for 2026.3

### Task 1.1: New colour settings extension point
**Priority:** P1
**Status:** Done (2026-11-11). `plugin.xml` now uses `<colorSettings ... displayName="Fantom"/>`. Old entry removed because sinceBuild is 263.

`com.intellij.colorSettingsPage` is deprecated since 2026.3. In `src/main/resources/META-INF/plugin.xml:15` register the same class with `<colorSettings implementation="co.anbora.labs.fantom.ide.color.FantomColorSettingPage" displayName="Fantom"/>`. Since `sinceBuild` is 263, the old entry can go. If Task 0.1 asks for older IDE support, keep both entries (JetBrains docs say 2026.3+ ignores the old one and older IDEs ignore the new one).

### Task 1.2: Gradle plugin and platform version
**Priority:** P2
**Status:** Done (2026-11-11). Both Gradle plugins 2.20.1, `platformVersion` 263.7307.200.

- `build.gradle.kts:9-10`: `org.jetbrains.intellij.platform` and `...grammarkit` 2.18.1 to 2.20.1.
- `gradle.properties:15`: `platformVersion` 263.3889.65 to 263.7307.200.
- Run `./gradlew buildPlugin verifyPlugin`. Both IDE rows must say Compatible.

### Task 1.3: CI Java line
**Priority:** P3
**Status:** Done (2026-11-11). Temurin 25. A `verifyPlugin` step now runs before `publishPlugin`.

`.github/workflows/gradle.yml:10-14`: step says "Set up JDK 25" but installs `21`. Set `java-version: '25'` and `distribution: 'temurin'`. Keep the rest.

### Task 1.4: Lexer output path
**Priority:** P3
**Status:** Done (2026-11-11). `generateLexer` now has `targetRootOutputDir = src/main/gen` and `pathToClass = co/anbora/labs/fantom/lang/FanLexer.java`, so JFlex writes the file once and `purgeOldFiles` removes only that file. The root is shared with `generateParser`, whose purge emptied the whole root and deleted the lexer on the first try; it now has `pathToParser` and `pathToPsiRoot` set so it purges only its own folders. There is no `targetClass` property in the 2.20 task API.

### Task 1.5: Change notes and README
**Priority:** P3
**Status:** Done (2026-11-11). Dates for 1.5.2 and 1.5.3 put in order, 1.8.1 line added, `pluginVersion` is 1.8.1.

### Task 1.6: Open `until-build`
**Priority:** P2
**Status:** Decided: keep `263.*` for now (2026-11-11). The verifier can only check IDE builds that exist, so an open range would be a guess. The CI step `verifyPlugin` with `recommended()` is in place; when the 2027.1 EAP (271) is public, run the verifier against it and widen the range in that release.

---

## Phase 2: Grammar quality

### Task 2.1: Build a `.fan` test corpus
**Priority:** P1
**Status:** Done (2026-11-11, branch `grammar/fantom-corpus`)

Corpus is the seven Fantom distributions in `/Users/alper/fantom` (1.0.78 to 1.0.84, 869 files in 1.0.84). Test harness: `FantomCorpusParsingTest` and `FantomSnippetDumpTest` in `src/test/kotlin`, 42 snippets in `src/test/testData/snippets`. Baseline for 1.0.84: 4,590 errors in 560 of 869 files. How to run, the baseline, the root causes and the rewrite plan are in [grammar-work.md](grammar-work.md).

### Task 2.2: Fix the top grammar gaps
**Priority:** P1
**Status:** Done (2026-11-11). Lexer and parser rewritten in three waves on branch `grammar/fantom-corpus`. All 6,035 `.fan` files across Fantom 1.0.78 to 1.0.84 parse with zero errors. Details and how to rerun in [grammar-work.md](grammar-work.md) section 6.

From the corpus, rank error causes. Known suspects from `Fantom.flex` and `Fantom.bnf`: DSL strings (`DSL_STRING` is commented out in `FantomParserDefinition.kt:43`), nested block comments, `using [java]` FFI imports, map type shorthand `Str:Int`, closures vs function types, `it`-blocks, facets with arguments. Fix one gap per PR with a corpus file that proves it.

### Task 2.3: Compare with tree-sitter-fantom
**Priority:** P2
**Status:** Not Started

Run the tree-sitter `.wasm` and the Grammar-Kit parser on the same corpus. Print error counts per file side by side. Use the tree-sitter `grammar.js` conflict list as a checklist of hard spots. Share back any Fantom syntax that tree-sitter gets right and we do not, and the other way round, to the mcpfantom repo.

---

## Phase 3: Language intelligence (after Phase 2)

Goal: completion, navigation and rename like the Java and Kotlin plugins. Built as
layers; each needs the one before it.

| Layer | What | Gives the user |
|---|---|---|
| 1 | Named elements (`PsiNameIdentifierOwner`) on every declaration | Rename in file, structure view, find usages by word |
| 2 | Stubs and a file-based index of type and slot names | Go to class, go to symbol, cross-file lookup without parsing |
| 3 | References on identifiers (local, slot on `this`, imported type, `sys`) | Ctrl+click, go to declaration, real find usages |
| 4 | Library model: a Fantom home's `src/*/fan` as a synthetic library (pod files not needed) | Navigation into `sys::Str` and friends |
| 5 | Type inference for `:=`, literals, calls, fields, params | Knows the type of `x` in `x.` |
| 6 | Completion contributor | Ctrl+Space on slots, types and keywords |
| 7 | Inspections, formatter, run configs for `fan`/`fant`, debugger | Each its own task |

### Task 3.1: Layer 1, named elements, structure view, rename
**Priority:** P1
**Status:** Done (2026-11-11). Base commit `a2229b8`: `FantomNamedElement` mixin on classDef, mixinDef, facetDef, enumDef, enumValDef, fieldDef, methodDef, ctorDef, param, localDef, closureParam, catchDef. Merged from three parallel agent branches: named-element tests (22 tests, plus a check over 20 `sys` pod files), Structure View (`ide/structure`, Cmd+7, types then enum values, fields, ctors, methods), find usages, in-place rename and names validator (`ide/usages`, `ide/refactoring`). Known limit: there are no references yet, so rename changes the declaration only; that is layer 3. A type-only closure param like `|Int|` has a null name by design.

### Task 3.2: Layer 2, index and navigation
**Priority:** P2
**Status:** Done (2026-11-11). Design choice: a `FileBasedIndex` (`lang/index/FantomDeclarationIndex`) instead of stub trees. It maps a short name to `(kind, ownerType)` per file and re-parses only matching files on lookup. This keeps the grammar free of stub plumbing and is enough for navigation and for the layer 3 resolver; stubs can come later if memory or speed demands it. Merged: Go to Class and Go to Symbol (`ide/navigation`, plain `NavigationItem` rows with a shared `ide/presentation/FantomPresentation` helper also used by the structure view), a corpus test over the real `sys` pod (79 files, 831 names, 1,528 entries, index built in about 1.5 s, cross-checked against the source with ripgrep), and `lang/library/FantomHome` which finds a Fantom install (`fantom.home` property, `FAN_HOME`, newest `/Users/alper/fantom/fantom-*`) for a later library root. 58 tests pass. Follow-ups: register `FantomHome.sourceRoots` as an `AdditionalLibraryRootsProvider` so `sys` types resolve from any project; add `getPresentation()` to `FantomNamedElementImpl` so the navigation wrapper can go.

### Task 3.2b: Layer 3, references
**Priority:** P2
**Status:** Done (2026-11-12). `lang/resolve/FantomReference` (kinds BARE, SLOT, TYPE) is attached by `core/psi/impl/FantomIdMixin` to every used identifier; `FantomScope` walks locals, params, closure params and catch params; slots resolve on the enclosing type then its supertypes through the index; types by short name. Merged from three agent branches: go to declaration (9 cases) and semantic colours for locals, params, fields, calls and types with unresolved names left neutral; find usages and rename across files with `getUseScope` limiting body names to their method or closure (14 tests, rename through a subtype works); a resolve test over the `sys` pod: 2,436 of 2,623 references resolve, all 187 unresolved are generic type params (V, K, L, M, R), 10 ambiguous slot uses await receiver typing. 97 tests pass. Known gaps: `sys` has no method bodies so locals are only tested on small inputs; `obj.run()` matches every slot named `run` until layer 5 gives receivers a type; facet values (`@Serializable { simple = true }`) get no reference yet; an `override` is not renamed with its base method.

### Task 3.2c: Layer 4, library model
**Priority:** P2
**Status:** Done (2026-11-12). `lang/library/FantomSettings` (project service, `.idea/fantom.xml`, `homePath` with fallback to `FantomHome.detect`) and `FantomLibraryRootsProvider`, an `AdditionalLibraryRootsProvider` that exposes every `src/<pod>/fan` of the home as one synthetic library "Fantom 1.0.84". Merged from three agent branches: Settings | Languages & Frameworks | Fantom to pick the home (fires a roots rescan on apply) plus a one-time balloon when a project has `.fan` files and no home; Quick Documentation (Ctrl+Q) from fandoc `**` comments and Parameter Info (Cmd+P) for calls and ctors; a resolve tally over the `util` pod with `sys` as a library (2,579 references, 99.7 percent resolve, TYPE 100 percent, BARE 99.5 percent). That tally found three resolver gaps, all fixed in the same session: a type with no written base never reached `Obj` (so `echo` failed), `this.x` did not narrow to the enclosing type, and a name declared in both project and library returned both (project now wins). 118 tests pass. Remaining limit is receiver typing: `obj.run()` still matches every slot named `run` (SLOT ambiguity 54 percent in `util`), which is layer 5.

### Task 3.2d: Layer 5, type inference
**Priority:** P2
**Status:** In Progress (2026-11-12). Base committed: `lang/types/FanType` (Named, ListOf, MapOf, Func, nullability) and `FantomTypeInference` (declaration types from the written type or the initializer; expression types for literals, resolved names, calls, ctors, casts, `as`/`is`, comparisons, ternary/elvis, list and map literals, and chains through slots, index and call; cached per element). `FantomReference` now narrows `recv.x` to the receiver's type chain and `Type.x` to that type's chain. 8 tests. Agents in parallel: `layer5/closures` (`it` in it-blocks, untyped closure params from the callee's func-type param, generic variable substitution V/K/L/M/R/A..H from the receiver, closure return types), `layer5/scale` (what inference bought on `util`, which receivers still fall back, top null-type shapes), `layer5/hints` (inlay type hints for `:=` locals and closure params, inferred type in Quick Doc).

### Task 3.2e: Layer 6, completion
**Priority:** P1
**Status:** In Progress (2026-11-12). Base committed: `lang/resolve/FantomSlotLookup` (type chain and all slots, nearest first) and `ide/completion/FantomCompletionContributor`. After `.`, `?.`, `->`: slots of the receiver's inferred type; `this.`/`super.`: enclosing chain; `Type.`: that type; bare name: locals, params, enclosing slots, types, keywords; type position: types. Methods with params insert `()`. Unknown receiver offers nothing. 9 tests, one against the real `sys` library. Agents in parallel: `layer6/ranking` (order: locals, own slots, inherited, Obj, types, keywords; hide private and ctors after `recv.`; keyword positions), `layer6/scale` (hit rate and rank over 150 real `recv.slot` sites in `util`), `layer6/templates` (live templates `main if for each try sw echo doc class fn`, postfix `.if .not .each .echo .var .return .null .nn`).

### Task 3.3: Tree-sitter side parser spike
**Priority:** P3
**Status:** Not Started

Only if a feature needs it and the grammar error rate is near zero. One week box: add `io.github.tree-sitter:jtreesitter` (0.27.0, Java 23+, FFM), build `libtree-sitter` and `libtree-sitter-fantom` for macOS arm64 only, load from the plugin folder, parse the open file in a background task, print node counts in the log. Measure parse time against the Grammar-Kit parser. Decide go or no-go. See tree-sitter.md section 2.

### Task 3.4: LSP client (paid IDEs only)
**Priority:** P3
**Status:** Not Started

If mcpfantom gains an LSP front end, add an optional `LspServerSupportProvider` behind a setting. Guard it so IntelliJ IDEA Community still loads the plugin without it.

---

## Phase 4: Release

### Task 4.1: Ship 1.8.1
**Priority:** P2
**Status:** Not Started

After Phase 1: bump `pluginVersion` to 1.8.1, add the change note, PR to `master`, let the workflow publish. Check the Marketplace versions page shows 1.8.1 for 2026.3.
