package akka.actor.impl;

import akka.actor.*;
import akka.dispatch.AbstractBoundedNodeQueue;
import akka.dispatch.BoundedMessageQueueSemantics;
import akka.dispatch.BoundedNodeMessageQueue;
import akka.dispatch.BoundedQueueBasedMessageQueue;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.instrumentation.api.field.VirtualField;
import io.scalac.mesmer.otelextension.instrumentations.akka.actor.Instruments;
import io.scalac.mesmer.otelextension.instrumentations.akka.actor.InstrumentsProvider;
import io.scalac.mesmer.otelextension.instrumentations.akka.actor.impl.otel.ActorCellInstrumentationState;
import java.util.Objects;
import net.bytebuddy.asm.Advice;

public class BoundedMessageQueueSemanticsEnqueueAdvice {

  @Advice.OnMethodExit
  public static void queue(
      @Advice.Argument(0) ActorRef ref, @Advice.This BoundedMessageQueueSemantics self) {

    if (ref instanceof ActorRefWithCell) {
      ActorContext context = (ActorCell) ((ActorRefWithCell) ref).underlying();

      Attributes attrs = VirtualField.find(ActorContext.class, Attributes.class).get(context);
      Instruments instruments = InstrumentsProvider.instance();
      ActorCellInstrumentationState state =
          VirtualField.find(ActorContext.class, ActorCellInstrumentationState.class).get(context);

      if (Objects.nonNull(attrs) && Objects.nonNull(state)) {
        if (self instanceof BoundedNodeMessageQueue) {
          if (Boolean.FALSE.equals(
              VirtualField.find(AbstractBoundedNodeQueue.class, Boolean.class)
                  .get((AbstractBoundedNodeQueue<?>) self))) {
            instruments.droppedMessages().add(1L, attrs);
          }

        } else if (self instanceof BoundedQueueBasedMessageQueue) {
          BoundedQueueProxy<?> proxy =
              (BoundedQueueProxy<?>) ((BoundedQueueBasedMessageQueue) self).queue();

          if (!proxy.getResult()) {
            instruments.droppedMessages().add(1L, attrs);
          }
        }
      }
    }
  }
}
