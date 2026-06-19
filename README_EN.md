# Chexson's AE Utils

[中文 README](README.md)

`Chexson's AE Utils` is a utility add-on mod for Applied Energistics 2. The current target stack is `Minecraft 1.21.1 + NeoForge 21.1.222 + AE2 19.2.17 + Java 21`. It adds a multi-slot ME emitter, processing pattern replacement rules, ignore-missing autocrafting continuation, and the migrated AEA dyeable pattern, enhanced crafting status, Building Gadgets 2, and FTB Ultimine compatibility features.

This repository is the `Minecraft 1.21.1 + NeoForge` migration branch. It is not a direct release note for the old `1.20.1 + Forge` branch. Treat the actual build artifact, changelog, and verification results as the source of truth for releases.

## AI Authorship Notice

This mod's code, documentation, and some assets were primarily written, migrated, and organized by AI under developer direction, with human review and selection. Treat it as independently verifiable software: back up important worlds, confirm dependency versions, and test core automation workflows before using it in production servers or valuable saves.

## Versions And Dependencies

- Minecraft: `1.21.1`
- NeoForge: `21.1.222` or a compatible newer `21.1.x` build
- Applied Energistics 2: `19.2.17` or a compatible newer `19.2.x` build
- Java: `21`
- Mod ID: `chexsonsaeutils`
- License: `MIT`

This branch integrates with current NeoForge and AE2 APIs, menus, mixins, and runtime behavior. Re-run tests and in-game verification after updating AE2, NeoForge, or Minecraft.

## Features

### Migrated AEA Features

The current branch keeps the following AEA migrations:

- `Dyeable Patterns`:
  AE2 encoded patterns can be dyed.
  A built-in client resource pack overrides pattern models and textures.
  Planning prefers same-color patterns while preserving the existing replacement, continuation, formal-machine, and parallel CPU main-path semantics.
- `Enhanced Crafting Status`:
  Adds `Blocked` and `Pattern Times` data to crafting status and Craft Confirm views.
  This only extends status data and serialization, without replacing the existing continuation, formal-machine, or parallel CPU UI behavior in this repository.
- `Building Gadgets 2 Integration`:
  Encodes BG2 template material requirements into standard AE2 processing patterns.
  Generated patterns stay standard AE2 processing patterns and do not inject replacement metadata.
- `FTB Ultimine & Memory Card Compatibility`:
  FTB Ultimine bulk right-click flows can reuse AE memory-card targets.
  When FTB Ultimine is absent, the corresponding mixin stays unloaded.

### ME Multi-Level Emitter

The `ME Multi-Level Emitter` extends AE2's level emitter semantics. Its item ID is `chexsonsaeutils:multi_level_emitter`. It is still placed as an AE2 part on a compatible network side, but one part can manage multiple monitored slots.

Main capabilities:

- Configure up to `64` monitored slots.
- Each slot can have its own marked item, threshold, comparison mode, and fuzzy matching mode.
- Crafting-card-aware slot behavior is supported.
- Expression logic is supported, for example `#1 OR (#2 AND #3)`.
- Expressions support `AND`, `OR`, and parentheses, with validation feedback for invalid syntax, out-of-range slots, and unmarked slots.
- Slot count, thresholds, comparison modes, fuzzy matching modes, crafting modes, and expression text are persisted to part NBT.

The recipe is shapeless:

- `ae2:level_emitter`
- `ae2:logic_processor`
- `ae2:engineering_processor`

The result is `chexsonsaeutils:multi_level_emitter`.

### Processing Pattern Replacement Rules

Processing pattern replacement rules allow AE2 processing pattern input slots to store alternative input selections. This is useful when a processing workflow can accept item tags or explicitly selected equivalent items.

Main capabilities:

- Configure rules for processing input slots in AE2's pattern encoding terminal.
- Select shared item tag groups or explicit single items.
- Persist replacement rules into encoded pattern metadata under `chexsonsaeutils_processing_replacements`.
- Restore replacement-aware semantics when encoded patterns are decoded.
- Choose available replacement candidates during planning and execution instead of always using the single originally encoded input.
- Show unconfigured, configured, and partially invalid states in the UI to help diagnose tag or item changes.

### Crafting Continuation And Ignore Missing

Crafting continuation extends AE2's craft confirmation flow with a task-level mode switch between `Default` and `Ignore Missing`.

Main capabilities:

- Toggle crafting mode in the Craft Confirm screen.
- `Default` preserves AE2's normal behavior.
- `Ignore Missing` submits branches that can run and leaves only missing-input branches waiting.
- Crafting CPU menus and status views keep waiting amounts, waiting branches, and running summaries visible.
- When combined with the multi-level emitter's crafting-state behavior, this enables finer-grained stock and waiting automation.

## Configuration

The common config file is:

```text
config/chexsonsaeutils-common.toml
```

Current options:

```toml
craftingContinuationEnabled = true
formalMachineCraftingDispatchEnabled = false
formalMachinePlanningAggregationEnabled = true
processingPatternReplacementEnabled = true

[aeaMigration]
dyeablePatternsEnabled = true
dyeableRecursiveRetainedCatalystAmount = 1
enhancedCraftingStatusEnabled = true
buildingGadgets2IntegrationEnabled = true
ftbUltimineMemoryCardEnabled = true
```

- `craftingContinuationEnabled`: enables or disables the AE2 crafting continuation / ignore-missing feature bundle.
- `formalMachineCraftingDispatchEnabled`: enables or disables the formal-machine AE2 crafting dispatch fast path.
- `formalMachinePlanningAggregationEnabled`: enables or disables the formal-machine large-request planning aggregation fast path.
- `processingPatternReplacementEnabled`: enables or disables the AE2 processing pattern replacement feature bundle.
- `[aeaMigration].dyeablePatternsEnabled`: enables or disables the migrated AEA dyeable pattern feature.
  When disabled, dyeable-pattern mixins, item color handling, and the built-in client resource pack registration are all skipped.
- `[aeaMigration].dyeableRecursiveRetainedCatalystAmount`: sets how many recursive dyeable-pattern catalysts should remain after planning.
- `[aeaMigration].enhancedCraftingStatusEnabled`: enables or disables the migrated AEA enhanced crafting status feature.
- `[aeaMigration].buildingGadgets2IntegrationEnabled`: enables or disables the migrated AEA Building Gadgets 2 integration.
- `[aeaMigration].ftbUltimineMemoryCardEnabled`: enables or disables the migrated AEA FTB Ultimine memory-card compatibility.

All of these options are read at startup. Restart the game or server after changing them.

## Installation

1. Install Minecraft `1.21.1`, NeoForge `21.1.222+`, and Java `21`.
2. Install Applied Energistics 2 `19.2.17+`.
3. Put this mod's jar into the `mods` directory.
4. Check `config/chexsonsaeutils-common.toml` after the first launch.
5. Test the multi-level emitter, processing pattern replacement, crafting continuation, and migrated AEA features in a test world before using them in an important world or server.

For server deployments, validate the mod on a staging server before moving it to a production save.

## Development

This repository uses the Gradle Wrapper. Common commands:

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat runClient
.\gradlew.bat runServer
```

Command notes:

- `build`: compiles, processes resources, and packages the mod.
- `test`: runs the current JUnit tests.
- `runClient` / `runServer`: starts a development client or server for in-game verification.

To keep Gradle caches inside the repository during local work:

```powershell
$env:GRADLE_USER_HOME = (Join-Path (Get-Location) '.gradle-user')
.\gradlew.bat test
```

## Repository Layout

- `src/main/java/`: mod logic, AE2 integration, mixins, menus, and runtime behavior.
- `src/main/resources/`: metadata, assets, language files, textures, and data pack entries.
- `src/main/templates/META-INF/neoforge.mods.toml`: NeoForge mod metadata template.
- `src/main/resources/assets/chexsonsaeutils/lang/`: English and Chinese language files.
- `src/main/resources/data/chexsonsaeutils/recipes/`: mod recipes.
- `config/chexsonsaeutils-common.toml`: common config example for the development environment.

Project files are expected to use UTF-8 encoding and CRLF line endings.

## Compatibility And Limitations

- This mod is not an official AE2 project and does not imply official AE2 compatibility support.
- The current branch targets `Minecraft 1.21.1 + NeoForge + AE2 19`.
- Features depend on AE2 menu, pattern, crafting service, and part-system behavior; AE2 updates may require compatibility work.
- Back up important worlds before relying on modified processing pattern metadata or multi-level emitter NBT.
- If a modpack also changes AE2 crafting, patterns, or terminal screens, test those interactions carefully.
- The BG2 and FTB Ultimine integrations are optional dependency paths.
  When the corresponding mod is absent, this mod should still start and must not load the related mixins.

## Issue Reports

Useful issue reports should include:

- Minecraft, NeoForge, AE2, and Chexson's AE Utils versions.
- Whether the issue happened on client, integrated server, or dedicated server.
- Full logs and crash reports.
- Reproduction steps and the relevant pattern or emitter configuration.
- Relevant options from `config/chexsonsaeutils-common.toml`.

## License

This project is licensed under `MIT`. See the repository license file or release page for details.
