# Mod 入口 API

## 作用

Mod 入口 API 定义 preloader 如何把 mod 元数据和 `JavaVM` 传给 native mod。每个 native mod 必须导出 `LeviMod_Load`。

## 头文件

C:

```c
#include <pl/c/Mod.h>
```

C++:

```cpp
#include <pl/cpp/Mod.hpp>
```

## 类型签名

```c
typedef struct PLModInfo {
  uint32_t size;
  const char *mod_id;
  const char *display_name;
  const char *author;
  const char *version;
  const char *entry_path;
  const char *entry_file_name;
  const char *library_path;
  const char *icon_path;
  const char *manifest_path;
  const char *mod_root_path;
} PLModInfo;

typedef void (*PLModLoadFunc)(JavaVM *vm, const PLModInfo *mod_info);
```

mod 需要导出：

```c
void LeviMod_Load(JavaVM *vm, const PLModInfo *mod_info);
```

C++ mod 可以使用 `pl::mod` 自动生成这个入口：

```cpp
#include "mod/MyMod.h"
#include <pl/cpp/mod/RegisterHelper.hpp>

PL_REGISTER_MOD(my_mod::MyMod, my_mod::MyMod::getInstance());
```

`PL_REGISTER_MOD` 会在加载时先调用 `load()`，成功后再调用 `enable()`。
`disable()` 和 `unload()` 可以先绑定，供未来 launcher 暴露 native 生命周期事件时使用；
当前 Java 侧加载流程不会主动触发它们。

## 字段说明

| 字段 | 作用 |
| --- | --- |
| `size` | 当前结构体大小，用于兼容性判断 |
| `mod_id` | mod 目录名 |
| `display_name` | manifest 中的 `name`，为空时使用目录名 |
| `author` | manifest 中的 `author` |
| `version` | manifest 中的 `version` |
| `entry_path` | manifest 中的入口相对路径 |
| `entry_file_name` | 入口 `.so` 文件名 |
| `library_path` | 实际加载的 `.so` 路径 |
| `icon_path` | manifest 中有效的图标相对路径，可能为空 |
| `manifest_path` | manifest 文件路径 |
| `mod_root_path` | mod 根目录路径 |

## 参数

| 参数 | 说明 |
| --- | --- |
| `vm` | 当前进程的 `JavaVM *` |
| `mod_info` | 当前 mod 的元数据，可能为 `NULL` 时应防御处理 |

## 返回值

`LeviMod_Load` 没有返回值。加载成功与否由 preloader 的 `.so` 加载和符号解析过程决定。

## 最小示例

```c
#include <pl/c/Mod.h>

void LeviMod_Load(JavaVM *vm, const PLModInfo *mod_info) {
  (void)vm;
  if (!mod_info || mod_info->size < sizeof(PLModInfo)) {
    return;
  }

  const char *id = mod_info->mod_id;
  (void)id;
}
```

## 注意事项

- `PLModInfo` 内的字符串由 preloader 持有，只保证在 `LeviMod_Load` 调用期间可安全读取。需要长期保存时请复制字符串。
- `mod_root_path`、`manifest_path` 和 `library_path` 指向当前 profile 的
  `mods` 目录下的原始 mod 包位置。LeviLauncher 可能从运行时 cache 加载暂存副本，
  但公开给 mod 的元数据仍然以原始包位置为准。
- C++ mod 必须用 `extern "C"` 导出 `LeviMod_Load`，否则符号名会被 C++ name mangling 改掉。
- 不要修改 `PLModInfo` 指针指向的数据。
