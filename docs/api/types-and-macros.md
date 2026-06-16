# Types and Macros

## Purpose

Type and macro headers provide small helper macros and common type aliases.

## Headers

C:

```c
#include <pl/c/Macro.h>
#include <pl/c/Types.h>
```

C++:

```cpp
#include <pl/cpp/Types.hpp>
```

## VA_EXPAND

```c
#define VA_EXPAND(...) __VA_ARGS__
```

Expands variadic macro arguments.

## PLAPI

Marks a function as part of the public native interface.

```c
PLAPI void MyExportedFunction(void);
```

## PLCAPI

Declares a public C-style function.

```c
PLCAPI void MyCFunction(void);
```

## Base Type Aliases

| Alias | Equivalent type |
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

## Notes

- Prefer `pl/c/*` or `pl/cpp/*` in new code.
