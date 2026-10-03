# EasyTier Android 交叉编译参考

`app/src/main/jniLibs/arm64-v8a/libeasytier_ffi.so` 与 `libeasytier_android_jni.so` 由
[EasyTier](https://github.com/EasyTier/EasyTier)（Apache 2.0）交叉编译而来。

- `build_android_arm64.bat`：Windows 交叉编译脚本（在本目录维护）
- `EasyTierManager.kt` / `EasyTierVpnService.kt`：Android 侧接入参考代码
- 预编译产物已提交至 `app/src/main/jniLibs/`，普通构建无需 Rust 工具链

重新编译步骤：安装 Rust 交叉编译链（aarch64-linux-android）与 NDK 后，
在 EasyTier 源码目录执行本目录下的 `build_android_arm64.bat`。
