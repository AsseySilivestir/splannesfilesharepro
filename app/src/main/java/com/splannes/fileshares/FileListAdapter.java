package com.splannes.fileshares;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RecyclerView adapter for file/folder items.
 *
 * Fixes from original:
 * - No Context field (prevents Activity leak)
 * - Uses equals() for item identification
 * - Duplicate prevention in addFile()
 * - Immutable list returned from getFileList()
 * - DiffUtil for efficient updates
 */
public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.FileViewHolder> {

    private List<FileItem> fileList = new ArrayList<>();
    private OnFileActionListener listener;

    public interface OnFileActionListener {
        void onPreview(FileItem fileItem);
        void onDownload(FileItem fileItem);
        void onPrint(FileItem fileItem);
        void onDelete(FileItem fileItem);
    }

    public FileListAdapter(OnFileActionListener listener) {
        this.listener = listener;
    }

    /**
     * Backward-compatible constructor that accepts Context but doesn't store it.
     */
    public FileListAdapter(@SuppressWarnings("unused") android.content.Context context,
                           OnFileActionListener listener) {
        this.listener = listener;
    }

    public void setFiles(List<FileItem> files) {
        List<FileItem> oldList = new ArrayList<>(this.fileList);
        this.fileList = new ArrayList<>(files);

        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return files.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                return oldList.get(oldPos).equals(files.get(newPos));
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                return oldList.get(oldPos).equals(files.get(newPos));
            }
        });
        result.dispatchUpdatesTo(this);
    }

    public void addFile(FileItem fileItem) {
        // Prevent duplicates
        for (FileItem existing : fileList) {
            if (existing.equals(fileItem)) return;
        }
        fileList.add(0, fileItem);
        notifyItemInserted(0);
    }

    public void removeFile(FileItem fileItem) {
        int position = -1;
        for (int i = 0; i < fileList.size(); i++) {
            if (fileList.get(i).equals(fileItem)) {
                position = i;
                break;
            }
        }
        if (position >= 0) {
            fileList.remove(position);
            notifyItemRemoved(position);
        }
    }

    public List<FileItem> getFileList() {
        return Collections.unmodifiableList(fileList);
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Use parent.getContext() instead of stored Context
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileItem fileItem = fileList.get(position);

        // Icon
        holder.fileIcon.setText(fileItem.isFolder()
                ? FileIconHelper.getFolderIcon()
                : FileIconHelper.getIcon(fileItem.getFileExtension()));

        // Name & meta
        holder.fileName.setText(fileItem.getFileName());
        holder.fileMeta.setText(fileItem.isFolder()
                ? "FOLDER"
                : fileItem.getFileExtension().toUpperCase() + " • " + fileItem.getFormattedSize());

        // Preview button
        holder.btnPreview.setEnabled(fileItem.isPreviewable());
        holder.btnPreview.setAlpha(fileItem.isPreviewable() ? 1.0f : 0.4f);
        holder.btnPreview.setOnClickListener(v -> {
            if (listener != null) listener.onPreview(fileItem);
        });

        // Download button (only for files, not folders)
        holder.btnDownload.setVisibility(fileItem.isDownloadable() ? View.VISIBLE : View.GONE);
        holder.btnDownload.setOnClickListener(v -> {
            if (listener != null) listener.onDownload(fileItem);
        });

        // Print button
        holder.btnPrint.setOnClickListener(v -> {
            if (listener != null) listener.onPrint(fileItem);
        });

        // Delete button with confirmation
        holder.btnDelete.setOnClickListener(v -> {
            String displayName = fileItem.getFileName();
            if (displayName.length() > 50) displayName = displayName.substring(0, 47) + "...";
            new MaterialAlertDialogBuilder(v.getContext())
                    .setTitle(fileItem.isFolder() ? "Remove Folder" : "Delete File")
                    .setMessage("Are you sure you want to remove \"" + displayName + "\"?")
                    .setPositiveButton("Remove", (dialog, which) -> {
                        if (listener != null) listener.onDelete(fileItem);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Row click = preview
        holder.itemView.setOnClickListener(v -> {
            if (listener != null && fileItem.isPreviewable()) listener.onPreview(fileItem);
        });
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        TextView fileIcon;
        TextView fileName;
        TextView fileMeta;
        MaterialButton btnPreview;
        MaterialButton btnDownload;
        MaterialButton btnPrint;
        MaterialButton btnDelete;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.fileIcon);
            fileName = itemView.findViewById(R.id.fileName);
            fileMeta = itemView.findViewById(R.id.fileMeta);
            btnPreview = itemView.findViewById(R.id.btnPreview);
            btnDownload = itemView.findViewById(R.id.btnDownload);
            btnPrint = itemView.findViewById(R.id.btnPrint);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}
