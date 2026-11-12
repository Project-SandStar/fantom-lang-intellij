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

Never run `publishPlugin` or `signPlugin`, and never push to `master`.
A push to `master` publishes the plugin to the JetBrains Marketplace.

Resume document for the language work: `docs/language-layers.md`.
