# Phase 1 Patch 01

This patch performs only the confirmed source-set cleanup required before the Minecraft 26.2 API port.

## Changed

- Restored Elytra sources to the root Gradle client source set.
- Removed `ExampleBaritoneControl` and its runtime registration.
- Removed Schematica and Litematica commands.
- Removed the Litematica schematic format implementation while preserving Baritone's generic builder and the MCEdit/Sponge formats.
- Updated GitHub Actions to save a complete plain-text Gradle build log on every run, including failures.

## Not claimed

This patch is not expected to complete the Minecraft 26.2 API migration. Its purpose is to make the source tree internally consistent and produce a reliable next compiler report.
