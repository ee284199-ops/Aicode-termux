cmake_minimum_required(VERSION 3.22.1)
project("aicode_native")

find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)

# ── NativeConfig shim ────────────────────────────────────────────────────────
add_library(nativeconfig SHARED native_config.c)
target_link_libraries(nativeconfig ${log-lib})

# ── libtermux: PTY/subprocess JNI bridge for TerminalSession ─────────────────
# Provides: createSubprocess, setPtyWindowSize, waitFor, close
# Loaded by: com.aicode.studio.termux.terminal.JNI
add_library(termux SHARED termux.c)
target_link_libraries(termux ${log-lib})

# ── libtermux-exec: LD_PRELOAD path-remapping shim ───────────────────────────
# Intercepts ALL libc path-taking functions (open, openat, execve, stat, access,
# readlink, chmod, mkdir, rename, symlink, link, chdir, …) to remap the
# hardcoded /data/data/com.termux/ prefix compiled into upstream bootstrap
# binaries → /data/data/com.aicode.studio/.
# Set in LD_PRELOAD by TermuxShellUtils; inherited by every child process.
add_library(termux-exec SHARED termux_exec.c)
target_link_libraries(termux-exec ${log-lib} ${dl-lib})
