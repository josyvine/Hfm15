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
    private static final int FOREGROUND_ID = 9999; // Fixed ID for the service state

    private NotificationManager notificationManager;
    private ExecutorService executorService;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicInteger idGenerator = new AtomicInteger(1);
    private boolean isServiceForeground = false;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        executorService = Executors.newCachedThreadPool();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            checkAndStop();
            return START_NOT_STICKY;
        }

        // Capture files from Bridge immediately on Main Thread
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

        final int batchSize = intent.getIntExtra("batch_size", 1);
        final int uniqueJobId = idGenerator.incrementAndGet(); // Guaranteed unique ID

        activeTasks.incrementAndGet();

        // Ensure Service is in Foreground state immediately
        if (!isServiceForeground) {
            startForeground(FOREGROUND_ID, createNotification("HFM Service Active", "Processing deletion tasks...", 0, 0, false));
            isServiceForeground = true;
        }

        // Show the specific notification for THIS task
        notificationManager.notify(uniqueJobId, createNotification("Initialising...", "Preparing files...", 0, filePathsToProcess.size(), true));

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    performDeletionTask(filePathsToProcess, batchSize, uniqueJobId);
                } catch (Exception e) {
                    Log.e(TAG, "Task failed", e);
                } finally {
                    if (activeTasks.decrementAndGet() <= 0) {
                        checkAndStop();
                    }
                }
            }
        });

        return START_STICKY;
    }

    private void performDeletionTask(List<String> filePaths, int batchSize, int jobId) {
        int totalFiles = filePaths.size();
        int deletedCount = 0;
        ContentResolver resolver = getContentResolver();

        // Start processing
        for (int i = 0; i < totalFiles; i += batchSize) {
            int end = Math.min(i + batchSize, totalFiles);
            List<String> batchPaths = filePaths.subList(i, end);

            // Database Wipe
            try {
                StringBuilder selection = new StringBuilder(MediaStore.Files.FileColumns.DATA + " IN (");
                String[] selectionArgs = new String[batchPaths.size()];
                for (int j = 0; j < batchPaths.size(); j++) {
                    selection.append("?");
                    if (j < batchPaths.size() - 1) selection.append(",");
                    selectionArgs[j] = batchPaths.get(j);
                }
                selection.append(")");
                resolver.delete(MediaStore.Files.getContentUri("external"), selection.toString(), selectionArgs);
            } catch (Exception e) {
                Log.e(TAG, "SQL Delete error", e);
            }

            // Physical Wipe
            for (String path : batchPaths) {
                File file = new File(path);
                if (file.exists()) {
                    if (!file.delete()) {
                        StorageUtils.deleteFile(DeleteService.this, file);
                    }
                }
                deletedCount++;
            }

            // Update this specific task's notification
            String progressTitle = "Deleting " + totalFiles + " files";
            String progressText = "Status: " + end + "/" + totalFiles + " processed";
            notificationManager.notify(jobId, createNotification(progressTitle, progressText, end, totalFiles, true));
        }

        // Send Result Broadcast
        Intent broadcastIntent = new Intent(ACTION_DELETE_COMPLETE);
        broadcastIntent.putExtra(EXTRA_DELETED_COUNT, deletedCount);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);

        // Success State for notification
        notificationManager.notify(jobId, createNotification("Deletion Complete", "Successfully removed " + deletedCount + " files", 100, 100, false));
        
        // Auto-remove individual notification after 3 seconds
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
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
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);

        if (max > 0) {
            builder.setProgress(max, progress, false);
        } else if (ongoing) {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "File Deletion Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (executorService != null) executorService.shutdownNow();
        super.onDestroy();
    }
}