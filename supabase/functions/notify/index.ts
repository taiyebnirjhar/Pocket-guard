// Emails the owner's emergency contacts when an SOS starts, when it was a false alarm, or as a
// test. The phone sends the addresses the owner approved; the wording is fixed here.
//
// POST { kind: "sos" | "false_alarm" | "test", incident_id?, emails: [...], name?, time?, lang? }
// with the owner's (anonymous) sign-in in Authorization.

import { buildEmail, cleanName, cleanRecipients, cleanTime, type Kind, type Lang } from "./emails.ts";
import { json, rest, rpc, userOf } from "../_shared/supabase.ts";

const BREVO_API_URL = Deno.env.get("BREVO_API_URL") || "https://api.brevo.com/v3";
const BREVO_API_KEY = Deno.env.get("BREVO_API_KEY") ?? "";
const SENDER = Deno.env.get("BREVO_SENDER_EMAIL") ?? "";
const LIVE_PAGE_URL = Deno.env.get("LIVE_PAGE_URL") ?? "";

// Per owner and day: all sends, and tests among them; different addresses per owner and month;
// emails one address gets a day from anyone; and all sends a day, below Brevo's free 300, so
// free anonymous accounts can't use the quota up and real alerts still go out.
const MAX_SENDS_PER_DAY = 10;
const MAX_TESTS_PER_DAY = 3;
const MAX_PER_ADDRESS_DAY = 6;
const MAX_ADDRESSES_MONTH = 10;
const MAX_ALL_DAY = Number(Deno.env.get("EMAIL_DAILY_CAP") || 250);

/** A one-way hash of an address, for the per-address limit (the address itself isn't stored). */
async function hashAddress(email: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("pocket-guard:" + email));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function sendOne(to: string, subject: string, text: string, html: string, tag: string): Promise<boolean> {
  const res = await fetch(`${BREVO_API_URL}/smtp/email`, {
    method: "POST",
    headers: { "api-key": BREVO_API_KEY, "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({
      sender: { name: "Pocket Guard", email: SENDER },
      to: [{ email: to }],
      subject,
      textContent: text,
      htmlContent: html,
      tags: [tag],
    }),
  });
  if (!res.ok) console.error(`brevo ${res.status}: ${(await res.text()).slice(0, 200)}`);
  else await res.body?.cancel();
  return res.ok;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  const user = await userOf(req);
  if (!user) return json({ error: "not signed in" }, 401);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return json({ error: "bad json" }, 400);
  }
  const kind = body.kind as Kind;
  if (!["sos", "false_alarm", "test"].includes(kind)) return json({ error: "bad kind" }, 400);
  const lang: Lang = body.lang === "bn" ? "bn" : "en";
  const emails = cleanRecipients(body.emails);
  if (emails.length === 0) return json({ error: "no valid addresses" }, 400);

  let name = cleanName(body.name);
  let link: string | null = null;
  let incidentId: string | null = null;
  if (kind !== "test") {
    incidentId = String(body.incident_id ?? "");
    if (!/^[0-9a-f-]{36}$/.test(incidentId)) return json({ error: "bad incident" }, 400);
    const res = await rest(
      `incidents?id=eq.${incidentId}&user_id=eq.${user.id}&select=owner_name,viewer_token`,
    );
    const rows = res.ok ? await res.json() : [];
    if (rows.length !== 1) return json({ error: "no such incident" }, 404);
    // The name the app stored; cleaned the same way as a test's.
    name = cleanName(rows[0].owner_name);
    if (kind === "sos" && LIVE_PAGE_URL) link = `${LIVE_PAGE_URL}#t=${rows[0].viewer_token}`;
  }
  if (!name) return json({ error: "no name" }, 400);

  // Claimed before sending, under one lock: parallel retries can't both send, and the limits hold.
  const hashes = await Promise.all(emails.map(hashAddress));
  const claim = { p_user: user.id, p_incident: incidentId, p_kind: kind, p_hashes: hashes };
  const refused = await rpc("claim_email", {
    ...claim,
    p_max_user_day: MAX_SENDS_PER_DAY,
    p_max_tests_day: MAX_TESTS_PER_DAY,
    p_max_address_day: MAX_PER_ADDRESS_DAY,
    p_max_addresses_month: MAX_ADDRESSES_MONTH,
    p_max_all_day: MAX_ALL_DAY,
  });
  if (refused === "already sent") return json({ sent: 0, already: true });
  if (refused) return json({ error: refused }, 429);

  const email = buildEmail(kind, lang, name, cleanTime(body.time), link);
  let sent = 0;
  for (const to of emails) {
    if (await sendOne(to, email.subject, email.text, email.html, kind)) sent++;
  }
  // Nothing went out: undo the claim so the phone's retry can go through.
  if (sent === 0) await rpc("release_email", claim);
  return json({ sent }, sent > 0 ? 200 : 502);
});
