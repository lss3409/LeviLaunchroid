<div align="center">

# LBBL — Levi Better Bedrock Launcher

**A lightweight Android launcher for Minecraft: Bedrock Edition, forked & heavily enhanced from [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid)**

English | [中文](#中文)

</div>

---

## Introduction

LBBL (Levi Better Bedrock Launcher) is a fork of [LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid), a lightweight open-source Android launcher for legitimate players of Minecraft: Bedrock Edition. It lets you import your official Minecraft APK and run it without system installation, manage multiple game versions with full isolation, and manage resource packs and worlds.

The fork is based on the upstream codebase around v1.5.22 (commit `2e52effc`), then rebuilt and extended with **522+ commits** of our own: a complete online multiplayer system, a world map viewer with 2D/3D rendering, hardcore mode, a file manager, and much more. Upstream changes after v1.5.23 (external mod catalog v2, 1.26.50 support, etc.) have **not** been merged into this fork.

---

## Features inherited from upstream

- **APK Import & Installation-Free Launching** – Import your official Minecraft APK and run it directly without system installation
- **SO Module Loading** – Load external native SO modules to extend Minecraft
- **Multi-Version Management & Isolation** – Manage multiple Minecraft versions independently
- **Multiple Xbox Account Management** – Switch between Xbox accounts inside the launcher
- **Resource Pack & World Management** – Import, export and back up packs and worlds
- **Mods & CurseForge integration** – External mod catalog, mod management and CurseForge browser
- **Custom flat world editor** – Design custom superflat worlds
- **Personalization framework** – Theme manager, language switcher, storage migration, crash reporting

## What this fork adds

### Online play (联机) — built from scratch
- **Rooms with invite codes** – create a room, share a formatted invite code (with QR), join with one tap
- **P2P virtual LAN via EasyTier** – works across the internet behind CGNAT; bundled EasyTier core cross-compiled for Android (arm64-v8a), with a public relay fallback
- **Voice chat** – built-in push-to-talk, mute controls and noise reduction
- **Invite-to-world deep link** – members deep-link straight into the host's world from the room page
- **In-game floating overlay** – room member list, status and quick exit while playing
- **Bookmarks** – save favorite rooms for one-tap rejoin; rooms auto-restore after network drops

### World map viewer — built from scratch
- **2D satellite-style map** – biome, terrain/topographic layers, structure & ore markers, waypoints with custom colors, coordinate search/jump, HTML export
- **3D voxel view** – full 3D block rendering of any region, gesture rotate/zoom
- **High-performance renderer** – chunk-tile architecture with LOD, 6-thread viewport rendering and big-core scheduling; handles 180 MB+ worlds smoothly; fixed OOM on phones, ANR on the main thread, and many rendering correctness bugs (sea color, biome layers, subchunk parsing…)

### Hardcore mode — built from scratch
- Hardcore world detection with badges
- **Scheduled automatic backups** (with rollback) for hardcore worlds

### World data tools
- **NBT viewer/editor**, player data panel (health / position / UUID) and world settings form
- **Native LevelDB binding** (JNI, extracted from an open-source port) with pure-Java fallback — much faster world reading

### File manager — ported from ZalithLauncher2, adapted to this project
- Full-featured browser: grid/list views, multi-select, trash, ZIP create/extract, image/audio/text preview, built-in editor

### Import enhancements
- Whole-device scan for `.mcpack` / `.mcaddon` / `.mcworld` / `.mcstructure` with streaming results, persistent cache and thumbnails
- Multi-select batch import, Minecraft color-code rendering (§/&) in names

### Launcher experience
- **Update checker rewritten** – GitHub Releases direct links with multi-mirror fallback (for users behind restricted networks), progress bar and silent background download; three-state result (failed / up-to-date / update)
- **Liquid-glass effect** (Prismal) and an accent-color system across all dialogs/buttons
- **Complete translations for all 11 languages** – 简体中文, English, Español, Português, 日本語, Tiếng Việt, Bahasa Indonesia, हिन्दी, Français, Русский, Türkçe
- **Home-screen shortcuts** for game versions
- News module, app renamed to **LBBL**

---

## Download

Latest APK: [levi-updates releases](https://github.com/lss3409/levi-updates/releases)

The in-app update checker reads `update.json` from that repository automatically.

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

- Licensed under the **Apache License 2.0** (see [LICENSE](LICENSE)). This project is a modified fork of [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid); all modifications are made by this fork's maintainers. This project is **not affiliated with, endorsed by, or associated with LiteLDev**.
- **Not an official Minecraft product.** Not approved by or associated with Mojang or Microsoft. You must own a legitimate copy of Minecraft: Bedrock Edition to use this launcher. This project does not distribute any Minecraft game files.

---

<div id="中文"></div>

<div align="center">

# LBBL — Levi Better Bedrock Launcher

**轻量的 Minecraft 基岩版 Android 启动器，基于 [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) 深度增强的分支**

</div>

---

## 简介

LBBL（Levi Better Bedrock Launcher）是 [LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) 的分支——一个轻量、开源的 Minecraft 基岩版 Android 启动器。导入官方 Minecraft APK 即可免系统安装直接运行，支持多版本独立管理、资源包与世界管理。

本分支基于官方 v1.5.22 前后的代码基线（commit `2e52effc`）fork，此后以 **522+ 个提交**重构与扩展：全新的联机系统、2D/3D 世界地图查看器、极限模式、文件管理器等。官方 v1.5.23 之后的更新（v2 外部模组目录、1.26.50 支持等）**未合并**入本分支。

---

## 继承自原版的功能

- **APK 导入 / 免安装启动** – 导入官方 Minecraft APK，无需系统安装直接运行
- **SO 模块加载** – 加载外部原生 SO 模块扩展 Minecraft 功能
- **多版本管理与隔离** – 独立管理多个 Minecraft 版本，配置与数据互不干扰
- **多 Xbox 账号管理** – 启动器内管理并切换多个 Xbox 账号
- **资源包与世界管理** – 导入、导出、备份资源包和世界
- **模组与 CurseForge 集成** – 外部模组目录、模组管理、CurseForge 浏览
- **自定义超平坦世界编辑器** – 可视化设计超平坦世界
- **个性化框架** – 主题管理、语言切换、存储迁移、崩溃报告

## 本分支新增

### 联机系统 — 从零开发
- **邀请码房间** – 创建房间、格式化邀请码（附二维码）分享、一键加入
- **EasyTier P2P 虚拟组网** – 跨互联网联机、CGNAT 网络可用；内置 Android 交叉编译的 EasyTier 核心（arm64-v8a），公共中继兜底
- **语音通话** – 内置 PTT 对讲、禁麦控制、降噪
- **「邀请进入世界」深链** – 成员在房间页一键深链直连房主的世界
- **游戏内悬浮窗** – 游玩时查看成员列表、状态、快捷退出
- **收藏房间** – 常用房间一键重进；断线后房间自动恢复

### 世界地图查看器 — 从零开发
- **2D 卫星风格地图** – 生物群系、地形图层，结构与矿石标记，自定义颜色标点，坐标搜索/跳转，HTML 导出
- **3D 体素视图** – 任意区域全 3D 方块渲染，手势旋转缩放
- **高性能渲染器** – chunk-tile 架构 + LOD + 6 线程视口渲染 + 大核调度；180 MB+ 超大世界流畅缩放；修复了手机 OOM、主线程 ANR 以及大量渲染正确性问题（海色、群系图层、subchunk 解析等）

### 极限模式 — 从零开发
- 极限世界检测与标识
- **定时自动备份**（支持回档）保护极限存档

### 世界数据工具
- **NBT 查看/编辑**、玩家数据面板（生命值 / 坐标 / UUID）、世界设置表单
- **原生 LevelDB 绑定**（JNI，提取自开源移植，纯 Java 回退）——世界读取大幅提速

### 文件管理器 — 移植自 ZalithLauncher2 并适配本项目
- 全功能文件浏览：网格/列表视图、多选、回收站、ZIP 压缩/解压、图片/音频/文本预览、内置编辑器

### 导入增强
- 全盘扫描 `.mcpack` / `.mcaddon` / `.mcworld` / `.mcstructure`，流式显示结果、持久缓存、缩略图
- 多选批量导入，名称支持 Minecraft 颜色码（§/&）渲染

### 启动器体验
- **更新检查重写** – GitHub Releases 直链 + 多镜像回退（受限网络可用），进度条 + 静默后台下载；三态结果（失败 / 已最新 / 有更新）
- **液态玻璃效果**（Prismal）+ 全弹窗/按钮的强调色系统
- **11 语言全量翻译** – 简体中文、英语、西班牙语、葡萄牙语、日语、越南语、印尼语、印地语、法语、俄语、土耳其语
- 游戏版本**桌面快捷方式**
- 新闻模块，应用更名 **LBBL**

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

- 使用 **Apache License 2.0** 许可（见 [LICENSE](LICENSE)）。本项目是 [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) 的修改分支，所有修改由本分支维护者完成，本项目**与 LiteLDev 无关联，亦未获得其背书**。
- **非官方 Minecraft 产品**，与 Mojang / Microsoft 无关。使用本启动器需持有正版 Minecraft 基岩版。本项目不提供任何 Minecraft 游戏文件。
