# Grammar work: resume document

Date: 2026-11-11. Branch: `grammar/fantom-corpus` (off `docs/agent-guide-and-report`, off `master`).
Read this first if you pick up the grammar work in a new session. Everything below was checked against files on disk.

## 1. Goal

Make the plugin's lexer (`src/main/grammar/Fantom.flex`) and parser (`src/main/grammar/Fantom.bnf`) parse real Fantom code with no errors. The measure is a corpus test over the seven Fantom distributions in `/Users/alper/fantom`:

| Folder | `.fan` files |
|---|---|
| fantom-1.0.78 | 880 |
| fantom-1.0.79 | 895 |
| fantom-1.0.80 | 764 |
| fantom-1.0.81 | 852 |
| fantom-1.0.82 | 885 |
| fantom-1.0.83 | 890 |
| fantom-1.0.84 | 869 |

Use 1.0.84 as the main target. It is the newest and `fantom-1.0.84/bin/fan` runs on this Mac. The official grammar is `fantom-1.0.84/src/doc/docLang/doc/Grammar.md`. The official parser is `fantom-1.0.84/src/compiler/fan/parser/Parser.fan` (2,491 lines) and `Tokenizer.fan` (1,064 lines). When the plugin grammar and the compiler disagree, the compiler wins.

## 2. What exists on the branch (uncommitted before this doc, committed with it)

- `build.gradle.kts`: added `testFramework(TestFrameworkType.Platform)`, JUnit 4 and opentest4j test deps, and a `test` block that passes three env vars as system properties: `FANTOM_CORPUS`, `FANTOM_CORPUS_FAIL`, `FANTOM_SNIPPETS`.
- `src/test/kotlin/co/anbora/labs/fantom/FantomCorpusParsingTest.kt`: parses every `.fan` under `FANTOM_CORPUS` (colon separated folders). Writes `build/reports/fantom-corpus.txt` with totals, the 25 most common error messages, and one line per failing file with its first error. Passes unless `FANTOM_CORPUS_FAIL=true`.
- `src/test/kotlin/co/anbora/labs/fantom/FantomSnippetDumpTest.kt`: parses every `.fan` in `FANTOM_SNIPPETS`, writes `<name>.tree.txt` next to each, prints `SNIPPET <name>: errors=N` plus the first three errors. Never fails.
- `src/test/testData/snippets/`: 42 small `.fan` files, one construct each. `s01` to `s30` are one-liners. `01_ctor` to `12_misc_native_dsl` are longer.
- `docs/corpus-baseline-1.0.84.txt`: the baseline report, copied from `build/reports`.

Generated sources in `src/main/gen/` are git-ignored. Gradle regenerates them from the `.flex` and `.bnf` on every build.

## 3. How to run

```sh
cd /Users/alper/Code/fantom-lang-intellij

# full corpus, about 2 minutes, report in build/reports/fantom-corpus.txt
FANTOM_CORPUS=/Users/alper/fantom/fantom-1.0.84/src ./gradlew --no-daemon test --tests '*FantomCorpusParsingTest*'

# all seven distributions
FANTOM_CORPUS=$(ls -d /Users/alper/fantom/*/src | paste -sd: -) ./gradlew --no-daemon test --tests '*FantomCorpusParsingTest*'

# snippets, about 15 seconds, trees written next to the .fan files
FANTOM_SNIPPETS=$PWD/src/test/testData/snippets ./gradlew --no-daemon test --tests '*FantomSnippetDumpTest*'
```

Gradle prints only `BUILD SUCCESSFUL`. The per snippet lines are in `build/test-results/test/TEST-co.anbora.labs.fantom.FantomSnippetDumpTest.xml` inside `<system-out>`.

Check a construct against the real compiler with a tiny file and `/Users/alper/fantom/fantom-1.0.84/bin/fan File.fan`. A file with a `main` runs; a parse error prints the line.

## 4. Baseline (before any grammar change)

Corpus 1.0.84: **869 files, 182,562 lines, 4,590 errors, 560 files with errors**.

Snippet results (16 of 30 one-liners fail):

| Snippet | Fails on | Root cause |
|---|---|---|
| s03 `|->| { x := 1 }` | `{` after `|->|` | `closureSig` wants `dynCall type` after `->`; `->` with no return type is not allowed |
| s04 `i++` | `++` | `postfixExpr` is listed after `termExpr` in `unaryExpr`, so `termExpr` wins and `++` is left over |
| s06 `new make(Str s) : super(s) {}` | `make` | s05 (`: this.make()`) passes with the same rule order, so order and pins are not the cause. The bug is precedence: `ctorChain ::= ":" ctorChainThis | ctorChainSuper` reads as `(":" ctorChainThis) | ctorChainSuper`, so `: super(...)` never matches. Fix: `ctorChain ::= ":" (ctorChainThis | ctorChainSuper)`. Most of the 625/301/228/224 "`':', '::', '?', IDENTIFIER or '[' expected`" rows in the corpus report sit inside `SLOT_DEFS` right after this failure, so they are probably a cascade from it. Re-rank the corpus after this one-line fix before planning anything else |
| s07 `#! /usr/bin/env fan` | SHABENG at top | `compilationUnit` has no `SHABENG` slot |
| s08 `enum class Color { red, green("x"), blue }` | `}` after last value | `enumValDef` requires `(',' | &';')` after each value; the last value before `}` has neither |
| s10 `facet class Js {}` | `facet` at top | `facet` lexes as IDENTIFIER (no keyword rule) but `facetDef` uses the token `facet`, which is never produced |
| s11 `Str x; Void f()` | `;` after field | `fieldDef` has no `[";"]` |
| s13 `Str:Obj? m := [:]` and `[Str:Int][] x` | map types | `mapType ::= ["["] simpleType ":" type ["]"]` cannot nest; `listType` only wraps `simpleType` |
| s14 `{ get { &x } private set { ... } }` | `&x` as statement | `field ::= ["&"] id` exists but `stmt` recovery and `localDefBlock` do not reach it; also `it-block` vs accessor block ambiguity |
| s17 `|Int->Str| g, |->| h := null` | `|->|` | same as s03 inside `funcType` |
| s22 `static const Int x;` | `;` | same as s11 |
| s25 `Str[] names := [,]; Int n := 5` | `;` after `[,]` | Same as s11. `[,]` itself parses fine (checked in the PSI dump) |
| s26 `Str s := "a $x ${y.z} $<loc>"; Str t := """q "x" r"""` | `;` after the string | Same as s11. Both the interpolated string and the triple string lex as one `STRING_LITERAL` (checked in the PSI dump) |
| s27 `for (i:=0; i<3; ++i)` | `i:=0` | `forInit ::= expr | localDef` tries `expr` first; `i` parses as expr, then `:=` fails. Swap order |
| s28 `abstract Void f();` | `;` | `abstractMethodDef` has no `[";"]` |
| s30 `mixin M { abstract Void f(); }` | `;` | same |

Corpus top error families (from `docs/corpus-baseline-1.0.84.txt`):

| Count | Message | Same cause as |
|---|---|---|
| 625 | `':', '::', '?', IDENTIFIER or '[' expected, got '('` | type parsed where an expression was meant; `localDefBlock ::= type id ...` tries `type` first on every statement, and `simpleType` accepts any IDENTIFIER, so `foo(` is read as a type |
| 301 | same, got `.` | same |
| 228 | same, got `=` | same |
| 185 | `'make' unexpected` | s06 |
| 146 | `'(', ':=' or '{' expected, got ','` | closure params `|a, b|` read as `formalFull ::= type [id]` |
| 77 | `'++' unexpected` | s04 |
| 67 | `<type def> or FANDOC_LINE_COMMENT expected, got 'override'` | recovery fell out of the class after an earlier error |
| 51 | shebang | s07 |

## 5. Facts from the real compiler that the grammar must copy

From `Parser.fan` and `Tokenizer.fan` in 1.0.84:

- **Keywords** (45): abstract as assert break case catch class const continue default do else false final finally for foreach if internal is isnot it mixin native new null once override private protected public readonly return static super switch this throw true try using virtual void volatile while. Missing from the plugin lexer: `assert do foreach it void volatile`. `it` must become a keyword (9,632 uses of `it.` in the corpus; it is never used as a declared name).
- **Not keywords**: `enum`, `facet`, `get`, `set`. The compiler checks `cur.val == "enum"` on an IDENTIFIER. `facet` is used as a plain identifier 706 times in the corpus. So the lexer must not turn `enum` or `facet` into keyword tokens. The BNF should match them as contextual identifiers, like `get`/`set` already do through `FantomParserUtil.contextualKeyword`.
- **Statement end** is a newline, `;`, `}` or EOF (`endOfStmt`). Grammar-Kit skips whitespace, so the parser cannot see newlines. Add a `FantomParserUtil` external rule that looks at `b.rawLookup(-1)` for a `WHITE_SPACE` token containing `\n`. Use it in `exprStmt`, `localDef`, `returnStm`, `fieldDef`, `abstractMethodDef`, `enumValDefs`.
- **Newline sensitive tokens**: `(` and `[` only continue a term chain if they are on the same line as the previous token (`isCallOpenParen`, `isIndexOpenBracket`). Postfix `++`/`--` must be on the same line. `#` slot literal needs the id on the same line. 355 corpus lines start with `(` and 338 with `[` right after a complete statement, so this matters.
- **Whitespace sensitive**: `?` makes a type nullable only when there is no space before it (`!cur.whitespace`). `Foo ? a : b` is a ternary. Add a `noSpaceBefore` external rule for `?`.
- **Types** (`ctype`): `simpleType | "[" ctype "]" | funcType`, then optional `?`, then any number of `[]` each with optional `?`, then optional `: ctype` for a map, then optional `?`. Map key may not be nullable. This is recursive and handles `[Str:Obj][]`, `Str:Int?[]`, `|Int->Str|[]`.
- **Function type**: `"|" [formal ("," formal)*] ["->" ctype | "?->" ctype] "|"`. A formal is `type [id]` or just `id`. `|->|` has no params and no return. `|a, b|` are untyped names.
- **Try type then expression**: the compiler calls `tryType` at the start of every statement and every term. If a type parses and an IDENTIFIER follows, it is a local def. Else it resets and parses an expression. The compiler can do this because `simpleType` rejects any name that is not an imported type (`unit.importedTypes`). The plugin has no type table, so `type &IDENTIFIER` alone is not enough: `x ? a : b.c` (96 corpus hits) and `a ? b : c(` or `c[` (146 hits) would still read `a : b` as a map type. Use a naming heuristic instead. The corpus has 0 lowercase class or mixin names and only 2 uppercase local names, so: a type name is an IDENTIFIER whose first letter is uppercase, or `pod::Name`. Add a `<<typeName>>` external in `FantomParserUtil` and use it in `simpleType`. Then `localDefTyped ::= type &IDENTIFIER id [":=" expr]` is safe.
- **Accept, do not enforce**: the plugin's job is to show no false errors on valid code. Accepting too much is fine. So prefer `[";"]` and `<<sameLine>>` guards over a strict `<<eos>>` check. Use `<<sameLine>>` on `(`, `[`, postfix `++`/`--` and `#id`.
- **Slot order**: `static {` is a static init. Then facets, then flags. If the flags include `new`, it is a constructor: `new id "(" params ")" [":" (this|super) ["." id] "(" args ")"] body`. `static new` is allowed. If an IDENTIFIER is followed by `:=`, it is an untyped field. Else `type id` then `(` means method, else field.
- **Field**: `facets flags type id [":=" expr] ["{" [flags get [block]] [flags set [block]] "}"] eos`. Inside the accessor block the compiler does not treat `{` as an it-block when the next token is a protection keyword, `static`, `readonly`, `get` or `set`.
- **Method**: abstract and native methods have no body and end with eos. Others need `{ stmts }`.
- **Enum**: `enumValDef ("," enumValDef)* eos` then normal slots. A value is `facets id ["(" args ")"]`.
- **Facets**: `"@" ctype ["{" (id "=" expr eos)* "}"]`. Facet values are separated by newlines or `;`.
- **Using**: `using podSpec ["::" id ["$" id]* ["as" id]]`. `podSpec` is `[ffi] a.b.c` or a string.
- **Term base**: a type followed by `#`, `.`, DSL, `[`, `(`, or `{`; else `&id`, id, literals, `[` collection, keywords `null this super it true false`, or `#id`.
- **Term chain**: `.id`, `->id`, `?.id`, `?->id`, `[expr]` same line, `(args)` same line, `{ it-block }`. A call `id(args)` may be followed by a closure `|...| {}` or an it-block `{}`.
- **Numbers**: digits with `_`, optional `.digits`, optional exponent, then suffix: `f/F` float, `d/D` decimal, or duration `ns ms sec min hr day`; a number with `.` or exponent and no suffix is a decimal. `0x` hex and `0b` binary. `.5f` leading dot is valid.
- **Strings**: `"..."` with `\` escapes and `$x`, `${expr}`, `$<locale>` interpolation; `"""..."""` triple; `` `uri` ``; `'c'` char; `<|...|>` DSL after a type, no nesting. Multi-line normal strings are allowed.
- **Comments**: `//`, `/* */` nested, `**` doc lines.
- **Shebang** `#!` on line 1.

## 6. Plan for the rewrite

Do it in this order. Run the snippet test after each step and the corpus test after steps 2, 4 and 6.

1. **Lexer** (`Fantom.flex`): add keywords `it assert do foreach void volatile`. Remove `enum` from the keyword list (it stays IDENTIFIER). Keep `facet` as IDENTIFIER. Add `0b` binary. Add `->` and `?->` as single tokens and delete `dynCallImpl`/`safeDynCallImpl` from `FantomParserUtil`. Add locale literal inside strings only if the string regex breaks; the current regex already swallows `$<a>`. Make `/* */` nesting work with a state and a depth counter, same as the DSL state.
2. **BNF tokens**: add `IT ARROW SAFE_ARROW ASSERT DO FOREACH VOID VOLATILE`. Remove `ENUM`, `FACET`. Keep `GET`/`SET` as remapped tokens.
3. **BNF externals** in `FantomParserUtil`: `<<eos>>` (newline before current token, or `;`, `}`, EOF), `<<sameLine>>` (no newline before current token), `<<noSpaceBefore>>`, `<<enumKeyword>>`, `<<facetKeyword>>` (contextual IDENTIFIER remap, copy `getKeyword`).
   **Wave 1 done** (steps 1 to 3, commit on `grammar/fantom-corpus`):
   - Lexer: keywords `it assert do foreach void volatile` added; `enum` rule removed, so `enum` and `facet` lex as IDENTIFIER. `->` lexes as DYN_CALL and `?->` as SAFE_DYN_CALL. `0b` binary ints with `_`. Exponent now needs digits. Block comments nest (`BLOCK_COMMENT` state, one C_STYLE_COMMENT token, unterminated runs to EOF).
   - BNF tokens: `IT='it' ASSERT DO FOREACH VOID VOLATILE` added; `ENUM='enum_kw'` and `FACET='facet_kw'` kept as remapped tokens like `GET`/`SET`. The token names for the arrows stay `DYN_CALL` / `SAFE_DYN_CALL` (BNF text `'->'`, `'?->'`), not ARROW / SAFE_ARROW.
   - Externals in `FantomParserUtil`: `<<eos>>`, `<<sameLine>>`, `<<noSpaceBefore>>`, `<<enumKeyword>>`, `<<facetKeyword>>`. `enumDef`/`facetDef` use the last two. `dynCallImpl`, `safeDynCallImpl`, `collapse` deleted. Unit test: `FantomParserUtilTest`.
   - Small BNF fixes needed to stay level: `it` added to `stmt_item_recover`; `funcType` accepts `safeDynCall` so `|Obj?->Obj?|` parses (the compiler does the same).
   - `HighlightingAnnotator` colors ENUM/FACET leaves as keywords.
   - Results: snippets unchanged, 16 of 30 `sNN` still fail (same set). New `s31_wave1` covers the new tokens. Corpus: `files=869 lines=182562 errors=4581 badChars=0 filesWithErrors=556` (was 4590 / 560, no file got worse).

   **Fix batch done** (commit `d4b0ce4` on `grammar/fantom-corpus`): ctorChain parentheses, postfix before term with `<<sameLine>>`, forInit order, `[";"]` on field/abstract method/ctor, body-less ctor, enum list ends at last value, `|->|` with no return type, `return` takes an expr only on the same line, bare `Type name` local needs `<<eos>>`, itAdd as a suffix of exprStmt, `<<notAccessorBlock>>` guard on itBlock, SHABENG is a comment token, `generateParser` purges old files. Corpus: 4590 errors / 560 files to **588 / 132**. Baseline report replaced.
   **Wave 2 in flight** (three agents, separate worktrees, branches `grammar/w2-types`, `grammar/w2-slots`, `grammar/w2-exprs`, all off `d4b0ce4`): types rewrite (step 4), slot/facet/switch/using rules (step 5), statement and expression rules (step 6). Merge order after they report: types, then slots, then exprs. Rerun the corpus after each merge and keep the no-file-worse gate.

   **Wave 2 slots done** (branch `grammar/w2-slots`, off `bc6157a`): slot, facet, switch, using and compilation-unit rules.
   - `compilationUnit ::= usingDef* (typeDef | doc)*`. `usingPod` pins on `using`, takes `[";"]`, and `podSpec` allows `id ("." id)*`. `$` is already an identifier char in the lexer, so `Line2D$Double` is one IDENTIFIER.
   - `slotDef_item ::= staticInit | methodDef | fieldDef`. `methodDef ::= facets methodFlags type id itemParameterList [statementBlock] [";"]` (pin on the parameter list), so `native Int foo()`, `once Str bar() { "a" }` and `virtual Void g() {}` share one rule. Removed rules: `abstractMethodDef`, `methodImplDef`. `fieldDef` keeps its shape; `abstract Bool i` and `static const Int x` parse through the no-init form.
   - Accessors: `fieldDefBlock ::= "{" fieldAccessor* "}"` with `fieldGetter ::= fieldFlags get [statementBlock | ";"]` and the same for `set`, any order, so `{ private set }`, `{ get { &x } private set { &x = it } }` and `{ set { ... } override get { ... } }` work.
   - Facets: `facetVals ::= "{" facetVal* "}"`, `facetVal ::= id "=" expr [";"]` with a recover rule, so `@Opt { help = "x"; aliases = ["?"] }` and `@Serializable { simple = true }` parse and the next slot starts clean.
   - Switch: `caseStm`/`defaultStm` are gone from `stmt`. New `switchStmBlock ::= "{" switchEntry* "}"`, `caseBlock ::= caseLabel+ switchStmts`, `caseLabel ::= case (caseIdLabel | expr ":")`, `defaultBlock ::= default ":" switchStmts`. `caseIdLabel` takes a bare `id :` first so `case opPing: pong()` is not read as a map type. `switchStmts` stops at `case`/`default` without an error. PSI: `FantomCaseBlock`, `FantomCaseLabel`, `FantomDefaultBlock` replace `FantomCaseStm`, `FantomDefaultStm`.
   - Snippets: new `s51_slots_facetvals`, `s52_slots_accessors`, `s53_slots_switch`, `s54_slots_using`, `s55_slots_mixin_methods`. `10_facets` went 5 to 0 errors; `09_enum` stays 0. `s52` keeps 1 error from `if (c) return` followed by `&x = it` on the next line; that is a statement rule (wave 2 exprs) and fails the same way on the baseline.
   - Corpus: `errors=588 badChars=0 filesWithErrors=132` to `errors=558 badChars=0 filesWithErrors=130`. Per file: Dasm 18 to 2, NodeJsCmd 5 to 0, AbstractMain 5 to 0, InitCmd 5 to 2, FacetsTest 3 to 2, WebSocket 2 to 1. One file is up: WebRepoMain 5 to 6. Its old 5 errors were the facet on line 26; the fix exposed line 61, `[Field.makeSetFunc([a: b, c: d])]`, a list literal holding a map literal. That line fails the same way on the baseline grammar (checked with a one-line snippet) and belongs to the expression rules. Corpus test time 10.7 s, full run 28 s. `buildPlugin` passes.

4. **Types**: replace `type nullType nonNullType simpleType listType mapType funcType formal*` with the recursive shape in section 5. Keep the PSI class names `FantomType`, `FantomSimpleType`, `FantomFuncType`, `FantomFormalFull` because Kotlin code uses them.
5. **Slots**: `slotDef ::= staticInit | ctorDef | fieldOrMethod`. `ctorDef` starts with `facets ctorFlags new` and pins on `new`. `fieldDef` gets `[";"]`/eos and the accessor block. `abstractMethodDef` gets eos. Enum values end with eos. Shebang allowed first in `compilationUnit`.
6. **Statements and expressions**: `forInit ::= localDef | expr`. `unaryExpr ::= prefixExpr | postfixExpr | termExpr` with `postfixExpr ::= termExpr <<sameLine>> ("++"|"--")`. `localDefTyped ::= type &IDENTIFIER id ...`. `callOp` and `indexExpr` guarded by `<<sameLine>>`. `closureSig` accepts `|->|`, `|a, b|`, `|Type a->Ret|`, `|Type?->Ret|`. `list ::= [type] "[" ...` must not let `type` eat the trailing `[]`: use `listType` without the trailing list suffix in that position, or a lookahead `&("[" (","|expr))`. `ifExpr` ternary needs `<<noSpaceBefore>>` only for the nullable type case, not here.

## 7. Kotlin code that depends on PSI names

Keep these class names in the BNF or update these files:

| Kotlin file | Uses |
|---|---|
| `ide/annotator/HighlightingAnnotator.kt` | `FantomStaticCall FantomDotCall FantomFacetItems FantomSimpleType FantomType FantomSlotExpr FantomSimple FantomList` |
| `ide/folding/FantomFoldingBuilder.kt` | `FantomHighOrderBlock FantomEnumBlock FantomFieldDefBlock FantomStatementBlock FantomSwitchStmBlock FantomVisitor` |
| `ide/typing/FantomBraceMatcher.kt` | tokens `LPAR RPAR LBRACKET RBRACKET LBRACE RBRACE` |
| `ide/commenter/FantomCommenter.kt` | `FANDOC_LINE_COMMENT C_STYLE_COMMENT END_OF_LINE_COMMENT` |
| `lang/core/FantomTokenType.kt` | `KEYWORDS` set (add new keywords here), `STRING_LITERALS`, `NUMERIC_LITERALS`, `FANTOM_COMMENTS` |
| `lang/core/FantomParserUtil.kt` | `dynCallImpl safeDynCallImpl getKeyword setKeyword` |
| `lang/FantomParserDefinition.kt` | defines `C_STYLE_COMMENT END_OF_LINE_COMMENT SHABENG FAN_SYS_TYPE` outside the BNF |

`FantomParser.java` is generated. Do not edit it. The lexer output path is doubled (`src/main/gen/co/anbora/labs/fantom/lang/co/anbora/...`) because `generateLexer.targetRootOutputDir` already includes the package; it works, fix later (tasks.md 1.4).

## 8. Open questions

- The report from the user says the plugin "is not working with latest". The plugin loads and highlights in IntelliJ 2026.3 on this Mac. The likely meaning is bad highlighting on real files, which this grammar work fixes. Confirm with the user which file or IDE if the corpus work does not close it.
- Whether to also run the corpus over 1.0.78 to 1.0.83. The newer grammar is a superset, so 1.0.84 first, then the rest as a regression check.
