package dev.sherry.wcs.features.items.contacts.hidecontacts

import android.app.Activity
import android.widget.BaseAdapter
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.sherry.wcs.R
import dev.sherry.wcs.constants.PackageNames
import dev.sherry.wcs.features.api.ui.WeContactPrefsScreenApi
import dev.sherry.wcs.features.items.contacts.HideContacts
import dev.sherry.wcs.features.items.contacts.localizedContactsString
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.currentWxId
import dev.sherry.wcs.utils.android.showToast

private const val TAG = "HideContacts.Profile"

/**
 * The host's own 「添加到通讯录」 row.
 *
 * `ContactInfoUI` builds it on every profile and hides it while the contact is a friend, so revealing
 * it is one `hidePreference(key, false)` call rather than an injected row — and the native row keeps
 * the native click target, which opens WeChat's own 验证 page.
 */
private const val ADD_CONTACT_KEY = "contact_profile_add_contact"

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
 * preference screen's own hide method, the host's own 「添加到通讯录」 row is revealed by the same call,
 * and clicks on any friend-only row that somehow survived are swallowed with a toast. Nothing about the
 * actual relationship changes — the chat and the database row are untouched, which is also why 临时显示
 * lifts the whole simulation ([HideContacts.isHiddenNow] is the single predicate).
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
 * Hides the friend-only rows of a simulated profile and reveals the host's own add-contact row.
 *
 * WeChat's preference screen exposes `hidePreference(String, boolean)` — the same call its own pages
 * use — which keeps the row's bookkeeping (position, separators, adapter notify) inside the host. It is
 * found by parameter list only: both the name and the return type are obfuscated host classes that
 * differ per version, and demanding `void` here made the lookup miss on 8.0.76 and left every
 * friend-only row on screen. The resolved name is logged because a signature-only match is the kind of
 * thing that needs to be checkable from a device log.
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
                superclass()
            }?.self

        if (hidePreference == null) {
            WeLogger.w(
                TAG,
                "preference screen ${screen.javaClass.name} has no (String, boolean) row switch; rows stay visible",
            )
            return@hookAfter
        }

        WeLogger.d(TAG, "row switch is ${screen.javaClass.name}.${hidePreference.name}")
        FRIEND_ONLY_PROFILE_KEYS.forEach { hidePreference.invoke(screen, it, true) }
        hidePreference.invoke(screen, ADD_CONTACT_KEY, false)
        (screen as BaseAdapter).notifyDataSetChanged()
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
 * The click gate, delivered through [WeContactPrefsScreenApi].
 *
 * It contributes no rows of its own: the 添加到通讯录 row comes from the host (see [ADD_CONTACT_KEY]),
 * so intercepting that key would only get in its way. What stays is the fallback for the case the row
 * switch could not be resolved — a friend-only row that is still on screen must not open a chat, start
 * a call or reveal the relationship when tapped.
 */
private object SimulatedStrangerProvider : WeContactPrefsScreenApi.IContactInfoProvider {

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> =
        emptyList()

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (!isSimulatedStranger(activity)) return false
        if (key !in FRIEND_ONLY_PROFILE_KEYS) return false

        showToast(activity, localizedContactsString(R.string.contacts_hide_not_a_friend))
        return true
    }
}
