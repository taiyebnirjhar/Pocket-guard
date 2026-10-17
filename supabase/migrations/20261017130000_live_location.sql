-- Live location during SOS: the phone signs in anonymously, opens an incident and uploads its
-- location; contacts follow it on the live page through the incident's secret viewer token,
-- without an account. Rows go when the account is deleted and after 90 days.

create table public.incidents (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  owner_name text not null check (char_length(owner_name) between 1 and 60),
  -- 48 hex characters from 24 random bytes: the only key to the live page.
  viewer_token text not null unique default encode(extensions.gen_random_bytes(24), 'hex'),
  status text not null default 'active' check (status in ('active', 'ended', 'false_alarm')),
  battery smallint check (battery between 0 and 100),
  started_at timestamptz not null default now(),
  ended_at timestamptz
);
create index incidents_user_id on public.incidents (user_id, started_at);

create table public.locations (
  id bigint generated always as identity primary key,
  incident_id uuid not null references public.incidents (id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  lat double precision not null check (lat between -90 and 90),
  lng double precision not null check (lng between -180 and 180),
  accuracy real check (accuracy >= 0),
  -- When the phone took the fix; the upload can come later when it was offline.
  recorded_at timestamptz not null default now() check (recorded_at < now() + interval '5 minutes')
);
create index locations_incident on public.locations (incident_id, recorded_at);

-- Alert emails sent by the notify function, for its rate limits.
create table public.email_log (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  incident_id uuid references public.incidents (id) on delete cascade,
  kind text not null,
  recipients smallint not null,
  sent_at timestamptz not null default now()
);
create index email_log_user on public.email_log (user_id, sent_at);

-- Nothing is reachable through the API unless granted below, whatever the project's defaults.
revoke all on public.incidents, public.locations, public.email_log from anon, authenticated;
alter table public.incidents enable row level security;
alter table public.locations enable row level security;
alter table public.email_log enable row level security;

-- The owner opens, reads and closes their own incidents. The token and the owner can't be set
-- or changed by the app.
grant select, insert (owner_name, battery), update (status, battery, ended_at)
  on public.incidents to authenticated;
create policy "own incidents: read" on public.incidents
  for select to authenticated using (user_id = (select auth.uid()));
create policy "own incidents: open" on public.incidents
  for insert to authenticated with check (user_id = (select auth.uid()));
create policy "own incidents: update" on public.incidents
  for update to authenticated using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

-- Locations go only into the owner's incident while it runs, or for a minute after (the last
-- queued fixes).
grant select, insert (incident_id, lat, lng, accuracy, recorded_at) on public.locations to authenticated;
create policy "own locations: read" on public.locations
  for select to authenticated using (user_id = (select auth.uid()));
create policy "own locations: add" on public.locations
  for insert to authenticated with check (
    user_id = (select auth.uid())
    and exists (
      select 1 from public.incidents i
      where i.id = incident_id and i.user_id = (select auth.uid())
        and (i.status = 'active' or i.ended_at > now() - interval '1 minute')
    )
  );

-- Limits against misuse of an anonymous account: 20 incidents a day, 2000 points each.
create function public.limit_incidents() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if (select count(*) from public.incidents
      where user_id = new.user_id and started_at > now() - interval '1 day') >= 20 then
    raise exception 'too many incidents today' using errcode = 'P0001';
  end if;
  return new;
end $$;
create trigger limit_incidents before insert on public.incidents
  for each row execute function public.limit_incidents();

create function public.limit_locations() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if (select count(*) from public.locations where incident_id = new.incident_id) >= 2000 then
    raise exception 'too many locations for this incident' using errcode = 'P0001';
  end if;
  return new;
end $$;
create trigger limit_locations before insert on public.locations
  for each row execute function public.limit_locations();

-- The live page: anyone with the token sees the incident and its trail, until a day after it
-- ended. Returns null for an unknown or expired token.
create function public.view_incident(token text) returns jsonb
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
    ), '[]'::jsonb)
  )
  from public.incidents i
  where i.viewer_token = token
    and (i.ended_at is null or i.ended_at > now() - interval '1 day')
$$;

revoke all on function public.view_incident(text) from public;
revoke all on function public.limit_incidents() from public, anon, authenticated;
revoke all on function public.limit_locations() from public, anon, authenticated;
grant execute on function public.view_incident(text) to anon, authenticated;

-- Daily at 03:17 UTC: close incidents a dead phone left open for over a day, and delete
-- everything older than 90 days.
create extension if not exists pg_cron;
select cron.schedule('pocket-guard-retention', '17 3 * * *', $$
  update public.incidents set status = 'ended', ended_at = now()
    where status = 'active' and started_at < now() - interval '1 day';
  delete from public.incidents where started_at < now() - interval '90 days';
  delete from public.email_log where sent_at < now() - interval '90 days';
$$);
