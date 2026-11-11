# Fantom Lang IntelliJ plugin: state report

Date: 2026-11-11. Checked by building, verifying and loading the plugin on this Mac.

See [tasks.md](tasks.md) for the work list and [tree-sitter.md](tree-sitter.md) for the tree-sitter study.

## 1. Result in one line

Plugin 1.8.0 builds, passes the JetBrains Plugin Verifier, and loads in the newest IntelliJ (2026.3, build 263.7307.200). The report could not reproduce "not working with latest".

## 2. What was checked

| Check | Result |
|---|---|
| `./gradlew buildPlugin verifyPlugin` | BUILD SUCCESSFUL in 9m 42s |
| Verifier against IU-263.3889.65 (the `platformVersion` in `gradle.properties`) | Compatible |
| Verifier against IU-263.7307.200 (`recommended()`, the newest release) | Compatible |
| Installed IntelliJ IDEA on this Mac | IU-263.7307.200 (2026.3) |
| Plugin in that IDE (`~/Library/Application Support/JetBrains/IntelliJIdea2026.3/plugins/fantom-lang`) | 1.8.0, listed in "Loaded custom plugins" at startup |
| Open `src/main/resources/demo/JsonConverter.fan` in that IDE | opens, highlights, no errors reported |
| `idea.log` for 2026.3 | no exception that names `co.anbora` or a Fantom class |
| Marketplace page for plugin 20289 | 1.8.0 (2026-09-06) for 2026.3; 1.7.0 for 2026.2; 1.6.0 for 2025.3 to 2026.1 |
| Newer platform than 263 | none found; no 2027.1 (271) EAP is public yet |
| GitHub Actions on `master` | last two runs passed (2026-05-17, 2026-09-06) |

## 3. What "not working with latest" may mean

The evidence points to one of these. Each needs a word from you.

1. **Older IDE on the same Mac.** WebStorm here is build 253 (2025.3) and PhpStorm is 243 (2024.3). Plugin 1.8.0 needs build 263. Those IDEs get 1.6.0 or nothing from the Marketplace. If this is the case, the fix is a wider `sinceBuild` or a back-port build.
2. **A specific file that highlights badly.** The lexer and parser are hand-written and cover only part of Fantom. A `.fan` file with DSL strings, nested comments or newer syntax may show red. That is a grammar gap, not an SDK break. Send the file.
3. **A missing feature.** The plugin does syntax colours only. No completion, no go-to, no structure view. GitHub issue #12 ("Full support fantom lang intellij") has been open since 2023.

## 4. Findings from the API change lists (2026.3)

Source: https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html and https://plugins.jetbrains.com/docs/intellij/api-notable-list-2026.html

| Finding | Impact | Where |
|---|---|---|
| `com.intellij.colorSettingsPage` is deprecated since 2026.3. New point is `com.intellij.colorSettings` with `displayName`. Old one still works. | Warning today. Will break in a later release. | `src/main/resources/META-INF/plugin.xml:15` |
| JNA is no longer on the default class path; OkHttp is unbundled. | None. The plugin uses neither. | — |
| Several modules moved to `bundledModule(...)` or `bundledPlugin(...)`. | None. The plugin depends only on `com.intellij.modules.platform` and `com.intellij.modules.lang`. | `plugin.xml:6-7` |
| IDE runs on Java 25 with `--enable-native-access=ALL-UNNAMED`. FFM (`java.lang.foreign`) is final. | Enables a native tree-sitter binding later. See tree-sitter.md. | — |

No API that this plugin imports appears in the incompatible list for 2026.1, 2026.2 or 2026.3.

## 5. Build and repo hygiene findings

| Finding | Where |
|---|---|
| IntelliJ Platform Gradle Plugin is 2.18.1; 2.20.1 is current. Gradle prints a warning. | `build.gradle.kts:9-10` |
| `platformVersion` 263.3889.65 is an early 2026.3 build. Current is 263.7307.200. | `gradle.properties:15` |
| CI step is named "Set up JDK 25" but installs Java 21. The Kotlin toolchain then downloads a JDK 25. It works but is slow and misleading. | `.github/workflows/gradle.yml:10-14` |
| `generateLexer` writes to a doubled path (`src/main/gen/co/anbora/labs/fantom/lang/co/anbora/labs/fantom/lang/FanLexer.java`) and warns "Cannot purge old lexer files because `pathToClass` is not set". | `build.gradle.kts:112-117` |
| `change-notes.html` has dates out of order (1.5.3 on 2025-05-16 after 1.5.2 on 2025-08-24). | `src/main/html/change-notes.html` |
| `docs/` was empty. README has no link to docs. | `docs/`, `README.md` |
| `until-build` is `263.*`. Every new IDE major needs a new release. JetBrains allows an open `until-build`. | `gradle.properties:12` |

## 6. Open source projects found (Kotlin and Java only)

Found with Firecrawl. None adopted yet.

| Project | Why it fits |
|---|---|
| [hcengineering/huly-code-treesitter](https://github.com/hcengineering/huly-code-treesitter) | Kotlin JetBrains plugin that highlights with tree-sitter grammars (MIT). Closest example of tree-sitter inside IntelliJ. Targets 2023.3; would need porting. |
| [tree-sitter/java-tree-sitter](https://github.com/tree-sitter/java-tree-sitter) (`io.github.tree-sitter:jtreesitter` 0.27.0) | Official Java binding over FFM. Needs JDK 23+. IDE 2026.3 runs Java 25 with native access on. |
| [tree-sitter/kotlin-tree-sitter](https://github.com/tree-sitter/kotlin-tree-sitter) (ktreesitter) | Official Kotlin binding, JDK 17+, with a Gradle plugin that generates language bindings. |
| [agentsmd/agents.md](https://github.com/agentsmd/agents.md) | The AGENTS.md convention used for the new `AGENTS.md` in this repo. |

## 7. Recommendation

1. Confirm which IDE and which file fails (section 3). Until then there is no SDK bug to fix.
2. Do the small upkeep in [tasks.md](tasks.md) Phase 1: new colour settings point, Gradle plugin 2.20.1, current `platformVersion`, CI Java line.
3. Keep the Grammar-Kit lexer and parser. Fix grammar gaps found by real files.
4. Treat tree-sitter as a Phase 3 spike, not a replacement. See [tree-sitter.md](tree-sitter.md).
