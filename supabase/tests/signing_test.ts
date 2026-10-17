// R2 signing (AWS Signature V4) against reference values from the AWS SDK (botocore 1.35.99),
// computed for these fixed inputs. A match means R2 accepts the links, as it accepts the SDK's.

import { assertEquals } from "@std/assert";
import { presign, signedHeaders, type Store } from "../functions/_shared/s3.ts";

const store: Store = {
  endpoint: "https://acct.r2.cloudflarestorage.com",
  publicEndpoint: "https://acct.r2.cloudflarestorage.com",
  region: "auto",
  accessKey: "AKIDEXAMPLE",
  secretKey: "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
  bucket: "pocket-guard-media",
};
const now = new Date("2026-10-17T12:00:00Z");
const base = "https://acct.r2.cloudflarestorage.com/pocket-guard-media";
const query = (expires: number, signed: string) =>
  "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIDEXAMPLE%2F20261017%2Fauto%2Fs3%2Faws4_request" +
  `&X-Amz-Date=20261017T120000Z&X-Amz-Expires=${expires}&X-Amz-SignedHeaders=${signed}`;

Deno.test("a viewing link matches the AWS SDK's", async () => {
  assertEquals(
    await presign("GET", "u/a b/c.mp4", 600, {}, store, now),
    `${base}/u/a%20b/c.mp4?${query(600, "host")}` +
      "&X-Amz-Signature=b5babc818d36083fa97d8b97c8ffbd8c76adabbbdd36a3335c3876e571b2d126",
  );
});

Deno.test("an upload link signs its type and exact size, like the AWS SDK's", async () => {
  const headers = { "content-type": "video/mp4", "content-length": "4096" };
  assertEquals(
    await presign("PUT", "u/x/y.mp4", 900, headers, store, now),
    `${base}/u/x/y.mp4?${query(900, "content-length%3Bcontent-type%3Bhost")}` +
      "&X-Amz-Signature=568eb104bd2a350fdc0e903c5c1f9717b8c21592f258fc41f54d16c7047eaa0a",
  );
});

Deno.test("the function's own HEAD and DELETE are signed like the AWS SDK's", async () => {
  const scope =
    "Credential=AKIDEXAMPLE/20261017/auto/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date";
  const head = await signedHeaders("HEAD", "u/x/y.mp4", store, now);
  assertEquals(head.url, `${base}/u/x/y.mp4`);
  assertEquals(
    head.headers.Authorization,
    `AWS4-HMAC-SHA256 ${scope}, Signature=9adf1413ce36d4f5df68b75f6d0a17f4e1bc6d5d5ad1309b8ff03672cc36b5c0`,
  );
  const del = await signedHeaders("DELETE", "u/x/y.mp4", store, now);
  assertEquals(
    del.headers.Authorization,
    `AWS4-HMAC-SHA256 ${scope}, Signature=73a0b35d1ce3e7e0b96ba8c08aed8d0e546accb94a0f1be49514753d513bb9b8`,
  );
});
