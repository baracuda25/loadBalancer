package com.baracuda25.server

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

import com.baracuda25.server.actors.ProviderInstance

trait LoadBalancerStrategy {
  def select(providers: Seq[ProviderInstance]): Option[ProviderInstance]
}

class RoundRobinStrategy extends LoadBalancerStrategy {

  val next = new AtomicLong

  override def select(
      providers: Seq[ProviderInstance]
  ): Option[ProviderInstance] =
    if (providers.nonEmpty) {
      val size = providers.size
      val index = (next.getAndIncrement % size).asInstanceOf[Int]
      Some(providers(if (index < 0) size + index else index))
    } else None
}

class RandomStrategy extends LoadBalancerStrategy {
  override def select(
      providers: Seq[ProviderInstance]
  ): Option[ProviderInstance] = {
    if (providers.isEmpty) None
    else Some(providers(ThreadLocalRandom.current.nextInt(providers.size)))
  }
}
