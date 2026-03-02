package com.hfm.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

        // Hide checkbox as it's not needed in the recycle bin view
        holder.checkBox.setVisibility(View.GONE);

        if (file.isDirectory()) {
            holder.fileIcon.setImageResource(R.drawable.ic_folder_modern);
        } else {
            holder.fileIcon.setImageResource(getIconForFileType(file.getName()));
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
        if (lowerFileName.endsWith(".doc") || lowerFileName.endsWith(".docx") || lowerFileName.endsWith(".pdf")) return android.R.drawable.ic_menu_save;
        if (lowerFileName.endsWith(".xls") || lowerFileName.endsWith(".xlsx")) return android.R.drawable.ic_menu_agenda;
        if (lowerFileName.endsWith(".ppt") || lowerFileName.endsWith(".pptx")) return android.R.drawable.ic_menu_slideshow;
        if (lowerFileName.endsWith(".txt") || lowerFileName.endsWith(".rtf") || lowerFileName.endsWith(".log")) return android.R.drawable.ic_menu_view;
        if (lowerFileName.endsWith(".zip") || lowerFileName.endsWith(".rar") || lowerFileName.endsWith(".7z")) return android.R.drawable.ic_menu_set_as;
        if (lowerFileName.endsWith(".mp3") || lowerFileName.endsWith(".wav") || lowerFileName.endsWith(".ogg")) return android.R.drawable.ic_media_play;
        if (lowerFileName.endsWith(".jpg") || lowerFileName.endsWith(".jpeg") || lowerFileName.endsWith(".png") || lowerFileName.endsWith(".gif")) return android.R.drawable.ic_menu_gallery;
        if (lowerFileName.endsWith(".mp4") || lowerFileName.endsWith(".mkv") || lowerFileName.endsWith(".avi")) return android.R.drawable.ic_media_play;
        return android.R.drawable.ic_menu_info_details;
    }
}