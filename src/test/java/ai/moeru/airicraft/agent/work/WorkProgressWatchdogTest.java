package ai.moeru.airicraft.agent.work;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorkProgressWatchdogTest {
	private final WorkSnapshot work = new WorkSnapshot(WorkHandle.of(WorkHandle.Kind.JOB, "break"), "",
		WorkSnapshot.State.RUNNING, "BREAK_BLOCKS", "RUNNING", true, 1, Map.of());
	private WorkProgressWatchdog.Sample sample(double x, double progress, boolean input) {
		return new WorkProgressWatchdog.Sample(x, 135, 0, Map.of("block:1", progress), input);
	}
	@Test void waitingForAimNotifiesOnceWithoutChangingRunningWork() {
		var watchdog = new WorkProgressWatchdog(10);
		for (int i=0;i<10;i++) assertTrue(watchdog.observe(List.of(work), sample(0,0,false), true).isEmpty());
		var notices = watchdog.observe(List.of(work), sample(0,0,false), true);
		assertEquals(1, notices.size());
		assertEquals("no_input", notices.getFirst().reason());
		assertEquals(work.handle().id(), notices.getFirst().workId());
		assertEquals(WorkSnapshot.State.RUNNING, work.state());
		for (int i=0;i<30;i++) assertTrue(watchdog.observe(List.of(work), sample(0,0,false), true).isEmpty());
	}
	@Test void oscillatingMovementAndInputDoNotCountAsProgress() {
		var watchdog = new WorkProgressWatchdog(10);
		int notices=0;
		for(int i=0;i<40;i++) notices += watchdog.observe(List.of(work),sample(i%2*2,0,true),true).size();
		assertEquals(1,notices);
	}
	@Test void stationaryMiningProgressPreventsAlertButRestartingSameDamageDoesNot() {
		var watchdog = new WorkProgressWatchdog(10);
		for(int i=0;i<40;i++) assertTrue(watchdog.observe(List.of(work),sample(0,i/100.0,true),true).isEmpty());
		int notices=0;
		for(int i=0;i<40;i++) notices += watchdog.observe(List.of(work),sample(0,(i%10)/100.0,true),true).size();
		assertEquals(1,notices);
	}
	@Test void pausedTimeDoesNotConsumeBudgetAndContinueGrantsGrace() {
		var watchdog = new WorkProgressWatchdog(10);
		for(int i=0;i<6;i++) watchdog.observe(List.of(work),sample(0,0,false),true);
		for(int i=0;i<100;i++) assertTrue(watchdog.observe(List.of(work),sample(0,0,false),false).isEmpty());
		int notices=0;
		for(int i=0;i<5;i++) notices += watchdog.observe(List.of(work),sample(0,0,false),true).size();
		assertEquals(1,notices);
		watchdog.continueTrying();
		for(int i=0;i<9;i++) assertTrue(watchdog.observe(List.of(work),sample(0,0,false),true).isEmpty());
		assertEquals(1,watchdog.observe(List.of(work),sample(0,0,false),true).size());
	}
	@Test void excludesWaitingWorkAndParentWithRunningChild() {
		var parent = new WorkSnapshot(WorkHandle.of(WorkHandle.Kind.GRAPH,"graph"),"",WorkSnapshot.State.RUNNING,"goal","RUNNING",true,1,Map.of());
		var child = new WorkSnapshot(work.handle(),parent.handle().id(),work.state(),work.label(),work.phase(),true,1,Map.of());
		var wait = new WorkSnapshot(WorkHandle.of(WorkHandle.Kind.SMELTING,"furnace"),"",WorkSnapshot.State.WAITING,"smelting","COOKING",false,1,Map.of());
		var watchdog = new WorkProgressWatchdog(10);
		int notices=0;
		for(int i=0;i<20;i++) {
			var found=watchdog.observe(List.of(parent,child,wait),sample(0,0,false),true);
			for(var notice:found) assertEquals(child.handle().id(),notice.workId());
			notices+=found.size();
		}
		assertEquals(1,notices);
	}
	@Test void realProgressRearmsAndFinishedWorkIsForgotten() {
		var watchdog = new WorkProgressWatchdog(10);
		for(int i=0;i<11;i++) watchdog.observe(List.of(work),sample(0,0,false),true);
		assertTrue(watchdog.observe(List.of(work),sample(0,0.5,true),true).isEmpty());
		for(int i=0;i<9;i++) assertTrue(watchdog.observe(List.of(work),sample(0,0.5,true),true).isEmpty());
		assertEquals(1,watchdog.observe(List.of(work),sample(0,0.5,true),true).size());
		watchdog.observe(List.of(),sample(0,0,false),true);
		assertTrue(watchdog.observe(List.of(work),sample(0,0,false),true).isEmpty());
	}
}
