-- DDL基本往返: CREATE/ALTER/DROP，以及约束违反和对象不存在的错误码
-- 期望输出的语法说明见 com.lealone.test.script.SqlScriptTest

-- 注意: Lealone的DDL执行后更新计数是0，所以期望写成 update count: 0，H2脚本里是 ok
create table t(id int primary key, name varchar(20) not null, v int default 7);
> update count: 0

insert into t(id, name) values(1, 'a');
> update count: 1

-- 没有指定的列使用默认值
select * from t;
> ID NAME V
> -- ---- -
> 1  a    7
> rows: 1

insert into t(id, name, v) values(2, 'b', 8);
> update count: 1

select id, name, v from t order by id;
> ID NAME V
> -- ---- -
> 1  a    7
> 2  b    8
> rows (ordered): 2

update t set v = 9 where id = 1;
> update count: 1

delete from t where id = 2;
> update count: 1

select id, v from t order by id;
> ID V
> -- -
> 1  9
> rows (ordered): 1

-- ALTER TABLE: 增加列、重命名表、重命名列、删除列
alter table t add column c int;
> update count: 0

insert into t(id, name, c) values(3, 'c', 30);
> update count: 1

select id, c from t order by id;
> ID C
> -- ----
> 1  null
> 3  30
> rows (ordered): 2

alter table t rename to t2;
> update count: 0

select count(*) c from t2;
> C
> -
> 2
> rows: 1

alter table t2 rename column c to c2;
> update count: 0

select id, c2 from t2 order by id;
> ID C2
> -- ----
> 1  null
> 3  30
> rows (ordered): 2

alter table t2 drop column c2;
> update count: 0

select * from t2 order by id;
> ID NAME V
> -- ---- -
> 1  a    9
> 3  c    7
> rows (ordered): 2

-- CREATE TABLE AS SELECT
create table t3 as select 1 id, 'x' name;
> update count: 0

select * from t3;
> ID NAME
> -- ----
> 1  x
> rows: 1

-- 索引的创建和删除
create index idx_t2_name on t2(name);
> update count: 0

drop index idx_t2_name;
> update count: 0

-- 主键冲突和NOT NULL约束违反
insert into t2(id, name) values(1, 'dup');
> exception 23505

insert into t2(id, name) values(4, null);
> exception 23502

select count(*) c from t2;
> C
> -
> 2
> rows: 1

drop table t2;
> update count: 0

-- 对象不存在
select count(*) c from t2;
> exception 42102

-- 对象已存在
create table t4(id int);
> update count: 0

create table t4(id int);
> exception 42101

-- 列数和值个数不匹配
insert into t4(id) values(1, 2);
> exception 21002

drop table t4;
> update count: 0

drop table t3;
> update count: 0

