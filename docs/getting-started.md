# Getting started

## Install

1. In IntelliJ IDEA (2026.3 or newer), open Settings | Plugins, search for
   **Project Sandstar Fantom**, and install it.
2. Restart the IDE when asked.

To install a build instead, download `fantom-lang-<version>.zip` from the
[releases page](https://github.com/Project-SandStar/fantom-lang-intellij/releases)
and use Settings | Plugins | Install Plugin from Disk.

## Point the plugin at Fantom

The plugin reads the Fantom standard library from a Fantom installation, so
that `Str`, `List`, `echo` and every other `sys` name resolves, completes and
shows documentation.

Open Settings | Languages & Frameworks | Fantom. The page lists every install
under `~/fantom`, `~/haxall` and `~/skyspark`. Change the root folders if your
installs live elsewhere, or use **Add folder**. Tick the installs you work
with and pick the default build target.

Until you tick one, the plugin uses `FAN_HOME`, else the newest
`fantom-<version>` folder under `~/fantom`. If none is found, a balloon says so
the first time a project with `.fan` files opens; click Configure to open the
page.

### Haxall or SkySpark

Haxall and SkySpark ship no `sys` sources, so the plugin pairs each with a
plain Fantom install. SkySpark 4.0.7 pairs with Fantom 1.0.84 on its own; the
Fantom column shows the match and lets you pick another.

- Haxall: every pod under `src/` is indexed, so `haystack::Etc` and
  `xeto::Dict` resolve and complete next to `sys::Str`.
- SkySpark: the plugin reads its compiled pods. Runs and builds use SkySpark's
  own `bin/fan`.

See [Settings](settings.md) for details.

## Open a pod

Open the folder that holds `build.fan`, or any folder with `fan/*.fan` files.
The plugin reads the pod name from `build.fan` (`podName = "..."`) or from the
folder above `fan/`.

## First things to try

- Type `.` after any value and press Ctrl+Space.
- Hover a name, or press F1, for its documentation.
- Press Cmd+B (Ctrl+B) on `Str` to open the real `Str.fan`.
- Press Shift+F6 on a method to rename it everywhere.
- Press Cmd+Alt+L (Ctrl+Alt+L) to reformat a file.
- Click the green arrow next to `main` or a `Test` class to run it.
