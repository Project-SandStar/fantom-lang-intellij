# Language intelligence layers: what exists and how to resume

Written 2026-11-12 so a new session or agent can continue without the chat history.
Branch: `grammar/fantom-corpus`. Not merged to `master` (a push to `master` publishes
the plugin). Work list with status per layer: [tasks.md](tasks.md) Phase 3. Grammar
history and corpus gate: [grammar-work.md](grammar-work.md).

## 1. One-line state

Layers 1 to 6 done, layer 7 wave 1 done, wave 2 two of three merged (run configs,
usings). Open: the formatter (`layer7/formatter`, note in `docs/formatter-work.md`
when its agent writes it). 256+ tests pass. Corpus: 6,035 `.fan` files across seven
Fantom versions parse with 0 errors.

## 2. How to run everything

```sh
# full check: regenerate lexer+parser, all tests, corpus over every Fantom version, plugin zip
FANTOM_CORPUS=$(ls -d /Users/alper/fantom/*/src | paste -sd: -) \
  ./gradlew --no-daemon --rerun-tasks generateLexer generateParser test buildPlugin

# one test class
./gradlew --no-daemon test --tests '*FantomCompletionTest*'

# sandbox IDE with the plugin (no restart of the main IDE)
./gradlew --no-daemon runIde
```

Rules that bit us:
- After any BNF change or merge: `rm -rf src/main/gen` first, or stale generated PSI
  classes break `compileJava`. `--rerun-tasks` on the generate tasks is also needed.
- Grammar-Kit generates `Fantom<Rule>Impl`; a hand-written mixin must not reuse that
  name (hence `FantomIdMixin`, `FantomNamedElementImpl`).
- Tests that read the real Fantom install need `NO_FS_ROOTS_ACCESS_CHECK=true`
  (set in the Gradle test task). A roots change in a test must run inside
  `WriteAction.runAndWait { ProjectRootManagerEx.makeRootsChange(...) }`.
- `FantomHome.detect()` finds `/Users/alper/fantom/fantom-1.0.84` on this machine
  even with no configured home, so "library present" is the normal test condition
  here. Library-dependent tests must skip with a println when `detect()` is null.
- Snippet `.tree.txt` dumps are generated and git-ignored.

## 3. Architecture by layer

| Layer | Package | Key classes | Tests |
|---|---|---|---|
| Grammar | `src/main/grammar`, `lang/core` | `Fantom.flex`, `Fantom.bnf`, `FantomParserUtil` (externals `eos`, `sameLine`, `noSpaceBefore`, `typeName`, `notAccessorBlock`, `enumKeyword`, `facetKeyword`) | `FantomCorpusParsingTest`, `FantomSnippetDumpTest`, `FantomParserUtilTest` |
| 1 names | `lang/core/psi` | `FantomNamedElement` + `FantomNamedElementImpl` mixin on 12 declaration rules; `getUseScope` limits body names | `FantomNamedElementTest`, `FantomStructureViewTest`, `FantomRenameTest` |
| 2 index | `lang/index` | `FantomDeclarationIndex` (FileBasedIndex, short name -> kind + owner type), `FantomDeclarationKind` | `FantomDeclarationIndexTest`, `FantomGotoContributorTest` |
| 3 refs | `lang/resolve` | `FantomReference` (BARE, SLOT, TYPE), `FantomScope` (locals, params, closure params, catch params, type chain helpers), `FantomIdMixin` attaches the reference | `FantomReferenceTest`, `FantomGotoDeclarationTest`, `FantomUsagesTest`, `FantomRenameAcrossFilesTest` |
| 4 library | `lang/library` | `FantomHome` (detect install), `FantomSettings` (project service, `.idea/fantom.xml`), `FantomLibraryRootsProvider` (every `src/<pod>/fan` as one synthetic library), `FantomPodResolver` (pod name of a file) | `FantomLibraryTest`, `FantomConfigurableTest` |
| 5 types | `lang/types` | `FanType` (Named, ListOf, MapOf, Func), `FantomTypeInference` (cached per element), `FantomGenerics` (V L K M R A..H This), `FantomItScope` (`it` and untyped closure params) | `FantomTypeInferenceTest`, `FantomClosureTypeTest`, `FantomTypeHintsTest` |
| 6 completion | `ide/completion`, `lang/resolve/FantomSlotLookup` | `FantomCompletionContributor` (receiver slots, scope, types, keywords; priority ladder), `FantomKeywords` | `FantomCompletionTest`, `FantomCompletionRankingTest`, `FantomCompletionAutoImportTest` |
| 7 tooling | `ide/inspections`, `ide/typing`, `ide/generate`, `ide/run`, `ide/imports`, `ide/templates` | unresolved/unused/nullable/unused-using inspections, typing handlers, override/implement, run configs for `fan`/`fant`, import optimizer, live and postfix templates | `*Inspection*Test`, `FantomTypingTest`, `FantomOverrideTest`, `FantomRunConfigurationTest`, `FantomImportOptimizerTest`, `FantomUsingScopeTest`, `FantomLiveTemplateTest`, `FantomPostfixTemplateTest` |

Shared presentation (icons, signatures) lives in `ide/presentation/FantomPresentation`.

## 4. Resolve order (what the code does, in one place)

BARE `x`: locals and params visible at the use (nearest first, a `:=` name is not
visible inside its own initializer), then slots of the enclosing type, its written
supertypes breadth first, then the implicit roots (`Enum`/`Facet`, then `Obj`),
then types by short name. SLOT `recv.x`: if the receiver is `this`/`super`, the
enclosing chain; if `Type.x`, that type's chain; else the inferred receiver type's
chain (`FantomTypeInference.receiverType`); if the type is unknown, every slot named
`x` in scope (the one remaining source of ambiguity). TYPE: types by short name,
visible ones per `FantomUsingScope` preferred, project copies preferred over library
twins. Generic placeholders (`V`, `K`, ...) have no declaration and never resolve;
inspections must skip them.

## 5. Measurements that act as gates (keep them green)

| Test | Measures | Current |
|---|---|---|
| `FantomCorpusParsingTest` | parse errors over all Fantom versions | 0 of 6,035 files |
| `FantomResolveCorpusTest` | resolve in `sys` (no bodies) | 2,436 of 2,623; rest are generic params |
| `FantomResolveBodiesCorpusTest` | resolve in `util` with `sys` as library | 99.7 percent |
| `FantomTypedResolveCorpusTest` | slot refs narrowed to one target in `util` | 501 of 647 by receiver type, 134 by owner, 3 plain fallbacks |
| `FantomCompletionCorpusTest` | right slot offered at 150 real sites | 90.7 percent, 87.3 percent in top 10 |
| `FantomUnresolvedInspectionCorpusTest` | false warnings over `util` | 4 (it-block field `ordered = true`) |

## 6. Known gaps, in priority order

1. It-block field assignment `Str:Obj?[:] { ordered = true }`: the BARE name
   `ordered` does not look at the it-block receiver. Fix spot: `FantomReference`
   BARE path, consult `FantomItScope` for the enclosing it-block receiver type.
2. `sys` declares `List.map` as `Obj?[]`; the map family is special-cased in
   `FantomTypeInference.slotType` to use the closure's return type.
3. A closure handed straight to a ctor (`Foo(a) |x| { }`) is untyped.
4. No flow analysis: `if (x != null) x.foo` still gets the nullable-receiver warning.
5. An `override` is not renamed with its base method.
6. Facet values inside `@Facet { x = 1 }` get no reference.
7. Pods are a directory convention, not a model; `using` resolution is a preference.

## 7. How the agent pattern worked

One base commit by the coordinator (interfaces, registration, one test), then three
agents in `isolation: worktree` on branches `layerN/<topic>`, each told exactly which
files it may touch and where to put its `plugin.xml` lines. Merge one at a time in
dependency order, rerun the full suite after each, delete the branch and worktree.
A measurement agent per layer (corpus tally, read-only on `src/main`) found every
real bug; the coordinator fixed those in the shared files after the merge.

Agent worktrees sometimes start on old `master` (`99c58fb`): the first instruction
must be `git checkout -B <branch> grammar/fantom-corpus`. The account usage limit
has killed agents mid-task twice; their worktrees held only untracked files, and
relaunching from the current head was cheaper than resurrecting. Ask long-running
agents to commit a short resume note early.

## 8. Next steps

- Merge `layer7/formatter` when its corpus gate (reformatting `sys` and `util`
  changes nothing) holds, then run the full check and update this file.
- Fix gap 1 above; it also removes the last inspection false positives in `util`.
- Try the plugin live with `runIde`, open a real pod, exercise completion, rename,
  run arrow, Alt+Insert.
- Release: bump change notes, open a PR from `grammar/fantom-corpus` to `master`.
  The push to `master` publishes to the Marketplace.
