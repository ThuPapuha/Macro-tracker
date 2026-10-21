-- Macro Tracker: esquema de Supabase.
-- Es idempotente: el workflow de GitHub Actions lo vuelve a aplicar en cada despliegue.
-- Las tablas tienen RLS activado y ninguna política: los clientes solo pueden usar
-- las funciones de abajo, que validan auth.uid() y la pertenencia a los grupos.

create table if not exists public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  display_name text not null default 'Anónimo',
  updated_at timestamptz not null default now()
);

create table if not exists public.groups (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  invite_code text not null unique,
  owner_id uuid not null references auth.users (id) on delete cascade,
  created_at timestamptz not null default now()
);

create table if not exists public.group_members (
  group_id uuid not null references public.groups (id) on delete cascade,
  user_id uuid not null references auth.users (id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (group_id, user_id)
);

create index if not exists group_members_user_idx on public.group_members (user_id);

create table if not exists public.daily_summaries (
  user_id uuid not null references auth.users (id) on delete cascade,
  day date not null,
  calories double precision not null default 0,
  protein double precision not null default 0,
  carbs double precision not null default 0,
  fat double precision not null default 0,
  target_calories double precision,
  target_protein double precision,
  target_carbs double precision,
  target_fat double precision,
  meals jsonb not null default '[]'::jsonb,
  updated_at timestamptz not null default now(),
  primary key (user_id, day)
);

create table if not exists public.ai_usage (
  user_id uuid not null references auth.users (id) on delete cascade,
  day date not null,
  calls integer not null default 0,
  primary key (user_id, day)
);

create table if not exists public.meal_comments (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.groups (id) on delete cascade,
  target_user uuid not null references auth.users (id) on delete cascade,
  day date not null,
  meal_name text,
  author_id uuid not null references auth.users (id) on delete cascade,
  body text not null,
  created_at timestamptz not null default now()
);

create index if not exists meal_comments_group_day_idx on public.meal_comments (group_id, day);
create index if not exists meal_comments_target_idx on public.meal_comments (target_user, created_at);

alter table public.profiles enable row level security;
alter table public.groups enable row level security;
alter table public.group_members enable row level security;
alter table public.daily_summaries enable row level security;
alter table public.ai_usage enable row level security;
alter table public.meal_comments enable row level security;

-- ---------------------------------------------------------------------------
-- Helpers (not callable by clients)
-- ---------------------------------------------------------------------------

create or replace function public.require_uid() returns uuid
language plpgsql stable set search_path = public as $$
declare
  uid uuid := auth.uid();
begin
  if uid is null then
    raise exception 'No autenticado' using errcode = '28000';
  end if;
  return uid;
end $$;

create or replace function public.require_member(p_group uuid) returns uuid
language plpgsql stable security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
begin
  if not exists (select 1 from group_members gm where gm.group_id = p_group and gm.user_id = uid) then
    raise exception 'No perteneces a este grupo' using errcode = '42501';
  end if;
  return uid;
end $$;

create or replace function public.new_invite_code() returns text
language plpgsql volatile security definer set search_path = public as $$
declare
  alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  raw bytea;
  code text;
begin
  loop
    raw := uuid_send(gen_random_uuid());
    code := '';
    for i in 0..5 loop
      code := code || substr(alphabet, (get_byte(raw, i) % 32) + 1, 1);
    end loop;
    exit when not exists (select 1 from groups g where g.invite_code = code);
  end loop;
  return code;
end $$;

create or replace function public.group_json(p_group uuid) returns json
language sql stable security definer set search_path = public as $$
  select json_build_object(
    'id', g.id,
    'name', g.name,
    'invite_code', g.invite_code,
    'owner_id', g.owner_id,
    'member_count', (select count(*) from group_members m where m.group_id = g.id)
  )
  from groups g
  where g.id = p_group;
$$;

-- ---------------------------------------------------------------------------
-- API (called from the app through PostgREST: POST /rest/v1/rpc/<name>)
-- ---------------------------------------------------------------------------

create or replace function public.set_profile(p_display_name text) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
  v_name text := left(btrim(coalesce(p_display_name, '')), 40);
begin
  if v_name = '' then
    raise exception 'El nombre no puede estar vacío';
  end if;
  insert into profiles (id, display_name, updated_at)
  values (uid, v_name, now())
  on conflict (id) do update set display_name = excluded.display_name, updated_at = now();
end $$;

create or replace function public.create_group(p_name text) returns json
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
  v_name text := left(btrim(coalesce(p_name, '')), 60);
  gid uuid;
begin
  if v_name = '' then
    raise exception 'El grupo necesita un nombre';
  end if;
  if (select count(*) from groups g where g.owner_id = uid) >= 20 then
    raise exception 'Ya creaste demasiados grupos';
  end if;
  insert into groups (name, invite_code, owner_id)
  values (v_name, public.new_invite_code(), uid)
  returning id into gid;
  insert into group_members (group_id, user_id) values (gid, uid);
  return public.group_json(gid);
end $$;

create or replace function public.join_group(p_code text) returns json
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
  gid uuid;
begin
  select g.id into gid from groups g where g.invite_code = upper(btrim(coalesce(p_code, '')));
  if gid is null then
    raise exception 'No existe un grupo con ese código';
  end if;
  if not exists (select 1 from group_members m where m.group_id = gid and m.user_id = uid)
     and (select count(*) from group_members m where m.group_id = gid) >= 50 then
    raise exception 'El grupo está lleno';
  end if;
  insert into group_members (group_id, user_id) values (gid, uid) on conflict do nothing;
  return public.group_json(gid);
end $$;

create or replace function public.leave_group(p_group uuid) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
begin
  delete from group_members m where m.group_id = p_group and m.user_id = uid;
  if not exists (select 1 from group_members m where m.group_id = p_group) then
    delete from groups g where g.id = p_group;
  elsif exists (select 1 from groups g where g.id = p_group and g.owner_id = uid) then
    update groups g
    set owner_id = (
      select m.user_id from group_members m where m.group_id = p_group order by m.joined_at limit 1
    )
    where g.id = p_group;
  end if;
end $$;

create or replace function public.my_groups() returns json
language plpgsql stable security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
begin
  return coalesce(
    (select json_agg(public.group_json(m.group_id) order by m.joined_at)
     from group_members m where m.user_id = uid),
    '[]'::json
  );
end $$;

create or replace function public.group_members_list(p_group uuid)
returns table (user_id uuid, display_name text, is_owner boolean, joined_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.require_member(p_group);
  return query
    select m.user_id, coalesce(p.display_name, 'Anónimo'), (g.owner_id = m.user_id), m.joined_at
    from group_members m
    join groups g on g.id = m.group_id
    left join profiles p on p.id = m.user_id
    where m.group_id = p_group
    order by m.joined_at;
end $$;

create or replace function public.group_summaries(p_group uuid, p_from date, p_to date)
returns table (
  user_id uuid,
  day date,
  calories double precision,
  protein double precision,
  carbs double precision,
  fat double precision,
  target_calories double precision,
  target_protein double precision,
  target_carbs double precision,
  target_fat double precision,
  meals jsonb
)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.require_member(p_group);
  if p_to < p_from or p_to - p_from > 62 then
    raise exception 'Rango de fechas inválido';
  end if;
  return query
    select s.user_id, s.day, s.calories, s.protein, s.carbs, s.fat,
           s.target_calories, s.target_protein, s.target_carbs, s.target_fat, s.meals
    from daily_summaries s
    join group_members m on m.user_id = s.user_id and m.group_id = p_group
    where s.day between p_from and p_to
    order by s.day, s.user_id;
end $$;

create or replace function public.upsert_daily_summary(
  p_day date,
  p_calories double precision,
  p_protein double precision,
  p_carbs double precision,
  p_fat double precision,
  p_target_calories double precision default null,
  p_target_protein double precision default null,
  p_target_carbs double precision default null,
  p_target_fat double precision default null,
  p_meals jsonb default '[]'::jsonb
) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
  v_meals jsonb := coalesce(p_meals, '[]'::jsonb);
begin
  if p_day < current_date - 400 or p_day > current_date + 2 then
    raise exception 'Fecha fuera de rango';
  end if;
  if jsonb_typeof(v_meals) <> 'array' or length(v_meals::text) > 20000 then
    v_meals := '[]'::jsonb;
  end if;
  insert into daily_summaries as s (
    user_id, day, calories, protein, carbs, fat,
    target_calories, target_protein, target_carbs, target_fat, meals, updated_at
  ) values (
    uid, p_day, coalesce(p_calories, 0), coalesce(p_protein, 0), coalesce(p_carbs, 0), coalesce(p_fat, 0),
    p_target_calories, p_target_protein, p_target_carbs, p_target_fat, v_meals, now()
  )
  on conflict (user_id, day) do update set
    calories = excluded.calories,
    protein = excluded.protein,
    carbs = excluded.carbs,
    fat = excluded.fat,
    target_calories = excluded.target_calories,
    target_protein = excluded.target_protein,
    target_carbs = excluded.target_carbs,
    target_fat = excluded.target_fat,
    meals = excluded.meals,
    -- updated_at drives friends' notifications, so it only moves when the food changed.
    updated_at = case
      when (s.calories, s.protein, s.carbs, s.fat, s.meals)
        is distinct from (excluded.calories, excluded.protein, excluded.carbs, excluded.fat, excluded.meals)
      then now()
      else s.updated_at
    end
  where (s.calories, s.protein, s.carbs, s.fat, s.meals,
         s.target_calories, s.target_protein, s.target_carbs, s.target_fat)
    is distinct from (excluded.calories, excluded.protein, excluded.carbs, excluded.fat, excluded.meals,
                      excluded.target_calories, excluded.target_protein, excluded.target_carbs, excluded.target_fat);
end $$;

create or replace function public.add_meal_comment(
  p_group uuid, p_target uuid, p_day date, p_meal text, p_body text
) returns json
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_member(p_group);
  v_body text := left(btrim(coalesce(p_body, '')), 500);
  v_meal text := nullif(left(btrim(coalesce(p_meal, '')), 120), '');
  v_id uuid;
begin
  if v_body = '' then
    raise exception 'El comentario está vacío';
  end if;
  if not exists (select 1 from group_members m where m.group_id = p_group and m.user_id = p_target) then
    raise exception 'Esa persona ya no está en el grupo';
  end if;
  if (select count(*) from meal_comments c where c.author_id = uid and c.created_at > now() - interval '1 day') >= 300 then
    raise exception 'Llegaste al límite de comentarios por hoy';
  end if;
  insert into meal_comments (group_id, target_user, day, meal_name, author_id, body)
  values (p_group, p_target, p_day, v_meal, uid, v_body)
  returning id into v_id;
  return json_build_object('id', v_id);
end $$;

create or replace function public.delete_meal_comment(p_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
begin
  delete from meal_comments c where c.id = p_id and c.author_id = uid;
end $$;

create or replace function public.group_comments(p_group uuid, p_from date, p_to date)
returns table (
  id uuid,
  target_user uuid,
  day date,
  meal_name text,
  author_id uuid,
  author_name text,
  body text,
  created_at timestamptz
)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.require_member(p_group);
  if p_to < p_from or p_to - p_from > 62 then
    raise exception 'Rango de fechas inválido';
  end if;
  return query
    select c.id, c.target_user, c.day, c.meal_name, c.author_id,
           coalesce(p.display_name, 'Anónimo'), c.body, c.created_at
    from meal_comments c
    left join profiles p on p.id = c.author_id
    where c.group_id = p_group and c.day between p_from and p_to
    order by c.created_at;
end $$;

-- Polled by the app in the background: friends' food changes and comments on my food since p_since.
-- With p_since null it only returns the server time, to start the cursor without old notifications.
create or replace function public.group_activity(p_since timestamptz) returns json
language plpgsql stable security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
begin
  if p_since is null then
    return json_build_object('now', now(), 'items', '[]'::json, 'comments', '[]'::json);
  end if;
  return json_build_object(
    'now', now(),
    'items', coalesce((
      select json_agg(x order by x.updated_at) from (
        select distinct on (s.user_id, s.day)
          s.user_id, coalesce(p.display_name, 'Anónimo') as display_name,
          m.group_id, g.name as group_name,
          s.day, s.calories, s.protein, s.carbs, s.fat, s.meals, s.updated_at
        from daily_summaries s
        join group_members m on m.user_id = s.user_id
        join group_members mine on mine.group_id = m.group_id and mine.user_id = uid
        join groups g on g.id = m.group_id
        left join profiles p on p.id = s.user_id
        where s.updated_at > p_since
          and s.user_id <> uid
          and s.day >= current_date - 2
          and (s.calories > 0 or jsonb_array_length(s.meals) > 0)
        order by s.user_id, s.day, m.joined_at
      ) x
    ), '[]'::json),
    'comments', coalesce((
      select json_agg(y order by y.created_at) from (
        select c.id, c.group_id, g.name as group_name, c.day, c.meal_name, c.body, c.created_at,
               coalesce(p.display_name, 'Anónimo') as author_name
        from meal_comments c
        join groups g on g.id = c.group_id
        left join profiles p on p.id = c.author_id
        where c.target_user = uid and c.author_id <> uid and c.created_at > p_since
        order by c.created_at
        limit 50
      ) y
    ), '[]'::json)
  );
end $$;

-- Called by the `gemini` Edge Function with the user's JWT: counts AI requests per day.
create or replace function public.bump_ai_usage() returns integer
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
  total integer;
begin
  insert into ai_usage as u (user_id, day, calls) values (uid, current_date, 1)
  on conflict (user_id, day) do update set calls = u.calls + 1
  returning u.calls into total;
  return total;
end $$;

-- ---------------------------------------------------------------------------
-- Permissions
-- ---------------------------------------------------------------------------

revoke all on function public.require_uid() from public, anon, authenticated;
revoke all on function public.require_member(uuid) from public, anon, authenticated;
revoke all on function public.new_invite_code() from public, anon, authenticated;
revoke all on function public.group_json(uuid) from public, anon, authenticated;

revoke all on function public.set_profile(text) from public, anon;
revoke all on function public.create_group(text) from public, anon;
revoke all on function public.join_group(text) from public, anon;
revoke all on function public.leave_group(uuid) from public, anon;
revoke all on function public.my_groups() from public, anon;
revoke all on function public.group_members_list(uuid) from public, anon;
revoke all on function public.group_summaries(uuid, date, date) from public, anon;
revoke all on function public.upsert_daily_summary(
  date, double precision, double precision, double precision, double precision,
  double precision, double precision, double precision, double precision, jsonb
) from public, anon;
revoke all on function public.bump_ai_usage() from public, anon;
revoke all on function public.add_meal_comment(uuid, uuid, date, text, text) from public, anon;
revoke all on function public.delete_meal_comment(uuid) from public, anon;
revoke all on function public.group_comments(uuid, date, date) from public, anon;
revoke all on function public.group_activity(timestamptz) from public, anon;

grant execute on function public.set_profile(text) to authenticated;
grant execute on function public.create_group(text) to authenticated;
grant execute on function public.join_group(text) to authenticated;
grant execute on function public.leave_group(uuid) to authenticated;
grant execute on function public.my_groups() to authenticated;
grant execute on function public.group_members_list(uuid) to authenticated;
grant execute on function public.group_summaries(uuid, date, date) to authenticated;
grant execute on function public.upsert_daily_summary(
  date, double precision, double precision, double precision, double precision,
  double precision, double precision, double precision, double precision, jsonb
) to authenticated;
grant execute on function public.bump_ai_usage() to authenticated;
grant execute on function public.add_meal_comment(uuid, uuid, date, text, text) to authenticated;
grant execute on function public.delete_meal_comment(uuid) to authenticated;
grant execute on function public.group_comments(uuid, date, date) to authenticated;
grant execute on function public.group_activity(timestamptz) to authenticated;

notify pgrst, 'reload schema';
