# Settings

## Fantom installation

Settings | Languages & Frameworks | Fantom lists every Fantom, Haxall and
SkySpark install on your machine.

```
Roots
  Fantom root    [ ~/fantom   ] [Browse]
  Haxall root    [ ~/haxall   ] [Browse]
  SkySpark root  [ ~/skyspark ] [Browse]
Installs found                                    [+ Add folder]
  [x] Fantom 1.0.84         (itself)                    sys.pod
  [x] Haxall 4.0.7          Fantom 1.0.84 (auto)  v     SkySpark 4.0.7
  [x] SkySpark 4.0.7        Fantom 1.0.84 (auto)  v     sys.pod
  [ ] SkySpark 3.0.29       ! Fantom 1.0.76 missing v   sys.pod
Default build target  [ SkySpark 4.0.7 v ]
[x] Compile on save
```

**Roots.** The plugin looks at each folder directly under the three roots.
Zips, project folders and other folders that are not installs are left out.
It tells the kinds apart:

| Kind | What it needs | What gets indexed |
|---|---|---|
| Fantom | `bin/fan` and `src/sys/fan` | every `src/<pod>/fan` |
| Haxall | `src/core` (or a `src/build.fan` that names Haxall) | every pod under `src/`, at any depth (`src/core/haystack`), with its `fan/` and `test/` folders |
| SkySpark | `lib/fan/skyarcd.pod` and `bin/fan` | stubs read from its compiled pods |

**Add folder** adds an install from anywhere, such as a SkySpark dev build.
It shows as "(custom folder)".

**The tick.** Ticked installs feed the index. Each ticked install brings its
paired Fantom's `sys` sources. When two installs have a pod of the same name,
the default build target's copy wins.

**The Fantom column.** Haxall and SkySpark ship no `sys` sources, so each is
paired with a Fantom install:

- SkySpark: from `lib/fan/sys.pod`. Its version is the Fantom version plus a
  build number, so SkySpark 4.0.7 ships `1.0.84.4007` and pairs with Fantom
  1.0.84.
- Haxall: it does not record its Fantom version. Haxall 4.0.7 takes the Fantom
  of SkySpark 4.0.7 when that SkySpark is found. If not, pick one.
- Fantom: paired with itself. Its version comes from `sys.pod`, or from the
  folder name `fantom-<version>` when it has no `sys.pod`.

"(auto)" marks an automatic match. Pick another Fantom in the dropdown; pick
"(auto)" to go back. A warning such as "Fantom 1.0.76 missing" means the
Fantom it needs is not installed. You cannot build for that install until you
pick one.

**Default build target.** Run configurations, tests, compile on save and the
index use this install. SkySpark runs its own `bin/fan`. Haxall has no
`bin/fan`, so it runs its paired Fantom's.

Changing the table reindexes the library. Each pod shows up by name under
External Libraries in the Project view.

**From an older version.** If you set a single Fantom home before, the page
opens with that home as the one ticked install and the default target. A
Fantom sources folder you set becomes its chosen Fantom. Nothing changes until
you click Apply.

## Compile on save

The checkbox runs `bin/fan build.fan compile` for the pod of a `.fan` file
when you save it, with the default build target. The compiler's errors and
warnings show up in the editor with the exact message. The compile runs in a
temp folder with its own `fan.props`, so the pod file goes there and never
into an install. While a fresh compiler result exists, the "Unresolved
reference" inspection stays quiet for that file.

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
