package com.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.security.SecureRandom;
import java.text.SimpleDateFormat; 
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StorageBrowserActivity extends Activity implements StorageBrowserAdapter.OnItemClickListener, StorageBrowserAdapter.OnHeaderCheckedChangeListener, StorageBrowserAdapter.OnHeaderClickListener {

    private static final String TAG = "StorageBrowserActivity";

    private TextView titleTextView, pathTextView;
    private ImageButton backButton, sortButton, deleteButton;
    private EditText searchInput;
    private RecyclerView fileGrid;
    private RelativeLayout loadingView;
    private RelativeLayout operationProgressLayout;
    private ProgressBar operationProgressBar;
    private TextView operationProgressText;

    private RelativeLayout pasteControlsLayout;
    private Button pasteButton, createFolderButton, cancelPasteButton;

    private StorageBrowserAdapter adapter;
    private List<Object> masterList = new ArrayList<>();
    private List<Object> displayList = new ArrayList<>(); 
    private String currentPath;
    private BroadcastReceiver deleteCompletionReceiver;
    private BroadcastReceiver operationBroadcastReceiver;
    private BroadcastReceiver compressionBroadcastReceiver;
    private GridLayoutManager gridLayoutManager;

    private static final int SORT_BY_NAME = 1;
    private static final int SORT_BY_DATE = 2;
    private static final int SORT_BY_SIZE = 3;
    private static final int SORT_BY_TYPE = 4;
    private int currentSortOrder = SORT_BY_DATE;

    private static final Pattern FILE_BASE_NAME_PATTERN = Pattern.compile("^(IMG|VID|PANO|DSC)_\\d{8}_\\d{6}");
    private List<File> mFilesPendingPermission;

    private static final int CATEGORY_IMAGES = 1;
    private static final int CATEGORY_VIDEOS = 2;
    private static final int CATEGORY_AUDIO = 3;
    private static final int CATEGORY_DOCS = 4;
    private static final int CATEGORY_OTHER = 5;

    private Runnable mPendingOperation;
    private File mFilePendingPermissionForExtraction;


    public static class DateHeader {
        private final String dateString;
        private boolean isChecked;
        private boolean isExpanded; 

        public DateHeader(String dateString) {
            this.dateString = dateString;
            this.isChecked = false;
            this.isExpanded = true; 
        }

        public String getDateString() { return dateString; }
        public boolean isChecked() { return isChecked; }
        public void setChecked(boolean checked) { isChecked = checked; }
        public boolean isExpanded() { return isExpanded; }
        public void setExpanded(boolean expanded) { isExpanded = expanded; }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage_browser);

        initializeViews();

        Intent intent = getIntent();
        currentPath = intent.getStringExtra(DashboardActivity.EXTRA_STORAGE_PATH);
        String storageName = intent.getStringExtra(DashboardActivity.EXTRA_STORAGE_NAME);

        if (currentPath == null || storageName == null) {
            Toast.makeText(this, "Error: Storage path not provided.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        titleTextView.setText(storageName);
        pathTextView.setText(currentPath);

        setupRecyclerView();
        setupListeners();
        setupBroadcastReceivers();

        new ScanFilesTask().execute(new File(currentPath));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFooterUI();
    }

    private void initializeViews() {
        titleTextView = findViewById(R.id.title_storage_browser);
        pathTextView = findViewById(R.id.path_storage_browser);
        backButton = findViewById(R.id.back_button_storage_browser);
        sortButton = findViewById(R.id.sort_button_storage_browser);
        deleteButton = findViewById(R.id.delete_button_storage_browser);
        searchInput = findViewById(R.id.search_input_storage_browser);
        fileGrid = findViewById(R.id.file_grid_storage_browser);
        loadingView = findViewById(R.id.loading_view_browser);
        operationProgressLayout = findViewById(R.id.operation_progress_layout);
        operationProgressBar = findViewById(R.id.operation_progress_bar);
        operationProgressText = findViewById(R.id.operation_progress_text);

        pasteControlsLayout = findViewById(R.id.paste_controls_layout);
        pasteButton = findViewById(R.id.paste_button);
        createFolderButton = findViewById(R.id.create_folder_button);
        cancelPasteButton = findViewById(R.id.cancel_paste_button);
    }

    private void setupRecyclerView() {
        adapter = new StorageBrowserAdapter(this, displayList, this, this, this);
        gridLayoutManager = new GridLayoutManager(this, 3);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                                @Override
                                public int getSpanSize(int position) {
                                        if (position >= 0 && position < adapter.getItemCount()) {
                                                if (displayList.get(position) instanceof DateHeader) {
                                                        return gridLayoutManager.getSpanCount();
                                                }
                                        }
                                        return 1;
                                }
                        });

        fileGrid.setLayoutManager(gridLayoutManager);
        fileGrid.setAdapter(adapter);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        File file = new File(currentPath);
                                        File parent = file.getParentFile();
                                        if (parent != null) {
                                                currentPath = parent.getAbsolutePath();
                                                pathTextView.setText(currentPath);
                                                new ScanFilesTask().execute(parent);
                                        } else {
                                                finish();
                                        }
                                }
                        });

        deleteButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        if (com.hfm.app.ClipboardManager.getInstance().hasItems()) {
                                                updateFooterUI();
                                        } else {
                                                showFileOperationsDialog();
                                        }
                                }
                        });

        sortButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        showSortMenu(v);
                                }
                        });

        searchInput.addTextChangedListener(new TextWatcher() {
                                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                                        if (adapter != null) {
                                                adapter.getFilter().filter(s);
                                        }
                                }
                                @Override public void afterTextChanged(Editable s) {}
                        });

        pasteButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        performPaste();
                                }
                        });

        createFolderButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        showCreateFolderDialog();
                                }
                        });

        cancelPasteButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        com.hfm.app.ClipboardManager.getInstance().clear();
                                        updateFooterUI();
                                        Toast.makeText(StorageBrowserActivity.this, "Operation cancelled.", Toast.LENGTH_SHORT).show();
                                }
                        });
    }

    @Override
    public void onItemClick(int position, Object item) {
        if (item instanceof StorageBrowserAdapter.FileItem) {
            StorageBrowserAdapter.FileItem fileItem = (StorageBrowserAdapter.FileItem) item;
            File file = fileItem.getFile();
            if (file.isDirectory()) {
                currentPath = file.getAbsolutePath();
                pathTextView.setText(currentPath);
                new ScanFilesTask().execute(file);
            } else {
                fileItem.setSelected(!fileItem.isSelected());
                adapter.notifyItemChanged(position);
                updateHeaderStateForItem(fileItem);
            }
        }
    }

    @Override
    public void onItemLongClick(int position, Object item) {
        if (item instanceof StorageBrowserAdapter.FileItem) {
            final File selectedFile = ((StorageBrowserAdapter.FileItem) item).getFile();
            if (selectedFile.isDirectory()) {
                showFolderOperationsDialog(selectedFile);
            } else {
                String fileName = selectedFile.getName().toLowerCase();
                if (fileName.endsWith(".zip") || fileName.endsWith(".rar")) {
                    showArchiveOperationsDialog(selectedFile);
                } else {
                    openFileViewer(selectedFile);
                }
            }
        }
    }

    @Override
    public void onSelectionChanged() {
    }

    @Override
    public void onHeaderCheckedChanged(DateHeader header, boolean isChecked) {
        header.setChecked(isChecked);
        int headerIndex = masterList.indexOf(header);
        if (headerIndex == -1) return;

        for (int i = headerIndex + 1; i < masterList.size(); i++) {
            Object currentItem = masterList.get(i);
            if (currentItem instanceof StorageBrowserAdapter.FileItem) {
                ((StorageBrowserAdapter.FileItem) currentItem).setSelected(isChecked);
            } else if (currentItem instanceof DateHeader) {
                break;
            }
        }
        rebuildDisplayList();
    }

    @Override
    public void onHeaderClick(DateHeader header) {
        header.setExpanded(!header.isExpanded());
        rebuildDisplayList();
    }

    private void rebuildDisplayList() {
        displayList.clear();
        boolean isCurrentGroupExpanded = true;
        for (Object item : masterList) {
            if (item instanceof DateHeader) {
                DateHeader header = (DateHeader) item;
                displayList.add(header);
                isCurrentGroupExpanded = header.isExpanded();
            } else {
                if (isCurrentGroupExpanded) {
                    displayList.add(item);
                }
            }
        }
        adapter.updateMasterList(displayList);
    }

    private void updateHeaderStateForItem(StorageBrowserAdapter.FileItem item) {
        int itemIndex = masterList.indexOf(item);
        if (itemIndex == -1) return;

        DateHeader parentHeader = null;
        int headerIndex = -1;
        for (int i = itemIndex - 1; i >= 0; i--) {
            if (masterList.get(i) instanceof DateHeader) {
                parentHeader = (DateHeader) masterList.get(i);
                headerIndex = i;
                break;
            }
        }
        if (parentHeader == null) return;

        boolean allChildrenSelected = true;
        for (int i = headerIndex + 1; i < masterList.size(); i++) {
            Object currentItem = masterList.get(i);
            if (currentItem instanceof StorageBrowserAdapter.FileItem) {
                if(!((StorageBrowserAdapter.FileItem) currentItem).getFile().isDirectory()){
                    if (!((StorageBrowserAdapter.FileItem) currentItem).isSelected()) {
                        allChildrenSelected = false;
                        break;
                    }
                }
            } else if (currentItem instanceof DateHeader) {
                break;
            }
        }
        parentHeader.setChecked(allChildrenSelected);

        int displayHeaderIndex = displayList.indexOf(parentHeader);
        if (displayHeaderIndex != -1) {
            adapter.notifyItemChanged(displayHeaderIndex);
        }
    }

    private void showFileOperationsDialog() {
        final List<File> selectedFiles = getSelectedFiles();
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_file_operations, null);
        builder.setView(dialogView);

        final AlertDialog dialog = builder.create();

        Button detailsButton = dialogView.findViewById(R.id.button_details);
        Button sendToDropZoneButton = dialogView.findViewById(R.id.button_send_to_drop_zone);
        Button compressButton = dialogView.findViewById(R.id.button_compress);
        Button copyButton = dialogView.findViewById(R.id.button_copy);
        Button moveButton = dialogView.findViewById(R.id.button_move);
        Button hideButton = dialogView.findViewById(R.id.button_hide);
        Button deleteButton = dialogView.findViewById(R.id.button_delete_permanently);
        Button recycleButton = dialogView.findViewById(R.id.button_move_to_recycle);

        detailsButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        showDetailsDialog(selectedFiles);
                                        dialog.dismiss();
                                }
                        });

        sendToDropZoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedFiles.size() == 1) {
                    showSendToDropDialog(selectedFiles.get(0));
                } else {
                    Toast.makeText(StorageBrowserActivity.this, "HFM Drop currently supports sending a single file at a time.", Toast.LENGTH_LONG).show();
                }
                dialog.dismiss();
            }
        });

        compressButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        ArchiveUtils.startCompression(StorageBrowserActivity.this, selectedFiles, new File(currentPath));
                                        Toast.makeText(StorageBrowserActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
                                        dialog.dismiss();
                                }
                        });

        copyButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        com.hfm.app.ClipboardManager.getInstance().setItems(selectedFiles, com.hfm.app.ClipboardManager.Operation.COPY);
                                        Toast.makeText(StorageBrowserActivity.this, selectedFiles.size() + " item(s) ready to copy.", Toast.LENGTH_SHORT).show();
                                        updateFooterUI();
                                        dialog.dismiss();
                                }
                        });

        moveButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        com.hfm.app.ClipboardManager.getInstance().setItems(selectedFiles, com.hfm.app.ClipboardManager.Operation.MOVE);
                                        Toast.makeText(StorageBrowserActivity.this, selectedFiles.size() + " item(s) ready to move.", Toast.LENGTH_SHORT).show();
                                        updateFooterUI();
                                        dialog.dismiss();
                                }
                        });

        hideButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        // UPDATED: Check permissions before executing hiding task
                                        initiateHidingProcess(selectedFiles);
                                        dialog.dismiss();
                                }
                        });

        deleteButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        initiateDeletionProcess();
                                        dialog.dismiss();
                                }
                        });

        recycleButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                    AlertDialog.Builder binBuilder = new AlertDialog.Builder(StorageBrowserActivity.this);
                    binBuilder.setTitle("Choose Recycle Bin");
                    binBuilder.setItems(new CharSequence[]{"Phone Recycle Bin", "SD Card Recycle Bin"}, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int which) {
                            new MoveToRecycleTask(selectedFiles, which == 1).execute();
                        }
                    });
                    binBuilder.show();
                                        dialog.dismiss();
                                }
                        });

        dialog.show();
    }

    private void showFolderOperationsDialog(final File folder) {
        final CharSequence[] options = {"Details", "Compress", "Copy", "Move", "Hide", "Move to Recycle Bin", "Delete Permanently"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Folder Operation: " + folder.getName());
        builder.setItems(options, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                        List<File> folderList = new ArrayList<>();
                                        folderList.add(folder);
                                        switch (which) {
                        case 0: // Details
                            showDetailsDialog(folderList);
                            break;
                        case 1: // Compress
                            ArchiveUtils.startCompression(StorageBrowserActivity.this, folderList, new File(currentPath));
                            Toast.makeText(StorageBrowserActivity.this, "Compression started in background.", Toast.LENGTH_SHORT).show();
                            break;
                                                case 2: // Copy
                                                        com.hfm.app.ClipboardManager.getInstance().setItems(folderList, com.hfm.app.ClipboardManager.Operation.COPY);
                                                        Toast.makeText(StorageBrowserActivity.this, "Folder '" + folder.getName() + "' ready to copy.", Toast.LENGTH_SHORT).show();
                                                        updateFooterUI();
                                                        break;
                                                case 3: // Move
                                                        com.hfm.app.ClipboardManager.getInstance().setItems(folderList, com.hfm.app.ClipboardManager.Operation.MOVE);
                                                        Toast.makeText(StorageBrowserActivity.this, "Folder '" + folder.getName() + "' ready to move.", Toast.LENGTH_SHORT).show();
                                                        updateFooterUI();
                                                        break;
                        case 4: // Hide
                            // UPDATED: Check permissions before executing hiding task
                            initiateHidingProcess(folderList);
                            break;
                                                case 5: // Move to Recycle Bin
                            AlertDialog.Builder binBuilder = new AlertDialog.Builder(StorageBrowserActivity.this);
                            binBuilder.setTitle("Choose Recycle Bin");
                            binBuilder.setItems(new CharSequence[]{"Phone Recycle Bin", "SD Card Recycle Bin"}, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int whichBin) {
                                    new MoveToRecycleTask(folderList, whichBin == 1).execute();
                                }
                            });
                            binBuilder.show();
                                                        break;
                                                case 6: // Delete Permanently
							initiateFolderDeletionProcess(folder);
                                                        break;
                                        }
                                }
                        });
        builder.show();
    }

    private void showArchiveOperationsDialog(final File archiveFile) {
        final CharSequence[] options = {"Details", "Extract Here", "Send to Drop Zone"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(archiveFile.getName());
        builder.setItems(options, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                    if (which == 0) { // Details
                        List<File> fileList = new ArrayList<>();
                        fileList.add(archiveFile);
                        showDetailsDialog(fileList);
                    } else if (which == 1) { // Extract Here
                                                final File destination = new File(currentPath);
                                                if (StorageUtils.isFileOnSdCard(StorageBrowserActivity.this, destination) && !StorageUtils.hasSdCardPermission(StorageBrowserActivity.this)) {
                                                        mFilePendingPermissionForExtraction = archiveFile;
                                                        mPendingOperation = new Runnable() {
                                                                @Override
                                                                public void run() {
                                                                        ArchiveUtils.extractArchive(StorageBrowserActivity.this, archiveFile, destination);
                                                                }
                                                        };
                                                        promptForSdCardPermission();
                                                } else {
                                                        ArchiveUtils.extractArchive(StorageBrowserActivity.this, archiveFile, destination);
                                                }
                                        } else if (which == 2) { // Send to Drop Zone
                        showSendToDropDialog(archiveFile);
                    }
                                }
                        });
        builder.show();
    }

    private void showDetailsDialog(final List<File> files) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_details, null);
        builder.setView(dialogView);
        builder.setCancelable(false);

        final TextView basicDetailsText = dialogView.findViewById(R.id.details_text_basic);
        final TextView aiDetailsText = dialogView.findViewById(R.id.details_text_ai);
        final ProgressBar progressBar = dialogView.findViewById(R.id.details_progress_bar);
        final Button moreButton = dialogView.findViewById(R.id.details_button_more);
        final Button copyButton = dialogView.findViewById(R.id.details_button_copy);
        final Button closeButton = dialogView.findViewById(R.id.details_button_close);

        final AlertDialog dialog = builder.create();

        // Populate basic details
        if (files.size() == 1) {
            File file = files.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("Name: ").append(file.getName()).append("\n");
            sb.append("Path: ").append(file.getAbsolutePath()).append("\n");
            sb.append("Size: ").append(Formatter.formatFileSize(this, file.length())).append("\n");
            sb.append("Last Modified: ").append(new Date(file.lastModified()).toString());
            basicDetailsText.setText(sb.toString());
        } else {
            long totalSize = 0;
            for (File file : files) {
                totalSize += file.length();
            }
            basicDetailsText.setText("Items selected: " + files.size() + "\nTotal size: " + Formatter.formatFileSize(this, totalSize));
        }

        final GeminiAnalyzer analyzer = new GeminiAnalyzer(this, aiDetailsText, progressBar, copyButton);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        moreButton.setEnabled(ApiKeyManager.getApiKey(this) != null && isConnected);

        moreButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        analyzer.analyze(files);
                                }
                        });

        copyButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                                        ClipData clip = ClipData.newPlainText("AI Summary", aiDetailsText.getText());
                                        clipboard.setPrimaryClip(clip);
                                        Toast.makeText(StorageBrowserActivity.this, "Summary copied to clipboard.", Toast.LENGTH_SHORT).show();
                                }
                        });

        closeButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                        dialog.dismiss();
                                }
                        });

        dialog.show();
    }

    private void showSendToDropDialog(final File fileToSend) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_send_drop, null);
        final EditText receiverUsernameInput = dialogView.findViewById(R.id.edit_text_receiver_username);

        builder.setView(dialogView)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String receiverUsername = receiverUsernameInput.getText().toString().trim();
                        if (receiverUsername.isEmpty()) {
                            Toast.makeText(StorageBrowserActivity.this, "Receiver username cannot be empty.", Toast.LENGTH_SHORT).show();
                        } else {
                            showSenderWarningDialog(receiverUsername, fileToSend);
                        }
                    }
                })
                .setNegativeButton("Cancel", null);
        builder.create().show();
    }

    private void showSenderWarningDialog(final String receiverUsername, final File fileToSend) {
        final String secretNumber = generateSecretNumber();

        new AlertDialog.Builder(this)
                .setTitle("Important: Connection Stability")
                .setMessage("You are about to act as a temporary server for this file transfer.\n\n"
                        + "Please keep the app open and maintain a stable internet connection until the transfer is complete.\n\n"
                        + "Your Secret Number for this transfer is:\n" + secretNumber + "\n\nShare this number with the receiver.")
                .setPositiveButton("I Understand, Start Sending", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startSenderService(receiverUsername, secretNumber, fileToSend);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startSenderService(String receiverUsername, String secretNumber, File fileToSend) {
        if (fileToSend == null || !fileToSend.exists()) {
            Toast.makeText(this, "Error: File to send does not exist.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, SenderService.class);
        intent.setAction(SenderService.ACTION_START_SEND);
        intent.putExtra(SenderService.EXTRA_FILE_PATH, fileToSend.getAbsolutePath());
        intent.putExtra(SenderService.EXTRA_RECEIVER_USERNAME, receiverUsername);
        intent.putExtra(SenderService.EXTRA_SECRET_NUMBER, secretNumber);
        ContextCompat.startForegroundService(this, intent);
    }

    private String generateSecretNumber() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void initiateFolderDeletionProcess(final File folder) {
        // Optimized: Don't scan subfiles. Just send folder root path directly to Service.
        final ArrayList<String> folderPathList = new ArrayList<>();
        folderPathList.add(folder.getAbsolutePath());

        final String[] batchOptions = {"50", "100", "500", "1000", "Max (All at once)"};
        final int[] batchValues = {50, 100, 500, 1000, 100000};

        new AlertDialog.Builder(StorageBrowserActivity.this)
            .setTitle("Confirm Folder Wipe")
            .setMessage("Delete '" + folder.getName() + "' and all contents permanently?")
            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int which) {
                    performDeletion(folderPathList, 50); // Turbo wipe ignores batch size generally, passing 50 for fallback consistency
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // NEW METHOD: Initiate hiding process with permission check
    private void initiateHidingProcess(final List<File> filesToHide) {
        boolean requiresSdCardPermission = false;
        for (File file : filesToHide) {
            if (StorageUtils.isFileOnSdCard(this, file) && !StorageUtils.hasSdCardPermission(this)) {
                requiresSdCardPermission = true;
                break;
            }
        }

        if (requiresSdCardPermission) {
            mFilesPendingPermission = filesToHide;
            mPendingOperation = new Runnable() {
                @Override
                public void run() {
                    new GatherFilesForHidingTask().execute(filesToHide.toArray(new File[0]));
                }
            };
            promptForSdCardPermission();
        } else {
            new GatherFilesForHidingTask().execute(filesToHide.toArray(new File[0]));
        }
    }

    private void initiateDeletionProcess() {
        final List<File> initiallySelectedFiles = getSelectedFiles();

        if (initiallySelectedFiles.isEmpty()) {
            Toast.makeText(this, "No files selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean requiresSdCardPermission = false;
        for (File file : initiallySelectedFiles) {
            if (StorageUtils.isFileOnSdCard(this, file) && !StorageUtils.hasSdCardPermission(this)) {
                requiresSdCardPermission = true;
                break;
            }
        }

        if (requiresSdCardPermission) {
            mFilesPendingPermission = initiallySelectedFiles;
            mPendingOperation = new Runnable() {
                @Override
                public void run() {
                    showDeleteConfirmationDialog(initiallySelectedFiles);
                }
            };
            promptForSdCardPermission();
        } else {
            showDeleteConfirmationDialog(initiallySelectedFiles);
        }
    }

    // We need findSiblingFiles to prevent abandoning burst-shots and raw sidecars
    private List<File> findSiblingFiles(File originalFile) {
        List<File> siblings = new ArrayList<>();
        siblings.add(originalFile);
        String fileName = originalFile.getName();
        Matcher matcher = FILE_BASE_NAME_PATTERN.matcher(fileName);
        if (matcher.find()) {
            String baseName = matcher.group(0);
            File parentDir = originalFile.getParentFile();
            if (parentDir != null && parentDir.isDirectory()) {
                File[] filesInDir = parentDir.listFiles();
                if (filesInDir != null) {
                    for (File potentialSibling : filesInDir) {
                        if (potentialSibling.isFile() && potentialSibling.getName().startsWith(baseName) && !potentialSibling.getAbsolutePath().equals(originalFile.getAbsolutePath())) {
                            siblings.add(potentialSibling);
                        }
                    }
                }
            }
        }
        return siblings;
    }

    private void showDeleteConfirmationDialog(final List<File> filesToConfirm) {
        final Set<File> masterDeleteSet = new HashSet<>();
        int foldersCount = 0;
        
        for (File selectedFile : filesToConfirm) {
            if (selectedFile.isDirectory()) {
                // Fast logic: Process root paths. Don't expand folders into subfile lists here.
                masterDeleteSet.add(selectedFile);
                foldersCount++;
            } else {
                // Keep the vital sibling finder active for individual files
                masterDeleteSet.addAll(findSiblingFiles(selectedFile));
            }
        }

        final List<File> filesToDelete = new ArrayList<>(masterDeleteSet);
        final ArrayList<String> pathsToDelete = new ArrayList<>();
        int filesCount = 0;

        for (File f : filesToDelete) {
            pathsToDelete.add(f.getAbsolutePath());
            if (!f.isDirectory()) {
                filesCount++;
            }
        }

        String dialogMessage;
        if (filesToDelete.size() > filesToConfirm.size() && foldersCount == 0) {
            int siblingCount = filesToDelete.size() - filesToConfirm.size();
            dialogMessage = "You selected <b>" + filesToConfirm.size() + "</b> file(s), but we found <b>" + siblingCount
                                + "</b> other related version(s).<br/><br/>Are you sure you want to permanently delete all <b>"
                                + filesToDelete.size() + "</b> related file(s)? This action cannot be undone.";
        } else {
            dialogMessage = "Are you sure you want to permanently delete " + filesCount + " file(s) and " + foldersCount + " folder(s)? This action cannot be undone.";
        }

        new AlertDialog.Builder(this)
                        .setTitle("Confirm Deletion")
                        .setMessage(Html.fromHtml(dialogMessage))
                        .setPositiveButton("Delete All", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                    final String[] batchOptions = {"50", "100", "500", "1000", "Max (All at once)"};
                    final int[] batchValues = {50, 100, 500, 1000, 100000};

                    new AlertDialog.Builder(StorageBrowserActivity.this)
                        .setTitle("Select Deletion Speed")
                        .setItems(batchOptions, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int index) {
                                performDeletion(pathsToDelete, batchValues[index]);
                            }
                        }).show();
                                }
                        })
                        .setNeutralButton("Move to Recycle", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                    AlertDialog.Builder binBuilder = new AlertDialog.Builder(StorageBrowserActivity.this);
                    binBuilder.setTitle("Choose Recycle Bin");
                    binBuilder.setItems(new CharSequence[]{"Phone Recycle Bin", "SD Card Recycle Bin"}, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int whichBin) {
                            new MoveToRecycleTask(filesToDelete, whichBin == 1).execute();
                        }
                    });
                    binBuilder.show();
                                }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
    }

    private void performDeletion(List<String> filePathsToDelete, int batchSize) {
        operationProgressLayout.setVisibility(View.VISIBLE);
        operationProgressBar.setIndeterminate(true);
        operationProgressText.setText("Starting deletion...");

        // Load into Bridge to handle massive lists without Intent size limits
        FileBridge.mFilesToDelete = new ArrayList<>(filePathsToDelete);

        Intent deleteIntent = new Intent(this, DeleteService.class);
        deleteIntent.putStringArrayListExtra(DeleteService.EXTRA_FILES_TO_DELETE, new ArrayList<>(filePathsToDelete)); // Added fallback just in case
        deleteIntent.putExtra("batch_size", batchSize);
        ContextCompat.startForegroundService(this, deleteIntent);
    }

    // Essential for Folder Hiding feature extraction
    private ArrayList<File> getAllFilesRecursive(File directory) {
        ArrayList<File> fileList = new ArrayList<>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    fileList.addAll(getAllFilesRecursive(file));
                } else {
                    fileList.add(file);
                }
            }
        }
        return fileList;
    }

    // AsyncTask wrapper for Folder Hiding to prevent Activity crash
    private class GatherFilesForHidingTask extends AsyncTask<File, Void, ArrayList<File>> {
        AlertDialog progressDialog;

        @Override
        protected void onPreExecute() {
            AlertDialog.Builder builder = new AlertDialog.Builder(StorageBrowserActivity.this);
            builder.setView(R.layout.dialog_progress_simple);
            builder.setCancelable(false);
            progressDialog = builder.create();
            progressDialog.show();
        }

        @Override
        protected ArrayList<File> doInBackground(File... items) {
            ArrayList<File> filesToHide = new ArrayList<>();
            for (File item : items) {
                // MODIFICATION: Do not expand directories. Pass them as-is.
                // This enables the "Hide Folder as Entity" feature.
                filesToHide.add(item);
            }
            return filesToHide;
        }

        @Override
        protected void onPostExecute(ArrayList<File> files) {
            progressDialog.dismiss();
            if (files.isEmpty()) {
                Toast.makeText(StorageBrowserActivity.this, "No files found to hide.", Toast.LENGTH_SHORT).show();
            } else {
                Intent intent = new Intent(StorageBrowserActivity.this, FileHiderActivity.class);
                intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, files);
                startActivity(intent);
            }
        }
    }

    private class ScanFilesTask extends AsyncTask<File, Void, List<File>> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingView.setVisibility(View.VISIBLE);
            fileGrid.setVisibility(View.GONE);
        }

        @Override
        protected List<File> doInBackground(File... dirs) {
            File[] files = dirs[0].listFiles();
            if (files != null) {
                return new ArrayList<>(Arrays.asList(files));
            }
            return new ArrayList<>();
        }

        @Override
        protected void onPostExecute(List<File> result) {
            super.onPostExecute(result);
            sortAndGroupFiles(result);
            rebuildDisplayList();
            loadingView.setVisibility(View.GONE);
            fileGrid.setVisibility(View.VISIBLE);
        }
    }

    private void showSortMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenuInflater().inflate(R.menu.sort_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                                @Override
                                public boolean onMenuItemClick(MenuItem item) {
                                        int itemId = item.getItemId();
                                        if (itemId == R.id.sort_by_name) {
                                                currentSortOrder = SORT_BY_NAME;
                                        } else if (itemId == R.id.sort_by_date) {
                                                currentSortOrder = SORT_BY_DATE;
                                        } else if (itemId == R.id.sort_by_size) {
                                                currentSortOrder = SORT_BY_SIZE;
                                        } else if (itemId == R.id.sort_by_type) {
                                                currentSortOrder = SORT_BY_TYPE;
                                        }
                                        sortAndGroupFiles(getCurrentFiles());
                                        rebuildDisplayList();
                                        return true;
                                }
                        });
        popup.show();
    }

    private List<File> getCurrentFiles() {
        List<File> currentFiles = new ArrayList<>();
        for (Object item : masterList) {
            if (item instanceof StorageBrowserAdapter.FileItem) {
                currentFiles.add(((StorageBrowserAdapter.FileItem) item).getFile());
            }
        }
        return currentFiles;
    }

    private void sortAndGroupFiles(List<File> files) {
        Collections.sort(files, new Comparator<File>() {
                                @Override
                                public int compare(File f1, File f2) {
                                        if (f1.isDirectory() && !f2.isDirectory()) return -1;
                                        if (!f1.isDirectory() && f2.isDirectory()) return 1;

                                        switch (currentSortOrder) {
                                                case SORT_BY_NAME:
                                                        return f1.getName().compareToIgnoreCase(f2.getName());
                                                case SORT_BY_SIZE:
                                                        return Long.compare(f2.length(), f1.length());
                                                case SORT_BY_TYPE:
                                                        String ext1 = getFileExtension(f1);
                                                        String ext2 = getFileExtension(f2);
                                                        return ext1.compareToIgnoreCase(ext2);
                                                case SORT_BY_DATE:
                                                default:
                                                        return Long.compare(f2.lastModified(), f1.lastModified());
                                        }
                                }
                        });

        masterList.clear();
        if (currentSortOrder != SORT_BY_DATE) {
            for (File file : files) {
                masterList.add(new StorageBrowserAdapter.FileItem(file));
            }
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("MMMM d, yyyy", Locale.getDefault());
            String currentHeaderDate = "";
            for (File file : files) {
                if(file.isDirectory()){
                    masterList.add(new StorageBrowserAdapter.FileItem(file));
                    continue;
                }
                String resultDate = sdf.format(new Date(file.lastModified()));
                if (!resultDate.equals(currentHeaderDate)) {
                    currentHeaderDate = resultDate;
                    masterList.add(new DateHeader(currentHeaderDate));
                }
                masterList.add(new StorageBrowserAdapter.FileItem(file));
            }
        }
    }

    private String getFileExtension(File file) {
        String name = file.getName();
        int lastIndexOf = name.lastIndexOf(".");
        if (lastIndexOf == -1) {
            return "";
        }
        return name.substring(lastIndexOf);
    }

    private void setupBroadcastReceivers() {
        deleteCompletionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int deletedCount = intent.getIntExtra(DeleteService.EXTRA_DELETED_COUNT, 0);
                Toast.makeText(StorageBrowserActivity.this, "Deletion complete. " + deletedCount + " files/folders removed.", Toast.LENGTH_LONG).show();
                operationProgressLayout.setVisibility(View.GONE);
                new ScanFilesTask().execute(new File(currentPath));
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(deleteCompletionReceiver, new IntentFilter(DeleteService.ACTION_DELETE_COMPLETE));

        operationBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;

                if (FileOperationService.ACTION_OPERATION_PROGRESS.equals(action)) {
                    String text = intent.getStringExtra(FileOperationService.EXTRA_PROGRESS_TEXT);
                    int progress = intent.getIntExtra(FileOperationService.EXTRA_PROGRESS_VALUE, 0);
                    operationProgressLayout.setVisibility(View.VISIBLE);
                    operationProgressText.setText(text);
                    operationProgressBar.setIndeterminate(false);
                    operationProgressBar.setProgress(progress);
                } else if (FileOperationService.ACTION_OPERATION_COMPLETE.equals(action)) {
                    operationProgressLayout.setVisibility(View.GONE);
                    boolean success = intent.getBooleanExtra(FileOperationService.EXTRA_SUCCESS, false);
                    if (success) {
                        Toast.makeText(StorageBrowserActivity.this, "Operation complete.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(StorageBrowserActivity.this, "Operation failed.", Toast.LENGTH_LONG).show();
                    }
                    refreshCurrentDirectory();
                }
            }
        };
        IntentFilter opFilter = new IntentFilter();
        opFilter.addAction(FileOperationService.ACTION_OPERATION_PROGRESS);
        opFilter.addAction(FileOperationService.ACTION_OPERATION_COMPLETE);
        LocalBroadcastManager.getInstance(this).registerReceiver(operationBroadcastReceiver, opFilter);

        compressionBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean success = intent.getBooleanExtra(CompressionService.EXTRA_SUCCESS, false);
                if (success) {
                    refreshCurrentDirectory();
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(compressionBroadcastReceiver, new IntentFilter(CompressionService.ACTION_COMPRESSION_COMPLETE));
    }


    @Override
    protected void onDestroy() {
        if (deleteCompletionReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(deleteCompletionReceiver);
        }
        if (operationBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(operationBroadcastReceiver);
        }
        if (compressionBroadcastReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(compressionBroadcastReceiver);
        }
        super.onDestroy();
    }

    private void promptForSdCardPermission() {
        new AlertDialog.Builder(this)
                        .setTitle("SD Card Permission Needed")
                        .setMessage("To perform this operation on your external SD card, you must grant this app access. Please tap 'Grant', then select the root of your SD card and tap 'Allow'.")
                        .setPositiveButton("Grant", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                        StorageUtils.requestSdCardPermission(StorageBrowserActivity.this);
                                }
                        })
                        .setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == StorageUtils.REQUEST_CODE_SDCARD_PERMISSION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();
                if (treeUri != null) {
                    getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    StorageUtils.saveSdCardUri(this, treeUri);
                    Toast.makeText(this, "SD card access granted.", Toast.LENGTH_SHORT).show();
                    if (mPendingOperation != null) {
                        mPendingOperation.run();
                    }
                }
            } else {
                Toast.makeText(this, "SD card permission was not granted.", Toast.LENGTH_SHORT).show();
            }
            mFilesPendingPermission = null;
            mPendingOperation = null;
            mFilePendingPermissionForExtraction = null;
        }
    }

    private void openFileViewer(final File file) {
        new AsyncTask<Void, Void, Intent>() {
            @Override
            protected Intent doInBackground(Void... voids) {
                String path = file.getAbsolutePath();
                String name = file.getName();
                int category = getFileCategory(name);
                Intent intent = null;

                if (category == CATEGORY_IMAGES || category == CATEGORY_VIDEOS || category == CATEGORY_AUDIO) {
                    ArrayList<String> fileList = getSiblingFilesForViewer(file, category);
                    int currentIndex = fileList.indexOf(path);
                    if (currentIndex == -1) return null;

                    if (category == CATEGORY_IMAGES) {
                        intent = new Intent(StorageBrowserActivity.this, ImageViewerActivity.class);
                        intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(ImageViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_VIDEOS) {
                        intent = new Intent(StorageBrowserActivity.this, VideoViewerActivity.class);
                        intent.putStringArrayListExtra(VideoViewerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(VideoViewerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    } else if (category == CATEGORY_AUDIO) {
                        intent = new Intent(StorageBrowserActivity.this, AudioPlayerActivity.class);
                        intent.putStringArrayListExtra(AudioPlayerActivity.EXTRA_FILE_PATH_LIST, fileList);
                        intent.putExtra(AudioPlayerActivity.EXTRA_CURRENT_INDEX, currentIndex);
                    }
                } else {
                    if (name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                        intent = new Intent(StorageBrowserActivity.this, PdfViewerActivity.class);
                    } else {
                        intent = new Intent(StorageBrowserActivity.this, TextViewerActivity.class);
                    }
                    intent.putExtra(TextViewerActivity.EXTRA_FILE_PATH, path);
                }
                return intent;
            }

            @Override
            protected void onPostExecute(Intent intent) {
                if (intent != null) {
                    startActivity(intent);
                } else {
                    Toast.makeText(StorageBrowserActivity.this, "Error opening file.", Toast.LENGTH_SHORT).show();
                }
            }
        }.execute();
    }

    private ArrayList<String> getSiblingFilesForViewer(File currentFile, final int category) {
        ArrayList<String> siblingFiles = new ArrayList<>();
        for(Object item : masterList){
            if (item instanceof StorageBrowserAdapter.FileItem) {
                File file = ((StorageBrowserAdapter.FileItem) item).getFile();
                if(file.isFile() && getFileCategory(file.getName()) == category){
                    siblingFiles.add(file.getAbsolutePath());
                }
            }
        }
        Collections.sort(siblingFiles);
        return siblingFiles;
    }

    private int getFileCategory(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase(Locale.ROOT);
        }
        List<String> imageExtensions = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");
        List<String> videoExtensions = Arrays.asList("mp4", "3gp", "mkv", "webm", "avi");
        List<String> audioExtensions = Arrays.asList("mp3", "wav", "ogg", "m4a", "aac", "flac");
        List<String> docExtensions = Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "json", "xml", "html", "js", "css", "java", "kt", "py", "c", "cpp", "h", "cs", "php", "rb", "go", "swift", "sh", "bat", "ps1", "ini", "cfg", "conf", "md", "prop", "gradle", "pro", "sql");
        if (imageExtensions.contains(extension)) return CATEGORY_IMAGES;
        if (videoExtensions.contains(extension)) return CATEGORY_VIDEOS;
        if (audioExtensions.contains(extension)) return CATEGORY_AUDIO;
        if (docExtensions.contains(extension)) return CATEGORY_DOCS;
        return CATEGORY_OTHER;
    }

    private List<File> getSelectedFiles() {
        List<File> selectedFiles = new ArrayList<>();
        for (Object item : displayList) {
            if (item instanceof StorageBrowserAdapter.FileItem) {
                StorageBrowserAdapter.FileItem fileItem = (StorageBrowserAdapter.FileItem) item;
                if (fileItem.isSelected()) {
                    selectedFiles.add(fileItem.getFile());
                }
            }
        }
        return selectedFiles;
    }

    private void updateFooterUI() {
        boolean hasItems = com.hfm.app.ClipboardManager.getInstance().hasItems();
        findViewById(R.id.footer_controls_browser).setVisibility(hasItems ? View.GONE : View.VISIBLE);
        pasteControlsLayout.setVisibility(hasItems ? View.VISIBLE : View.GONE);
    }

    private void performPaste() {
        final File destination = new File(currentPath);
        if (StorageUtils.isFileOnSdCard(this, destination) && !StorageUtils.hasSdCardPermission(this)) {
            mPendingOperation = new Runnable() {
                @Override
                public void run() {
                    performPaste();
                }
            };
            promptForSdCardPermission();
            return;
        }

        List<File> filesToOperate = com.hfm.app.ClipboardManager.getInstance().getItems();
        com.hfm.app.ClipboardManager.Operation operation = com.hfm.app.ClipboardManager.getInstance().getOperation();

        if (!destination.isDirectory()) {
            Toast.makeText(this, "Destination is not a valid folder.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent serviceIntent = new Intent(this, FileOperationService.class);
        serviceIntent.setAction(FileOperationService.ACTION_START_OPERATION);
        serviceIntent.putExtra(FileOperationService.EXTRA_SOURCE_FILES, (Serializable) filesToOperate);
        serviceIntent.putExtra(FileOperationService.EXTRA_DESTINATION_DIR, destination);
        serviceIntent.putExtra(FileOperationService.EXTRA_OPERATION_TYPE, operation);
        ContextCompat.startForegroundService(this, serviceIntent);

        com.hfm.app.ClipboardManager.getInstance().clear();
        updateFooterUI();
    }

    private void showCreateFolderDialog() {
        final File destination = new File(currentPath);
        if (StorageUtils.isFileOnSdCard(this, destination) && !StorageUtils.hasSdCardPermission(this)) {
            mPendingOperation = new Runnable() {
                @Override
                public void run() {
                    showCreateFolderDialog();
                }
            };
            promptForSdCardPermission();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_create_folder, null);
        final EditText folderNameInput = dialogView.findViewById(R.id.edit_text_folder_name);

        builder.setView(dialogView)
                        .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int id) {
                                        String folderName = folderNameInput.getText().toString().trim();
                                        if (!folderName.isEmpty()) {
                                                File newFolder = new File(currentPath, folderName);
                                                if (StorageUtils.createDirectory(StorageBrowserActivity.this, newFolder)) {
                                                        Toast.makeText(StorageBrowserActivity.this, "Folder created.", Toast.LENGTH_SHORT).show();
                                                        refreshCurrentDirectory();
                                                } else {
                                                        Toast.makeText(StorageBrowserActivity.this, "Failed to create folder.", Toast.LENGTH_SHORT).show();
                                                }
                                        }
                                }
                        })
                        .setNegativeButton("Cancel", null);
        builder.create().show();
    }

    public void refreshCurrentDirectory() {
        new ScanFilesTask().execute(new File(currentPath));
    }

    private class MoveToRecycleTask extends AsyncTask<Void, String, List<File>> {
        private AlertDialog progressDialog;
        private List<File> filesToMove;
        private Context context;
        private boolean useSdCardBin;

        public MoveToRecycleTask(List<File> filesToMove, boolean useSdCardBin) {
            this.filesToMove = filesToMove;
            this.context = StorageBrowserActivity.this;
            this.useSdCardBin = useSdCardBin;
        }

        @Override
        protected void onPreExecute() {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_progress_simple, null);
            builder.setView(dialogView);
            builder.setCancelable(false);
            progressDialog = builder.create();
            progressDialog.show();
        }

        @Override
        protected List<File> doInBackground(Void... voids) {
            List<File> movedFiles = new ArrayList<>();
            File recycleBinDir = new File(Environment.getExternalStorageDirectory(), "HFMRecycleBin");
            if (!recycleBinDir.exists() && !useSdCardBin) {
                 if (!recycleBinDir.mkdir()) return new ArrayList<>();
            }

            for (int i = 0; i < filesToMove.size(); i++) {
                File sourceFile = filesToMove.get(i);
                publishProgress("Moving: " + sourceFile.getName());

                if (!sourceFile.exists()) continue;

                boolean moveSuccess = false;

                if (useSdCardBin && StorageUtils.isFileOnSdCard(context, sourceFile)) {
                     if (StorageUtils.moveFileOnSdCardSafely(context, sourceFile)) {
                         moveSuccess = true;
                     }
                } else {
                     File destFile = new File(recycleBinDir, sourceFile.getName());
                     if (destFile.exists()) {
                        String name = sourceFile.getName();
                        String extension = "";
                        int dotIndex = name.lastIndexOf(".");
                        if (dotIndex > 0 && !sourceFile.isDirectory()) {
                            extension = name.substring(dotIndex);
                            name = name.substring(0, dotIndex);
                        }
                        destFile = new File(recycleBinDir, name + "_" + System.currentTimeMillis() + extension);
                    }
                    
                    if (sourceFile.renameTo(destFile)) {
                        moveSuccess = true;
                    } else {
                        if (StorageUtils.copyFile(context, sourceFile, destFile)) {
                            if (StorageUtils.deleteFile(context, sourceFile)) {
                                moveSuccess = true;
                            } else {
                                destFile.delete(); 
                            }
                        } 
                    }
                    if(moveSuccess) sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destFile)));
                }

                if (moveSuccess) {
                    movedFiles.add(sourceFile);
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(sourceFile)));
                }
            }
            return movedFiles;
        }

        @Override
        protected void onPostExecute(List<File> movedFiles) {
            progressDialog.dismiss();
            if (movedFiles.isEmpty() && !filesToMove.isEmpty()) {
                Toast.makeText(context, "Failed to move files.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(context, movedFiles.size() + " item(s) moved to Recycle Bin.", Toast.LENGTH_LONG).show();
            }

            if (!movedFiles.isEmpty()) {
                List<Object> itemsToRemove = new ArrayList<>();
                for (Object item : masterList) {
                    if (item instanceof StorageBrowserAdapter.FileItem) {
                        if (movedFiles.contains(((StorageBrowserAdapter.FileItem) item).getFile())) {
                            itemsToRemove.add(item);
                        }
                    }
                }
                masterList.removeAll(itemsToRemove);
                rebuildDisplayList();
            }
        }

        private boolean copyFile(File source, File destination) {
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(source);
                out = new FileOutputStream(destination);
                byte[] buf = new byte[131072]; 
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                return true;
            } catch (IOException e) {
                return StorageUtils.copyFile(context, source, destination);
            } finally {
                try {
                    if (in != null) in.close();
                    if (out != null) out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

--- START OF FILE 1RitualManager.java.txt ---

package com.hfm.app;

import android.content.ContentResolver;
import android.content.Context;
import android.location.Location;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class RitualManager {

    private static final String TAG = "RitualManager";
    private static final String RITUAL_FILE_NAME = "rituals.dat";
    private static final String HIDDEN_DIR_NAME = "hidden";

    // --- Data Class to represent a single Ritual ---
    public static class Ritual implements Serializable {
        private static final long serialVersionUID = 2L; // Updated version ID due to structure change

        public final int tapCount;
        public final int shakeCount;
        public final float[] magnetometerData;
        public final double latitude;
        public final double longitude;

        // --- FIELDS FOR MAP FALLBACK ---
        public Double fallbackLatitude;
        public Double fallbackLongitude;

        public List<HiddenFile> hiddenFiles;

        public Ritual(int tapCount, int shakeCount, float[] magnetometerData, Location location) {
            this.tapCount = tapCount;
            this.shakeCount = shakeCount;
            this.magnetometerData = magnetometerData.clone();
            this.latitude = location.getLatitude();
            this.longitude = location.getLongitude();
            this.hiddenFiles = new ArrayList<>();
            this.fallbackLatitude = null;
            this.fallbackLongitude = null;
        }

        public void addHiddenFile(HiddenFile file) {
            this.hiddenFiles.add(file);
        }
    }

    // --- Data Class to store info about a hidden file ---
    public static class HiddenFile implements Serializable {
        private static final long serialVersionUID = 3L; // Updated UID

        public final String originalPath;
        public final String encryptedFileName; // The new random name in the hidden directory
        public final boolean isFolder; // NEW: Track if this is a zipped folder

        public HiddenFile(String originalPath, String encryptedFileName, boolean isFolder) {
            this.originalPath = originalPath;
            this.encryptedFileName = encryptedFileName;
            this.isFolder = isFolder;
        }
    }

    // --- Public Method to Start the Hiding Process ---
    public void createAndSaveRitual(Context context, int taps, int shakes, float[] magnetometer, Location location, List<File> filesToHide) {
        Ritual newRitual = new Ritual(taps, shakes, magnetometer, location);
        new HideFilesTask(context, newRitual, filesToHide, -1).execute();
    }

    // --- NEW Public Method to add files to an existing Ritual ---
    public void addFilesToRitual(Context context, int ritualIndex, List<File> filesToHide) {
        List<Ritual> rituals = loadRituals(context);
        if (rituals != null && ritualIndex >= 0 && ritualIndex < rituals.size()) {
            Ritual existingRitual = rituals.get(ritualIndex);
            new HideFilesTask(context, existingRitual, filesToHide, ritualIndex).execute();
        } else {
            Toast.makeText(context, "Error: Could not find the specified ritual to update.", Toast.LENGTH_LONG).show();
        }
    }

    // --- Public Method to Start the Unhiding Process ---
    public void verifyAndDecryptRitual(Context context, Ritual ritual, int ritualIndex) {
        new UnhideFilesTask(context, ritual, ritualIndex).execute();
    }


    // --- File Persistence Methods ---
    public List<Ritual> loadRituals(Context context) {
        File ritualFile = new File(context.getFilesDir(), RITUAL_FILE_NAME);
        if (!ritualFile.exists()) {
            return new ArrayList<>();
        }
        try {
            FileInputStream fis = new FileInputStream(ritualFile);
            ObjectInputStream ois = new ObjectInputStream(fis);
            List<Ritual> rituals = (List<Ritual>) ois.readObject();
            ois.close();
            fis.close();
            return rituals;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load rituals", e);
            return new ArrayList<>(); // Return empty list on failure
        }
    }

    public void saveRituals(Context context, List<Ritual> rituals) {
        File ritualFile = new File(context.getFilesDir(), RITUAL_FILE_NAME);
        try {
            FileOutputStream fos = new FileOutputStream(ritualFile);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(rituals);
            oos.close();
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save rituals", e);
        }
    }

    // --- Cryptography and Key Generation ---
    private SecretKeySpec generateKey(Ritual ritual) throws Exception {
        StringBuilder passwordBuilder = new StringBuilder();
        passwordBuilder.append("taps:").append(ritual.tapCount);
        passwordBuilder.append("-shakes:").append(ritual.shakeCount);
        passwordBuilder.append("-mag:");
        passwordBuilder.append(Float.floatToIntBits(ritual.magnetometerData[0])).append(",");
        passwordBuilder.append(Float.floatToIntBits(ritual.magnetometerData[1])).append(",");
        passwordBuilder.append(Float.floatToIntBits(ritual.magnetometerData[2]));
        passwordBuilder.append("-loc:");
        passwordBuilder.append(Double.doubleToLongBits(ritual.latitude)).append(",");
        passwordBuilder.append(Double.doubleToLongBits(ritual.longitude));

        String passwordString = passwordBuilder.toString();
        
        String salt = "hfm_secure_salt"; 
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        KeySpec spec = new PBEKeySpec(passwordString.toCharArray(), salt.getBytes(), 65536, 256); // 256-bit key
        SecretKey tmp = factory.generateSecret(spec);

        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    // --- MODIFIED AsyncTask to handle file HIDING ---
    private class HideFilesTask extends AsyncTask<Void, String, Boolean> {
        private final Context context;
        private final Ritual ritual;
        private final List<File> filesToHide;
        private final SecretKeySpec secretKey;
        private final int ritualIndex;

        HideFilesTask(Context context, Ritual ritual, List<File> filesToHide, int ritualIndex) {
            this.context = context;
            this.ritual = ritual;
            this.filesToHide = filesToHide;
            this.ritualIndex = ritualIndex;
            SecretKeySpec key = null;
            try {
                key = generateKey(ritual);
            } catch (Exception e) {
                Log.e(TAG, "Key generation failed!", e);
            }
            this.secretKey = key;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            Toast.makeText(context, "Starting encryption process...", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            if (secretKey == null) {
                return false;
            }

            File hiddenDir = new File(context.getFilesDir(), HIDDEN_DIR_NAME);
            if (!hiddenDir.exists()) {
                hiddenDir.mkdir();
            }

            // ContentResolver resolver = context.getContentResolver(); // Removed to fix system popup

            for (int i = 0; i < filesToHide.size(); i++) {
                File originalFile = filesToHide.get(i);
                String path = originalFile.getAbsolutePath();
                boolean isFolder = originalFile.isDirectory();
                
                publishProgress("Encrypting: " + originalFile.getName() + " (" + (i + 1) + "/" + filesToHide.size() + ")");

                File fileToEncrypt = originalFile;
                File tempZipFile = null;

                // --- NEW LOGIC: Zip Folder before encrypting ---
                if (isFolder) {
                    try {
                        tempZipFile = new File(context.getCacheDir(), UUID.randomUUID().toString() + "_temp.zip");
                        zipFolder(originalFile, tempZipFile);
                        fileToEncrypt = tempZipFile;
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to zip folder: " + originalFile.getName(), e);
                        continue; // Skip this file if zipping fails
                    }
                }

                String encryptedFileName = UUID.randomUUID().toString() + ".hfm";
                File encryptedFile = new File(hiddenDir, encryptedFileName);

                try {
                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    IvParameterSpec iv = new IvParameterSpec("hfm_static_iv_16".getBytes(StandardCharsets.UTF_8));
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);

                    FileInputStream fis = new FileInputStream(fileToEncrypt);
                    FileOutputStream fos = new FileOutputStream(encryptedFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] output = cipher.update(buffer, 0, bytesRead);
                        if (output != null) {
                            fos.write(output);
                        }
                    }
                    byte[] finalBytes = cipher.doFinal();
                    if (finalBytes != null) {
                        fos.write(finalBytes);
                    }
                    fis.close();
                    fos.flush();
                    fos.close();

                    ritual.addHiddenFile(new HiddenFile(path, encryptedFileName, isFolder));

                    // Clean up temp zip
                    if (tempZipFile != null && tempZipFile.exists()) {
                        tempZipFile.delete();
                    }

                    // --- FIX FOR SYSTEM POPUP ---
                    // Do NOT use ContentResolver.delete(). Only do physical delete.
                    // Then trigger a silent MediaScan on the DELETED path to clean the DB.
                    if (isFolder) {
                        StorageUtils.deleteRecursive(context, originalFile);
                    } else {
                        if (!originalFile.delete()) {
                            StorageUtils.deleteFile(context, originalFile);
                        }
                    }
                    
                    // Silent DB Update: Scan the path that no longer exists
                    MediaScannerConnection.scanFile(context, new String[]{path}, null, null);

                } catch (Exception e) {
                    Log.e(TAG, "Encryption failed for " + originalFile.getName(), e);
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            Toast.makeText(context, values[0], Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (success) {
                List<Ritual> rituals = loadRituals(context);
                if (ritualIndex == -1) {
                    rituals.add(ritual);
                } else {
                    rituals.set(ritualIndex, ritual);
                }
                saveRituals(context, rituals);
                Toast.makeText(context, "Items successfully hidden!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "A critical error occurred. Hiding process failed.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- AsyncTask to handle file UNHIDING ---
    private class UnhideFilesTask extends AsyncTask<Void, String, Boolean> {
        private final Context context;
        private final Ritual ritual;
        private final int ritualIndex;
        private final SecretKeySpec secretKey;

        UnhideFilesTask(Context context, Ritual ritual, int ritualIndex) {
            this.context = context;
            this.ritual = ritual;
            this.ritualIndex = ritualIndex;
            SecretKeySpec key = null;
            try {
                key = generateKey(ritual);
            } catch (Exception e) {
                Log.e(TAG, "Key re-generation failed for decryption!", e);
            }
            this.secretKey = key;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            Toast.makeText(context, "Starting decryption process...", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            if (secretKey == null) return false;

            File hiddenDir = new File(context.getFilesDir(), HIDDEN_DIR_NAME);
            if (!hiddenDir.exists()) return false;

            for (int i = 0; i < ritual.hiddenFiles.size(); i++) {
                HiddenFile hiddenFile = ritual.hiddenFiles.get(i);
                File encryptedFile = new File(hiddenDir, hiddenFile.encryptedFileName);
                final File destinationPath = new File(hiddenFile.originalPath);

                publishProgress("Decrypting: " + destinationPath.getName() + " (" + (i + 1) + "/" + ritual.hiddenFiles.size() + ")");

                // Decrypt to a temporary file in internal cache first
                File tempDecryptedFile = new File(context.getCacheDir(), "temp_decrypt_" + UUID.randomUUID().toString());

                try {
                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    IvParameterSpec iv = new IvParameterSpec("hfm_static_iv_16".getBytes(StandardCharsets.UTF_8));
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, iv);

                    FileInputStream fis = new FileInputStream(encryptedFile);
                    FileOutputStream fos = new FileOutputStream(tempDecryptedFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] output = cipher.update(buffer, 0, bytesRead);
                        if (output != null) {
                            fos.write(output);
                        }
                    }
                    byte[] finalBytes = cipher.doFinal();
                    if (finalBytes != null) {
                        fos.write(finalBytes);
                    }
                    fis.close();
                    fos.flush();
                    fos.close();

                    // --- RESTORE LOGIC ---
                    if (hiddenFile.isFolder) {
                        // If it was a folder, unzip it to the destination
                        unzip(tempDecryptedFile, destinationPath);
                    } else {
                        // If file, move copy safely using StorageUtils (Handles SD Card permissions)
                        copyFileSafely(tempDecryptedFile, destinationPath);
                    }

                    // --- FIX FOR SLOW FILE REAPPEARANCE ---
                    // Force the MediaScanner to index the new file immediately.
                    // This is much faster/reliable than the old Broadcast intent.
                    MediaScannerConnection.scanFile(context, 
                        new String[]{ destinationPath.getAbsolutePath() }, 
                        null, 
                        new MediaScannerConnection.OnScanCompletedListener() {
                            @Override
                            public void onScanCompleted(String path, Uri uri) {
                                Log.d(TAG, "Scanned " + path + ":");
                                Log.d(TAG, "-> uri=" + uri);
                            }
                        }
                    );

                    // Cleanup temp file
                    tempDecryptedFile.delete();

                    // If decryption successful, delete encrypted file
                    encryptedFile.delete();

                } catch (Exception e) {
                    Log.e(TAG, "Decryption/Restore failed for " + encryptedFile.getName(), e);
                    if (tempDecryptedFile.exists()) tempDecryptedFile.delete();
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            Toast.makeText(context, values[0], Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (success) {
                List<Ritual> rituals = loadRituals(context);
                if (rituals.size() > ritualIndex) {
                    rituals.remove(ritualIndex);
                }
                saveRituals(context, rituals);
                Toast.makeText(context, "Items restored successfully!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "Error: Decryption process failed.", Toast.LENGTH_LONG).show();
            }
        }
        
        // --- Helper: Copy file safely (SD Card Compatible) ---
        private void copyFileSafely(File source, File destination) throws IOException {
            InputStream in = new FileInputStream(source);
            // Use StorageUtils to get a writeable stream, which handles SAF/SD cards
            OutputStream out = StorageUtils.getOutputStream(context, destination);
            
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();
        }

        // --- Helper: Unzip (SD Card Compatible) ---
        private void unzip(File zipFile, File targetDirectory) throws IOException {
            if (!targetDirectory.exists()) {
                StorageUtils.createDirectory(context, targetDirectory);
            }

            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
            try {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    File outFile = new File(targetDirectory, ze.getName());
                    
                    if (ze.isDirectory()) {
                        StorageUtils.createDirectory(context, outFile);
                    } else {
                        // Ensure parent exists
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists()) {
                            StorageUtils.createDirectory(context, parent);
                        }
                        
                        // Use StorageUtils output stream for SD card support
                        OutputStream out = StorageUtils.getOutputStream(context, outFile);
                        BufferedOutputStream fout = new BufferedOutputStream(out);
                        
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            fout.write(buffer, 0, count);
                        }
                        fout.close();
                    }
                }
            } finally {
                zis.close();
            }
        }
    }

    // --- Zipping Helpers (Inside main class for self-containment) ---

    private void zipFolder(File fileToZip, File zipFile) throws IOException {
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile));
        zipFile(fileToZip, fileToZip.getName(), zipOut);
        zipOut.close();
    }

    private void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }
        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }
            File[] children = fileToZip.listFiles();
            if (children != null) {
                for (File childFile : children) {
                    zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
                }
            }
            return;
        }
        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);
        byte[] bytes = new byte[8192];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }
}