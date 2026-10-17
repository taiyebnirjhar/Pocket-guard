// SOS recordings in R2. The phone asks for a signed upload link per piece and confirms it after
// uploading; the live page asks for a short-lived link to play one. Nobody gets a storage key.
//
// POST { action: "upload", incident_id, kind: "video" | "photo", bytes, content_type }  (signed in)
// POST { action: "done", media_id }                                                    (signed in)
// POST { action: "view", token, media_id }                     (anyone with the incident's token)

import { json, rest, userOf } from "../_shared/supabase.ts";
import { objectSize, presign, storageConfigured } from "../_shared/s3.ts";

const MAX_BYTES = 25 * 1024 * 1024; // One piece: a minute of 480p video is about 12 MB.
const MAX_PER_INCIDENT = 20;
const MAX_BYTES_PER_DAY = 600 * 1024 * 1024; // Per owner.
const UPLOAD_HOURS_AFTER_END = 6; // The last pieces can still be on their way.
const TYPES: Record<string, string> = { video: "video/mp4", photo: "image/jpeg" };
const EXT: Record<string, string> = { video: "mp4", photo: "jpg" };

// The live page calls "view" from the browser.
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "apikey, authorization, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function reply(body: unknown, status = 200): Response {
  const res = json(body, status);
  for (const [k, v] of Object.entries(CORS)) res.headers.set(k, v);
  return res;
}

const ID = /^[0-9a-f-]{36}$/;

async function upload(req: Request, body: Record<string, unknown>): Promise<Response> {
  const user = await userOf(req);
  if (!user) return reply({ error: "not signed in" }, 401);
  const incidentId = String(body.incident_id ?? "");
  const kind = String(body.kind ?? "");
  const bytes = Number(body.bytes);
  if (!ID.test(incidentId)) return reply({ error: "bad incident" }, 400);
  if (!(kind in TYPES) || body.content_type !== TYPES[kind]) return reply({ error: "bad kind" }, 400);
  if (!Number.isInteger(bytes) || bytes <= 0 || bytes > MAX_BYTES) return reply({ error: "bad size" }, 413);

  const after = new Date(Date.now() - UPLOAD_HOURS_AFTER_END * 3600 * 1000).toISOString();
  const incidents = await (await rest(
    `incidents?id=eq.${incidentId}&user_id=eq.${user.id}&or=(status.eq.active,ended_at.gt.${after})&select=id`,
  )).json();
  if (incidents.length !== 1) return reply({ error: "no such open incident" }, 404);

  const count = await (await rest(`media?incident_id=eq.${incidentId}&select=id`)).json();
  if (count.length >= MAX_PER_INCIDENT) return reply({ error: "too many for this incident" }, 429);
  const since = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
  const today = await (await rest(`media?user_id=eq.${user.id}&created_at=gt.${since}&select=bytes`)).json();
  const used = today.reduce((sum: number, r: { bytes: number }) => sum + r.bytes, 0);
  if (used + bytes > MAX_BYTES_PER_DAY) return reply({ error: "daily limit" }, 429);

  const key = `u/${user.id}/${incidentId}/${crypto.randomUUID()}.${EXT[kind]}`;
  const insert = await rest("media?select=id", {
    method: "POST",
    headers: { Prefer: "return=representation" },
    body: JSON.stringify({
      incident_id: incidentId,
      user_id: user.id,
      kind,
      object_key: key,
      content_type: TYPES[kind],
      bytes,
    }),
  });
  if (!insert.ok) return reply({ error: "could not record it" }, 500);
  const [row] = await insert.json();
  const headers = { "content-type": TYPES[kind], "content-length": String(bytes) };
  return reply({ media_id: row.id, url: await presign("PUT", key, 900, headers), headers });
}

async function done(req: Request, body: Record<string, unknown>): Promise<Response> {
  const user = await userOf(req);
  if (!user) return reply({ error: "not signed in" }, 401);
  const id = Number(body.media_id);
  if (!Number.isInteger(id)) return reply({ error: "bad media" }, 400);
  const rows = await (await rest(`media?id=eq.${id}&user_id=eq.${user.id}&select=object_key`)).json();
  if (rows.length !== 1) return reply({ error: "no such media" }, 404);
  const size = await objectSize(rows[0].object_key);
  if (size === null || size <= 0) return reply({ error: "not uploaded" }, 409);
  await rest(`media?id=eq.${id}`, {
    method: "PATCH",
    headers: { Prefer: "return=minimal" },
    body: JSON.stringify({ uploaded: true, bytes: size }),
  }).then((r) => r.body?.cancel());
  return reply({ uploaded: true });
}

async function view(body: Record<string, unknown>): Promise<Response> {
  const token = String(body.token ?? "");
  const id = Number(body.media_id);
  if (!/^[0-9a-f]{48}$/.test(token) || !Number.isInteger(id)) return reply({ error: "bad request" }, 400);
  // The same rule as the live page: until a day after the SOS ended.
  const after = new Date(Date.now() - 24 * 3600 * 1000).toISOString();
  const rows = await (await rest(
    `media?id=eq.${id}&uploaded=is.true&select=object_key,incidents!inner(viewer_token,ended_at)` +
      `&incidents.viewer_token=eq.${token}&incidents.or=(ended_at.is.null,ended_at.gt.${after})`,
  )).json();
  if (!Array.isArray(rows) || rows.length !== 1) return reply({ error: "not found" }, 404);
  return reply({ url: await presign("GET", rows[0].object_key, 600) });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  if (req.method !== "POST") return reply({ error: "POST only" }, 405);
  if (!storageConfigured()) return reply({ error: "storage not configured" }, 503);
  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return reply({ error: "bad json" }, 400);
  }
  switch (body.action) {
    case "upload":
      return await upload(req, body);
    case "done":
      return await done(req, body);
    case "view":
      return await view(body);
    default:
      return reply({ error: "bad action" }, 400);
  }
});
