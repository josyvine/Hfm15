package com.vineyard.hfm.app;

import android.content.Context;
import android.graphics.PorterDuff;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;

public class RecycleBinAdapter extends RecyclerView.Adapter<RecycleBinAdapter.ViewHolder> {

    private final Context context;
    private final List<File> fileList;
    private final OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(File file);
        void onItemLongClick(File file);
    }

    public RecycleBinAdapter(Context context, List<File> fileList, OnItemClickListener listener) {
        this.context = context;
        this.fileList = fileList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.list_item_file_picker, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final File file = fileList.get(position);
        holder.fileName.setText(file.getName());

        // Determine contrast color based on theme
        int contrastColor;
        String currentTheme = ThemeManager.getTheme(context);
        if (currentTheme.equals(ThemeManager.THEME_DARK) || currentTheme.equals(ThemeManager.THEME_AMOLED) || currentTheme.equals(ThemeManager.THEME_NORDIC)) {
            contrastColor = ContextCompat.getColor(context, android.R.color.white);
        } else {
            contrastColor = ContextCompat.getColor(context, R.color.lt_colorPrimary);
        }

        // Hide checkbox as it's not needed in the recycle bin view
        holder.checkBox.setVisibility(View.GONE);

        if (file.isDirectory()) {
            // UPDATED: Modern yellow folder icon
            holder.fileIcon.setImageResource(R.drawable.ic_folder_modern);
            // Apply theme-based tint
            holder.fileIcon.setColorFilter(contrastColor, PorterDuff.Mode.SRC_IN);
        } else {
            holder.fileIcon.setImageResource(getIconForFileType(file.getName()));
            // Apply theme-based tint
            holder.fileIcon.setColorFilter(contrastColor, PorterDuff.Mode.SRC_IN);
        }

        holder.itemView.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					if (listener != null) {
						listener.onItemClick(file);
					}
				}
			});

        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
				@Override
				public boolean onLongClick(View v) {
					if (listener != null) {
						listener.onItemLongClick(file);
					}
					return true; // Consume the long click event
				}
			});
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView fileIcon;
        TextView fileName;
        View checkBox; // We get a reference to hide it, even though it's a CheckBox

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.file_icon_picker);
            fileName = itemView.findViewById(R.id.file_name_picker);
            checkBox = itemView.findViewById(R.id.file_checkbox_picker);
        }
    }

    private int getIconForFileType(String fileName) {
        String lowerFileName = fileName.toLowerCase();
        if (lowerFileName.endsWith(".doc") || lowerFileName.endsWith(".docx") || lowerFileName.endsWith(".pdf")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".xls") || lowerFileName.endsWith(".xlsx")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".ppt") || lowerFileName.endsWith(".pptx")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".txt") || lowerFileName.endsWith(".rtf") || lowerFileName.endsWith(".log")) return R.drawable.docs_24px;
        if (lowerFileName.endsWith(".zip") || lowerFileName.endsWith(".rar") || lowerFileName.endsWith(".7z")) return R.drawable.category_24px;
        if (lowerFileName.endsWith(".mp3") || lowerFileName.endsWith(".wav") || lowerFileName.endsWith(".ogg")) return R.drawable.audio_file_24px;
        if (lowerFileName.endsWith(".jpg") || lowerFileName.endsWith(".jpeg") || lowerFileName.endsWith(".png") || lowerFileName.endsWith(".gif")) return R.drawable.image_24px;
        if (lowerFileName.endsWith(".mp4") || lowerFileName.endsWith(".mkv") || lowerFileName.endsWith(".avi")) return R.drawable.video_file_24px;
        return R.drawable.category_24px;
    }
}