package com.hfm.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5: Hidden Vault & Secure Playback
 * Manages the secure local storage of reconstructed files and provides a secure,
 * temporary playback mechanism with a LifecycleObserver Kill-Switch.
 */
public class SecureVaultManager {

    private static final String TAG = "SecureVaultManager";
    private static final String VAULT_DIR_NAME = ".vault";
    private static final String TEMP_PLAYBACK_DIR = "secure_cache";

    private final Context context;

    public SecureVaultManager(Context context) {
        this.context = context;
    }

    /**
     * Creates and returns a reference to the hidden Vault directory.
     * Drops a .nomedia file to prevent Android MediaScanner from indexing the files.
     */
    public File getVaultDirectory() {
        File vaultDir = new File(context.getExternalFilesDir(null), VAULT_DIR_NAME);
        if (!vaultDir.exists()) {
            if (vaultDir.mkdirs()) {
                try {
                    new File(vaultDir, ".nomedia").createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create .nomedia file in vault");
                }
            }
        }
        return vaultDir;
    }

    /**
     * Creates a new empty file in the vault for the ReconstructionEngine to write to.
     */
    public File createVaultFile(String originalFileName) {
        File vaultDir = getVaultDirectory();
        // Append a UUID to prevent naming collisions
        String safeName = UUID.randomUUID().toString().substring(0, 8) + "_" + originalFileName;
        return new File(vaultDir, safeName);
    }

    /**
     * MAIN ENTRY POINT: Updated to show Choice Dialog (Internal vs External)
     */
    public void playSecurely(final File vaultFile, final String originalFileName) {
        if (!vaultFile.exists()) {
            Toast.makeText(context, "Secure file not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Secure Playback");
        builder.setMessage("How would you like to play this file?");

        builder.setPositiveButton("RAM Mode (Internal)", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                handleInternalPlayback(vaultFile, originalFileName);
            }
        });

        builder.setNeutralButton("External Player", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                handleExternalPlayback(vaultFile, originalFileName);
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /**
     * MODE 1: INTERNAL RAM PLAYBACK
     * Logic: Starts the internal app activity corresponding to the file type.
     */
    private void handleInternalPlayback(File vaultFile, String originalFileName) {
        try {
            String path = vaultFile.getAbsolutePath();
            String ext = getExtension(originalFileName);
            Intent intent = null;

            if (isImage(ext)) {
                ArrayList<String> list = new ArrayList<>();
                list.add(path);
                intent = new Intent(context, ImageViewerActivity.class);
                intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_FILE_PATH_LIST, list);
                intent.putExtra(ImageViewerActivity.EXTRA_CURRENT_INDEX, 0);
            } else if (isVideo(ext)) {
                ArrayList<String> list = new ArrayList<>();
                list.add(path);
                intent = new Intent(context, VideoViewerActivity.class);
                intent.putStringArrayListExtra(VideoViewerActivity.EXTRA_FILE_PATH_LIST, list);
                intent.putExtra(VideoViewerActivity.EXTRA_CURRENT_INDEX, 0);
            } else if (isAudio(ext)) {
                ArrayList<String> list = new ArrayList<>();
                list.add(path);
                intent = new Intent(context, AudioPlayerActivity.class);
                intent.putStringArrayListExtra(AudioPlayerActivity.EXTRA_FILE_PATH_LIST, list);
                intent.putExtra(AudioPlayerActivity.EXTRA_CURRENT_INDEX, 0);
            } else if (ext.equals("pdf")) {
                intent = new Intent(context, PdfViewerActivity.class);
                intent.putExtra(PdfViewerActivity.EXTRA_FILE_PATH, path);
            } else {
                intent = new Intent(context, TextViewerActivity.class);
                intent.putExtra(TextViewerActivity.EXTRA_FILE_PATH, path);
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            showDetailedErrorDialog("Internal Playback Error", e);
        }
    }

    /**
     * MODE 2: EXTERNAL MEDIA PLAYER (Disk/Cache Mode)
     * Logic: Copies file to cache, fires Intent with FileProvider, activates Kill-Switch.
     */
    private void handleExternalPlayback(File vaultFile, String originalFileName) {
        File tempCacheDir = new File(context.getCacheDir(), TEMP_PLAYBACK_DIR);
        if (!tempCacheDir.exists()) tempCacheDir.mkdirs();

        final File tempPlayFile = new File(tempCacheDir, originalFileName);

        try {
            // Your original 8192-byte buffer loop logic
            copyToCache(vaultFile, tempPlayFile);

            // Generate URI via FileProvider
            Uri fileUri = FileProvider.getUriForFile(
                    context, 
                    "com.hfm.app.provider", 
                    tempPlayFile
            );

            String mimeType = getMimeType(originalFileName);

            Intent playIntent = new Intent(Intent.ACTION_VIEW);
            playIntent.setDataAndType(fileUri, mimeType);
            playIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            playIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(playIntent);

            // Activate modified Kill-Switch to prevent race condition
            activatePlaybackKillSwitch(tempPlayFile);

        } catch (Exception e) {
            showDetailedErrorDialog("External Playback Error", e);
        }
    }

    /**
     * THE KILL-SWITCH: Monitors the app's lifecycle via ProcessLifecycleOwner.
     * FIX: Added 'hasLeftApp' check to prevent immediate shredding when the player/menu opens.
     */
    private void activatePlaybackKillSwitch(final File tempFile) {
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            private boolean hasLeftApp = false;

            @Override
            public void onStop(LifecycleOwner owner) {
                // Triggered when the user leaves HFM (to the Player or Resolver Menu)
                hasLeftApp = true;
            }

            @Override
            public void onStart(LifecycleOwner owner) {
                // Triggered when the user returns to HFM
                if (hasLeftApp) {
                    Log.d(TAG, "Kill-Switch Activated: Shredding secure cache file.");
                    shredFile(tempFile);
                    ProcessLifecycleOwner.get().getLifecycle().removeObserver(this);
                }
            }
        });
    }

    /**
     * FORENSIC SHREDDER: Your original logic to zero out file bytes.
     */
    private void shredFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            long length = file.length();
            FileOutputStream fos = new FileOutputStream(file);
            // Original 8192-byte zeroes loop
            byte[] zeroes = new byte[8192];
            long written = 0;
            while (written < length) {
                int toWrite = (int) Math.min(zeroes.length, length - written);
                fos.write(zeroes, 0, toWrite);
                written += toWrite;
            }
            fos.flush();
            fos.close();
            file.delete();
            Log.d(TAG, "File successfully shredded.");
        } catch (IOException e) {
            Log.e(TAG, "Failed to shred file, falling back to standard delete", e);
            file.delete();
        }
    }

    /**
     * Your original 8192-byte buffer copy logic.
     */
    private void copyToCache(File source, File dest) throws IOException {
        InputStream in = new FileInputStream(source);
        OutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    /**
     * ADVANCED ERROR REPORTING: Displays full Java StackTrace in scrollable dialog.
     */
    private void showDetailedErrorDialog(String title, Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        final String detailedError = sw.toString();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);

        // Scrollable TextView for the stacktrace
        final TextView errorTextView = new TextView(context);
        errorTextView.setText(detailedError);
        errorTextView.setPadding(40, 40, 40, 40);
        errorTextView.setTextIsSelectable(true);

        builder.setView(errorTextView);

        builder.setPositiveButton("Copy Error", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("HFM_Error_Log", detailedError);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "Error copied to clipboard.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        builder.setNegativeButton("Close", null);
        builder.show();
    }

    private String getExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) return fileName.substring(lastDot + 1).toLowerCase();
        return "";
    }

    private boolean isImage(String ext) {
        return Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(ext);
    }

    private boolean isVideo(String ext) {
        return Arrays.asList("mp4", "3gp", "mkv", "webm", "avi").contains(ext);
    }

    private boolean isAudio(String ext) {
        return Arrays.asList("mp3", "wav", "ogg", "m4a", "aac", "flac").contains(ext);
    }

    private String getMimeType(String fileName) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName.replace(" ", "%20"));
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        return (type == null) ? "*/*" : type;
    }
}