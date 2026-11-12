# Formatter work (layer7/formatter)

Resume note for the Fantom formatting model task. Facts only.

## Goal

Reformat Code and auto-indent for `.fan` files. Fantom house style
(sys/util pods): two-space indent, Allman braces for types, slots and
control statements, `{` on the same line for closures and it-blocks,
`case x:` one step in from `switch`, body one more step, blank lines kept
up to 3, comments keep their column, banners at column 0 stay there.

Gate: reformatting every `sys/fan/*.fan` and `util/fan/*.fan` must change
nothing (0 differing files), or a short list of justified exceptions.

## State

- Branch `layer7/formatter` from `grammar/fantom-corpus`.
- Done. All three classes exist and are registered. Tests green.
- Enter handler and formatter agree (`testEnterHandlerAgreesWithFormatter`).

## What the formatter does

Indent only. It sets the indent of a line that starts a statement, slot,
type, brace, `else`/`catch`/`finally`, `case`/`default` or a comment. It
never adds or removes a line break and never changes spacing inside a line
(`i:=0`, `s[0..<n]`, hand-aligned declarations stay as they are). Every
other line start, for example a continued expression or a ternary `:`,
keeps its indent (`Spacing.getReadOnlySpacing`).

## Files

- `src/main/kotlin/co/anbora/labs/fantom/ide/formatter/FantomFormattingModelBuilder.kt`
- `.../ide/formatter/FantomBlock.kt` (one block class, indent rules in `childIndent`).
- `.../ide/formatter/FantomLanguageCodeStyleSettingsProvider.kt`
  (indent 2, continuation 2, tab 2, blank lines 3, indent and blank-line tabs only).
- `plugin.xml`: `lang.formatter` and `langCodeStyleSettingsProvider`.
- Tests: `src/test/kotlin/co/anbora/labs/fantom/FantomFormatterTest.kt`,
  `FantomFormatterCorpusTest.kt`.

## Corpus facts (from a scan of sys and util before coding)

- Lines that start with `?` / `:` (ternary continuation): util/PathEnv.fan:77-78.
- Operators at line end: util/FileLogger.fan:130-131 (`+`), JsonInStream.fan:172 (`?`).
- Compact operators exist: `str.size-off`, `i<toks.size`, `i:=0`, `s[0..<open]`,
  `(Opt)field.facet(...)`. So the formatter must NOT touch spacing inside a line.
- `while(` and `while( cur ... )` in util/CsvInStream.fan:106, JsonInStream.fan:181.
- Trailing whitespace: one line, sys/Locale.fan:24.
- `[,]`, `[:]` empty list/map literals must stay compact.
- Facet it-blocks `@Serializable { simple = true }` on one line.
- Empty bodies `{}` on one line: `class BootEnv : Env {}`, `new make(...) : super(...) {}`.
- `else if` chains: the nested `if` sits at the column of the first `if`.
- Max blank-line run in the pods: 4 (one place); runs of 3: one place.

## How to run

```
./gradlew --no-daemon test --tests '*FantomFormatter*' --tests '*FantomTypingTest*'
./gradlew --no-daemon buildPlugin
```

Corpus test: `FantomFormatterCorpusTest` loads sys and util `.fan` files from
`FantomHome.detect()` (skips if absent), reformats each in memory, prints
the first 5 differing files with a 3-line diff, and the count.

## Corpus count

96 files, 0 differing. Two kinds of change are counted apart and allowed:

- Trailing-space only: 1 file (sys/Locale.fan:24). The formatter trims the
  end of a line it re-indents.
- Known pod oddities, listed in `KNOWN_CORPUS_ODDITIES` in the test:
  - util/TestRunner.fan:122-124: three statements at 5 spaces after a
    triple-quoted string. The pod itself breaks the two-space rule.
  - util/JsonOutStream.fan:69: the first `if` of a chain is pushed right by
    hand to line up with the `else if` lines below it.

## Platform API gotchas

- `TabbedLanguageCodeStylePanel` has a protected constructor. Subclass it
  and override `initTabs` to pick the tabs.
- A typing test that reformats after `myFixture.type` must call
  `PsiDocumentManager.commitAllDocuments()` first, or reformat throws
  "Attempt to modify PSI for non-committed Document".
- Only nodes that start a line get a real indent. `STMT` is the node for
  every statement, so `IF_STM`, `TRY_STM`, `EXPR_STMT`, `LOCAL_DEF` and the
  others must be in the same indent set as `STMT` or nested control blocks
  fall back to read-only spacing and keep their wrong indent.
- The `BLOCK` child of `if`/`while`/`for`/`catch`: no indent when it starts
  with `{` or is an `else if`; otherwise one step.

## Next steps

None open. Possible later work: optional spacing rules behind code style
flags, and alignment of `else if` chains.
