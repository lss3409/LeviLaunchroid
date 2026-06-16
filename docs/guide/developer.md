# Native Mod Quick Start

This page is for developers building preload-native mods for LeviLauncher. Regular launcher users should start with [Getting Started](/guide/getting-started).

## How Loading Works

Preloader reads `manifest.json` from a mod directory, loads the `.so` pointed to by `entry`, finds the exported `LeviMod_Load` symbol, and calls it.

## Directory Layout

```text
example-mod/
├── manifest.json
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
| `entry` | Safe relative path to the mod entry `.so`. | Yes |
| `name` | Display name. The directory name is used when empty. | No |
| `author` | Author. | No |
| `version` | Mod version. | No |
| `icon` | Relative icon path. Invalid paths are ignored. | No |
| `minecraft_versions` | Compatible Minecraft versions. Exact strings and `*` prefix wildcards are supported. Missing or empty means all versions. | No |

## C Entry Example

```c
#include <android/log.h>
#include <pl/c/Mod.h>

void LeviMod_Load(JavaVM *vm, const PLModInfo *mod_info) {
  (void)vm;

  const char *name = mod_info && mod_info->display_name
                         ? mod_info->display_name
                         : "unknown";
  __android_log_print(ANDROID_LOG_INFO, "ExampleMod", "Loaded %s", name);
}
```

## C++ Entry Example

```cpp
#include <android/log.h>
#include <pl/cpp/Mod.hpp>

extern "C" void LeviMod_Load(JavaVM *vm, const PLModInfo *mod_info) {
  (void)vm;
  __android_log_print(ANDROID_LOG_INFO, "ExampleMod", "Loaded %s",
                      mod_info ? mod_info->mod_id : "unknown");
}
```

## Build a Mod

```cmake
cmake_minimum_required(VERSION 3.22)
project(example_mod LANGUAGES C CXX)

add_library(example SHARED src/example.cpp)

target_include_directories(example PRIVATE
    path/to/preloader/src)
```

Use the Android NDK toolchain and build for a supported ARM ABI.

## Common Errors

- `LeviMod_Load` is not exported with C ABI. C++ mods must use `extern "C"`.
- `manifest.json` has a `type` other than `preload-native`.
- `entry` is absolute, contains `..`, or does not point to a `.so`.
- The mod was built for an unsupported ABI.

Continue with the [Preloader API Reference](/api/mod) when the minimal mod loads correctly.
