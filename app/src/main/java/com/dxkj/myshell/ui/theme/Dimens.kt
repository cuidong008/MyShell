package com.dxkj.myshell.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 统一常用间距尺度，避免各页面随手写 dp 导致“松紧不一”。
 */
object Dimens {
    val ScreenPaddingH = 16.dp
    val ScreenPaddingV = 16.dp
    val CardPadding = 16.dp

    val SpacingXs = 6.dp
    val Spacing2 = 4.dp
    val Spacing1 = 2.dp
    val SpacingSm = 8.dp
    val SpacingMd = 12.dp
    val SpacingLg = 16.dp
    val SpacingXl = 24.dp

    val SidebarPadding = 10.dp

    // 终端浮层/工具条（Hub/Full 两页保持一致）
    val OverlayCorner = 14.dp
    val OverlayCornerSm = 12.dp
    val OverlayPaddingH = 12.dp
    val OverlayPaddingV = 8.dp
    val OverlayChipPaddingH = 10.dp
    val OverlayChipPaddingV = 6.dp
    val OverlayGap = 6.dp

    // Haven 风格两行工具条高度（用于底部预留空间与浮层避让）
    val TerminalKeyBarHeight = 72.dp
}

