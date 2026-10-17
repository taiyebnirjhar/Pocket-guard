// Small helpers for talking to this project's own API from a function, without a client library.

const url = Deno.env.get("SUPABASE_URL")!;

/** A key from the JSON map the platform provides (new keys), or the legacy JWT variable. */
function key(jsonVar: string, legacyVar: string): string {
  const map = Deno.env.get(jsonVar);
  if (map) {
    try {
      const keys = JSON.parse(map) as Record<string, string>;
      const first = keys.default ?? Object.values(keys)[0];
      if (first) return first;
    } catch { /* fall through to the legacy key */ }
  }
  return Deno.env.get(legacyVar) ?? "";
}

const publicKey = key("SUPABASE_PUBLISHABLE_KEYS", "SUPABASE_ANON_KEY");
const serviceKey = key("SUPABASE_SECRET_KEYS", "SUPABASE_SERVICE_ROLE_KEY");

/** Headers acting as the service: new sb_secret keys go in apikey alone, legacy JWTs in both. */
function serviceHeaders(): Record<string, string> {
  return serviceKey.startsWith("sb_")
    ? { apikey: serviceKey }
    : { apikey: serviceKey, Authorization: `Bearer ${serviceKey}` };
}

/** The signed-in user (anonymous ones included) behind the request, or null. */
export async function userOf(req: Request): Promise<{ id: string } | null> {
  const auth = req.headers.get("Authorization") ?? "";
  if (!auth.startsWith("Bearer ")) return null;
  const res = await fetch(`${url}/auth/v1/user`, { headers: { apikey: publicKey, Authorization: auth } });
  if (!res.ok) return null;
  const user = await res.json();
  return typeof user?.id === "string" ? { id: user.id } : null;
}

/** PostgREST as the service (bypasses row-level security; every query filters by user). */
export async function rest(path: string, init: RequestInit = {}): Promise<Response> {
  return await fetch(`${url}/rest/v1/${path}`, {
    ...init,
    headers: { ...serviceHeaders(), "Content-Type": "application/json", ...(init.headers ?? {}) },
  });
}

export async function deleteUser(id: string): Promise<boolean> {
  const res = await fetch(`${url}/auth/v1/admin/users/${id}`, { method: "DELETE", headers: serviceHeaders() });
  await res.body?.cancel();
  return res.ok;
}

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
