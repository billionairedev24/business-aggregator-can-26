-- make db-create: the role and database the apps use, on a local Postgres, created only when missing (never drops,
-- never changes an existing role's password). Run as a superuser against the `postgres` database:
--   psql -v ON_ERROR_STOP=1 -v owner=northline -v password=… -v db=northline -d postgres -f db/local/create.sql
-- \gexec runs the statement a query returns, so names and the password are quoted by format() (%I, %L).

select format('create role %I login password %L', :'owner', :'password')
 where not exists (select from pg_roles where rolname = :'owner') \gexec

select format('create database %I owner %I', :'db', :'owner')
 where not exists (select from pg_database where datname = :'db') \gexec

\connect :"db"
set client_min_messages = warning;

-- PostGIS is not a trusted extension, so the superuser creates it; the migrations then find all three in place.
create extension if not exists postgis;
create extension if not exists citext;
create extension if not exists pgcrypto;

select format('grant all on schema public to %I', :'owner') \gexec
