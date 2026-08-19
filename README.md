# sbt 2 stale compile cache repro

In sbt 2.0.6, changing an sbt plugin version that contributes `scalacOptions` does not
invalidate the disk (action) cache:

1. `compile` replays a stale cached result instead of recompiling;
2. a forced recompile runs with the previous plugin's `scalacOptions`, fails, and the
   failure is recorded under the current cache key;
3. every following `compile` replays `sbt.util.CachedCompileFailure` (~1s, no compiler
   run) although the configuration compiles fine. `clean` does not purge it, only
   `cleanFull` does.

Trigger: [sbt-scalac-opts-plugin](https://github.com/evolution-gaming/sbt-scalac-opts-plugin)
0.1.0 adds `-Wnonunit-statement` + `-Xfatal-warnings`, 0.2.0 does not. `Repro.scala` has a
non-unit statement, so it must fail under 0.1.0 and compile under 0.2.0.

## Steps (sbt 2.0.6, Scala 2.13.18, Temurin 25)

```sh
sbt --batch cleanFull
sbt --batch compile                                # exit 0, as expected

sed -i 's/0\.2\.0/0.1.0/' project/plugins.sbt
sbt --batch compile                                # BUG 1: exit 0 — stale replay,
                                                   # expected failure
sbt --batch cleanFull
sbt --batch compile                                # exit 1, real error recorded:
                                                   # "unused value of type List[Int]"

sed -i 's/0\.1\.0/0.2.0/' project/plugins.sbt
sbt --batch compile                                # BUG 2: exit 1 — recompiles with
                                                   # stale -Wnonunit-statement
sbt --batch compile                                # BUG 3: exit 1 in ~1s —
                                                   # CachedCompileFailure replay
sbt --batch clean
sbt --batch compile                                # still exit 1
sbt --batch cleanFull
sbt --batch compile                                # exit 0
```

BUG 3 output:

```
[error] sbt.util.CachedCompileFailure$$anon$1: Compilation failed
[error]         at sbt.util.CachedCompileFailure.toException(CachedCompileFailure.scala:23)
[error] elapsed time: 1 s, cache 100%, 1 cached-failure cache hit, ...
```

With 0.1.0 on the classpath, `eval` of the plugin's own `scalacOptsFor("2.13.18", ...)`
returns `-Wnonunit-statement`, yet `show compile/scalacOptions` prints the 0.2.0-era value
without it — the cached value is replayed because the plugin classpath is not part of the
cache key.
