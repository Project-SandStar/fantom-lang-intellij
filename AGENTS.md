# AGENTS.md

Guidance for AI coding agents working in this repository.

## What this repo is

An IntelliJ Platform plugin that adds Fantom (`.fan`) support to JetBrains
IDEs: file type, lexer, parser, syntax colours, brace matching, folding,
comments and a "new Fantom class" action. Kotlin, Gradle, Grammar-Kit.
Published on the JetBrains Marketplace as plugin 20289
(`co.anbora.labs.fantom-lang`).

Docs live in `docs/`: [report.md](docs/report.md) (state of the plugin),
[tasks.md](docs/tasks.md) (work list), [tree-sitter.md](docs/tree-sitter.md)
(can the tree-sitter grammar replace or help the parser),
[grammar-work.md](docs/grammar-work.md) (lexer and parser rewrite: corpus,
baseline, root causes, plan; read it before touching `src/main/grammar`).
For the language intelligence work (names, index, references, library, types,
completion, tooling) read [docs/language-layers.md](docs/language-layers.md)
first; it is the resume document.

## Use context-mode for reading and searching

Gradle, the plugin verifier and the IDE sandbox write long output and large
files. Raw `grep`, `cat`, `find` and log tails flood the context window.

Use the **context-mode** tools for any search or read that may return more
than a few dozen lines. Only the result you print enters your context.

| Task | Use | Not |
|---|---|---|
| Search code or text across files | context-mode `ctx_execute` with a small script that searches and prints only the matches you need | `grep -r`, or `rg`/`find` with long output |
| Read part of a large file to analyse it | context-mode `ctx_execute` with `file` set to the path, and code that prints only what you need | `cat` of the whole file |
| Read a file you are about to edit | the Read tool, with `offset`/`limit` for the part you need | context-mode (Edit needs the exact bytes in context) |
| Gradle, verifier and `runIde` output | context-mode: run the command, print `BUILD`, `Compatible`/`Incompatible`, errors and their first lines | pasting the full Gradle log |
| IDE logs (`build/idea-sandbox/*/log/idea.log`, `~/Library/Logs/JetBrains/<IDE>/idea.log`) | context-mode: filter by `co.anbora`, `ERROR`, time; print counts and the last few matches | `tail -n 1000` |
| Verifier HTML (`build/reports/pluginVerifier/*/report.html`) | context-mode: strip tags, print lines with Compatible, Deprecated, Experimental, Internal, Missing | opening the HTML in context |
| Fetch a web page or API | Firecrawl `firecrawl_scrape` with a `query` prompt, or context-mode `context-mode-index` then `context-mode-search` | `curl` into the context |
| Text you need again later | context-mode `context-mode-index`, then `context-mode-search` | reading it twice |

Short, targeted commands are fine with Bash: `git status`, `git log --oneline -5`,
`./gradlew buildPlugin 2>&1 | tail -5`, `ls` of one folder.

### Tool order for search and shell output

1. **context-mode first** (the gateway). Use its tools for every search and
   every read that may return more than a few dozen lines, as in the table
   above.
2. **If context-mode is not available** in a session, or for a quick
   one-off command:
   - Search with **ripgrep** (`rg`), never `grep`. Examples:
     `rg -n "pattern" src/main/kotlin`, `rg -l "name" --type kotlin`.
     ripgrep skips `.gitignore`d folders by default; still add
     `-g '!build' -g '!src/main/gen'` where needed.
   - Run shell commands through **rtk** for token savings:
     `rtk git status`, `rtk git log`, `rtk ls`, `rtk find`.
     If a Claude Code hook already rewrites commands to rtk, plain commands
     are fine. Use `rtk proxy <cmd>` only to see raw output when debugging.
   - Keep output small: `head`, `tail -n 20`, `rg -c`, `wc -l`. Never print
     whole logs, generated files or build folders.

## IDE Integration

Three MCP servers talk to the running JetBrains IDE. Prefer them over shell,
grep, scripting or screenshots whenever one of them does the job. Their tools
arrive deferred: at the start of an IDE task, load the ones you need with
`ToolSearch` (`select:mcp__jetbrains-index__ide_find_references,...`).

Always use the `jetbrains-index` MCP server when applicable for:
- **Finding references** — Use `ide_find_references` instead of grep/search
- **Go to definition** — Use `ide_find_definition` for accurate navigation
- **Renaming symbols** — Use `ide_refactor_rename` for safe, project-wide renames
- **Type hierarchy** — Use `ide_type_hierarchy` to understand class relationships
- **Finding implementations** — Use `ide_find_implementations` for interfaces/abstract classes
- **Diagnostics** — Use `ide_diagnostics` to check for code problems
- **Call hierarchy** — Use `ide_call_hierarchy` to trace who calls what

The IDE's semantic understanding is far more accurate than text-based search.
Prefer IDE tools over grep, ripgrep, or manual file searching when working
with code symbols.

Use the `jetbrains-debugger` MCP server instead of print statements or log
digging when a bug needs runtime state: `start_debug_session` on a run
configuration, `set_breakpoint` (with a condition or a log message),
`step_over` / `step_into` / `step_out`, `evaluate_expression`, read frames and
variables, then `stop_debug_session`.

Use the built-in `idea` MCP server for the rest of the IDE:
- `get_file_problems` / `get_inspections` / `apply_quick_fix` after an edit
- `build_project` to compile and read errors instead of a Gradle run
- `get_run_configurations` / `execute_run_configuration` to run or test
- `get_symbol_info` (Quick Documentation) and `analyze_calls`
- `get_all_open_file_paths`, `get_project_modules`, `get_project_dependencies`

Limits in this repo: the servers reach the user's main IDE, not the sandbox
started by `runIde`. The sandbox runs the plugin under development; drive it
by hand or by test. Fantom files are only understood semantically once the
plugin from this branch is installed in the main IDE.

## Never search these folders

`build/`, `.gradle/`, `.intellijPlatform/`, `src/main/gen/` (generated by
Grammar-Kit from `src/main/grammar/`), `.idea/`. Search `src/main/grammar/`
instead of `src/main/gen/`.

## Repository map

- `build.gradle.kts`, `gradle.properties` — IntelliJ Platform Gradle Plugin
  2.x setup. `platformVersion`, `pluginSinceBuild`, `pluginUntilBuild` and
  `pluginVersion` live in `gradle.properties`.
- `src/main/grammar/Fantom.flex` — JFlex lexer. `Fantom.bnf` — Grammar-Kit
  parser and PSI. Both generate into `src/main/gen/` at build time.
- `src/main/kotlin/co/anbora/labs/fantom/lang/` — language, file type,
  parser definition, lexer adapter, token and element types.
- `src/main/kotlin/co/anbora/labs/fantom/ide/` — highlighter, colour page,
  annotator, folding, brace matcher, commenter, icons, new-file action.
- `src/main/resources/META-INF/plugin.xml` — extension points.
  `src/main/html/` — Marketplace description and change notes.
  `src/main/resources/demo/JsonConverter.fan` — colour page sample.
- `.github/workflows/gradle.yml` — push to `master` builds and verifies; a `v*` tag runs `publishPlugin`.
- `docs/` — report, tasks, tree-sitter study.

## Build and check

```sh
./gradlew buildPlugin          # builds build/distributions/fantom-lang-<ver>.zip
./gradlew verifyPlugin         # runs the JetBrains Plugin Verifier (slow, ~5 min)
./gradlew runIde               # starts a sandbox IDE with the plugin
```

Java 25 is required (`jvmToolchain(25)`). The platform version to test is
in `gradle.properties`. When you change `platformVersion` or `since/until`,
run `verifyPlugin` and read the `Compatible` line for each IDE.

## API changes

JetBrains publishes breaking and notable API changes per year:
- https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html
- https://plugins.jetbrains.com/docs/intellij/api-notable-list-2026.html
- https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html

Run the `intellij-api-check` agent before an SDK upgrade. It compares the
APIs this plugin uses with those pages and the verifier report.

## Working rules

- Several agents may work in this repo at once. Work on your own branch or
  in your own git worktree under `.worktrees/<name>`, never on `master`.
- `git pull --rebase` before you commit. Commit only your own files.
  Never commit `src/main/gen/` or `build/`.
- Commit messages end with the `Co-Authored-By` line given in your task.
- Never push to `master`. The workflow on `master` signs and publishes the
  plugin. Never run `publishPlugin` or `signPlugin` locally; publishing is CI on a `v*` tag.
- Never print secrets: `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`,
  `PRIVATE_KEY_PASSWORD`.
- When you bump the SDK, update `gradle.properties`, `change-notes.html`
  and `pluginVersion` together.
- Plain English, short sentences in docs and commit messages.
