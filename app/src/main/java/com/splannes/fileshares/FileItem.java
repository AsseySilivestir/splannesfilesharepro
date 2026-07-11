package com.splannes.fileshares;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * Immutable data model representing a shared file or folder.
 * Implements Parcelable for safe Intent passing and equals/hashCode
 * for correct RecyclerView and collection behavior.
 */
public class FileItem implements Parcelable {

    public enum Type {
        FILE, FOLDER
    }

    private final String fileName;
    private final String filePath;      // ALWAYS a filesystem path (e.g. /data/... or /storage/...)
    private final long fileSize;
    private final String fileUri;       // ALWAYS a URI string (content:// or file://)
    private final Type type;
    private final String folderPath;    // For folder items: the shared folder root path

    // Cached computed values
    private final String fileExtension;
    private final String mimeType;

    public FileItem(String fileName, String filePath, long fileSize, String fileUri) {
        this(fileName, filePath, fileSize, fileUri, Type.FILE, null);
    }

    public FileItem(String fileName, String filePath, long fileSize, String fileUri, Type type, String folderPath) {
        this.fileName = fileName != null ? fileName : "Unknown";
        this.filePath = filePath != null ? filePath : "";
        this.fileSize = Math.max(0, fileSize);
        this.fileUri = fileUri != null ? fileUri : "";
        this.type = type != null ? type : Type.FILE;
        this.folderPath = folderPath != null ? folderPath : "";

        this.fileExtension = extractExtension(this.fileName);
        this.mimeType = computeMimeType(this.fileExtension);
    }

    // --- Getters (no setters — this class is immutable) ---

    public String getFileName() { return fileName; }

    public String getFilePath() { return filePath; }

    public long getFileSize() { return fileSize; }

    public String getFileUri() { return fileUri; }

    public Type getType() { return type; }

    public String getFolderPath() { return folderPath; }

    public boolean isFolder() { return type == Type.FOLDER; }

    public String getFileExtension() { return fileExtension; }

    public String getMimeType() { return mimeType; }

    public String getFormattedSize() {
        if (fileSize <= 0) return type == Type.FOLDER ? "Folder" : "Unknown size";
        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format("%.1f KB", fileSize / 1024.0);
        if (fileSize < 1024L * 1024 * 1024) return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
        return String.format("%.2f GB", fileSize / (1024.0 * 1024.0 * 1024.0));
    }

    // --- Type checks ---

    public boolean isImage() { return mimeType.startsWith("image/"); }

    public boolean isVideo() { return mimeType.startsWith("video/"); }

    public boolean isAudio() { return mimeType.startsWith("audio/"); }

    public boolean isPdf() { return "pdf".equals(fileExtension); }

    public boolean isText() {
        if (mimeType.startsWith("text/")) return true;
        // Code and config files that may have non-text MIME types
        switch (fileExtension) {
            case "json": case "js": case "ts": case "java": case "py":
            case "c": case "cpp": case "cs": case "go": case "rs":
            case "rb": case "php": case "sh": case "bat": case "yaml":
            case "yml": case "xml": case "html": case "htm": case "css":
            case "md": case "log": case "ini": case "conf": case "properties":
            case "sql": case "gradle": case "kt": case "swift":
                return true;
            default:
                return false;
        }
    }

    public boolean isPreviewable() {
        return isImage() || isVideo() || isAudio() || isPdf() || isText() || isFolder();
    }

    public boolean isDownloadable() {
        return type == Type.FILE;
    }

    // --- equals & hashCode ---
    // Both methods use the SAME fields to maintain the Java contract:
    // if a.equals(b) is true, then a.hashCode() == b.hashCode().

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileItem)) return false;
        FileItem that = (FileItem) o;
        // Use fileUri as the primary identity — it's the most stable reference
        // (content:// URIs are unique per file, and file:// URIs are unique per path)
        if (!fileUri.isEmpty() && !that.fileUri.isEmpty()) {
            return fileUri.equals(that.fileUri);
        }
        // Fallback: filePath + type comparison
        if (!filePath.isEmpty() && !that.filePath.isEmpty()) {
            return filePath.equals(that.filePath) && type == that.type;
        }
        // Last resort: same name + same size + same type
        return fileName.equals(that.fileName) && fileSize == that.fileSize && type == that.type;
    }

    @Override
    public int hashCode() {
        // Consistent with equals: primary key is fileUri, then filePath+type
        if (!fileUri.isEmpty()) {
            return fileUri.hashCode();
        }
        if (!filePath.isEmpty()) {
            return 31 * filePath.hashCode() + type.hashCode();
        }
        return 31 * (31 * fileName.hashCode() + Long.hashCode(fileSize)) + type.hashCode();
    }

    @NonNull
    @Override
    public String toString() {
        return "FileItem{" + fileName + ", " + getFormattedSize() + ", " + type + "}";
    }

    // --- Parcelable ---

    protected FileItem(Parcel in) {
        fileName = in.readString();
        filePath = in.readString();
        fileSize = in.readLong();
        fileUri = in.readString();
        type = Type.valueOf(in.readString());
        folderPath = in.readString();
        fileExtension = extractExtension(fileName);
        mimeType = computeMimeType(fileExtension);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(fileName);
        dest.writeString(filePath);
        dest.writeLong(fileSize);
        dest.writeString(fileUri);
        dest.writeString(type.name());
        dest.writeString(folderPath);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<FileItem> CREATOR = new Creator<FileItem>() {
        @Override
        public FileItem createFromParcel(Parcel in) { return new FileItem(in); }
        @Override
        public FileItem[] newArray(int size) { return new FileItem[size]; }
    };

    // --- Private helpers ---

    private static String extractExtension(String fileName) {
        if (fileName == null) return "";
        int lastDot = fileName.lastIndexOf(".");
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    private static String computeMimeType(String ext) {
        if (ext == null || ext.isEmpty()) return "application/octet-stream";
        switch (ext) {
            // Images
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            // Video
            case "mp4": return "video/mp4";
            case "avi": return "video/x-msvideo";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            case "3gp": return "video/3gpp";
            case "webm": return "video/webm";
            // Audio
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            case "flac": return "audio/flac";
            case "aac": return "audio/aac";
            case "m4a": return "audio/mp4";
            // Documents
            case "pdf": return "application/pdf";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            // Text & Code
            case "txt": return "text/plain";
            case "csv": return "text/csv";
            case "html": case "htm": return "text/html";
            case "css": return "text/css";
            case "js": return "text/javascript";
            case "json": return "application/json";
            case "xml": return "text/xml";
            case "md": return "text/markdown";
            case "yaml": case "yml": return "text/yaml";
            case "java": return "text/x-java-source";
            case "py": return "text/x-python";
            case "c": return "text/x-c";
            case "cpp": return "text/x-c++src";
            case "sh": return "text/x-shellscript";
            case "sql": return "text/x-sql";
            case "log": return "text/plain";
            // Archives
            case "zip": return "application/zip";
            case "rar": return "application/vnd.rar";
            case "7z": return "application/x-7z-compressed";
            case "tar": return "application/x-tar";
            case "gz": return "application/gzip";
            // Android
            case "apk": return "application/vnd.android.package-archive";
            default: return "application/octet-stream";
        }
    }
}
