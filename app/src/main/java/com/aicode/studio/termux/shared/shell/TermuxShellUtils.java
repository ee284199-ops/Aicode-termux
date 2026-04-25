package com.aicode.studio.termux.shared.shell;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import androidx.annotation.NonNull;

import com.aicode.studio.termux.shared.android.SELinuxUtils;
import com.aicode.studio.termux.shared.data.DataUtils;
import com.aicode.studio.termux.shared.models.errors.Error;
import com.aicode.studio.termux.shared.termux.TermuxConstants;
import com.aicode.studio.termux.shared.file.FileUtils;
import com.aicode.studio.termux.shared.logger.Logger;
import com.aicode.studio.termux.shared.packages.PackageUtils;
import com.aicode.studio.termux.shared.termux.TermuxUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TermuxShellUtils {

    public static String TERMUX_VERSION_NAME;
    public static String TERMUX_IS_DEBUGGABLE_BUILD;
    public static String TERMUX_APP_PID;
    public static String TERMUX_APK_RELEASE;

    public static String TERMUX_API_VERSION_NAME;

    public static String getDefaultWorkingDirectoryPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    public static String getDefaultBinPath() {
        return TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;
    }

    public static String[] buildEnvironment(Context currentPackageContext, boolean isFailSafe, String workingDirectory) {
        TermuxConstants.TERMUX_HOME_DIR.mkdirs();

        if (workingDirectory == null || workingDirectory.isEmpty())
            workingDirectory = getDefaultWorkingDirectoryPath();

        List<String> environment = new ArrayList<>();

        loadTermuxEnvVariables(currentPackageContext);

        if (TERMUX_VERSION_NAME != null)
            environment.add("TERMUX_VERSION=" + TERMUX_VERSION_NAME);
        if (TERMUX_IS_DEBUGGABLE_BUILD != null)
            environment.add("TERMUX_IS_DEBUGGABLE_BUILD=" + TERMUX_IS_DEBUGGABLE_BUILD);
        if (TERMUX_APP_PID != null)
            environment.add("TERMUX_APP_PID=" + TERMUX_APP_PID);
        if (TERMUX_APK_RELEASE != null)
            environment.add("TERMUX_APK_RELEASE=" + TERMUX_APK_RELEASE);

        if (TERMUX_API_VERSION_NAME != null)
            environment.add("TERMUX_API_VERSION=" + TERMUX_API_VERSION_NAME);



        environment.add("TERM=xterm-256color");
        environment.add("COLORTERM=truecolor");

        try {
            ApplicationInfo applicationInfo = currentPackageContext.getPackageManager().getApplicationInfo(
                TermuxConstants.TERMUX_PACKAGE_NAME, 0);
            if (applicationInfo != null && !applicationInfo.enabled) {
                applicationInfo = null;
            }

            if (applicationInfo != null) {
                // Use the /data/data/ path (not /data/user/0/ which dataDir may return on API 29+)
                // so that the value is consistent with TERMUX_PREFIX_DIR_PATH and the bootstrap.
                environment.add("TERMUX_APP__DATA_DIR=" + TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH);
                // TERMUX_APP__LEGACY_DATA_DIR is the compiled-in prefix that bootstrap binaries
                // reference (i.e. what was hard-coded at build time).  Bootstrap packages from
                // the official Termux repository are always compiled for com.termux, so we
                // hard-code that here.  libtermux-exec uses this to know which path to redirect.
                environment.add("TERMUX_APP__LEGACY_DATA_DIR=/data/data/com.termux");

                environment.add("TERMUX_APP__SE_FILE_CONTEXT=" + SELinuxUtils.getFileContext(applicationInfo.dataDir));

                String seInfoUser = PackageUtils.getApplicationInfoSeInfoUserForPackage(applicationInfo);
                environment.add("TERMUX_APP__SE_INFO=" + PackageUtils.getApplicationInfoSeInfoForPackage(applicationInfo) +
                    (DataUtils.isNullOrEmpty(seInfoUser) ? "" : seInfoUser));
            }

        } catch (final Exception e) {
            // Ignore
        }

        // Both names used by different versions of libtermux-exec; set both for compatibility.
        environment.add("TERMUX__ROOTFS_DIR=" + TermuxConstants.TERMUX_FILES_DIR_PATH);
        environment.add("TERMUX__ROOTFS=" + TermuxConstants.TERMUX_FILES_DIR_PATH);
        environment.add("HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.add("TERMUX__HOME=" + TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.add("PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        environment.add("TERMUX__PREFIX=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH);

        environment.add("TERMUX__SE_PROCESS_CONTEXT=" + SELinuxUtils.getContext());

        environment.add("BOOTCLASSPATH=" + System.getenv("BOOTCLASSPATH"));
        environment.add("ANDROID_ROOT=" + System.getenv("ANDROID_ROOT"));
        environment.add("ANDROID_DATA=" + System.getenv("ANDROID_DATA"));
        // EXTERNAL_STORAGE is needed for /system/bin/am to work on at least
        // Samsung S7 - see https://plus.google.com/110070148244138185604/posts/gp8Lk3aCGp3.
        environment.add("EXTERNAL_STORAGE=" + System.getenv("EXTERNAL_STORAGE"));

        // These variables are needed if running on Android 10 and higher.
        addToEnvIfPresent(environment, "ANDROID_ART_ROOT");
        addToEnvIfPresent(environment, "DEX2OATBOOTCLASSPATH");
        addToEnvIfPresent(environment, "ANDROID_I18N_ROOT");
        addToEnvIfPresent(environment, "ANDROID_RUNTIME_ROOT");
        addToEnvIfPresent(environment, "ANDROID_TZDATA_ROOT");

        environment.add("ANDROID__BUILD_VERSION_SDK=" + Build.VERSION.SDK_INT);

        if (isFailSafe) {
            // Keep the default path so that system binaries can be used in the failsafe session.
            environment.add("PATH=" + System.getenv("PATH"));
        } else {
            environment.add("LANG=en_US.UTF-8");
            environment.add("PATH=" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            environment.add("PWD=" + workingDirectory);
            environment.add("TMPDIR=" + TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);

            // Bootstrap binaries are compiled with RPATH=/data/data/com.termux/files/usr/lib
            // hardcoded. Since our package name is different, the dynamic linker cannot find
            // shared libraries via RPATH. LD_LIBRARY_PATH is checked before RPATH, so setting
            // it to our actual lib directory makes the binaries load correctly.
            environment.add("LD_LIBRARY_PATH=" + TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);

            // dpkg and apt are compiled with --prefix=/data/data/com.termux/files/usr so
            // their built-in default paths are wrong for our package name.  Override the
            // critical path env vars so dpkg/apt use our actual prefix even if LD_PRELOAD
            // cannot intercept every internal code path (e.g. realpath(), internal DSO calls).
            environment.add("DPKG_ADMINDIR=" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/var/lib/dpkg");
            environment.add("DPKG_DATADIR="  + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/share/dpkg");
            environment.add("APT_CONFIG="    + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/apt.conf");

            // LD_PRELOAD: use our APK-bundled full path interceptor (termux_exec.c / libtermux-exec.so).
            //
            // This library intercepts ALL libc path-taking functions:
            //   open, open64, openat, opendir, fopen, fopen64, execve,
            //   access, faccessat, stat, lstat, fstatat, readlink, readlinkat,
            //   chmod, chown, mkdir, rmdir, unlink, rename, symlink, link, chdir,
            //   realpath, truncate, utimensat
            // and remaps every /data/data/com.termux/ prefix to /data/data/com.aicode.studio/.
            //
            // We use OUR library instead of the bootstrap's libtermux-exec-ld-preload.so (proxy)
            // because the proxy uses a hardcoded /data/data/com.termux/ absolute path in its
            // dlopen() call to load the direct library — which fails in our package context.
            // Without the direct library loaded, the proxy provides exec*()-only interception,
            // which does NOT fix dpkg's compiled-in sysconfdir (opendir) or bash's profile (open).
            try {
                String nativeLibDir = currentPackageContext.getApplicationInfo().nativeLibraryDir;
                // Our full path-remapping shim, compiled from app/src/main/cpp/termux_exec.c.
                java.io.File apkPathRedirector = new java.io.File(nativeLibDir, "libtermux-exec.so");
                if (apkPathRedirector.exists()) {
                    environment.add("LD_PRELOAD=" + apkPathRedirector.getAbsolutePath());
                    Logger.logInfo("TermuxShellUtils", "LD_PRELOAD → " + apkPathRedirector.getAbsolutePath());
                } else {
                    Logger.logWarn("TermuxShellUtils", "libtermux-exec.so not found in " + nativeLibDir);
                }
            } catch (Exception e) {
                Logger.logWarn("TermuxShellUtils", "Could not set LD_PRELOAD: " + e.getMessage());
            }
        }

        return environment.toArray(new String[0]);
    }

    public static void addToEnvIfPresent(List<String> environment, String name) {
        String value = System.getenv(name);
        if (value != null) {
            environment.add(name + "=" + value);
        }
    }

    public static String[] setupProcessArgs(@NonNull String fileToExecute, String[] arguments) {
        // The file to execute may either be:
        // - An elf file, in which we execute it directly.
        // - A script file without shebang, which we execute with our standard shell $PREFIX/bin/sh instead of the
        //   system /system/bin/sh. The system shell may vary and may not work at all due to LD_LIBRARY_PATH.
        // - A file with shebang, which we try to handle with e.g. /bin/foo -> $PREFIX/bin/foo.
        String interpreter = null;
        try {
            File file = new File(fileToExecute);
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[256];
                int bytesRead = in.read(buffer);
                if (bytesRead > 4) {
                    if (buffer[0] == 0x7F && buffer[1] == 'E' && buffer[2] == 'L' && buffer[3] == 'F') {
                        // Elf file, do nothing.
                    } else if (buffer[0] == '#' && buffer[1] == '!') {
                        // Try to parse shebang.
                        StringBuilder builder = new StringBuilder();
                        for (int i = 2; i < bytesRead; i++) {
                            char c = (char) buffer[i];
                            if (c == ' ' || c == '\n') {
                                if (builder.length() == 0) {
                                    // Skip whitespace after shebang.
                                } else {
                                    // End of shebang.
                                    String executable = builder.toString();
                                    if (executable.startsWith("/usr") || executable.startsWith("/bin")) {
                                        String[] parts = executable.split("/");
                                        String binary = parts[parts.length - 1];
                                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + binary;
                                    } else if (executable.startsWith("/data/data/com.termux/files/usr/")) {
                                        // Bootstrap scripts may have shebangs hardcoded to
                                        // /data/data/com.termux/... — remap to our actual prefix.
                                        String binary = executable.substring("/data/data/com.termux/files/usr/bin/".length());
                                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + binary;
                                    }
                                    break;
                                }
                            } else {
                                builder.append(c);
                            }
                        }
                    } else {
                        // No shebang and no ELF, use standard shell.
                        interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                    }
                }
            }
        } catch (IOException e) {
            // Ignore.
        }

        List<String> result = new ArrayList<>();
        if (interpreter != null) result.add(interpreter);
        result.add(fileToExecute);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }

    public static void clearTermuxTMPDIR(boolean onlyIfExists) {
        if(onlyIfExists && !FileUtils.directoryFileExists(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, false))
            return;

        Error error;
        error = FileUtils.clearDirectory("$TMPDIR", FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null));
        if (error != null) {
            Logger.logErrorExtended(error.toString());
        }
    }

    public static void loadTermuxEnvVariables(Context currentPackageContext) {
        String termuxAPKReleaseOld = TERMUX_APK_RELEASE;
        TERMUX_VERSION_NAME = TERMUX_IS_DEBUGGABLE_BUILD = TERMUX_APP_PID = TERMUX_APK_RELEASE = null;

        // Check if Termux app is installed and not disabled
        if (TermuxUtils.isTermuxAppInstalled(currentPackageContext) == null) {
            // This function may be called by a different package like a plugin, so we get version for Termux package via its context
            Context termuxPackageContext = TermuxUtils.getTermuxPackageContext(currentPackageContext);
            if (termuxPackageContext != null) {
                TERMUX_VERSION_NAME = PackageUtils.getVersionNameForPackage(termuxPackageContext);
                TERMUX_IS_DEBUGGABLE_BUILD = PackageUtils.isAppForPackageADebuggableBuild(termuxPackageContext) ? "1" : "0";

                TERMUX_APP_PID = TermuxUtils.getTermuxAppPID(currentPackageContext);

                // Getting APK signature is a slightly expensive operation, so do it only when needed
                if (termuxAPKReleaseOld == null) {
                    String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(termuxPackageContext);
                    if (signingCertificateSHA256Digest != null)
                        TERMUX_APK_RELEASE = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest).replaceAll("[^a-zA-Z]", "_").toUpperCase();
                } else {
                    TERMUX_APK_RELEASE = termuxAPKReleaseOld;
                }
            }
        }


        TERMUX_API_VERSION_NAME = null;

        // Check if Termux:API app is installed and not disabled
        if (TermuxUtils.isTermuxAPIAppInstalled(currentPackageContext) == null) {
            // This function may be called by a different package like a plugin, so we get version for Termux:API package via its context
            Context termuxAPIPackageContext = TermuxUtils.getTermuxAPIPackageContext(currentPackageContext);
            if (termuxAPIPackageContext != null)
                TERMUX_API_VERSION_NAME = PackageUtils.getVersionNameForPackage(termuxAPIPackageContext);
        }
    }

}
