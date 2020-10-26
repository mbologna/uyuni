#!/usr/bin/python3

OUTPUT_SQL_SCRIPT_FILENAME = 'debian_version_database_comparator_tests.sql'

preamble = """
-- launch me with spacewalk-sql -i < {}
DO
$$
declare
	result NUMERIC := 0;
begin

""".format(OUTPUT_SQL_SCRIPT_FILENAME)

conclusion = """
end;
$$
"""

# source: https://git.dpkg.org/cgit/dpkg/dpkg.git/tree/scripts/t/Dpkg_Version.t
tests = """1.0-1 2.0-2 -1
2.2~rc-4 2.2-1 -1
2.2-1 2.2~rc-4 1
1.0000-1 1.0-1 0
0foo 0foo 0
0foo-0 0foo 0
0foo 0foo-0 0
0foo 0fo 1
0foo-0 0foo+ -1
0foo~1 0foo -1
0foo~foo+Bar 0foo~foo+bar -1
0foo~~ 0foo~ -1
1~ 1 -1
12345+that-really-is-some-ver-0 12345+that-really-is-some-ver-10 -1
0foo-0 0foo-01 -1
0foo.bar 0foobar 1
0foo.bar 0foo1bar 1
0foo.bar 0foo0bar 1
0foo1bar-1 0foobar-1 -1
0foo2.0 0foo2 1
0foo2.0.0 0foo2.10.0 -1
0foo2.0 0foo2.0.0 -1
0foo2.0 0foo2.10 -1
0foo2.1 0foo2.10 -1
1.09 1.9 0
1.0.8+nmu1 1.0.8 1
3.11 3.10+nmu1 1
0.9j-20080306-4 0.9i-20070324-2 1
1.2.0~b7-1 1.2.0~b6-1 1
1.011-1 1.06-2 1
0.0.9+dfsg1-1 0.0.8+dfsg1-3 1
4.6.99+svn6582-1 4.6.99+svn6496-1 1
53 52 1
0.9.9~pre122-1 0.9.9~pre111-1 1
1.0.1+gpl-1 1.0.1-2 1
1a 1000a -1"""

with open(OUTPUT_SQL_SCRIPT_FILENAME, 'w') as f:
    f.write(preamble)

    for test in tests.split('\n'):
        operand1, operand2, expected_result = test.split(' ')
        statement = """--
    select * into result from deb.debstrcmp('{}', '{}');

    if result <> {}
    then
        raise notice 'deb.debstrcmp({}, {}) should be {}';
    end if;
    --
    """.format(operand1, operand2, expected_result, operand1, operand2, expected_result)
        f.write(statement)
    print("SQL script generated: {}".format(OUTPUT_SQL_SCRIPT_FILENAME))
    f.write(conclusion)
