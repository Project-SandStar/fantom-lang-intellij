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

**Haxall column (SkySpark rows).** SkySpark ships compiled pods only. Each
SkySpark row is paired with a Haxall whose source stands in for them
(haystack, folio, axon, xeto, hx). The match is by equal version: SkySpark
4.0.7 takes Haxall 4.0.7. If there is no Haxall of that version (a 4.0.8 dev
folder, say), the nearest lower one is used, with a warning such as "no Haxall
4.0.8; using Haxall 4.0.7". Pick another Haxall in the dropdown, or "(auto)"
to go back.

**Which copy of a pod wins.** For each pod name, the first source found is
used:

1. the open project, when it defines the pod itself;
2. a source you pinned in "Dependencies of this project";
3. the ticked installs, default target first: the install's own sources, then
   its paired Haxall's source, then its paired Fantom's;
4. a project pod found under the source roots (see below);
5. a stub made from the compiled `.pod` file.

So with SkySpark as the target, `haystack` comes from Haxall source, not from a
stub.

**The status bar.** "Fantom: SkySpark 4.0.7" shows the default build target.
Click it to pick another ticked install. Picking a target in the Build Pod
menu also makes it the default. Each switch picks the Haxall and the tags
again and reindexes.

Changing the table reindexes the library. Each pod shows up by name under
External Libraries in the Project view.

**From an older version.** If you set a single Fantom home before, the page
opens with that home as the one ticked install and the default target. A
Fantom sources folder you set becomes its chosen Fantom. Nothing changes until
you click Apply.

## Source roots for project pods

Your project can depend on pods from your other repositories, for example
`bassgCommon 3.0+`. List the folders that hold them here. The default is
`~/Code`. Click Add to add a folder and Rescan to look again.

The scan runs in the background and reads only `build.fan` files and git
data. The result is kept until you click Rescan. Copies of one repository are
grouped by their git remote path. The host and a `.git` ending do not count,
so `https://gitlab.bas.co/team/repo` and `git@gitlab.com:team/repo.git` are
the same repository.

**Which version.** Release tags look like `<pod>/<SkySpark version>.<API level>`,
for example `bassgCommon/4.0.6.2.2`, or bare, like `3.0.27.1`. For the default
build target:

1. a tag of the same SkySpark version, highest API level;
2. else the nearest lower SkySpark version, with a warning such as
   "no 4.0.7 tag; using bassgCommon/4.0.6.2.2";
3. else, for a repository with no usable tag, the working copy, labelled as
   such. A plain Fantom target always uses the working copy.

Tags of the other clones of the same repository count too. Tags of another
pod in the same repository are ignored.

The plugin never checks out a tag in your folder. It copies the tagged files
(`git archive`) into the IDE's system folder, under `fantom-tag-cache`, and
marks them read-only. It copies a tag again only when the tag moves to
another commit.

**`depends` limits.** A copy whose version does not meet the `depends` line is
skipped: `bassgCommon 3.0+` takes any 3.x or higher. This applies to copies
under the source roots only; install and Haxall sources are always used.

## Dependencies of this project

This table lists the `depends` of the open project's `build.fan` files, for the
default build target:

| Column | Shows |
|---|---|
| Pod | the pod name |
| Needs | the version in `depends` |
| Source | where the pod comes from, for example "Haxall 4.0.7 source" |
| How | auto, pinned, nearest tag, stub or not found |

Pick another entry in the Source dropdown to pin it: an install source, a tag,
a working copy or the stub. Pick "(auto)" to remove the pin. Pins are kept
per project and apply when you click Apply.

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
