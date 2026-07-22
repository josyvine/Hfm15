package com.vineyard.hfm.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MediaPickerActivity extends Activity {

    // UI Elements
    private ImageButton backButton;
    private TextView titleTextView, selectionCountTextView;
    private RecyclerView mediaRecyclerView;
    private Button sendButton;
    private LinearLayout loadingView;

    private MediaPickerAdapter adapter;
    private List<File> mediaFileList = new ArrayList<>();
    private String categoryType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_picker);

        initializeViews();

        categoryType = getIntent().getStringExtra(CategoryPickerActivity.EXTRA_CATEGORY_TYPE);
        if (categoryType == null) {
            Toast.makeText(this, "Error: No category specified.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        setupRecyclerView();
        setupListeners();
        updateTitle();

        new ScanMediaTask().execute(categoryType);
    }

    private void initializeViews() {
        backButton = findViewById(R.id.back_button_media_picker);
        titleTextView = findViewById(R.id.title_text_media_picker);
        selectionCountTextView = findViewById(R.id.selection_count_text_media_picker);
        mediaRecyclerView = findViewById(R.id.media_recycler_view);
        sendButton = findViewById(R.id.button_send_media_picker);
        loadingView = findViewById(R.id.loading_view_media_picker);
    }

    private void setupRecyclerView() {
        adapter = new MediaPickerAdapter(this, mediaFileList, new MediaPickerAdapter.OnItemClickListener() {
				@Override
				public void onSelectionChanged() {
					updateSelectionCount();
				}
			});
        mediaRecyclerView.setLayoutManager(new GridLayoutManager(this, 3));
        mediaRecyclerView.setAdapter(adapter);
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					finish();
				}
			});

        sendButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					ArrayList<String> selectedPaths = new ArrayList<>();
					for (MediaPickerAdapter.FileItem item : adapter.getItems()) {
						if (item.isSelected()) {
							selectedPaths.add(item.getFile().getAbsolutePath());
						}
					}

					if (selectedPaths.isEmpty()) {
						Toast.makeText(MediaPickerActivity.this, "No files selected.", Toast.LENGTH_SHORT).show();
						return;
					}

					Intent resultIntent = new Intent();
					resultIntent.putStringArrayListExtra("picked_files", selectedPaths);
					setResult(Activity.RESULT_OK, resultIntent);
					finish();
				}
			});
    }

    private void updateTitle() {
        switch (categoryType) {
            case CategoryPickerActivity.CATEGORY_VIDEOS:
                titleTextView.setText("Select Videos");
                break;
            case CategoryPickerActivity.CATEGORY_IMAGES:
                titleTextView.setText("Select Images");
                break;
            case CategoryPickerActivity.CATEGORY_AUDIO:
                titleTextView.setText("Select Audio");
                break;
            case CategoryPickerActivity.CATEGORY_DOCUMENTS:
                titleTextView.setText("Select Documents");
                break;
        }
    }

    private void updateSelectionCount() {
        int count = 0;
        for (MediaPickerAdapter.FileItem item : adapter.getItems()) {
            if (item.isSelected()) {
                count++;
            }
        }
        selectionCountTextView.setText(count + " files selected");
    }

    private class ScanMediaTask extends AsyncTask<String, Void, List<File>> {

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingView.setVisibility(View.VISIBLE);
            mediaRecyclerView.setVisibility(View.GONE);
        }

        @Override
        protected List<File> doInBackground(String... params) {
            String category = params[0];
            List<File> foundFiles = new ArrayList<>();
            ContentResolver contentResolver = getContentResolver();

            Uri queryUri;
            String[] projection = {MediaStore.MediaColumns.DATA};
            String selection = null;
            String[] selectionArgs = null;

            switch (category) {
                case CategoryPickerActivity.CATEGORY_VIDEOS:
                    queryUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    break;
                case CategoryPickerActivity.CATEGORY_IMAGES:
                    queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    break;
                case CategoryPickerActivity.CATEGORY_AUDIO:
                    queryUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    break;
                case CategoryPickerActivity.CATEGORY_DOCUMENTS:
                    queryUri = MediaStore.Files.getContentUri("external");
                    selection = MediaStore.Files.FileColumns.MIME_TYPE + " IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    selectionArgs = new String[]{
                        "application/pdf",
                        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // .doc, .docx
                        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation", // .ppt, .pptx
                        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xls, .xlsx
                        "text/plain", "text/csv", "text/html"
                    };
                    break;
                default:
                    return foundFiles; // Return empty for unknown category
            }

            Cursor cursor = contentResolver.query(queryUri, projection, selection, selectionArgs, MediaStore.MediaColumns.DATE_MODIFIED + " DESC");

            if (cursor != null) {
                try {
                    int dataColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
                    while (cursor.moveToNext()) {
                        String path = cursor.getString(dataColumnIndex);
                        File file = new File(path);
                        if (file.exists() && file.length() > 0) { // Check if file exists and is not empty
                            foundFiles.add(file);
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
            return foundFiles;
        }

        @Override
        protected void onPostExecute(List<File> result) {
            super.onPostExecute(result);
            loadingView.setVisibility(View.GONE);
            mediaRecyclerView.setVisibility(View.VISIBLE);

            if (result.isEmpty()) {
                Toast.makeText(MediaPickerActivity.this, "No files found for this category.", Toast.LENGTH_LONG).show();
            } else {
                mediaFileList.clear();
                mediaFileList.addAll(result);
                adapter = new MediaPickerAdapter(MediaPickerActivity.this, mediaFileList, new MediaPickerAdapter.OnItemClickListener() {
						@Override
						public void onSelectionChanged() {
							updateSelectionCount();
						}
					});
                mediaRecyclerView.setAdapter(adapter);
            }
        }
    }
}

