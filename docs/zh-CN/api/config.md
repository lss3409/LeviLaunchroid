# Config API

## 作用

Config API 为 native mod 提供强类型 JSON 配置。它适合用户可编辑、需要跨版本保留，
同时又希望自动获得新默认字段和启动器 UI 元数据的配置。

适合这些场景：

- 从 C++ 默认值创建 `config/config.json`。
- mod 更新后，把旧用户配置合并到新的默认布局上。
- 加载后写回规范化 JSON。
- 自动生成 `config/config.schema.json`，供 LeviLauncher 配置编辑器使用。

## 头文件

```cpp
#include <pl/cpp/Config.hpp>
```

该 API 使用 aggregate 反射，因此配置结构体应保持简单：公开字段、默认成员初始化，
不要把业务逻辑塞进配置对象。

## 文件布局

默认情况下，`pl::config::ConfigFile<T>` 读写：

```text
<mod root>/
└── config/
    ├── config.json
    └── config.schema.json
```

`config.json` 保存用户可编辑的值。`config.schema.json` 被启动器配置编辑器读取，用来
显示标题、描述、枚举选项、数值范围和只读字段。

## 定义配置

`T` 必须是 aggregate 类型，并包含整型 `version` 字段。

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

生成 JSON 时，字段顺序与 aggregate 字段顺序一致。字段名以 `$` 开头时会被反射忽略。

## 加载与保存

通常在 `load()` 中创建 `ConfigFile<T>`，调用 `load()`，然后把强类型值保存到 mod
状态里。

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

如果运行时修改了配置，可以再保存：

```cpp
config.enabled = false;

pl::config::ConfigFile<ModConfig> configFile{config};
configFile.save();
```

如需自定义路径，把配置路径和 schema 路径传入构造函数：

```cpp
auto configPath = getSelf().getConfigDir() / "advanced.json";
auto schemaPath = getSelf().getConfigDir() / "advanced.schema.json";

pl::config::ConfigFile<ModConfig> configFile{
    ModConfig{},
    configPath,
    schemaPath,
};
```

## 更新策略

`ConfigFile<T>::load()` 总是先拿 C++ 默认值作为基底，再把已有 JSON 合并进去。

具体行为：

- 配置文件不存在时自动创建。
- 新增字段会按 C++ 默认值补齐。
- 能正常反序列化的旧用户值会保留。
- `version` 会被强制更新为 C++ 默认配置里的 `version`。
- 文件缺失、版本过旧、格式错误或布局不完整时，会写回规范化 JSON。
- 未知字段在规范化后不会保留。

如果 JSON 解析失败，会使用默认配置并重新写回。

## Schema 元数据

schema 生成器可以自动推断 `type`、`default`、嵌套 `properties`、数组 `items` 和枚举
值。用户界面上的标题、描述、范围等信息通过特化 `pl::config::Schema<T>` 添加。

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

支持的元数据字段：

| 字段 | schema 输出 | 作用 |
| --- | --- | --- |
| `title` | `title` | 人类可读的字段名。 |
| `description` | `description` | 编辑器展示的帮助文本。 |
| `minimum` | `minimum` | 数值下限。 |
| `maximum` | `maximum` | 数值上限。 |
| `readOnly` | `readOnly` | 标记生成字段或说明字段。 |

启动器当前消费的是一个聚焦的 JSON Schema 子集：`title`、`description`、`type`、
`default`、`enum`、`minimum`、`maximum`、`readOnly`、`properties` 和 `items`。

## 生成的 Schema

上面的 `Profile` 枚举会生成字符串枚举字段：

```json
{
  "type": "string",
  "enum": ["Quiet", "Balanced", "Verbose"],
  "default": "Balanced",
  "title": "Profile"
}
```

嵌套 aggregate 会生成 object schema，vector 会生成 array schema：

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

## 支持类型

强类型反射层支持：

| C++ 类型 | JSON 表示 |
| --- | --- |
| aggregate struct | object |
| `std::string` | string |
| `bool` | boolean |
| 整数类型 | integer |
| 浮点类型 | number |
| enum | `magic_enum` 能解析时保存为字符串枚举 |
| `std::vector<T>` | array |
| `std::optional<T>` | 值或 `null` |

建议保持配置结构简单。避免自定义构造函数、私有字段，以及带大量逻辑的配置对象。

## 构建期生成

Android mod template 包含主机端配置生成器。打包脚本会先运行生成器，再进行 Android
编译，并把生成的 `config.json` 和 `config.schema.json` 复制进 `.levipack`。

```powershell
./scripts/package.ps1 -Abi arm64-v8a
```

这样 mod 导入启动器后，即使 native 库还没第一次加载，启动器也能直接展示可编辑配置。

## 兼容性说明

- 旧的弱类型 JSON helper 仍然可用，例如 `pl::config::loadConfig(defaults)` 和
  `pl::config::saveConfig(value)`。
- `ConfigFile<T>` 只提供 C++ API，不导出新的 C ABI。
- `version` 用来表达你的配置布局版本。默认结构或字段含义变化时应递增。
- schema metadata 保持简洁即可。它是 UI 文案，不是完整文档的替代品。
