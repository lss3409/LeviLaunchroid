# Mod API

## Purpose

The Mod API provides the `MyMod` lifecycle style used by LeviLauncher native
mods. New mods should use the C++ template and `PL_REGISTER_MOD`.

## Headers

```cpp
#include <pl/cpp/Mod.hpp>
#include <pl/cpp/mod/RegisterHelper.hpp>
```

Use the typed config helpers from:

```cpp
#include <pl/cpp/Config.hpp>
```

## Register a Mod

```cpp
#include "mod/MyMod.h"
#include <pl/cpp/mod/RegisterHelper.hpp>

PL_REGISTER_MOD(my_mod::MyMod, my_mod::MyMod::getInstance());
```

`MyMod` should provide these methods:

```cpp
class MyMod {
public:
  static MyMod &getInstance();

  bool load();
  bool enable();
  bool disable();
  bool unload();
};
```

`unload()` is optional. Add it when the mod owns resources that should be
released during shutdown.

## Lifecycle

| Method | When it runs |
| --- | --- |
| `load()` | The mod is loaded. |
| `enable()` | The game is about to start. |
| `disable()` | The game is closing. |
| `unload()` | The mod is doing final cleanup. |

Each method should return `true` when it succeeds and `false` when it fails.

## NativeMod

Use `getSelf()` in your mod class to access the current mod object:

```cpp
pl::mod::NativeMod &MyMod::getSelf() const {
  return *pl::mod::NativeMod::current();
}
```

Common methods:

| Method | Purpose |
| --- | --- |
| `getLogger()` | Logger dedicated to this mod. |
| `getId()` | Mod id. |
| `getName()` | Display name. |
| `getAuthor()` | Author from manifest. |
| `getVersion()` | Version from manifest. |
| `getModDir()` | Mod package directory. |
| `getDataDir()` | Directory for mod data files. |
| `getConfigDir()` | Directory for mod configuration files. |
| `getResourceDir()` | Directory for bundled resource files. |
| `getManifestPath()` | Manifest file path. |
| `getLibraryPath()` | Mod library path. |
| `getJavaVM()` | Current Java VM pointer. |

## Example

```cpp
bool MyMod::load() {
  auto &self = getSelf();
  self.getLogger().info("Loading {}", self.getName());

  std::filesystem::create_directories(self.getDataDir());
  std::filesystem::create_directories(self.getConfigDir());
  return true;
}

bool MyMod::enable() {
  getSelf().getLogger().info("Enabled");
  return true;
}

bool MyMod::disable() {
  getSelf().getLogger().info("Disabled");
  return true;
}

bool MyMod::unload() {
  getSelf().getLogger().info("Unloaded");
  return true;
}
```

## Config

Use `pl::config::ConfigFile<T>` for typed JSON config files, automatic default
layout updates, and launcher-editable schema generation. See the
[Config API Reference](/api/config).

## Notes

- Store mod data in `getDataDir()`.
- Store user-editable configuration in `getConfigDir()`, or use
  `pl::config::ConfigFile<T>` for typed JSON config.
- Keep `load()` lightweight and move game-facing work to `enable()` when possible.
- Clean up resources in the reverse order: `disable()` first, then `unload()`.
