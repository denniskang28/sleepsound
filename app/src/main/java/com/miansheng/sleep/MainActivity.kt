package com.miansheng.sleep

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.lifecycle.ViewModelProvider
import com.miansheng.sleep.ui.*

class MainActivity : ComponentActivity() {
    private lateinit var vm: SleepViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        vm = ViewModelProvider(this)[SleepViewModel::class.java]
        setContent { SleepTheme { SleepApp(vm) } }
        handleIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("monitor", false) == true) vm.screen = Screen.MONITOR
        if (intent?.getBooleanExtra("confirmEnd", false) == true) { vm.screen = Screen.MONITOR; vm.sheet = "end" }
    }
    override fun onStop() { vm.pausePlayback(); super.onStop() }
}
