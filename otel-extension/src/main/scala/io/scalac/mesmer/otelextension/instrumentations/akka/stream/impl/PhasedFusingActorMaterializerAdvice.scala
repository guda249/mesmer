package io.scalac.mesmer.otelextension.instrumentations.akka.stream.impl

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter._
import net.bytebuddy.asm.Advice._

import io.scalac.mesmer.core.event.ActorEvent
import io.scalac.mesmer.core.event.EventBus
import io.scalac.mesmer.core.model.ActorRefTags
import io.scalac.mesmer.core.model.Tag

object PhasedFusingActorMaterializerAdvice {

  @OnMethodExit
  def actorOf(@Return ref: ActorRef, @This self: Object): Unit =
    EventBus(self.asInstanceOf[akka.MesmerMirrorTypes.ExtendedActorMaterializerMirror].system.toTyped)
      .publishEvent(ActorEvent.TagsSet(ActorRefTags(ref, Set(Tag.stream))))
}
