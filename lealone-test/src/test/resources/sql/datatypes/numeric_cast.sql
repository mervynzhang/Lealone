-- CAST与数值类型转换的已知行为
-- 期望输出的语法说明见 com.lealone.test.script.SqlScriptTest

-- 整数/大整数/字符串/小数之间的转换
select cast(1 as bigint) c;
> C
> -
> 1
> rows: 1

select cast('123' as int) c;
> C
> ---
> 123
> rows: 1

select cast(1 as varchar) c;
> C
> -
> 1
> rows: 1

select cast(1.50 as decimal(5,2)) c;
> C
> ----
> 1.50
> rows: 1

select cast(cast(1 as bigint) as varchar) c;
> C
> -
> 1
> rows: 1

select cast('1.5' as double) c;
> C
> ---
> 1.5
> rows: 1

select cast(123 as tinyint) c;
> C
> ---
> 123
> rows: 1

-- 小数转整数时四舍五入
select cast(1.9 as int) c1, cast(-1.9 as int) c2;
> C1 C2
> -- --
> 2  -2
> rows: 1

select cast(2.5 as int) c1, cast(3.5 as int) c2, cast(-2.5 as int) c3;
> C1 C2 C3
> -- -- --
> 3  4  -3
> rows: 1

select cast(1.234 as decimal(5,2)) c;
> C
> ----
> 1.23
> rows: 1

-- 整数除法截断
select 1/2 c1, 7 % 3 c2, -7 % 3 c3;
> C1 C2 C3
> -- -- --
> 0  1  -1
> rows: 1

-- LEALONE: 与H2不同，Lealone把1.0/2算成DECIMAL 0.5，H2算成0.500000000000000000000
select 1.0/2 c1, 1/2.0 c2;
> C1  C2
> --- ---
> 0.5 0.5
> rows: 1

-- 布尔和日期类型转换
select cast(1 as boolean) c1, cast(0 as boolean) c2, cast('true' as boolean) c3;
> C1   C2    C3
> ---- ----- ----
> TRUE FALSE TRUE
> rows: 1

select cast('2020-01-02' as date) c;
> C
> ----------
> 2020-01-02
> rows: 1

select cast(null as int) c1, cast(null as varchar) c2;
> C1   C2
> ---- ----
> null null
> rows: 1

-- 转换失败: 22018数据转换错误，22003数值越界，22007日期格式错误，22012除零
select cast('abc' as int) c;
> exception 22018

select cast('x' as boolean) c;
> exception 22018

select cast(1234567890123 as int) c;
> exception 22003

select cast('2020-13-01' as date) c;
> exception 22007

select 1/0 c;
> exception 22012

-- LEALONE: 与H2不同，ROUND对负数按Math.round(向正无穷)舍入，-1.55保留1位得到-1.5、
-- -2.5得到-2.0，H2用HALF_UP得到-1.6和-3
select round(1.55, 1) c1, round(-1.55, 1) c2, round(2.5) c3, round(-2.5) c4;
> C1  C2   C3  C4
> --- ---- --- ----
> 1.6 -1.5 3.0 -2.0
> rows: 1

-- LEALONE: 与H2不同，FLOOR/CEILING返回DOUBLE并带.0后缀(1.0/2.0)，H2返回1和2
select floor(1.9) c1, ceiling(1.1) c2, abs(-5) c3;
> C1  C2  C3
> --- --- --
> 1.0 2.0 5
> rows: 1

