// Checks a deployed backend end to end with a throwaway anonymous account, then deletes it.
// Sends no email (the notify call uses an address it must refuse).
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

const signup = await call("/auth/v1/signup", null, {});
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

const del = await call("/functions/v1/delete-account", token, {});
check(del.status === 200, "delete the account", del);

const gone = await call("/rest/v1/rpc/view_incident", null, { token: viewer_token });
check(gone.status === 200 && gone.body === null, "its live page is gone", gone);
