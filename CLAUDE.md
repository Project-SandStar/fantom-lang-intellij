# CLAUDE.md

Read and follow **[AGENTS.md](AGENTS.md)**. It applies to Claude Code and to
every subagent working in this repository.

The most important rule: **use the context-mode tools for searching and for
reading large files, logs and Gradle output**, not raw `grep`, `cat`, `find`
or long `tail`. See the table in AGENTS.md.

If context-mode is not available, search with ripgrep (`rg`), never
`grep`, and run shell commands through `rtk` for token savings.

Do not search `build/`, `.gradle/`, `.intellijPlatform/` or `src/main/gen/`.

Before you change platform or API code, run the `intellij-api-check` agent
(`.claude/agents/intellij-api-check.md`). It reads the JetBrains change lists
and the local verifier report. Treat `./gradlew verifyPlugin` as the final word.

Never run `publishPlugin` or `signPlugin` by hand, and never push a `v*` tag
without the user's word. A `v*` tag publishes the plugin to the JetBrains
Marketplace through CI; pushes to `master` only build and verify.

## Two remotes, two audiences

- `origin`: the public GitHub repo `Project-SandStar/fantom-lang-intellij`.
  Code, tests, `docs/*.md` (user documentation) and `README.md` go there.
- `internal`: the private GitLab repo
  `gitlab.bas.co/openbassg/skyspark/fantom-lang-intellij`. Everything goes
  there, including `docs/engineering/` and `marketing/`.

`docs/engineering/` and `marketing/` are in `.gitignore` for the public repo.
Never add them to a commit that will be pushed to `origin`. They are committed
on the `internal` remote only; see AGENTS.md for the commands.

## Where things are

| Path | What | Public |
|---|---|---|
| `docs/getting-started.md`, `features.md`, `settings.md`, `troubleshooting.md`, `roadmap.md`, `contributing.md` | user documentation | yes |
| `docs/engineering/language-layers.md` | resume document for the language work; read it first | no |
| `docs/engineering/tasks.md` | work list, layer by layer | no |
| `docs/engineering/grammar-work.md`, `tree-sitter.md`, `report.md`, `formatter-work.md` | design history | no |
| `marketing/intellij/` | Marketplace screenshots | no |
| `docs/video/` | video script and shot list | no |

Local Fantom installs on this machine: `~/fantom/fantom-1.0.78` to `1.0.84`
(sources), `~/haxall/haxall-4.0.4` to `4.0.7` (sources, no `sys`),
`~/skyspark/skyspark-3.0.16` to `3.0.29` (pods only, no sources). The corpus
tests read `~/fantom`.
