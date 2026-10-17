// Tests the database rules and the functions against a local stack (`supabase start`), with a
// stand-in for Brevo on port 9999 that records the emails.
//
//   cd supabase && API_URL=http://127.0.0.1:54321 PUBLISHABLE_KEY=... deno test --allow-net --allow-env tests/
//
// The functions must see BREVO_API_URL=http://host.docker.internal:9999/v3 (see .github/workflows/ci.yml).

import { assert, assertEquals } from "@std/assert";

const API = Deno.env.get("API_URL") ?? "http://127.0.0.1:54321";
const KEY = Deno.env.get("PUBLISHABLE_KEY")!;

const mails: Array<{ to: string; subject: string; text: string }> = [];
const brevo = Deno.serve({ port: 9999, onListen() {} }, async (req) => {
  const body = await req.json();
  if (req.headers.get("api-key") !== "test") return new Response("bad key", { status: 401 });
  mails.push({ to: body.to[0].email, subject: body.subject, text: body.textContent });
  return Response.json({ messageId: "x" }, { status: 201 });
});

/** PostgREST answers "permission denied" with 401 for anon and 403 for signed-in users. */
function assertDenied(status: number, body: string) {
  assert(status === 401 || status === 403, `expected permission denied, got ${status}: ${body}`);
}

async function signIn(): Promise<string> {
  const res = await fetch(`${API}/auth/v1/signup`, {
    method: "POST",
    headers: { apikey: KEY, "Content-Type": "application/json" },
    body: "{}",
  });
  assertEquals(res.status, 200, await res.clone().text());
  return (await res.json()).access_token;
}

function call(path: string, token: string | null, body?: unknown, method = "POST", extra: Record<string, string> = {}) {
  return fetch(`${API}${path}`, {
    method,
    headers: {
      apikey: KEY,
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...extra,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function openIncident(token: string, name = "Taiyeb") {
  const res = await call(
    "/rest/v1/incidents?select=id,viewer_token",
    token,
    { owner_name: name, battery: 80 },
    "POST",
    {
      Prefer: "return=representation",
    },
  );
  assertEquals(res.status, 201, await res.clone().text());
  return (await res.json())[0] as { id: string; viewer_token: string };
}

Deno.test("an anonymous owner opens an incident, uploads its trail, and the live page sees it", async () => {
  const t = await signIn();
  const inc = await openIncident(t);
  assertEquals(inc.viewer_token.length, 48);

  const pts = [
    { incident_id: inc.id, lat: 23.81, lng: 90.41, accuracy: 8 },
    { incident_id: inc.id, lat: 23.82, lng: 90.42, accuracy: 6 },
  ];
  const up = await call("/rest/v1/locations", t, pts, "POST", { Prefer: "return=minimal" });
  assertEquals(up.status, 201, await up.text());

  const view = await (await call("/rest/v1/rpc/view_incident", null, { token: inc.viewer_token })).json();
  assertEquals(view.name, "Taiyeb");
  assertEquals(view.status, "active");
  assertEquals(view.battery, 80);
  assertEquals(view.points.length, 2);
  assertEquals(view.points[1][0], 23.82);

  const wrong = await (await call("/rest/v1/rpc/view_incident", null, { token: "0".repeat(48) })).json();
  assertEquals(wrong, null);
});

Deno.test("nobody else can read or write an owner's data", async () => {
  const owner = await signIn();
  const other = await signIn();
  const inc = await openIncident(owner);

  // Anonymous visitors without a sign-in see no tables at all.
  for (const table of ["incidents", "locations", "email_log"]) {
    const res = await call(`/rest/v1/${table}?select=*`, null, undefined, "GET");
    await res.body?.cancel();
    assert(res.status === 401 || res.status === 403, `${table} without sign-in: ${res.status}`);
  }
  // Another user sees none of the owner's rows and can't add to their incident.
  const rows = await (await call(`/rest/v1/incidents?select=id`, other, undefined, "GET")).json();
  assertEquals(rows, []);
  const add = await call("/rest/v1/locations", other, { incident_id: inc.id, lat: 1, lng: 1 });
  assertEquals(add.status, 403, await add.text());
  const close = await call(`/rest/v1/incidents?id=eq.${inc.id}`, other, { status: "ended" }, "PATCH", {
    Prefer: "return=representation",
  });
  assertEquals(await close.json(), []);
  // The owner can't pick their own token or change it later.
  const pick = await call("/rest/v1/incidents", owner, { owner_name: "x", viewer_token: "a".repeat(48) });
  assertDenied(pick.status, await pick.text());
  const change = await call(`/rest/v1/incidents?id=eq.${inc.id}`, owner, { viewer_token: "b".repeat(48) }, "PATCH");
  assertDenied(change.status, await change.text());
  // The email log is never visible to the app.
  const log = await call("/rest/v1/email_log?select=*", owner, undefined, "GET");
  assertDenied(log.status, await log.text());
});

Deno.test("after the owner ends it, late points still land for a minute and the page shows the end", async () => {
  const t = await signIn();
  const inc = await openIncident(t);
  const end = await call(`/rest/v1/incidents?id=eq.${inc.id}`, t, {
    status: "false_alarm",
    ended_at: new Date().toISOString(),
  }, "PATCH");
  assertEquals(end.status, 204, await end.text());
  const late = await call("/rest/v1/locations", t, { incident_id: inc.id, lat: 23.8, lng: 90.4 });
  assertEquals(late.status, 201, await late.text());
  const view = await (await call("/rest/v1/rpc/view_incident", null, { token: inc.viewer_token })).json();
  assertEquals(view.status, "false_alarm");
  assert(view.ended_at);
});

Deno.test("SOS email goes to the approved addresses once, with the live link", async () => {
  mails.length = 0;
  const t = await signIn();
  const inc = await openIncident(t, "Taiyeb <b>");
  const req = {
    kind: "sos",
    incident_id: inc.id,
    emails: ["Mom@Example.com", "mom@example.com", "not-an-email", "dad@example.org"],
    time: "14:05",
  };
  const res = await call("/functions/v1/notify", t, req);
  assertEquals(res.status, 200, await res.clone().text());
  assertEquals((await res.json()).sent, 2);
  assertEquals(mails.map((m) => m.to).sort(), ["dad@example.org", "mom@example.com"]);
  assertEquals(mails[0].subject, "Taiyeb <b> needs help (SOS)");
  assert(mails[0].text.includes(`#t=${inc.viewer_token}`), mails[0].text);
  assert(mails[0].text.includes("14:05"));

  // A retry from the phone sends nothing more.
  const again = await (await call("/functions/v1/notify", t, req)).json();
  assertEquals(again.already, true);
  assertEquals(mails.length, 2);

  // The false alarm follows, without a link.
  const fa = await call("/functions/v1/notify", t, {
    kind: "false_alarm",
    incident_id: inc.id,
    emails: ["mom@example.com"],
  });
  assertEquals(fa.status, 200, await fa.text());
  assertEquals(mails.length, 3);
  assert(!mails[2].text.includes("#t="));
});

Deno.test("notify refuses strangers, other people's incidents, and too many tests", async () => {
  const t = await signIn();
  const other = await signIn();
  const inc = await openIncident(t);

  const noAuth = await call("/functions/v1/notify", null, { kind: "test", emails: ["a@b.co"], name: "X" });
  assertEquals(noAuth.status, 401, await noAuth.text());
  const notMine = await call("/functions/v1/notify", other, { kind: "sos", incident_id: inc.id, emails: ["a@b.co"] });
  assertEquals(notMine.status, 404, await notMine.text());
  const noEmails = await call("/functions/v1/notify", t, { kind: "test", emails: ["nope"], name: "X" });
  assertEquals(noEmails.status, 400, await noEmails.text());

  const codes = [];
  for (let i = 0; i < 4; i++) {
    const r = await call("/functions/v1/notify", t, { kind: "test", emails: ["a@b.co"], name: "Taiyeb", lang: "bn" });
    codes.push(r.status);
    await r.body?.cancel();
  }
  assertEquals(codes, [200, 200, 200, 429]);
});

Deno.test("deleting the account removes the incidents and the live page", async () => {
  const t = await signIn();
  const inc = await openIncident(t);
  const del = await call("/functions/v1/delete-account", t, {});
  assertEquals(del.status, 200, await del.text());
  const view = await (await call("/rest/v1/rpc/view_incident", null, { token: inc.viewer_token })).json();
  assertEquals(view, null);
});

globalThis.addEventListener("unload", () => brevo.shutdown());

// --- Recordings (needs a stand-in for R2 on port 9000: s3rver, see .github/workflows/ci.yml) -----

async function media(token: string | null, body: Record<string, unknown>) {
  const res = await call("/functions/v1/media", token, body);
  return { status: res.status, body: await res.json() };
}

Deno.test("a recording goes up through a signed link and plays from the live page", async () => {
  const t = await signIn();
  const inc = await openIncident(t);
  const video = new Uint8Array(4096).map((_, i) => i % 251);

  const up = await media(t, {
    action: "upload",
    incident_id: inc.id,
    kind: "video",
    bytes: video.length,
    content_type: "video/mp4",
  });
  assertEquals(up.status, 200, JSON.stringify(up.body));
  // Not listed until confirmed.
  const before = await (await call("/rest/v1/rpc/view_incident", null, { token: inc.viewer_token })).json();
  assertEquals(before.media, []);

  // (That the link takes only the announced size, and stops working when changed, is R2's part:
  // the stand-in doesn't check signatures, so signing_test.ts checks them against the AWS SDK.)
  const put = await fetch(up.body.url, { method: "PUT", headers: up.body.headers, body: video });
  assert(put.ok, `upload: ${put.status} ${await put.text()}`);

  const done = await media(t, { action: "done", media_id: up.body.media_id });
  assertEquals(done.status, 200, JSON.stringify(done.body));
  const after = await (await call("/rest/v1/rpc/view_incident", null, { token: inc.viewer_token })).json();
  assertEquals(after.media.length, 1);
  assertEquals(after.media[0][1], "video");

  const view = await media(null, { action: "view", token: inc.viewer_token, media_id: after.media[0][0] });
  assertEquals(view.status, 200, JSON.stringify(view.body));
  const got = new Uint8Array(await (await fetch(view.body.url)).arrayBuffer());
  assertEquals(got, video);

  // A wrong token gets nothing.
  const wrong = await media(null, { action: "view", token: "0".repeat(48), media_id: after.media[0][0] });
  assertEquals(wrong.status, 404);
});

Deno.test("uploads are refused for other people's incidents, wrong types and oversize pieces", async () => {
  const t = await signIn();
  const other = await signIn();
  const inc = await openIncident(t);
  const ok = { action: "upload", incident_id: inc.id, kind: "video", bytes: 100, content_type: "video/mp4" };
  assertEquals((await media(null, ok)).status, 401);
  assertEquals((await media(other, ok)).status, 404);
  assertEquals((await media(t, { ...ok, content_type: "text/html" })).status, 400);
  assertEquals((await media(t, { ...ok, bytes: 26 * 1024 * 1024 })).status, 413);
  const notUp = await media(t, ok);
  assertEquals((await media(t, { action: "done", media_id: notUp.body.media_id })).status, 409);
  assertEquals((await media(other, { action: "done", media_id: notUp.body.media_id })).status, 404);
});

Deno.test("deleting the account removes its recordings from storage", async () => {
  const t = await signIn();
  const inc = await openIncident(t);
  const up = await media(t, {
    action: "upload",
    incident_id: inc.id,
    kind: "video",
    bytes: 10,
    content_type: "video/mp4",
  });
  await (await fetch(up.body.url, { method: "PUT", headers: up.body.headers, body: new Uint8Array(10) })).body
    ?.cancel();
  await media(t, { action: "done", media_id: up.body.media_id });
  const before = await (await call("/rest/v1/rpc/view_incident", null, { token: inc.viewer_token })).json();
  const view = await media(null, { action: "view", token: inc.viewer_token, media_id: before.media[0][0] });
  const link = view.body.url as string;
  assertEquals(await fetch(link).then((r) => (r.body?.cancel(), r.status)), 200);

  assertEquals((await call("/functions/v1/delete-account", t, {})).status, 200);
  const gone = await fetch(link);
  await gone.body?.cancel();
  assertEquals(gone.status, 404);
});
