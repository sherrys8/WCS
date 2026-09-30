package dev.sherry.wcs.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import dev.sherry.wcs.i18n.LocaleResourceMode
import dev.sherry.wcs.i18n.WcSLocaleProvider

@Composable
fun ModuleAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    WcSLocaleProvider(mode = LocaleResourceMode.ModuleApp) {
        val colorScheme = if (darkTheme) darkScheme else lightScheme
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
        ) {
            content()
        }
    }
}
