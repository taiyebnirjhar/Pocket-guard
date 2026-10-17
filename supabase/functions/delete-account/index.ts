// Deletes the caller's account. Their incidents, locations and email log go with it (the
// tables cascade from auth.users).
//
// POST with the owner's (anonymous) sign-in in Authorization.

import { deleteUser, json, userOf } from "../_shared/supabase.ts";

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" }, 405);
  const user = await userOf(req);
  if (!user) return json({ error: "not signed in" }, 401);
  return (await deleteUser(user.id)) ? json({ deleted: true }) : json({ error: "delete failed" }, 502);
});
