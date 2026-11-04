package com.nemuigames.nemuitv.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nemuigames.nemuitv.ha.HaPresence
import com.nemuigames.nemuitv.ha.HaStatus
import com.nemuigames.nemuitv.home.Jt
import com.nemuigames.nemuitv.ui.GuideLinks
import com.nemuigames.nemuitv.ui.GuideQr

/** The hub shows the "no Home Assistant" page (instead of loading / error notes) when HA isn't configured at all. */
internal fun hubShowsNoHaPage(status: HaStatus): Boolean = !HaPresence.showsHaUi(status)

/** What the hub would add with Home Assistant, plus a QR code to the setup guide. Nothing here takes focus; Back leaves. */
@Composable
internal fun NoHaPane(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No Home Assistant set up", fontSize = 20.sp, color = Jt.TextPrimary)
            Spacer(Modifier.height(4.dp))
            listOf("Rooms, lights and scenes", "Cameras, doorbell and alerts", "Tesla").forEach {
                Text(it, fontSize = 15.sp, color = Jt.TextSecondary)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GuideQr(GuideLinks.HA, 120.dp)
            Text("Setup guide", fontSize = 12.sp, color = Jt.TextSecondary)
        }
    }
}
