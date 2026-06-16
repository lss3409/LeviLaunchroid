# Hook API

## Purpose

Hook API lets a mod run custom code before or instead of a target function.
Multiple hooks on the same target are ordered by priority.

## Headers

C:

```c
#include <pl/c/Hook.h>
```

C++:

```cpp
#include <pl/cpp/Hook.hpp>
```

## Signatures

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

C++ wrappers:

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

### Purpose

Installs a hook for a target function.

### Parameters

| Parameter | Description |
| --- | --- |
| `target` | Target function address; must not be `NULL` |
| `detour` | Replacement function address; must not be `NULL` |
| `originalFunc` | Receives the function pointer to call from the detour; must not be `NULL` |
| `priority` | Hook priority; lower values run earlier |

### Return Value

| Value | Description |
| --- | --- |
| `0` | Success |
| `-1` | Invalid argument or hook failure |

### Example

```cpp
#include <pl/cpp/Hook.hpp>

using UpdateFn = void (*)(void *);
static UpdateFn old_update = nullptr;

static void my_update(void *self) {
  old_update(self);
}

void install(void *target) {
  pl::hook::hook(target,
                 reinterpret_cast<void *>(my_update),
                 reinterpret_cast<void **>(&old_update),
                 pl::hook::PriorityNormal);
}
```

## pl_unhook

### Purpose

Removes one detour from a target function.

### Parameters

| Parameter | Description |
| --- | --- |
| `target` | Target function address |
| `detour` | Detour function address to remove |

### Return Value

Returns `true` when removed, otherwise `false`.

## Chain Behavior

- Lower priority values run earlier.
- Same priority keeps registration order.
- `originalFunc` points to the function that should be called from the detour.

## Common Mistakes

- Passing `NULL` as `originalFunc`: `pl_hook` returns `-1`.
- Detour parameters or return type do not match the target function.
- Calling the target address directly inside detour, causing recursion.
- Installing before the target function is available.
