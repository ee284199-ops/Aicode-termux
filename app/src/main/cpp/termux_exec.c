/*
 * termux_exec.c — LD_PRELOAD path-remapping shim
 *
 * Bootstrap binaries downloaded from termux-packages have /data/data/com.termux/
 * hardcoded in TWO ways:
 *   1. Script shebangs  (#!/data/data/com.termux/files/usr/bin/bash)
 *   2. ELF string constants compiled into binaries
 *      (bash: --sysconfdir=/data/data/com.termux/files/usr/etc, etc.)
 *
 * Shebang patching during install fixes (1) for the kernel's shebang handler.
 * This library fixes (2) — and the remaining (1) cases — by intercepting every
 * libc function that takes a path and remapping com.termux → com.aicode.studio
 * before the syscall reaches the kernel.
 *
 * Loaded via LD_PRELOAD in the shell environment (TermuxShellUtils).
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <linux/limits.h>
#include <android/log.h>

#define LOG_TAG "termux-exec"

/* ─── package prefixes ─────────────────────────────────────────────────── */
#define OLD_PFX_BASE "/data/data/com.termux"
#define OLD_PFX_LEN  (sizeof(OLD_PFX_BASE) - 1)
#define NEW_PFX_BASE "/data/data/com.aicode.studio"

/* ─── remap helper ──────────────────────────────────────────────────────── */
/*
 * If *path* starts with OLD_PFX_BASE, and is either exactly that or followed
 * by a slash, remap it to NEW_PFX_BASE.
 */
static inline const char *remap(const char *path, char *buf) {
    if (path && strncmp(path, OLD_PFX_BASE, OLD_PFX_LEN) == 0) {
        if (path[OLD_PFX_LEN] == '\0' || path[OLD_PFX_LEN] == '/') {
            snprintf(buf, PATH_MAX, "%s%s", NEW_PFX_BASE, path + OLD_PFX_LEN);
            return buf;
        }
    }
    return path;
}

/* ─── real-function pointers (resolved once, lazily) ────────────────────── */
#define RESOLVE(name, ret, ...) \
    static ret (*real_##name)(__VA_ARGS__) = NULL; \
    if (!real_##name) real_##name = dlsym(RTLD_NEXT, #name)

/* ─── open / openat ─────────────────────────────────────────────────────── */
int open(const char *path, int flags, ...) {
    RESOLVE(open, int, const char *, int, ...);
    char buf[PATH_MAX];
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return real_open(remap(path, buf), flags, mode);
}

int open64(const char *path, int flags, ...) {
    RESOLVE(open64, int, const char *, int, ...);
    char buf[PATH_MAX];
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return real_open64(remap(path, buf), flags, mode);
}

int openat(int dirfd, const char *path, int flags, ...) {
    RESOLVE(openat, int, int, const char *, int, ...);
    char buf[PATH_MAX];
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return real_openat(dirfd, remap(path, buf), flags, mode);
}

/* ─── execve ─────────────────────────────────────────────────────────────── */
/*
 * Intercept execve to handle two critical Android/Termux issues:
 * 1. Permission Denied: Force chmod +x before execution.
 * 2. Bad Shebang: If the kernel can't find the interpreter (due to hardcoded com.termux),
 *    we manually wrap the call using our own shell.
 */
int execve(const char *path, char * const argv[], char * const envp[]) {
    RESOLVE(execve, int, const char *, char * const [], char * const []);
    RESOLVE(open, int, const char *, int, ...);
    char buf[PATH_MAX];
    const char *rp = remap(path, buf);

    // Try to ensure the file is executable (fixes dpkg extraction permission issues)
    chmod(rp, 0700);

    // Try original/remapped execution
    int ret = real_execve(rp, argv, envp);

    // If it fails with ENOENT or EACCES, check for a bad shebang
    if (ret == -1 && (errno == ENOENT || errno == EACCES)) {
        int fd = real_open(rp, O_RDONLY, 0);
        if (fd != -1) {
            char head[2];
            if (read(fd, head, 2) == 2 && head[0] == '#' && head[1] == '!') {
                close(fd);
                // It's a script. The kernel failed to find the interpreter.
                // Wrap it with /system/bin/sh
                int argc = 0;
                while (argv[argc]) argc++;
                char **new_argv = malloc(sizeof(char*) * (argc + 3));
                if (new_argv) {
                    new_argv[0] = "/system/bin/sh";
                    new_argv[1] = (char*)rp;
                    for (int i = 0; i <= argc; i++) new_argv[i+2] = argv[i+1];
                    return real_execve(new_argv[0], new_argv, envp);
                }
            }
            close(fd);
        }
    }
    return ret;
}

/* ─── access / faccessat ────────────────────────────────────────────────── */
int access(const char *path, int mode) {
    RESOLVE(access, int, const char *, int);
    char buf[PATH_MAX];
    return real_access(remap(path, buf), mode);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
    RESOLVE(faccessat, int, int, const char *, int, int);
    char buf[PATH_MAX];
    return real_faccessat(dirfd, remap(path, buf), mode, flags);
}

/* ─── stat family ───────────────────────────────────────────────────────── */
int stat(const char *path, struct stat *st) {
    RESOLVE(stat, int, const char *, struct stat *);
    char buf[PATH_MAX];
    return real_stat(remap(path, buf), st);
}

int lstat(const char *path, struct stat *st) {
    RESOLVE(lstat, int, const char *, struct stat *);
    char buf[PATH_MAX];
    return real_lstat(remap(path, buf), st);
}

int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    RESOLVE(fstatat, int, int, const char *, struct stat *, int);
    char buf[PATH_MAX];
    return real_fstatat(dirfd, remap(path, buf), st, flags);
}

/* ─── readlink / readlinkat ─────────────────────────────────────────────── */
ssize_t readlink(const char *path, char *out, size_t len) {
    RESOLVE(readlink, ssize_t, const char *, char *, size_t);
    char buf[PATH_MAX];
    return real_readlink(remap(path, buf), out, len);
}

ssize_t readlinkat(int dirfd, const char *path, char *out, size_t len) {
    RESOLVE(readlinkat, ssize_t, int, const char *, char *, size_t);
    char buf[PATH_MAX];
    return real_readlinkat(dirfd, remap(path, buf), out, len);
}

/* ─── chmod / chown ─────────────────────────────────────────────────────── */
int chmod(const char *path, mode_t mode) {
    RESOLVE(chmod, int, const char *, mode_t);
    char buf[PATH_MAX];
    return real_chmod(remap(path, buf), mode);
}

int chown(const char *path, uid_t uid, gid_t gid) {
    RESOLVE(chown, int, const char *, uid_t, gid_t);
    char buf[PATH_MAX];
    return real_chown(remap(path, buf), uid, gid);
}

/* ─── mkdir / rmdir / unlink ────────────────────────────────────────────── */
int mkdir(const char *path, mode_t mode) {
    RESOLVE(mkdir, int, const char *, mode_t);
    char buf[PATH_MAX];
    return real_mkdir(remap(path, buf), mode);
}

int rmdir(const char *path) {
    RESOLVE(rmdir, int, const char *);
    char buf[PATH_MAX];
    return real_rmdir(remap(path, buf));
}

int unlink(const char *path) {
    RESOLVE(unlink, int, const char *);
    char buf[PATH_MAX];
    return real_unlink(remap(path, buf));
}

/* ─── rename ────────────────────────────────────────────────────────────── */
int rename(const char *oldpath, const char *newpath) {
    RESOLVE(rename, int, const char *, const char *);
    char b1[PATH_MAX], b2[PATH_MAX];
    return real_rename(remap(oldpath, b1), remap(newpath, b2));
}

/* ─── symlink / link ────────────────────────────────────────────────────── */
int symlink(const char *target, const char *linkpath) {
    RESOLVE(symlink, int, const char *, const char *);
    char b1[PATH_MAX], b2[PATH_MAX];
    return real_symlink(remap(target, b1), remap(linkpath, b2));
}

int link(const char *oldpath, const char *newpath) {
    RESOLVE(link, int, const char *, const char *);
    char b1[PATH_MAX], b2[PATH_MAX];
    return real_link(remap(oldpath, b1), remap(newpath, b2));
}

/* ─── chdir ─────────────────────────────────────────────────────────────── */
int chdir(const char *path) {
    RESOLVE(chdir, int, const char *);
    char buf[PATH_MAX];
    return real_chdir(remap(path, buf));
}

/* ─── realpath ───────────────────────────────────────────────────────────── */
/*
 * apt calls realpath() on dpkg's status-file path before opening it.
 * The path contains the hardcoded com.termux prefix; remap it so realpath()
 * resolves against our actual installation directory.
 */
char *realpath(const char *path, char *resolved) {
    RESOLVE(realpath, char *, const char *, char *);
    char buf[PATH_MAX];
    return real_realpath(remap(path, buf), resolved);
}

/* ─── truncate / utimensat ───────────────────────────────────────────────── */
int truncate(const char *path, off_t length) {
    RESOLVE(truncate, int, const char *, off_t);
    char buf[PATH_MAX];
    return real_truncate(remap(path, buf), length);
}

int utimensat(int dirfd, const char *path, const struct timespec times[2], int flags) {
    RESOLVE(utimensat, int, int, const char *, const struct timespec *, int);
    char buf[PATH_MAX];
    return real_utimensat(dirfd, remap(path, buf), times, flags);
}

/* ─── opendir ────────────────────────────────────────────────────────────── */
/*
 * bionic's opendir() calls its own internal open() — not through the PLT —
 * so our open() hook is NOT invoked for paths that go via opendir().
 * Override opendir() directly so apt/dpkg directory scans
 * (apt.conf.d/, trusted.gpg.d/, sources.list.d/, dpkg/info/, …) are remapped.
 */
DIR *opendir(const char *path) {
    RESOLVE(opendir, DIR *, const char *);
    char buf[PATH_MAX];
    return real_opendir(remap(path, buf));
}

/* ─── fopen / fopen64 ────────────────────────────────────────────────────── */
/*
 * Same reasoning as opendir(): bionic's fopen() calls its own open() without
 * going through the PLT.  apt reads config/list files via stdio fopen() calls,
 * so we must intercept fopen() itself.
 */
FILE *fopen(const char *path, const char *mode) {
    RESOLVE(fopen, FILE *, const char *, const char *);
    char buf[PATH_MAX];
    return real_fopen(remap(path, buf), mode);
}

FILE *fopen64(const char *path, const char *mode) {
    RESOLVE(fopen64, FILE *, const char *, const char *);
    char buf[PATH_MAX];
    return real_fopen64(remap(path, buf), mode);
}
