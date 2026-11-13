# Settings

## Fantom installation

Settings | Languages & Frameworks | Fantom holds the path of the Fantom
installation. Leave it empty to use `FAN_HOME` or the newest `~/fantom/fantom-*`
folder. The field turns red when the folder has no `bin/fan` or `src/`.

Changing the path reindexes the library. The pod sources under
`src/<pod>/fan` show up under External Libraries in the Project view.

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
