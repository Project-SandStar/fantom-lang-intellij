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
**Status:** Not Started

`com.intellij.colorSettingsPage` is deprecated since 2026.3. In `src/main/resources/META-INF/plugin.xml:15` register the same class with `<colorSettings implementation="co.anbora.labs.fantom.ide.color.FantomColorSettingPage" displayName="Fantom"/>`. Since `sinceBuild` is 263, the old entry can go. If Task 0.1 asks for older IDE support, keep both entries (JetBrains docs say 2026.3+ ignores the old one and older IDEs ignore the new one).

### Task 1.2: Gradle plugin and platform version
**Priority:** P2
**Status:** Not Started

- `build.gradle.kts:9-10`: `org.jetbrains.intellij.platform` and `...grammarkit` 2.18.1 to 2.20.1.
- `gradle.properties:15`: `platformVersion` 263.3889.65 to 263.7307.200.
- Run `./gradlew buildPlugin verifyPlugin`. Both IDE rows must say Compatible.

### Task 1.3: CI Java line
**Priority:** P3
**Status:** Not Started

`.github/workflows/gradle.yml:10-14`: step says "Set up JDK 25" but installs `21`. Set `java-version: '25'` and `distribution: 'temurin'`. Keep the rest.

### Task 1.4: Lexer output path
**Priority:** P3
**Status:** Not Started

`build.gradle.kts:112-117`: `generateLexer` writes to `src/main/gen/co/anbora/labs/fantom/lang/co/anbora/labs/fantom/lang/FanLexer.java` and warns that `pathToClass` is not set. Set `targetRootOutputDir` to `src/main/gen` and `targetClass` to `co.anbora.labs.fantom.lang.FanLexer`, or whatever the 2.20 task API expects. Check the generated path and that `purgeOldFiles` works.

### Task 1.5: Change notes and README
**Priority:** P3
**Status:** Partly done (2026-11-11: docs links added to README)

- Fix the out-of-order dates in `src/main/html/change-notes.html` (1.5.2 and 1.5.3).
- Add a 1.8.1 line when Phase 1 ships.

### Task 1.6: Open `until-build`
**Priority:** P2
**Status:** Not Started

Decide whether to drop `pluginUntilBuild` (`gradle.properties:12`) so each new IDE major does not need a new release. Risk: a real API break then shows as a runtime error instead of "not compatible". The verifier `recommended()` run in CI lowers that risk. Add `verifyPlugin` to the workflow before `publishPlugin` if we go this way.

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

## Phase 3: Features (after Phase 2)

### Task 3.1: Structure view
**Priority:** P2
**Status:** Not Started

`StructureViewBuilder` over the PSI: types, slots, fields. Needs stable `TypeDef`/`SlotDef` PSI from Phase 2.

### Task 3.2: Go to symbol and find usages (local)
**Priority:** P3
**Status:** Not Started

Named elements and a stub index for type and slot names. Same-pod resolve only at first.

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
