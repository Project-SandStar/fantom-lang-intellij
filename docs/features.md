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
| Introduce Variable | Cmd+Alt+V (Ctrl+Alt+V); puts `name := expr` before the statement, names it from the call or type, can replace equal expressions in the method |
| Extract Method | Cmd+Alt+M (Ctrl+Alt+M) on whole statements; makes a private method after the current one, with locals as params and at most one result |
| Surround With | Cmd+Alt+T (Ctrl+Alt+T); statements: `if`, `if / else`, `try / catch`, `while`, closure `\|->\| { }`; expressions: `(expr)`, `(expr as Type)` |
| Unwrap / Remove | Cmd+Shift+Delete (Ctrl+Shift+Delete); unwraps `if`, `else`, `try`, `while`, `for` and a closure block, removes `else` and `catch` |

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

## Debug

Debug needs the Java plugin, so it works in IntelliJ IDEA. In IDEs without
Java the plugin still loads, but Debug is not there.

Click in the gutter of a `.fan` file to set a breakpoint, then start a Fantom
run configuration with Debug. The IDE starts `java` with a JDWP agent and
`fanx.tools.Fan` directly (the same program `bin/fan` runs) and attaches the
Java debugger.

What works:

- Line breakpoints in the `.fan` files of a pod. The pod must be built
  (Build Pod) before you debug.
- The stack shows `Foo.fan` lines.
- The Variables view shows Fantom names for parameters and locals. The
  debugger sets `FAN_DEBUG=true` for this; set it to `false` in the run
  configuration to turn it off.
- Step Over stays in `.fan` code. Step Into skips the Fantom runtime
  (`fan.sys`, `fanx`) and goes into a closure body from `list.each |v| {...}`.
- Evaluate Expression, Watches and breakpoint conditions take Fantom
  expressions, such as `x + 1`, `list.size`, `list[0]`, `s.upper`,
  `x > 2 && y != null` or `f?.name ?: "none"`. A breakpoint with the
  condition `x == 3` stops only when `x` is 3. You can use:
  - locals, fields, `this` and slots of `this` without `this.`;
  - Int, Float, Str, Bool and null literals, and list literals `[a, b]`;
  - `a.b`, `a?.b`, calls `a.m(x, y)` and static calls `Type.m()`;
  - index `a[i]` on a List, Map or Str;
  - `+ - * / % == != < <= > >= <=>`, `&& || !`, `?:`, `a ? b : c`,
    `is`, `isnot` and `as`.
- The Variables view shows a Fantom List and Map with their elements, and
  other Fantom objects with their type name (`pod::Type`) and fields.

What does not work:

- Other expressions give "Not supported in the Fantom evaluator", for
  example assignment, closures, `->` calls, ranges, map literals and Str
  interpolation (`"$x"`). Static calls find only types the program has
  loaded. There is no completion in the Evaluate box.
- Fantom Test configurations have no Debug yet.
- Breakpoints in scripts (a run target that is a `.fan` path) do not stop.
  Scripts compile in memory into a pod with a generated name.
- Hot swap of changed code. Rebuild the pod and start again.
- Breakpoints in `sys` sources: `sys` runs as Java (`fan.sys`), not from
  `.fan` files.

## Axon and other files

| File or place | What you get |
|---|---|
| `.axon` files | Axon colours (keywords, strings, numbers with units, dates, times, `@refs`, `^symbols`, URIs, comments, operators), brace matching for `( ) [ ] { }`, and Cmd+/ comments. Colours: Settings, Editor, Color Scheme, Axon |
| Axon in Fantom strings | The first string argument of `eval`, `evalAll`, `evalToFunc`, `parseAxon` or `Axon` is coloured as Axon. Put `// language=axon` on the line above any other string to do the same |
| `.trio` files | Haystack records: `---` separators, tag names, values, `//` comments. The `src:` value is coloured as Axon |
| `.fandoc` files | Headings and their underlines, list markers, `pre>` blocks, backtick links and `'code'` |
| `fan.props`, and `.props` files under an `etc/` folder | `key=value` colours and `//` comments |
