// Checks a deployed backend end to end with a throwaway anonymous account, then deletes it, with
// a 16-byte recording uploaded to R2 and removed again. Sends no email (the notify call uses an
// address it must refuse).
//
//   cd supabase && API_URL=https://<ref>.supabase.co PUBLISHABLE_KEY=... deno run --allow-net --allow-env tests/smoke.ts

const API = Deno.env.get("API_URL")!.replace(/\/$/, "");
const KEY = Deno.env.get("PUBLISHABLE_KEY")!;

async function call(path: string, token: string | null, body: unknown, extra: Record<string, string> = {}) {
  const res = await fetch(`${API}${path}`, {
    method: "POST",
    headers: {
      apikey: KEY,
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...extra,
    },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

function check(ok: boolean, what: string, detail: unknown = "") {
  console.log(`${ok ? "✅" : "❌"} ${what}`);
  if (!ok) {
    console.log(detail);
    Deno.exit(1);
  }
}

// Right after a deploy changes the sign-in settings, they take a moment to apply.
let signup = await call("/auth/v1/signup", null, {});
for (let i = 0; i < 18 && signup.body?.error_code === "anonymous_provider_disabled"; i++) {
  await new Promise((r) => setTimeout(r, 5000));
  signup = await call("/auth/v1/signup", null, {});
}
check(signup.status === 200, "anonymous sign-in", signup);
const token = signup.body.access_token;

const inc = await call("/rest/v1/incidents?select=id,viewer_token", token, { owner_name: "Smoke test", battery: 50 }, {
  Prefer: "return=representation",
});
check(inc.status === 201, "open an incident", inc);
const { id, viewer_token } = inc.body[0];

const point = await call("/rest/v1/locations", token, [
  { incident_id: id, lat: 23.81, lng: 90.41, accuracy: 10, recorded_at: new Date().toISOString() },
]);
check(point.status === 201, "upload a location", point);

const view = await call("/rest/v1/rpc/view_incident", null, { token: viewer_token });
check(view.status === 200 && view.body?.points?.length === 1, "the live page reads it without signing in", view);

const notify = await call("/functions/v1/notify", token, { kind: "test", emails: ["not-an-address"], name: "Smoke" });
check(notify.status === 400, "notify is deployed and checks the sign-in (refused a bad address)", notify);

const bytes = new TextEncoder().encode("pocket guard ok\n");
const up = await call("/functions/v1/media", token, {
  action: "upload",
  incident_id: id,
  kind: "video",
  bytes: bytes.length,
  content_type: "video/mp4",
});
check(up.status === 200, "get a signed upload link", up);
const put = await fetch(up.body.url, { method: "PUT", headers: up.body.headers, body: bytes });
check(put.ok, "upload to R2 through it", { status: put.status, body: await put.text() });
const done = await call("/functions/v1/media", token, { action: "done", media_id: up.body.media_id });
check(done.status === 200, "the function finds the file in R2", done);
const listed = await call("/rest/v1/rpc/view_incident", null, { token: viewer_token });
const mediaId = listed.body?.media?.[0]?.[0];
const play = await call("/functions/v1/media", null, { action: "view", token: viewer_token, media_id: mediaId });
check(play.status === 200, "the live page gets a link to play it", play);
const played = new Uint8Array(await (await fetch(play.body.url)).arrayBuffer());
check(new TextDecoder().decode(played) === "pocket guard ok\n", "the link plays the same file", played.length);

const del = await call("/functions/v1/delete-account", token, {});
check(del.status === 200, "delete the account", del);
const after = await fetch(play.body.url);
await after.body?.cancel();
check(after.status === 404, "its recording is gone from R2", after.status);

const gone = await call("/rest/v1/rpc/view_incident", null, { token: viewer_token });
check(gone.status === 200 && gone.body === null, "its live page is gone", gone);
