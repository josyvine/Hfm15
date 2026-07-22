package com.vineyard.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    private static final String NOTIFICATION_CHANNEL_ID = "DownloadServiceChannel";
    private static final int NOTIFICATION_ID = 1002;

    public static final String ACTION_DOWNLOAD_ERROR = "com.vineyard.hfm.app.action.DOWNLOAD_ERROR";
    public static final String EXTRA_ERROR_MESSAGE = "com.vineyard.hfm.app.extra.ERROR_MESSAGE";

    private FirebaseFirestore db;
    // requestListener and dropRequestId removed from here to support multiple files simultaneously

    @Override
    public void onCreate() {
        super.onCreate();
        db = FirebaseFirestore.getInstance();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        if (intent != null) {
            final String currentDropRequestId = intent.getStringExtra("drop_request_id");
            String rawSecretNumber = intent.getStringExtra("secret_number"); 
            
            // --- CRITICAL FIX: Sanitize the PIN to remove invisible clipboard line-breaks ---
            final String passedSecretNumber = (rawSecretNumber != null) ? rawSecretNumber.trim().replaceAll("\\s+", "") : null;

            Notification notification = buildNotification("Initializing Secure Drop...", true, 0, 0);
            startForeground(NOTIFICATION_ID, notification);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    // Pass the sanitized PIN and startId into the processing thread
                    startDownloadProcess(currentDropRequestId, passedSecretNumber, startId);
                }
            }).start();
        }
        return START_NOT_STICKY;
    }

    private void startDownloadProcess(final String docId, final String secretNumber, final int startId) {
        // Phase 4 & 5: Reconstruction
        final DocumentReference docRef = db.collection("drop_requests").document(docId);
        
        // Local listener for this specific file task
        final ListenerRegistration currentListener = listenForStatusChange(docRef, startId);

        docRef.get().addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
            @Override
            public void onSuccess(final DocumentSnapshot documentSnapshot) {
                if (!documentSnapshot.exists()) {
                    broadcastError("Error: Drop request not found.");
                    stopServiceAndCleanup(null, startId, currentListener, docId);
                    return;
                }
                
                final String encryptedManifestId = documentSnapshot.getString("encryptedManifestId");
                final long originalFilesize = documentSnapshot.getLong("filesize");
                final String fileNameFromServer = documentSnapshot.getString("originalFilename");

                // Check ONLY what the server provides, and ensure the UI passed the PIN successfully
                if (encryptedManifestId == null) {
                    broadcastError("Error: Incomplete transfer details from server.");
                    stopServiceAndCleanup(null, startId, currentListener, docId);
                    return;
                }

                if (secretNumber == null || secretNumber.isEmpty()) {
                    broadcastError("Error: Secret Number missing from receiver UI.");
                    stopServiceAndCleanup(null, startId, currentListener, docId);
                    return;
                }

                // Authenticate with Google Drive
                GoogleSignInAccount driveAccount = GoogleSignIn.getLastSignedInAccount(DownloadService.this);
                if (driveAccount == null) {
                    broadcastError("Google Drive authentication failed. Please sign in again.");
                    stopServiceAndCleanup(null, startId, currentListener, docId);
                    return;
                }

                final GoogleDriveManager driveManager = new GoogleDriveManager(DownloadService.this, driveAccount);
                final ReconstructionEngine reconstructionEngine = new ReconstructionEngine(DownloadService.this, driveManager);
                final SecureVaultManager vaultManager = new SecureVaultManager(DownloadService.this);

                // Create a destination file in the secure vault
                final File vaultFile = vaultManager.createVaultFile(fileNameFromServer);

                // --- CRITICAL FIX: Move heavy network/crypto logic completely off the Main Thread ---
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // FIX: To avoid Build Error, we use the final fileNameFromServer inside the ProgressListener
                            String originalFileName = reconstructionEngine.executeReconstruction(encryptedManifestId, secretNumber, vaultFile, new ReconstructionEngine.ProgressListener() {
                                @Override
                                public void onProgress(int progress, int max, long bytesProcessed) {
                                    updateNotification("Reconstructing " + fileNameFromServer + "... " + progress + "%", true, progress, max);
                                    broadcastStatus("Reconstructing...",
                                            String.format(Locale.US, "%s / %s",
                                                    Formatter.formatFileSize(getApplicationContext(), bytesProcessed),
                                                    Formatter.formatFileSize(getApplicationContext(), originalFilesize)),
                                            progress, max, bytesProcessed);
                                }

                                @Override
                                public void onStatusUpdate(String minorStatus) {
                                    updateNotification(minorStatus, true, 0, 0);
                                    broadcastStatus("Reconstructing...", minorStatus, -1, -1, -1);
                                }
                            });

                            // If we reach this line, reconstruction was successful
                            docRef.update("status", "complete");
                            updateNotification("Download Complete: " + originalFileName, false, 100, 100);
                            
                            // Pass the variables to the broadcast for secure playback
                            broadcastComplete(originalFileName, vaultFile.getAbsolutePath());
                            
                            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                                FirebaseAuth.getInstance().getCurrentUser().delete();
                            }
                            stopServiceAndCleanup(null, startId, currentListener, docId);

                        } catch (Exception e) {
                            // The engine threw a fatal error (Google Drive API or AES Decryption)
                            docRef.update("status", "error");
                            
                            // Extract the massive, detailed Java stack trace
                            String exactErrorLog = getStackTraceAsString(e);
                            
                            // Broadcast the raw error log to the screen instead of the generic string
                            broadcastError("FATAL RECONSTRUCTION ERROR:\n\n" + exactErrorLog);
                            stopServiceAndCleanup(null, startId, currentListener, docId);
                        }
                    }
                }).start();
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                broadcastError("Could not retrieve transfer details from the server.\n\n" + getStackTraceAsString(e));
                stopServiceAndCleanup(null, startId, currentListener, docId);
            }
        });
    }

    private String getStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }

    private void broadcastStatus(String major, String minor, int progress, int max, long bytes) {
        Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
        intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, major);
        intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, minor);
        intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, progress);
        intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, max);
        intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, bytes);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
    
    private void broadcastComplete(String originalFileName, String vaultFilePath) {
        Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
        intent.putExtra("original_file_name", originalFileName);
        intent.putExtra("vault_file_path", vaultFilePath);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void broadcastError(String message) {
        Intent errorIntent = new Intent(ACTION_DOWNLOAD_ERROR);
        errorIntent.putExtra(EXTRA_ERROR_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(errorIntent);
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
    }

    private ListenerRegistration listenForStatusChange(DocumentReference docRef, final int startId) {
        return docRef.addSnapshotListener(new EventListener<DocumentSnapshot>() {
            @Override
            public void onEvent(DocumentSnapshot snapshot, FirebaseFirestoreException e) {
                if (e != null) { return; }
                if (snapshot != null && snapshot.exists()) {
                    String status = snapshot.getString("status");
                    if ("error".equals(status) || "declined".equals(status) || "cancelled".equals(status)) {
                         stopServiceAndCleanup("Transfer was cancelled or encountered an error.", startId, null, null);
                    }
                } else {
                     stopServiceAndCleanup("Transfer was cancelled by the sender.", startId, null, null);
                }
            }
        });
    }

    private void stopServiceAndCleanup(final String toastMessage, final int startId, ListenerRegistration listener, String docId) {
        if (toastMessage != null) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(DownloadService.this, toastMessage, Toast.LENGTH_LONG).show();
                }
            });
        }
        
        if (listener != null) {
            listener.remove();
        }

        if (docId != null) {
            db.collection("drop_requests").document(docId).delete();
        }

        // FIX: Ensuring startId is used so the service only stops if this was the last active task
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "DownloadService onDestroy.");
        stopForeground(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "HFM Drop Downloader",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text, boolean ongoing, int progress, int max) {
        Notification notification = buildNotification(text, ongoing, progress, max);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text, boolean ongoing, int progress, int max) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("HFM Drop")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (max > 0) {
            builder.setProgress(max, progress, false);
        } else {
            builder.setProgress(0, 0, true); // Indeterminate
        }
        return builder.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}