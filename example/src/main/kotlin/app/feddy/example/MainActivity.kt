package app.feddy.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.feddy.sdk.Feddy
import app.feddy.sdk.FeddyUnreadDot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    var popups by remember { mutableStateOf(Feddy.replyPopupsEnabled) }
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Feddy Example", style = MaterialTheme.typography.headlineMedium)
                        Button(onClick = { Feddy.present(context) }, modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Help & support")
                                Spacer(Modifier.width(8.dp))
                                FeddyUnreadDot(showsCount = true)
                            }
                        }
                        OutlinedButton(onClick = { Feddy.presentNewConversation(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text("New message")
                        }
                        OutlinedButton(
                            onClick = {
                                Feddy.identify(
                                    userId = "example-user",
                                    name = "Example User",
                                    attributes = mapOf("plan" to "pro", "sessions" to 12),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Identify a test user") }
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(context, PlainActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open a plain (non-Compose) activity") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reply popups", Modifier.weight(1f))
                            Switch(checked = popups, onCheckedChange = {
                                popups = it
                                Feddy.replyPopupsEnabled = it
                            })
                        }
                    }
                }
            }
        }
    }
}
