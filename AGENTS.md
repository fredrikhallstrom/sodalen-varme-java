# Agents working on sodalen-varme

Fredrik's rewrite of Sodalen's heating core in Java 25, one step at a time,
to run beside the Python version in shadow mode in the end. The house itself,
its hosts and its rules are in `AGENTS.md` of sodalen-router-system, where the
Python version lives (`heating/`); read it when you need the original.

## Who writes what

- **Fredrik writes all the production code**, everything under `src/main`.
  He is learning Java by doing it. An agent never writes, edits or generates
  code there, and does not hand him a finished solution unless he asks for
  one: explain what is wrong, point at the line, show the idea in a few lines
  if that helps, and let him write it.
- **Agents write the tests and what is around them:** `steg/NN-name/`
  (`java/` with the tests, `resources/` with the answer files, `STEG.md`,
  and the Python file the step translates), `build.gradle`, `gradle/`,
  `verktyg/` and the docs.
- **One package per part** under `se.sodalen.heating` (`helper`, `pid`,
  `curve`; Fredrik's choice). A step's tests live in the package of the
  class they test, and its `STEG.md` names that package. Ask him which
  package a new part goes in before writing its tests.
- **A test is not changed to make code pass.** If Fredrik thinks a test is
  wrong, look at it with him; it may be.

## Writing a step's tests

1. Write them when he is close to the step, against the Python version as it
   is then. The roadmap and each step's state are in `README.md`.
2. Translate the Python tests where there are some; where there are none,
   write them from the code. Swedish in what he reads (`STEG.md`, display
   names), as the rest of the repository.
3. The answer files come from the Python code with a fixed seed:
   `python3 verktyg/facit.py <path to heating/ in sodalen-router-system>`.
4. **Check the tests against a reference solution of your own, kept outside
   this repository** and never committed, pushed or shown unless he asks.
   Then break the reference the way a translation goes wrong (integer
   division, `(int)` for `Math.floor`, `round` for banker's rounding, the
   default locale, a missing null) and see a test fail each time.
5. A step is unlocked by `steg=N` in `gradle.properties`, which is his to
   raise.

When the Python version of a step he has done changes, rerun `facit.py`,
commit the new answer files, and tell him which tests may turn red and why.

## Running

    JAVA_HOME=/usr/lib/jvm/java-25-temurin-jdk ./gradlew test

Gradle 9.8 with the Groovy DSL and a version catalog, JUnit 6. Commits are
local (the repository has no remote yet), in Swedish like its history.
