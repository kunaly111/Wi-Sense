package com.wisense.caregiver.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wisense.caregiver.data.SessionStore
import com.wisense.caregiver.data.ensureCaregiverCode

/**
 * Shown once, right after sign-in, before a house is linked. The resident
 * adds this caregiver's 6-digit code to their house's caregiverIds
 * (Settings screen on their app) — this screen is where that code is shown
 * for sharing, and where the house code they're given in return gets
 * entered and saved.
 */
@Composable
fun HouseLinkScreen(onLinked: (String) -> Unit) {
    val context = LocalContext.current
    val sessionStore = remember { SessionStore(context) }
    var houseCodeInput by remember { mutableStateOf("") }
    var caregiverCode by remember { mutableStateOf<String?>(null) }
    var caregiverCodeError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            caregiverCode = ensureCaregiverCode()
        } catch (e: Exception) {
            caregiverCodeError = e.message ?: e.toString()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Link to a house", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("Your caregiver code (share this with the house owner):")
        Spacer(Modifier.height(4.dp))
        Text(
            caregiverCode ?: caregiverCodeError?.let { "Error: $it" } ?: "Loading…",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(24.dp))

        Text("Once they've added you, enter the house code they give you:")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = houseCodeInput,
            onValueChange = { houseCodeInput = it },
            label = { Text("House code") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val id = houseCodeInput.trim()
                sessionStore.houseId = id
                onLinked(id)
            },
            enabled = houseCodeInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
    }
}
