# Roadmap

Done in 2.0: parser for every Fantom 1.0.78 to 1.0.84 source, references and
rename across files, the Fantom installation as a library, type inference,
completion, quick documentation, inspections, auto-import, run configurations,
templates, a formatter and a full colour scheme.

Items 1 to 15 shipped in 2.1.0; items 16 and 17 shipped in 3.0.0.

## 2.1: the compiler in the loop

1. **Compiler errors inline.** Run `fanc` on the pod when a file is saved and
   show its errors and warnings in the editor, with the exact message the
   compiler gives. This covers everything the resolver cannot know.
2. **Test tool window.** `fant` results feed the Test runner: one row per test
   method, green and red, click to jump, rerun failed.
3. **Parameter name hints.** `addLine("A-1", 2, 9.5d)` shows `sku:`, `qty:`,
   `price:` as inlays; off for one-argument calls and when the argument is a
   name already.
4. **Highlight usages in file**, **Go to type declaration**, **Go to
   implementation**, breadcrumbs, TODO highlighting in comments, spell check
   in fandoc and strings.
5. **Rendered fandoc** in quick documentation: code spans, bullets, links,
   headings.

## 2.2: Fantom and SkySpark specifics

6. **Haxall and SkySpark as a home.** Accept a Haxall or SkySpark install,
   detected by `lib/fan/haxall.pod` or `lib/fan/skyspark.pod` next to
   `bin/fan`, with the Fantom source tree beside it for `sys`.
7. **Pod browsing.** Open a `.pod` file, list its types and slots from the
   compiled metadata, show their fandoc, navigate into them like a library.
8. **Pod dependencies.** Read `depends` from `build.fan` so only declared pods
   are visible, offer them for `using`, and run `fan build.fan` from the IDE
   with its errors inline.
9. **Axon inside Fantom strings.** Language injection for Axon in string
   literals and in `.trio` files: colouring, brace matching, and later
   completion from the Axon function index.
10. **Fandoc, Trio and `fan.props` file types.**

## 2.3: refactoring and typing

11. **Introduce variable**, **surround with** (`if`, `try`, closure),
    **unwrap**, then **extract method**.
12. **Rename follows the hierarchy**: renaming a virtual slot renames its
    overrides and the base.
13. **Typing gaps.** Generic binding on more library methods, it-block field
    assignment, closures handed to constructors, facet value references.
14. **Flow-aware nullable check** so `if (x != null) x.foo` does not warn.
15. **Completion hint when the receiver type is unknown** instead of an empty
    list.

## 3.0: debugger (shipped in 3.0.0)

16. **Debugger.** Done: breakpoints in `.fan` files, Fantom Test
    configurations and standalone scripts; Fantom frames and named locals;
    step over, into and out, including into closures, with `sys` internals
    skipped; Fantom expressions in Evaluate, Watches and breakpoint
    conditions; Fantom List, Map and object display. Needs the Java plugin.
    Not yet: assignment, closures, `->` calls, ranges and string
    interpolation in the evaluator; breakpoints in a script inside a pod
    folder.

## 3.1: many installs and build targets (shipped in 3.0.0)

17. **Install table and build targets.** The Fantom settings page gets three
    root folders (Fantom, Haxall, SkySpark). The plugin lists every install
    under them in a table with a check box; ticked installs feed the index.
    Each Haxall and SkySpark row is paired with a Fantom install: matched
    automatically from the install's `sys.pod` version (SkySpark 4.0.7 ships
    `sys` 1.0.84.4007, so it pairs with Fantom 1.0.84), with a dropdown to
    choose another. A custom folder can be added, such as a SkySpark dev
    build. Build Pod gets a target menu ("Build for SkySpark 4.0.7 / Haxall
    4.0.6 / Fantom 1.0.84"); it compiles with the paired Fantom against the
    target's pods. Writing the built pod into the target's `lib/fan` is an
    explicit choice per build; a running server loads it after a restart.

18. **Dependencies resolved from source.** Each pod in `build.fan`
    `depends` maps to real source when it exists:
    - SkySpark pods come from the Haxall source of the same version
      (SkySpark 4.0.7 pairs with Haxall 4.0.7, `haystack` from
      `core/haystack`), with a compiled-pod stub only as a fallback.
    - Your own pods come from source roots you add (for example `~/Code`).
      Copies of one repository are grouped by their git remote, and the
      version comes from git tags such as `bassgCommon/4.0.6.2.2`, not from
      the checked-out branch. The tagged code is indexed read-only, so the
      working copy is never touched.
    - With no tag for the build target, the nearest lower tag is used with a
      warning, for example `4.0.6.2.2` when building for SkySpark 4.0.7.
    - The build target is one click to switch, from the status bar and the
      Build Pod menu; switching re-maps every dependency to that target.
    - Each dependency row has a dropdown to pin another source or tag.

The promotional video follows these items.

Ideas and votes: <https://github.com/Project-SandStar/fantom-lang-intellij/discussions>.
