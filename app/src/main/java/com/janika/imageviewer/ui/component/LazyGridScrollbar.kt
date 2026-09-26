package com.janika.imageviewer.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 在网格右侧绘制随列表位置变化的滚动条。 */
@Composable
fun LazyGridScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    color: Color
) {
    val scrollbarInfo by remember(state) {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val totalItems = layoutInfo.totalItemsCount
            if (visibleItems.isEmpty() || totalItems <= visibleItems.size ||
                (!state.canScrollBackward && !state.canScrollForward)
            ) {
                null
            } else {
                val firstIndex = visibleItems.first().index
                val lastIndex = visibleItems.last().index
                val visibleCount = (lastIndex - firstIndex + 1).coerceAtLeast(1)
                val thumbFraction = (visibleCount.toFloat() / totalItems)
                    .coerceIn(0.08f, 1f)
                val scrollableItems = (totalItems - visibleCount).coerceAtLeast(1)
                val scrollFraction = (firstIndex.toFloat() / scrollableItems)
                    .coerceIn(0f, 1f)
                thumbFraction to scrollFraction
            }
        }
    }

    scrollbarInfo?.let { (thumbFraction, scrollFraction) ->
        Canvas(modifier = modifier) {
            val thumbHeight = size.height * thumbFraction
            val thumbTop = (size.height - thumbHeight) * scrollFraction
            val radius = 2.dp.toPx()
            drawRoundRect(
                color = color.copy(alpha = 0.18f),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(radius, radius)
            )
            drawRoundRect(
                color = color.copy(alpha = 0.65f),
                topLeft = Offset(0f, thumbTop),
                size = Size(size.width, thumbHeight),
                cornerRadius = CornerRadius(radius, radius)
            )
        }
    }
}
