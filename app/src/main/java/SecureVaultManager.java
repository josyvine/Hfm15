package com.hfm.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;
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
     * Secure Playback System:
     * Asks the user if they want to use the internal RAM player or an external player.
     * 
     * @param vaultFile The file stored in the secure vault.
     * @param originalFileName The real name of the file.
     */
    public void playSecurely(final File vaultFile, final String originalFileName) {
        if (!vaultFile.exists()) {
            Toast.makeText(context, "Secure file not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        // --- NEW: DUAL OPTION SELECTION DIALOG ---
        // This resolves your feedback that the app "didn't ask for dual option mode"
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Secure Playback Mode");
        builder.setMessage("How would you like to view this file?");

        builder.setPositiveButton("Internal HFM Player", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Mode 1: Internal RAM Playback (Uses your VideoViewerActivity)
                openInternalPlayer(vaultFile);
            }
        });

        builder.setNeutralButton("External Player", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Mode 2: External Player (The logic that needs file_provider_paths.xml)
                executeExternalPlayback(vaultFile, originalFileName);
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /**
     * Mode 1: Opens the internal VideoViewerActivity.
     * This is the "RAM Mode" because it plays the file directly from the vault without a temp copy.
     */
    private void openInternalPlayer(File vaultFile) {
        try {
            ArrayList<String> fileList = new ArrayList<>();
            fileList.add(vaultFile.getAbsolutePath());

            Intent intent = new Intent(context, VideoViewerActivity.class);
            intent.putStringArrayListExtra(VideoViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
            intent.putExtra(VideoViewerActivity.EXTRA_CURRENT_INDEX, 0);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "Internal player error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Mode 2: Copies the file to cache and uses FileProvider to trigger VLC/MX Player.
     */
    private void executeExternalPlayback(File vaultFile, String originalFileName) {
        File tempCacheDir = new File(context.getCacheDir(), TEMP_PLAYBACK_DIR);
        if (!tempCacheDir.exists()) tempCacheDir.mkdirs();

        final File tempPlayFile = new File(tempCacheDir, originalFileName);

        try {
            // Copy file to secure_cache folder
            copyToCache(vaultFile, tempPlayFile);

            // Generate strict read-only URI via FileProvider
            Uri fileUri = FileProvider.getUriForFile(
                    context, 
                    context.getPackageName() + ".provider", 
                    tempPlayFile
            );

            // Resolve MIME type
            String mimeType = getMimeType(originalFileName);

            // Fire Intent
            Intent playIntent = new Intent(Intent.ACTION_VIEW);
            playIntent.setDataAndType(fileUri, mimeType);
            playIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            playIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            context.startActivity(playIntent);

            // Activate the Kill-Switch
            activatePlaybackKillSwitch(tempPlayFile);

        } catch (Exception e) {
            Log.e(TAG, "Secure playback failed", e);

            // DETAILED ERROR DIAGNOSTIC POPUP (The "White Box" error you saw)
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            final String detailedError = sw.toString();

            new AlertDialog.Builder(context)
                .setTitle("Secure Playback Error")
                .setMessage(detailedError)
                .setPositiveButton("Copy Error", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("HFM_Detailed_Error", detailedError);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(context, "Error details copied to clipboard.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
        }
    }

    /**
     * The Kill-Switch: Monitors the app's lifecycle.
     * The moment HFM comes back into the foreground, it shreds the cache file.
     */
    private void activatePlaybackKillSwitch(final File tempFile) {
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(LifecycleOwner owner) {
                Log.d(TAG, "Kill-Switch Activated: Shredding secure cache file.");
                shredFile(tempFile);
                ProcessLifecycleOwner.get().getLifecycle().removeObserver(this);
            }
        });
    }

    /**
     * Zeroes out the file bytes before deleting it to prevent forensic recovery.
     */
    private void shredFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            long length = file.length();
            FileOutputStream fos = new FileOutputStream(file);
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

    private String getMimeType(String fileName) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName.replace(" ", "%20"));
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
        }
        if (type == null) {
            type = "*/*";
        }
        return type;
    }
}