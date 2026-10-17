// Cloudflare R2 through its S3 API, signed with AWS Signature V4 (no client library). The phone
// and the live page never get keys: they get links signed here that work for a few minutes.

export interface Store {
  /** Where the function itself reaches the store. */
  endpoint: string;
  /** What phones and browsers reach (tests run the store on another address than the function sees). */
  publicEndpoint: string;
  region: string;
  accessKey: string;
  secretKey: string;
  bucket: string;
}

const env = (name: string) => (Deno.env.get(name) ?? "").replace(/\/$/, "");
const STORE: Store = {
  endpoint: env("R2_ENDPOINT"),
  publicEndpoint: env("R2_PUBLIC_ENDPOINT") || env("R2_ENDPOINT"),
  region: env("R2_REGION") || "auto",
  accessKey: env("R2_ACCESS_KEY_ID"),
  secretKey: env("R2_SECRET_ACCESS_KEY"),
  bucket: env("R2_BUCKET"),
};

export const storageConfigured = () => Boolean(STORE.endpoint && STORE.accessKey && STORE.secretKey && STORE.bucket);

const enc = new TextEncoder();

async function hmac(key: ArrayBuffer | Uint8Array, data: string): Promise<ArrayBuffer> {
  const raw = key instanceof Uint8Array ? key.slice().buffer : key;
  const k = await crypto.subtle.importKey("raw", raw, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return await crypto.subtle.sign("HMAC", k, enc.encode(data));
}

async function sha256Hex(data: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", enc.encode(data)));
}

function hex(buf: ArrayBuffer): string {
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** RFC 3986 encoding, as SigV4 wants it ("/" kept in paths). */
function uriEncode(s: string, keepSlash = false): string {
  return [...enc.encode(s)].map((b) => {
    const c = String.fromCharCode(b);
    if (/[A-Za-z0-9\-._~]/.test(c) || (keepSlash && c === "/")) return c;
    return "%" + b.toString(16).toUpperCase().padStart(2, "0");
  }).join("");
}

function stamps(now: Date) {
  const amzDate = now.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
  return { amzDate, day: amzDate.slice(0, 8) };
}

async function signature(store: Store, day: string, stringToSign: string): Promise<string> {
  let key = await hmac(enc.encode("AWS4" + store.secretKey), day);
  key = await hmac(key, store.region);
  key = await hmac(key, "s3");
  key = await hmac(key, "aws4_request");
  return hex(await hmac(key, stringToSign));
}

function canonicalQuery(params: Record<string, string>): string {
  return Object.keys(params).sort().map((k) => `${uriEncode(k)}=${uriEncode(params[k])}`).join("&");
}

/**
 * A link that does [method] on [key] without keys, for [expiresSec]. [headers] are signed too, so
 * the caller must send them exactly (a signed content-length caps the upload's size).
 */
export async function presign(
  method: "GET" | "PUT",
  key: string,
  expiresSec: number,
  headers: Record<string, string> = {},
  store: Store = STORE,
  now: Date = new Date(),
): Promise<string> {
  const base = new URL(store.publicEndpoint);
  const path = `/${store.bucket}/${uriEncode(key, true)}`;
  const { amzDate, day } = stamps(now);
  const scope = `${day}/${store.region}/s3/aws4_request`;
  const all: Record<string, string> = { host: base.host };
  for (const [k, v] of Object.entries(headers)) all[k.toLowerCase()] = v.trim();
  const names = Object.keys(all).sort();
  const params: Record<string, string> = {
    "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
    "X-Amz-Credential": `${store.accessKey}/${scope}`,
    "X-Amz-Date": amzDate,
    "X-Amz-Expires": String(expiresSec),
    "X-Amz-SignedHeaders": names.join(";"),
  };
  const query = canonicalQuery(params);
  const canonical = [
    method,
    path,
    query,
    names.map((n) => `${n}:${all[n]}\n`).join(""),
    names.join(";"),
    "UNSIGNED-PAYLOAD",
  ].join("\n");
  const toSign = ["AWS4-HMAC-SHA256", amzDate, scope, await sha256Hex(canonical)].join("\n");
  return `${base.origin}${path}?${query}&X-Amz-Signature=${await signature(store, day, toSign)}`;
}

// The hash of an empty body, as the SDKs sign requests without one.
const EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

/** The headers that sign a request without a body (HEAD and DELETE on one object). */
export async function signedHeaders(
  method: "HEAD" | "DELETE" | "GET",
  key: string,
  store: Store = STORE,
  now: Date = new Date(),
  params: Record<string, string> = {},
): Promise<{ url: string; headers: Record<string, string> }> {
  const base = new URL(store.endpoint);
  const path = key ? `/${store.bucket}/${uriEncode(key, true)}` : `/${store.bucket}`;
  const query = canonicalQuery(params);
  const { amzDate, day } = stamps(now);
  const scope = `${day}/${store.region}/s3/aws4_request`;
  const headers: Record<string, string> = {
    host: base.host,
    "x-amz-content-sha256": EMPTY_SHA256,
    "x-amz-date": amzDate,
  };
  const names = Object.keys(headers).sort();
  const canonical = [
    method,
    path,
    query,
    names.map((n) => `${n}:${headers[n]}\n`).join(""),
    names.join(";"),
    EMPTY_SHA256,
  ].join("\n");
  const toSign = ["AWS4-HMAC-SHA256", amzDate, scope, await sha256Hex(canonical)].join("\n");
  const sig = await signature(store, day, toSign);
  const auth = `AWS4-HMAC-SHA256 Credential=${store.accessKey}/${scope}, SignedHeaders=${
    names.join(";")
  }, Signature=${sig}`;
  const { host: _host, ...sent } = headers;
  return { url: `${base.origin}${path}${query ? "?" + query : ""}`, headers: { ...sent, Authorization: auth } };
}

async function request(method: "HEAD" | "DELETE", key: string): Promise<Response> {
  const { url, headers } = await signedHeaders(method, key);
  return await fetch(url, { method, headers });
}

/** The stored size of [key], or null when it isn't there. */
export async function objectSize(key: string): Promise<number | null> {
  const res = await request("HEAD", key);
  await res.body?.cancel();
  return res.ok ? Number(res.headers.get("content-length") ?? 0) : null;
}

export async function deleteObject(key: string): Promise<boolean> {
  const res = await request("DELETE", key);
  await res.body?.cancel();
  return res.ok || res.status === 404;
}

/** Every key under [prefix] (ListObjectsV2, a page at a time), or null when listing failed. */
export async function listKeys(prefix: string): Promise<string[] | null> {
  const keys: string[] = [];
  let token: string | undefined;
  for (let page = 0; page < 100; page++) {
    const params: Record<string, string> = { "list-type": "2", prefix };
    if (token) params["continuation-token"] = token;
    const { url, headers } = await signedHeaders("GET", "", STORE, new Date(), params);
    const res = await fetch(url, { headers });
    if (!res.ok) {
      await res.body?.cancel();
      return null;
    }
    const xml = await res.text();
    for (const m of xml.matchAll(/<Key>([^<]*)<\/Key>/g)) keys.push(unescapeXml(m[1]));
    const next = xml.match(/<NextContinuationToken>([^<]*)<\/NextContinuationToken>/);
    if (!/<IsTruncated>true<\/IsTruncated>/.test(xml) || !next) return keys;
    token = unescapeXml(next[1]);
  }
  return keys;
}

function unescapeXml(s: string): string {
  return s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(
    /&amp;/g,
    "&",
  );
}
