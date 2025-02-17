/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh, H2 Group
 */
 
drop schema if exists pg_catalog;
create schema pg_catalog;

drop alias if exists pg_convertType;
create alias pg_convertType deterministic for "org.lealone.plugins.postgresql.sql.PgAlias.convertType";

drop alias if exists pg_get_oid;
create alias pg_get_oid deterministic for "org.lealone.plugins.postgresql.sql.PgAlias.getOid";

create table pg_catalog.pg_version as select 2 as version, 2 as version_read;
--create table pg_catalog.pg_version(version int, version_read int);
--insert into pg_catalog.pg_version(version,version_read) values(2,2);
grant select on pg_catalog.pg_version to public;

create view pg_catalog.pg_roles -- (oid, rolname, rolcreaterole, rolcreatedb)
as
select
    id oid,
    cast(name as varchar_ignorecase) rolname,
    case when admin then 't' else 'f' end as rolcreaterole,
    case when admin then 't' else 'f' end as rolcreatedb
from information_schema.users;
grant select on pg_catalog.pg_roles to public;

create view pg_catalog.pg_namespace -- (oid, nspname)
as
select
    id oid,
    cast(schema_name as varchar_ignorecase) nspname
from information_schema.schemas;
grant select on pg_catalog.pg_namespace to public;

create table pg_catalog.pg_type(
    oid int primary key,
    typname varchar_ignorecase,
    typnamespace int,
    typlen int,
    typrelid int,
    typtype varchar,
    typbasetype int,
    typtypmod int);
grant select on pg_catalog.pg_type to public;

insert into pg_catalog.pg_type
select
    pg_convertType(data_type) oid,
    cast(type_name as varchar_ignorecase) typname,
    (select oid from pg_catalog.pg_namespace where nspname = 'pg_catalog') typnamespace,
    -1 typlen,
    0 typrelid,
    'c' typtype,
    0 typbasetype,
    -1 typtypmod
from information_schema.type_info
where pos = 0
    and pg_convertType(data_type) <> 705; -- not unknown

merge into pg_catalog.pg_type values(
    19,
    'name',
    (select oid from pg_catalog.pg_namespace where nspname = 'pg_catalog'),
    -1,
    0,
    'c',
    0,
    -1
);
merge into pg_catalog.pg_type values(
    0,
    'null',
    (select oid from pg_catalog.pg_namespace where nspname = 'pg_catalog'),
    -1,
    0,
    'c',
    0,
    -1
);
merge into pg_catalog.pg_type values(
    22,
    'int2vector',
    (select oid from pg_catalog.pg_namespace where nspname = 'pg_catalog'),
    -1,
    0,
    'c',
    0,
    -1
);

-- (oid, relname, relnamespace, relkind, relam, reltuples, relpages, relhasrules, relhasoids)
create view pg_catalog.pg_class
as
select
    id oid,
    cast(table_name as varchar_ignorecase) relname,
    cast((select id from information_schema.schemas where schema_name = table_schema) as varchar_ignorecase)
    as relnamespace,
    case table_type when 'TABLE' then 'r' else 'v' end relkind,
    0 relam,
    cast(0 as float) reltuples,
    0 relpages,
    false relhasrules,
    false relhasoids
from information_schema.tables
union all
select
    id oid,
    cast(index_name as varchar_ignorecase) relname,
    cast((select id from information_schema.schemas where schema_name = table_schema) as varchar_ignorecase)
    as relnamespace,
    'i' relkind,
    0 relam,
    cast(0 as float) reltuples,
    0 relpages,
    false relhasrules,
    false relhasoids
from information_schema.indexes;
grant select on pg_catalog.pg_class to public;

create table pg_catalog.pg_proc(
    oid int,
    proname varchar_ignorecase,
    prorettype int,
    pronamespace int
);
grant select on pg_catalog.pg_proc to public;

create table pg_catalog.pg_trigger(
    oid int,
    tgconstrrelid int,
    tgfoid int,
    tgargs int,
    tgnargs int,
    tgdeferrable boolean,
    tginitdeferred boolean,
    tgconstrname varchar_ignorecase,
    tgrelid int
);
grant select on pg_catalog.pg_trigger to public;

create view pg_catalog.pg_attrdef -- (oid, adsrc, adrelid, adnum)
as
select
    id oid,
    0 adsrc,
    0 adrelid,
    0 adnum
from information_schema.tables where 1=0;
grant select on pg_catalog.pg_attrdef to public;

-- (oid, attrelid, attname, atttypid, attlen, attnum, atttypmod, attnotnull, attisdropped, atthasdef)
create view pg_catalog.pg_attribute
as
select
    t.id*10000 + c.ordinal_position oid,
    t.id attrelid,
    c.column_name attname,
    pg_convertType(data_type) atttypid,
    case when numeric_precision > 255 then -1 else numeric_precision end attlen,
    c.ordinal_position attnum,
    -1 atttypmod,
    case c.is_nullable when 'YES' then false else true end attnotnull,
    false attisdropped,
    false atthasdef
from information_schema.tables t, information_schema.columns c
where t.table_name = c.table_name
and t.table_schema = c.table_schema
union all
select
    1000000 + t.id*10000 + c.ordinal_position oid,
    i.id attrelid,
    c.column_name attname,
    pg_convertType(data_type) atttypid,
    case when numeric_precision > 255 then -1 else numeric_precision end attlen,
    c.ordinal_position attnum,
    -1 atttypmod,
    case c.is_nullable when 'YES' then false else true end attnotnull,
    false attisdropped,
    false atthasdef
from information_schema.tables t, information_schema.indexes i, information_schema.columns c
where t.table_name = i.table_name
and t.table_schema = i.table_schema
and t.table_name = c.table_name
and t.table_schema = c.table_schema;
grant select on pg_catalog.pg_attribute to public;

 -- (oid, indexrelid, indrelid, indisclustered, indisunique, indisprimary, indexprs, indkey)
create view pg_catalog.pg_index
as
select
    i.id oid,
    i.id indexrelid,
    t.id indrelid,
    false indisclustered,
    not non_unique indisunique,
    primary_key indisprimary,
    cast('' as varchar_ignorecase) indexprs,
    cast(1 as array) indkey
from information_schema.indexes i, information_schema.tables t
where i.table_schema = t.table_schema
and i.table_name = t.table_name
and i.ordinal_position = 1
-- workaround for MS Access problem opening tables with primary key
and 1=0;
grant select on pg_catalog.pg_index to public;

drop alias if exists pg_get_indexdef;
create alias pg_get_indexdef for "org.lealone.plugins.postgresql.sql.PgAlias.getIndexColumn";

drop alias if exists current_schema;
create alias current_schema for "org.lealone.plugins.postgresql.sql.PgAlias.getCurrentSchema";

drop alias if exists pg_encoding_to_char;
create alias pg_encoding_to_char for "org.lealone.plugins.postgresql.sql.PgAlias.getEncodingName";

drop alias if exists pg_char_to_encoding;
create alias pg_char_to_encoding for "org.lealone.plugins.postgresql.sql.PgAlias.getEncodingCode";

drop alias if exists format_type;
create alias format_type for "org.lealone.plugins.postgresql.sql.PgAlias.formatType";

drop alias if exists pg_postmaster_start_time;
create alias pg_postmaster_start_time for "org.lealone.plugins.postgresql.sql.PgAlias.getStartTime";

drop alias if exists pg_get_userbyid;
create alias pg_get_userbyid for "org.lealone.plugins.postgresql.sql.PgAlias.getUserById";

drop alias if exists has_database_privilege;
create alias has_database_privilege for "org.lealone.plugins.postgresql.sql.PgAlias.hasDatabasePrivilege";

drop alias if exists has_table_privilege;
create alias has_table_privilege for "org.lealone.plugins.postgresql.sql.PgAlias.hasTablePrivilege";

drop alias if exists currtid2;
create alias currtid2 for "org.lealone.plugins.postgresql.sql.PgAlias.getCurrentTid";

create table pg_catalog.pg_database(
    oid int,
    datname varchar_ignorecase,
    encoding int,
    datlastsysoid int,
    datallowconn boolean,
    datconfig array, -- text[]
    datacl array, -- aclitem[]
    datdba int,
    dattablespace int
);
grant select on pg_catalog.pg_database to public;

insert into pg_catalog.pg_database values(
    0, -- oid
    'postgres', -- datname
    6, -- encoding, UTF8
    100000, -- datlastsysoid
    true, -- datallowconn
    null, -- datconfig
    null, -- datacl
    select min(id) from information_schema.users where admin=true, -- datdba
    0 -- dattablespace
);

create table pg_catalog.pg_tablespace(
    oid int,
    spcname varchar_ignorecase,
    spclocation varchar_ignorecase,
    spcowner int,
    spcacl array -- aclitem[]
);
grant select on pg_catalog.pg_tablespace to public;

insert into pg_catalog.pg_tablespace values(
    0,
    'main', -- spcname
    '?', -- spclocation
    0, -- spcowner,
    null -- spcacl
);

create table pg_catalog.pg_settings(
    oid int,
    name varchar_ignorecase,
    setting varchar_ignorecase
);
grant select on pg_catalog.pg_settings to public;

insert into pg_catalog.pg_settings values
(0, 'autovacuum', 'on'),
(1, 'stats_start_collector', 'on'),
(2, 'stats_row_level', 'on');

create view pg_catalog.pg_user -- oid, usename, usecreatedb, usesuper
as
select
    id oid,
    cast(name as varchar_ignorecase) usename,
    true usecreatedb,
    true usesuper
from information_schema.users;
grant select on pg_catalog.pg_user to public;

create table pg_catalog.pg_authid(
    oid int,
    rolname varchar_ignorecase,
    rolsuper boolean,
    rolinherit boolean,
    rolcreaterole boolean,
    rolcreatedb boolean,
    rolcatupdate boolean,
    rolcanlogin boolean,
    rolconnlimit boolean,
    rolpassword boolean,
    rolvaliduntil timestamp, -- timestamptz
    rolconfig array -- text[]
);
grant select on pg_catalog.pg_authid to public;

create table pg_catalog.pg_am(oid int, amname varchar_ignorecase);
grant select on pg_catalog.pg_am to public;
insert into  pg_catalog.pg_am values(0, 'btree');
insert into  pg_catalog.pg_am values(1, 'hash');

create table pg_catalog.pg_description -- (objoid, objsubid, classoid, description)
as
select
    oid objoid,
    0 objsubid,
    -1 classoid,
    cast(datname as varchar_ignorecase) description
from pg_catalog.pg_database;
grant select on pg_catalog.pg_description to public;

create table pg_catalog.pg_group -- oid, groname
as
select
    0 oid,
    cast('' as varchar_ignorecase) groname
from pg_catalog.pg_database where 1=0;
grant select on pg_catalog.pg_group to public;

create table pg_catalog.pg_conversion(
    oid oid,
    conname varchar_ignorecase,
    connamespace oid,
    conowner oid,
    conforencoding int,
    contoencoding  int,
    conproc oid,
    condefault boolean
);
grant select on pg_catalog.pg_conversion to public;
