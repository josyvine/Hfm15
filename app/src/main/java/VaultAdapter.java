package com.hfm.app;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

public class VaultAdapter extends RecyclerView.Adapter<VaultAdapter.VaultViewHolder> {

    private final Context context;
    private final List<File> fileList;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(File file);
    }

    public VaultAdapter(Context context, List<File> fileList, OnItemClickListener listener) {
        this.context = context;
        this.fileList = fileList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VaultViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.list_item_vault, parent, false);
        return new VaultViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VaultViewHolder holder, int position) {
        final File file = fileList.get(position);
        
        // DISPLAY LOGIC: Strip the security UUID prefix for the UI.
        // The prefix is the first 8 characters (UUID) plus the underscore.
        String rawName = file.getName();
        String displayName = rawName;
        if (rawName.length() > 9 && rawName.contains("_")) {
            displayName = rawName.substring(rawName.indexOf("_") + 1);
        }
        
        holder.fileName.setText(displayName);
        holder.fileSize.setText(Formatter.formatFileSize(context, file.length()));
        
        // ICON LOGIC: Set icon based on extension
        holder.fileIcon.setImageResource(getIconForFileType(displayName));

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onItemClick(file);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    private int getIconForFileType(String fileName) {
        String lower = fileName.toLowerCase();
        
        // Video Icons
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || 
            lower.endsWith(".webm") || lower.endsWith(".3gp")) {
            return android.R.drawable.ic_media_play;
        }
        
        // Image Icons
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || 
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")) {
            return android.R.drawable.ic_menu_gallery;
        }
        
        // Document Icons
        if (lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || 
            lower.endsWith(".txt") || lower.endsWith(".log")) {
            return android.R.drawable.ic_menu_view;
        }
        
        // Default Fallback
        return android.R.drawable.ic_menu_info_details;
    }

    public static class VaultViewHolder extends RecyclerView.ViewHolder {
        ImageView fileIcon;
        TextView fileName;
        TextView fileSize;

        public VaultViewHolder(@NonNull View itemView) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.vault_file_icon);
            fileName = itemView.findViewById(R.id.vault_file_name);
            fileSize = itemView.findViewById(R.id.vault_file_size);
        }
    }
}