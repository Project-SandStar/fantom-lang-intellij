# Formatter work (layer7/formatter)

Resume note for the Fantom formatting model task. Facts only.

## Goal

Reformat Code and auto-indent for `.fan` files. Fantom house style
(sys/util pods): two-space indent, Allman braces for types, slots and
control statements, `{` on the same line for closures and it-blocks, one
space around binary operators and `:=`/`=`, no space before `(` in calls,
`, ` in args, `case x:` at body indent, blank lines kept up to 1 between
slots (2 at file level), comments keep their column. Keep runs of more than
one space inside a line (column-aligned initializers in sys).

Gate: reformatting every `sys/fan/*.fan` and `util/fan/*.fan` must change
nothing (0 differing files), or a short list of justified exceptions.

## State

- Branch `layer7/formatter` from `grammar/fantom-corpus`.
- Generated sources present (`./gradlew --no-daemon generateLexer generateParser` done).
- Nothing implemented yet. Only this note is committed.

## Plan (files)

- `src/main/kotlin/co/anbora/labs/fantom/ide/formatter/FantomFormattingModelBuilder.kt`
  (`FormattingModelBuilder`, `SpacingBuilder`).
- `.../ide/formatter/FantomBlock.kt` (`AbstractBlock`, indent and wrap rules).
- `.../ide/formatter/FantomLanguageCodeStyleSettingsProvider.kt`
  (indent 2, continuation 2, code sample, minimal options).
- `plugin.xml`: `<lang.formatter language="fantom_lang" .../>` and
  `<langCodeStyleSettingsProvider .../>` right after `lang.foldingBuilder`.
- Tests: `src/test/kotlin/co/anbora/labs/fantom/FantomFormatterTest.kt`,
  `FantomFormatterCorpusTest.kt`.

## Corpus facts (from a scan of sys and util before coding)

- Lines that start with `?` / `:` (ternary continuation): util/PathEnv.fan:77-78.
- Operators at line end: util/FileLogger.fan:130-131 (`+`), JsonInStream.fan:172 (`?`).
- Compact operators exist: `str.size-off`, `i<toks.size`, `i:=0`, `s[0..<open]`,
  `(Opt)field.facet(...)`. So the formatter must NOT add spaces inside
  expressions that already have none; it must only keep existing spacing or
  normalise a single space to one. Safest rule: within a line, keep what is
  there; only indent at line starts is normalised.
- `while(` and `while( cur ... )` in util/CsvInStream.fan:106, JsonInStream.fan:181.
- Trailing whitespace on ~10 lines (Env.fan:411, Locale.fan:24, AbstractMain.fan).
- `[,]`, `[:]` empty list/map literals must stay compact.
- Facet it-blocks `@Serializable { simple = true }` on one line.
- Empty bodies `{}` on one line: `class BootEnv : Env {}`, `new make(...) : super(...) {}`.

## How to run

```
./gradlew --no-daemon test --tests '*FantomFormatter*' --tests '*FantomTypingTest*'
./gradlew --no-daemon buildPlugin
```

Corpus test: `FantomFormatterCorpusTest` loads sys and util `.fan` files from
`FantomHome.detect()` (skips if absent), reformats each in memory, prints
the first 5 differing files with a 3-line diff, and the count.

## Corpus count

Not measured yet (no formatter).

## Platform API gotchas

None hit yet.

## Next steps

1. Write the three main classes and register them.
2. Write the corpus test with a printed count; run it; fix rules; tighten to 0.
3. Write the unit test; check Enter after `{` still gives +2 with the enter
   handler (adjust `FantomEnterHandler` only if they disagree).
4. Update this note; commit.
