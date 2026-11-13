-- Signal lost: the live page tells the contacts when the phone stopped reaching the server, so a
-- phone switched off or smashed during SOS is noticed. No email: the page shows it (Brevo's free
-- plan is kept for the SOS alert itself).
--  * incidents.last_seen_at: the server's time of the phone's latest upload (a location, or the
--    incident update the phone sends every 15 s when it has no new location).
--  * view_incident also returns the server's own time, so a viewer's wrong clock can't make the
--    phone look offline or online.

alter table public.incidents add column last_seen_at timestamptz not null default now();

-- Any update by the phone (battery, status) is a sign of life.
create function public.touch_incident() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.last_seen_at := now();
  return new;
end $$;
create trigger touch_incident before update on public.incidents
  for each row execute function public.touch_incident();

-- So is every location it uploads.
create function public.touch_incident_from_location() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.incidents set last_seen_at = now() where id = new.incident_id;
  return null;
end $$;
create trigger touch_incident_from_location after insert on public.locations
  for each row execute function public.touch_incident_from_location();

revoke all on function public.touch_incident() from public, anon, authenticated;
revoke all on function public.touch_incident_from_location() from public, anon, authenticated;

create or replace function public.view_incident(token text) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'name', i.owner_name,
    'status', i.status,
    'battery', i.battery,
    'started_at', i.started_at,
    'ended_at', i.ended_at,
    'last_seen_at', i.last_seen_at,
    'now', now(),
    'points', coalesce((
      select jsonb_agg(jsonb_build_array(p.lat, p.lng, p.accuracy, p.recorded_at) order by p.recorded_at)
      from (
        select l.lat, l.lng, l.accuracy, l.recorded_at from public.locations l
        where l.incident_id = i.id order by l.recorded_at desc limit 500
      ) p
    ), '[]'::jsonb),
    'media', coalesce((
      select jsonb_agg(jsonb_build_array(m.id, m.kind, m.created_at) order by m.created_at)
      from public.media m where m.incident_id = i.id and m.uploaded
    ), '[]'::jsonb)
  )
  from public.incidents i
  where i.viewer_token = token
    and (i.ended_at is null or i.ended_at > now() - interval '1 day')
$$;
revoke all on function public.view_incident(text) from public;
grant execute on function public.view_incident(text) to anon, authenticated;
