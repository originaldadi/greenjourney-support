package io.github.originaldadi.soundcast.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.originaldadi.soundcast.R
import io.github.originaldadi.soundcast.core.SonosClient
import io.github.originaldadi.soundcast.core.SonosGroup
import io.github.originaldadi.soundcast.core.SsdpDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) { CastScreen() }
        }
    }
}

/** Finds every Sonos group on the WiFi network. Runs on a background thread. */
private suspend fun findGroups(context: Context): List<SonosGroup> = withContext(Dispatchers.IO) {
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    val lock = wifi.createMulticastLock("soundcast-discovery").apply { setReferenceCounted(false) }
    val locations = try {
        lock.acquire()
        SsdpDiscovery.discover()
    } finally {
        lock.release()
    }
    if (locations.isEmpty()) return@withContext emptyList()

    val client = SonosClient()
    // Any speaker can describe the whole household; try them until one answers.
    for (location in locations) {
        val host = SsdpDiscovery.hostOf(location) ?: continue
        val groups = runCatching { client.groups(host) }.getOrNull()
        if (!groups.isNullOrEmpty()) return@withContext groups
    }
    locations.mapNotNull { runCatching { client.standaloneGroup(it) }.getOrNull() }
}

@Composable
private fun CastScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by CastState.status.collectAsState()

    var groups by remember { mutableStateOf<List<SonosGroup>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<SonosGroup?>(null) }
    var volume by remember { mutableFloatStateOf(-1f) }

    fun search() {
        searching = true
        searchError = null
        scope.launch {
            val found = runCatching { findGroups(context) }
            searching = false
            groups = found.getOrDefault(emptyList())
            searchError = found.exceptionOrNull()?.message
            if (selected !in groups) selected = groups.singleOrNull()
        }
    }

    LaunchedEffect(Unit) { search() }

    // Show the speaker's current volume when a group is picked.
    LaunchedEffect(selected) {
        volume = -1f
        val host = selected?.coordinatorHost ?: return@LaunchedEffect
        volume = withContext(Dispatchers.IO) {
            runCatching { SonosClient().groupVolume(host).toFloat() }.getOrDefault(-1f)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val group = selected
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null && group != null) {
            CastService.start(context, result.resultCode, data, group)
        }
    }

    fun askForCapture() {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        // On Android 14+ skip the "single app" option: audio capture needs the whole device.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) askForCapture()
        else CastState.set(CastStatus.Failed(context.getString(R.string.need_audio_permission)))
    }

    fun startCasting() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) askForCapture() else permissionLauncher.launch(needed.toTypedArray())
    }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)

            StatusLine(status)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.speakers), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else OutlinedButton(onClick = ::search) { Text(stringResource(R.string.refresh)) }
            }

            if (!searching && groups.isEmpty()) {
                Text(
                    searchError ?: stringResource(R.string.no_speakers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups, key = { it.coordinatorUuid }) { group ->
                    val isSelected = group == selected
                    Card(
                        Modifier.fillMaxWidth().clickable { selected = group },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = isSelected, onClick = { selected = group })
                            Column {
                                Text(group.name, style = MaterialTheme.typography.titleMedium)
                                if (group.memberCount > 1) {
                                    Text(
                                        stringResource(R.string.group_of, group.memberCount),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            val current = selected
            if (current != null && volume >= 0f) {
                Text(stringResource(R.string.volume, volume.toInt()), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = {
                        val v = volume.toInt()
                        scope.launch(Dispatchers.IO) { runCatching { SonosClient().setGroupVolume(current.coordinatorHost, v) } }
                    },
                    valueRange = 0f..100f,
                )
            }

            val active = status is CastStatus.Casting || status is CastStatus.Starting
            if (active) {
                Button(onClick = { CastService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stop))
                }
            } else {
                Button(onClick = ::startCasting, enabled = current != null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.start))
                }
            }
            Text(
                stringResource(R.string.hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun StatusLine(status: CastStatus) {
    val (text, color) = when (status) {
        CastStatus.Idle -> stringResource(R.string.status_idle) to MaterialTheme.colorScheme.onSurfaceVariant
        is CastStatus.Starting -> stringResource(R.string.status_starting, status.group.name) to MaterialTheme.colorScheme.primary
        is CastStatus.Casting -> stringResource(R.string.status_casting, status.group.name) to MaterialTheme.colorScheme.primary
        is CastStatus.Failed -> status.message to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.titleSmall, color = color)
}
