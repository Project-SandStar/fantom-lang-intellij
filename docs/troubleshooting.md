# Troubleshooting

**`Str` and other library names are not coloured and do not complete.**
No Fantom installation is configured. Set one in Settings | Languages &
Frameworks | Fantom. A valid folder has `bin/fan` and `src/`.

**Completion after `obj.` lists slots from many classes.**
The plugin could not infer the type of `obj`. That happens after a dynamic
call (`->`), or when the value comes from a method whose return type is a
generic placeholder the plugin does not bind yet. Declare the type on the
local (`Str s := ...`) and the list narrows.

**A warning says a name cannot be resolved, but it compiles.**
The name may come from a pod that is not in the configured installation, or
from an it-block field assignment (`Foo { bar = 1 }`), which the resolver does
not follow yet. Lower the inspection severity in Settings | Editor |
Inspections | Fantom if it bothers you.

**Reformat changed nothing.**
The formatter only fixes indentation. Spacing inside a line is left as it is
on purpose, because the Fantom sources themselves mix styles.

**The run arrow does not appear on `main`.**
The method must be `static Void main` inside a class, and the file must sit
in a pod folder (`fan/`) or next to a `build.fan`.

**Something else.**
Open an issue at
<https://github.com/Project-SandStar/fantom-lang-intellij/issues> with the
IDE version, the plugin version and a small `.fan` file that shows the problem.
