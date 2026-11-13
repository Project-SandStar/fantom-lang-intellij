# Features

## Editing

| Feature | How |
|---|---|
| Syntax and semantic colouring | Every token kind has a colour key; see [Settings](settings.md) |
| Completion | Ctrl+Space. After `.` the list shows the slots of the receiver's type, own slots first, then inherited, then `Obj` |
| Auto-import | A type from another pod shows its pod in grey; accepting it adds the `using` line. Types of pods in the `depends` of `build.fan` come first; others show `(not in depends)` |
| Parameter info | Cmd+P inside a call |
| Quick documentation | F1 or hover; shows the signature and the fandoc comment |
| Inlay type hints | The inferred type after `x := ...` and on untyped closure parameters |
| Live templates | `main`, `if`, `ife`, `for`, `while`, `each`, `try`, `tryf`, `sw`, `echo`, `doc`, `class`, `fn` |
| Postfix templates | `.if`, `.not`, `.each`, `.echo`, `.var`, `.return`, `.null`, `.nn` |
| Typing helpers | Quotes and backticks pair, `\|` pairs for closures, Enter indents inside braces and continues `**` fandoc |
| Formatter | Cmd+Alt+L fixes indentation and leaves in-line spacing alone |

## Navigation

| Feature | How |
|---|---|
| Go to declaration | Cmd+B or Cmd+click, including into the Fantom library sources |
| Go to class, Go to symbol | Cmd+O, Cmd+Alt+O |
| Find usages | Alt+F7 |
| Structure view | Cmd+7 |
| Override gutter | Arrows on overriding and overridden slots |

## Refactoring

| Feature | How |
|---|---|
| Rename | Shift+F6; follows every reference across files |
| Override or implement members | Alt+Insert inside a class |
| Optimize imports | Ctrl+Alt+O removes unused `using` lines and sorts the rest |

## Inspections

Each one has a quick fix under Alt+Enter where one makes sense.

| Inspection | Reports | Fix |
|---|---|---|
| Unresolved reference | A name that exists nowhere | Add `using pod::Type` |
| Unused declaration | Locals, parameters and private slots with no use | |
| Nullable receiver | `.x` on a value declared with `?` | Replace `.` with `?.` |
| Unused using | A `using` line nothing refers to | Remove it |
| Pod not in depends | `using x` when pod `x` is not in the `depends` of `build.fan` | Add `"x 1.0"` to `depends` |

## Build a pod

Build | Build Pod runs `fan build.fan compile` for the pod of the current file.
A gutter icon on the `podName` line of `build.fan` does the same. A
notification shows the result, and the open files of the pod show the
compiler's errors and warnings in place.

The IDE reads `podName`, `version`, `depends`, `srcDirs` and `resDirs` from
`build.fan` when they are written as plain values, such as
`depends = ["sys 1.0", "concurrent 1.0"]`. When `depends` comes from a variable
or a method, the IDE does not check it.

## Run

A green arrow appears next to `static Void main`, next to each `Test`
subclass, and next to each `test*` method. Run configurations call `fan` and
`fant` from the configured Fantom installation.
