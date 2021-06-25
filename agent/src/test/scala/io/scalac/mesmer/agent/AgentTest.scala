package io.scalac.mesmer.agent

import io.scalac.mesmer.agent.Agent.LoadingResult
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.instrument.Instrumentation

class AgentTest extends AnyFlatSpec with Matchers {

  def returning(result: LoadingResult): (AgentBuilder, Instrumentation) => LoadingResult = (_, _) => result

  behavior of "Agent"

  it should "keep one copy of equal instrumentation" in {

    val agentInstrumentationOne = AgentInstrumentation("name", Set("tag"))(returning(LoadingResult.empty))
    val agentInstrumentationTwo = AgentInstrumentation("name", Set("tag"))(returning(LoadingResult.empty))

    val agent = Agent(agentInstrumentationOne, agentInstrumentationTwo)

    agent.instrumentations should have size (1)
  }

  it should "combine result from different agent instrumentations" in {

    val agentInstrumentationOne   = AgentInstrumentation("test_name_one", Set("tag"))(returning(LoadingResult("one")))
    val agentInstrumentationTwo   = AgentInstrumentation("test_name_one", Set.empty)(returning(LoadingResult("two")))
    val agentInstrumentationThree = AgentInstrumentation("test_name_two", Set("tag"))(returning(LoadingResult("three")))

    val expectedResult = LoadingResult(Seq("one", "two", "three"))

    val agent = Agent(agentInstrumentationOne, agentInstrumentationTwo, agentInstrumentationThree)

    agent.installOn(new AgentBuilder.Default(), ByteBuddyAgent.install()) should be(expectedResult)
  }

}
