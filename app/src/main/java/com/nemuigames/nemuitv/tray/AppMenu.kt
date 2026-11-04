package com.nemuigames.nemuitv.tray

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.apps.AppInfo
import com.nemuigames.nemuitv.favorites.AppOrder
import com.nemuigames.nemuitv.home.Jt
import com.nemuigames.nemuitv.home.trapFocus

/**
 * The long-press that opens the menu is still being held when the menu appears. A real remote keeps sending
 * *repeated* OK presses while held, then the release, and that release would click the first option.
 * So OK is ignored until a genuinely new press (repeatCount 0) happens inside the menu.
 */
class MenuKeyGuard {
    private var armed = false
    /** True = swallow this key event. */
    fun swallow(isDown: Boolean, isOk: Boolean, repeatCount: Int): Boolean {
        if (isDown && repeatCount == 0) { armed = true; return false }
        return isOk && !armed
    }
}

/** What the long-press menu can ask the home screen to do. */
interface AppMenuActions {
    fun startMove(pkg: String)
    fun hide(pkg: String)
    fun uninstall(app: AppInfo)
    fun forceClose(app: AppInfo)
}

enum class MenuOption(val label: String) {
    MOVE("Move"),
    HIDE("Hide from tray"),
    UNINSTALL("Uninstall…"),
    // Opens Android's page for the app, where the system "Force stop" works even on an app you just left
    // (a launcher itself can only close apps Android already considers idle).
    FORCE_CLOSE("Force close…"),
}

/** Which options the long-press menu shows. System apps can't be uninstalled. */
fun menuOptions(isSystem: Boolean, isHome: Boolean = false): List<MenuOption> =
    if (isHome) listOf(MenuOption.MOVE) // ⌂ is not an app: it can only be moved
    else listOfNotNull(MenuOption.MOVE, MenuOption.HIDE, MenuOption.UNINSTALL.takeIf { !isSystem }, MenuOption.FORCE_CLOSE)

/** Long-press menu: Move, Hide from tray, Uninstall… (user apps only) and Force close…. */
@Composable
fun AppMenu(app: AppInfo, actions: AppMenuActions, onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    val guard = remember { MenuKeyGuard() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).onPreviewKeyEvent {
            guard.swallow(
                isDown = it.type == KeyEventType.KeyDown,
                isOk = it.key == Key.DirectionCenter || it.key == Key.Enter,
                repeatCount = it.nativeKeyEvent.repeatCount,
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(300.dp).background(Jt.PanelBg, Jt.PanelShape).padding(16.dp).trapFocus()) {
            Text(app.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Jt.TextPrimary)
            Spacer(Modifier.height(12.dp))
            menuOptions(app.isSystem, isHome = app.packageName == AppOrder.HOME_ID).forEachIndexed { i, option ->
                MenuButton(option.label, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                    when (option) {
                        MenuOption.MOVE -> actions.startMove(app.packageName)
                        MenuOption.HIDE -> actions.hide(app.packageName)
                        MenuOption.UNINSTALL -> actions.uninstall(app)
                        MenuOption.FORCE_CLOSE -> actions.forceClose(app)
                    }
                    onDismiss()
                }
            }
        }
    }
}

/** A row-style button for frosted panels (long-press menu, Hidden apps), with an optional [leading] logo. */
@Composable
internal fun MenuButton(
    label: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Jt.Frost, focusedContainerColor = Jt.FrostFocused,
            contentColor = Jt.TextPrimary, focusedContentColor = Jt.TextOnFocus,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
            Text(label, fontSize = 14.sp)
        }
    }
}
