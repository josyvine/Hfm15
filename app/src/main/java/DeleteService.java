package com.hfm.app;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.Manifest;

public class DeleteService extends IntentService {

    private static final String TAG = "DeleteService";

    public static final String ACTION_DELETE_COMPLETE = "com.hfm.app.action.DELETE_COMPLETE";
    public static final String EXTRA_FILES_TO_DELETE = "com.hfm.app.extra.FILES_TO_DELETE";
    public static final String EXTRA_DELETED_COUNT = "com.hfm.app.extra.DELETED_COUNT";

    private static final String NOTIFICATION_CHANNEL_ID = "DeleteServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private NotificationManager notificationManager;

    public DeleteService() {
        super("DeleteService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        // Pull from Bridge to avoid crash
        ArrayList<String> filePathsToDelete = FileBridge.mFilesToDelete;
        if (filePathsToDelete != null && !filePathsToDelete.isEmpty()) {
            FileBridge.mFilesToDelete = new ArrayList<>(); 
        } else {
            filePathsToDelete = intent.getStringArrayListExtra(EXTRA_FILES_TO_DELETE);
        }

        int batchSize = intent.getIntExtra("batch_size", 1); // Default to 1 if not set, but we usually set higher

        if (filePathsToDelete == null || filePathsToDelete.isEmpty()) {
            return;
        }

        int totalFiles = filePathsToDelete.size();
        int deletedCount = 0;

        boolean canShowNotification = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                canShowNotification = true;
            }
        } else {
            canShowNotification = true;
        }

        if (canShowNotification) {
            startForeground(NOTIFICATION_ID, createNotification("Starting deletion...", 0, totalFiles));
        }

        // RESTORED: Batch processing logic. 
        // We process in chunks to keep the UI responsive, but use the FAST FileUtils batch delete.
        int processingBatchSize = 50; // Process 50 at a time for speed balance
        
        for (int i = 0; i < totalFiles; i += processingBatchSize) {
            int end = Math.min(i + processingBatchSize, totalFiles);
            List<String> batchPaths = filePathsToDelete.subList(i, end);
            List<File> batchFiles = new ArrayList<>();

            for (String path : batchPaths) {
                batchFiles.add(new File(path));
            }

            // CRITICAL FIX: Use FileUtils.deleteFileBatch. 
            // This performs a single SQL delete for the whole batch (INSTANT)
            // instead of looping SAF calls (SLOW).
            deletedCount += FileUtils.deleteFileBatch(this, batchFiles);

            if (canShowNotification) {
                String progressText = "Deleted " + end + " of " + totalFiles + "...";
                notificationManager.notify(NOTIFICATION_ID, createNotification(progressText, end, totalFiles));
            }
        }

        Intent broadcastIntent = new Intent(ACTION_DELETE_COMPLETE);
        broadcastIntent.putExtra(EXTRA_DELETED_COUNT, deletedCount);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
    }

    private Notification createNotification(String contentText, int progress, int max) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
			.setContentTitle("Deleting Files")
			.setContentText(contentText)
			.setSmallIcon(android.R.drawable.ic_menu_delete)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setOngoing(true);

        if (max > 0) {
            builder.setProgress(max, progress, false);
        } else {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
					NOTIFICATION_CHANNEL_ID,
					"File Deletion Service",
					NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Shows progress of background file deletion");
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
}