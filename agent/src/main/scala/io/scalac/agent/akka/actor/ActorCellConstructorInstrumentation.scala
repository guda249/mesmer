package io.scalac.agent.akka.actor

import net.bytebuddy.asm.Advice.{ OnMethodExit, This }

import io.scalac.extension.actor.{ MailboxTimeDecorator, MessageCounterDecorators }

class ActorCellConstructorInstrumentation
object ActorCellConstructorInstrumentation {

  @OnMethodExit
  def onEnter(@This actorCell: Object): Unit =
    Option(actorCell).foreach { ac =>
      MailboxTimeDecorator.setAggregator(ac)
      MessageCounterDecorators.setCounters(ac)
    }

}
