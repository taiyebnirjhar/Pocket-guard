-- Email claims, tightened.
--  * Each recipient row knows the claim (email_log row) it belongs to, so undoing a failed send
--    removes exactly that claim's rows, never those of an earlier alert to the same people.
--  * Part of the shared daily allowance is kept for real SOS alerts: tests and false alarms stop
--    at 60% of it, so a burst of throwaway accounts sending tests can't block anyone's SOS email.

alter table public.email_recipients
  add column log_id bigint references public.email_log (id) on delete set null;

create or replace function public.claim_email(
  p_user uuid, p_incident uuid, p_kind text, p_hashes text[],
  p_max_user_day int, p_max_tests_day int, p_max_address_day int, p_max_addresses_month int, p_max_all_day int
) returns text
language plpgsql security definer set search_path = '' as $$
declare
  h text;
  v_log bigint;
  v_all int;
begin
  perform pg_advisory_xact_lock(hashtext('email'));
  v_all := (select coalesce(sum(recipients), 0) from public.email_log where sent_at > now() - interval '1 day');
  if v_all + cardinality(p_hashes) > p_max_all_day then
    return 'daily limit for all accounts';
  end if;
  if p_kind <> 'sos' and v_all + cardinality(p_hashes) > p_max_all_day * 6 / 10 then
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
    insert into public.email_log (user_id, incident_id, kind, recipients)
      values (p_user, p_incident, p_kind, cardinality(p_hashes)) returning id into v_log;
  exception when unique_violation then
    return 'already sent';
  end;
  insert into public.email_recipients (user_id, address_hash, log_id) select p_user, unnest(p_hashes), v_log;
  return null;
end $$;
revoke all on function public.claim_email(uuid, uuid, text, text[], int, int, int, int, int) from public, anon, authenticated;

-- Undoes the latest claim of this kind (its sends all failed), and only that claim's recipients.
create or replace function public.release_email(p_user uuid, p_incident uuid, p_kind text, p_hashes text[]) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_log bigint;
begin
  select id into v_log from public.email_log where user_id = p_user and kind = p_kind
    and incident_id is not distinct from p_incident order by sent_at desc limit 1;
  if v_log is null then
    return;
  end if;
  delete from public.email_recipients where log_id = v_log;
  delete from public.email_log where id = v_log;
end $$;
revoke all on function public.release_email(uuid, uuid, text, text[]) from public, anon, authenticated;
