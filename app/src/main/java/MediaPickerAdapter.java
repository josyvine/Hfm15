package com.hfm.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MediaPickerAdapter extends RecyclerView.Adapter<MediaPickerAdapter.FileViewHolder> {

    private final Context context;
    private List<FileItem> fileList;
    private final OnItemClickListener itemClickListener;

    public interface OnItemClickListener {
        void onSelectionChanged();
    }

    public MediaPickerAdapter(Context context, List<File> files, OnItemClickListener itemClickListener) {
        this.context = context;
        this.itemClickListener = itemClickListener;
        this.fileList = new ArrayList<>();
        for (File file : files) {
            this.fileList.add(new FileItem(file));
        }
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.grid_item_media_picker, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final FileViewHolder holder, final int position) {
        final FileItem item = fileList.get(position);
        final File file = item.getFile();

        holder.fileName.setText(file.getName());
        holder.selectionOverlay.setVisibility(item.isSelected() ? View.VISIBLE : View.GONE);

        // Reset listener to prevent unwanted triggering during scrolling
        holder.selectionCheckbox.setOnCheckedChangeListener(null);
        holder.selectionCheckbox.setChecked(item.isSelected());

        holder.selectionCheckbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                item.setSelected(isChecked);
                holder.selectionOverlay.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                if (itemClickListener != null) {
                    itemClickListener.onSelectionChanged();
                }
            }
        });

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Toggle checkbox state on item click
                holder.selectionCheckbox.setChecked(!holder.selectionCheckbox.isChecked());
            }
        });

        // GLIDE INTEGRATION: Replaces the manual Executor/BitmapFactory logic
        int fallbackIcon = getIconForFileType(file.getName());
        
        Glide.with(context)
            .load(file)
            .apply(new RequestOptions()
                .placeholder(fallbackIcon)
                .error(fallbackIcon)
                .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache resized images for speed
                .centerCrop())
            .into(holder.thumbnailImage);
    }

    private int getIconForFileType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".pdf")) return R.drawable.docs_24px;
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return R.drawable.docs_24px;
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return R.drawable.docs_24px;
        if (lower.endsWith(".txt") || lower.endsWith(".rtf") || lower.endsWith(".log")) return R.drawable.docs_24px;
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")) return R.drawable.category_24px;
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg")) return R.drawable.audio_file_24px;
        
        // Default icon
        return R.drawable.category_24px;
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    public List<FileItem> getItems() {
        return fileList;
    }

    public static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView thumbnailImage;
        TextView fileName;
        View selectionOverlay;
        CheckBox selectionCheckbox;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnailImage = itemView.findViewById(R.id.thumbnail_image_media);
            fileName = itemView.findViewById(R.id.file_name_media);
            selectionOverlay = itemView.findViewById(R.id.selection_overlay_media);
            selectionCheckbox = itemView.findViewById(R.id.selection_checkbox_media);
        }
    }

    public static class FileItem {
        private File file;
        private boolean isSelected;

        public FileItem(File file) {
            this.file = file;
            this.isSelected = false;
        }

        public File getFile() {
            return file;
        }

        public boolean isSelected() {
            return isSelected;
        }

        public void setSelected(boolean selected) {
            isSelected = selected;
        }
    }
}