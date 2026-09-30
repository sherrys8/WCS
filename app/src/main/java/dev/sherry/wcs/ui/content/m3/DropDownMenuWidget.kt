// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2023-2026 iamr0s, InstallerX Revived contributors
package dev.sherry.wcs.ui.content.m3

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

data class DropdownOption<T>(val value: T, val label: String)

/** M3 菜单行的固定高度，用于把弹窗限制在若干行内并开启滚动。 */
private const val MENU_ITEM_HEIGHT_DP = 48

@Composable
fun <T> ExpressiveOptionDropdown(
    expanded: Boolean,
    value: T,
    options: List<DropdownOption<T>>,
    maxVisibleItems: Int = 6,
    dismissOnClickOutside: Boolean = true,
    onDismissRequest: () -> Unit,
    onValueChange: (T) -> Unit,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        // Popup 窗口只要收到落在自身边界外的 ACTION_DOWN 就默认 onDismissRequest
        // （AndroidPopup.onTouchEvent），所以手指按在卡片边缘外一点点、或想滑动长列表时，卡片会当场消失。
        // 关掉这个开关后，卡片只由「选中一项」「再点一次宿主行」和返回键关闭。
        properties = PopupProperties(focusable = true, dismissOnClickOutside = dismissOnClickOutside),
    ) {
        Column(
            Modifier
                .heightIn(max = (MENU_ITEM_HEIGHT_DP * maxVisibleItems).dp)
                .verticalScroll(rememberScrollState()),
        ) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                options.forEachIndexed { index, option ->
                    SelectableDropdownMenuItem(
                        selected = option.value == value,
                        onClick = { onValueChange(option.value) },
                        text = { Text(option.label) },
                        shapes = MenuDefaults.itemShape(index, options.size),
                    )
                }
            }
        }
    }
}

@Composable
fun <T> DropDownMenuWidget(
    icon: ImageVector? = null,
    iconPlaceholder: Boolean = false,
    title: String,
    description: String?,
    value: T,
    options: List<DropdownOption<T>>,
    enabled: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    onValueChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pressPosition by remember { mutableStateOf(Offset.Zero) }
    val selected = options.first { it.value == value }

    // DropdownMenuPopup anchors to its parent layout, so place a zero-size anchor
    // at the press position instead of a fixed spot inside the row.
    Box(
        modifier = Modifier.pointerInput(enabled) {
            awaitEachGesture {
                pressPosition = awaitFirstDown(requireUnconsumed = false).position
            }
        }
    ) {
        BaseWidget(
            icon = icon,
            iconPlaceholder = iconPlaceholder,
            title = title,
            description = description ?: selected.label,
            enabled = enabled,
            onClick = if (enabled) ({ expanded = !expanded }) else null,
        )
        Box(
            Modifier.offset {
                IntOffset(pressPosition.x.roundToInt(), pressPosition.y.roundToInt())
            }
        ) {
            ExpressiveOptionDropdown(
                expanded = expanded,
                value = value,
                options = options,
                dismissOnClickOutside = dismissOnClickOutside,
                onDismissRequest = { expanded = false },
                onValueChange = {
                    onValueChange(it)
                    expanded = false
                },
            )
        }
    }
}
