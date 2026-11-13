# Settings

## Fantom installation

Settings | Languages & Frameworks | Fantom has two folder fields.

**Fantom home** is the Fantom, Haxall or SkySpark folder you work against.
Leave it empty to use `FAN_HOME` or the newest `~/fantom/fantom-*` folder.
The plugin tells the three kinds apart:

| Kind | What it needs | What gets indexed |
|---|---|---|
| Fantom | `bin/fan` and `src/sys/fan` | every `src/<pod>/fan` |
| Haxall | `src/core` (or a `src/build.fan` that names Haxall) | every pod under `src/`, at any depth (`src/core/haystack`), with its `fan/` and `test/` folders |
| SkySpark | `lib/fan/skyarcd.pod` and `bin/fan` | nothing; SkySpark ships compiled pods only. Pod browsing comes in a later version |

The field turns red when the folder is none of these.

**Fantom sources** is optional. It is a plain Fantom install that supplies
`sys` and the other core pods when the home is Haxall or SkySpark. Leave it
empty to use the newest `~/fantom/fantom-*` folder; the page shows which one it
found. The field turns red when the folder has no `src/sys/fan`. It is not used
when the home is a plain Fantom install. When a pod is in both folders, the
home's copy wins.

Run configurations, tests and compile on save use the home's `bin/fan`. A
Haxall checkout has no `bin/fan`, so for Haxall they use the Fantom sources'
`bin/fan`.

Changing either path reindexes the library. Each pod shows up by name under
External Libraries in the Project view.

## Compile on save

The checkbox under the home path runs `bin/fan build.fan compile` for the pod
of a `.fan` file when you save it. The compiler's errors and warnings show up
in the editor with the exact message. The compile runs in a temp folder with
its own `fan.props`, so the pod file goes there and not into your Fantom
install. While a fresh compiler result exists, the "Unresolved reference"
inspection stays quiet for that file.

## Colours

Settings | Editor | Color Scheme | Fantom lists every colour the plugin paints:
keywords, operators, braces, declarations, static and constant slots, type
references, string interpolation, URIs, facets, fandoc code spans and more.
The preview updates as you change them.

## Code style

Settings | Editor | Code Style | Fantom sets the indent size (default 2) and
the continuation indent. The formatter changes indentation only; it never
adds or removes spaces inside a line.

## Inlay hints

Settings | Editor | Inlay Hints | Types | Fantom switches the inferred type
hints on and off.

## Inspections

Settings | Editor | Inspections | Fantom lets you change the severity of
each inspection or turn it off.
