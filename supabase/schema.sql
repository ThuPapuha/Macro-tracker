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

-- Comments can target any post of the feed: a day of food, a workout or a weigh-in.
-- post_id is the day (yyyy-mm-dd) for food and weight posts and the workout id for workouts.
alter table public.meal_comments add column if not exists post_type text not null default 'food';
alter table public.meal_comments add column if not exists post_id text;
update public.meal_comments set post_id = day::text where post_id is null;

alter table public.profiles add column if not exists goal_weight double precision;
alter table public.profiles add column if not exists start_weight double precision;
alter table public.profiles add column if not exists share_weight boolean not null default true;

create table if not exists public.weight_logs (
  user_id uuid not null references auth.users (id) on delete cascade,
  day date not null,
  kg double precision not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, day)
);

-- Body composition measured by hand (scale, InBody...): body fat %, muscle % and bone mass in kg.
alter table public.weight_logs add column if not exists fat_pct double precision;
alter table public.weight_logs add column if not exists muscle_pct double precision;
alter table public.weight_logs add column if not exists bone_kg double precision;

create table if not exists public.workouts (
  id uuid primary key,
  user_id uuid not null references auth.users (id) on delete cascade,
  day date not null,
  title text not null,
  exercises jsonb not null default '[]'::jsonb,
  pr_count integer not null default 0,
  duration_min integer,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists workouts_user_day_idx on public.workouts (user_id, day);
-- When the workout happened on the phone; created_at is when the server first saw it.
alter table public.workouts add column if not exists performed_at timestamptz;

create table if not exists public.post_reactions (
  group_id uuid not null references public.groups (id) on delete cascade,
  target_user uuid not null references auth.users (id) on delete cascade,
  post_type text not null,
  post_id text not null,
  user_id uuid not null references auth.users (id) on delete cascade,
  emoji text not null,
  created_at timestamptz not null default now(),
  primary key (group_id, target_user, post_type, post_id, user_id)
);

create index if not exists post_reactions_target_idx on public.post_reactions (target_user, created_at);

alter table public.profiles enable row level security;
alter table public.groups enable row level security;
alter table public.group_members enable row level security;
alter table public.daily_summaries enable row level security;
alter table public.ai_usage enable row level security;
alter table public.meal_comments enable row level security;
alter table public.weight_logs enable row level security;
alter table public.workouts enable row level security;
alter table public.post_reactions enable row level security;

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

drop function if exists public.group_members_list(uuid);
create function public.group_members_list(p_group uuid)
returns table (
  user_id uuid,
  display_name text,
  is_owner boolean,
  joined_at timestamptz,
  goal_weight double precision,
  start_weight double precision,
  current_weight double precision
)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.require_member(p_group);
  return query
    select m.user_id, coalesce(p.display_name, 'Anónimo'), (g.owner_id = m.user_id), m.joined_at,
           case when coalesce(p.share_weight, true) then p.goal_weight end,
           case when coalesce(p.share_weight, true) then p.start_weight end,
           case when coalesce(p.share_weight, true) then (
             select l.kg from weight_logs l where l.user_id = m.user_id order by l.day desc limit 1
           ) end
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

drop function if exists public.add_meal_comment(uuid, uuid, date, text, text);
create or replace function public.add_meal_comment(
  p_group uuid, p_target uuid, p_day date, p_meal text, p_body text,
  p_post_type text default 'food', p_post_id text default null
) returns json
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_member(p_group);
  v_body text := left(btrim(coalesce(p_body, '')), 500);
  v_meal text := nullif(left(btrim(coalesce(p_meal, '')), 120), '');
  v_type text := coalesce(nullif(p_post_type, ''), 'food');
  v_post text := coalesce(nullif(p_post_id, ''), p_day::text);
  v_id uuid;
begin
  if v_body = '' then
    raise exception 'El comentario está vacío';
  end if;
  if v_type not in ('food', 'workout', 'weight') then
    raise exception 'Publicación inválida';
  end if;
  if not exists (select 1 from group_members m where m.group_id = p_group and m.user_id = p_target) then
    raise exception 'Esa persona ya no está en el grupo';
  end if;
  if (select count(*) from meal_comments c where c.author_id = uid and c.created_at > now() - interval '1 day') >= 300 then
    raise exception 'Llegaste al límite de comentarios por hoy';
  end if;
  insert into meal_comments (group_id, target_user, day, meal_name, author_id, body, post_type, post_id)
  values (p_group, p_target, p_day, v_meal, uid, v_body, v_type, v_post)
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

drop function if exists public.group_comments(uuid, date, date);
create function public.group_comments(p_group uuid, p_from date, p_to date)
returns table (
  id uuid,
  target_user uuid,
  day date,
  meal_name text,
  author_id uuid,
  author_name text,
  body text,
  created_at timestamptz,
  post_type text,
  post_id text
)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.require_member(p_group);
  if p_to < p_from or p_to - p_from > 62 then
    raise exception 'Rango de fechas inválido';
  end if;
  return query
    select c.id, c.target_user, c.day, c.meal_name, c.author_id,
           coalesce(p.display_name, 'Anónimo'), c.body, c.created_at, c.post_type, c.post_id
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
    return json_build_object('now', now(), 'items', '[]'::json, 'comments', '[]'::json,
                             'workouts', '[]'::json, 'reactions', '[]'::json);
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
               coalesce(p.display_name, 'Anónimo') as author_name, c.post_type, c.post_id
        from meal_comments c
        join groups g on g.id = c.group_id
        left join profiles p on p.id = c.author_id
        where c.target_user = uid and c.author_id <> uid and c.created_at > p_since
        order by c.created_at
        limit 50
      ) y
    ), '[]'::json),
    'workouts', coalesce((
      select json_agg(w2 order by w2.created_at) from (
        select distinct on (w.id)
          w.id, w.user_id, coalesce(p.display_name, 'Anónimo') as display_name,
          m.group_id, g.name as group_name, w.title, w.pr_count, w.exercises, w.created_at
        from workouts w
        join group_members m on m.user_id = w.user_id
        join group_members mine on mine.group_id = m.group_id and mine.user_id = uid
        join groups g on g.id = m.group_id
        left join profiles p on p.id = w.user_id
        where w.created_at > p_since and w.user_id <> uid
          and coalesce(w.performed_at, w.created_at) > now() - interval '1 day'
        order by w.id, m.joined_at
        limit 20
      ) w2
    ), '[]'::json),
    'reactions', coalesce((
      select json_agg(r2 order by r2.created_at) from (
        select r.group_id, g.name as group_name, r.post_type, r.post_id, r.emoji, r.created_at,
               r.user_id as author_id, coalesce(p.display_name, 'Anónimo') as author_name
        from post_reactions r
        join groups g on g.id = r.group_id
        left join profiles p on p.id = r.user_id
        where r.target_user = uid and r.user_id <> uid and r.created_at > p_since
        order by r.created_at
        limit 50
      ) r2
    ), '[]'::json)
  );
end $$;

create or replace function public.set_weight_goal(
  p_goal double precision, p_start double precision, p_share boolean default true
) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
begin
  if (p_goal is not null and (p_goal < 20 or p_goal > 400)) or (p_start is not null and (p_start < 20 or p_start > 400)) then
    raise exception 'Peso fuera de rango';
  end if;
  insert into profiles (id, goal_weight, start_weight, share_weight)
  values (uid, p_goal, p_start, coalesce(p_share, true))
  on conflict (id) do update set
    goal_weight = excluded.goal_weight,
    start_weight = excluded.start_weight,
    share_weight = excluded.share_weight,
    updated_at = now();
end $$;

-- The app always sends the whole day: a null body composition value clears it.
drop function if exists public.upsert_weight(date, double precision);
create or replace function public.upsert_weight(
  p_day date, p_kg double precision,
  p_fat double precision default null, p_muscle double precision default null, p_bone double precision default null
) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
begin
  if p_kg is null or p_kg < 20 or p_kg > 400 then
    raise exception 'Peso fuera de rango';
  end if;
  if (p_fat is not null and (p_fat < 1 or p_fat > 80))
     or (p_muscle is not null and (p_muscle < 5 or p_muscle > 90))
     or (p_bone is not null and (p_bone < 0.2 or p_bone > 15)) then
    raise exception 'Composición corporal fuera de rango';
  end if;
  if p_day < current_date - 800 or p_day > current_date + 2 then
    raise exception 'Fecha fuera de rango';
  end if;
  insert into weight_logs as l (user_id, day, kg, fat_pct, muscle_pct, bone_kg)
  values (uid, p_day, p_kg, p_fat, p_muscle, p_bone)
  on conflict (user_id, day) do update set
    kg = excluded.kg,
    fat_pct = excluded.fat_pct,
    muscle_pct = excluded.muscle_pct,
    bone_kg = excluded.bone_kg,
    updated_at = now()
  where (l.kg, l.fat_pct, l.muscle_pct, l.bone_kg)
    is distinct from (excluded.kg, excluded.fat_pct, excluded.muscle_pct, excluded.bone_kg);
end $$;

create or replace function public.delete_weight(p_day date) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
begin
  delete from weight_logs l where l.user_id = uid and l.day = p_day;
end $$;

drop function if exists public.upsert_workout(uuid, date, text, jsonb, integer, integer);
create or replace function public.upsert_workout(
  p_id uuid, p_day date, p_title text, p_exercises jsonb,
  p_pr_count integer default 0, p_duration_min integer default null, p_performed_at timestamptz default null
) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
  v_title text := coalesce(nullif(left(btrim(coalesce(p_title, '')), 80), ''), 'Entrenamiento');
  v_exercises jsonb := coalesce(p_exercises, '[]'::jsonb);
  v_performed timestamptz := least(coalesce(p_performed_at, now()), now());
begin
  if p_day < current_date - 800 or p_day > current_date + 2 then
    raise exception 'Fecha fuera de rango';
  end if;
  if jsonb_typeof(v_exercises) <> 'array' or length(v_exercises::text) > 50000 then
    raise exception 'Entrenamiento inválido';
  end if;
  insert into workouts as w (id, user_id, day, title, exercises, pr_count, duration_min, performed_at)
  values (p_id, uid, p_day, v_title, v_exercises, greatest(coalesce(p_pr_count, 0), 0), p_duration_min, v_performed)
  on conflict (id) do update set
    day = excluded.day,
    title = excluded.title,
    exercises = excluded.exercises,
    pr_count = excluded.pr_count,
    duration_min = excluded.duration_min,
    updated_at = now()
  where w.user_id = uid
    and (w.day, w.title, w.exercises, w.pr_count, w.duration_min)
      is distinct from (excluded.day, excluded.title, excluded.exercises, excluded.pr_count, excluded.duration_min);
end $$;

create or replace function public.delete_workout(p_id uuid) returns void
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_uid();
begin
  delete from workouts w where w.id = p_id and w.user_id = uid;
end $$;

-- Toggles my reaction on a post: same emoji again removes it, another emoji replaces it.
create or replace function public.react(
  p_group uuid, p_target uuid, p_post_type text, p_post_id text, p_emoji text
) returns json
language plpgsql security definer set search_path = public as $$
declare
  uid uuid := public.require_member(p_group);
  v_emoji text := left(btrim(coalesce(p_emoji, '')), 16);
begin
  if p_post_type not in ('food', 'workout', 'weight') or coalesce(p_post_id, '') = '' then
    raise exception 'Publicación inválida';
  end if;
  if not exists (select 1 from group_members m where m.group_id = p_group and m.user_id = p_target) then
    raise exception 'Esa persona ya no está en el grupo';
  end if;
  if v_emoji = '' or exists (
    select 1 from post_reactions r
    where r.group_id = p_group and r.target_user = p_target and r.post_type = p_post_type
      and r.post_id = p_post_id and r.user_id = uid and r.emoji = v_emoji
  ) then
    delete from post_reactions r
    where r.group_id = p_group and r.target_user = p_target and r.post_type = p_post_type
      and r.post_id = p_post_id and r.user_id = uid;
    return json_build_object('emoji', null);
  end if;
  insert into post_reactions as r (group_id, target_user, post_type, post_id, user_id, emoji)
  values (p_group, p_target, p_post_type, p_post_id, uid, v_emoji)
  on conflict (group_id, target_user, post_type, post_id, user_id)
  do update set emoji = excluded.emoji, created_at = now();
  return json_build_object('emoji', v_emoji);
end $$;

-- The group's social feed: days of food, workouts and weigh-ins, newest first.
create or replace function public.group_feed(
  p_group uuid, p_before timestamptz default null, p_limit integer default 30
) returns json
language plpgsql stable security definer set search_path = public as $$
declare
  uid uuid := public.require_member(p_group);
  v_before timestamptz := coalesce(p_before, 'infinity'::timestamptz);
  v_limit integer := least(greatest(coalesce(p_limit, 30), 1), 100);
begin
  return coalesce((
    select json_agg(f order by f.at desc) from (
      select posts.post_type, posts.post_id, posts.user_id, posts.day, posts.at, posts.data,
             coalesce(pr.display_name, 'Anónimo') as display_name,
             coalesce((
               select jsonb_object_agg(x.emoji, x.n) from (
                 select r.emoji, count(*) as n from post_reactions r
                 where r.group_id = p_group and r.target_user = posts.user_id
                   and r.post_type = posts.post_type and r.post_id = posts.post_id
                 group by r.emoji
               ) x
             ), '{}'::jsonb) as reactions,
             (
               select r.emoji from post_reactions r
               where r.group_id = p_group and r.target_user = posts.user_id and r.post_type = posts.post_type
                 and r.post_id = posts.post_id and r.user_id = uid
             ) as my_reaction,
             (
               select count(*) from meal_comments c
               where c.group_id = p_group and c.target_user = posts.user_id
                 and c.post_type = posts.post_type and c.post_id = posts.post_id
             ) as comment_count
      from (
        select * from (
          select 'food'::text as post_type, s.day::text as post_id, s.user_id, s.day, s.updated_at as at,
                 jsonb_build_object(
                   'calories', s.calories, 'protein', s.protein, 'carbs', s.carbs, 'fat', s.fat,
                   'target_calories', s.target_calories, 'target_protein', s.target_protein,
                   'target_carbs', s.target_carbs, 'target_fat', s.target_fat, 'meals', s.meals
                 ) as data
          from daily_summaries s
          join group_members m on m.user_id = s.user_id and m.group_id = p_group
          where s.updated_at < v_before and (s.calories > 0 or jsonb_array_length(s.meals) > 0)
          union all
          select 'workout', w.id::text, w.user_id, w.day, coalesce(w.performed_at, w.created_at),
                 jsonb_build_object('title', w.title, 'exercises', w.exercises,
                                    'pr_count', w.pr_count, 'duration_min', w.duration_min)
          from workouts w
          join group_members m on m.user_id = w.user_id and m.group_id = p_group
          where coalesce(w.performed_at, w.created_at) < v_before
          union all
          select 'weight', l.day::text, l.user_id, l.day, l.updated_at,
                 jsonb_build_object(
                   'kg', l.kg, 'goal', pw.goal_weight, 'start', pw.start_weight,
                   'fat', l.fat_pct, 'muscle', l.muscle_pct, 'bone', l.bone_kg,
                   'previous', (select l2.kg from weight_logs l2
                                where l2.user_id = l.user_id and l2.day < l.day order by l2.day desc limit 1),
                   'previous_fat', (select l2.fat_pct from weight_logs l2
                                    where l2.user_id = l.user_id and l2.day < l.day and l2.fat_pct is not null
                                    order by l2.day desc limit 1),
                   'previous_muscle', (select l2.muscle_pct from weight_logs l2
                                       where l2.user_id = l.user_id and l2.day < l.day and l2.muscle_pct is not null
                                       order by l2.day desc limit 1)
                 )
          from weight_logs l
          join group_members m on m.user_id = l.user_id and m.group_id = p_group
          left join profiles pw on pw.id = l.user_id
          -- Old weigh-ins uploaded later (backup import, first sync) do not flood the feed.
          where l.updated_at < v_before and coalesce(pw.share_weight, true)
            and l.day >= (l.updated_at at time zone 'utc')::date - 2
        ) u
        order by u.at desc
        limit v_limit
      ) posts
      left join profiles pr on pr.id = posts.user_id
    ) f
  ), '[]'::json);
end $$;

-- Comments of one post of the feed.
create or replace function public.post_comments(
  p_group uuid, p_target uuid, p_post_type text, p_post_id text
) returns table (
  id uuid,
  target_user uuid,
  day date,
  meal_name text,
  author_id uuid,
  author_name text,
  body text,
  created_at timestamptz,
  post_type text,
  post_id text
)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.require_member(p_group);
  return query
    select c.id, c.target_user, c.day, c.meal_name, c.author_id,
           coalesce(p.display_name, 'Anónimo'), c.body, c.created_at, c.post_type, c.post_id
    from meal_comments c
    left join profiles p on p.id = c.author_id
    where c.group_id = p_group and c.target_user = p_target
      and c.post_type = p_post_type and c.post_id = p_post_id
    order by c.created_at
    limit 300;
end $$;

-- A member's weight history (if shared) and recent workouts, for their profile in the group.
create or replace function public.member_progress(p_group uuid, p_user uuid) returns json
language plpgsql stable security definer set search_path = public as $$
declare
  v_share boolean;
begin
  perform public.require_member(p_group);
  if not exists (select 1 from group_members m where m.group_id = p_group and m.user_id = p_user) then
    raise exception 'Esa persona ya no está en el grupo';
  end if;
  select coalesce(p.share_weight, true) into v_share from profiles p where p.id = p_user;
  v_share := coalesce(v_share, true);
  return json_build_object(
    'share_weight', v_share,
    'goal_weight', case when v_share then (select p.goal_weight from profiles p where p.id = p_user) end,
    'start_weight', case when v_share then (select p.start_weight from profiles p where p.id = p_user) end,
    'weights', case when v_share then coalesce((
      select json_agg(json_build_object('day', l.day, 'kg', l.kg, 'fat', l.fat_pct, 'muscle', l.muscle_pct, 'bone', l.bone_kg)
                      order by l.day)
      from weight_logs l where l.user_id = p_user and l.day >= current_date - 180
    ), '[]'::json) else '[]'::json end,
    'workouts', coalesce((
      select json_agg(w2 order by w2.day desc, w2.at desc) from (
        select w.id, w.day, w.title, w.exercises, w.pr_count, w.duration_min,
               coalesce(w.performed_at, w.created_at) as at
        from workouts w where w.user_id = p_user
        order by w.day desc, coalesce(w.performed_at, w.created_at) desc
        limit 30
      ) w2
    ), '[]'::json)
  );
end $$;

-- Gym ranking: workouts, records and kilos moved per member since p_from.
create or replace function public.group_gym_stats(p_group uuid, p_from date)
returns table (user_id uuid, workouts integer, prs integer, volume double precision)
language plpgsql stable security definer set search_path = public as $$
begin
  perform public.require_member(p_group);
  return query
    select w.user_id, count(*)::integer, coalesce(sum(w.pr_count), 0)::integer,
           coalesce(sum((
             select sum(case when jsonb_typeof(st->'reps') = 'number' and jsonb_typeof(st->'weight') = 'number'
                             then (st->>'reps')::double precision * (st->>'weight')::double precision else 0 end)
             from jsonb_array_elements(case when jsonb_typeof(w.exercises) = 'array' then w.exercises else '[]'::jsonb end) e,
                  jsonb_array_elements(case when jsonb_typeof(e->'sets') = 'array' then e->'sets' else '[]'::jsonb end) st
           )), 0)::double precision
    from workouts w
    join group_members m on m.user_id = w.user_id and m.group_id = p_group
    where w.day >= p_from
    group by w.user_id;
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
revoke all on function public.add_meal_comment(uuid, uuid, date, text, text, text, text) from public, anon;
revoke all on function public.set_weight_goal(double precision, double precision, boolean) from public, anon;
revoke all on function public.upsert_weight(date, double precision, double precision, double precision, double precision) from public, anon;
revoke all on function public.delete_weight(date) from public, anon;
revoke all on function public.upsert_workout(uuid, date, text, jsonb, integer, integer, timestamptz) from public, anon;
revoke all on function public.post_comments(uuid, uuid, text, text) from public, anon;
revoke all on function public.member_progress(uuid, uuid) from public, anon;
revoke all on function public.group_gym_stats(uuid, date) from public, anon;
revoke all on function public.delete_workout(uuid) from public, anon;
revoke all on function public.react(uuid, uuid, text, text, text) from public, anon;
revoke all on function public.group_feed(uuid, timestamptz, integer) from public, anon;
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
grant execute on function public.add_meal_comment(uuid, uuid, date, text, text, text, text) to authenticated;
grant execute on function public.set_weight_goal(double precision, double precision, boolean) to authenticated;
grant execute on function public.upsert_weight(date, double precision, double precision, double precision, double precision) to authenticated;
grant execute on function public.delete_weight(date) to authenticated;
grant execute on function public.upsert_workout(uuid, date, text, jsonb, integer, integer, timestamptz) to authenticated;
grant execute on function public.post_comments(uuid, uuid, text, text) to authenticated;
grant execute on function public.member_progress(uuid, uuid) to authenticated;
grant execute on function public.group_gym_stats(uuid, date) to authenticated;
grant execute on function public.delete_workout(uuid) to authenticated;
grant execute on function public.react(uuid, uuid, text, text, text) to authenticated;
grant execute on function public.group_feed(uuid, timestamptz, integer) to authenticated;
grant execute on function public.delete_meal_comment(uuid) to authenticated;
grant execute on function public.group_comments(uuid, date, date) to authenticated;
grant execute on function public.group_activity(timestamptz) to authenticated;

notify pgrst, 'reload schema';
