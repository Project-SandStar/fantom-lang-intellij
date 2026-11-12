# Roadmap

Done in 2.0: parser for every Fantom 1.0.78 to 1.0.84 source, references and
rename across files, the Fantom installation as a library, type inference,
completion, quick documentation, inspections, auto-import, run configurations,
templates, a formatter and a full colour scheme.

## Next

1. **Haxall and SkySpark as a home.** Accept a Haxall or SkySpark install as
   the Fantom home, detected by `lib/fan/haxall.pod` or `lib/fan/skyspark.pod`
   next to `bin/fan`. Index their pod sources so `haystack::Dict`, `axon::Fn`
   and `folio::Folio` resolve and complete like `sys`. SkySpark ships pods
   without sources, so this needs the Fantom source tree next to it.
2. **Pod dependencies.** Read `depends` from `build.fan` so only declared pods
   are visible, and offer the right `using`.
3. **Build from the IDE.** Run `fan build.fan` for the current pod and show
   compiler errors in the editor.
4. **Receiver typing gaps.** Bind generic placeholders on more library
   methods, follow it-block field assignments, type closures handed to
   constructors.
5. **More refactorings.** Extract method, inline, move type to a new file.
6. **Debugger.** Attach to `fan` through the JVM debugger with Fantom frames.

Ideas and votes: <https://github.com/Project-SandStar/fantom-lang-intellij/discussions>.
