-- Detection logs, sent by phones whose owner turned on "Send detection logs" (off by default), so
-- false alarms and missed thefts on real phones can be studied. What the guard saw and decided
-- (sensor events, phases, timings), the phone model and Android version, and the owner's own
-- labels for alarms ("my test", "false alarm"). No location, contacts or messages.
-- Only the service (the owner exporting them from the Supabase dashboard) can read them; the app
-- can only add. Deleted after 30 days, or with the account.

create table public.diag_logs (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at timestamptz not null default now(),
  reason text not null check (reason in ('alarm', 'disarm', 'manual', 'label')),
  app_version text not null check (char_length(app_version) between 1 and 40),
  device jsonb not null check (pg_column_size(device) <= 4096),
  lines text not null check (char_length(lines) between 1 and 40000)
);
create index diag_logs_user on public.diag_logs (user_id, created_at);
create index diag_logs_created on public.diag_logs (created_at);

revoke all on public.diag_logs from anon, authenticated;
alter table public.diag_logs enable row level security;
grant insert (reason, app_version, device, lines) on public.diag_logs to authenticated;
create policy "own logs: add" on public.diag_logs
  for insert to authenticated with check (user_id = (select auth.uid()));

-- Free-plan guard: 30 a day per phone, and nothing more once the table holds 100 MB (the database
-- has 500 MB, mostly for SOS).
create function public.limit_diag_logs() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if (select count(*) from public.diag_logs
      where user_id = new.user_id and created_at > now() - interval '1 day') >= 30 then
    raise exception 'too many logs today' using errcode = 'P0001';
  end if;
  if pg_total_relation_size('public.diag_logs') > 100 * 1024 * 1024 then
    raise exception 'log storage full' using errcode = 'P0001';
  end if;
  return new;
end $$;
create trigger limit_diag_logs before insert on public.diag_logs
  for each row execute function public.limit_diag_logs();
revoke all on function public.limit_diag_logs() from public, anon, authenticated;

select cron.schedule('pocket-guard-diag-retention', '27 3 * * *', $$
  delete from public.diag_logs where created_at < now() - interval '30 days';
$$);
