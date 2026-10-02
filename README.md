<div align="center">

# LBBL — Levi Better Bedrock Launcher

**A lightweight Android launcher for Minecraft: Bedrock Edition, forked & enhanced from [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid)**

English | [中文](#中文)

</div>

---

## Introduction

LBBL (Levi Better Bedrock Launcher) is a fork of [LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) — a lightweight, open-source Android launcher for legitimate players of Minecraft: Bedrock Edition. It lets you import your official Minecraft APK and run it without system installation, manage multiple game versions with full isolation, and manage resource packs and worlds.

This fork keeps the original core experience and adds a large set of enhancements built on top of it.

### Key Features (original)

- **APK Import & Installation-Free Launching** – Import your official Minecraft APK and run it directly without system installation
- **SO Module Loading** – Load external native SO modules to extend Minecraft
- **Multi-Version Management & Isolation** – Manage multiple Minecraft versions independently
- **Multiple Xbox Account Management** – Switch between Xbox accounts inside the launcher
- **Resource Pack & World Management** – Import, export and back up packs and worlds

### What this fork adds

- **Online play (联机)** – Create/join rooms with invite codes over a virtual LAN (EasyTier-based P2P networking), built-in voice chat (PTT, mute, noise reduction), room member list, deep-link "invite to world" that connects members straight into the host's world, and a relay fallback for CGNAT networks
- **World map viewer** – 2D satellite-style map with biome/topographic layers, structures, waypoints, coordinate search, and a 3D voxel view (hardcore-optimized chunk-tile renderer for huge worlds)
- **World data editing** – NBT viewer/editor, player data (health, position, UUID), world settings form
- **File manager** – Full-featured file browser with ZIP create/extract, multi-select, trash, image/audio/text preview
- **Hardcore auto-backup** – Scheduled backups for hardcore worlds
- **Personalization** – Accent color themes, UI/font scaling, liquid-glass effect, custom world map marker colors
- **11-language i18n** – English, 简体中文, Español, Português, 日本語, Tiếng Việt, Bahasa Indonesia, हिन्दी, Français, Русский, Türkçe
- **Built-in update checker** – GitHub Releases with multi-mirror fallback, progress bar and silent background download

---

## Screenshots

*Coming soon*

---

## Download

Latest APK: [levi-updates releases](https://github.com/lss3409/levi-updates/releases)

The in-app update checker reads `update.json` from that repository automatically.

---

## System Requirements

- Android 9.0+ (API 28+)
- arm64-v8a device (the bundled EasyTier native library is built for arm64)

---

## Building

Requirements:

- JDK 21
- Android SDK (compileSdk 36, build-tools 35.0.0)
- Android NDK not required unless you rebuild the bundled EasyTier core library

```bash
git clone https://github.com/lss3409/LeviLaunchroid.git
cd LeviLaunchroid
git tag v0.0.x   # versionName is derived from git tag (semver)
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The EasyTier JNI library in `app/src/main/jniLibs/arm64-v8a/` is cross-compiled from [EasyTier](https://github.com/EasyTier/EasyTier) for Android (see the build script kept outside this repo). Prebuilt binaries are committed so a normal build does not need the Rust toolchain.

---

## License & Disclaimer

- Licensed under the **Apache License 2.0** (see [LICENSE](LICENSE)). This project is a modified fork of [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid); all modifications are made by this fork's maintainers and this project is **not affiliated with, endorsed by, or associated with LiteLDev**.
- **Not an official Minecraft product.** Not approved by or associated with Mojang or Microsoft. You must own a legitimate copy of Minecraft: Bedrock Edition to use this launcher. The project does not distribute any Minecraft game files.

---

<div id="中文"></div>

<div align="center">

# LBBL — Levi Better Bedrock Launcher

**轻量的 Minecraft 基岩版 Android 启动器，基于 [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) 的增强分支**

</div>

---

## 简介

LBBL（Levi Better Bedrock Launcher）是 [LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) 的一个分支——一个轻量、开源的 Minecraft 基岩版 Android 启动器。导入官方 Minecraft APK 即可免系统安装直接运行，支持多版本独立管理（配置与数据完全隔离），内置资源包与世界管理。

本分支在保留原版核心体验的基础上，新增了大量增强功能。

### 原版核心功能

- **APK 导入 / 免安装启动** – 导入官方 Minecraft APK，无需系统安装直接运行
- **SO 模块加载** – 加载外部原生 SO 模块扩展 Minecraft 功能
- **多版本管理与隔离** – 独立管理多个 Minecraft 版本，配置与数据互不干扰
- **多 Xbox 账号管理** – 启动器内管理并切换多个 Xbox 账号
- **资源包与世界管理** – 导入、导出、备份资源包和世界

### 本分支新增

- **联机系统** – 邀请码创建/加入房间，EasyTier P2P 虚拟组网（异地联机、CGNAT 网络下中转兜底），内置语音通话（PTT、禁麦、降噪），成员列表，「邀请进入世界」深链直连房主世界
- **世界地图查看器** – 2D 卫星风格地图（生物群系/地形图层、结构标记、标点、坐标搜索）+ 3D 体素视图（chunk-tile 渲染架构，超大世界流畅缩放）
- **世界数据编辑** – NBT 查看/编辑、玩家数据（生命/坐标/UUID）、世界设置表单
- **文件管理器** – 全功能文件浏览（ZIP 压缩/解压、多选、回收站、图片/音频/文本预览）
- **极限模式自动备份** – 定时备份极限存档
- **个性化** – 强调色主题、UI/字体缩放、液态玻璃效果、地图标点自定义颜色
- **11 语言多语言** – 英语、简体中文、西班牙语、葡萄牙语、日语、越南语、印尼语、印地语、法语、俄语、土耳其语
- **内置更新检查** – GitHub Releases 直链 + 多镜像回退，带进度条、可静默后台下载

---

## 下载

最新 APK：[levi-updates releases](https://github.com/lss3409/levi-updates/releases)

应用内「检查更新」会自动从该仓库读取 `update.json`。

---

## 系统要求

- Android 9.0+（API 28+）
- arm64-v8a 设备（内置 EasyTier 原生库为 arm64 编译）

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

- 使用 **Apache License 2.0** 许可（见 [LICENSE](LICENSE)）。本项目是 [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) 的修改分支，所有修改由本分支维护者完成，本项目**与 LiteLDev 无关联，亦未获得其背书**。
- **非官方 Minecraft 产品**，与 Mojang / Microsoft 无关。使用本启动器需持有正版 Minecraft 基岩版。本项目不提供任何 Minecraft 游戏文件。
