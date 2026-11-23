package com.unlimitedcloud.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unlimitedcloud.ui.theme.AccentArchive
import com.unlimitedcloud.ui.theme.AccentAudio
import com.unlimitedcloud.ui.theme.AccentCode
import com.unlimitedcloud.ui.theme.AccentDocument
import com.unlimitedcloud.ui.theme.AccentOther
import com.unlimitedcloud.ui.theme.AccentPhoto
import com.unlimitedcloud.ui.theme.AccentVideo

/** Visual category derived from a file extension, used for icons and accent colours. */
enum class CloudFileKind(
    val label: String,
    val icon: ImageVector,
    val color: Color,
) {
    Photo("Gambar", AppIcons.Photo, AccentPhoto),
    Video("Video", AppIcons.Video, AccentVideo),
    Audio("Audio", AppIcons.Music, AccentAudio),
    Archive("Arsip", AppIcons.Archive, AccentArchive),
    Code("Kode", AppIcons.Code, AccentCode),
    Document("Dokumen", AppIcons.Document, AccentDocument),
    Other("Berkas", AppIcons.File, AccentOther),
}

fun cloudFileKind(fileName: String): CloudFileKind {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg", "avif" -> CloudFileKind.Photo
        "mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v" -> CloudFileKind.Video
        "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus" -> CloudFileKind.Audio
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso" -> CloudFileKind.Archive
        "kt", "kts", "java", "py", "js", "ts", "json", "xml", "html", "css",
        "sh", "c", "cpp", "h", "go", "rs", "rb", "php", "sql", "yaml", "yml",
        -> CloudFileKind.Code

        "pdf", "doc", "docx", "txt", "md", "ppt", "pptx", "xls", "xlsx", "csv", "rtf",
        -> CloudFileKind.Document

        else -> CloudFileKind.Other
    }
}

/** Best-effort MIME type for the share sheet, derived from the file name. */
fun mimeTypeFor(fileName: String): String {
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "avif" -> "image/avif"
        "svg" -> "image/svg+xml"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "tar", "gz" -> "application/gzip"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}

@Composable
fun FileKindIcon(
    kind: CloudFileKind,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                color = kind.color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = kind.icon,
            contentDescription = kind.label,
            tint = kind.color,
            modifier = Modifier.size(size * 0.48f),
        )
    }
}
