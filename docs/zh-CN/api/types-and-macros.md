# Types 与宏

## 作用

类型和宏头提供常用辅助宏与基础类型别名。

## 头文件

C:

```c
#include <pl/c/Macro.h>
#include <pl/c/Types.h>
```

C++:

```cpp
#include <pl/cpp/Types.hpp>
```

## 宏

### VA_EXPAND

```c
#define VA_EXPAND(...) __VA_ARGS__
```

用于展开可变参数宏。

### PLAPI

```c
#ifdef PRELOADER_EXPORT
#define PLAPI __attribute__((visibility("default")))
#else
#define PLAPI
#endif
```

作用：标记公开的 native 接口函数。

使用场景：

```c
PLAPI void MyExportedFunction(void);
```

### PLCAPI

```c
#ifdef __cplusplus
#define PLCAPI extern "C" PLAPI
#else
#define PLCAPI extern PLAPI
#endif
```

作用：声明 C 风格公开函数。

## 基础类型

`pl/c/Types.h` 提供以下别名：

| 类型 | 等价类型 |
| --- | --- |
| `ushort` | `unsigned short` |
| `uint` | `unsigned int` |
| `ulong` | `unsigned long` |
| `llong` | `long long` |
| `ullong` | `unsigned long long` |
| `uchar` | `unsigned char` |
| `schar` | `signed char` |
| `byte` | `uchar` |
| `ldouble` | `long double` |
| `int64` | `long long` |
| `int32` | `int` |
| `int16` | `short` |
| `int8` | `char` |
| `uint64` | `unsigned long long` |
| `uint32` | `unsigned int` |
| `uint16` | `unsigned short` |
| `uint8` | `unsigned char` |

## 注意事项

- 新代码优先使用 `pl/c/*` 或 `pl/cpp/*`。
