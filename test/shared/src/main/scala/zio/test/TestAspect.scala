/*
 * Copyright 2019-2020 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.test

import zio._
import zio.duration._
import zio.test.Assertion.{ equalTo, hasMessage, isCase, isSubtype }
import zio.test.environment.{ Live, Restorable, TestClock, TestConsole, TestRandom, TestSystem }

/**
 * A `TestAspect` is an aspect that can be weaved into specs. You can think of
 * an aspect as a polymorphic function, capable of transforming one test into
 * another, possibly enlarging the environment or error type.
 */
trait TestAspect[+LowerR, -UpperR, +LowerE, -UpperE] { self =>

  /**
   * Applies the aspect to some tests in the spec, chosen by the provided
   * predicate.
   */
  def some[R >: LowerR <: UpperR, E >: LowerE <: UpperE](predicate: String => Boolean, spec: ZSpec[R, E]): ZSpec[R, E]

  /**
   * An alias for [[all]].
   */
  final def apply[R >: LowerR <: UpperR, E >: LowerE <: UpperE](spec: ZSpec[R, E]): ZSpec[R, E] =
    all(spec)

  /**
   * Applies the aspect to every test in the spec.
   */
  final def all[R >: LowerR <: UpperR, E >: LowerE <: UpperE](spec: ZSpec[R, E]): ZSpec[R, E] =
    some[R, E](_ => true, spec)

  /**
   * Returns a new aspect that represents the sequential composition of this
   * aspect with the specified one.
   */
  final def >>>[LowerR1 >: LowerR, UpperR1 <: UpperR, LowerE1 >: LowerE, UpperE1 <: UpperE](
    that: TestAspect[LowerR1, UpperR1, LowerE1, UpperE1]
  ): TestAspect[LowerR1, UpperR1, LowerE1, UpperE1] =
    new TestAspect[LowerR1, UpperR1, LowerE1, UpperE1] {
      def some[R >: LowerR1 <: UpperR1, E >: LowerE1 <: UpperE1](
        predicate: String => Boolean,
        spec: ZSpec[R, E]
      ): ZSpec[R, E] =
        that.some(predicate, self.some(predicate, spec))
    }

  final def andThen[LowerR1 >: LowerR, UpperR1 <: UpperR, LowerE1 >: LowerE, UpperE1 <: UpperE](
    that: TestAspect[LowerR1, UpperR1, LowerE1, UpperE1]
  ): TestAspect[LowerR1, UpperR1, LowerE1, UpperE1] =
    self >>> that
}
object TestAspect extends TimeoutVariants {

  /**
   * An aspect that returns the tests unchanged
   */
  val identity: TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](predicate: String => Boolean, spec: ZSpec[R, E]): ZSpec[R, E] =
        spec
    }

  /**
   * An aspect that marks tests as ignored.
   */
  val ignore: TestAspectAtLeastR[Annotations] =
    new TestAspectAtLeastR[Annotations] {
      def some[R <: Annotations, E](predicate: String => Boolean, spec: ZSpec[R, E]): ZSpec[R, E] =
        spec.when(false)
    }

  /**
   * Constructs an aspect that runs the specified effect after every test.
   */
  def after[R0, E0](effect: ZIO[R0, E0, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        test.run
          .zipWith(effect.catchAllCause(cause => ZIO.fail(TestFailure.Runtime(cause))).run)(_ <* _)
          .flatMap(ZIO.done(_))
    }

  /**
   * Annotates tests with the specified test annotation.
   */
  def annotate[V](key: TestAnnotation[V], value: V): TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](predicate: String => Boolean, spec: ZSpec[R, E]): ZSpec[R, E] =
        spec.annotate(key, value)
    }

  /**
   * Constructs an aspect that evaluates every test between two effects,
   * `before` and `after`,  where the result of `before` can be used in
   * `after`.
   */
  def around[R0, E0, A0](
    before: ZIO[R0, E0, A0]
  )(after: A0 => ZIO[R0, Nothing, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        before.catchAllCause(c => ZIO.fail(TestFailure.Runtime(c))).bracket(after)(_ => test)
    }

  /**
   * A less powerful variant of `around` where the result of `before` is not
   * required by after.
   */
  def around_[R0, E0](before: ZIO[R0, E0, Any], after: ZIO[R0, Nothing, Any]): TestAspect[Nothing, R0, E0, Any] =
    around(before)(_ => after)

  /**
   * Constructs an aspect that evaluates every test inside the context of the
   * managed function.
   */
  def aroundTest[R0, E0](
    managed: ZManaged[R0, TestFailure[E0], TestSuccess => ZIO[R0, TestFailure[E0], TestSuccess]]
  ): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        managed.use(f => test.flatMap(f))
    }

  /**
   * Constructs a simple monomorphic aspect that only works with the specified
   * environment and error type.
   */
  def aspect[R0, E0](
    f: ZIO[R0, TestFailure[E0], TestSuccess] => ZIO[R0, TestFailure[E0], TestSuccess]
  ): TestAspect[R0, R0, E0, E0] =
    new TestAspect.PerTest[R0, R0, E0, E0] {
      def perTest[R >: R0 <: R0, E >: E0 <: E0](
        test: ZIO[R, TestFailure[E], TestSuccess]
      ): ZIO[R, TestFailure[E], TestSuccess] =
        f(test)
    }

  /**
   * Constructs an aspect that runs the specified effect before every test.
   */
  def before[R0, E0](effect: ZIO[R0, Nothing, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        effect *> test
    }

  /**
   * An aspect that runs each test on a separate fiber and prints a fiber dump
   * if the test fails or has not terminated within the specified duration.
   */
  def diagnose(duration: Duration): TestAspectAtLeastR[Live] =
    new TestAspectAtLeastR[Live] {
      def some[R <: Live, E](predicate: String => Boolean, spec: ZSpec[R, E]): ZSpec[R, E] = {
        def diagnose[R <: Live, E](
          label: String,
          test: ZIO[R, TestFailure[E], TestSuccess]
        ): ZIO[R, TestFailure[E], TestSuccess] =
          test.fork.flatMap { fiber =>
            fiber.join.raceWith(Live.live(ZIO.sleep(duration)))(
              (exit, sleepFiber) => dump(label, fiber).when(!exit.succeeded) *> sleepFiber.interrupt *> ZIO.done(exit),
              (_, _) => dump(label, fiber) *> fiber.join
            )
          }
        def dump[E, A](label: String, fiber: Fiber.Runtime[E, A]): ZIO[Live, Nothing, Unit] =
          Live.live(Fiber.putDumpStr(label, fiber))
        spec.transform[R, TestFailure[E], TestSuccess] {
          case c @ Spec.SuiteCase(_, _, _) => c
          case Spec.TestCase(label, test, annotations) =>
            Spec.TestCase(label, if (predicate(label)) diagnose(label, test) else test, annotations)
        }
      }
    }

  /**
   * An aspect that runs each test with the `TestConsole` instance in the
   * environment set to debug mode so that console output is rendered to
   * standard output in addition to being written to the output buffer.
   */
  val debug: TestAspectAtLeastR[TestConsole] =
    new PerTest.AtLeastR[TestConsole] {
      def perTest[R <: TestConsole, E](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        TestConsole.debug(test)
    }

  /**
   * An aspect that applies the specified aspect on Dotty.
   */
  def dotty[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestVersion.isDotty) that else identity

  /**
   * An aspect that only runs tests on Dotty.
   */
  val dottyOnly: TestAspectAtLeastR[Annotations] =
    if (TestVersion.isDotty) identity else ignore

  /**
   * An aspect that retries a test until success, without limit.
   */
  val eventually: TestAspectAtLeastR[ZTestEnv] = {
    val eventually = new PerTest.Poly {
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        test.eventually
    }
    restoreTestEnvironment >>> eventually
  }

  /**
   * An aspect that runs tests on all versions except Dotty.
   */
  val exceptDotty: TestAspectAtLeastR[Annotations] =
    if (TestVersion.isDotty) ignore else identity

  /**
   * An aspect that runs tests on all platforms except ScalaJS.
   */
  val exceptJS: TestAspectAtLeastR[Annotations] =
    if (TestPlatform.isJS) ignore else identity

  /**
   * An aspect that runs tests on all platforms except the JVM.
   */
  val exceptJVM: TestAspectAtLeastR[Annotations] =
    if (TestPlatform.isJVM) ignore else identity

  /**
   * An aspect that runs tests on all platforms except ScalaNative.
   */
  val exceptNative: TestAspectAtLeastR[Annotations] =
    if (TestPlatform.isNative) ignore else identity

  /**
   * An aspect that runs tests on all versions except Scala 2.
   */
  val exceptScala2: TestAspectAtLeastR[Annotations] =
    if (TestVersion.isScala2) ignore else identity

  /**
   * An aspect that runs tests on all versions except Scala 2.11.
   */
  val exceptScala211: TestAspectAtLeastR[Annotations] =
    if (TestVersion.isScala211) ignore else identity

  /**
   * An aspect that runs tests on all versions except Scala 2.12.
   */
  val exceptScala212: TestAspectAtLeastR[Annotations] =
    if (TestVersion.isScala212) ignore else identity

  /**
   * An aspect that runs tests on all versions except Scala 2.13.
   */
  val exceptScala213: TestAspectAtLeastR[Annotations] =
    if (TestVersion.isScala213) ignore else identity

  /**
   * An aspect that sets suites to the specified execution strategy, but only
   * if their current strategy is inherited (undefined).
   */
  def executionStrategy(exec: ExecutionStrategy): TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](predicate: String => Boolean, spec: ZSpec[R, E]): ZSpec[R, E] =
        spec.transform[R, TestFailure[E], TestSuccess] {
          case Spec.SuiteCase(label, specs, None) if (predicate(label)) => Spec.SuiteCase(label, specs, Some(exec))
          case c                                                        => c
        }
    }

  /**
   * An aspect that makes a test that failed for any reason pass. Note that if
   * the test passes this aspect will make it fail.
   */
  val failing: TestAspectPoly =
    failing(Assertion.anything)

  /**
   * An aspect that makes a test that failed for the specified failure pass.
   * Note that the test will fail for other failures and also if it passes
   * correctly.
   */
  def failing[E0](assertion: Assertion[TestFailure[E0]]): TestAspect[Nothing, Any, Nothing, E0] =
    new TestAspect.PerTest[Nothing, Any, Nothing, E0] {
      def perTest[R, E <: E0](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        test.foldM(
          failure =>
            if (assertion.test(failure)) ZIO.succeedNow(TestSuccess.Succeeded(BoolAlgebra.unit))
            else ZIO.fail(TestFailure.assertion(assert(failure)(assertion))),
          _ => ZIO.fail(TestFailure.die(new RuntimeException("did not fail as expected")))
        )
    }

  /**
   * An aspect that retries a test until success, with a default limit, for use
   * with flaky tests.
   */
  val flaky: TestAspectAtLeastR[ZTestEnv with Annotations] =
    flaky(100)

  /**
   * An aspect that retries a test until success, with the specified limit, for
   * use with flaky tests.
   */
  def flaky(n: Int): TestAspectAtLeastR[ZTestEnv with Annotations] =
    retry(Schedule.recurs(n))

  /**
   * An aspect that runs each test on its own separate fiber.
   */
  val forked: TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        test.fork.flatMap(_.join)
    }

  /**
   * An aspect that only runs a test if the specified environment variable
   * satisfies the specified assertion.
   */
  def ifEnv(env: String, assertion: Assertion[String]): TestAspectAtLeastR[Live with Annotations] =
    new TestAspectAtLeastR[Live with Annotations] {
      def some[R <: Live with Annotations, E](predicate: String => Boolean, spec: ZSpec[R, E]): ZSpec[R, E] =
        spec.whenM(Live.live(system.env(env)).orDie.map(_.fold(false)(assertion.test)))
    }

  /**
   * As aspect that only runs a test if the specified environment variable is
   * set.
   */
  def ifEnvSet(env: String): TestAspectAtLeastR[Live with Annotations] =
    ifEnv(env, Assertion.anything)

  /**
   * An aspect that only runs a test if the specified Java property satisfies
   * the specified assertion.
   */
  def ifProp(prop: String, assertion: Assertion[String]): TestAspectAtLeastR[Live with Annotations] =
    new TestAspectAtLeastR[Live with Annotations] {
      def some[R <: Live with Annotations, E](predicate: String => Boolean, spec: ZSpec[R, E]): ZSpec[R, E] =
        spec.whenM(Live.live(system.property(prop)).orDie.map(_.fold(false)(assertion.test)))
    }

  /**
   * As aspect that only runs a test if the specified Java property is set.
   */
  def ifPropSet(prop: String): TestAspectAtLeastR[Live with Annotations] =
    ifProp(prop, Assertion.anything)

  /**
   * An aspect that applies the specified aspect on ScalaJS.
   */
  def js[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestPlatform.isJS) that else identity

  /**
   * An aspect that only runs tests on ScalaJS.
   */
  val jsOnly: TestAspectAtLeastR[Annotations] =
    if (TestPlatform.isJS) identity else ignore

  /**
   * An aspect that applies the specified aspect on the JVM.
   */
  def jvm[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestPlatform.isJVM) that else identity

  /**
   * An aspect that only runs tests on the JVM.
   */
  val jvmOnly: TestAspectAtLeastR[Annotations] =
    if (TestPlatform.isJVM) identity else ignore

  /**
   * An aspect that applies the specified aspect on ScalaNative.
   */
  def native[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestPlatform.isNative) that else identity

  /**
   * An aspect that only runs tests on ScalaNative.
   */
  val nativeOnly: TestAspectAtLeastR[Annotations] =
    if (TestPlatform.isNative) identity else ignore

  /**
   * An aspect that repeats the test a default number of times, ensuring it is
   * stable ("non-flaky"). Stops at the first failure.
   */
  val nonFlaky: TestAspectAtLeastR[ZTestEnv with Annotations] =
    nonFlaky(100)

  /**
   * An aspect that repeats the test a specified number of times, ensuring it
   * is stable ("non-flaky"). Stops at the first failure.
   */
  def nonFlaky(n: Int): TestAspectAtLeastR[ZTestEnv with Annotations] =
    repeat(Schedule.recurs(n))

  /**
   * Constructs an aspect that requires a test to not terminate within the
   * specified time.
   */
  def nonTermination(duration: Duration): TestAspectAtLeastR[Live] =
    timeout(duration) >>>
      failing(
        isCase[TestFailure[Any], Throwable](
          "Runtime", {
            case TestFailure.Runtime(cause) => cause.dieOption
            case _                          => None
          },
          isSubtype[TestTimeoutException](hasMessage(equalTo(s"Timeout of ${duration.render} exceeded.")))
        )
      )

  /**
   * Sets the seed of the `TestRandom` instance in the environment to a random
   * value before each test.
   */
  val nondeterministic: TestAspectAtLeastR[Live with TestRandom] =
    before(Live.live(clock.nanoTime).flatMap(TestRandom.setSeed(_)))

  /**
   * An aspect that executes the members of a suite in parallel.
   */
  val parallel: TestAspectPoly =
    executionStrategy(ExecutionStrategy.Parallel)

  /**
   * An aspect that executes the members of a suite in parallel, up to the
   * specified number of concurrent fibers.
   */
  def parallelN(n: Int): TestAspectPoly =
    executionStrategy(ExecutionStrategy.ParallelN(n))

  /**
   * An aspect that repeats successful tests according to a schedule.
   */
  def repeat[R0 <: ZTestEnv with Annotations](schedule: Schedule[R0, TestSuccess, Any]): TestAspectAtLeastR[R0] = {
    val repeat = new PerTest.AtLeastR[R0] {
      def perTest[R <: R0, E](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        test.repeat(
          (schedule *> Schedule.identity[TestSuccess]).tapOutput(_ => Annotations.annotate(TestAnnotation.repeated, 1))
        )
    }
    restoreTestEnvironment >>> repeat
  }

  /**
   * An aspect that restores a given
   * [[zio.test.environment.Restorable Restorable]]'s state to its starting
   * state after the test is run. Note that this is only useful when repeating
   * tests.
   */
  def restore[R0](service: R0 => Restorable): TestAspectAtLeastR[R0] =
    around(ZIO.accessM[R0](r => service(r).save))(restore => restore)

  /**
   * An aspect that restores the
   * [[zio.test.environment.TestClock TestClock]]'s state to its starting
   * state after the test is run. Note that this is only useful when repeating
   * tests.
   */
  def restoreTestClock: TestAspectAtLeastR[TestClock] =
    restore[TestClock](_.get)

  /**
   * An aspect that restores the
   * [[zio.test.environment.TestConsole TestConsole]]'s state to its starting
   * state after the test is run. Note that this is only useful when repeating
   * tests.
   */
  def restoreTestConsole: TestAspectAtLeastR[TestConsole] =
    restore[TestConsole](_.get)

  /**
   * An aspect that restores the
   * [[zio.test.environment.TestRandom TestRandom]]'s state to its starting
   * state after the test is run. Note that this is only useful when repeating
   * tests.
   */
  def restoreTestRandom: TestAspectAtLeastR[TestRandom] =
    restore[TestRandom](_.get)

  /**
   * An aspect that restores the
   * [[zio.test.environment.TestSystem TestSystem]]'s state to its starting
   * state after the test is run. Note that this is only useful when repeating
   * tests.
   */
  def restoreTestSystem: TestAspectAtLeastR[ZTestEnv] =
    restore[TestSystem](_.get)

  /**
   * An aspect that restores all state in the standard provided test
   * environments ([[zio.test.environment.TestClock TestClock]],
   * [[zio.test.environment.TestConsole TestConsole]],
   * [[zio.test.environment.TestRandom TestRandom]] and
   * [[zio.test.environment.TestSystem TestSystem]]) to their starting state
   * after the test is run. Note that this is only useful when repeating tests.
   */
  def restoreTestEnvironment: TestAspectAtLeastR[ZTestEnv] =
    restoreTestClock >>> restoreTestConsole >>> restoreTestRandom >>> restoreTestSystem

  /**
   * An aspect that retries failed tests according to a schedule.
   */
  def retry[R0 <: ZTestEnv with Annotations, E0](
    schedule: Schedule[R0, TestFailure[E0], Any]
  ): TestAspect[Nothing, R0, Nothing, E0] = {
    val retry = new TestAspect.PerTest[Nothing, R0, Nothing, E0] {
      def perTest[R <: R0, E <: E0](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        test.retry(schedule.tapOutput(_ => Annotations.annotate(TestAnnotation.retried, 1)))
    }
    restoreTestEnvironment >>> retry
  }

  /**
   * An aspect that executes the members of a suite sequentially.
   */
  val sequential: TestAspectPoly =
    executionStrategy(ExecutionStrategy.Sequential)

  /**
   * An aspect that applies the specified aspect on Scala 2.
   */
  def scala2[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestVersion.isScala2) that else identity

  /**
   * An aspect that applies the specified aspect on Scala 2.11.
   */
  def scala211[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestVersion.isScala211) that else identity

  /**
   * An aspect that applies the specified aspect on Scala 2.12.
   */
  def scala212[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestVersion.isScala212) that else identity

  /**
   * An aspect that applies the specified aspect on Scala 2.13.
   */
  def scala213[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestVersion.isScala213) that else identity

  /**
   * An aspect that only runs tests on Scala 2.
   */
  val scala2Only: TestAspectAtLeastR[Annotations] =
    if (TestVersion.isScala2) identity else ignore

  /**
   * An aspect that only runs tests on Scala 2.11.
   */
  val scala211Only: TestAspectAtLeastR[Annotations] =
    if (TestVersion.isScala211) identity else ignore

  /**
   * An aspect that only runs tests on Scala 2.12.
   */
  val scala212Only: TestAspectAtLeastR[Annotations] =
    if (TestVersion.isScala212) identity else ignore

  /**
   * An aspect that only runs tests on Scala 2.13.
   */
  val scala213Only: TestAspectAtLeastR[Annotations] =
    if (TestVersion.isScala213) identity else ignore

  /**
   * Sets the seed of the `TestRandom` instance in the environment to the
   * specified value before each test.
   */
  def setSeed(seed: => Long): TestAspectAtLeastR[TestRandom] =
    before(TestRandom.setSeed(seed))

  /**
   * An aspect that runs each test with the `TestConsole` instance in the
   * environment set to silent mode so that console output is only written to
   * the output buffer and not rendered to standard output.
   */
  val silent: TestAspectAtLeastR[TestConsole] =
    new PerTest.AtLeastR[TestConsole] {
      def perTest[R <: TestConsole, E](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        TestConsole.silent(test)
    }

  /**
   * An aspect that converts ignored tests into test failures.
   */
  val success: TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        test.flatMap {
          case TestSuccess.Ignored =>
            ZIO.fail(TestFailure.Runtime(Cause.die(new RuntimeException("Test was ignored."))))
          case x => ZIO.succeedNow(x)
        }
    }

  /**
   * Annotates tests with string tags.
   */
  def tag(tag: String, tags: String*): TestAspectPoly =
    annotate(TestAnnotation.tagged, Set(tag) union tags.toSet)

  /**
   * Annotates tests with their execution times.
   */
  val timed: TestAspectAtLeastR[Live with Annotations] =
    new TestAspect.PerTest.AtLeastR[Live with Annotations] {
      def perTest[R <: Live with Annotations, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      ): ZIO[R, TestFailure[E], TestSuccess] =
        Live.withLive(test)(_.either.timed).flatMap {
          case (duration, result) =>
            ZIO.fromEither(result) <* Annotations.annotate(TestAnnotation.timing, duration)
        }
    }

  /**
   * An aspect that times out tests using the specified duration.
   * @param duration maximum test duration
   * @param interruptDuration after test timeout will wait given duration for successful interruption
   */
  def timeout(
    duration: Duration
  ): TestAspectAtLeastR[Live] =
    new PerTest.AtLeastR[Live] {
      def perTest[R <: Live, E](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] = {
        def timeoutFailure =
          TestTimeoutException(s"Timeout of ${duration.render} exceeded.")
        Live
          .withLive(test)(_.either.disconnect.timeout(duration).flatMap {
            case None         => ZIO.fail(TestFailure.Runtime(Cause.die(timeoutFailure)))
            case Some(result) => ZIO.fromEither(result)
          })
      }
    }

  /**
   * Verifies the specified post-condition after each test is run.
   */
  def verify[R0, E0](condition: => ZIO[R0, E0, TestResult]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](test: ZIO[R, TestFailure[E], TestSuccess]): ZIO[R, TestFailure[E], TestSuccess] =
        test <* ZTest(condition)
    }

  trait PerTest[+LowerR, -UpperR, +LowerE, -UpperE] extends TestAspect[LowerR, UpperR, LowerE, UpperE] {

    def perTest[R >: LowerR <: UpperR, E >: LowerE <: UpperE](
      test: ZIO[R, TestFailure[E], TestSuccess]
    ): ZIO[R, TestFailure[E], TestSuccess]

    final def some[R >: LowerR <: UpperR, E >: LowerE <: UpperE](
      predicate: String => Boolean,
      spec: ZSpec[R, E]
    ): ZSpec[R, E] =
      spec.transform[R, TestFailure[E], TestSuccess] {
        case c @ Spec.SuiteCase(_, _, _) => c
        case Spec.TestCase(label, test, annotations) =>
          Spec.TestCase(label, if (predicate(label)) perTest(test) else test, annotations)
      }
  }
  object PerTest {

    /**
     * A `PerTest.AtLeast[R]` is a `TestAspect.PerTest` that that requires at
     * least an `R` in its environment
     */
    type AtLeastR[R] = TestAspect.PerTest[Nothing, R, Nothing, Any]

    /**
     * A `PerTest.Poly` is a `TestAspect.PerTest` that is completely
     * polymorphic, having no requirements ZRTestEnv on error or environment.
     */
    type Poly = TestAspect.PerTest[Nothing, Any, Nothing, Any]
  }
}
