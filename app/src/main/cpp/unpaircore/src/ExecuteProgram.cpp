#include "HookManager.h"

#include <dlfcn.h>
#include <string>

std::string getCallerSoName() {
    void* caller = __builtin_return_address(0);
    Dl_info info;
    if (dladdr(caller, &info)) {
        return info.dli_fname;
    }
    return "";
}

extern "C" void ExecuteProgram(void) {
    std::string caller_so = getCallerSoName();
    if (caller_so.find("minecraftpe.so") != std::string::npos) {
        core::hookTimer();
        core::setupHooks();
        core::patchMinecraftLogo();
    }
}