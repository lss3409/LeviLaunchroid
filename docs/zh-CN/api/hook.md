# Hook API

## 作用

Hook API 允许模组在目标函数执行前或替代目标函数执行自定义代码。多个 hook
作用于同一目标时会按优先级执行。

## 头文件

C:

```c
#include <pl/c/Hook.h>
```

C++:

```cpp
#include <pl/cpp/Hook.hpp>
```

## 类型签名

```c
typedef void *PLFuncPtr;
typedef PLFuncPtr FuncPtr;

typedef enum PLHookPriority {
  PL_HOOK_PRIORITY_HIGHEST = 0,
  PL_HOOK_PRIORITY_HIGH = 100,
  PL_HOOK_PRIORITY_NORMAL = 200,
  PL_HOOK_PRIORITY_LOW = 300,
  PL_HOOK_PRIORITY_LOWEST = 400,
} PLHookPriority;

PLAPI int pl_hook(PLFuncPtr target, PLFuncPtr detour,
                  PLFuncPtr *originalFunc,
                  PLHookPriority priority);

PLAPI bool pl_unhook(PLFuncPtr target, PLFuncPtr detour);
```

C++ 包装：

```cpp
namespace pl::hook {
using FuncPtr = PLFuncPtr;

enum Priority : int {
  PriorityHighest = PL_HOOK_PRIORITY_HIGHEST,
  PriorityHigh = PL_HOOK_PRIORITY_HIGH,
  PriorityNormal = PL_HOOK_PRIORITY_NORMAL,
  PriorityLow = PL_HOOK_PRIORITY_LOW,
  PriorityLowest = PL_HOOK_PRIORITY_LOWEST,
};

int pl_hook(FuncPtr target, FuncPtr detour, FuncPtr *originalFunc,
            Priority priority);
bool pl_unhook(FuncPtr target, FuncPtr detour);
int hook(FuncPtr target, FuncPtr detour, FuncPtr *originalFunc,
         Priority priority = PriorityNormal);
bool unhook(FuncPtr target, FuncPtr detour);
}
```

## pl_hook

### 作用

为目标函数安装 hook。

### 参数

| 参数 | 说明 |
| --- | --- |
| `target` | 目标函数地址，不能为 `NULL` |
| `detour` | 替换函数地址，不能为 `NULL` |
| `originalFunc` | 用于接收 detour 中应调用的函数指针，不能为 `NULL` |
| `priority` | hook 优先级，数值越小越靠前 |

### 返回值

| 返回值 | 说明 |
| --- | --- |
| `0` | 成功 |
| `-1` | 参数无效或 hook 失败 |

### 示例

```cpp
#include <pl/cpp/Hook.hpp>

using UpdateFn = void (*)(void *);
static UpdateFn old_update = nullptr;

static void my_update(void *self) {
  // before
  old_update(self);
  // after
}

void install(void *target) {
  pl::hook::hook(target,
                 reinterpret_cast<void *>(my_update),
                 reinterpret_cast<void **>(&old_update),
                 pl::hook::PriorityNormal);
}
```

## pl_unhook

### 作用

从指定目标函数上移除指定 detour。

### 参数

| 参数 | 说明 |
| --- | --- |
| `target` | 目标函数地址 |
| `detour` | 要移除的 detour 函数地址 |

### 返回值

返回 `true` 表示移除成功，返回 `false` 表示未找到对应 hook。

## 链式 hook 行为

多个 hook 作用于同一个 `target` 时：

- priority 数字越小越先执行。
- 同优先级下，先注册的 detour 更靠前。
- `originalFunc` 会指向 detour 中应继续调用的函数。

## 常见错误

- `originalFunc` 传 `NULL`。
- detour 参数或返回值和目标函数不一致。
- detour 内直接调用目标函数地址：会递归进入 detour，应该调用 `originalFunc`。
- 在目标函数可用之前安装 hook。
