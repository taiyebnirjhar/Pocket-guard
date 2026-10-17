-- SOS recordings uploaded to R2 while SOS runs, for the people with the live link. The rows are
-- written only by the media function (which also signs the upload and viewing links); the files
-- live in R2 under u/<user>/<incident>/ and go after 90 days, like the rows.

create table public.media (
  id bigint generated always as identity primary key,
  incident_id uuid not null references public.incidents (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  kind text not null check (kind in ('video', 'photo')),
  object_key text not null unique,
  content_type text not null,
  bytes integer not null check (bytes > 0),
  uploaded boolean not null default false,
  created_at timestamptz not null default now()
);
create index media_incident on public.media (incident_id, created_at);
create index media_user on public.media (user_id, created_at);

revoke all on public.media from anon, authenticated;
alter table public.media enable row level security;
-- No policies: only the service (the media function) reads and writes it.

-- The live page now also lists the uploaded recordings (ids only; the page asks the media
-- function for a short-lived link to play one).
create or replace function public.view_incident(token text) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'name', i.owner_name,
    'status', i.status,
    'battery', i.battery,
    'started_at', i.started_at,
    'ended_at', i.ended_at,
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

-- Uploads that never finished are dropped after a day (the R2 rule removes their files).
select cron.schedule('pocket-guard-media-cleanup', '27 3 * * *', $$
  delete from public.media where not uploaded and created_at < now() - interval '1 day';
$$);
