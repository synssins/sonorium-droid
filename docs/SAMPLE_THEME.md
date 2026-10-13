# The sample theme

The app ships one theme that everyone can play straight after installing: offline, with no server and no
themes folder. It is the sample in My channel on the device's own entry.

## Changing it

The sample is one file:

```
app/src/main/assets/sample/sample-theme.zip
```

Replace it with another theme zip and build the app. The zip must look like Sonorium's **Export**
(Themes › a theme's ⋯ › Export theme) makes it:

- one top folder (any name), with the theme's files inside: its audio files (in group folders where it has
  groups), `metadata.json` with the theme's `id` and `name`, and `presets.json` if it has presets or
  sequences;
- no other top folders (only the first folder's files are used).

A theme exported from a Sonorium server can be dropped in as it is.

## What the app does with it

- The first time it's needed (and again after every app update) the zip is unpacked into the app's own
  storage. Nothing is written to the phone's shared storage.
- The sample is read-only. It isn't listed when the device's themes folder holds the same theme (same
  `metadata.json` id), so there is never a second copy.

The code is `app/src/main/java/app/sonorium/local/SampleTheme.kt` (`ASSET` names the file).
