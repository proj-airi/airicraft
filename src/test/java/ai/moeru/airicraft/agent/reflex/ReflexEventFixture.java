package ai.moeru.airicraft.agent.reflex;

public final class ReflexEventFixture {
	public static void enqueue(SurvivalReflexRuntime runtime, SurvivalReflexEvent event) {
		runtime.enqueueEventForTests(event);
	}
}
