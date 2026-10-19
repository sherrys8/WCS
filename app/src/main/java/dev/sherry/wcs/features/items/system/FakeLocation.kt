package dev.sherry.wcs.features.items.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.os.SystemClock
import android.view.View
import android.widget.AdapterView
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.children
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.composables.icons.materialsymbols.outlined.Delete
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.sherry.wcs.R
import dev.sherry.wcs.dexkit.abc.IResolveDex
import dev.sherry.wcs.dexkit.dsl.dexMethod
import dev.sherry.wcs.features.api.ui.WeChatInputBarMenuApi
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.i18n.WcSLocaleController
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.OsmLocationPicker
import dev.sherry.wcs.ui.content.TextButton
import dev.sherry.wcs.ui.content.m3.BaseSupportingWidget
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.getTopMostActivity
import dev.sherry.wcs.utils.android.showToast
import org.osmdroid.util.GeoPoint
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.WeakHashMap

object FakeLocation : ClickableFeature(), IResolveDex {

    override val technicalId = "虚拟定位"
    override val nameRes = R.string.feature_fake_location_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_fake_location_description

    private val methodListener by dexMethod {
        matcher {
            name = "onLocationChanged"
            usingEqStrings("MicroMsg.SLocationListener")
        }
    }
    private val methodListenerWgs84 by dexMethod {
        matcher {
            name = "onLocationChanged"
            usingEqStrings("MicroMsg.SLocationListenerWgs84")
        }
    }
    private val methodDefaultManager by dexMethod {
        matcher {
            name = "onLocationChanged"
            usingEqStrings("MicroMsg.DefaultTencentLocationManager", "[mlocationListener]error:%d, reason:%s")
        }
    }

    private const val KEY_LAT = "fake_lat"
    private const val KEY_LNG = "fake_lng"

    private const val DEFAULT_LAT = 31.224361F
    private const val DEFAULT_LNG = 121.469170F
    private const val WECHAT_LOCATION_REQUEST_CODE = 6
    private const val TAG = "FakeLocation"

    private var latitude by prefOption(KEY_LAT, DEFAULT_LAT)
    private var longitude by prefOption(KEY_LNG, DEFAULT_LNG)

    private const val KEY_JUMP = "fake_jump_enabled"
    private const val KEY_JUMP_RADIUS = "fake_jump_radius_m"
    private const val KEY_JUMP_DWELL = "fake_jump_dwell_s"
    private const val KEY_JUMP_ACCURACY = "fake_jump_accuracy"

    private const val DEFAULT_JUMP_RADIUS = 200_000
    private const val MIN_JUMP_RADIUS = 100
    private const val MAX_JUMP_RADIUS = 5_000_000
    private const val DEFAULT_JUMP_DWELL = 60
    private const val MIN_JUMP_DWELL = 5
    private const val MAX_JUMP_DWELL = 600
    private const val MIN_JUMP_ACCURACY = 10
    private const val MAX_JUMP_ACCURACY = 50

    // Mean earth radius per degree of latitude.
    private const val METERS_PER_DEGREE = 111_320.0

    private var jumpEnabled by prefOption(KEY_JUMP, false)
    private var jumpRadius by prefOption(KEY_JUMP_RADIUS, DEFAULT_JUMP_RADIUS)
    private var jumpDwell by prefOption(KEY_JUMP_DWELL, DEFAULT_JUMP_DWELL)
    private var jumpAccuracy by prefOption(KEY_JUMP_ACCURACY, true)

    private val hookedLocationClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val rejectedLocationClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val hookedAppGridLongClickClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val locationItemViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val locationIntentRegex = Regex("""lat ([-+]?[0-9]*\.?[0-9]+);lng ([-+]?[0-9]*\.?[0-9]+);""")

    @Volatile
    private var pendingWeChatPicker = false

    @Volatile
    private var wechatPickerHooked = false

    override fun onEnable() {
        listOf(methodListener, methodListenerWgs84, methodDefaultManager).forEach {
            it.hookBefore {
                val tencentLocation = args[0]
                hookTencentLocation(tencentLocation)
            }
        }

        WeChatInputBarMenuApi.methodAppGridGetView.hookAfter {
            val itemView = result as View
            val isLocationItem = isLocationItem(itemView)
            synchronized(locationItemViews) {
                if (isLocationItem) locationItemViews.add(itemView)
                else locationItemViews.remove(itemView)
            }
            if (isLocationItem) {
                val appGrid = args[2] as AdapterView<*>
                hookAppGridLongClick(appGrid.onItemLongClickListener!!)
            }
        }
    }

    override fun onDisable() {
        hookedLocationClasses.clear()
        rejectedLocationClasses.clear()
        hookedAppGridLongClickClasses.clear()
        synchronized(locationItemViews) { locationItemViews.clear() }
        synchronized(jumpSamples) { jumpSamples.clear() }
        pendingWeChatPicker = false
        wechatPickerHooked = false
    }

    override fun onClick(context: ComponentActivity) {
        showSettings(context)
    }

    private fun showSettings(context: ComponentActivity) {
        showComposeDialog(context) {
            var jump by remember { mutableStateOf(jumpEnabled) }
            // Kept as text: a numeric field must be able to hold "2" while the user is still typing
            // toward "200000"; only in-range parses reach the preference.
            var radiusText by remember { mutableStateOf(jumpRadius.toString()) }
            var dwellText by remember { mutableStateOf(jumpDwell.toString()) }
            var accuracy by remember { mutableStateOf(jumpAccuracy) }
            // Local copies so clearing the selection updates the row in place; a preference delegate
            // read straight from composition never recomposes on its own.
            var centerLat by remember { mutableStateOf(latitude) }
            var centerLng by remember { mutableStateOf(longitude) }
            val isDefaultCenter = centerLat == DEFAULT_LAT && centerLng == DEFAULT_LNG

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_fake_location_name)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SegmentedColumn(title = stringResource(R.string.system_fake_location_group_point)) {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.system_fake_location_center),
                                    description = String.format(Locale.US, "%.5f, %.5f", centerLat, centerLng),
                                    onClick = {
                                        onDismiss()
                                        showLocationPicker(context)
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
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.system_fake_location_clear),
                                    description = stringResource(R.string.system_fake_location_clear_description),
                                    enabled = !isDefaultCenter,
                                    onClick = {
                                        latitude = DEFAULT_LAT
                                        longitude = DEFAULT_LNG
                                        centerLat = DEFAULT_LAT
                                        centerLng = DEFAULT_LNG
                                        showToast(localizedSystemString(R.string.system_fake_location_cleared))
                                    },
                                    trailingContent = {
                                        Icon(
                                            MaterialSymbols.Outlined.Delete,
                                            contentDescription = null,
                                            tint = if (isDefaultCenter) {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            },
                                        )
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        SegmentedColumn(title = stringResource(R.string.system_fake_location_group_jump)) {
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.system_fake_location_jump),
                                    description = stringResource(R.string.system_fake_location_jump_description),
                                    checked = jump,
                                    onCheckedChange = {
                                        jump = it
                                        jumpEnabled = it
                                    },
                                )
                            }
                            item {
                                val radiusHint = stringResource(R.string.system_fake_location_jump_radius_hint)
                                BaseSupportingWidget(title = stringResource(R.string.system_fake_location_jump_radius)) {
                                    val radiusValid = radiusText.trim().toIntOrNull()
                                        ?.let { it in MIN_JUMP_RADIUS..MAX_JUMP_RADIUS } == true
                                    OutlinedTextField(
                                        value = radiusText,
                                        onValueChange = { raw ->
                                            radiusText = raw
                                            raw.trim().toIntOrNull()
                                                ?.takeIf { it in MIN_JUMP_RADIUS..MAX_JUMP_RADIUS }
                                                ?.let { jumpRadius = it }
                                        },
                                        placeholder = { Text(radiusHint) },
                                        suffix = { Text(" m") },
                                        enabled = jump,
                                        isError = !radiusValid,
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            item {
                                val dwellHint = stringResource(R.string.system_fake_location_jump_dwell_hint)
                                BaseSupportingWidget(title = stringResource(R.string.system_fake_location_jump_dwell)) {
                                    val dwellValid = dwellText.trim().toIntOrNull()
                                        ?.let { it in MIN_JUMP_DWELL..MAX_JUMP_DWELL } == true
                                    OutlinedTextField(
                                        value = dwellText,
                                        onValueChange = { raw ->
                                            dwellText = raw
                                            raw.trim().toIntOrNull()
                                                ?.takeIf { it in MIN_JUMP_DWELL..MAX_JUMP_DWELL }
                                                ?.let { jumpDwell = it }
                                        },
                                        placeholder = { Text(dwellHint) },
                                        suffix = { Text(" s") },
                                        enabled = jump,
                                        isError = !dwellValid,
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.system_fake_location_jump_accuracy),
                                    description = stringResource(R.string.system_fake_location_jump_accuracy_description),
                                    checked = accuracy,
                                    enabled = jump,
                                    onCheckedChange = {
                                        accuracy = it
                                        jumpAccuracy = it
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
            )
        }
    }

    private fun showLocationPicker(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.system_fake_location_select)) },
                text = {
                    SegmentedColumn {
                        item {
                            BaseWidget(
                                title = stringResource(R.string.system_fake_location_wechat),
                                description = stringResource(R.string.system_fake_location_wechat_summary),
                                onClick = {
                                    onDismiss()
                                    launchWechatLocationPicker()
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                title = stringResource(R.string.system_fake_location_osm),
                                description = stringResource(R.string.system_fake_location_osm_summary),
                                onClick = {
                                    onDismiss()
                                    showOsmLocationPicker(context)
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
            )
        }
    }

    private fun isLocationItem(itemView: View): Boolean {
        return buildList {
            fun collectVisibleIcons(view: View) {
                if (view.visibility != View.VISIBLE) return
                if (view is ImageView && view.drawable != null) add(view.drawable)
                if (view is android.view.ViewGroup) view.children.forEach(::collectVisibleIcons)
            }
            collectVisibleIcons(itemView)
        }.any { drawable ->
            runCatching {
                val resourceId = drawable.reflekt()
                    .firstField { type = Int::class; superclass() }
                    .get() as Int
                itemView.resources.getResourceEntryName(resourceId) == "panel_icon_location"
            }.getOrDefault(false)
        }
    }

    private fun hookAppGridLongClick(listener: AdapterView.OnItemLongClickListener) {
        val listenerClass = listener.javaClass
        if (!hookedAppGridLongClickClasses.add(listenerClass)) return

        listenerClass.reflekt().firstMethod {
            name = "onItemLongClick"
            parameters(AdapterView::class, View::class, Int::class, Long::class)
        }.hookBefore {
            val itemView = args[1] as View
            val isLocationItem = synchronized(locationItemViews) { itemView in locationItemViews }
            if (!isLocationItem) return@hookBefore

            showLocationPicker((args[0] as AdapterView<*>).context)
            result = true
        }
    }

    private fun showOsmLocationPicker(context: Context) {
        showComposeDialog(context) {
            OsmLocationPicker(
                initialLocation = GeoPoint(
                    latitude.toDouble(),
                    longitude.toDouble(),
                ),
                onLocationSelected = {
                    onDismiss()
                    saveLocation(it.latitude.toFloat(), it.longitude.toFloat())
                },
                onDismiss = onDismiss
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun launchWechatLocationPicker() {
        val activity = getTopMostActivity()
        if (activity == null) {
            showToast(localizedSystemString(R.string.system_fake_location_no_activity))
            return
        }

        val redirectUiClass = "${activity.packageName}.plugin.location.ui.RedirectUI".toClass()

        if (!ensureWeChatPickerHooked(redirectUiClass)) {
            showToast(localizedSystemString(R.string.system_fake_location_listener_failed))
            return
        }

        runCatching {
            pendingWeChatPicker = true
            activity.startActivityForResult(
                Intent(activity, redirectUiClass).apply {
                    putExtra("map_view_type", 8)
                },
                WECHAT_LOCATION_REQUEST_CODE
            )
        }.onFailure {
            pendingWeChatPicker = false
            WeLogger.e(TAG, "failed to launch native location picker", it)
            showToast(localizedSystemString(R.string.system_fake_location_launch_failed, it.message.orEmpty()))
        }
    }

    private fun hookTencentLocation(tencentLocation: Any?) {
        val locationClass = tencentLocation?.javaClass ?: return
        if (locationClass in hookedLocationClasses) return

        val locationRef = locationClass.reflekt()
        val getLatitudeMethod = locationRef.firstMethodOrNull { name = "getLatitude" }
        val getLongitudeMethod = locationRef.firstMethodOrNull { name = "getLongitude" }
        if (getLatitudeMethod == null || getLongitudeMethod == null) {
            if (rejectedLocationClasses.add(locationClass)) {
                WeLogger.w(TAG, "no latitude/longitude getter on ${locationClass.name}, left alone")
            }
            return
        }
        if (!hookedLocationClasses.add(locationClass)) return

        getLatitudeMethod.hookBefore {
            result = if (jumpEnabled) sampleFor(thisObject!!).latitude else latitude.toDouble()
        }

        getLongitudeMethod.hookBefore {
            result = if (jumpEnabled) sampleFor(thisObject!!).longitude else longitude.toDouble()
        }

        // A jump that still reports a fixed accuracy reads as a pinned coordinate, so the accuracy
        // comes from the same frame. The preference is checked inside the hook, so toggling it needs
        // no re-hooking.
        val getAccuracyMethod = locationRef.firstMethodOrNull { name = "getAccuracy" }
        if (getAccuracyMethod != null) {
            when (getAccuracyMethod.self.returnType) {
                Int::class.javaPrimitiveType -> getAccuracyMethod.hookBefore {
                    if (jumpAccuracy) result = sampleFor(thisObject!!).accuracy
                }

                Double::class.javaPrimitiveType -> getAccuracyMethod.hookBefore {
                    if (jumpAccuracy) result = sampleFor(thisObject!!).accuracy.toDouble()
                }

                else -> WeLogger.w(TAG, "unexpected getAccuracy return type on ${locationClass.name}")
            }
        }
    }

    private class JumpSample(
        val at: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Int,
    )

    private val jumpSamples = Collections.synchronizedMap(WeakHashMap<Any, JumpSample>())

    /**
     * One random point per location object. WeChat asks latitude and longitude through two separate
     * calls, so drawing a value per getter would splice two different samples into a coordinate that
     * was never picked; the whole frame is cached on the instance instead. The dwell window covers
     * hosts that keep recycling a single location object.
     */
    private fun sampleFor(location: Any): JumpSample = synchronized(jumpSamples) {
        val now = SystemClock.elapsedRealtime()
        val dwellMs = jumpDwell * 1000L
        jumpSamples[location]?.takeIf { now - it.at < dwellMs }?.let { return it }

        val random = ThreadLocalRandom.current()
        val angle = random.nextDouble() * 2.0 * Math.PI
        // sqrt keeps the draw uniform over the disc; radius*random would pile every point on the centre.
        val distance = jumpRadius * Math.sqrt(random.nextDouble())
        val centerLat = latitude.toDouble()
        // cos() collapses toward zero near the poles; the clamp keeps the longitude offset finite.
        val latitudeScale = Math.max(Math.abs(Math.cos(Math.toRadians(centerLat))), 1e-6)
        JumpSample(
            at = now,
            latitude = (centerLat + distance * Math.sin(angle) / METERS_PER_DEGREE).coerceIn(-90.0, 90.0),
            longitude = (longitude + distance * Math.cos(angle) / (METERS_PER_DEGREE * latitudeScale))
                .coerceIn(-180.0, 180.0),
            accuracy = random.nextInt(MIN_JUMP_ACCURACY, MAX_JUMP_ACCURACY + 1),
        ).also { jumpSamples[location] = it }
    }

    private fun ensureWeChatPickerHooked(redirectUiClass: Class<*>): Boolean {
        if (wechatPickerHooked) return true

        return synchronized(this) {
            if (wechatPickerHooked) return@synchronized true

            val onActivityResult = runCatching {
                redirectUiClass.reflekt().firstMethod {
                    name = "onActivityResult"
                    parameters(Int::class, Int::class, Intent::class)
                }.self
            }.onFailure {
                WeLogger.e(TAG, "failed to find native location picker result handler", it)
            }.getOrNull() ?: return@synchronized false

            onActivityResult.hookAfter {
                handleWechatLocationPickerResult(args)
            }
            wechatPickerHooked = true
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun handleWechatLocationPickerResult(args: Array<Any?>) {
        val requestCode = args.getOrNull(0) as? Int ?: return
        if (requestCode != WECHAT_LOCATION_REQUEST_CODE || !pendingWeChatPicker) return

        pendingWeChatPicker = false
        val resultCode = args.getOrNull(1) as? Int ?: return
        if (resultCode != Activity.RESULT_OK) return

        val intent = args.getOrNull(2) as? Intent ?: return
        val locationIntent = intent.getParcelableExtra<Parcelable>("KLocationIntent") ?: return
        val locationData = locationIntent.reflekt()
            .firstMethod { returnType = String::class }
            .invoke() as? String ?: return

        val match = locationIntentRegex.find(locationData)
        val latitude = match?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val longitude = match?.groupValues?.getOrNull(2)?.toFloatOrNull()
        if (latitude == null || longitude == null) {
            WeLogger.w(TAG, "failed to parse native location result: $locationData")
            showToast(localizedSystemString(R.string.system_fake_location_parse_failed))
            return
        }

        saveLocation(latitude, longitude)
    }

    private fun saveLocation(latitude: Float, longitude: Float) {
        this.latitude = latitude
        this.longitude = longitude
        val locale = Locale.forLanguageTag(WcSLocaleController.resolvedLocale.androidTag)
        showToast(
            localizedSystemString(
                R.string.system_fake_location_selected,
                String.format(locale, "%.4f", latitude),
                String.format(locale, "%.4f", longitude),
            )
        )
    }
}
