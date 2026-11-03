@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package eu.kanade.tachiyomi.novel

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Novel cover image with a warm gradient visible when the source has no usable cover. */
@Composable
internal fun NovelCover(
    url: String?,
    size: DpSize,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(NovelDimensions.coverCornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(NovelColors.coverFallback)
            .semantics(mergeDescendants = true) {
                contentDescription?.let { this.contentDescription = listOf(it) }
            },
    ) {
        url?.takeIf(String::isNotBlank)?.let { imageUrl ->
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Circular icon-action button with a touch target larger than the 44 dp minimum. */
@Composable
internal fun RoundIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .sizeIn(minWidth = NovelDimensions.touchTarget, minHeight = NovelDimensions.touchTarget)
            .size(48.dp)
            .semantics { this.contentDescription = listOf(contentDescription) },
    ) {
        icon()
    }
}

/** Pill-shaped single choice used for tabs and compact filters. */
@Composable
internal fun ChipTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (selected) NovelColors.accent else Color.Transparent
    val contentColor = if (selected) Color.White else NovelColors.textSecondary
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = NovelDimensions.touchTarget)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = NovelDimensions.spacingLarge, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

internal data class NovelSegment(
    val key: String,
    val label: String,
)

/** Equal-width, pill-shaped selector for a small set of mutually exclusive options. */
@Composable
internal fun SegmentedControl(
    options: List<NovelSegment>,
    selectedKey: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(NovelColors.divider.copy(alpha = 0.55f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val selected = option.key == selectedKey
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = NovelDimensions.touchTarget)
                    .clip(CircleShape)
                    .background(if (selected) NovelColors.surface else Color.Transparent)
                    .clickable { onSelected(option.key) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    color = if (selected) NovelColors.accent else NovelColors.textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Main call-to-action button that can show an in-place progress indicator. */
@Composable
internal fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = NovelColors.accent,
            contentColor = Color.White,
            disabledContainerColor = NovelColors.accent.copy(alpha = 0.48f),
            disabledContentColor = Color.White,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Standard bottom sheet surface used for novel source and reader controls. */
@Composable
internal fun NovelBottomSheet(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(
            topStart = NovelDimensions.bottomSheetTopRadius,
            topEnd = NovelDimensions.bottomSheetTopRadius,
        ),
        containerColor = NovelColors.surface,
        contentColor = NovelColors.textPrimary,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = NovelColors.textSecondary.copy(alpha = 0.45f))
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = NovelDimensions.screenHorizontal,
                    vertical = NovelDimensions.spacingMedium,
                ),
            content = content,
        )
    }
}

/** Animated placeholder surface shared by loading states in the novels flow. */
@Composable
internal fun NovelSkeleton(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(NovelDimensions.spacingSmall),
) {
    val transition = rememberInfiniteTransition(label = "novelSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "novelSkeletonAlpha",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(NovelColors.skeleton.copy(alpha = alpha)),
    )
}
