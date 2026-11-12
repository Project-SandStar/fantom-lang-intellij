# Contributing

## Build

```sh
./gradlew --no-daemon generateLexer generateParser test buildPlugin
./gradlew --no-daemon runIde      # sandbox IDE with the plugin
```

Java 25 and the Gradle wrapper are all you need. The IntelliJ platform is
downloaded on the first build.

## Tests

`./gradlew test` runs the unit tests. The corpus test parses a Fantom source
tree when `FANTOM_CORPUS` points at one or more `src` folders separated by
`:`; it must report zero errors. Library-dependent tests skip when no Fantom
installation is found.

## Layout

| Path | What |
|---|---|
| `src/main/grammar` | `Fantom.flex` (lexer) and `Fantom.bnf` (parser); generated code goes to `src/main/gen` |
| `lang/core` | PSI base classes and named elements |
| `lang/index` | File-based index of declarations |
| `lang/resolve` | References, scopes, slot lookup, `using` visibility |
| `lang/library` | Fantom home detection and the synthetic library |
| `lang/types` | Type model and inference |
| `ide/*` | Everything the user sees: completion, inspections, formatter, run configurations, colours |

## Pull requests

Keep a change to one topic, add or update a test, and run the full build
before you push. Grammar changes must keep the corpus at zero errors.

## License

GPL v3. By contributing you agree your work is released under the same
license.
