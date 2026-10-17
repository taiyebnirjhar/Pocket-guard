// Emails the owner's emergency contacts when an SOS starts, when it was a false alarm, or as a
// test. The phone sends the addresses the owner approved; the wording is fixed here.
//
// POST { kind: "sos" | "false_alarm" | "test", incident_id?, emails: [...], name?, time?, lang? }
// with the owner's (anonymous) sign-in in Authorization.

import { buildEmail, cleanName, cleanRecipients, cleanTime, type Kind, type Lang } from "./emails.ts";
import { json, rest, userOf } from "../_shared/supabase.ts";

const BREVO_API_URL = Deno.env.get("BREVO_API_URL") || "https://api.brevo.com/v3";
const BREVO_API_KEY = Deno.env.get("BREVO_API_KEY") ?? "";
const SENDER = Deno.env.get("BREVO_SENDER_EMAIL") ?? "";
const LIVE_PAGE_URL = Deno.env.get("LIVE_PAGE_URL") ?? "";

// Per owner and day: all sends, and tests among them.
const MAX_SENDS_PER_DAY = 10;
const MAX_TESTS_PER_DAY = 3;

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
    name = rows[0].owner_name;
    if (kind === "sos" && LIVE_PAGE_URL) link = `${LIVE_PAGE_URL}#t=${rows[0].viewer_token}`;
    // Once per incident and kind, so retries from the phone can't repeat it.
    const sent = await (await rest(`email_log?incident_id=eq.${incidentId}&kind=eq.${kind}&select=id`)).json();
    if (sent.length > 0) return json({ sent: 0, already: true });
  }
  if (!name) return json({ error: "no name" }, 400);

  const since = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
  const log = await (await rest(`email_log?user_id=eq.${user.id}&sent_at=gt.${since}&select=kind`)).json();
  if (log.length >= MAX_SENDS_PER_DAY) return json({ error: "daily limit" }, 429);
  if (kind === "test" && log.filter((r: { kind: string }) => r.kind === "test").length >= MAX_TESTS_PER_DAY) {
    return json({ error: "daily test limit" }, 429);
  }

  const email = buildEmail(kind, lang, name, cleanTime(body.time), link);
  let sent = 0;
  for (const to of emails) {
    if (await sendOne(to, email.subject, email.text, email.html, kind)) sent++;
  }
  // Logged only when something went out, so the phone can retry a failed send.
  if (sent > 0) {
    await rest("email_log", {
      method: "POST",
      headers: { Prefer: "return=minimal" },
      body: JSON.stringify({ user_id: user.id, incident_id: incidentId, kind, recipients: sent }),
    }).then((r) => r.body?.cancel());
  }
  return json({ sent }, sent > 0 ? 200 : 502);
});
