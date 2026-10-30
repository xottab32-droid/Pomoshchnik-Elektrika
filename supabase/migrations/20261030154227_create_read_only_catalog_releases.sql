-- Supabase migration applied to project olqazmqalxtqznehpmkb as 20261030154227.
-- Public releases are read-only; client credentials cannot write any rows.
create table if not exists public.catalog_releases (
  version text primary key,
  payload jsonb not null,
  published boolean not null default false,
  published_at timestamptz,
  created_at timestamptz not null default now(),
  constraint catalog_version_valid check (length(version) between 1 and 64),
  constraint catalog_published_at_valid check (not published or published_at is not null),
  constraint catalog_payload_version check (payload->>'version' = version),
  constraint catalog_payload_format check (payload->>'format' = 'ru.pomoshchnik.elektrika.catalog'),
  constraint catalog_payload_schema check (payload->>'schemaVersion' = '1'),
  constraint catalog_payload_items check (jsonb_typeof(payload->'items') = 'array')
);
create index if not exists catalog_releases_published_at_idx
  on public.catalog_releases (published_at desc) where published = true;
alter table public.catalog_releases enable row level security;
revoke all on table public.catalog_releases from public, anon, authenticated;
grant usage on schema public to anon;
grant select on table public.catalog_releases to anon;
grant select, insert, update, delete on table public.catalog_releases to service_role;
create policy published_catalog_releases_are_readable
  on public.catalog_releases for select to anon using (published = true);
