// Deletes the caller's account. Their recordings are removed from R2 first; their incidents,
// locations, media rows and email log then go with the account (the tables cascade from it).
//
// POST with the owner's (anonymous) sign-in in Authorization.

import { deleteUser, json, rest, userOf } from "../_shared/supabase.ts";
import { deleteObject, storageConfigured } from "../_shared/s3.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  const user = await userOf(req);
  if (!user) return json({ error: "not signed in" }, 401);
  if (storageConfigured()) {
    const files = await (await rest(`media?user_id=eq.${user.id}&select=object_key`)).json();
    for (const f of files) {
      // A file that can't be removed now still goes with the bucket's 90-day rule.
      if (!(await deleteObject(f.object_key))) console.error(`couldn't delete ${f.object_key}`);
    }
  }
  return (await deleteUser(user.id)) ? json({ deleted: true }) : json({ error: "delete failed" }, 502);
});
