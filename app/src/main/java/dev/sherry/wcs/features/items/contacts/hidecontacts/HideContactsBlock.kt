package dev.sherry.wcs.features.items.contacts.hidecontacts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.ujhhgtg.reflekt.reflekt
import dev.sherry.wcs.R
import dev.sherry.wcs.constants.PackageNames
import dev.sherry.wcs.features.api.ui.WeStartActivityApi
import dev.sherry.wcs.features.items.contacts.HideContacts
import dev.sherry.wcs.features.items.contacts.localizedContactsString
import dev.sherry.wcs.utils.HookParam
import dev.sherry.wcs.utils.HostInfo
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.runOnUiThread
import dev.sherry.wcs.utils.android.showToast

private const val TAG = "HideContactsBlock"

private const val CHATTING_UI = "${PackageNames.WECHAT}.ui.chatting.ChattingUI"
private const val CONTACT_INFO_UI = "${PackageNames.WECHAT}.plugin.profile.ui.ContactInfoUI"

/**
 * 禁止进入聊天 / 禁止查看资料.
 *
 * Two independent gates, switched by [HideSurface.BLOCK_CHAT] and [HideSurface.BLOCK_PROFILE]:
 * a hidden contact's chat cannot be opened, and its profile page closes as soon as it appears.
 * The decision is made per entry, against the hidden set and [HideContacts.isHiddenNow], so 临时显示
 * lifts the block exactly like it lifts every other surface.
 *
 * Only the chat gate says anything. 「该联系人已隐藏」 is itself a tell — a toast that names the block
 * defeats the point of hiding the contact — and a profile page that simply does not open reads the
 * same as one the user never tapped.
 *
 * Blocked in two places, because neither alone covers every entry:
 *
 * - [WeStartActivityApi] sees every `startActivity`/`startActivityForResult` the host makes, so the
 *   chat/profile Activity is never created in the first place: no flicker, no data loaded. This is
 *   the path hit by tapping a search result, a Moments avatar, a forwarded card, etc.
 * - `Activity.onCreate` covers launches that never pass through the host's own `startActivity` —
 *   most notably the notification tray, which starts the Activity from the system process.
 *   `finish()` there still lets the page appear for one frame, but it leaves nothing usable behind.
 *
 * The `onCreate` hook lands on the framework method rather than on the two Activity classes on
 * purpose: `ContactInfoUI` is only a compile-time stub (no members), and `ChattingUI` may or may not
 * declare `onCreate` itself depending on the version, whereas every Activity must call
 * `Activity.onCreate` — so one hook with a class-name check is both version-proof and cheaper than
 * two reflection lookups that can silently 0-hit.
 */
fun HideContacts.installBlockHooks() {
    WeStartActivityApi.addListener(BlockedActivityLauncher)

    // `onCreate(Bundle)` specifically: `Activity` also declares `onCreate(Bundle, PersistableBundle)`,
    // and the framework only calls that one when there is saved persistable state, so binding to it
    // by name alone would silently never fire.
    Activity::class.reflekt().firstMethod {
        name = "onCreate"
        parameters(Bundle::class)
    }.hookBefore {
        val activity = thisObject as Activity
        val surface = blockedSurface(activity.javaClass.name, activity.intent) ?: return@hookBefore

        WeLogger.i(TAG, "closing ${activity.javaClass.simpleName} for a hidden contact")
        activity.finish()
        if (surface == HideSurface.BLOCK_CHAT) showBlockedToast()
    }
}

fun HideContacts.uninstallBlockHooks() {
    WeStartActivityApi.removeListener(BlockedActivityLauncher)
}

private object BlockedActivityLauncher : WeStartActivityApi.IStartActivityListener {
    override fun onStartActivity(param: HookParam, intent: Intent) {
        // The listener is registered with another feature's API, so it is not covered by unhookAll():
        // if enable() fails after installBlockHooks() the feature is inactive but this would still be
        // listening. Stay inert in that case instead of blocking chats behind a disabled switch.
        if (!HideContacts.isActive) return
        val surface = blockedSurface(intent.component?.className, intent) ?: return

        // startActivity/startActivityForResult return void: assigning a result (null included) is
        // what tells the bridge to skip the original call. Done first, so nothing below can leave the
        // Activity un-cancelled.
        param.result = null
        WeLogger.i(TAG, "blocked startActivity to ${intent.component?.className} for a hidden contact")
        if (surface == HideSurface.BLOCK_CHAT) showBlockedToast()
    }
}

/**
 * Which gate [className] + [intent] trips, or null when opening it should be left alone.
 *
 * The two Activities take the wxid as a plain string extra; their own names are the only stable
 * anchors (`ChattingUI`/`ContactInfoUI` are both unobfuscated and have been for every supported
 * version). A missing component or extra means the target cannot be identified, so the call is let
 * through rather than blocked blind.
 */
private fun blockedSurface(className: String?, intent: Intent): HideSurface? {
    val (surface, extra) = when (className) {
        CHATTING_UI -> HideSurface.BLOCK_CHAT to "Chat_User"
        CONTACT_INFO_UI -> HideSurface.BLOCK_PROFILE to "Contact_User"
        else -> return null
    }
    if (!HideContacts.surfaceOn(surface)) return null

    val wxId = intent.getStringExtra(extra) ?: return null
    return if (HideContacts.isHiddenNow(wxId)) surface else null
}

private fun showBlockedToast() {
    // startActivity may be invoked from a background thread, and Toast requires a Looper.
    runOnUiThread {
        showToast(HostInfo.application, localizedContactsString(R.string.contacts_hide_blocked))
    }
}
