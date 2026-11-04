package com.yearwall.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import com.yearwall.app.automation.Scheduler
import com.yearwall.app.ui.YearwallScreen
import com.yearwall.app.ui.theme.YearwallTheme

class MainActivity : ComponentActivity() {

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YearwallTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        topBar = { TopAppBar(title = { Text("Yearwall") }) }
                    ) { padding ->
                        Column(
                            modifier = Modifier
                                .padding(padding)
                                .fillMaxSize()
                        ) {
                            YearwallScreen()
                        }
                    }
                }
            }
        }
    }

    /*
     * Equivalent of /etc/profile.d yearwall-catchup (the third layer in the shell-script
     * original): if midnight fired while the device was neither booted-and-awake enough for
     * WorkManager to run the midnight job nor restarted for BootReceiver to fire, the app
     * being opened is the moment that catches up. ensure() collapses to a no-op whenever the
     * on-wall image is already today's, exactly like the profile script's date-vs-file check.
     */
    override fun onResume() {
        super.onResume()
        Scheduler.ensure(this, rebaseExisting = false)
    }
}
