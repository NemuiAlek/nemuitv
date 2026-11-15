package com.nemuigames.nemuitv.hub

import androidx.compose.runtime.mutableLongStateOf
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.core.NemuiCore
import com.nemuigames.nemuitv.home.Glass
import com.nemuigames.nemuitv.home.Jt
import kotlinx.coroutines.launch
import kotlin.math.abs

private fun roomKey(r: Room) = "room:${r.areaId}"

// Locked layout (960×540 dp design canvas): rail and strip start just under
// the home clock's date (y = 21 % = 113 dp); rail x = 48, ≈ 182 wide; pane x ≈ 250 → 912, top 30.5 % ≈ 164; the strip
// is exactly the pane's width with its bottom 10 dp above the pane.
private val HubTop = 113.dp
private val RailWidth = 182.dp
private val RailGap = 20.dp
private val StripHeight = 41.dp
private val StripGap = 10.dp
private val PaneShape = RoundedCornerShape(12.dp)
private val RailShape = RoundedCornerShape(6.dp)
/** The room count on the white (selected) rail item: the mockup's darker yellow, readable on white. */
internal val WarmOnWhite = Color(0xFFA07A00)

/** What's open over the hub: the brightness sheet (hold OK on a dimmable light) or the thermostat sheet. */
sealed interface HubSheet {
    data class Brightness(val target: SheetTarget) : HubSheet
    /** [start] = HA's state when the sheet opened; [draft] = the user's edits. Back sends the one call between them. */
    data class Thermostat(val entityId: String, val start: ThermoDraft, val draft: ThermoDraft) : HubSheet
}

/** Focus with a result (the plain call fails silently, e.g. on an item that isn't composed yet). */
private fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)

private suspend fun retryFocus(request: suspend () -> Boolean) {
    repeat(10) { if (request()) return; withFrameNanos { } }
}

/**
 * The ⌂ Home hub page (spec §2–§3): left rail (Overview, Tesla, rooms), the status strip, and the pane for whatever
 * the rail has selected. Drawn over the home screen's wallpaper + clock (HomeScreen keeps those). Opens [NemuiCore.hub]
 * while shown and closes it when it leaves; [onExit] = back to the home screen (Back from the rail, or Home stops
 * the activity / an app takes over).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HubScreen(core: NemuiCore, onExit: () -> Unit) {
    val hub = core.hub
    val ui by hub.ui.collectAsState()
    val haStatus by core.ha.status.collectAsState()
    val noHa = hubShowsNoHaPage(haStatus)
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(core) { core.cameras.prewarm(appContext) } // before Overview's 500 ms debounce fires
    DisposableEffect(hub) {
        Log.i("NemuiTV", "hub: open")
        hub.open()
        onDispose { Log.i("NemuiTV", "hub: close"); hub.close() }
    }
    // Close right at ON_STOP: the dispose-time close() would wait for a recomposition, which is paused while an app or
    // the screensaver is in front. close() is idempotent, so the later dispose is harmless.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        Log.i("NemuiTV", "hub: close (stopped)")
        core.cameras.stopNow() // at once: no hidden decoders competing with the app in front (the camera effect below can't recompose while stopped)
        hub.close(); onExit()
    }

    var selected by remember { mutableStateOf(OVERVIEW) }
    var sheet by remember { mutableStateOf<HubSheet?>(null) }
    var paneHasFocus by remember { mutableStateOf(false) }
    val railFocus = remember { HashMap<String, FocusRequester>() }
    fun railReq(key: String) = railFocus.getOrPut(key) { FocusRequester() }
    val tileFocus = remember { HashMap<String, FocusRequester>() }
    fun tileReq(id: String) = tileFocus.getOrPut(id) { FocusRequester() }
    val roomOffFocus = remember { FocusRequester() }
    val overviewFirst = remember { FocusRequester() }
    val thermostatFocus = remember { FocusRequester() }
    val teslaFirst = remember { FocusRequester() }
    val railState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val rooms = ui.model?.rooms.orEmpty()
    val hasCars = ui.model?.cars?.isNotEmpty() == true
    val railKeys = railKeys(rooms.map(::roomKey), hasCars)
    val room = rooms.firstOrNull { roomKey(it) == selected }
    // Appliance cycles (washer, dryer…) count down: re-read the time every 30 s, only when the house has any.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasCycles = ui.model?.cycles?.isNotEmpty() == true
    LaunchedEffect(hasCycles) {
        while (hasCycles) { kotlinx.coroutines.delay(30_000); nowMs = System.currentTimeMillis() }
    }
    val tiles = room?.let { hub.tiles(it, nowMs) }.orEmpty()
    // Not confirmed by HA right now (offline, or reopened/reconnected and still waiting for the first event): what's
    // shown may be old, so it's dimmed and inert. Only real offline gets the banner.
    val inert = ui.offline || !ui.live

    // These read the current state when called (not values captured at some earlier composition): they're handed to
    // the rail/pane as callbacks, which Compose may keep from an earlier composition.
    fun currentRoom(): Room? = hub.ui.value.model?.rooms?.firstOrNull { roomKey(it) == selected }
    fun focusRail() = scope.launch {
        val key = selected
        val cars = hub.ui.value.model?.cars?.isNotEmpty() == true
        val keys = railKeys(hub.ui.value.model?.rooms.orEmpty().map(::roomKey), cars)
        val index = railIndex(keys.indexOf(key).coerceAtLeast(0), cars)
        if (railState.layoutInfo.visibleItemsInfo.none { it.index == index }) runCatching { railState.scrollToItem(index) }
        retryFocus { railReq(key).tryFocus() }
    }
    fun enterPane() {
        when (selected) {
            OVERVIEW -> { overviewFirst.tryFocus() }
            // The first car's fan (or its COP row); nothing when no car has controls. Only → / OK on the rail item gets
            // here, never ▲ ▼ through the rail (a control must not be focused by arriving on the pane).
            TESLA -> { teslaFirst.tryFocus() }
            else -> {
                val r = currentRoom() ?: return
                hub.tiles(r).firstOrNull()?.let { tileReq(it.tile.entityId).tryFocus() } ?: roomOffFocus.tryFocus()
            }
        }
    }
    fun openThermostat() {
        val id = hub.ui.value.model?.thermostat ?: return
        val start = startDraft(hub.ui.value.states[id]) ?: return
        sheet = HubSheet.Thermostat(id, start, start)
    }
    fun closeSheet(save: Boolean) {
        val s = sheet ?: return
        // Focus goes back to the tile BEFORE the sheet leaves: when the focused sheet just disappears, Compose hands
        // focus to the page's first item (Overview), which would switch the pane.
        // If its tile vanished meanwhile: the pane's first item, else the selected rail item (never Overview by accident).
        val back = when (s) {
            is HubSheet.Brightness -> {
                val r = currentRoom()
                tileReq(s.target.entityId).tryFocus() ||
                    (r != null && hub.tiles(r).firstOrNull()?.let { tileReq(it.tile.entityId).tryFocus() } == true)
            }
            is HubSheet.Thermostat -> thermostatFocus.tryFocus() || overviewFirst.tryFocus()
        } || railReq(selected).tryFocus()
        if (!back) Log.w("NemuiTV", "hub: no focus target after the sheet")
        sheet = null
        if (!save) return
        // Saves only a change, and only while the device is still available (else closes unsaved). Offline is left
        // to setBrightness / setClimate, which refuse it with the "Home Assistant offline" message instead of closing
        // silently.
        val u = hub.ui.value
        when (s) {
            is HubSheet.Brightness -> {
                val t = s.target
                if (t.value != t.start && !isUnavailable(u.states[t.entityId])) hub.setBrightness(t.entityId, t.value)
            }
            // One call, or none if unchanged (climateCall decides).
            is HubSheet.Thermostat ->
                if (!isUnavailable(u.states[s.entityId])) hub.setClimate(s.entityId, s.start, s.draft)
        }
    }

    // Start on Overview.
    LaunchedEffect(Unit) { withFrameNanos { }; retryFocus { railReq(OVERVIEW).tryFocus() } }
    // Live cameras run only while Overview is on screen (a sheet over it counts) AND the activity is STARTED. The bank
    // debounces true by 500 ms, so the rail passing over Overview starts nothing. Leaving the hub (Back / rail away)
    // starts the 60 s grace (onDispose too); Home-to-app / screensaver stop at once at ON_STOP above and in
    // HomeActivity.onStop (which also covers the hub being gone already).
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val cameraIds = ui.model?.cameras.orEmpty()
    val camerasOn = selected == OVERVIEW && ui.model != null && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(camerasOn, cameraIds) { core.cameras.setVisible(cameraIds, camerasOn) }
    DisposableEffect(core) { onDispose { core.cameras.setVisible(emptyList(), false) } }
    // The selected pane's rail item vanished (the car went away): back to Overview.
    LaunchedEffect(railKeys) {
        val v = validSelection(selected, railKeys)
        if (v != selected) { selected = v; sheet = null; focusRail() }
    }
    // The selected room vanished from HA (renamed / emptied): back to Overview.
    LaunchedEffect(selected, ui.model) {
        if (ui.model != null && selected != OVERVIEW && selected != TESLA && room == null) {
            selected = OVERVIEW; sheet = null; focusRail()
        }
    }

    BackHandler {
        when {
            sheet != null -> closeSheet(save = true)
            paneHasFocus -> focusRail()
            else -> onExit()
        }
    }

    // Scroll the rail / tile grid only as far as needed to show the focused item (the TV default keeps it at a pivot,
    // which slid the rail's top items up under the date).
    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
        Box(Modifier.fillMaxSize().padding(start = Jt.SafeAreaH, end = Jt.SafeAreaH, top = HubTop, bottom = Jt.SafeAreaV)) {
            if (noHa) {
                // No Home Assistant: just the explainer panel (no rail, no strip), same pane geometry as the full hub.
                Box(Modifier.fillMaxSize().padding(start = RailWidth + RailGap).then(Glass.surface(PaneShape)).padding(13.dp)) {
                    NoHaPane()
                }
            } else Row(Modifier.fillMaxSize()) {
                HubRail(
                    rooms = rooms, hasCars = hasCars, ui = ui, selected = selected, state = railState, railReq = ::railReq,
                    lastKey = railKeys.last(), inert = inert,
                    onFocused = { selected = it },
                    onEnter = { enterPane() },
                    modifier = Modifier.width(RailWidth),
                )
                Spacer(Modifier.width(RailGap))
                Column(Modifier.weight(1f)) {
                    HubStrip(ui.strip, dimmed = inert, modifier = Modifier.fillMaxWidth().height(StripHeight))
                    Spacer(Modifier.height(StripGap))
                    Box(
                        Modifier.fillMaxSize().then(Glass.surface(PaneShape)).padding(13.dp)
                            .onFocusChanged { paneHasFocus = it.hasFocus }
                            // Trap focus in the pane: ▲ ▼ ▶ must never search their way onto the rail (that switched
                            // the room). ◀ to the rail and the sheet are explicit requestFocus calls (direction Enter).
                            .focusProperties {
                                this.onExit = { // "this.": HubScreen has its own onExit parameter
                                    if (requestedFocusDirection != FocusDirection.Enter) cancelFocusChange()
                                }
                            }
                            .focusGroup(),
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            if (ui.offline) {
                                Text(
                                    "Home Assistant offline — reconnecting…", fontSize = 12.sp, color = Jt.TextSecondary,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            when {
                                ui.model == null && ui.loading -> PaneNote("Loading…")
                                ui.model == null -> PaneNote("Couldn't load your home — reconnecting…")
                                room != null -> RoomPane(
                                    room = room, tiles = tiles,
                                    onCount = roomOnCount(room, ui.states),
                                    offline = inert || sheet != null,
                                    canRoomOff = roomOffActive(room, ui.states),
                                    onToggle = hub::toggle,
                                    onRoomOff = { hub.roomOff(room) },
                                    onHold = { t ->
                                        val start = sheetStart(t.brightness)
                                        sheet = HubSheet.Brightness(
                                            SheetTarget(t.tile.entityId, "${t.tile.name} · ${room.name}", start, start),
                                        )
                                    },
                                    onLeft = { focusRail() },
                                    tileFocus = ::tileReq,
                                    roomOffFocus = roomOffFocus,
                                )
                                selected == OVERVIEW -> OverviewPane(
                                    ui = ui, bank = core.cameras, scenes = hub.scenes(), inert = inert || sheet != null,
                                    onScene = hub::runScene,
                                    onThermostat = { openThermostat() },
                                    onLock = hub::lock,
                                    onLeft = { focusRail() },
                                    firstFocus = overviewFirst,
                                    thermostatFocus = thermostatFocus,
                                    cycles = hub.runningCycles(nowMs),
                                )
                                selected == TESLA -> {
                                    val m = ui.model!!
                                    TeslaPane(
                                        cards = teslaCards(m, ui.states, core.hubConfig, System.currentTimeMillis(), java.time.ZoneId.systemDefault()),
                                        inert = inert || sheet != null,
                                        controls = { p ->
                                            CarControlsUi(
                                                fanPending = m.carClimate[p]?.let { it in ui.pending } == true,
                                                copPending = m.carCop[p]?.let { it in ui.pending } == true,
                                                blocked = p in ui.blockedCars,
                                            )
                                        },
                                        onFan = hub::toggleCarClimate,
                                        onCop = hub::toggleCop,
                                        onLeft = { focusRail() },
                                        firstFocus = teslaFirst,
                                    )
                                }
                                else -> PaneNote("Coming in the next update")
                            }
                        }
                        ui.message?.let { msg ->
                            Text(
                                msg, fontSize = 12.sp, color = Jt.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.BottomCenter)
                                    .then(Glass.matte(RoundedCornerShape(8.dp)))
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    when (val s = sheet) {
        is HubSheet.Brightness -> BrightnessSheet(s.target) { v -> sheet = s.copy(target = s.target.copy(value = v)) }
        is HubSheet.Thermostat -> ThermostatSheet(s.draft) { d -> sheet = s.copy(draft = d) }
        null -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
private object MinimalScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f && trailing > containerSize -> 0f
            abs(offset) < abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}

@Composable
private fun PaneNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 15.sp, color = Jt.TextSecondary)
    }
}

@Composable
private fun HubRail(
    rooms: List<Room>,
    hasCars: Boolean,
    ui: HubUi,
    selected: String,
    state: LazyListState,
    railReq: (String) -> FocusRequester,
    lastKey: String,
    inert: Boolean,
    onFocused: (String) -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enter by rememberUpdatedState(onEnter)
    val focused by rememberUpdatedState(onFocused)

    @Composable
    fun RailItem(key: String, label: String, count: Int) {
        val isSelected = key == selected
        Surface(
            onClick = { enter() },
            modifier = Modifier.fillMaxWidth().focusRequester(railReq(key))
                .onFocusChanged { if (it.isFocused) focused(key) }
                .onPreviewKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionRight -> { if (e.type == KeyEventType.KeyDown) enter(); true }
                        Key.DirectionLeft -> true
                        // The ends of the rail: stay (nothing else on the page should catch ▲ / ▼ from here).
                        Key.DirectionUp -> key == OVERVIEW
                        Key.DirectionDown -> key == lastKey
                        else -> false
                    }
                },
            shape = ClickableSurfaceDefaults.shape(RailShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (isSelected) Jt.FrostFocused else Color.Transparent,
                contentColor = if (isSelected) Jt.TextOnFocus else Jt.TextPrimary,
                focusedContainerColor = Jt.FrostFocused, focusedContentColor = Jt.TextOnFocus,
                pressedContainerColor = Jt.FrostFocused, pressedContentColor = Jt.TextOnFocus,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(BorderStroke(if (isSelected) 2.dp else 0.dp, if (isSelected) Color.White else Color.Transparent), shape = RailShape),
                focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = RailShape),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (count > 0) {
                    // Parked (inert): the count may be old, so it dims like the pane.
                    val warm = if (isSelected) WarmOnWhite else HubWarm
                    Text(" $count", fontSize = 14.sp, color = warm.copy(alpha = if (inert) 0.45f else 1f))
                }
            }
        }
    }

    // More rooms above / below: the rail's edge row fades out instead of being cut off hard.
    val fade = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val h = 36.dp.toPx()
        if (state.canScrollForward) drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - h, endY = size.height),
            topLeft = Offset(0f, size.height - h), blendMode = BlendMode.DstIn,
        )
        if (state.canScrollBackward) drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = h),
            size = Size(size.width, h), blendMode = BlendMode.DstIn,
        )
    }
    LazyColumn(modifier.fillMaxSize().then(fade), state = state, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        item(key = OVERVIEW) { RailItem(OVERVIEW, "⌂ Overview", 0) }
        if (hasCars) item(key = TESLA) { RailItem(TESLA, "🚗 Tesla", 0) }
        item(key = "divider") {
            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(Color.White.copy(alpha = 0.2f)))
        }
        rooms.forEach { r ->
            item(key = roomKey(r)) { RailItem(roomKey(r), r.name, roomOnCount(r, ui.states)) }
        }
    }
}
