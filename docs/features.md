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
| Typing helpers | Quotes and backticks pair, `\|` pairs for closures, Enter indents inside braces, adds the `}` after an unmatched `{`, and continues `**` fandoc |
| Formatter | Cmd+Alt+L fixes indentation and leaves in-line spacing alone |

## Navigation

| Feature | How |
|---|---|
| Go to declaration | Cmd+B or Cmd+click, including into the Fantom library sources |
| Go to class, Go to symbol | Cmd+O, Cmd+Alt+O |
| Find usages | Alt+F7 |
| Structure view | Cmd+7 |
| Override gutter | Arrows on overriding and overridden slots |

## Java FFI

Fantom code can use Java classes with `using [java]`:

```fantom
using [java] javax.crypto              // a whole package
using [java] java.util::Base64          // one class
using [java] fanx.interop               // Interop, ByteArray, IntArray, ...
```

With the Java plugin (IntelliJ IDEA), these names resolve to the Java
classes:

- Go to Declaration on `Cipher` or `Base64` opens the JDK class.
- `Cipher.getInstance(...)`, `c.doFinal(...)` and `Cipher.ENCRYPT_MODE`
  resolve to the Java methods and fields.
- Overloads are picked by the number and types of the arguments, as the
  Fantom compiler does: `sb.append(5)` goes to `append(long)`, `sb.append("x")`
  to `append(String)`. `foo.a` without parentheses means the field; on
  `foo->a` the method hides the field.
- Java types map to Fantom types: `long`, `int`, `short`, `byte` and `char`
  are `Int`; `double` and `float` are `Float`; `boolean` is `Bool`; `String` is
  `Str?`; other classes are nullable. So `enc := Base64.getEncoder()` knows its
  type, and `enc.` completes the members of `Base64$Encoder` with no extra
  `using` line.
- Inner classes use the Java name: `using [java] java.util::Map$Entry as Entry`.
- A closure fits a Java interface with one method, such as `Runnable`.
- A Fantom class can extend a Java class (`class FanDate : JDate`). Inherited
  Java methods resolve, and an `override` gets the gutter arrow to the Java method.
- A Java annotation used as a facet (`@Retention { value = ... }`) resolves.
- Completion after `Base64.` lists static members. After a Java object, such
  as `c.`, it lists all public members.
- `fanx.interop` comes from `lib/java/sys.jar` of the configured Fantom
  installation.
- Jars a pod bundles also count: every `.jar` in the pod's `jar/`, `jars/` or
  `lib/java/` folder, and every jar its `build.fan` names.
- A name imported with `pkg::Type` wins over a Fantom type of the same name.
  With a package import, a Fantom type from your `using` lines wins.

In IDEs without the Java plugin, `[java]` names do not resolve. The plugin
does not mark them as errors.

## Natives

A slot marked `native` is written in Java or JavaScript, in the pod's
`java/` and `js/` folders (`javaDirs` and `jsDirs` in `build.fan`).

- A gutter icon on a `native` slot jumps to its method in `<Type>Peer.java`
  (or `<Type>.java`) and in `<Type>Peer.js` (or `<Type>.js`).
- The inspection "Native slot without implementation" warns when a folder
  `build.fan` declares has no implementation for the slot. Pods that declare
  no such folder are not checked. This works without the JavaScript plugin.

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

When you have ticked installs in the settings, a menu lists them as targets:
"Build for SkySpark 4.0.7", "Build for Haxall 4.0.7", "Build for Fantom
1.0.84". The last target you used comes first. The pod compiles with the
target's `bin/fan` against the target's pods.

- Haxall ships sources, not pods. The plugin asks whether to borrow the pods of
  the SkySpark of the same version, or to build Haxall's pods first. Building
  them takes a few minutes; they go into a temp folder.
- The pod goes into a temp folder and never into an install. To copy it into
  the target's `lib/fan`, choose **Build with Options** and tick "Write pod
  into the target's lib/fan". A running server loads it after a restart.
- You cannot build for an install whose Fantom is missing until you pick one
  in the settings.

Picking a target in this menu also makes it the default build target. The
status bar shows it ("Fantom: SkySpark 4.0.7"); click it to switch without a
build.

## Dependencies from source

Navigation, completion and docs use source where it exists, so you see real
code and comments, not stubs:

- SkySpark pods such as `haystack`, `folio` and `axon` come from the source of
  the paired Haxall of the same version.
- Pods from your own repositories, such as `bassgCommon`, come from the
  release tag that matches the build target, for example
  `bassgCommon/4.0.6.2.2`. The plugin copies the tag into a read-only cache and
  never touches your working copy.
- Pods with no source anywhere, such as `skyarcd`, come from stubs.

Switch the build target and every dependency is picked again. See
[Settings](settings.md#source-roots-for-project-pods) for the rules.

The IDE reads `podName`, `version`, `depends`, `srcDirs` and `resDirs` from
`build.fan` when they are written as plain values, such as
`depends = ["sys 1.0", "concurrent 1.0"]`. When `depends` comes from a variable
or a method, the IDE does not check it.

## Run

A green arrow appears next to `static Void main`, next to each `Test`
subclass, and next to each `test*` method. Run configurations call `fan` and
`fant` from the configured Fantom installation.

Before launch, a Fantom or Fantom Test configuration builds its pod from the
current sources. This is the **Build Fantom pod** step in the configuration's
**Before launch** list. It is on for new configurations, like Java's
**Build**:

- The step compiles the pod with the current build target into a private
  temp folder. It does not write into the `lib/fan` of any install.
- `fan` or `fant` then starts in that folder. Its `fan.props` puts the new pod
  in front of the installed one, so a test you added today runs, not the
  older pod in the install.
- If the build fails, the launch stops. The errors show in the editor and in
  a notification. Nothing runs against the old pod.
- When the step ran, the configuration's working directory is not used.
- A script target (a `.fan` path) is not built: it compiles when it runs.
- To run against the installed pod, remove the step from **Before launch**.

Configurations saved before this version get the step once, when the
project opens. If you remove it, it stays removed.

## Debug

Debug needs the Java plugin, so it works in IntelliJ IDEA. In IDEs without
Java the plugin still loads, but Debug is not there.

Click in the gutter of a `.fan` file to set a breakpoint, then start a Fantom
run or test configuration with Debug. The IDE starts `java` with a JDWP agent
and the program that `bin/fan` or `bin/fant` runs, and attaches the Java
debugger.

What works:

- Line breakpoints in the `.fan` files of a pod. The **Build Fantom pod**
  step (see Run) builds the pod before the debugger starts, so breakpoints
  in new code stop.
- Debug on a Fantom Test configuration. Breakpoints in `test*` methods stop,
  and the test tree still shows which tests pass and fail.
- Line breakpoints in scripts (a run target that is a `.fan` path), when the
  script is not inside a pod folder (no `build.fan` with a `podName` above it).
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
- The Variables view shows a Fantom List and Map with their size
  (`size = 3`) and expands them to their elements. Other Fantom objects show
  their JVM class (`fan.pod.Type`), their `toStr` value and their fields.

What does not work:

- Other expressions give "Not supported in the Fantom evaluator", for
  example assignment, closures, `->` calls, ranges, map literals and Str
  interpolation (`"$x"`). Static calls find only types the program has
  loaded. There is no completion in the Evaluate box.
- Breakpoints in a script inside a pod folder, such as `build.fan` itself.
- Hot swap of changed code. Rebuild the pod and start again.
- Breakpoints in `sys` sources: `sys` runs as Java (`fan.sys`), not from
  `.fan` files.

## Axon and other files

| File or place | What you get |
|---|---|
| `.axon` files | A real Axon parser. Syntax errors are marked as you type, and one error does not hide the rest of the file. Ctrl+click (Cmd+click) on a name goes to its definition in the same file: a local (`name: expr`), a lambda parameter, a `defcomp` cell, a `catch` variable or a function defined at the top of the file. Rename works for these names. A call to a function of your Axon project (see [Axon project copy](#axon-project-copy)) goes to its `.axon` file; a local or a function of the file with the same name wins. Built-in SkySpark and Haxall functions (`readAll`, `hisRead`) are not resolved and are not marked as errors. Folding for `do ... end`, `defcomp ... end`, lists, dicts and comments. Structure view with functions and `defcomp` cells. Reformat Code sets the indent of lines (two spaces) and changes nothing else. Colours (keywords, strings, numbers with units, dates, times, `@refs`, `^symbols`, URIs, comments, operators). Names are coloured by what they are: a function name, a parameter, a local, a function call, a tag name, a `defcomp` cell; a name that is not defined in the file and is not called keeps the plain colour. Brace matching for `( ) [ ] { }`, and Cmd+/ comments. Colours: Settings, Editor, Color Scheme, Axon |
| Axon in Fantom strings | The first string argument of `eval`, `evalAll`, `evalToFunc`, `parseAxon` or `Axon` is parsed as Axon, with colours, syntax errors and navigation. Put `// language=axon` on the line above any other string to do the same. A string with `$name` interpolation, or one joined with `+`, is only a part of the Axon text, so no syntax error is shown in it |
| `.trio` files | A full parser for Haystack records. Colours for `---` separators, tag names, `//` comments and each kind of value: strings, numbers with units, `true`/`false`, `@refs` with their names, `^symbols`, dates, times, URIs, `C(lat,lng)`, `Type("text")`, `NA`, lists `[ ]` and dicts `{ }`. Indented text, `Zinc:` and `Trio:` blocks are one value. Syntax errors are marked, and an error in one record does not spoil the next. Fold a record or a multi-line value. The Structure view shows one node per record, named by its `name`, `dis` or `id` tag. Cmd+/ comments a line, and `[ ]`, `{ }`, `( )` are matched. The `src:` value is coloured as Axon The `src:` value is parsed as Axon, with the same errors and navigation as an `.axon` file. The `src:` of a `view` record holds view settings and is left as plain text |
| `.fandoc` files | Headings and their underlines, list markers, `pre>` blocks, backtick links and `'code'` |
| `fan.props`, and `.props` files under an `etc/` folder | `key=value` colours and `//` comments |

## Axon project copy

When you work on one SkySpark project, the IDE can hold a read-only copy of
that project's Axon functions.

- The status bar shows "Axon: none" or "Axon: instance/project". Click it to
  pick a project, to refresh the copy, or to drop it.
- "Pick Axon Project..." opens a window with a search field, the connections
  on the left and their projects on the right. Type to filter by connection or
  project name. Up and Down move in the project list, Enter or a
  double-click picks, and Escape closes the window. The window opens at the
  current project, or else at the connection you clicked last.
- The functions come from an Axon server when its URL is set. With no URL
  they come from the global proj folder. See [Settings](settings.md).
- The copy shows under External Libraries as "Axon: instance/project", one
  `.axon` file per function. The files open with the Axon colours.
- The files are read-only. They are outside your project, so version control
  never sees them.
- There is one copy per IDE project. Picking another project, or none,
  deletes the old copy. The copy stays after an IDE restart; no network is
  used at start.
- Refresh asks the server to read SkySpark again, then fetches the functions.
- "Sync Axon Functions" does the same from a menu: Tools, or a right-click on
  the project root or on the "Axon: instance/project" node in the Project
  view. It runs in the background with a progress bar you can cancel. A
  notification then lists the functions added, changed and removed. The item
  is greyed out when no server URL, no access key or no project is set; its
  tooltip says which one. A local `proj` folder alone cannot sync: only the
  server reads SkySpark.
- A fetch that fails changes nothing: the old copy stays as it was. The error
  shows as a notification.

In any `.axon` file, and in Axon inside Fantom strings and `.trio` files, the
functions of the copy work like the functions of the file:

- Ctrl+click (Cmd+click) on a call opens the `.axon` file of the function.
- Completion offers the function names, with the first line of each doc.
- Quick Documentation (F1 or Ctrl+Q) shows `name(params)`, the project and
  the doc.
- Find Usages on a function file finds its calls in your project.
- A name the copy does not know is not marked as an error.

Only the functions of the project you picked are used. Pick another project
and the old names no longer resolve.

Not there yet: editing a function and pushing it back from the IDE.

## Axon files in a proj folder

The Axon server keeps the functions of each SkySpark project in a `proj`
folder:

```
proj/<connection>/<project>/.sync-metadata.json
proj/<connection>/<project>/func/<name>.axon
proj/<connection>/<project>/func/<name>.trio
```

Open that folder in the IDE (the whole `proj` folder, one connection or one
project) and each `.axon` file knows its own project:

- A call resolves to the function of the same project only. Two projects can
  each have a function of the same name; they never mix.
- Completion lists the functions of the same project.
- Axon in the `src` tag of a `.trio` file in the project uses the same project.
- Quick Documentation shows the `doc` of the function's `.trio` file.
- Find Usages on a function file searches its own project folder only.
- The status bar shows "Axon: connection/project" for the `.axon` or
  `.trio` file in the editor.
- Files in `backup`, `.versions`, `.backups` and `.sidecar-*` folders are
  copies: they have no project, and their names resolve to nothing.

The project comes from the path under the **Global proj folder** setting, and
its name from `.sync-metadata.json`. With the setting empty, the IDE looks for
the nearest `.sync-metadata.json`, at most four folders up. See
[Settings](settings.md#axon).

For these files the project copy is not used. Files outside a `proj` folder
work as before.

## Tools for AI agents (MCP)

When the bundled MCP Server plugin is on (Settings, Tools, MCP Server), an AI
agent such as Claude Code can ask the IDE about Fantom and Axon code. The
answers are the ones the IDE itself gives.

| Tool | What it answers |
|---|---|
| `fantom_diagnostics` | The problems of a `.fan` or `.axon` file: syntax errors, enabled inspections, and compiler messages when a fresh compile exists. One line per problem: severity, line:column, message |
| `fantom_resolve` | Where a name is defined, as Go to Declaration finds it. Give a file with an offset or a line and column, or give a name such as `demo::Foo.go`, `Foo.go` or `Foo` |
| `fantom_find_usages` | Find Usages for a declaration, in project files only. Point at the declaration or at a use of it. An Axon name is searched in its own file |

- The tools read only files in the open project.
- While the IDE indexes, each tool answers "indexing, retry".
- Each tool returns at most 50 lines unless you ask for more (500 at most).
- Without the MCP Server plugin the Fantom plugin works as before; only these
  tools are missing.
