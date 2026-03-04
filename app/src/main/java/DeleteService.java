package com.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import android.Manifest;

public class DeleteService extends Service {

    private static final String TAG = "DeleteService";
    public static final String ACTION_DELETE_COMPLETE = "com.hfm.app.action.DELETE_COMPLETE";
    public static final String EXTRA_FILES_TO_DELETE = "com.hfm.app.extra.FILES_TO_DELETE";
    public static final String EXTRA_DELETED_COUNT = "com.hfm.app.extra.DELETED_COUNT";

    private static final String NOTIFICATION_CHANNEL_ID = "DeleteServiceChannel";
    private static final int FOREGROUND_ID = 9999; 

    private NotificationManager notificationManager;
    private ExecutorService executorService;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicInteger idGenerator = new AtomicInteger(1);
    private boolean isServiceForeground = false;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // Use a fixed pool to prevent over-loading the System Media Database
        executorService = Executors.newFixedThreadPool(4);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            checkAndStop();
            return START_NOT_STICKY;
        }

        final ArrayList<String> filePathsToProcess;
        ArrayList<String> bridgedFiles = FileBridge.mFilesToDelete;
        
        if (bridgedFiles != null && !bridgedFiles.isEmpty()) {
            filePathsToProcess = new ArrayList<>(bridgedFiles);
            FileBridge.mFilesToDelete = new ArrayList<>(); 
        } else {
            ArrayList<String> extraFiles = intent.getStringArrayListExtra(EXTRA_FILES_TO_DELETE);
            filePathsToProcess = (extraFiles != null) ? new ArrayList<>(extraFiles) : new ArrayList<String>();
        }

        if (filePathsToProcess.isEmpty()) {
            checkAndStop();
            return START_NOT_STICKY;
        }

        final int uniqueJobId = idGenerator.incrementAndGet();
        activeTasks.incrementAndGet();

        if (!isServiceForeground) {
            startForeground(FOREGROUND_ID, createNotification("HFM Delete Service", "Running tasks...", 0, 0, false));
            isServiceForeground = true;
        }

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    performDeletionTask(filePathsToProcess, uniqueJobId);
                } finally {
                    if (activeTasks.decrementAndGet() <= 0) {
                        checkAndStop();
                    }
                }
            }
        });

        return START_STICKY;
    }

    private void performDeletionTask(List<String> filePaths, int jobId) {
        int totalFiles = filePaths.size();
        int deletedCount = 0;
        ContentResolver resolver = getContentResolver();

        // IMMEDIATELY update notification so it doesn't stay on "Initialising"
        notificationManager.notify(jobId, createNotification("Deleting " + totalFiles + " files", "Starting...", 0, totalFiles, true));

        for (int i = 0; i < totalFiles; i++) {
            String path = filePaths.get(i);
            File file = new File(path);
            boolean deleted = false;

            // 1. FAST DELETE (Java Path)
            if (file.exists()) {
                deleted = file.delete();
            }

            // 2. SLOW DELETE FALLBACK (SAF Path - only if Java fails)
            if (!deleted && file.exists()) {
                deleted = StorageUtils.deleteFile(DeleteService.this, file);
            }

            // 3. CLEAN DATABASE
            if (deleted || !file.exists()) {
                deletedCount++;
                try {
                    resolver.delete(MediaStore.Files.getContentUri("external"), 
                        MediaStore.Files.FileColumns.DATA + "=?", new String[]{path});
                } catch (Exception ignored) {}
            }

            // UPDATE NOTIFICATION REGULARLY (Every 2 files or 100% update)
            // This prevents the "Stuck" look because the UI refreshes frequently.
            if (i % 2 == 0 || i == totalFiles - 1) {
                String progressText = "Processed " + (i + 1) + " of " + totalFiles;
                notificationManager.notify(jobId, createNotification("Deleting Files", progressText, i + 1, totalFiles, true));
            }
        }

        // Final Broadcast
        Intent broadcastIntent = new Intent(ACTION_DELETE_COMPLETE);
        broadcastIntent.putExtra(EXTRA_DELETED_COUNT, deletedCount);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);

        // Final notification state
        notificationManager.notify(jobId, createNotification("Done", "Removed " + deletedCount + " files", 100, 100, false));
        
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        notificationManager.cancel(jobId);
    }

    private void checkAndStop() {
        if (activeTasks.get() <= 0) {
            isServiceForeground = false;
            stopForeground(true);
            stopSelf();
        }
    }

    private Notification createNotification(String title, String content, int progress, int max, boolean ongoing) {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .setProgress(max, progress, max == 0 && ongoing)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, "File Deletion", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}