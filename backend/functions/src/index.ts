import { logger } from "firebase-functions/v2";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { onDocumentCreated, onDocumentUpdated } from "firebase-functions/v2/firestore";

initializeApp();

async function caregiverTokens(houseId: string): Promise<string[]> {
  const db = getFirestore();
  const houseSnap = await db.collection("wisense_houses").doc(houseId).get();
  if (!houseSnap.exists) {
    logger.warn(`caregiverTokens: house ${houseId} does not exist`);
    return [];
  }

  const caregiverIds = (houseSnap.data()?.caregiverIds ?? []) as string[];
  logger.info(`caregiverTokens: house ${houseId} has caregiverIds=${JSON.stringify(caregiverIds)}`);
  if (caregiverIds.length === 0) return [];

  const userDocs = await db.getAll(
    ...caregiverIds.map((uid) => db.collection("wisense_users").doc(uid)),
  );
  const tokens = userDocs
    .flatMap((doc) => (doc.data()?.fcmTokens ?? []) as string[])
    .filter((token): token is string => Boolean(token));
  logger.info(`caregiverTokens: resolved ${tokens.length} token(s) for house ${houseId}`);
  return tokens;
}

/**
 * Fires the instant a resident writes a new wisense_emergencies/{id} doc.
 * Data message (not a notification message) so the caregiver app has full
 * control over the incoming-call-style full-screen UI — required either
 * way to wake and route into the live stream even if the app was killed.
 */
export const onEmergencyCreated = onDocumentCreated(
  "wisense_emergencies/{emergencyId}",
  async (event) => {
    const emergency = event.data?.data();
    const houseId = emergency?.houseId as string | undefined;
    logger.info(`onEmergencyCreated: emergency=${event.params.emergencyId} houseId=${houseId}`);
    if (!houseId) {
      logger.warn(`onEmergencyCreated: emergency ${event.params.emergencyId} has no houseId, skipping`);
      return;
    }

    const tokens = await caregiverTokens(houseId);
    if (tokens.length === 0) {
      logger.warn(`onEmergencyCreated: no caregiver tokens for house ${houseId}, not sending push`);
      return;
    }

    const response = await getMessaging().sendEachForMulticast({
      tokens,
      data: {
        type: "emergency_active",
        emergencyId: event.params.emergencyId,
        houseId,
      },
      android: { priority: "high" },
    });
    logger.info(
      `onEmergencyCreated: sent to ${tokens.length} token(s), ` +
        `successCount=${response.successCount} failureCount=${response.failureCount}`,
    );
    response.responses.forEach((r, i) => {
      if (!r.success) {
        logger.warn(`onEmergencyCreated: token[${i}] failed: ${r.error?.code} ${r.error?.message}`);
      }
    });
  },
);

/**
 * Fires when status transitions to "resolved" — pushes the all-clear and
 * cleans up the short-lived signaling subcollection now the call is over.
 */
export const onEmergencyResolved = onDocumentUpdated(
  "wisense_emergencies/{emergencyId}",
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after) return;
    if (before.status === after.status || after.status !== "resolved") return;

    const houseId = after.houseId as string | undefined;
    if (houseId) {
      const tokens = await caregiverTokens(houseId);
      if (tokens.length > 0) {
        await getMessaging().sendEachForMulticast({
          tokens,
          data: {
            type: "emergency_resolved",
            emergencyId: event.params.emergencyId,
            houseId,
          },
        });
      }
    }

    const db = getFirestore();
    const signalingDocs = await db
      .collection("wisense_emergencies")
      .doc(event.params.emergencyId)
      .collection("signaling")
      .listDocuments();
    await Promise.all(signalingDocs.map((doc) => doc.delete()));
  },
);
