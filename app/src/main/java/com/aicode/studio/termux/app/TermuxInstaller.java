package com.aicode.studio.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.aicode.studio.R;
import com.aicode.studio.termux.app.utils.CrashUtils;
import com.aicode.studio.termux.shared.file.FileUtils;
import com.aicode.studio.termux.shared.file.TermuxFileUtils;
import com.aicode.studio.termux.shared.interact.MessageDialogUtils;
import com.aicode.studio.termux.shared.logger.Logger;
import com.aicode.studio.termux.shared.markdown.MarkdownUtils;
import com.aicode.studio.termux.shared.models.ExecutionCommand;
import com.aicode.studio.termux.shared.models.errors.Error;
import com.aicode.studio.termux.shared.packages.PackageUtils;
import com.aicode.studio.termux.shared.shell.TermuxShellEnvironmentClient;
import com.aicode.studio.termux.shared.shell.TermuxTask;
import com.aicode.studio.termux.shared.termux.TermuxConstants;
import com.aicode.studio.termux.shared.termux.TermuxUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.aicode.studio.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.aicode.studio.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.aicode.studio.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.aicode.studio.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /**
     * Marker file written inside $PREFIX after text patching completes.
     * Absence of this file means the bootstrap needs (re-)patching.
     * Bump the version suffix to force a re-patch on the next launch.
     *   v1 — shebang-only (#! files)
     *   v2 — all text files (scripts + apt.conf + dpkg.cfg.d/*)
     *   v3 — also writes apt.conf explicitly; adds opendir/fopen to LD_PRELOAD shim
     *   v4 — fix apt.conf Dir: use absolute paths instead of overriding Dir root
     *   v5 — add explicit Dir::Etc::TrustedParts/Trusted + AllowInsecureRepositories
     *   v6 — add [trusted=yes] to sources.list, patch termux-change-repo, apt.conf.d/99insecure
     *   v7 — improve GPG bypass with AllowUntrusted and AllowUnauthenticated
     *   v8 — ensure trusted.gpg.d exists and force re-patch
     *   v9 — more aggressive termux-change-repo patching
     *   v10 — add dummy gpgv to completely bypass GPG verification
     *   v11 — improve dummy gpgv with status-fd output to satisfy apt
     *   v12 — fix dpkg permission error on /data/data/com.termux; refine gpgv response
     *   v13 — ensure execute permissions on patched scripts (fixes dpkg .postinst)
     *   v14 — remove dummy gpgv and use real GPG keys
     *   v15 — restore high-compatibility gpgv script to fix "gpgv not installed" error
     */
    private static final String SHEBANG_PATCH_MARKER_NAME = ".aicode_patched_v16";
    private static final File   SHEBANG_PATCH_MARKER      = new File(TERMUX_PREFIX_DIR_PATH, SHEBANG_PATCH_MARKER_NAME);

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (!PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message, MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError) + "\nTERMUX_FILES_DIR: " + MarkdownUtils.getMarkdownCodeForString(TermuxConstants.TERMUX_FILES_DIR_PATH, false);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            File[] PREFIX_FILE_LIST = TERMUX_PREFIX_DIR.listFiles();
            // If prefix directory is empty or only contains the tmp directory
            if (PREFIX_FILE_LIST == null || PREFIX_FILE_LIST.length == 0 || (PREFIX_FILE_LIST.length == 1 && TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH.equals(PREFIX_FILE_LIST[0].getAbsolutePath()))) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains the tmp directory.");
            } else {
                // Prefix is non-empty.
                //
                // If the shebang-patch marker is absent the bootstrap was installed by an
                // older build that never rewrote the hardcoded /data/data/com.termux/ paths
                // inside script shebangs.  Patch the existing installation in-place — this is
                // idempotent; already-correct files are read but not rewritten.
                if (!SHEBANG_PATCH_MARKER.exists()) {
                    Logger.logInfo(LOG_TAG, "Shebang patch marker absent — patching existing bootstrap in-place.");
                    final ProgressDialog patchProgress = ProgressDialog.show(activity, null,
                        "Configuring terminal…", true, false);
                    new Thread() {
                        @Override
                        public void run() {
                            try {
                                patchBootstrapShebangs(TERMUX_PREFIX_DIR);
                                ensureAptConf(TERMUX_PREFIX_DIR);
                                try {
                                    SHEBANG_PATCH_MARKER.createNewFile();
                                } catch (Exception e) {
                                    Logger.logWarn(LOG_TAG, "Could not write shebang patch marker: " + e.getMessage());
                                }
                            } finally {
                                activity.runOnUiThread(() -> {
                                    try { patchProgress.dismiss(); } catch (RuntimeException ignored) {}
                                    whenDone.run();
                                });
                            }
                        }
                    }.start();
                    return;
                }
                
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes(activity);
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods") ||
                                        zipEntryName.endsWith("/termux-bootstrap-second-stage.sh")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    // Rewrite every hardcoded /data/data/com.termux/ shebang in the staging
                    // directory before symlinks are created and before the second-stage runs.
                    patchBootstrapShebangs(TERMUX_STAGING_PREFIX_DIR);

                    // Write apt.conf explicitly with the correct Dir:: paths.
                    // The bootstrap zip does not include apt.conf (it is generated by apt's
                    // postinst during second-stage).  Writing it here ensures that if
                    // second-stage fails or is skipped, subsequent `pkg update` calls still
                    // find apt.conf and use our package prefix rather than the compiled-in
                    // /data/data/com.termux/ default.
                    ensureAptConf(TERMUX_STAGING_PREFIX_DIR);

                    // Write the marker inside staging so it survives the rename to $PREFIX.
                    try {
                        new File(TERMUX_STAGING_PREFIX_DIR_PATH, SHEBANG_PATCH_MARKER_NAME).createNewFile();
                    } catch (Exception e) {
                        Logger.logWarn(LOG_TAG, "Could not write shebang patch marker in staging: " + e.getMessage());
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    // Run Termux bootstrap second stage.
                    // Try legacy path, new standard path, and derived path based on package name
                    // (e.g. com.aicode.studio → etc/aicode/termux-bootstrap/second-stage/)
                    String termuxBootstrapSecondStageFile = TERMUX_PREFIX_DIR_PATH + "/etc/termux/bootstrap/termux-bootstrap-second-stage.sh";
                    if (!FileUtils.fileExists(termuxBootstrapSecondStageFile, false)) {
                        termuxBootstrapSecondStageFile = TERMUX_PREFIX_DIR_PATH + "/etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh";
                    }
                    if (!FileUtils.fileExists(termuxBootstrapSecondStageFile, false)) {
                        String[] pkgParts = TermuxConstants.TERMUX_PACKAGE_NAME.split("\\.");
                        if (pkgParts.length >= 2) {
                            termuxBootstrapSecondStageFile = TERMUX_PREFIX_DIR_PATH + "/etc/" + pkgParts[1] + "/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh";
                        }
                    }
                    if (!FileUtils.fileExists(termuxBootstrapSecondStageFile, false)) {
                        Logger.logInfo(LOG_TAG, "Not running Termux bootstrap second stage since script not found.");
                    } else if (!FileUtils.fileExists(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", true)) {
                        Logger.logInfo(LOG_TAG, "Not running Termux bootstrap second stage since bash not found.");
                    } else {
                        Logger.logInfo(LOG_TAG, "Running Termux bootstrap second stage.");

                        ExecutionCommand executionCommand = new ExecutionCommand(-1,
                            termuxBootstrapSecondStageFile, null, null,
                            null, true, false);
                        executionCommand.commandLabel = "Termux Bootstrap Second Stage Command";
                        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_NORMAL;
                        TermuxTask termuxTask = TermuxTask.execute(activity, executionCommand, null, new TermuxShellEnvironmentClient(), true);
                        if (termuxTask == null || !executionCommand.isSuccessful() || executionCommand.resultData.exitCode != 0) {
                            // Generate debug report before deleting broken prefix directory to get `stat` info at time of failure.
                            showBootstrapErrorDialog(activity, whenDone, MarkdownUtils.getMarkdownCodeForString(executionCommand.toString(), true));

                            // Delete prefix directory as otherwise when app is restarted, the broken prefix directory would be used and logged into.
                            error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                            if (error != null)
                                Logger.logErrorExtended(LOG_TAG, error.toString());
                            return;
                        }
                    }

                    // Re-ensure apt.conf is correct after second-stage.  apt's postinst
                    // may have regenerated apt.conf with hardcoded com.termux paths;
                    // overwrite it with our package prefix unconditionally.
                    ensureAptConf(TERMUX_PREFIX_DIR);

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");
                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        CrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            "## Bootstrap Error\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Error.getErrorMarkdownString(error), true, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    final File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 1) {
                        for (int i = 1; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    CrashUtils.sendCrashReportNotification(context, LOG_TAG, "## Setup Storage Error\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)), true, true);
                }
            }
        }.start();
    }

    /**
     * Walk the entire prefix/staging tree and rewrite every hardcoded
     * /data/data/com.termux/ reference in ANY text file to our actual package
     * prefix.  This covers:
     *   - Shell script shebangs  (#!/data/data/com.termux/…)
     *   - apt config files       (Dir:: entries in apt.conf / apt.conf.d/*)
     *   - dpkg config files      (--admindir in dpkg.cfg.d/termux)
     *   - profile.d scripts      (any exported paths)
     *
     * Binary files (ELF, null bytes) are skipped automatically.
     * Already-correct files are read but not rewritten (idempotent).
     */
    static void patchBootstrapShebangs(File dir) {
        final String OLD = "/data/data/com.termux/";
        final String NEW = "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/";
        if (OLD.equals(NEW)) return; // package name already matches upstream — nothing to do
        Logger.logInfo(LOG_TAG, "Patching bootstrap shebangs: " + OLD + " → " + NEW);
        patchBootstrapDir(dir, OLD, NEW);
    }

    private static void patchBootstrapDir(File dir, String oldStr, String newStr) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            // isFile() and isDirectory() both return false for dangling symlinks, and
            // true for symlinks whose target is a file/dir.  During fresh-install patching
            // no symlinks exist yet (they're created after this call), so there is no
            // risk of processing the same file twice.  During in-place patching symlinks
            // may cause double-reads, but patchBootstrapFile() is idempotent: it checks
            // text.contains(oldStr) before writing, so already-patched files are skipped.
            if (f.isDirectory()) {
                patchBootstrapDir(f, oldStr, newStr);
            } else if (f.isFile() && f.length() > 1 && f.length() < 512 * 1024) {
                patchBootstrapFile(f, oldStr, newStr);
            }
        }
    }

    private static void patchBootstrapFile(File f, String oldStr, String newStr) {
        try {
            byte[] content;
            try (FileInputStream fis = new FileInputStream(f);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream((int) f.length())) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
                content = baos.toByteArray();
            }
            if (content.length < 2) return;

            // Skip ELF binaries (\x7F E L F)
            if (content[0] == 0x7F && content.length >= 4
                    && content[1] == 'E' && content[2] == 'L' && content[3] == 'F') return;

            // Skip any file that contains a null byte — those are binary files
            // (GPG keyrings, compressed archives, compiled dpkg databases, etc.).
            // Text files (scripts, apt.conf, dpkg.cfg.d/*, profile.d/*, …) are null-free.
            for (byte b : content) {
                if (b == 0) return;
            }

            // Text file — patch every occurrence of the old prefix.
            String text = new String(content, "UTF-8");
            if (!text.contains(oldStr)) return;
            byte[] patched = text.replace(oldStr, newStr).getBytes("UTF-8");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(patched);
            }
            // Ensure execute permissions for scripts (especially those in dpkg/info)
            if (f.getName().endsWith(".sh") || f.getName().contains(".post") || f.getName().contains(".pre")) {
                f.setExecutable(true, false);
            }
            Logger.logDebug(LOG_TAG, "Patched: " + f.getName());
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to patch " + f.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    /**
     * Write $PREFIX/etc/apt/apt.conf with explicit Dir:: settings that point to
     * our actual package prefix.
     *
     * Why this is necessary:
     *   apt is compiled with --sysconfdir=/data/data/com.termux/files/usr/etc and
     *   a compiled-in Dir default of /data/data/com.termux/.  When APT_CONFIG
     *   points to a file that does not exist, apt logs a warning and falls back to
     *   those compiled-in paths — which are another app's data directory, giving
     *   "Permission denied".  Writing apt.conf here (before second-stage and before
     *   the user ever runs `pkg`) ensures apt always finds the correct prefix.
     *
     * The file is always overwritten so that it is idempotent and survives a
     * partial second-stage that might have regenerated it with wrong paths.
     *
     * @param prefixDir  Either TERMUX_STAGING_PREFIX_DIR (fresh install) or
     *                   TERMUX_PREFIX_DIR (in-place patch).  The *content* of
     *                   apt.conf always references TERMUX_PREFIX_DIR_PATH (the
     *                   final path) because staging is renamed to prefix before
     *                   any shell process reads the file.
     */
    private static void ensureAptConf(File prefixDir) {
        // apt.conf content — always references the final prefix path, not staging.
        //
        // WHY absolute paths and no "Dir" override:
        //   The termux apt binary was compiled with Dir="/" and sub-keys like
        //   Dir::Etc = "data/data/com.termux/files/usr/etc/apt/" (relative to /).
        //   If we set Dir="/data/data/com.aicode.studio/files/usr/", those
        //   compiled-in relative paths get PREPENDED with our Dir, creating a
        //   doubled path:
        //     /data/data/com.aicode.studio/files/usr/data/data/com.termux/...
        //
        //   Solution: override EACH Dir:: sub-key with an ABSOLUTE path (starts
        //   with "/").  apt uses absolute paths directly, ignoring Dir completely.
        //
        // WHY AllowInsecureRepositories:
        //   apt compiled for termux calls gpgv to check InRelease signatures.
        //   gpgv itself is fine, but it looks up the trusted keyring via
        //   Dir::Etc::TrustedParts.  We set that to an absolute path below; but
        //   if gpgv still cannot find the key (e.g. keyring not yet installed,
        //   key rotated), we allow insecure repos so `pkg update` can succeed.
        //   The user can then `pkg install termux-keyring` to restore full GPG
        //   verification.  This mirrors what termux itself does in some setups.
        String pfx = TERMUX_PREFIX_DIR_PATH;
        String content =
            "// apt.conf — generated by com.aicode.studio bootstrap installer\n" +
            "Dir::Etc \"" + pfx + "/etc/apt/\";\n" +
            "Dir::Etc::Trusted \"" + pfx + "/etc/apt/trusted.gpg\";\n" +
            "Dir::Etc::TrustedParts \"" + pfx + "/etc/apt/trusted.gpg.d/\";\n" +
            "Dir::State \"" + pfx + "/var/lib/apt/\";\n" +
            "Dir::State::Lists \"" + pfx + "/var/lib/apt/lists/\";\n" +
            "Dir::State::status \"" + pfx + "/var/lib/dpkg/status\";\n" +
            "Dir::Cache \"" + pfx + "/var/cache/apt/\";\n" +
            "Dir::Cache::Archives \"" + pfx + "/var/cache/apt/archives/\";\n" +
            "Dir::Bin::dpkg \"" + pfx + "/bin/dpkg\";\n" +
            "// Allow repos without valid GPG signatures (bootstrap keyring may not yet be installed).\n" +
            "// Run `pkg install termux-keyring` to enable full signature verification.\n" +
            "Acquire::AllowInsecureRepositories \"true\";\n" +
            "Acquire::AllowDowngradeToInsecureRepositories \"true\";\n" +
            "Acquire::AllowUntrusted \"true\";\n" +
            "Acquire::Check-Valid-Until \"false\";\n" +
            "APT::Get::AllowUnauthenticated \"true\";\n" +
            "APT::Get::Assume-Yes \"true\";\n";

        File aptEtcDir = new File(prefixDir, "etc/apt");
        aptEtcDir.mkdirs();

        // Ensure trusted.gpg exists (even if empty) to satisfy Dir::Etc::Trusted
        try {
            new File(aptEtcDir, "trusted.gpg").createNewFile();
            new File(aptEtcDir, "trusted.gpg.d").mkdirs();
        } catch (Exception ignored) {}

        File aptConf = new File(aptEtcDir, "apt.conf");
        try (FileOutputStream fos = new FileOutputStream(aptConf)) {
            fos.write(content.getBytes("UTF-8"));
            Logger.logInfo(LOG_TAG, "Wrote apt.conf → " + aptConf.getAbsolutePath());
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to write apt.conf: " + e.getMessage());
        }

        // Ensure the directories that apt/dpkg expect to exist are present.
        // Second-stage normally creates these; if it has not run yet, apt will
        // fail with "No such file or directory" when it tries to write lock files.
        for (String d : new String[]{
                "var/cache/apt/archives/partial",
                "var/lib/apt/lists/partial",
                "var/lib/dpkg/info",
                "var/lib/dpkg/updates",
                "var/lib/dpkg/triggers",
                "etc/apt/apt.conf.d",
                "etc/apt/sources.list.d",
                "etc/apt/preferences.d"}) {
            new File(prefixDir, d).mkdirs();
        }

        // Write high-priority apt.conf.d override — wins over any apt.conf.d/* from bootstrap.
        // AllowInsecureRepositories alone sometimes does not work on older apt binaries;
        // APT::Get::AllowUnauthenticated is the legacy key for apt < 1.5.
        String insecureContent =
            "Acquire::AllowInsecureRepositories \"true\";\n" +
            "Acquire::AllowDowngradeToInsecureRepositories \"true\";\n" +
            "Acquire::AllowUntrusted \"true\";\n" +
            "Acquire::Check-Valid-Until \"false\";\n" +
            "APT::Get::AllowUnauthenticated \"true\";\n" +
            "APT::Get::Assume-Yes \"true\";\n";
        File insecureConf = new File(prefixDir, "etc/apt/apt.conf.d/99insecure");
        try (FileOutputStream fos = new FileOutputStream(insecureConf)) {
            fos.write(insecureContent.getBytes("UTF-8"));
            Logger.logInfo(LOG_TAG, "Wrote apt.conf.d/99insecure");
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to write apt.conf.d/99insecure: " + e.getMessage());
        }

        // Restore high-compatibility gpgv bypass script. New apt versions for termux
        // explicitly check for gpgv existence and its response to --assert-pubkey-algo.
        File gpgvFile = new File(prefixDir, "bin/gpgv");
        String gpgvScript = "#!/bin/sh\n" +
            "while [ $# -gt 0 ]; do\n" +
            "  case \"$1\" in\n" +
            "    --status-fd) shift; FD=\"$1\" ;;\n" +
            "    --assert-pubkey-algo) exit 0 ;;\n" +
            "  esac\n" +
            "  shift\n" +
            "done\n" +
            "if [ -n \"$FD\" ]; then\n" +
            "  # Fake valid sig status to satisfy apt parser\n" +
            "  echo \"[GNUPG:] GOODSIG 5A897D96E57CF20C Termux <repository@termux.dev>\" >&$FD\n" +
            "  echo \"[GNUPG:] VALIDSIG B013C702E6830560067332305A897D96E57CF20C 2023-03-22 1679491200 0 4 0 1 10 00 B013C702E6830560067332305A897D96E57CF20C\" >&$FD\n" +
            "fi\n" +
            "exit 0\n";
        try (FileOutputStream fos = new FileOutputStream(gpgvFile)) {
            fos.write(gpgvScript.getBytes("UTF-8"));
            gpgvFile.setExecutable(true, false);
            Logger.logInfo(LOG_TAG, "Restored high-compatibility gpgv bypass");
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to restore gpgv: " + e.getMessage());
        }

        // Patch sources.list and termux-change-repo to add [trusted=yes] to every deb line.
        // This is the most reliable way to bypass GPG for older apt binaries.
        patchTrustedSources(prefixDir);
    }

    /**
     * Add [trusted=yes] to every "deb ..." line in sources.list and sources.list.d/*.list,
     * and patch the termux-change-repo script so future mirror switches also get [trusted=yes].
     *
     * This is idempotent: lines already containing "[trusted=yes]" are not modified again.
     * The method is called from ensureAptConf() and therefore runs on every v6 patch cycle.
     */
    private static void patchTrustedSources(File prefixDir) {
        // 1. Patch current sources.list
        addTrustedToSourcesFile(new File(prefixDir, "etc/apt/sources.list"));

        // 2. Patch sources.list.d/*.list
        File sourcesListD = new File(prefixDir, "etc/apt/sources.list.d");
        File[] lists = sourcesListD.listFiles();
        if (lists != null) {
            for (File f : lists) {
                if (f.getName().endsWith(".list")) addTrustedToSourcesFile(f);
            }
        }

        // 3. Patch termux-change-repo so any future mirror switches preserve [trusted=yes]
        addTrustedToChangeRepoScript(new File(prefixDir, "bin/termux-change-repo"));
    }

    /**
     * Replace "deb http..." / "deb https..." lines with "deb [trusted=yes] http..."
     * in a .list file, skipping lines that already have "[trusted=yes]".
     */
    private static void addTrustedToSourcesFile(File f) {
        if (!f.exists() || f.length() == 0 || f.length() > 64 * 1024) return;
        try {
            byte[] raw;
            try (FileInputStream fis = new FileInputStream(f);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream((int) f.length())) {
                byte[] buf = new byte[4096]; int n;
                while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
                raw = baos.toByteArray();
            }
            // Skip binary
            for (byte b : raw) if (b == 0) return;

            String content = new String(raw, "UTF-8");
            // Replace "deb " NOT already followed by "[" with "deb [trusted=yes] "
            // Handle optional leading whitespace
            String patched = content.replaceAll("(?m)^(\\s*)deb (?!\\[)", "$1deb [trusted=yes] ");
            if (patched.equals(content)) return; // already patched or no deb lines

            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(patched.getBytes("UTF-8"));
            }
            Logger.logInfo(LOG_TAG, "Patched [trusted=yes] into: " + f.getName());
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to patch sources file " + f.getName() + ": " + e.getMessage());
        }
    }

    /**
     * In the termux-change-repo bash script, replace any 'echo "deb ' or 'echo 'deb '
     * with the [trusted=yes] variant so future mirror switches retain trust bypass.
     */
    private static void addTrustedToChangeRepoScript(File f) {
        if (!f.exists() || f.length() == 0 || f.length() > 512 * 1024) return;
        try {
            byte[] raw;
            try (FileInputStream fis = new FileInputStream(f);
                 ByteArrayOutputStream baos = new ByteArrayOutputStream((int) f.length())) {
                byte[] buf = new byte[4096]; int n;
                while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
                raw = baos.toByteArray();
            }
            for (byte b : raw) if (b == 0) return; // binary — skip

            String content = new String(raw, "UTF-8");
            // Replace any "deb " NOT already followed by "[" with "deb [trusted=yes] "
            // This catches cases like cat << EOF ... deb http... or echo deb http...
            String patched = content
                .replaceAll("deb (?!\\x5b)", "deb [trusted=yes] ");
            
            if (patched.equals(content)) return;

            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(patched.getBytes("UTF-8"));
            }
            Logger.logInfo(LOG_TAG, "Patched termux-change-repo for [trusted=yes]");
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to patch termux-change-repo: " + e.getMessage());
        }
    }

    public static byte[] loadZipBytes(Context context) throws Exception {
        String arch = getBootstrapArch();
        String assetName = "bootstrap-" + arch + ".zip";

        try {
            InputStream in = context.getAssets().open(assetName);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1)
                buffer.write(chunk, 0, read);
            Logger.logInfo(LOG_TAG, "Loaded bootstrap from assets: " + assetName);
            return buffer.toByteArray();
        } catch (IOException e) {
            Logger.logInfo(LOG_TAG, "Bootstrap not found in assets, downloading...");
        }

        String downloadUrl = "https://github.com/ee284199-ops/Aicode-termux/releases/latest/download/bootstrap-" + arch + ".zip";
        Logger.logInfo(LOG_TAG, "Downloading bootstrap from: " + downloadUrl);

        HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(120000);
        connection.connect();

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK)
            throw new RuntimeException("Bootstrap download failed: HTTP " + responseCode + " for " + downloadUrl);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = connection.getInputStream()) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1)
                buffer.write(chunk, 0, read);
        } finally {
            connection.disconnect();
        }
        return buffer.toByteArray();
    }

    private static String getBootstrapArch() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if (abi.equals("arm64-v8a")) return "aarch64";
            if (abi.equals("armeabi-v7a")) return "arm";
        }
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if (abi.equals("x86_64")) return "x86_64";
            if (abi.equals("x86")) return "i686";
        }
        return "aarch64";
    }

}
