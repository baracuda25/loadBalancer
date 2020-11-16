package com.baracuda25.server

import scala.concurrent.Future

sealed trait Status
object Alive extends Status
object Busy extends Status
object Dead extends Status

trait Provider {
  val id: String
  def get(): Future[String]
  def check(): Future[Status]
}

class DefaultProviderImpl(val id: String) extends Provider {
  override def get(): Future[String] = Future.successful(id)
  override def check(): Future[Status] = Future.successful(Alive)
}

object Provider {
  def apply(id: String): Provider = new DefaultProviderImpl(id)
}
