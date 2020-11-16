package com.baracuda25.server

import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.baracuda25.server.actors._

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class RegistryResult(providerId: String)

trait LoadBalancer {
  def get()(implicit timeout: Timeout): Future[String]
  def registerProvider(provider: Provider)(implicit
      timeout: Timeout
  ): Future[Try[RegistryResult]]
  def registerProviders(provider: Seq[Provider])(implicit
      timeout: Timeout
  ): Future[Seq[Try[RegistryResult]]]
  def unregisterProvider(provider: Provider)(implicit
      timeout: Timeout
  ): Future[Try[RegistryResult]]
  def shutdown(): Unit
}

class LoadBalancerImpl(
    strategy: LoadBalancerStrategy,
    maxNumOfProviders: Int,
    providerCapacity: Int,
    healthCheckInterval: FiniteDuration,
    healthTrashHold: Int
)(implicit ec: ExecutionContext)
    extends LoadBalancer {

  private val system: ActorSystem = ActorSystem("loadBalancerActorSystem")

  private val loadBalancerActor = system.actorOf(
    Props(
      classOf[LoadBalancerActor],
      strategy,
      maxNumOfProviders,
      providerCapacity,
      healthCheckInterval,
      healthTrashHold
    ),
    s"loadBalancerActor"
  )

  override def get()(implicit timeout: Timeout): Future[String] =
    loadBalancerActor.ask(GetRequest).mapTo[Response].flatMap {
      case GetResponse(message) => Future.successful(message)
      case ErrorResponse(message) =>
        Future.failed(new IllegalStateException(message))
    }

  override def registerProvider(
      provider: Provider
  )(implicit timeout: Timeout): Future[Try[RegistryResult]] =
    loadBalancerActor
      .ask(RegisterProviderRequest(provider))
      .mapTo[Response]
      .flatMap {
        case RegisterProviderResponse(providerId) =>
          Future.successful(Success(RegistryResult(providerId)))
        case ErrorResponse(message) =>
          Future.successful(Failure(new IllegalStateException(message)))
      }

  override def unregisterProvider(
      provider: Provider
  )(implicit timeout: Timeout): Future[Try[RegistryResult]] =
    loadBalancerActor
      .ask(UnregisterProviderRequest(provider.id))
      .mapTo[Response]
      .flatMap {
        case UnregisterProviderResponse(providerId) =>
          Future.successful(Success(RegistryResult(providerId)))
        case ErrorResponse(message) =>
          Future.successful(Failure(new IllegalStateException(message)))
      }

  override def shutdown(): Unit = loadBalancerActor ! PoisonPill

  override def registerProviders(provider: Seq[Provider])(implicit
      timeout: Timeout
  ): Future[Seq[Try[RegistryResult]]] =
    Future.sequence(provider.map(registerProvider))
}

object LoadBalancer {
  def apply(
      strategy: LoadBalancerStrategy = new RoundRobinStrategy,
      maxNumOfProviders: Int = 10,
      providerCapacity: Int = 10,
      healthCheckInterval: FiniteDuration = 5.seconds,
      healthTrashHold: Int = 2
  )(implicit ec: ExecutionContext): LoadBalancer = new LoadBalancerImpl(
    strategy,
    maxNumOfProviders,
    providerCapacity,
    healthCheckInterval,
    healthTrashHold
  )
}
