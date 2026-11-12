# Project Sandstar Fantom IntelliJ Plugin

![Fantom](/images/fantom.png)

Language support for [Fantom](https://fantom-lang.org/) in IntelliJ IDEA and
the other JetBrains IDEs. Part of [Project Sandstar](https://project-sandstar.org).

## What it does

- Parses every Fantom 1.0.78 to 1.0.84 source file without errors
- Syntax and semantic colouring with a configurable colour scheme
- Structure view, Go to Class, Go to Symbol, Go to Declaration into the Fantom library
- Find usages and rename across files
- Type inference with inlay hints; completion ranked by receiver type
- Quick documentation from fandoc, parameter info
- Inspections with quick fixes: unresolved reference, unused declaration, nullable receiver, unused `using`
- Auto-import and Optimize Imports
- Override and implement members; run and test from the gutter with `fan` and `fant`
- Live and postfix templates, indent-aware formatter, typing helpers

## Install

From the JetBrains Marketplace: search for "Project Sandstar Fantom" in
Settings | Plugins.

From a build: `./gradlew buildPlugin`, then Settings | Plugins | Install Plugin
from Disk and pick `build/distributions/fantom-lang-<version>.zip`.

Then set the Fantom installation in Settings | Languages & Frameworks | Fantom.
The plugin also finds `FAN_HOME` or a `fantom-*` folder on its own.

## Build and test

```sh
./gradlew --no-daemon generateLexer generateParser test buildPlugin
./gradlew --no-daemon runIde            # sandbox IDE with the plugin
```

The corpus test runs over a Fantom source tree when `FANTOM_CORPUS` points at
one or more `src` folders, separated by `:`.

## Docs

- [Language layers, the resume document](docs/language-layers.md)
- [Tasks](docs/tasks.md)
- [Grammar work](docs/grammar-work.md)
- [Agent guide](AGENTS.md)

## Origin and license

This plugin started as [Fantom Lang](https://github.com/anboralabs/fantom-lang-intellij)
by Anbora Labs (Daniel Garcia). Project Sandstar rewrote the lexer and parser
and added the language layers listed above, 2026. Their work and ours are
licensed under the [GNU General Public License v3.0](LICENSE).
