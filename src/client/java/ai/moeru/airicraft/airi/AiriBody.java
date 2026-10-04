package ai.moeru.airicraft.airi;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.EmbodiedAgentRuntime;
import ai.moeru.airicraft.agent.events.AgentEventBus;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

/**
 * Joins the AIRI link and the agent runtime. Commands from AIRI go to the planner. Body status, chat, alarms and
 * command progress go to AIRI. Everything except the link listener runs on the client thread.
 */
public final class AiriBody implements AutoCloseable {
	private static final int STATUS_EVERY_TICKS = 20;
	private static final Set<String> REPORTED_EVENTS = Set.of(
		"player.died", "combat.damage_taken", "work.changed", "social.player_spoke");

	private final AiriLink link;
	private final Supplier<EmbodiedAgentRuntime> runtimes;
	private final AiriBodyReport report = new AiriBodyReport(() -> UUID.randomUUID().toString());
	private EmbodiedAgentRuntime runtime;
	private AgentEventBus.Subscription subscription;
	private boolean linkReady;
	private long lastBodyChatCount;
	private boolean inWorld;
	private int ticks;

	public AiriBody(AiriLink link, Supplier<EmbodiedAgentRuntime> runtimes) {
		this.link = Objects.requireNonNull(link, "link");
		this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
		link.onEvent("spark:command", event -> Minecraft.getInstance().execute(() -> onCommand(event)));
	}

	public void tick(Minecraft minecraft) {
		if (link.state() == AiriLink.State.DISABLED) {
			// The standalone track: the planner is the only brain, and this class does no work.
			// The open command ends here. Its guidance can still be in the planner queue.
			report.dropOpenCommand("The link to AIRI was turned off.");
			close();
			linkReady = false;
			return;
		}
		follow(runtimes.get());
		boolean nowInWorld = runtime.sessionSnapshot().worldLoaded();
		if (inWorld && !nowInWorld) {
			send(report.worldLeft());
		}
		inWorld = nowInWorld;
		boolean ready = link.state() == AiriLink.State.READY;
		if (ready && !linkReady) {
			report.resend();
		}
		linkReady = ready;
		if (!ready) {
			// AIRI does not get old body lines after a reconnect.
			lastBodyChatCount = runtime.chatSentLineCount();
			return;
		}
		reportBodyChat(minecraft);
		if (++ticks % STATUS_EVERY_TICKS == 0) {
			send(report.status(facts(minecraft), System.currentTimeMillis()));
		}
	}

	@Override
	public void close() {
		if (subscription != null) {
			closeSubscription();
		}
		runtime = null;
	}

	private void onCommand(AiriEvent event) {
		AiriSparkCommand command;
		try {
			command = AiriSparkCommand.parse(event.data());
		}
		catch (IllegalArgumentException exception) {
			Airicraft.LOGGER.warn("Ignored a spark:command from AIRI: {}", exception.getMessage());
			return;
		}
		follow(runtimes.get());
		String refusal = runtime.onAiriCommand(command.commandId(), command.plannerText());
		send(refusal == null ? report.commandAccepted(command.commandId()) : List.of(report.commandRefused(command.commandId(), refusal)));
	}

	/** Subscribes to the current runtime. A reload makes a new runtime, so the open command is dropped. */
	private void follow(EmbodiedAgentRuntime current) {
		if (current == runtime) {
			return;
		}
		if (subscription != null) {
			closeSubscription();
			send(report.dropOpenCommand("The body reloaded."));
		}
		runtime = current;
		lastBodyChatCount = current.chatSentLineCount();
		subscription = current.subscribeEvents(REPORTED_EVENTS::contains,
			event -> onClientThread(() -> send(report.event(event.type(), event.payload(), System.currentTimeMillis()))));
	}

	private static void onClientThread(Runnable task) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.isSameThread()) {
			task.run();
		}
		else {
			minecraft.execute(task);
		}
	}

	private void closeSubscription() {
		subscription.close();
		subscription = null;
	}

	/** The lines that the body itself says in the game. Each line goes out once, also when one tick sends several. */
	private void reportBodyChat(Minecraft minecraft) {
		long count = runtime.chatSentLineCount();
		if (count == lastBodyChatCount) {
			return;
		}
		List<String> lines = runtime.chatLinesSentAfter(lastBodyChatCount);
		lastBodyChatCount = count;
		String name = minecraft.player == null ? "The body" : minecraft.player.getName().getString();
		for (String line : lines) {
			send(report.chat(name + " (the body)", line));
		}
	}

	private AiriBodyReport.BodyFacts facts(Minecraft minecraft) {
		var player = minecraft.player;
		var session = runtime.sessionSnapshot();
		if (player == null || !session.worldLoaded()) {
			return new AiriBodyReport.BodyFacts(false, false, "", 0, 0, 0, 0F, 0F, 0, runtime.plannerEnabled());
		}
		String dimension = Objects.requireNonNullElse(session.dimensionId(), "an unknown dimension");
		return new AiriBodyReport.BodyFacts(true, player.isDeadOrDying(), dimension, player.getBlockX(),
			player.getBlockY(), player.getBlockZ(), player.getHealth(), player.getMaxHealth(), player.getFoodData().getFoodLevel(),
			runtime.plannerEnabled());
	}

	private void send(List<AiriBodyReport.Outgoing> events) {
		for (AiriBodyReport.Outgoing event : events) {
			link.send(event.type(), event.data());
		}
	}
}
