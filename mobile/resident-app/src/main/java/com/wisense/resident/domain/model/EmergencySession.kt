package com.wisense.resident.domain.model

/**
 * Local state of an in-progress emergency on the resident phone (Phase 2 is
 * local capture only — no Firestore doc exists yet). Phase 4 will read
 * [cameraAvailable] and [startedAtMillis] when writing emergencies/{id}.
 */
data class EmergencySession(
    val active: Boolean,
    val startedAtMillis: Long = 0L,
    /** §7: false when camera hardware/permission is out — the rest proceeds. */
    val cameraAvailable: Boolean = true,
    /** §7: false when mic hardware/permission is out. */
    val micAvailable: Boolean = true,
) {
    companion object {
        val Inactive = EmergencySession(active = false)
    }
}
