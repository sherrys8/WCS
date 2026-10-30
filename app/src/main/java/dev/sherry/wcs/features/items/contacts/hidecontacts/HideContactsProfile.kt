package dev.sherry.wcs.features.items.contacts.hidecontacts

import android.app.Activity
import android.content.Intent
import android.widget.BaseAdapter
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.sherry.wcs.R
import dev.sherry.wcs.constants.PackageNames
import dev.sherry.wcs.features.api.core.WeDatabaseApi
import dev.sherry.wcs.features.api.ui.WeContactPrefsScreenApi
import dev.sherry.wcs.features.items.contacts.HideContacts
import dev.sherry.wcs.features.items.contacts.localizedContactsString
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.currentWxId
import dev.sherry.wcs.utils.android.showToast

private const val TAG = "HideContacts.Profile"

/** Key of the row this file inserts into a simulated profile. Never collides with `contact_profile_*`. */
private const val ADD_CONTACT_KEY = "wcs_profile_add_contact"

private const val CONTACT_INFO_UI = "${PackageNames.WECHAT}.plugin.profile.ui.ContactInfoUI"

/**
 * Rows a friend's profile has but a stranger's does not.
 *
 * Taken from the donor implementation (vxMyfy `GroupProfileFeature`) rather than derived here: the
 * page keys are layout identities, not something that can be re-derived from a class shape, and the
 * set covers both the action rows and the friend-only information rows. `contact_profile_add_contact`
 * is deliberately absent — that is the row a simulated profile is supposed to offer.
 */
private val FRIEND_ONLY_PROFILE_KEYS = listOf(
    "contact_profile_send",
    "contact_profile_voip",
    "contact_profile_multi_button",
    "contact_profile_say_hi",
    "contact_profile_accept_contact",
    "contact_profile_accept_contact_by_wework",
    "contact_profile_setting_permission",
    "contact_profile_setting_desc",
    "contact_profile_label",
    "contact_profile_phone",
    "contact_profile_desc",
)

/**
 * 资料模拟非好友 / 拦截提交加好友.
 *
 * Two switches, both opt-in (`defaultOn = false` in [HideSurface]), sharing one idea: a hidden
 * contact should look and behave like someone who was never added.
 *
 * **资料模拟非好友** reworks the contact profile page. Friend-only rows are hidden through the
 * preference screen's own hide method, a 「添加到通讯录」 row is appended to the page, and clicks that
 * survive the hiding are swallowed with a toast. Nothing about the actual relationship changes — the
 * chat and the database row are untouched, which is also why 临时显示 lifts the whole simulation
 * ([HideContacts.isHiddenNow] is the single predicate).
 *
 * **拦截提交加好友** covers the other half: the add row opens WeChat's own 验证 page so the flow looks
 * native, but the commit button's `onClick` is cancelled before it can send anything. The page then
 * toasts 「已发送」 and closes. A friend request to a hidden contact never leaves the device, no matter
 * which entry opened that page.
 *
 * Neither feature needs a new process, a host restart, or a stored state beyond the two surface
 * switches: the page is rebuilt on every open, so a flip applies to the next profile.
 */
fun HideContacts.installProfileHooks() {
    WeContactPrefsScreenApi.addProvider(SimulatedStrangerProvider)
    hookSimulatedProfileRows()
    hookFriendRequestSubmit()
}

fun HideContacts.uninstallProfileHooks() {
    WeContactPrefsScreenApi.removeProvider(SimulatedStrangerProvider)
}

/**
 * Whether [activity] is a hidden contact's profile that should be presented as a stranger.
 *
 * `ContactInfoUI` only: [WeContactPrefsScreenApi] calls its providers for `ChatroomInfoUI` as well, and
 * a group's own profile must keep its rows even when the group itself is hidden.
 */
private fun isSimulatedStranger(activity: Activity): Boolean {
    // The provider is registered with another feature's API, so it is not covered by unhookAll():
    // stay inert when this feature is off instead of simulating profiles behind a disabled switch.
    if (!HideContacts.isActive) return false
    // Compared by name rather than with `is ContactInfoUI`: the stub has no superclass, so an `is`
    // check here is provably true at compile time and rejected by the compiler.
    if (activity.javaClass.name != CONTACT_INFO_UI) return false
    if (!HideContacts.surfaceOn(HideSurface.PROFILE_STRANGER)) return false

    val wxId = activity.currentWxId ?: return false
    return HideContacts.isHiddenNow(wxId)
}

/**
 * Hides the friend-only rows of a simulated profile.
 *
 * WeChat's preference screen exposes `hidePreference(String, boolean)` — the same call its own pages
 * use — which keeps the row's bookkeeping (position, separators, adapter notify) inside the host. The
 * method is found by signature, never by name: it is obfuscated differently on every version while
 * the `(String, boolean)` shape has not changed.
 *
 * A miss leaves the rows visible; the click gate in [SimulatedStrangerProvider] still keeps them
 * inert, so the page degrades to "looks normal, does nothing" rather than failing open.
 */
private fun HideContacts.hookSimulatedProfileRows() {
    ContactInfoUI::class.reflekt().firstMethod {
        name = "initView"
    }.hookAfter {
        val activity = thisObject as Activity
        if (!isSimulatedStranger(activity)) return@hookAfter

        val screen = activity.reflekt()
            .firstMethod {
                name = "getPreferenceScreen"
                superclass()
            }.invoke() ?: return@hookAfter

        val hidePreference = screen.javaClass.reflekt()
            .firstMethodOrNull {
                parameters(String::class, Boolean::class)
                returnType = Void.TYPE
                modifiers { it.contains(Modifiers.PUBLIC) }
                superclass()
            }?.self

        if (hidePreference == null) {
            WeLogger.w(TAG, "preference screen has no hidePreference(String, boolean); rows stay visible")
            return@hookAfter
        }

        FRIEND_ONLY_PROFILE_KEYS.forEach { hidePreference.invoke(screen, it, true) }
        (screen as BaseAdapter).notifyDataSetChanged()
        WeLogger.d(TAG, "hid ${FRIEND_ONLY_PROFILE_KEYS.size} friend-only row(s) on a simulated profile")
    }
}

/**
 * Swallows the friend request commit on WeChat's verify page.
 *
 * Cancelling `onClick` is the whole interception: the request is built and sent inside that body, so
 * there is nothing to clean up afterwards. The toast/finish pair exists only so the user sees the
 * same outcome as a real send — a deliberate simulation, not an error state.
 *
 * The page is reached from the click listener's own Activity field; the listener is an inner class of
 * the page on every version we have seen, and if it ever is the page itself `thisObject` already is
 * the Activity.
 */
private fun HideContacts.hookFriendRequestSubmit() {
    if (methodVerifySubmit.isPlaceholder) {
        WeLogger.w(TAG, "verify page submit wasn't resolved; friend requests to hidden contacts aren't intercepted")
        return
    }

    val pageField = methodVerifySubmit.method.declaringClass.reflekt()
        .firstFieldOrNull { type { it isSubclassOf Activity::class } }
        ?.self
        ?.makeAccessible()

    methodVerifySubmit.hookBefore {
        if (!surfaceOn(HideSurface.BLOCK_FRIEND_REQUEST)) return@hookBefore

        val page = when {
            thisObject is Activity -> thisObject as Activity
            pageField != null -> pageField.get(thisObject) as? Activity
            else -> null
        } ?: return@hookBefore

        val wxId = page.intent.getStringExtra("Contact_User") ?: return@hookBefore
        if (!isHiddenNow(wxId)) return@hookBefore

        result = null
        WeLogger.i(TAG, "intercepted a friend request to a hidden contact")
        showToast(page, localizedContactsString(R.string.contacts_hide_request_sent))
        page.finish()
    }
}

/**
 * The 添加到通讯录 row and the click gate, both delivered through [WeContactPrefsScreenApi] so the row
 * is added with WeChat's own adapter call instead of by mutating the screen from the outside.
 */
private object SimulatedStrangerProvider : WeContactPrefsScreenApi.IContactInfoProvider {

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        if (!isSimulatedStranger(activity)) return emptyList()
        // Without a resolved page the row could only toast an error, so don't offer it at all.
        if (resolvedVerifyPage() == null) return emptyList()

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = ADD_CONTACT_KEY,
                title = localizedContactsString(R.string.contacts_hide_add_contact),
                position = 1,
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (!isSimulatedStranger(activity)) return false

        if (key == ADD_CONTACT_KEY) {
            openNativeVerifyPage(activity)
            return true
        }
        if (key in FRIEND_ONLY_PROFILE_KEYS) {
            showToast(activity, localizedContactsString(R.string.contacts_hide_not_a_friend))
            return true
        }
        return false
    }
}

/** The resolved page class, or null when the resolution missed or landed on a non-Activity. */
private fun resolvedVerifyPage(): Class<*>? {
    if (HideContacts.classVerifyPage.isPlaceholder) return null

    val pageClass = HideContacts.classVerifyPage.clazz
    if (!Activity::class.java.isAssignableFrom(pageClass)) {
        WeLogger.w(TAG, "resolved verify page ${pageClass.name} is not an Activity")
        return null
    }
    return pageClass
}

/**
 * Opens WeChat's own verify page for a hidden contact.
 *
 * The extras are the page's documented entry contract: who to request, and which optional fields the
 * page may prefill. The profile's own intent is copied first so group-context extras (a member opened
 * from a chat) survive into the verify page, exactly as the donor implementation does.
 */
private fun openNativeVerifyPage(activity: Activity) {
    val pageClass = resolvedVerifyPage() ?: return
    val wxId = activity.currentWxId ?: return

    val intent = Intent(activity, pageClass).putExtras(activity.intent)
    intent.putExtra("Contact_User", wxId)
    intent.putExtra("Contact_Nick", WeDatabaseApi.getDisplayName(wxId))
    intent.putExtra("Contact_RemarkName", "")
    intent.putExtra("Contact_IsForceVerifyFriend", true)
    intent.putExtra("sayhi_with_sns_perm_send_verify", true)
    intent.putExtra("sayhi_with_sns_perm_add_remark", true)
    intent.putExtra("sayhi_with_sns_perm_set_label", false)

    activity.startActivity(intent)
    WeLogger.d(TAG, "opened the native verify page for a simulated profile")
}
