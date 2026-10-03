@echo off
chcp 65001 >nul
REM ===== LeviLauncher 联机模块：EasyTier Android JNI 库构建脚本（arm64） =====
REM 依赖：Rust 1.95（仓库 rust-toolchain.toml 锁定）、Android NDK、protoc
REM 前置：rustup target add aarch64-linux-android --toolchain 1.95
REM        NDK 路径按本机实际修改；protoc 解压路径按实际修改

set ANDROID_NDK_ROOT=C:\Android\Sdk\ndk\28.2.13676358
set NDK_BIN=%ANDROID_NDK_ROOT%\toolchains\llvm\prebuilt\windows-x86_64\bin
set SYSROOT=%ANDROID_NDK_ROOT%\toolchains\llvm\prebuilt\windows-x86_64\sysroot

set CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=%NDK_BIN%\aarch64-linux-android24-clang.cmd
set CC_aarch64_linux_android=%NDK_BIN%\aarch64-linux-android24-clang.cmd
set CXX_aarch64_linux_android=%NDK_BIN%\aarch64-linux-android24-clang++.cmd
set AR_aarch64_linux_android=%NDK_BIN%\llvm-ar.exe
set RANLIB_aarch64_linux_android=%NDK_BIN%\llvm-ranlib.exe
set PROTOC=C:\Users\lishu\AppData\Local\Temp\protoc\bin\protoc.exe
REM bindgen 需要 Android sysroot（否则 kcp-sys 报 stdlib.h 找不到）
set BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android=--sysroot=%SYSROOT% --target=aarch64-linux-android24

cd /d %~dp0easytier-contrib\easytier-android-jni
cargo build --release --target aarch64-linux-android -p easytier-ffi || goto :err
REM jni 库的 extern "C" 符号在 ffi 库里，必须链接进 DT_NEEDED（build.rs 读取本变量）
set EASYTIER_FFI_DIR=%~dp0target\aarch64-linux-android\release
set EASYTIER_FFI_DIR=%EASYTIER_FFI_DIR:\=/%
cargo build --release --target aarch64-linux-android -p easytier-android-jni || goto :err

echo.
echo 产物：
dir %~dp0target\aarch64-linux-android\release\libeasytier_android_jni.so
dir %~dp0target\aarch64-linux-android\release\libeasytier_ffi.so
echo 复制到 LeviLaunchroid\app\src\main\jniLibs\arm64-v8a\ 即可
goto :eof

:err
echo 构建失败
exit /b 1
