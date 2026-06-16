# Native Mod 快速开始

本页面向为 LeviLauncher 编写 preload-native mod 的开发者。普通启动器用户应从 [快速开始](/zh-CN/guide/getting-started) 阅读。

## 加载流程

Preloader 会读取 mod 目录中的 `manifest.json`，加载 `entry` 指向的 `.so`，查找导出符号 `LeviMod_Load` 并调用它。

## 目录结构

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

| 字段 | 作用 | 必填 |
| --- | --- | --- |
| `type` | 必须是 `preload-native`。 | 是 |
| `entry` | mod 入口 `.so` 的安全相对路径。 | 是 |
| `name` | 显示名称；为空时使用目录名。 | 否 |
| `author` | 作者。 | 否 |
| `version` | 版本。 | 否 |
| `icon` | 图标相对路径；无效时会被忽略。 | 否 |
| `minecraft_versions` | 兼容的 Minecraft 版本。支持精确字符串和 `*` 前缀通配。缺失或为空表示兼容所有版本。 | 否 |

## C 入口示例

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

## C++ 入口示例

```cpp
#include <android/log.h>
#include <pl/cpp/Mod.hpp>

extern "C" void LeviMod_Load(JavaVM *vm, const PLModInfo *mod_info) {
  (void)vm;
  __android_log_print(ANDROID_LOG_INFO, "ExampleMod", "Loaded %s",
                      mod_info ? mod_info->mod_id : "unknown");
}
```

## 构建 mod

```cmake
cmake_minimum_required(VERSION 3.22)
project(example_mod LANGUAGES C CXX)

add_library(example SHARED src/example.cpp)

target_include_directories(example PRIVATE
    path/to/preloader/src)
```

使用 Android NDK toolchain，并构建受支持的 ARM ABI。

## 常见错误

- `LeviMod_Load` 没有用 C ABI 导出：C++ mod 必须写 `extern "C"`。
- `manifest.json` 的 `type` 不是 `preload-native`。
- `entry` 是绝对路径、包含 `..`，或没有指向 `.so`。
- mod 构建成了不支持的 ABI。

最小 mod 能正常加载后，再继续阅读 [Preloader API 参考](/zh-CN/api/mod)。
