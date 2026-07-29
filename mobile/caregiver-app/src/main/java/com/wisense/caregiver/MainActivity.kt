package com.wisense.caregiver

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.wisense.caregiver.data.SessionStore
import com.wisense.caregiver.presentation.AuthScreen
import com.wisense.caregiver.presentation.HouseLinkScreen
import com.wisense.caregiver.presentation.ViewerScreen
import com.wisense.shared.firebase.AuthClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                Surface {
                    CaregiverApp()
                }
            }
        }
    }
}

private enum class Screen { Auth, HouseLink, Viewer }

@Composable
private fun CaregiverApp() {
    val context = LocalContext.current
    val sessionStore = remember { SessionStore(context) }

    fun currentScreen() = when {
        AuthClient.currentUser == null -> Screen.Auth
        sessionStore.houseId == null -> Screen.HouseLink
        else -> Screen.Viewer
    }

    var screen by remember { mutableStateOf(currentScreen()) }

    when (screen) {
        Screen.Auth -> AuthScreen(onSignedIn = { screen = currentScreen() })
        Screen.HouseLink -> HouseLinkScreen(onLinked = { screen = currentScreen() })
        Screen.Viewer -> ViewerScreen(houseId = sessionStore.houseId!!)
    }
}
