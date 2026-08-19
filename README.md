# sbt 2 stale compile cache repro

Changing an sbt plugin version that contributes `scalacOptions` does not invalidate
sbt 2's disk (action) cache. This yields three broken behaviors, reproduced below with
sbt 2.0.6:

1. after a plugin change, `compile` replays a stale cached result instead of recompiling;
2. after a *real* recompile is forced, it runs with the stale `scalacOptions` of the
   previous plugin version, fails, and the failure is recorded under the current cache key;
3. from then on every `compile` replays `sbt.util.CachedCompileFailure` (~1s, no compiler
   run) even though the configuration compiles fine. `clean` does not purge it; only
   `cleanFull` does.

The plugin used as the trigger is [sbt-scalac-opts-plugin](https://github.com/evolution-gaming/sbt-scalac-opts-plugin):
v0.1.0 adds `-Wnonunit-statement` (and `-Xfatal-warnings`), v0.2.0 does not add
`-Wnonunit-statement`. `Repro.scala` contains a non-unit statement, so it must fail to
compile under 0.1.0 and succeed under 0.2.0.

## Environment

- sbt 2.0.6 (bug originally hit on macOS, reproduced on Linux x86_64)
- JDK: Temurin 25.0.4
- Scala 2.13.18

## Steps

Starting state: `project/plugins.sbt` has plugin `0.2.0`.

```sh
sbt --batch cleanFull                              # start from an empty cache
sbt --batch compile                                # OK (exit 0), as expected

sed -i 's/0\.2\.0/0.1.0/' project/plugins.sbt      # downgrade: adds -Wnonunit-statement
sbt --batch compile                                # BUG 1: exit 0 — stale cache replay,
                                                   # expected a compile failure

sbt --batch cleanFull
sbt --batch compile                                # exit 1 with the expected real error:
                                                   # "unused value of type List[Int]"
                                                   # failure now recorded in the cache

sed -i 's/0\.1\.0/0.2.0/' project/plugins.sbt      # revert: flag no longer configured
sbt --batch compile                                # BUG 2: exit 1 — compiler actually runs,
                                                   # but with stale scalacOptions still
                                                   # containing -Wnonunit-statement
sbt --batch compile                                # BUG 3: exit 1 in ~1s —
                                                   # sbt.util.CachedCompileFailure replay

sbt --batch clean
sbt --batch compile                                # still CachedCompileFailure (exit 1)

sbt --batch cleanFull
sbt --batch compile                                # OK (exit 0)
```

## Observed output for BUG 3

```
[error] sbt.util.CachedCompileFailure$$anon$1: Compilation failed
[error]         at sbt.util.CachedCompileFailure.toException(CachedCompileFailure.scala:23)
[error]         at sbt.util.ActionCache$.cache(ActionCache.scala:185)
...
[error] (compileIncremental) sbt.util.CachedCompileFailure$$anon$1: Compilation failed
[error] elapsed time: 1 s, cache 100%, 1 cached-failure cache hit, ...
```

## Evidence that the options themselves are served stale

With `0.1.0` on the classpath (verified via
`eval com.evolution.scalacopts.ScalacOptsPlugin.getClass.getProtectionDomain.getCodeSource.getLocation`),
the plugin's own logic returns the flag:

```
> eval com.evolution.scalacopts.ScalacOptsPlugin.autoImport.scalacOptsFor("2.13.18", com.evolution.scalacopts.ScalacOptsPlugin.scalacOptsAll).filter(_.startsWith("-Wnon"))
ans: List[String] = List(-Wnonunit-statement)
```

yet `show compile/scalacOptions` still prints the 0.2.0-era value without
`-Wnonunit-statement` — the cached value is replayed because the plugin classpath is not
part of the cache key.

## Expected

Changing anything that affects a task's inputs — including the metabuild/plugin
classpath — should invalidate the cached value, and a compilation failure should never be
served for inputs that compile successfully.
