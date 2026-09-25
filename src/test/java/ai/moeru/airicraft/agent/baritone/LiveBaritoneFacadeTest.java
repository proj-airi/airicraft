package ai.moeru.airicraft.agent.baritone;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import baritone.api.IBaritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.BetterBlockPos;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.event.listener.IEventBus;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IFollowProcess;
import baritone.api.process.IBaritoneProcess;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveBaritoneFacadeTest {
	@Test
	void applySettingsAndPathEventsWorkThroughAnInjectedBaritoneHarness() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		AtomicReference<Boolean> settingsApplied = new AtomicReference<>(false);
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> settingsApplied.set(true));

		facade.applySettings();
		facade.startNavigate(new GoalPosition(12, 64, -8, true));
		harness.publishPathEvent(PathEvent.AT_GOAL);

		assertTrue(settingsApplied.get());
		assertEquals(Optional.of("AT_GOAL"), facade.pollPathEvent());
		assertEquals(Optional.of("custom_goal"), facade.activeProcessName());
		assertEquals(Optional.of(37.5D), facade.estimatedTicksToGoal());

		assertEquals(1, harness.navigateCalls.size());
		assertInstanceOf(GoalBlock.class, harness.navigateCalls.get(0));
		GoalBlock goal = (GoalBlock) harness.navigateCalls.get(0);
		assertEquals(12, goal.x);
		assertEquals(64, goal.y);
		assertEquals(-8, goal.z);
	}

	@Test
	void horizontalNavigationIgnoresHeightInBothPathAndCompletion() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> {}, () -> true);
		GoalPosition destination = new GoalPosition(215, 103, 416, false);
		facade.startNavigate(destination);
		GoalXZ goal = assertInstanceOf(GoalXZ.class, harness.navigateCalls.getFirst());
		assertTrue(goal.isInGoal(215, 97, 416));
		assertTrue(goal.isInGoal(215, 120, 416));
		assertFalse(goal.isInGoal(214, 97, 416));
		harness.feet.set(new BetterBlockPos(215, 97, 416));
		assertTrue(facade.navigationGoalReached(destination));
		assertFalse(facade.navigationGoalReached(new GoalPosition(215, 103, 416, true)));
		harness.feet.set(new BetterBlockPos(214, 97, 416));
		assertFalse(facade.navigationGoalReached(destination));
	}

	@Test
	void fallingThroughExactTargetIsNotArrival() {
		var harness = new RecordingBaritoneHarness();
		var supported = new java.util.concurrent.atomic.AtomicBoolean(false);
		var facade = new LiveBaritoneFacade(harness.baritone(), () -> {}, supported::get);
		harness.feet.set(new BetterBlockPos(318,-10,280));
		assertFalse(facade.navigationGoalReached(new GoalPosition(318,-10,280,true)));
		supported.set(true);
		assertTrue(facade.navigationGoalReached(new GoalPosition(318,-10,280,true)));
	}

	@Test
	void navigationCompletionUsesBaritoneFeetOnPartialHeightGround() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> {}, () -> true);
		// Standing at physical Y 62.9375 on farmland: Baritone feet are Y 63.
		harness.feet.set(new BetterBlockPos(264, 63, 483));
		assertTrue(facade.navigationGoalReached(new GoalPosition(264, 63, 483, true)));
		assertFalse(facade.navigationGoalReached(new GoalPosition(264, 62, 483, true)));
		assertFalse(facade.navigationGoalReached(new GoalPosition(261, 63, 483, true)));
		assertTrue(facade.navigationGoalReached(new GoalPosition(264, 62, 483, false)));
		harness.feet.set(null);
		assertFalse(facade.navigationGoalReached(new GoalPosition(264, 63, 483, true)));
	}

	@Test
	void startFollowAndCancelDelegateToTheInjectedBaritoneProcesses() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> {
		});

		facade.startFollow("Alice");
		assertTrue(facade.processActive());
		facade.cancel();

		assertNotNull(harness.followPredicate.get());
		assertEquals(Optional.of("follow"), facade.activeProcessName());
		assertTrue(harness.cancelEverythingCalled.get());
		assertFalse(facade.processActive());
	}

	@Test
	void startNavigateNearUsesGoalNearRadius() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> {
		});

		facade.startNavigateNear(new GoalPosition(12, 64, -8, false), 3);

		assertEquals(1, harness.navigateCalls.size());
		assertInstanceOf(GoalNear.class, harness.navigateCalls.get(0));
		GoalNear goal = (GoalNear) harness.navigateCalls.get(0);
		assertTrue(goal.isInGoal(12, 64, -8));
		assertTrue(goal.isInGoal(14, 64, -8));
		assertFalse(goal.isInGoal(16, 64, -8));
	}

	@Test
	void delayedInternalCancellationIsAcknowledgedWithoutLeakingToTheNextGoal() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> {
		});

		facade.startNavigate(new GoalPosition(8, 62, 3, true));
		facade.cancel();
		assertTrue(facade.cancellationPending());
		assertFalse(facade.processActive());

		facade.startNavigate(new GoalPosition(2, 62, 3, true));
		harness.publishPathEvent(PathEvent.CANCELED);

		assertFalse(facade.cancellationPending());
		assertEquals(1L, facade.cancellationAcknowledgement());
		assertTrue(facade.pollPathEvent().isEmpty());

		harness.publishPathEvent(PathEvent.CANCELED);
		assertEquals(Optional.of("CANCELED"), facade.pollPathEvent());
	}

	@Test
	void unsafeCancellationDoesNotCreateARequiredReceiptAndSuppressesItsOptionalLaterEvent() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> {
		});
		harness.cancelImmediatelySafe.set(false);

		facade.startNavigate(new GoalPosition(8, 62, 3, true));

		assertFalse(facade.cancel());
		assertFalse(facade.cancellationPending());
		assertTrue(facade.processActive());
		assertEquals(1, harness.cancelEverythingCalls.get());
		assertFalse(facade.cancel());
		assertEquals(1, harness.cancelEverythingCalls.get());

		harness.pathingActive.set(false);
		harness.publishPathEvent(PathEvent.CANCELED);

		assertFalse(facade.processActive());
		assertEquals(1L, facade.cancellationAcknowledgement());
		assertTrue(facade.pollPathEvent().isEmpty());
	}

	@Test
	void optionalCancellationReceiptExpiresBeforeTheNextOperation() {
		RecordingBaritoneHarness harness = new RecordingBaritoneHarness();
		LiveBaritoneFacade facade = new LiveBaritoneFacade(harness.baritone(), () -> {
		});
		harness.cancelImmediatelySafe.set(false);
		facade.startNavigate(new GoalPosition(8, 62, 3, true));
		assertFalse(facade.cancel());
		harness.pathingActive.set(false);

		facade.startNavigate(new GoalPosition(2, 62, 3, true));
		harness.publishPathEvent(PathEvent.CANCELED);

		assertEquals(Optional.of("CANCELED"), facade.pollPathEvent());
	}

	private static final class RecordingBaritoneHarness {
		private final AtomicReference<AbstractGameEventListener> pathListener = new AtomicReference<>();
		private final AtomicReference<String> activeProcessName = new AtomicReference<>();
		private final AtomicReference<Object> followPredicate = new AtomicReference<>();
		private final AtomicReference<Boolean> cancelEverythingCalled = new AtomicReference<>(false);
		private final AtomicInteger cancelEverythingCalls = new AtomicInteger();
		private final AtomicReference<Boolean> cancelImmediatelySafe = new AtomicReference<>(true);
		private final AtomicReference<Boolean> customGoalProcessActive = new AtomicReference<>(false);
		private final AtomicReference<Boolean> followProcessActive = new AtomicReference<>(false);
		private final AtomicReference<Boolean> pathingActive = new AtomicReference<>(false);
		private final AtomicReference<Double> estimatedTicksToGoal = new AtomicReference<>(37.5D);
		private final List<Object> navigateCalls = new ArrayList<>();
		private final IPathingBehavior pathingBehavior = proxy(IPathingBehavior.class, (proxy, method, args) -> switch (method.getName()) {
			case "estimatedTicksToGoal" -> Optional.ofNullable(estimatedTicksToGoal.get());
			case "cancelEverything" -> {
				cancelEverythingCalled.set(true);
				cancelEverythingCalls.incrementAndGet();
				customGoalProcessActive.set(false);
				followProcessActive.set(false);
				if (cancelImmediatelySafe.get()) {
					pathingActive.set(false);
				}
				yield cancelImmediatelySafe.get();
			}
			case "getGoal", "getCurrent", "getNext" -> null;
			case "getInProgress" -> Optional.empty();
			case "isPathing", "hasPath" -> pathingActive.get();
			default -> defaultValue(method);
		});
		private final IFollowProcess followProcess = proxy(IFollowProcess.class, (proxy, method, args) -> switch (method.getName()) {
			case "follow" -> {
				followPredicate.set(args[0]);
				activeProcessName.set("follow");
				followProcessActive.set(true);
				pathingActive.set(true);
				yield null;
			}
			case "pickup" -> null;
			case "following" -> List.of();
			case "currentFilter" -> null;
			case "cancel" -> null;
			case "isActive" -> followProcessActive.get();
			case "onTick" -> null;
			case "isTemporary" -> false;
			case "onLostControl" -> null;
			case "displayName0" -> "follow";
			default -> defaultValue(method);
		});
		private final ICustomGoalProcess customGoalProcess = proxy(ICustomGoalProcess.class, (proxy, method, args) -> switch (method.getName()) {
			case "setGoalAndPath", "setGoal" -> {
				navigateCalls.add(args[0]);
				activeProcessName.set("custom_goal");
				customGoalProcessActive.set(true);
				pathingActive.set(true);
				yield null;
			}
			case "path" -> null;
			case "getGoal", "mostRecentGoal" -> navigateCalls.isEmpty() ? null : navigateCalls.get(navigateCalls.size() - 1);
			case "isActive" -> customGoalProcessActive.get();
			case "onTick" -> null;
			case "isTemporary" -> false;
			case "onLostControl" -> null;
			case "displayName0" -> "custom_goal";
			default -> defaultValue(method);
		});
		private final IBaritoneProcess activeProcess = proxy(IBaritoneProcess.class, (proxy, method, args) -> switch (method.getName()) {
			case "displayName0" -> activeProcessName.get();
			case "isActive" -> customGoalProcessActive.get() || followProcessActive.get();
			case "onTick" -> null;
			case "isTemporary" -> false;
			case "onLostControl" -> null;
			default -> defaultValue(method);
		});
		private final IPathingControlManager pathingControlManager = proxy(IPathingControlManager.class, (proxy, method, args) -> switch (method.getName()) {
			case "mostRecentInControl" -> Optional.ofNullable(activeProcessName.get()).map(name -> activeProcess);
			case "mostRecentCommand" -> Optional.empty();
			default -> defaultValue(method);
		});
		private final IEventBus eventBus = proxy(IEventBus.class, (proxy, method, args) -> switch (method.getName()) {
			case "registerEventListener" -> {
				pathListener.set((AbstractGameEventListener) args[0]);
				yield null;
			}
			default -> defaultValue(method);
		});
		private final AtomicReference<BetterBlockPos> feet = new AtomicReference<>();
		private final IPlayerContext playerContext = proxy(IPlayerContext.class, (proxy, method, args) ->
			method.getName().equals("playerFeet") ? feet.get() : defaultValue(method));
		private final IBaritone baritone = proxy(IBaritone.class, (proxy, method, args) -> switch (method.getName()) {
			case "getPathingBehavior" -> pathingBehavior;
			case "getPlayerContext" -> playerContext;
			case "getFollowProcess" -> followProcess;
			case "getCustomGoalProcess" -> customGoalProcess;
			case "getPathingControlManager" -> pathingControlManager;
			case "getGameEventHandler" -> eventBus;
			case "getMineProcess", "getBuilderProcess", "getExploreProcess", "getFarmProcess", "getGetToBlockProcess", "getElytraProcess", "getWorldProvider", "getInputOverrideHandler", "getSelectionManager", "getCommandManager" -> null;
			case "openClick" -> null;
			default -> defaultValue(method);
		});

		IBaritone baritone() {
			return baritone;
		}

		void publishPathEvent(PathEvent pathEvent) {
			AbstractGameEventListener listener = pathListener.get();
			if (listener == null) {
				throw new IllegalStateException("Expected path event listener to be registered");
			}
			listener.onPathEvent(pathEvent);
		}

		private static <T> T proxy(Class<T> type, InvocationHandler handler) {
			Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
			return type.cast(proxy);
		}

		private static Object defaultValue(Method method) {
			Class<?> returnType = method.getReturnType();
			if (!returnType.isPrimitive()) {
				return null;
			}
			if (returnType == boolean.class) {
				return false;
			}
			if (returnType == int.class) {
				return 0;
			}
			if (returnType == long.class) {
				return 0L;
			}
			if (returnType == double.class) {
				return 0.0D;
			}
			if (returnType == float.class) {
				return 0.0F;
			}
			if (returnType == short.class) {
				return (short) 0;
			}
			if (returnType == byte.class) {
				return (byte) 0;
			}
			if (returnType == char.class) {
				return '\0';
			}
			throw new IllegalStateException("Unsupported primitive return type: " + returnType.getName());
		}
	}
}
