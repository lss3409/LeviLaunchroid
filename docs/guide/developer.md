# Native Mod Quick Start

This page is for developers building native mods for LeviLauncher. Regular
launcher users should start with [Getting Started](/guide/getting-started).

## Recommended Template

Start from the LeviLauncher Android mod template. It provides a ready-to-build
CMake project, a sample `MyMod` class, manifest configuration, packaging script,
and CI workflow.

Your mod logic normally lives in `src/mod/MyMod.cpp`.

## Directory Layout

```text
example-mod/
├── manifest.json
├── config/
│   ├── config.json
│   └── config.schema.json
└── libexample.so
```

## manifest.json

```json
{
  "type": "preload-native",
  "name": "Example Mod",
  "author": "LiteLDev",
  "version": "1.0.0",
  "entry": "libexample.so",
  "minecraft_versions": ["1.26.20", "1.26.2*", "1.26.*"]
}
```

| Field | Purpose | Required |
| --- | --- | --- |
| `type` | Must be `preload-native`. | Yes |
| `entry` | Relative path to the mod library. | Yes |
| `name` | Display name. | No |
| `author` | Author. | No |
| `version` | Mod version. | No |
| `icon` | Relative icon path. | No |
| `minecraft_versions` | Compatible Minecraft versions. Exact strings and `*` prefix wildcards are supported. Missing or empty means all versions. | No |

## MyMod Example

```cpp
bool MyMod::load() {
  getSelf().getLogger().info("Loaded {}", getSelf().getName());
  return true;
}

bool MyMod::enable() {
  return true;
}

bool MyMod::disable() {
  return true;
}

bool MyMod::unload() {
  return true;
}
```

Lifecycle timing:

1. `load()` runs when the mod is loaded.
2. `enable()` runs before the game starts.
3. `disable()` runs when the game is closing.
4. `unload()` runs during final mod cleanup.

## Useful APIs

- `getLogger()`
- `getId()`
- `getName()`
- `getModDir()`
- `getDataDir()`
- `getConfigDir()`
- `getResourceDir()`
- `getManifestPath()`
- `getLibraryPath()`
- `getJavaVM()`

Use `getDataDir()` and `getConfigDir()` for mod-owned files.

For user-editable JSON settings, prefer `pl::config::ConfigFile<T>`. The
template demonstrates a typed aggregate config, automatic default
`config.json` generation, and `config.schema.json` generation for the launcher
config editor.

## Build a Mod

Use the Android NDK toolchain and build for `arm64-v8a` or `armeabi-v7a`.

The template package script builds the mod, generates config files, and packages
them into the `.levipack`:

```powershell
./scripts/package.ps1 -Abi arm64-v8a
```

Import the generated `.levipack` into LeviLauncher.

## Common Errors

- `manifest.json` has a `type` other than `preload-native`.
- `entry` is empty, absolute, or does not point to a `.so`.
- The mod was built for an unsupported Android architecture.
- The selected Minecraft version is not listed in `minecraft_versions`.

Continue with the [Mod API Reference](/api/mod) when the minimal mod loads correctly.
