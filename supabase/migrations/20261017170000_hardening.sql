-- Hardening after the review: limits that hold under parallel requests, server-side times, and
-- caps across all accounts so free anonymous accounts can't use up the free tiers.

-- --- Limits that hold under parallel requests ------------------------------------------------------
-- Each trigger takes a lock per owner (or incident), so concurrent inserts are counted one after
-- the other instead of each seeing the count before the others.

create or replace function public.limit_incidents() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  perform pg_advisory_xact_lock(hashtext('incidents:' || new.user_id::text));
  if (select count(*) from public.incidents
      where user_id = new.user_id and started_at > now() - interval '1 day') >= 20 then
    raise exception 'too many incidents today' using errcode = 'P0001';
  end if;
  return new;
end $$;

create or replace function public.limit_locations() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  perform pg_advisory_xact_lock(hashtext('locations:' || new.incident_id::text));
  if (select count(*) from public.locations where incident_id = new.incident_id) >= 2000 then
    raise exception 'too many locations for this incident' using errcode = 'P0001';
  end if;
  -- The phone's clock can be fast: a fix "from the future" is taken as now, not refused (which
  -- would drop the whole batch and leave the live page empty).
  if new.recorded_at > now() then
    new.recorded_at := now();
  end if;
  return new;
end $$;

alter table public.locations drop constraint if exists locations_recorded_at_check;

-- --- Incident times come from the server -------------------------------------------------------------
-- The app still sends ended_at (older versions too), but the server's clock decides: the live
-- page's one-day window and the late-upload windows hang on it. An ended incident stays ended.

create function public.incident_times() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if old.status <> 'active' then
    if new.status = 'active' then
      raise exception 'an ended incident stays ended' using errcode = 'P0001';
    end if;
    new.status := old.status;
    new.ended_at := old.ended_at;
  elsif new.status <> 'active' then
    new.ended_at := now();
  else
    new.ended_at := null;
  end if;
  return new;
end $$;
create trigger incident_times before update on public.incidents
  for each row execute function public.incident_times();
revoke all on function public.incident_times() from public, anon, authenticated;

-- Anything already ended without a time gets one, so its live link expires.
update public.incidents set ended_at = coalesce(ended_at, started_at) where status <> 'active';

-- --- Email: once per incident and kind, and caps across all accounts ---------------------------------

-- The notify function claims (incident, kind) before sending, so parallel retries can't both send.
create unique index email_log_once on public.email_log (incident_id, kind) where incident_id is not null;

-- Who got alert emails, as a one-way hash of the address (no address is stored): an address gets
-- at most a few a day whoever sends them, so the function can't be used to flood someone.
create table public.email_recipients (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  address_hash text not null,
  sent_at timestamptz not null default now()
);
create index email_recipients_hash on public.email_recipients (address_hash, sent_at);
create index email_recipients_user on public.email_recipients (user_id, sent_at);
revoke all on public.email_recipients from anon, authenticated;
alter table public.email_recipients enable row level security;

-- The notify function's whole check, under one lock so parallel requests queue: returns null
-- when the send may go ahead (and records it), else the reason.
create function public.claim_email(
  p_user uuid, p_incident uuid, p_kind text, p_hashes text[],
  p_max_user_day int, p_max_tests_day int, p_max_address_day int, p_max_addresses_month int, p_max_all_day int
) returns text
language plpgsql security definer set search_path = '' as $$
declare
  h text;
begin
  perform pg_advisory_xact_lock(hashtext('email'));
  if (select coalesce(sum(recipients), 0) from public.email_log where sent_at > now() - interval '1 day') + cardinality(p_hashes) > p_max_all_day then
    return 'daily limit for all accounts';
  end if;
  if (select count(*) from public.email_log where user_id = p_user and sent_at > now() - interval '1 day') >= p_max_user_day then
    return 'daily limit';
  end if;
  if p_kind = 'test' and (select count(*) from public.email_log
      where user_id = p_user and kind = 'test' and sent_at > now() - interval '1 day') >= p_max_tests_day then
    return 'daily test limit';
  end if;
  if (select count(distinct address_hash) from public.email_recipients
      where user_id = p_user and sent_at > now() - interval '30 days'
        and not (address_hash = any (p_hashes))) + cardinality(p_hashes) > p_max_addresses_month then
    return 'too many different addresses';
  end if;
  foreach h in array p_hashes loop
    if (select count(*) from public.email_recipients where address_hash = h and sent_at > now() - interval '1 day') >= p_max_address_day then
      return 'an address had enough alerts today';
    end if;
  end loop;
  begin
    insert into public.email_log (user_id, incident_id, kind, recipients) values (p_user, p_incident, p_kind, cardinality(p_hashes));
  exception when unique_violation then
    return 'already sent';
  end;
  insert into public.email_recipients (user_id, address_hash) select p_user, unnest(p_hashes);
  return null;
end $$;
revoke all on function public.claim_email(uuid, uuid, text, text[], int, int, int, int, int) from public, anon, authenticated;

-- Undoes a claim whose sends all failed, so the phone's retry can go through.
create function public.release_email(p_user uuid, p_incident uuid, p_kind text, p_hashes text[]) returns void
language sql security definer set search_path = '' as $$
  delete from public.email_log where id = (
    select id from public.email_log where user_id = p_user and kind = p_kind
      and incident_id is not distinct from p_incident order by sent_at desc limit 1);
  delete from public.email_recipients where id in (
    select id from public.email_recipients where user_id = p_user and address_hash = any (p_hashes)
      and sent_at > now() - interval '10 minutes');
$$;
revoke all on function public.release_email(uuid, uuid, text, text[]) from public, anon, authenticated;

-- --- Recordings: one checked step, with caps across all accounts ---------------------------------------

-- Whether a new upload of [p_bytes] may go ahead (null) or why not, and if so its row. Pending
-- uploads count for a quarter of an hour (the link's life), so failed tries don't use up the cap.
create function public.claim_media(
  p_user uuid, p_incident uuid, p_kind text, p_key text, p_type text, p_bytes int,
  p_max_incident int, p_max_user_day bigint, p_max_all_stored bigint, p_upload_hours int
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  row_id bigint;
begin
  perform pg_advisory_xact_lock(hashtext('media'));
  if not exists (select 1 from public.incidents where id = p_incident and user_id = p_user
      and (status = 'active' or ended_at > now() - make_interval(hours => p_upload_hours))) then
    return jsonb_build_object('error', 'no such open incident', 'status', 404);
  end if;
  if (select count(*) from public.media where incident_id = p_incident
      and (uploaded or created_at > now() - interval '15 minutes')) >= p_max_incident then
    return jsonb_build_object('error', 'too many for this incident', 'status', 429);
  end if;
  if (select coalesce(sum(bytes), 0) from public.media where user_id = p_user
      and created_at > now() - interval '1 day' and (uploaded or created_at > now() - interval '15 minutes')) + p_bytes > p_max_user_day then
    return jsonb_build_object('error', 'daily limit', 'status', 429);
  end if;
  if (select coalesce(sum(bytes), 0) from public.media
      where uploaded or created_at > now() - interval '15 minutes') + p_bytes > p_max_all_stored then
    return jsonb_build_object('error', 'storage full', 'status', 507);
  end if;
  insert into public.media (incident_id, user_id, kind, object_key, content_type, bytes)
    values (p_incident, p_user, p_kind, p_key, p_type, p_bytes) returning id into row_id;
  return jsonb_build_object('id', row_id);
end $$;
revoke all on function public.claim_media(uuid, uuid, text, text, text, int, int, bigint, bigint, int) from public, anon, authenticated;

-- The object behind a recording, for anyone with the incident's token (in the body, not the URL,
-- so the token isn't in request logs).
create function public.media_for_viewer(p_token text, p_media bigint) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('key', m.object_key, 'kind', m.kind)
  from public.media m join public.incidents i on i.id = m.incident_id
  where m.id = p_media and m.uploaded and i.viewer_token = p_token
    and (i.ended_at is null or i.ended_at > now() - interval '1 day')
$$;
revoke all on function public.media_for_viewer(text, bigint) from public, anon, authenticated;

-- The service (the functions) calls these; nobody else can.
grant execute on function public.claim_email(uuid, uuid, text, text[], int, int, int, int, int) to service_role;
grant execute on function public.release_email(uuid, uuid, text, text[]) to service_role;
grant execute on function public.claim_media(uuid, uuid, text, text, text, int, int, bigint, bigint, int) to service_role;
grant execute on function public.media_for_viewer(text, bigint) to service_role;

-- Retention now covers the recipient hashes too.
select cron.schedule('pocket-guard-retention', '17 3 * * *', $$
  update public.incidents set status = 'ended'
    where status = 'active' and started_at < now() - interval '1 day';
  delete from public.incidents where started_at < now() - interval '90 days';
  delete from public.email_log where sent_at < now() - interval '90 days';
  delete from public.email_recipients where sent_at < now() - interval '90 days';
$$);
