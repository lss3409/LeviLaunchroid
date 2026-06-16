# Memory Hook Macros

## Purpose

`pl/api/memory/Hook.h` provides C++ macros for declaring and registering hooks
with less boilerplate.

## Header

```cpp
#include <pl/api/memory/Hook.h>
```

## Related Type

```cpp
namespace memory {
enum class HookPriority : int {
  Highest,
  High,
  Normal,
  Low,
  Lowest,
};
}
```

## Common Macros

| Macro | Purpose |
| --- | --- |
| `LL_STATIC_HOOK` | Static function hook, manual registration |
| `LL_AUTO_STATIC_HOOK` | Static function hook, automatic registration |
| `LL_INSTANCE_HOOK` | Member function hook, manual registration |
| `LL_AUTO_INSTANCE_HOOK` | Member function hook, automatic registration |
| `LL_TYPED_STATIC_HOOK` | Static hook that inherits from a custom type |
| `LL_AUTO_TYPED_STATIC_HOOK` | Auto typed static hook |
| `LL_TYPED_HOOK` | Member hook that inherits from a custom type |
| `LL_AUTO_TYPED_INSTANCE_HOOK` | Auto typed member hook |

## Parameters

```cpp
LL_STATIC_HOOK(DefType, priority, identifier, module, Ret, ...)
```

| Parameter | Description |
| --- | --- |
| `DefType` | Generated hook type name |
| `priority` | `memory::HookPriority` |
| `identifier` | Target address, function pointer, function name, or pattern |
| `module` | Target module name |
| `Ret` | Return type |
| `...` | Target function parameter list |

## Static Function Example

```cpp
#include <pl/api/memory/Hook.h>

LL_STATIC_HOOK(MyTickHook,
               memory::HookPriority::Normal,
               "Game_tick",
               "libminecraftpe.so",
               void,
               void *self) {
  origin(self);
}

void install() {
  MyTickHook::hook();
}
```

## Notes

- `origin(...)` calls the original or next function in the hook chain.
- Detour parameters and return type must match the target function.
- Automatic registration may fail if the target function is not available yet.
