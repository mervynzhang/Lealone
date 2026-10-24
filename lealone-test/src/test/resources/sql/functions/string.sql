-- 字符串函数: LENGTH/UPPER/LOWER/SUBSTRING/CONCAT/TRIM/REPLACE/LIKE 等
-- 期望输出的语法说明见 com.lealone.test.script.SqlScriptTest

-- LENGTH返回字符数(不是字节数)，NULL输入返回NULL
select length('abc') c1, length('') c2, length(null) c3;
> C1 C2 C3
> -- -- ----
> 3  0  null
> rows: 1

select length('中文') c1;
> C1
> --
> 2
> rows: 1

select char_length('abc') c1, character_length('abc') c2;
> C1 C2
> -- --
> 3  3
> rows: 1

select octet_length('abc') c1;
> C1
> --
> 3
> rows: 1

select bit_length('abc') c1;
> C1
> --
> 24
> rows: 1

-- 多字节字符按UTF-8计算：中=3字节，emoji=4字节
select octet_length('中文') c1, octet_length('😀') c2;
> C1 C2
> -- --
> 6  4
> rows: 1

-- UPPER/LOWER及其同义词UCASE/LCASE
select upper('aBc') c1, lower('AbC') c2, ucase('aBc') c3, lcase('AbC') c4;
> C1  C2  C3  C4
> --- --- --- ---
> ABC abc ABC abc
> rows: 1

select upper(null) c1, lower(null) c2;
> C1   C2
> ---- ----
> null null
> rows: 1

-- SUBSTRING: 两种写法等价；起始位置为0或负数时和H2的结果一致，越界或长度为负返回空串
select substring('abcdef', 2, 3) c1, substring('abcdef', 2) c2;
> C1  C2
> --- -----
> bcd bcdef
> rows: 1

select substring('abcdef' from 2 for 3) c1;
> C1
> ---
> bcd
> rows: 1

select substring('abcdef', 0, 3) c1;
> C1
> ---
> abc
> rows: 1

select substring('abcdef', -1, 3) c1;
> C1
> --
> f
> rows: 1

select substring('abcdef', 10, 3) c1;
> C1
> --
>
> rows: 1

select substring('abcdef', 2, -1) c1;
> C1
> --
>
> rows: 1

-- CONCAT可以接受多个参数，有NULL参数时忽略该参数
select concat('a', 'b') c1, concat('a', 'b', 'c') c2;
> C1 C2
> -- ---
> ab abc
> rows: 1

select concat('a', null) c1;
> C1
> --
> a
> rows: 1

-- LEALONE: 与H2不同，参数全为NULL时Lealone返回NULL，H2返回空串
select concat(null, null) c1;
> C1
> ----
> null
> rows: 1

-- || 连接符遇到NULL时结果整体为NULL
select 'a' || 'b' || 'c' c1;
> C1
> ---
> abc
> rows: 1

select 'a' || null c1;
> C1
> ----
> null
> rows: 1

-- CONCAT_WS用指定的分隔符连接
select concat_ws('-', 'a', 'b') c1;
> C1
> ---
> a-b
> rows: 1

-- TRIM/LTRIM/RTRIM，TRIM支持LEADING/TRAILING/BOTH
select trim('  ab  ') c1, ltrim('  ab  ') c2, rtrim('  ab  ') c3;
> C1 C2  C3
> -- --- ---
> ab ab   ab
> rows: 1

select trim(leading 'a' from 'aaab') c1, trim(trailing 'b' from 'abbb') c2;
> C1 C2
> -- --
> b  a
> rows: 1

select trim(both 'a' from 'aabaa') c1;
> C1
> --
> b
> rows: 1

select trim(leading from '  ab  ') c1;
> C1
> ---
> ab
> rows: 1

-- REPLACE: 没有匹配的字符时原样返回
select replace('aXbXc', 'X', '-') c1, replace('abc', 'X', '-') c2;
> C1    C2
> ----- ---
> a-b-c abc
> rows: 1

select replace('abc', '', '-') c1;
> C1
> ---
> abc
> rows: 1

-- LIKE: %匹配任意长度，_匹配单个字符，默认区分大小写
select 'abc' like 'a%' c1, 'abc' like '%c' c2, 'abc' like 'a_c' c3, 'abc' like '%b%' c4;
> C1   C2   C3   C4
> ---- ---- ---- ----
> TRUE TRUE TRUE TRUE
> rows: 1

select 'abc' like 'a%c' c1, 'abc' like '' c2, 'abc' like 'abcd' c3;
> C1   C2    C3
> ---- ----- -----
> TRUE FALSE FALSE
> rows: 1

select 'ABC' like 'a%' c1;
> C1
> -----
> FALSE
> rows: 1

select 'abc' not like 'x%' c1;
> C1
> ----
> TRUE
> rows: 1

-- ESCAPE指定转义字符
select 'a%b' like 'a\%b' escape '\' c1;
> C1
> ----
> TRUE
> rows: 1

select 'axb' like 'a\%b' escape '\' c1;
> C1
> -----
> FALSE
> rows: 1

-- REPEAT/LEFT/RIGHT/LPAD/RPAD
select repeat('ab', 3) c1, left('abcdef', 2) c2, right('abcdef', 2) c3;
> C1     C2 C3
> ------ -- --
> ababab ab ef
> rows: 1

select lpad('x', 3, 'ab') c1, rpad('x', 3, 'ab') c2;
> C1  C2
> --- ---
> aax xaa
> rows: 1

-- 数值参数会先转成字符串再计算长度
select length(123) c1;
> C1
> --
> 3
> rows: 1

-- ASCII/CHAR/DIFFERENCE/INSTR/POSITION/LOCATE
select ascii('A') c1, char(65) c2, difference('abc', 'abd') c3;
> C1 C2 C3
> -- -- --
> 65 A  3
> rows: 1

select instr('abc', 'b') c1, position('b' in 'abc') c2, locate('b', 'abc') c3;
> C1 C2 C3
> -- -- --
> 2  2  2
> rows: 1

