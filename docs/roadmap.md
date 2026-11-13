# Roadmap

Done in 2.0: parser for every Fantom 1.0.78 to 1.0.84 source, references and
rename across files, the Fantom installation as a library, type inference,
completion, quick documentation, inspections, auto-import, run configurations,
templates, a formatter and a full colour scheme.

Done on `master`, to ship as 2.1: items 1 to 15 below. Only the debugger
(item 16) is still open.

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

## 3.0: debugger

16. **Debugger.** Breakpoints in `.fan` files, Fantom frames and locals over
    the JVM debugger, step in and over, evaluate Fantom expressions. The
    largest item, done last.

Ideas and votes: <https://github.com/Project-SandStar/fantom-lang-intellij/discussions>.
