package com.baracuda25.server

package object actors {

  trait Message
  trait Request extends Message
  trait Response extends Message
  trait Event extends Message

}
