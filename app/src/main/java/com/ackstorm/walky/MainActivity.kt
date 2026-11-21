package com.ackstorm.walky

import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)

    var settings: Settings? = store.load()
        private set

    val controller = Controller(
        recorder = MediaRecorderRecorder(app),
        uploader = HttpUploader(),
        takes = TakeStore(File(app.filesDir, "take")),
        loadSettings = { settings },
        scope = viewModelScope,
        io = Dispatchers.IO,
        now = SystemClock::elapsedRealtime,
    )

    fun save(updated: Settings) {
        store.save(updated)
        settings = updated
        controller.settingsChanged()
    }
}

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(model) }
    }

    // Locking the phone or leaving the app must never send a take.
    override fun onPause() {
        super.onPause()
        model.controller.interrupt()
    }
}

@Composable
private fun App(model: MainViewModel) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            val state by model.controller.state.collectAsState()
            var showSettings by rememberSaveable { mutableStateOf(model.settings == null) }
            if (showSettings) {
                BackHandler(enabled = model.settings != null) { showSettings = false }
                SettingsScreen(initial = model.settings) {
                    model.save(it)
                    showSettings = false
                }
            } else {
                RecordScreen(state, model.controller, onOpenSettings = { showSettings = true })
            }
        }
    }
}
