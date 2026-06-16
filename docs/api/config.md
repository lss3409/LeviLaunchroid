# Config API

## Purpose

The Config API provides typed JSON configuration for native mods. It is designed
for user-editable settings that should survive mod updates while still gaining
new default fields and launcher UI metadata.

Use it when a mod needs:

- `config/config.json` created from C++ defaults.
- old user values merged into a newer default layout.
- a normalized JSON file written back after load.
- `config/config.schema.json` generated for LeviLauncher's config editor.

## Header

```cpp
#include <pl/cpp/Config.hpp>
```

The API uses aggregate reflection, so config structs should be simple data
types with public fields and default member initializers.

## File Layout

By default, `pl::config::ConfigFile<T>` reads and writes:

```text
<mod root>/
└── config/
    ├── config.json
    └── config.schema.json
```

`config.json` stores the user-editable values. `config.schema.json` is consumed
by the launcher config editor for titles, descriptions, enum choices, numeric
ranges, and read-only fields.

## Define a Config

`T` must be an aggregate type with an integral `version` field.

```cpp
#include <pl/cpp/Config.hpp>

#include <string>
#include <vector>

enum class Profile {
  Quiet,
  Balanced,
  Verbose,
};

struct HudConfig {
  bool showMessage = true;
  std::string message = "Hello from config";
  double scale = 1.25;
};

struct FeatureConfig {
  std::string name = "logger";
  bool enabled = true;
  int weight = 1;
};

struct ModConfig {
  int version = 1;
  bool enabled = true;
  Profile profile = Profile::Balanced;
  HudConfig hud;
  std::vector<FeatureConfig> features = {
      {"logger", true, 1},
      {"overlay", true, 2},
  };
};
```

Field order in the generated JSON follows the aggregate field order. Fields
whose names start with `$` are ignored by reflection.

## Load and Save

Create a `ConfigFile<T>` in `load()`, call `load()`, and keep the typed value in
your mod state.

```cpp
class MyMod {
public:
  bool load();
  bool enable();

private:
  ModConfig config;
};

bool MyMod::load() {
  pl::config::ConfigFile<ModConfig> configFile;
  if (!configFile.load()) {
    getSelf().getLogger().warn("Failed to load config");
    return false;
  }

  config = configFile.value();
  return true;
}

bool MyMod::enable() {
  if (!config.enabled)
    return true;

  getSelf().getLogger().info("Profile is active");
  return true;
}
```

To save runtime changes:

```cpp
config.enabled = false;

pl::config::ConfigFile<ModConfig> configFile{config};
configFile.save();
```

For custom paths, pass them to the constructor:

```cpp
auto configPath = getSelf().getConfigDir() / "advanced.json";
auto schemaPath = getSelf().getConfigDir() / "advanced.schema.json";

pl::config::ConfigFile<ModConfig> configFile{
    ModConfig{},
    configPath,
    schemaPath,
};
```

## Update Behavior

`ConfigFile<T>::load()` always starts from the C++ default value, then merges
the existing JSON into that default layout.

This means:

- missing files are created automatically.
- new fields are added with their C++ defaults.
- existing user values are preserved when their type can be deserialized.
- `version` is forced back to the C++ default `version`.
- the normalized JSON layout is written back when the file is missing, outdated,
  malformed, or incomplete.
- unknown keys are not kept after normalization.

If parsing fails, the default config is used and written back.

## Schema Metadata

The schema generator can infer `type`, `default`, nested `properties`, array
`items`, and enum values. Add user-facing metadata with
`pl::config::Schema<T>` specializations.

```cpp
template <> struct pl::config::Schema<ModConfig> {
  static constexpr std::string_view title = "Example Config";
  static constexpr std::string_view description =
      "Settings for the example native mod.";

  static constexpr FieldSchema field(std::string_view name) {
    if (name == "version")
      return {.title = "Version", .readOnly = true};
    if (name == "enabled")
      return {.title = "Enabled",
              .description = "Turns the mod behavior on or off."};
    if (name == "profile")
      return {.title = "Profile",
              .description = "Selects the runtime behavior preset."};
    return {};
  }
};

template <> struct pl::config::Schema<HudConfig> {
  static constexpr FieldSchema field(std::string_view name) {
    if (name == "showMessage")
      return {.title = "Show Message"};
    if (name == "message")
      return {.title = "Message"};
    if (name == "scale")
      return {.title = "Scale", .minimum = 0.5, .maximum = 3.0};
    return {};
  }
};
```

Supported metadata fields:

| Field | Schema output | Purpose |
| --- | --- | --- |
| `title` | `title` | Human-readable field name. |
| `description` | `description` | Help text shown by the editor. |
| `minimum` | `minimum` | Lower numeric bound. |
| `maximum` | `maximum` | Upper numeric bound. |
| `readOnly` | `readOnly` | Marks generated or informational fields. |

The launcher currently consumes a focused JSON Schema subset: `title`,
`description`, `type`, `default`, `enum`, `minimum`, `maximum`, `readOnly`,
`properties`, and `items`.

## Generated Schema

For the example above, the `Profile` enum becomes a string field with choices:

```json
{
  "type": "string",
  "enum": ["Quiet", "Balanced", "Verbose"],
  "default": "Balanced",
  "title": "Profile"
}
```

Nested aggregates become object schemas, and vectors become array schemas:

```json
{
  "type": "array",
  "items": {
    "type": "object",
    "properties": {
      "name": { "type": "string", "default": "logger" },
      "enabled": { "type": "boolean", "default": true },
      "weight": { "type": "integer", "default": 1 }
    }
  }
}
```

## Supported Types

The typed reflection layer supports:

| C++ type | JSON representation |
| --- | --- |
| aggregate struct | object |
| `std::string` | string |
| `bool` | boolean |
| integral types | integer |
| floating point types | number |
| enum | string enum when `magic_enum` can name the value |
| `std::vector<T>` | array |
| `std::optional<T>` | value or `null` |

Prefer simple config structs. Avoid custom constructors, private fields, and
logic-heavy config objects.

## Build-Time Generation

The Android mod template includes a host-side config generator. The package
script runs it before Android compilation, then copies the generated
`config.json` and `config.schema.json` into the `.levipack`.

```powershell
./scripts/package.ps1 -Abi arm64-v8a
```

This lets the launcher show an editable config immediately after the mod is
imported, before the native library is loaded for the first time.

## Compatibility Notes

- Existing weak JSON helpers such as `pl::config::loadConfig(defaults)` and
  `pl::config::saveConfig(value)` remain available.
- `ConfigFile<T>` is a C++ API only; no C ABI is exported.
- Use `version` for your own config layout version. Increase it when default
  structure or field meaning changes.
- Keep schema metadata concise. It is UI text, not a replacement for full
  documentation.
