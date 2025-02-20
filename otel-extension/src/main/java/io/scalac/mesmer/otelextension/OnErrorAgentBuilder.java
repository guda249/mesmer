package io.scalac.mesmer.otelextension;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.tooling.AgentExtension;
import net.bytebuddy.agent.builder.AgentBuilder;

@AutoService(AgentExtension.class)
public class OnErrorAgentBuilder implements AgentExtension {
  @Override
  public AgentBuilder extend(AgentBuilder agentBuilder) {
    return agentBuilder.with(AgentBuilder.Listener.StreamWriting.toSystemOut().withErrorsOnly());
  }

  @Override
  public String extensionName() {
    return "mesmer-debug-run";
  }
}
