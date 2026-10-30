package dev.sherry.wcs.features.items.contacts.hidecontacts

import androidx.annotation.StringRes
import dev.sherry.wcs.R
import dev.sherry.wcs.features.api.core.WeConversationApi
import dev.sherry.wcs.features.items.contacts.HideContacts
import dev.sherry.wcs.preferences.WePrefs

/**
 * The individual surfaces 隐藏联系人 can hide a contact from.
 *
 * Each entry is one switch in the feature's settings dialog and one condition every hook asks before
 * it acts. They are plain [WePrefs] booleans rather than [dev.sherry.wcs.features.core.SwitchFeature]
 * objects on purpose: all surfaces share the same handful of hooks, so splitting them into separate
 * features would install the same hook many times and force a WeChat restart before a flip took
 * effect. With a pref read inside each hook body, toggling any of them applies to the very next list
 * render — in the main process and, for [NOTIFICATIONS], in `:push` as well.
 *
 * Every surface ships **off**: hiding a contact does not make anything disappear until the user
 * turns on the specific places they want it to apply. Turning a surface on is a per-place opt-in,
 * and the settings dialog shows the real state because it reads [surfaceOn].
 */
enum class HideSurface(
    val prefKey: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val defaultOn: Boolean,
) {
    CONVERSATIONS(
        "hide_surface_conversations",
        R.string.contacts_hide_surface_conversations,
        R.string.contacts_hide_surface_conversations_desc,
        false,
    ),
    CONTACTS(
        "hide_surface_contacts",
        R.string.contacts_hide_surface_contacts,
        R.string.contacts_hide_surface_contacts_desc,
        false,
    ),
    CONTACT_LABEL(
        "hide_surface_contact_label",
        R.string.contacts_hide_surface_contact_label,
        R.string.contacts_hide_surface_contact_label_desc,
        false,
    ),
    NEW_FRIENDS(
        "hide_surface_new_friends",
        R.string.contacts_hide_surface_new_friends,
        R.string.contacts_hide_surface_new_friends_desc,
        false,
    ),
    UNREAD(
        "hide_surface_unread",
        R.string.contacts_hide_surface_unread,
        R.string.contacts_hide_surface_unread_desc,
        false,
    ),
    SEARCH(
        "hide_surface_search",
        R.string.contacts_hide_surface_search,
        R.string.contacts_hide_surface_search_desc,
        false,
    ),
    GROUP_MEMBERS(
        "hide_surface_group_members",
        R.string.contacts_hide_surface_group_members,
        R.string.contacts_hide_surface_group_members_desc,
        false,
    ),
    RECENT_FORWARD(
        "hide_surface_recent_forward",
        R.string.contacts_hide_surface_recent_forward,
        R.string.contacts_hide_surface_recent_forward_desc,
        false,
    ),
    STORAGE_MANAGE(
        "hide_surface_storage_manage",
        R.string.contacts_hide_surface_storage_manage,
        R.string.contacts_hide_surface_storage_manage_desc,
        false,
    ),
    STATUS(
        "hide_surface_status",
        R.string.contacts_hide_surface_status,
        R.string.contacts_hide_surface_status_desc,
        false,
    ),
    FAVORITES(
        "hide_surface_favorites",
        R.string.contacts_hide_surface_favorites,
        R.string.contacts_hide_surface_favorites_desc,
        false,
    ),
    FINDER_LIKE(
        "hide_surface_finder_like",
        R.string.contacts_hide_surface_finder_like,
        R.string.contacts_hide_surface_finder_like_desc,
        false,
    ),
    EXDEVICE(
        "hide_surface_exdevice",
        R.string.contacts_hide_surface_exdevice,
        R.string.contacts_hide_surface_exdevice_desc,
        false,
    ),
    MOMENTS(
        "hide_surface_moments",
        R.string.contacts_hide_surface_moments,
        R.string.contacts_hide_surface_moments_desc,
        false,
    ),
    MOMENTS_INLINE(
        "hide_surface_moments_inline",
        R.string.contacts_hide_surface_moments_inline,
        R.string.contacts_hide_surface_moments_inline_desc,
        false,
    ),
    GROUP_ICON(
        "hide_surface_group_icon",
        R.string.contacts_hide_surface_group_icon,
        R.string.contacts_hide_surface_group_icon_desc,
        false,
    ),
    DISCOVER(
        "hide_surface_discover",
        R.string.contacts_hide_surface_discover,
        R.string.contacts_hide_surface_discover_desc,
        false,
    ),
    VOIP(
        "hide_surface_voip",
        R.string.contacts_hide_surface_voip,
        R.string.contacts_hide_surface_voip_desc,
        false,
    ),
    MUTE(
        "hide_surface_mute",
        R.string.contacts_hide_surface_mute,
        R.string.contacts_hide_surface_mute_desc,
        false,
    ),
    NOTIFICATIONS(
        "hide_surface_notifications",
        R.string.contacts_hide_surface_notifications,
        R.string.contacts_hide_surface_notifications_desc,
        false,
    ),
    PAT(
        "hide_surface_pat",
        R.string.contacts_hide_surface_pat,
        R.string.contacts_hide_surface_pat_desc,
        false,
    ),
    TEAM_SUMMARY(
        "hide_surface_team_summary",
        R.string.contacts_hide_surface_team_summary,
        R.string.contacts_hide_surface_team_summary_desc,
        false,
    ),
    BLOCK_CHAT(
        "hide_surface_block_chat",
        R.string.contacts_hide_surface_block_chat,
        R.string.contacts_hide_surface_block_chat_desc,
        false,
    ),
    BLOCK_PROFILE(
        "hide_surface_block_profile",
        R.string.contacts_hide_surface_block_profile,
        R.string.contacts_hide_surface_block_profile_desc,
        false,
    ),
    PROFILE_STRANGER(
        "hide_surface_profile_stranger",
        R.string.contacts_hide_surface_profile_stranger,
        R.string.contacts_hide_surface_profile_stranger_desc,
        false,
    ),
    BLOCK_FRIEND_REQUEST(
        "hide_surface_block_friend_request",
        R.string.contacts_hide_surface_block_friend_request,
        R.string.contacts_hide_surface_block_friend_request_desc,
        false,
    ),
}

/**
 * Whether [surface] currently hides anything.
 *
 * Read from [WePrefs] rather than cached in memory because [HideContactsNotifications] runs in
 * `:push`, where [HideContacts]'s runtime state does not exist. MMKV is opened in
 * `MULTI_PROCESS_MODE`, so both processes see the same value.
 */
fun HideContacts.surfaceOn(surface: HideSurface): Boolean =
    WePrefs.getBoolOrDef(surface.prefKey, surface.defaultOn)

/** Persists a surface switch and re-runs the list queries so the change is visible immediately. */
fun HideContacts.setSurfaceOn(surface: HideSurface, enabled: Boolean) {
    WePrefs.putBool(surface.prefKey, enabled)
    WeConversationApi.reloadConversations()
}
