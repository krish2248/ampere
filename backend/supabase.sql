-- Ampere live leaderboard. Paste into Supabase > SQL editor > Run. Free tier is plenty.
-- Clients can only read the view and call three functions; nobody can touch the table directly.

create table if not exists public.entries (
  id         bigint generated always as identity primary key,
  token_hash text unique not null,              -- sha256 of a random secret that never leaves the phone in readable form
  nick       text not null,
  country    text not null default '',
  device     text not null default '',
  vp         int  not null default 0,
  t_0_100    int, t_20_80 int, t_40_100 int,    -- seconds
  peak_mw    int  not null default 0,
  cycles     real not null default 0,
  reports    int  not null default 0,
  updated_at timestamptz not null default now()
);
alter table public.entries enable row level security;   -- no policies: direct access is closed

create or replace view public.board as
  select id, nick, country, device, vp, t_0_100, t_20_80, t_40_100, peak_mw, cycles
  from public.entries where reports < 5;
grant select on public.board to anon;

create extension if not exists pgcrypto with schema extensions;

create or replace function public.submit_entry(
  p_token text, p_nick text, p_country text, p_device text, p_vp int,
  p_t0100 int, p_t2080 int, p_t40100 int, p_peak int, p_cycles real
) returns void language plpgsql security definer set search_path = public, extensions as $$
declare h text := encode(digest(p_token, 'sha256'), 'hex');
begin
  if length(p_token) < 32 then raise exception 'bad token'; end if;
  if p_nick !~ '^[A-Za-z0-9_ .-]{2,18}$' then raise exception 'bad nick'; end if;
  if p_country !~ '^([A-Z]{2})?$' then raise exception 'bad country'; end if;
  -- Physically implausible numbers are refused.
  if p_peak not between 0 and 260000 or p_cycles not between 0 and 5000 or p_vp not between 0 and 500000
     or coalesce(p_t0100, 9999) < 480 or coalesce(p_t2080, 9999) < 240 or coalesce(p_t40100, 9999) < 300 then
    raise exception 'out of range';
  end if;
  insert into entries (token_hash, nick, country, device, vp, t_0_100, t_20_80, t_40_100, peak_mw, cycles)
  values (h, trim(p_nick), p_country, left(p_device, 40), p_vp, p_t0100, p_t2080, p_t40100, p_peak, p_cycles)
  on conflict (token_hash) do update set
    nick = excluded.nick, country = excluded.country, device = excluded.device, vp = excluded.vp,
    t_0_100 = excluded.t_0_100, t_20_80 = excluded.t_20_80, t_40_100 = excluded.t_40_100,
    peak_mw = excluded.peak_mw, cycles = excluded.cycles, updated_at = now()
  where entries.updated_at < now() - interval '20 seconds';   -- rate limit
end $$;

create or replace function public.remove_entry(p_token text) returns void
language sql security definer set search_path = public, extensions as $$
  delete from entries where token_hash = encode(digest(p_token, 'sha256'), 'hex');
$$;

create or replace function public.report_entry(p_id bigint) returns void
language sql security definer set search_path = public as $$
  update entries set reports = reports + 1 where id = p_id;
$$;

revoke all on function public.submit_entry, public.remove_entry, public.report_entry from public;
grant execute on function public.submit_entry, public.remove_entry, public.report_entry to anon;
