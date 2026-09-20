package com.janika.imageviewer.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 显示文件夹直属层级中可浏览的文件与文件夹数量。 */
@Composable
fun FolderContentCount(
    fileCount: Int?,
    directoryCount: Int?,
    modifier: Modifier = Modifier
) {
    if (fileCount == null || directoryCount == null) return
    val countText = buildList {
        if (fileCount > 0) add("文件 $fileCount")
        if (directoryCount > 0) add("文件夹 $directoryCount")
    }.joinToString(" · ")
    if (countText.isEmpty()) return

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text = countText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}
