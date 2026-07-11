package com.splannes.fileshares;

/**
 * Single source of truth for file type icons.
 * Used by FileListAdapter, FileServer, and FileServerService.
 */
public class FileIconHelper {

    public static String getIcon(String extension) {
        if (extension == null || extension.isEmpty()) return "📁";
        switch (extension.toLowerCase()) {
            case "jpg": case "jpeg": case "png": case "gif": case "bmp": case "webp": case "svg": return "🖼️";
            case "mp4": case "avi": case "mkv": case "mov": case "3gp": case "webm": return "🎥";
            case "mp3": case "wav": case "ogg": case "flac": case "aac": case "m4a": return "🎵";
            case "pdf": return "📄";
            case "doc": case "docx": return "📝";
            case "xls": case "xlsx": return "📊";
            case "ppt": case "pptx": return "📽️";
            case "txt": case "csv": case "log": return "📃";
            case "xml": case "json": case "html": case "htm": case "css": case "js":
            case "java": case "py": case "c": case "cpp": case "sh": case "md":
            case "yaml": case "yml": case "sql": case "gradle": case "kt": return "💻";
            case "zip": case "rar": case "7z": case "tar": case "gz": return "📦";
            case "apk": return "📱";
            default: return "📁";
        }
    }

    public static String getFolderIcon() { return "📂"; }
}
