/*
 * termux.c — PTY / subprocess JNI bridge
 * Package: com.aicode.studio.termux.terminal.JNI
 *
 * Ported from termux-app-0.118.3/terminal-emulator/src/main/jni/termux.c
 * Original: https://github.com/termux/termux-app
 *
 * JNI symbols renamed:
 *   Java_com_termux_terminal_JNI_*  →  Java_com_aicode_studio_termux_terminal_JNI_*
 *
 * Works as-is with targetSdkVersion=28 (untrusted_app_27 SELinux domain),
 * where execute_no_trans on app_data_file is still permitted — same as
 * original Termux 0.118.3.
 */

#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <android/log.h>

#define LOG_TAG "termux-jni"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define TERMUX_UNUSED(x) x __attribute__((__unused__))

static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

static int create_subprocess(JNIEnv* env,
        char const* cmd,
        char const* cwd,
        char* const argv[],
        char** envp,
        int* pProcessId,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

    char devname[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname))) {
        return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
    }

    /* Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display. */
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    /* Set initial window size. */
    struct winsize sz = {
        .ws_row    = (unsigned short) rows,
        .ws_col    = (unsigned short) columns,
        .ws_xpixel = (unsigned short) (columns * cell_width),
        .ws_ypixel = (unsigned short) (rows    * cell_height)
    };
    ioctl(ptm, TIOCSWINSZ, &sz);

    LOGI("createSubprocess: cmd=%s cwd=%s", cmd, cwd);

    pid_t pid = fork();
    if (pid < 0) {
        return throw_runtime_exception(env, "Fork failed");
    } else if (pid > 0) {
        /* Parent */
        *pProcessId = (int) pid;
        LOGI("createSubprocess: pid=%d ptm=%d slave=%s", pid, ptm, devname);
        return ptm;
    } else {
        /* Child */

        /* Clear signals which the Android java process may have blocked. */
        sigset_t signals_to_unblock;
        sigfillset(&signals_to_unblock);
        sigprocmask(SIG_UNBLOCK, &signals_to_unblock, 0);

        close(ptm);
        setsid();

        int pts = open(devname, O_RDWR);
        if (pts < 0) {
            LOGE("child: open slave pts '%s' failed: %s", devname, strerror(errno));
            _exit(1);
        }

        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);

        /* Close all file descriptors except stdin/stdout/stderr. */
        DIR* self_dir = opendir("/proc/self/fd");
        if (self_dir != NULL) {
            int self_dir_fd = dirfd(self_dir);
            struct dirent* entry;
            while ((entry = readdir(self_dir)) != NULL) {
                int fd = atoi(entry->d_name);
                if (fd > 2 && fd != self_dir_fd) close(fd);
            }
            closedir(self_dir);
        }

        /* Set environment using clearenv + putenv (matches original Termux approach). */
        clearenv();
        if (envp) for (; *envp; ++envp) putenv(*envp);

        /* Change to working directory. */
        if (chdir(cwd) != 0) {
            LOGE("child: chdir('%s') failed: %s — falling back to /", cwd, strerror(errno));
            chdir("/");
        }

        /* Execute the shell / command. */
        execvp(cmd, argv);

        /* execvp only returns on failure. */
        LOGE("child: execvp('%s') FAILED errno=%d (%s)", cmd, errno, strerror(errno));
        _exit(1);
    }
}

JNIEXPORT jint JNICALL Java_com_aicode_studio_termux_terminal_JNI_createSubprocess(
        JNIEnv* env,
        jclass TERMUX_UNUSED(clazz),
        jstring cmd,
        jstring cwd,
        jobjectArray args,
        jobjectArray envVars,
        jintArray processIdArray,
        jint rows,
        jint columns,
        jint cell_width,
        jint cell_height)
{
    jsize size = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = NULL;
    if (size > 0) {
        argv = (char**) malloc((size + 1) * sizeof(char*));
        if (!argv) return throw_runtime_exception(env, "Couldn't allocate argv array");
        for (int i = 0; i < size; ++i) {
            jstring arg_java_string = (jstring) (*env)->GetObjectArrayElement(env, args, i);
            char const* arg_utf8 = (*env)->GetStringUTFChars(env, arg_java_string, NULL);
            if (!arg_utf8) return throw_runtime_exception(env, "GetStringUTFChars() failed for argv");
            argv[i] = strdup(arg_utf8);
            (*env)->ReleaseStringUTFChars(env, arg_java_string, arg_utf8);
        }
        argv[size] = NULL;
    }

    size = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char** envp = NULL;
    if (size > 0) {
        envp = (char**) malloc((size + 1) * sizeof(char *));
        if (!envp) return throw_runtime_exception(env, "malloc() for envp array failed");
        for (int i = 0; i < size; ++i) {
            jstring env_java_string = (jstring) (*env)->GetObjectArrayElement(env, envVars, i);
            char const* env_utf8 = (*env)->GetStringUTFChars(env, env_java_string, 0);
            if (!env_utf8) return throw_runtime_exception(env, "GetStringUTFChars() failed for env");
            envp[i] = strdup(env_utf8);
            (*env)->ReleaseStringUTFChars(env, env_java_string, env_utf8);
        }
        envp[size] = NULL;
    }

    int procId = 0;
    char const* cmd_cwd  = (*env)->GetStringUTFChars(env, cwd, NULL);
    char const* cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    int ptm = create_subprocess(env, cmd_utf8, cmd_cwd, argv, envp, &procId,
                                rows, columns, cell_width, cell_height);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    (*env)->ReleaseStringUTFChars(env, cwd, cmd_cwd);

    if (argv) {
        for (char** tmp = argv; *tmp; ++tmp) free(*tmp);
        free(argv);
    }
    if (envp) {
        for (char** tmp = envp; *tmp; ++tmp) free(*tmp);
        free(envp);
    }

    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) return throw_runtime_exception(env, "GetPrimitiveArrayCritical(processIdArray) failed");
    *pProcId = procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);

    return ptm;
}

JNIEXPORT void JNICALL Java_com_aicode_studio_termux_terminal_JNI_setPtyWindowSize(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz),
        jint fd, jint rows, jint cols, jint cell_width, jint cell_height)
{
    struct winsize sz = {
        .ws_row    = (unsigned short) rows,
        .ws_col    = (unsigned short) cols,
        .ws_xpixel = (unsigned short) (cols * cell_width),
        .ws_ypixel = (unsigned short) (rows * cell_height)
    };
    ioctl(fd, TIOCSWINSZ, &sz);
}

JNIEXPORT void JNICALL Java_com_aicode_studio_termux_terminal_JNI_setPtyUTF8Mode(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_aicode_studio_termux_terminal_JNI_waitFor(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid)
{
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status))   return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL Java_com_aicode_studio_termux_terminal_JNI_close(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fileDescriptor)
{
    close(fileDescriptor);
}
