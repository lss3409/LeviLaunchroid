<div align="center">

# LBBL — Levi Better Bedrock Launcher

**A lightweight Android launcher for Minecraft: Bedrock Edition**

English | [中文](#中文)

</div>

---

## About

LBBL is a fork of [LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) (a.k.a. LeviLauncher for Android), the open-source Minecraft: Bedrock Edition launcher by the LeviMC community. This fork adds some extra features on top of the upstream launcher and fixes various issues we ran into while using it.

This project is **not affiliated with LiteLDev or the LeviMC team** — it is a personal fork. Some features were developed with reference to other open-source projects; see [References](#references) below.

This fork was developed 100% with the assistance of the deepseek-v4-pro AI model.

---

## Features added in this fork

- **Custom storage path** – choose where worlds, packs and game data are stored (default `/storage/emulated/0/Levilauncher/`)
- **Shared folder** – a common library for resources (worlds / packs / skins / structures…) that all game versions can read, alongside each version's own isolated storage
- **Import improvements** – scan the whole device for `.mcpack` / `.mcaddon` / `.mcworld` / `.mcstructure` (including zips) and pick from a list UI; batch import; duplicate detection via uuid; progress bar with silent background mode
- **Global game settings** – field of view / render distance / safe area / touch controls applied to all game versions at once
- **World map viewer** – 2D satellite-style map (layers for biomes, structures, ores, entities), waypoints, coordinate search, HTML export, and an experimental 3D voxel view
- **Hardcore auto-backup** – scheduled backups with rollback for hardcore worlds
- **Online play** – rooms with invite codes over an EasyTier-based virtual LAN (works across networks without public IP), voice chat, in-game overlay, invite-to-world deep link
- **File manager** – browse, preview, ZIP create/extract, trash, text editor with syntax highlighting
- **Help page** – built-in browser tabs (Chunkbase, MC Wiki, Bilibili) inside the launcher
- **Improved mod-menu floating ball** – moved from in-game to the launcher home page, with edge snapping / auto-hide / position memory
- **UI scale slider**, default language set to Chinese, completed translations for all 11 languages

Some of these features are still a work in progress (e.g. the 3D voxel view).

## References

This fork references the following open-source projects:

| Feature | Referenced project |
|---|---|
| File manager | ZalithLauncher2 |
| Map rendering | bedrockmap, BTR (BedrockTopographyRenderer) |
| Online play UI | Astral Launcher |
| Networking core | EasyTier |
| Invite code protocol | PaperConnect (Round-Studio) |
| Floating ball UX | AOSP AccessibilityFloatingMenuView |
| Update checker | ZalithLauncher2 update flow |
| License bypass | LeviLaunchroidUnlocked approach |
| HTML map | Leaflet |

---

## Download

Latest APK: [levi-updates releases](https://github.com/lss3409/levi-updates/releases)

The in-app update checker reads `update.json` from that repository.

---

## System Requirements

- Android 9.0+ (API 28+)
- arm64-v8a device (the bundled EasyTier native library is arm64-only)

---

## Building

Requirements:

- JDK 21
- Android SDK (compileSdk 36, build-tools 35.0.0)
- No NDK needed unless you rebuild the bundled EasyTier core library

```bash
git clone https://github.com/lss3409/LeviLaunchroid.git
cd LeviLaunchroid
git tag v0.0.x   # versionName is derived from the git tag (semver)
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The EasyTier JNI library under `app/src/main/jniLibs/arm64-v8a/` is cross-compiled from [EasyTier](https://github.com/EasyTier/EasyTier) for Android (build script maintained outside this repo). Prebuilt binaries are committed, so a normal build does not need the Rust toolchain.

---

## License & Disclaimer

- Licensed under the **Apache License 2.0** (see [LICENSE](LICENSE)). This project is a fork of [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) with modifications by this fork's maintainers. It is **not affiliated with, endorsed by, or associated with LiteLDev or the LeviMC team**.
- **Not an official Minecraft product.** Not approved by or associated with Mojang or Microsoft. You must own a legitimate copy of Minecraft: Bedrock Edition to use this launcher. This project does not distribute any Minecraft game files.

---

<div id="中文"></div>

<div align="center">

# LBBL — Levi Better Bedrock Launcher

**轻量的 Minecraft 基岩版 Android 启动器**

</div>

---

## 简介

LBBL 是 [LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid)（LeviMC 社区的开源基岩版启动器，即安卓版 LeviLauncher）的一个分支。我们在官方启动器的基础上补充了一些功能，并修复了使用过程中遇到的各种问题。

本项目是个人分支，**与 LiteLDev / LeviMC 团队无关**。部分功能参考了其他开源项目的实现，见文末[参考项目](#参考项目)。

本分支 100% 由 deepseek-v4-pro 模型协助开发。

---

## 本分支增加的功能

- **自定义存储路径** – 自定义存档、资源包与游戏数据的保存位置（默认 `/storage/emulated/0/Levilauncher/`）
- **共享文件夹** – 常用资源（存档/资源包/皮肤/结构文件等）放入共享文件夹供所有版本读取，各版本仍保留独立存储
- **导入增强** – 全盘扫描手机上的 .mcpack/.mcaddon/.mcworld/.mcstructure（含 zip 压缩包）列表选择导入；多选批量导入；uuid 去重检测；进度条 + 静默后台模式
- **全局游戏配置** – 视角/视距/安全区/触控方案一次设置，所有版本通用
- **世界地图查看器** – 2D 卫星风格地图（生物群系/结构/矿石/实体图层）、标点、坐标搜索、HTML 导出，以及一个还不完善的 3D 体素视图
- **极限存档定时备份** – 定时备份极限存档，支持回档
- **联机** – 邀请码房间 + EasyTier 虚拟组网（无公网 IP 也能异地联机）、语音通话、游戏内悬浮窗、邀请进入世界深链
- **文件管理器** – 浏览/预览、ZIP 压缩解压、回收站、带语法高亮的文本编辑器
- **帮助页** – 启动器内置网页（chunkbase 查询器、MC Wiki、Bilibili）
- **悬浮球改造** – 官方的游戏内模组菜单悬浮球改为启动器主页悬浮球（贴边吸附/自动隐藏/位置记忆）
- **界面缩放滑块**，默认语言设为中文，11 种语言翻译补全

部分功能仍在完善中（如 3D 体素视图）。

## 参考项目

本分支参考了以下开源项目：

| 功能 | 参考项目 |
|---|---|
| 文件管理器 | ZalithLauncher2 |
| 地图渲染 | bedrockmap、BTR（BedrockTopographyRenderer） |
| 联机 UI | Astral Launcher |
| 组网内核 | EasyTier |
| 邀请码协议 | PaperConnect（Round-Studio） |
| 悬浮球交互 | AOSP AccessibilityFloatingMenuView |
| 更新检查 | ZalithLauncher2 更新方案 |
| 去正版验证 | LeviLaunchroidUnlocked 思路 |
| HTML 地图 | Leaflet |

---

## 下载

最新 APK：[levi-updates releases](https://github.com/lss3409/levi-updates/releases)

应用内「检查更新」会自动从该仓库读取 `update.json`。

---

## 系统要求

- Android 9.0+（API 28+）
- arm64-v8a 设备（内置 EasyTier 原生库仅 arm64）

---

## 构建

环境要求：

- JDK 21
- Android SDK（compileSdk 36，build-tools 35.0.0）
- 无需 NDK（除非重新编译内置的 EasyTier 核心库）

```bash
git clone https://github.com/lss3409/LeviLaunchroid.git
cd LeviLaunchroid
git tag v0.0.x   # versionName 取自 git tag（语义化版本）
./gradlew assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk
```

`app/src/main/jniLibs/arm64-v8a/` 下的 EasyTier JNI 库由 [EasyTier](https://github.com/EasyTier/EasyTier) 交叉编译而来（构建脚本在仓库外维护）。预编译产物已提交，普通构建无需 Rust 工具链。

---

## 协议与声明

- 使用 **Apache License 2.0** 许可（见 [LICENSE](LICENSE)）。本项目是 [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) 的分支，修改由本分支维护者完成，**与 LiteLDev / LeviMC 团队无关联，亦未获得其背书**。
- **非官方 Minecraft 产品**，与 Mojang / Microsoft 无关。使用本启动器需持有正版 Minecraft 基岩版。本项目不提供任何 Minecraft 游戏文件。
