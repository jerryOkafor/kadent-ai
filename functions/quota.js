// How many model calls one signed-in person may make. The key is Kadent's, so every call is paid
// for by Kadent; the limits are what stops one account, or one stolen ID token, from spending it.
// A receipt is one call and a planned week a handful, so a household doing its shopping never
// comes near them.

/** The limits the function runs with. One person, not one household: the quota follows the account. */
export const LIMITS = { perMinute: 20, perDay: 300 };

const MINUTE = 60_000;
const DAY = 86_400_000;

/**
 * The quota after one more call at `now`, from the stored value (`null` before a person's first
 * call). A refused call returns the stored value unchanged, so it costs nothing.
 *
 * Minutes and days are whole UTC buckets, not sliding windows: one number per bucket is all the
 * database has to hold, and a person refused at 23:59 waits a minute, which is fine.
 */
export function nextQuota(current, now, limits) {
  const minute = Math.floor(now / MINUTE);
  const day = Math.floor(now / DAY);
  const minuteCount = current?.minute === minute ? current.minuteCount : 0;
  const dayCount = current?.day === day ? current.dayCount : 0;

  if (dayCount >= limits.perDay) {
    return { allowed: false, retryAfterSeconds: Math.ceil(((day + 1) * DAY - now) / 1000), value: current };
  }
  if (minuteCount >= limits.perMinute) {
    return { allowed: false, retryAfterSeconds: Math.ceil(((minute + 1) * MINUTE - now) / 1000), value: current };
  }
  return { allowed: true, value: { minute, minuteCount: minuteCount + 1, day, dayCount: dayCount + 1 } };
}

/**
 * Takes one call from `uid`'s quota in the Realtime Database, at `aiQuota/{uid}`. A transaction,
 * because two calls from the same person can arrive together on two function instances.
 *
 * Kadent's database rules name no `aiQuota` path, and the rules refuse whatever they do not name,
 * so no phone can read or reset its own count; only this function, through the Admin SDK, can.
 */
export async function takeQuota(db, uid, now, limits = LIMITS) {
  let outcome;
  await db.ref(`aiQuota/${uid}`).transaction((current) => {
    outcome = nextQuota(current, now, limits);
    // Returning undefined aborts the transaction, so a refused call writes nothing.
    return outcome.allowed ? outcome.value : undefined;
  });
  return { allowed: outcome.allowed, retryAfterSeconds: outcome.retryAfterSeconds };
}
