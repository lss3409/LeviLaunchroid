# Mod API

## 作用

Mod API 提供 LeviLauncher native mod 使用的 `MyMod` 生命周期写法。新 mod
建议使用 C++ 模板和 `PL_REGISTER_MOD`。

## 头文件

```cpp
#include <pl/cpp/Mod.hpp>
#include <pl/cpp/mod/RegisterHelper.hpp>
```

强类型配置 helper 使用：

```cpp
#include <pl/cpp/Config.hpp>
```

## 注册模组

```cpp
#include "mod/MyMod.h"
#include <pl/cpp/mod/RegisterHelper.hpp>

PL_REGISTER_MOD(my_mod::MyMod, my_mod::MyMod::getInstance());
```

`MyMod` 应提供这些方法：

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

`unload()` 是可选的。模组持有需要在退出时释放的资源时再添加即可。

## 生命周期

| 方法 | 调用时机 |
| --- | --- |
| `load()` | 模组被加载时。 |
| `enable()` | 游戏即将启动时。 |
| `disable()` | 游戏正在结束时。 |
| `unload()` | 模组进行最终清理时。 |

每个方法成功时返回 `true`，失败时返回 `false`。

## NativeMod

在模组类里通过 `getSelf()` 访问当前模组对象：

```cpp
pl::mod::NativeMod &MyMod::getSelf() const {
  return *pl::mod::NativeMod::current();
}
```

常用方法：

| 方法 | 作用 |
| --- | --- |
| `getLogger()` | 当前模组专属 logger。 |
| `getId()` | 模组 id。 |
| `getName()` | 显示名称。 |
| `getAuthor()` | manifest 中的作者。 |
| `getVersion()` | manifest 中的版本。 |
| `getModDir()` | 模组包目录。 |
| `getDataDir()` | 模组数据文件目录。 |
| `getConfigDir()` | 模组配置文件目录。 |
| `getResourceDir()` | 模组资源文件目录。 |
| `getManifestPath()` | manifest 文件路径。 |
| `getLibraryPath()` | 模组库文件路径。 |
| `getJavaVM()` | 当前 Java VM 指针。 |

## 示例

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

## 配置

使用 `pl::config::ConfigFile<T>` 可以管理强类型 JSON 配置、自动更新默认布局，
并生成启动器可编辑的 schema。详情见 [Config API 参考](/zh-CN/api/config)。

## 注意事项

- 模组数据放到 `getDataDir()`。
- 用户可编辑配置放到 `getConfigDir()`，也可以使用
  `pl::config::ConfigFile<T>` 管理强类型 JSON 配置。
- `load()` 尽量保持轻量，和游戏运行相关的工作优先放到 `enable()`。
- 清理资源时按相反顺序处理：先 `disable()`，再 `unload()`。
