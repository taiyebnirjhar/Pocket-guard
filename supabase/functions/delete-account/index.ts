// Deletes the caller's account. Every file under their folder in R2 goes first (confirmed
// recordings and uploads that were never confirmed alike); then the account, and with it their
// incidents, locations, recordings' rows and email log (the tables cascade from it). If any file
// can't be removed, nothing more is deleted and the phone tries again later.
//
// POST with the owner's (anonymous) sign-in in Authorization.

import { deleteUser, json, userOf } from "../_shared/supabase.ts";
import { deleteObject, listKeys, storageConfigured } from "../_shared/s3.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  const user = await userOf(req);
  if (!user) return json({ error: "not signed in" }, 401);
  if (storageConfigured()) {
    const keys = await listKeys(`u/${user.id}/`);
    if (keys === null) return json({ error: "couldn't list the recordings" }, 502);
    for (const key of keys) {
      if (!(await deleteObject(key))) return json({ error: "couldn't delete a recording" }, 502);
    }
  }
  return (await deleteUser(user.id)) ? json({ deleted: true }) : json({ error: "delete failed" }, 502);
});
