package com.baracuda25.server.actors

import akka.actor.{Actor, ActorRef, Stash}
import com.baracuda25.server._

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration

object GetRequest extends Request
case class GetResponse(message: String) extends Response
case class RegisterProviderRequest(provider: Provider) extends Request
case class RegisterProviderResponse(providerId: String) extends Response
case class UnregisterProviderRequest(providerId: String) extends Request
case class UnregisterProviderResponse(providerId: String) extends Response
case class ErrorResponse(message: String) extends Response
case class ProviderInstance(
    id: String,
    status: Status,
    capacity: Int,
    inProgress: Int,
    actorRef: ActorRef
) {
  def isAvailable: Boolean = status == Alive && inProgress < capacity
}

class LoadBalancerActor(
    strategy: LoadBalancerStrategy,
    maxNumOfProviders: Int,
    providerCapacity: Int,
    healthCheckInterval: FiniteDuration,
    healthTrashHold: Int
) extends Actor
    with Stash {

  private implicit val ec: ExecutionContextExecutor = context.dispatcher

  object Initialize

  override def preStart(): Unit = self ! Initialize

  override def receive: Receive = {
    case Initialize =>
      context.become(operational(Nil))
      unstashAll()
    case _ => stash
  }

  def operational(providers: Seq[ProviderInstance]): Receive = {
    case GetRequest =>
      strategy
        .select(providers.filter(_.isAvailable))
        .fold {
          sender() ! ErrorResponse(
            "There is no available provider at the moment!"
          )
        } { provider =>
          updateState(
            providers,
            provider.id,
            _.copy(inProgress = provider.inProgress + 1)
          )
          provider.actorRef ! ProviderGetRequest(sender())
        }
    case ProviderGetResponse(providerId, message, originalSender) =>
      providers
        .find(_.id == providerId)
        .fold(originalSender ! GetResponse(message)) { provider =>
          updateState(
            providers,
            provider.id,
            _.copy(inProgress = provider.inProgress - 1)
          )
          originalSender ! GetResponse(message)
        }
    case RegisterProviderRequest(provider) =>
      if (providers.size < maxNumOfProviders) {
        if (providers.exists(_.id == provider.id)) {
          sender() ! ErrorResponse(
            s"Provider with id ${provider.id} already exists!"
          )
        } else {
          val providerRef =
            context.actorOf(
              ProviderActor.prop(provider, healthCheckInterval, healthTrashHold)
            )
          context.watch(providerRef)
          val originalSender = sender()
          context.become(
            operational(
              providers :+ ProviderInstance(
                provider.id,
                Dead,
                providerCapacity,
                0,
                providerRef
              )
            )
          )
          providerRef ! HealthCheck(Some(sender()))
        }
      } else {
        sender() ! ErrorResponse("Reached maximum amount of providers!")
      }
    case UnregisterProviderRequest(providerId) =>
      providers
        .find(_.id == providerId)
        .fold(sender() ! ErrorResponse(s"Couldn't find provider $providerId")) {
          provider =>
            context.become(operational(providers.filterNot(_.id == providerId)))
            context.stop(provider.actorRef)
            sender() ! UnregisterProviderResponse(provider.id)
        }
    case ProviderState(providerId, status) =>
      updateState(providers, providerId, _.copy(status = status))
    case ProviderRegistered(originalSender, state) =>
      updateState(providers, state.id, _.copy(status = state.status))
      originalSender ! RegisterProviderResponse(state.id)
  }

  private def updateState(
      providers: Seq[ProviderInstance],
      providerId: String,
      update: ProviderInstance => ProviderInstance
  ): Unit =
    context.become(operational(providers.map {
      case p: ProviderInstance if p.id == providerId => update(p)
      case p: ProviderInstance                       => p
    }))

}
