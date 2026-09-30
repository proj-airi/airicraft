package ai.moeru.airicraft.agent.control;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control plane is the only writer of the player: movement keys, the use key, the selected hotbar
 * slot, rotation, auto-jump, and the interaction manager's attack, break and use calls. Everything else
 * goes through leases, so ownership stays explicit and testable. This scans the sources and fails on a
 * direct write outside {@code agent/control}.
 */
class ActuationGuardTest {
	private static final List<Forbidden> FORBIDDEN = List.of(
		new Forbidden("key binding write", Pattern.compile("\\.setDown\\(")),
		new Forbidden("selected hotbar slot write", Pattern.compile("\\.setSelectedSlot\\(")),
		new Forbidden("hotbar sync packet", Pattern.compile("ServerboundSetCarriedItemPacket")),
		new Forbidden("interaction manager attack, break or use call", Pattern.compile(
			"\\bgameMode\\.(attack|interact|useItem|useItemOn|startDestroyBlock|continueDestroyBlock|stopDestroyBlock)\\(")),
		new Forbidden("auto-jump option write", Pattern.compile("autoJump\\(\\)\\s*\\.set\\(")),
		new Forbidden("player rotation write", Pattern.compile("\\.set(Y|X)Rot\\("))
	);

	/** Files that may still write directly. Keep it empty: each entry is a migration not finished. */
	private static final List<String> ALLOWED = List.of();

	@Test
	void nothingOutsideTheControlPackageWritesThePlayerDirectly() throws IOException {
		Path root = Path.of(System.getProperty("user.dir"));
		List<String> violations = new ArrayList<>();
		for (String source : List.of("src/client/java", "src/main/java")) {
			Path directory = root.resolve(source);
			if (!Files.isDirectory(directory)) continue;
			try (Stream<Path> files = Files.walk(directory)) {
				for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
					String relative = root.relativize(file).toString().replace('\\', '/');
					if (relative.contains("/agent/control/") || ALLOWED.contains(relative)) continue;
					violations.addAll(scan(relative, Files.readAllLines(file)));
				}
			}
		}
		assertTrue(violations.isEmpty(), "Direct player writes outside the control plane; take a lease through "
			+ "ControlPlane, MovementController or Actuator instead:\n" + String.join("\n", violations));
	}

	@Test
	void theScannerFlagsEachKindOfDirectWriteAndIgnoresCommentsAndReads() {
		List<String> lines = List.of(
			"minecraft.options.keyUp.setDown(true);",
			"player.getInventory().setSelectedSlot(3);",
			"connection.send(new ServerboundSetCarriedItemPacket(3));",
			"minecraft.gameMode.useItemOn(player, hand, hit);",
			"minecraft.gameMode.stopDestroyBlock();",
			"minecraft.options.autoJump().set(true);",
			"player.setYRot(90F);",
			"// minecraft.options.keyUp.setDown(true);",
			" * gameMode.attack(player, target)",
			"boolean pressed = minecraft.options.keyUp.isDown();",
			"int slot = player.getInventory().getSelectedSlot();",
			"minecraft.gameMode.handleInventoryMouseClick(1, 2, 3, null, player);"
		);

		List<String> flagged = scan("Sample.java", lines);

		assertEquals(7, flagged.size(), String.join("\n", flagged));
		assertFalse(flagged.stream().anyMatch(line -> line.contains("Sample.java:8") || line.contains("Sample.java:9")),
			"comments are ignored");
		assertFalse(flagged.stream().anyMatch(line -> line.contains("Sample.java:10") || line.contains("Sample.java:11")
			|| line.contains("Sample.java:12")), "reads and inventory clicks are not player writes");
	}

	private static List<String> scan(String file, List<String> lines) {
		List<String> found = new ArrayList<>();
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index);
			String trimmed = line.strip();
			if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
			for (Forbidden forbidden : FORBIDDEN) {
				if (forbidden.pattern().matcher(line).find()) {
					found.add(file + ":" + (index + 1) + " " + forbidden.name() + ": " + trimmed);
				}
			}
		}
		return found;
	}

	private record Forbidden(String name, Pattern pattern) {
	}
}
