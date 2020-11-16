package com.baracuda25.server.actors

import akka.actor.{Actor, ActorRef, Cancellable, Props, Stash}
import akka.event.Logging
import akka.pattern.pipe
import com.baracuda25.server.{Alive, Dead, Provider, Status}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.{Duration, FiniteDuration}

case class HealthCheck(originalSender: Option[ActorRef])
    extends Event
    with Request
case class ProviderGetRequest(originalSender: ActorRef) extends Request
case class ProviderGetResponse(
    id: String,
    message: String,
    originalSender: ActorRef
) extends Response
case class ProviderErrorResponse(
    id: String,
    message: String,
    originalSender: ActorRef
) extends Response

case class ProviderState(id: String, status: Status) extends Event
case class ProviderStateResponse(
    state: ProviderState,
    originalSender: Option[ActorRef]
) extends Response
case class ProviderRegistered(originalSender: ActorRef, state: ProviderState)

class ProviderActor(
    provider: Provider,
    healthCheckInterval: FiniteDuration,
    healthTrashHold: Int
) extends Actor
    with Stash {

  val log = Logging(context.system, this)

  private implicit val ec: ExecutionContextExecutor = context.dispatcher

  private val healthCheckTask: Cancellable =
    context.system.scheduler.scheduleWithFixedDelay(
      Duration.Zero,
      healthCheckInterval,
      self,
      HealthCheck(None)
    )

  private val providerId: String = provider.id

  override def receive: Receive =
    recovery(None) orElse healthCheck orElse doStash

  def recovery(aliveStatusNumAfterDead: Option[Int]): Receive = {
    case ProviderStateResponse(state, originalSender)
        if state.status == Alive =>
      aliveStatusNumAfterDead
        .map(_ + 1)
        .filter(_ < healthTrashHold)
        .fold {
          context.become(operational orElse healthCheck)
          notifyParent(state, originalSender)
          unstashAll()
        } { alives =>
          context.become(
            recovery(Some(alives)) orElse healthCheck orElse doStash
          )
        }
  }

  def doStash: Receive = { case _ =>
    stash()
  }

  def healthCheck: Receive = {
    case HealthCheck(originalSender) =>
      provider
        .check()
        .map(ProviderState(providerId, _))
        .recover { case t: Throwable =>
          log.error(t, "Error during health check.")
          ProviderState(providerId, Dead)
        }
        .map(ProviderStateResponse(_, originalSender)) pipeTo self
    case ProviderStateResponse(state, originalSender) =>
      if (state.status == Dead) {
        context.become(recovery(Some(0)) orElse healthCheck orElse doStash)
      }
      notifyParent(state, originalSender)
  }

  private def notifyParent(
      state: ProviderState,
      originalSender: Option[ActorRef]
  ) = {
    originalSender.fold(
      context.parent ! state
    ) { original =>
      context.parent ! ProviderRegistered(original, state)
    }
  }

  override def postStop(): Unit = healthCheckTask.cancel()

  def operational: Receive = {
    case ProviderGetRequest(originalSender: ActorRef) =>
      provider
        .get()
        .map(ProviderGetResponse(providerId, _, originalSender))
        .recover { case t: Throwable =>
          ProviderErrorResponse(providerId, t.getMessage, originalSender)
        } pipeTo sender()
  }
}

object ProviderActor {
  def prop(
      provider: Provider,
      healthCheckInterval: FiniteDuration,
      healthTrashHold: Int
  ): Props =
    Props(
      classOf[ProviderActor],
      provider,
      healthCheckInterval,
      healthTrashHold
    )
}
