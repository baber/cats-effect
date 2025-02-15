/*
 * Copyright 2020-2022 Typelevel
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

package cats
package effect
package kernel

import scala.concurrent.duration._

class DeferredSpec extends BaseSpec { outer =>

  "Deferred for Async" should {
    tests(IO(Deferred.unsafe), IO(Deferred.unsafe))
  }

  "Deferred for IO" should {
    tests(IO(new IODeferred), IO(new IODeferred))
  }

  def tests(deferredU: IO[Deferred[IO, Unit]], deferredI: IO[Deferred[IO, Int]]) = {
    "complete" in real {
      val op = deferredI.flatMap { p => p.complete(0) *> p.get }

      op.flatMap { res =>
        IO {
          res must beEqualTo(0)
        }
      }
    }

    "complete is only successful once" in real {
      val op = deferredI.flatMap { p => p.complete(0) *> p.complete(1).product(p.get) }

      op.flatMap { res =>
        IO {
          res must beEqualTo((false, 0))
        }
      }
    }

    "get blocks until set" in real {
      val op = for {
        state <- Ref[IO].of(0)
        modifyGate <- deferredU
        readGate <- deferredU
        _ <- (modifyGate.get *> state.update(_ * 2) *> readGate.complete(())).start
        _ <- (state.set(1) *> modifyGate.complete(())).start
        _ <- readGate.get
        res <- state.get
      } yield res

      op.flatMap { res =>
        IO {
          res must beEqualTo(2)
        }
      }
    }

    "concurrent - get - cancel before forcing" in real {
      def cancelBeforeForcing: IO[Option[Int]] =
        for {
          r <- Ref[IO].of(Option.empty[Int])
          p <- Deferred[IO, Int]
          fiber <- p.get.start
          _ <- fiber.cancel
          _ <- fiber
            .join
            .flatMap {
              case Outcome.Succeeded(ioi) => ioi.flatMap(i => r.set(Some(i)))
              case _ => IO.raiseError(new RuntimeException)
            }
            .voidError
            .start
          _ <- IO.sleep(100.millis)
          _ <- p.complete(42)
          _ <- IO.sleep(100.millis)
          result <- r.get
        } yield result

      cancelBeforeForcing.flatMap { res =>
        IO {
          res must beNone
        }
      }
    }

    "tryGet returns None for unset Deferred" in real {
      val op = deferredU.flatMap(_.tryGet)

      op.flatMap { res =>
        IO {
          res must beNone
        }
      }
    }

    "tryGet returns Some() for set Deferred" in real {
      val op = for {
        d <- deferredU
        _ <- d.complete(())
        result <- d.tryGet
      } yield result

      op.flatMap { res =>
        IO {
          res must beEqualTo(Some(()))
        }
      }
    }

    "issue #380: complete doesn't block, test #1" in real {
      def execute(times: Int): IO[Boolean] = {
        def foreverAsync(i: Int): IO[Unit] =
          if (i == 512) IO.async[Unit] { cb =>
            cb(Right(()))
            IO.pure(None)
          } >> foreverAsync(0)
          else IO.unit >> foreverAsync(i + 1)

        val task = for {
          d <- deferredU
          latch <- deferredU
          fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
          _ <- latch.get
          _ <- d.complete(()).timeout(15.seconds).guarantee(fb.cancel)
        } yield {
          true
        }

        task.flatMap { r =>
          if (times > 0) execute(times - 1)
          else IO.pure(r)
        }
      }

      execute(100).flatMap { res =>
        IO {
          res must beTrue
        }
      }
    }

    "issue #3283: complete must be uncancelable" in real {

      import cats.syntax.all._

      for {
        d <- Deferred[IO, Int]
        attemptCompletion = { (n: Int) => d.complete(n).void }
        res <- List(
          IO.race(attemptCompletion(1), attemptCompletion(2)).void,
          d.get.void
        ).parSequence
        r <- IO { (res == List((), ())) must beTrue }
      } yield r
    }

  }
}
