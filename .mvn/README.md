# `.mvn/` — Maven launcher configuration

## `jvm.config`

Every line in this file is passed **verbatim** to the JVM that runs Maven itself.

### It does not support comments

There is no comment syntax. Maven's launcher script reads the whole file, splits it on
whitespace, and hands the tokens to `java`. A `#` therefore arrives as a bare argument, the
JVM reads the first non-option token as the main class, and **every** Maven invocation —
including `mvn -v` — dies before Maven starts:

```
Error: Could not find or load main class #
Caused by: java.lang.ClassNotFoundException: #
```

This is not a soft failure that only affects one plugin: it bricks the entire build on all
platforms at once. Two explanatory `#` lines in this file did exactly that to every job of
CI run 30306512701. Explanations belong in this README instead.

### Why the flags are here

`javac`'s internals (`jdk.compiler`) are encapsulated from JDK 16 on. google-java-format runs
**in-process** inside the Spotless plugin's JVM, so it needs `--add-exports`/`--add-opens` to
reach them; without the flags `spotless:check` fails with `IllegalAccessError` on
`com.sun.tools.javac.*`.

Because these flags target the Maven JVM, they cannot be expressed in `pom.xml` — which is
why this file exists at all rather than the configuration living with the plugin.

ROADMAP item 1.11 (ErrorProne + NullAway) needs the same export set — **verified, no longer
anticipated**: with this file moved aside, `mvn compile` on JDK 21 dies with

```
java.lang.IllegalAccessError: class com.google.errorprone.BaseErrorProneJavaCompiler
  cannot access class com.sun.tools.javac.api.BasicJavacTask (in module jdk.compiler)
  because module jdk.compiler does not export com.sun.tools.javac.api to unnamed module
```

ErrorProne runs in-process in the compiler's JVM, exactly like google-java-format, and the eight
exports plus two opens already here are sufficient for both — item 1.11 added no flag. That is why the
set is kept whole rather than trimmed to Spotless's exact minimum, and why deleting a line here breaks
two gates at once (ADR-0009).

### Editing checklist

- One flag per line, nothing else.
- No comments, no blank-line separators carrying meaning.
- After any change, run `mvn -v`. If Maven cannot even print its version, this file is broken.
