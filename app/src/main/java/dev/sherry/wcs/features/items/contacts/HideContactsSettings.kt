package dev.sherry.wcs.features.items.contacts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.items.contacts.hidecontacts.HideSurface
import dev.sherry.wcs.features.items.contacts.hidecontacts.setSurfaceOn
import dev.sherry.wcs.features.items.contacts.hidecontacts.showSchedulesDialog
import dev.sherry.wcs.features.items.contacts.hidecontacts.surfaceOn
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.ContactsSelector
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.BaseItemContainer
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.IntNumberPickerWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.android.showToast

private val MESSAGE_SURFACES = listOf(
    HideSurface.CONVERSATIONS,
    HideSurface.UNREAD,
    HideSurface.MUTE,
    HideSurface.NOTIFICATIONS,
    HideSurface.TEAM_SUMMARY,
    HideSurface.PAT,
)

private val CONTACT_SURFACES = listOf(
    HideSurface.CONTACTS,
    HideSurface.CONTACT_LABEL,
    HideSurface.NEW_FRIENDS,
    HideSurface.GROUP_MEMBERS,
    HideSurface.SEARCH,
)

private val MOMENT_SURFACES = listOf(
    HideSurface.MOMENTS,
    HideSurface.MOMENTS_INLINE,
    HideSurface.GROUP_ICON,
    HideSurface.DISCOVER,
    HideSurface.FINDER_LIKE,
)

private val ENTRY_SURFACES = listOf(
    HideSurface.FAVORITES,
    HideSurface.EXDEVICE,
    HideSurface.RECENT_FORWARD,
    HideSurface.STORAGE_MANAGE,
    HideSurface.STATUS,
)

private val ACCESS_SURFACES = listOf(
    HideSurface.BLOCK_CHAT,
    HideSurface.BLOCK_PROFILE,
    HideSurface.PROFILE_STRANGER,
    HideSurface.BLOCK_FRIEND_REQUEST,
    HideSurface.PROFILE_MOMENTS_ENTRY,
)

/**
 * The 隐藏联系人 settings dialog.
 *
 * Every [HideSurface] gets its own switch, because the hooks behind them are shared: a single
 * "隐藏联系人" switch used to mean the contact vanished from *all* of WeChat at once, and there was no
 * way to keep, say, the conversations list filtered while leaving the address book intact. The
 * switches write straight through to MMKV and each hook re-reads its own switch on every invocation,
 * so a flip applies to the next list render — no restart, and no dialog-level draft state except for
 * the two gesture thresholds, whose sliders commit on drag (they are values, not a form).
 *
 * Grouped by where the surface lives in WeChat rather than by internal hook, so the list can be
 * scanned from the user's side: 会话 / 通讯录 / 朋友圈 / 其他入口 / 访问控制.
 */
fun HideContacts.showSettingsDialog(context: ComponentActivity) {
    // 隐藏名单只收私聊好友：本功能针对「某个人看不见」，群聊不在这个语义里（其它功能仍用
    // getFriends() + getGroups() 的常规名单，互不影响）。
    val regularContacts = WeDatabaseApi.getFriends()

    showComposeDialog(context) {
        AlertDialogContent(
            title = { Text(stringResource(R.string.feature_hide_contacts_name)) },
            text = {
                var surfaces by remember { mutableStateOf(HideSurface.entries.associateWith { surfaceOn(it) }) }
                var autoRejectInput by remember { mutableStateOf(autoRejectVoip) }
                var hideOnLeaveInput by remember { mutableStateOf(hideOnLeave) }
                var hideOnLockInput by remember { mutableStateOf(hideOnLock) }
                var gestureLongPressInput by remember { mutableStateOf(gestureLongPressEnabled) }
                var gestureTripleTapInput by remember { mutableStateOf(gestureTripleTapEnabled) }
                var longPressMsInput by remember { mutableIntStateOf(longPressMs) }
                var tripleTapMsInput by remember { mutableIntStateOf(tripleTapMs) }
                var awaySettleMsInput by remember { mutableIntStateOf(awaySettleMs) }
                // 四条文本行的显示值必须走局部状态：`by prefOption` 委托不是 Compose State，直接在组合里
                // 读它，写完也不会触发重组 —— 行上会一直挂着改之前的旧值（三个时长条从一开始就是这么写的）。
                var showCommandInput by remember { mutableStateOf(showCommand) }
                var hideCommandInput by remember { mutableStateOf(hideCommand) }
                var showHintInput by remember { mutableStateOf(showHintText) }
                var hideHintInput by remember { mutableStateOf(hideHintText) }

                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    SegmentedColumn(
                        title = stringResource(R.string.contacts_hide_group_general),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        item(key = "hidden-list") {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.contacts_hide_configure),
                                description = stringResource(R.string.contacts_hide_configure_description),
                                onClick = {
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = context.localizedContactsString(R.string.contacts_hide_select),
                                            contacts = regularContacts,
                                            initialSelectedWxIds = hiddenContacts,
                                            onDismiss = onDismiss
                                        ) {
                                            showToast(
                                                localizedContactsQuantity(
                                                    R.plurals.contacts_hide_saved,
                                                    it.size,
                                                    it.size,
                                                ),
                                            )
                                            hiddenContacts = it
                                            onDismiss()
                                        }
                                    }
                                },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        item(key = "schedule") {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.contacts_hide_schedule),
                                description = stringResource(R.string.contacts_hide_schedule_description),
                                onClick = { showSchedulesDialog(context) },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }

                    SegmentedColumn(
                        title = stringResource(R.string.contacts_hide_group_timing),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        item(key = "hide-on-leave") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.contacts_hide_on_leave),
                                description = stringResource(R.string.contacts_hide_on_leave_description),
                                checked = hideOnLeaveInput,
                                onCheckedChange = {
                                    hideOnLeaveInput = it
                                    hideOnLeave = it
                                },
                            )
                        }
                        // 时长条跟着「离开微信隐藏」显隐，和两个手势时长条同一个规矩。
                        if (hideOnLeaveInput) {
                            item(key = "away-settle-ms") {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.contacts_hide_away_settle_ms),
                                        description = stringResource(R.string.contacts_hide_away_settle_ms_description),
                                        value = awaySettleMsInput,
                                        startInt = 150,
                                        endInt = 1000,
                                        stepSize = 50,
                                        valueSuffix = "ms",
                                        onValueChange = {
                                            awaySettleMsInput = it
                                            awaySettleMs = it
                                        },
                                    )
                                }
                            }
                        }
                        item(key = "hide-on-lock") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.contacts_hide_on_lock),
                                description = stringResource(R.string.contacts_hide_on_lock_description),
                                checked = hideOnLockInput,
                                onCheckedChange = {
                                    hideOnLockInput = it
                                    hideOnLock = it
                                },
                            )
                        }
                        item(key = "gesture-long-press") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.contacts_hide_gesture_long_press),
                                description = stringResource(R.string.contacts_hide_gesture_long_press_description),
                                checked = gestureLongPressInput,
                                onCheckedChange = {
                                    gestureLongPressInput = it
                                    gestureLongPressEnabled = it
                                },
                            )
                        }
                        // 时长条跟着各自的手势开关显隐（用户定：关闭开关 = 整行不显示，不是置灰）。
                        if (gestureLongPressInput) {
                            item(key = "long-press-ms") {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.contacts_hide_long_press_ms),
                                        description = stringResource(R.string.contacts_hide_long_press_ms_description),
                                        value = longPressMsInput,
                                        startInt = 200,
                                        endInt = 2000,
                                        stepSize = 50,
                                        valueSuffix = "ms",
                                        onValueChange = {
                                            longPressMsInput = it
                                            longPressMs = it
                                        },
                                    )
                                }
                            }
                        }
                        item(key = "gesture-triple-tap") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.contacts_hide_gesture_triple_tap),
                                description = stringResource(R.string.contacts_hide_gesture_triple_tap_description),
                                checked = gestureTripleTapInput,
                                onCheckedChange = {
                                    gestureTripleTapInput = it
                                    gestureTripleTapEnabled = it
                                },
                            )
                        }
                        if (gestureTripleTapInput) {
                            item(key = "triple-tap-ms") {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.contacts_hide_triple_click_ms),
                                        description = stringResource(R.string.contacts_hide_triple_click_ms_description),
                                        value = tripleTapMsInput,
                                        startInt = 200,
                                        endInt = 1500,
                                        stepSize = 50,
                                        valueSuffix = "ms",
                                        onValueChange = {
                                            tripleTapMsInput = it
                                            tripleTapMs = it
                                        },
                                    )
                                }
                            }
                        }
                        item(key = "show-command") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.contacts_hide_show_command),
                                value = showCommandInput,
                                onValueChange = {
                                    val next = it.trim().ifEmpty { DEFAULT_SHOW_COMMAND }
                                    showCommandInput = next
                                    showCommand = next
                                },
                                dialogTitle = stringResource(R.string.contacts_hide_show_command),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.contacts_hide_show_command_value_hint),
                            )
                        }
                        item(key = "hide-command") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.contacts_hide_hide_command),
                                value = hideCommandInput,
                                onValueChange = {
                                    val next = it.trim().ifEmpty { DEFAULT_HIDE_COMMAND }
                                    hideCommandInput = next
                                    hideCommand = next
                                },
                                dialogTitle = stringResource(R.string.contacts_hide_hide_command),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.contacts_hide_hide_command_value_hint),
                            )
                        }
                        item(key = "show-hint-text") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.contacts_hide_show_hint),
                                value = showHintInput,
                                onValueChange = {
                                    val next = it.trim()
                                    showHintInput = next
                                    showHintText = next
                                },
                                dialogTitle = stringResource(R.string.contacts_hide_show_hint),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.contacts_hide_show_hint_value_hint),
                            )
                        }
                        item(key = "hide-hint-text") {
                            TextFieldDialogWidget(
                                title = stringResource(R.string.contacts_hide_hide_hint),
                                value = hideHintInput,
                                onValueChange = {
                                    val next = it.trim()
                                    hideHintInput = next
                                    hideHintText = next
                                },
                                dialogTitle = stringResource(R.string.contacts_hide_hide_hint),
                                confirmLabel = stringResource(R.string.dialog_confirm),
                                dismissLabel = stringResource(R.string.dialog_cancel),
                                valueHint = stringResource(R.string.contacts_hide_hide_hint_value_hint),
                            )
                        }
                    }

                    SurfaceGroup(
                        stringResource(R.string.contacts_hide_group_messages),
                        MESSAGE_SURFACES,
                        surfaces,
                    ) { surface, enabled ->
                        surfaces = surfaces + (surface to enabled)
                        setSurfaceOn(surface, enabled)
                    }

                    SurfaceGroup(
                        stringResource(R.string.contacts_hide_group_contacts),
                        CONTACT_SURFACES,
                        surfaces,
                    ) { surface, enabled ->
                        surfaces = surfaces + (surface to enabled)
                        setSurfaceOn(surface, enabled)
                    }

                    SurfaceGroup(
                        stringResource(R.string.contacts_hide_group_moments),
                        MOMENT_SURFACES,
                        surfaces,
                    ) { surface, enabled ->
                        surfaces = surfaces + (surface to enabled)
                        setSurfaceOn(surface, enabled)
                    }

                    SurfaceGroup(
                        stringResource(R.string.contacts_hide_group_entries),
                        ENTRY_SURFACES,
                        surfaces,
                    ) { surface, enabled ->
                        surfaces = surfaces + (surface to enabled)
                        setSurfaceOn(surface, enabled)
                    }

                    // 通话 is a custom group rather than a SurfaceGroup: 自动拒绝 is a plain pref
                    // (no HideSurface), but the two switches belong together because neither does
                    // anything without the other.
                    SegmentedColumn(
                        title = stringResource(R.string.contacts_hide_group_calls),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        item(key = HideSurface.VOIP.name) {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(HideSurface.VOIP.titleRes),
                                description = stringResource(HideSurface.VOIP.descRes),
                                checked = surfaces.getValue(HideSurface.VOIP),
                                onCheckedChange = {
                                    surfaces = surfaces + (HideSurface.VOIP to it)
                                    setSurfaceOn(HideSurface.VOIP, it)
                                },
                            )
                        }
                        item(key = "auto-reject") {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(R.string.contacts_hide_auto_reject),
                                description = stringResource(R.string.contacts_hide_auto_reject_description),
                                checked = autoRejectInput,
                                onCheckedChange = {
                                    autoRejectInput = it
                                    autoRejectVoip = it
                                },
                            )
                        }
                    }

                    SurfaceGroup(
                        stringResource(R.string.contacts_hide_group_access),
                        ACCESS_SURFACES,
                        surfaces,
                    ) { surface, enabled ->
                        surfaces = surfaces + (surface to enabled)
                        setSurfaceOn(surface, enabled)
                    }
                }
            },
            dismissButton = {
                TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
            },
        )
    }
}

@Composable
private fun SurfaceGroup(
    title: String,
    surfaces: List<HideSurface>,
    checked: Map<HideSurface, Boolean>,
    onToggle: (HideSurface, Boolean) -> Unit,
) {
    SegmentedColumn(title = title, contentPadding = PaddingValues(0.dp)) {
        surfaces.forEach { surface ->
            item(key = surface.name) {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(surface.titleRes),
                    description = stringResource(surface.descRes),
                    checked = checked.getValue(surface),
                    onCheckedChange = { onToggle(surface, it) },
                )
            }
        }
    }
}
