package dev.sherry.wcs.features.items.beautify.home_page_cards

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Keyboard_double_arrow_down
import com.composables.icons.materialsymbols.outlined.Keyboard_double_arrow_up
import dev.ujhhgtg.reflekt.reflekt
import dev.sherry.wcs.R
import dev.sherry.wcs.activity.TransparentActivity
import dev.sherry.wcs.features.core.ClickableFeature
import dev.sherry.wcs.features.core.FeatureCategoryIds
import dev.sherry.wcs.preferences.WePrefs.Companion.prefOption
import dev.sherry.wcs.ui.content.AlertDialogContent
import dev.sherry.wcs.ui.content.m3.BaseWidget
import dev.sherry.wcs.ui.content.m3.RadioButtonWidget
import dev.sherry.wcs.ui.content.m3.SegmentedColumn
import dev.sherry.wcs.ui.content.m3.SwitchWidget
import dev.sherry.wcs.ui.content.m3.TextFieldDialogWidget
import dev.sherry.wcs.ui.utils.showComposeDialog
import dev.sherry.wcs.utils.WeLogger
import dev.sherry.wcs.utils.android.showToast
import dev.sherry.wcs.utils.nul
import org.json.JSONArray

object HomePageCards : ClickableFeature() {

    override val technicalId = "首页卡片"
    override val nameRes = R.string.feature_beautify_home_page_cards_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_beautify_home_page_cards_description

    private const val TAG = "HomePageCards"

    override val defaultEnabled: Boolean = true

    var imageCardEnabled by prefOption("home_image_card", false)
    var musicCardEnabled by prefOption("home_music_card", false)
    var cardsOrder by prefOption("home_cards_order", "image,music")

    var imageCardBgImage by prefOption("home_image_card_bg_image", nul<String>())
    var imageCardFormat by prefOption("home_image_card_format", "single")
    var imageCardImages by prefOption("home_image_card_images", "")
    var qsToken by prefOption("home_qs_token", "")

    override fun onEnable() {
        WeLogger.i(TAG, "首页卡片已启用")
        HpcMediaNotification.release()

        Activity::class.java.reflekt().firstMethod { name = "onResume" }.hookAfter {
            val act = thisObject as? Activity ?: return@hookAfter
            if (act.javaClass.name != "com.tencent.mm.ui.LauncherUI") return@hookAfter
            onLauncherResume(act)
        }
        WeLogger.i(TAG, "首页卡片 hook 已注册")
    }

    override fun onDisable() {
        WeLogger.i(TAG, "首页卡片已禁用")
        HpcImageCard.clearCache()
        HpcMusicCard.clearCache()
        HpcMediaNotification.release()
    }

    fun onLauncherResume(act: Activity) {
        HpcMusicCard.bindActivity(act)
        HpcCardManager.insertCards(act)
    }

    private fun selectImageCardImage(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult
                val stored = HpcImageAssets.importFromUri(uri)
                if (stored == null) {
                    showToast("图片导入失败，请重试")
                    return@registerForActivityResult
                }
                HpcImageAssets.deleteAsset(imageCardBgImage)
                imageCardBgImage = stored
                HpcImageCard.clearCache()
            }
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun selectImageCardImages(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickMultipleVisualMedia(5)
            ) { uris ->
                finish()
                if (uris.isEmpty()) return@registerForActivityResult
                val imported = mutableListOf<String>()
                for (uri in uris) {
                    val stored = HpcImageAssets.importFromUri(uri)
                    if (stored == null) {
                        imported.forEach(HpcImageAssets::deleteAsset)
                        showToast("图片导入失败，请重试")
                        return@registerForActivityResult
                    }
                    imported += stored
                }
                HpcImageCard.imageCardImagesList().forEach(HpcImageAssets::deleteAsset)
                imageCardImages = JSONArray(imported).toString()
                HpcImageCard.clearCache()
            }
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var imgEnabled by remember { mutableStateOf(imageCardEnabled) }
            var musEnabled by remember { mutableStateOf(musicCardEnabled) }
            var order by remember { mutableStateOf(cardsOrder) }
            var imgHasImage by remember { mutableStateOf(imageCardBgImage != null) }
            var imgFormat by remember { mutableStateOf(imageCardFormat) }
            var imgFiveCount by remember { mutableStateOf(HpcImageCard.imageCardImagesList().size) }
            var qsTokenState by remember { mutableStateOf(qsToken) }

            AlertDialogContent(
                title = { Text("首页卡片设置") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(title = "子卡片开关") {
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "图片卡",
                                    description = "自定义背景图",
                                    checked = imgEnabled,
                                    onCheckedChange = {
                                        imgEnabled = it
                                        imageCardEnabled = it
                                    },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = "音乐播放器",
                                    description = "音乐搜索、播放、收藏、悬浮歌词",
                                    checked = musEnabled,
                                    onCheckedChange = {
                                        musEnabled = it
                                        musicCardEnabled = it
                                    },
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        SegmentedColumn(title = "汽水音乐设置") {
                            item {
                                TextFieldDialogWidget(
                                    title = "API Token",
                                    value = qsTokenState,
                                    onValueChange = {
                                        qsTokenState = it
                                        qsToken = it
                                    },
                                    dialogTitle = "设置汽水音乐 Token",
                                    confirmLabel = "确认",
                                    dismissLabel = "取消",
                                    valueHint = "未设置，请前往 api.cxzja.cn 获取",
                                    password = true,
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        SegmentedColumn(title = "图片卡背景") {
                            item {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = "单图格式",
                                    description = "一张背景图铺满整卡",
                                    selected = imgFormat == "single",
                                    onClick = {
                                        imgFormat = "single"
                                        imageCardFormat = "single"
                                        HpcImageCard.clearCache()
                                    },
                                )
                            }
                            item {
                                RadioButtonWidget(
                                    iconPlaceholder = false,
                                    title = "五图模板",
                                    description = "5 张图片并排展示",
                                    selected = imgFormat == "five",
                                    onClick = {
                                        imgFormat = "five"
                                        imageCardFormat = "five"
                                        HpcImageCard.clearCache()
                                    },
                                )
                            }
                            if (imgFormat == "single") {
                                item {
                                    BaseWidget(
                                        iconPlaceholder = false,
                                        title = "背景图片",
                                        description = if (imgHasImage) "已选择图片" else "未设置（使用纯色背景）",
                                        onClick = { selectImageCardImage(context) },
                                        trailingContent = {
                                            if (imgHasImage) {
                                                IconButton(onClick = {
                                                    HpcImageAssets.deleteAsset(imageCardBgImage)
                                                    imageCardBgImage = null
                                                    imgHasImage = false
                                                    HpcImageCard.clearCache()
                                                }) {
                                                    Icon(
                                                        MaterialSymbols.Outlined.Delete,
                                                        contentDescription = "清除图片",
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            } else {
                                item {
                                    BaseWidget(
                                        iconPlaceholder = false,
                                        title = "选择5张图片",
                                        description = if (imgFiveCount > 0) "已选择 $imgFiveCount 张图片" else "未设置（使用灰色占位）",
                                        onClick = { selectImageCardImages(context) },
                                        trailingContent = {
                                            if (imgFiveCount > 0) {
                                                IconButton(onClick = {
                                                    HpcImageCard.imageCardImagesList().forEach(HpcImageAssets::deleteAsset)
                                                    imageCardImages = ""
                                                    imgFiveCount = 0
                                                    HpcImageCard.clearCache()
                                                }) {
                                                    Icon(
                                                        MaterialSymbols.Outlined.Delete,
                                                        contentDescription = "清除五图",
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        SegmentedColumn(title = "卡片顺序") {
                            val ids = order.split(",").filter { it.isNotEmpty() && it != "calendar" }
                            val labels = mapOf(
                                "image" to "图片卡",
                                "music" to "音乐播放器",
                            )
                            ids.forEachIndexed { index, id ->
                                val label = labels[id] ?: id
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "${index + 1}. $label",
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(
                                            enabled = index > 0,
                                            onClick = {
                                                val list = ids.toMutableList()
                                                val tmp = list[index]
                                                list[index] = list[index - 1]
                                                list[index - 1] = tmp
                                                order = list.joinToString(",")
                                                cardsOrder = order
                                            },
                                        ) {
                                            Icon(
                                                MaterialSymbols.Outlined.Keyboard_double_arrow_up,
                                                contentDescription = "上移",
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                        IconButton(
                                            enabled = index < ids.size - 1,
                                            onClick = {
                                                val list = ids.toMutableList()
                                                val tmp = list[index]
                                                list[index] = list[index + 1]
                                                list[index + 1] = tmp
                                                order = list.joinToString(",")
                                                cardsOrder = order
                                            },
                                        ) {
                                            Icon(
                                                MaterialSymbols.Outlined.Keyboard_double_arrow_down,
                                                contentDescription = "下移",
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}