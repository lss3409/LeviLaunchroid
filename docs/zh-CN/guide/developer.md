# Native Mod 快速开始

本页面面向为 LeviLauncher 编写 native mod 的开发者。普通启动器用户应从
[快速开始](/zh-CN/guide/getting-started) 阅读。

## 推荐模板

建议从 LeviLauncher Android mod template 开始。模板已经准备好 CMake 项目、
示例 `MyMod` 类、manifest 配置、打包脚本和 CI 工作流。

通常只需要在 `src/mod/MyMod.cpp` 里编写模组逻辑。

## 目录结构

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

| 字段 | 作用 | 必填 |
| --- | --- | --- |
| `type` | 必须是 `preload-native`。 | 是 |
| `entry` | 模组库文件的相对路径。 | 是 |
| `name` | 显示名称。 | 否 |
| `author` | 作者。 | 否 |
| `version` | 版本。 | 否 |
| `icon` | 图标相对路径。 | 否 |
| `minecraft_versions` | 兼容的 Minecraft 版本。支持精确字符串和 `*` 前缀通配。缺失或为空表示兼容所有版本。 | 否 |

## MyMod 示例

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

生命周期含义：

1. `load()`：模组被加载时调用。
2. `enable()`：游戏即将启动时调用。
3. `disable()`：游戏结束时调用。
4. `unload()`：模组结束清理时调用。

## 常用 API

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

`getDataDir()` 和 `getConfigDir()` 适合保存模组自己的数据和配置文件。

用户可编辑的 JSON 设置建议使用 `pl::config::ConfigFile<T>`。模板中已经演示了
强类型 aggregate 配置、默认 `config.json` 自动生成，以及供启动器配置编辑器读取的
`config.schema.json` 自动生成。

## 构建 mod

使用 Android NDK toolchain，并构建 `arm64-v8a` 或 `armeabi-v7a`。

模板打包脚本会构建 mod、生成配置文件，并把它们一起打进 `.levipack`：

```powershell
./scripts/package.ps1 -Abi arm64-v8a
```

把生成的 `.levipack` 导入 LeviLauncher 即可。

## 常见错误

- `manifest.json` 的 `type` 不是 `preload-native`。
- `entry` 为空、是绝对路径，或没有指向 `.so`。
- mod 构建成了不支持的 Android 架构。
- 当前 Minecraft 版本不在 `minecraft_versions` 范围内。

最小 mod 能正常加载后，再继续阅读 [Mod API 参考](/zh-CN/api/mod)。
