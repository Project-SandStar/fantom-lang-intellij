# Can tree-sitter-fantom help this plugin?

Date: 2026-11-11. Grammar studied: `the `tree-sitter-fantom` grammar from the mcpfantom project` (version 0.1.0, ABI 14, 787-line `grammar.js`, 187 node types, 44 corpus tests, a `.wasm` build, C source in `src/parser.c`).

Short answer: **not as the parser. Maybe later as a helper.** Reasons below.

## 1. What IntelliJ needs from a language plugin

IntelliJ builds a PSI tree from a `Lexer` and a `PsiParser`. Every feature (colours, folding, brace match, structure view, find usages) reads that PSI tree. The plugin today gets both from Grammar-Kit: `Fantom.flex` makes the lexer and `Fantom.bnf` makes the parser and PSI classes.

Tree-sitter gives a concrete syntax tree in C memory. It does not give a PSI tree. To use it, the plugin would need:

- a Java binding to `libtree-sitter` and to `libtree-sitter-fantom`, built for macOS arm64 and x64, Linux x64 and Windows x64, and shipped in the plugin zip;
- an adapter that walks the tree-sitter tree and builds PSI nodes with the right text ranges;
- a token lexer for the editor highlighter anyway, since IntelliJ's `SyntaxHighlighter` is lexer-based.

The huly-code-treesitter plugin does this for highlighting only. It targets IntelliJ 2023.3 and ships vendor jars. It has no PSI, so no folding, structure or navigation.

## 2. Binding options

| Binding | Java | How | Fit |
|---|---|---|---|
| jtreesitter 0.27.0 (`io.github.tree-sitter:jtreesitter`) | 23+ | FFM (`java.lang.foreign`), needs `libtree-sitter` on the library path plus the language `.dylib`/`.so`/`.dll` | Best fit. IntelliJ 2026.3 runs Java 25 with `--enable-native-access=ALL-UNNAMED`, so FFM calls work without a warning (JetBrains API notes, 2026.3). |
| ktreesitter (kotlin-tree-sitter) | 17+ | JNI, Gradle plugin generates bindings | Works, but JNI needs a C toolchain per platform in CI. |
| web-tree-sitter `.wasm` | any | needs a wasm runtime in the JVM (none bundled) | Not practical inside the IDE. |

Either native route adds four native libraries to the plugin and a CI matrix to build them. Today the plugin is a single 344 KB jar with no native code.

## 3. How good is the grammar today?

Measured on this Mac with the `.wasm` build and `web-tree-sitter` from mcpfantom.

| Input | Lines | ERROR nodes | MISSING nodes |
|---|---|---|---|
| `src/main/resources/demo/JsonConverter.fan` (the plugin's colour sample) | 394 | 59 | 1 |
| 60 real `.fan` files from `~/Code` (not `build.fan`, over 2 KB) | 11,880 | 1,502 | 22 |

56 of the 60 files have at least one ERROR node. Worst files: `EacConfigLib.fan` (608 lines, 251 errors), `TestBeanBuider.fan` (245 lines, 94 errors).

The mcpfantom coverage file (`docs/tree-sitter-coverage.md`, 2026-05-08) agrees: tree-sitter finds fewer methods than the regex parser on `compiler/Parser.fan` (95 vs 215) and `Tokenizer.fan` (41 vs 121), finds no types in `Endian.fan` (enum class), and cannot parse files with `using [java]` FFI imports.

There is no `queries/highlights.scm`, so even the huly-style highlight route would need new query files.

The local `tree-sitter` CLI cannot compile the grammar right now because the Xcode licence is not accepted (`sudo xcodebuild -license`). The `.wasm` file was used instead.

## 4. Where tree-sitter could still help

1. **Grammar test corpus.** The 44 corpus tests and the 60-file error list are a ready test set for the Grammar-Kit grammar too. Run both parsers on the same files and compare error counts.
2. **Error recovery ideas.** Tree-sitter's grammar lists 19 conflicts and how it resolves them (`grammar.js` top). Those map to the hard spots in `Fantom.bnf`: type vs expression, closure vs function type, map type shorthand.
3. **Structure view or symbol index as a side parser.** Once the grammar error rate is near zero, a background jtreesitter parse could feed a structure view or a stub index without touching PSI. This is a Phase 3 spike.
4. **LSP route instead.** mcpfantom already parses and indexes Fantom with tree-sitter. If it spoke LSP, IntelliJ's LSP API could show completion and go-to from it. The LSP API exists in IntelliJ IDEA Ultimate, WebStorm, PhpStorm, PyCharm, CLion, GoLand, Rider and others, but not in IntelliJ IDEA Community. This plugin is the "Community Edition" and supports IC, so LSP can only be an optional extra.

## 5. Recommendation

- Keep Grammar-Kit as the parser. It is what IntelliJ expects and the plugin already works.
- Do not add native tree-sitter libraries until the grammar parses real Fantom with near-zero errors.
- Use the tree-sitter corpus and the 60-file sample to test and improve `Fantom.bnf` and `Fantom.flex` now (tasks 2.1 and 2.2).
- Revisit jtreesitter as a side parser in Phase 3, after the grammar is fixed and only if a feature needs it.

## 6. How to repeat the measurement

```sh
cd mcpfantom
node - <<'EOF'
const TS = require('web-tree-sitter'); const fs = require('fs');
(async () => {
  await TS.Parser.init();
  const lang = await TS.Language.load('tree-sitter-fantom/tree-sitter-fantom.wasm');
  const p = new TS.Parser(); p.setLanguage(lang);
  const t = p.parse(fs.readFileSync(process.argv[2] ?? 'src/main/resources/demo/JsonConverter.fan', 'utf8'));
  let e = 0; const w = n => { if (n.type === 'ERROR') e++; n.children.forEach(w); }; w(t.rootNode);
  console.log('ERROR nodes:', e, 'hasError:', t.rootNode.hasError);
})();
EOF
```
