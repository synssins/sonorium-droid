package app.sonorium.widget

import app.sonorium.ui.darkEdgeToEdge
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sonorium.R
import app.sonorium.SonoriumApp
import app.sonorium.ui.Palette
import app.sonorium.ui.SonoriumTheme
import app.sonorium.ui.UiSize
import app.sonorium.ui.server.SIcons
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first

/** A widget's channel: this device's My Channel, or a channel of a linked Sonorium. */
class WidgetConfigActivity : androidx.activity.ComponentActivity() {
    private data class Option(val choice: WidgetChoice, val title: String, val source: String, val device: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkEdgeToEdge(this)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // Backing out leaves no widget behind
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val app = application as SonoriumApp
        setContent {
            SonoriumTheme {
                val options = remember { mutableStateListOf<Option>() }
                var loading by remember { mutableStateOf(true) }
                val myChannel = stringResource(R.string.widget_my_channel)
                LaunchedEffect(Unit) {
                    val device = app.deviceRecord()
                    options += Option(WidgetChoice(SonoriumApp.DEVICE_ID, WidgetUpdater.DEVICE_CHANNEL), myChannel, device.displayName, true)
                    val servers = app.servers.servers.first().filter { !it.device }
                    servers.map { record ->
                        async {
                            val sessions = runCatching { WidgetUpdater.apiFor(app, record.id)?.sessions() }.getOrNull().orEmpty()
                            sessions.map { Option(WidgetChoice(record.id, it.id), it.name.ifBlank { myChannel }, record.displayName, false) }
                        }
                    }.awaitAll().forEach { options += it }
                    loading = false
                }
                Surface(color = Palette.background, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                        Text(stringResource(R.string.widget_config_title), color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.size(12.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(options) { o ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = UiSize.ROW.dp).clickable {
                                        WidgetStore.set(this@WidgetConfigActivity, widgetId, o.choice)
                                        WidgetUpdater.update(applicationContext, widgetId)
                                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                        finish()
                                    }.padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(if (o.device) SIcons.Device else SIcons.Server, null, tint = Palette.textSecondary, modifier = Modifier.size(UiSize.GLYPH.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(o.title, color = Palette.text, fontSize = 14.sp)
                                        Text(o.source, color = Palette.textSecondary, fontSize = 12.sp)
                                    }
                                }
                            }
                            if (loading) item {
                                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.widget_config_loading), color = Palette.textSecondary, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
