package com.baracuda25.server

import akka.util.Timeout
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should._
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.Try

class LoadBalancerSpec
    extends AnyWordSpec
    with ScalaFutures
    with IdiomaticMockito
    with Matchers
    with BeforeAndAfterEach {

  implicit val timeout: Timeout = 1.seconds

  "LoadBalancer" should {

    "Register successfully provider if there is not such provider already registered and max limit is not reached" in {
      val loadBalancer = LoadBalancer()
      val provider1 = Provider("provider1")
      whenReady(loadBalancer.registerProvider(provider1)) { _ =>
        whenReady(loadBalancer.get()) { result =>
          result shouldBe "provider1"
        }
      }
      loadBalancer.shutdown()
    }

    "Register successfully list of providers if there are not such provider already registered and max limit is not reached" in {
      val loadBalancer = LoadBalancer()
      val providers =
        for (i <- 1 to 10)
          yield Provider(s"provider$i")

      whenReady(loadBalancer.registerProviders(providers)) { _ =>
        val getResults =
          for (i <- 1 to 10)
            yield loadBalancer.get()

        whenReady(Future.sequence(getResults)) { result =>
          result shouldBe (for (i <- 1 to 10) yield s"provider$i")
        }
      }
      loadBalancer.shutdown()
    }

    "Fail to register provider if there is such provider already registered" in {
      val loadBalancer = LoadBalancer()
      val provider1 = Provider("provider1")
      whenReady(loadBalancer.registerProvider(provider1)) { _ =>
        whenReady(loadBalancer.registerProvider(provider1)) { e =>
          e.failed.toOption.map(_.getMessage) shouldBe Some(
            "Provider with id provider1 already exists!"
          )
        }
      }
      loadBalancer.shutdown()
    }

    "Fail to register provider if max limit is reached" in {
      val loadBalancer = LoadBalancer()
      val registeredProviders =
        for (i <- 1 to 10)
          yield loadBalancer.registerProvider(Provider(s"provider$i"))

      whenReady(Future.successful(registeredProviders)) { _ =>
        whenReady(loadBalancer.registerProvider(Provider("provider11"))) { e =>
          e.failed.toOption.map(_.getMessage) shouldBe Some(
            "Reached maximum amount of providers!"
          )
        }
      }
      loadBalancer.shutdown()
    }

    "Unregister successfully provider if there is such provider already registered" in {
      val loadBalancer = LoadBalancer()
      val provider1 = Provider("provider1")
      whenReady(loadBalancer.registerProvider(provider1)) { _ =>
        whenReady(loadBalancer.unregisterProvider(provider1)) { _ =>
          val failed = loadBalancer.get()
          whenReady(failed.failed) { e =>
            e shouldBe a[IllegalStateException]
          }
        }
      }
      loadBalancer.shutdown()
    }

    "Return different result based on the specified strategy" in {
      val roundRobinLb = LoadBalancer(new RoundRobinStrategy)
      val randomLb = LoadBalancer(new RandomStrategy)
      val providers =
        for (i <- 1 to 10)
          yield Provider(s"provider$i")

      whenReady(roundRobinLb.registerProviders(providers)) { _ =>
        whenReady(randomLb.registerProviders(providers)) { _ =>
          val rbGetResults =
            for (i <- 1 to 10)
              yield roundRobinLb.get()

          val randomGetResults =
            for (i <- 1 to 10)
              yield randomLb.get()

          whenReady(Future.sequence(rbGetResults)) { rbResult =>
            whenReady(Future.sequence(randomGetResults)) { randomResult =>
              randomResult should not equal rbResult
            }
          }
        }
      }
      randomLb.shutdown()
      roundRobinLb.shutdown()
    }

    "Exclude provider if it become unhealthy" in {
      val loadBalancer = LoadBalancer(healthCheckInterval = 1.second)
      val providers =
        for (i <- 1 to 10)
          yield {
            val provider = mock[Provider]
            provider.id returns s"provider$i"
            provider.get() returns Future.successful(s"provider$i")
            provider.check() returns Future.successful(Alive)
            provider
          }

      whenReady(loadBalancer.registerProviders(providers)) { _ =>
        def getResults =
          for (i <- 1 to 10)
            yield loadBalancer.get()

        whenReady(Future.sequence(getResults)) { result =>
          val unhealthyProvider = providers(0)
          reset(unhealthyProvider)
          unhealthyProvider.check() returns Future.successful(Dead)
          Thread.sleep(1100)

          whenReady(Future.sequence(getResults)) { withoutProvider1result =>
            withoutProvider1result shouldBe result.takeRight(8) ++ result.tail
              .take(2)
          }
        }
      }
      loadBalancer.shutdown()
    }

    "Include provider if it become healthy 2 consecutive times" in {
      val loadBalancer = LoadBalancer(healthCheckInterval = 1.second)
      val providers =
        for (i <- 1 to 10)
          yield {
            val provider = mock[Provider]
            provider.id returns s"provider$i"
            provider.get() returns Future.successful(s"provider$i")
            provider.check() returns Future.successful(Alive)
            provider
          }

      whenReady(loadBalancer.registerProviders(providers)) { _ =>
        def getResults =
          for (i <- 1 to 10)
            yield loadBalancer.get()

        whenReady(Future.sequence(getResults)) { result =>
          val unhealthyProvider = providers(0)
          reset(unhealthyProvider)
          unhealthyProvider.check() returns Future.successful(Dead)
          Thread.sleep(1100)

          whenReady(Future.sequence(getResults)) { withoutProvider1result =>
            withoutProvider1result shouldBe result.takeRight(8) ++ result.tail
              .take(2)
          }

          reset(unhealthyProvider)
          unhealthyProvider.check() returns Future.successful(Alive)
          unhealthyProvider.get() returns Future.successful("provider1")

          Thread.sleep(1000)

          whenReady(Future.sequence(getResults)) { withoutProvider1result =>
            withoutProvider1result shouldBe result.takeRight(7) ++ result.tail
              .take(3)
          }

          Thread.sleep(1100)

          whenReady(Future.sequence(getResults)) { witProvider1result =>
            witProvider1result shouldBe result
          }
        }
      }
      loadBalancer.shutdown()
    }

    "Reject call if maximum capacity is reached" in {

      implicit val patienceConfig: PatienceConfig =
        PatienceConfig(timeout = 1.seconds)

      case class DelayedProvider(override val id: String) extends Provider {
        override def get(): Future[String] = Future {
          delay(100.millisecond.fromNow)
          id
        }
        override def check(): Future[Status] = Future.successful(Alive)
      }

      def delay(dur: Deadline) = {
        Try(Await.ready(Promise().future, dur.timeLeft))
      }
      val loadBalancer = LoadBalancer(providerCapacity = 1)
      val providers =
        for (i <- 1 to 10)
          yield DelayedProvider(s"provider$i")

      whenReady(loadBalancer.registerProviders(providers)) { _ =>
        def getResults =
          for (i <- 1 to 10)
            yield loadBalancer.get()

        val runResults = Future.sequence(getResults)
        val failedResult = loadBalancer.get()
        whenReady(failedResult.failed) { e =>
          e shouldBe a[IllegalStateException]
        }

        whenReady(runResults) { _ =>
          loadBalancer.shutdown()
        }
      }
    }
  }
}
