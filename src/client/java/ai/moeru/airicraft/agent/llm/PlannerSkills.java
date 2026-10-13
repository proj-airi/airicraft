package ai.moeru.airicraft.agent.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task-specific planner rules kept out of the system prompt. Each skill is a classpath resource
 * {@code /skills/<name>/SKILL.md} (frontmatter {@code name} and {@code description}, then the body) listed in
 * {@code /skills/index.txt}. The system prompt carries only the one-line index; {@code use_skill} returns a body.
 */
public final class PlannerSkills {
	public record Skill(String name, String description, String body) {}

	private static final String INDEX = "/skills/index.txt";
	private static final PlannerSkills BUNDLED = load();

	private final Map<String, Skill> skills;

	private PlannerSkills(Map<String, Skill> skills) {
		this.skills = skills;
	}

	public static PlannerSkills bundled() {
		return BUNDLED;
	}

	public List<String> names() {
		return List.copyOf(skills.keySet());
	}

	public Optional<Skill> find(String name) {
		return Optional.ofNullable(name == null ? null : skills.get(name.strip()));
	}

	/** One line per skill, for the system prompt. */
	public String index() {
		StringBuilder text = new StringBuilder();
		for (Skill skill : skills.values()) text.append("- ").append(skill.name()).append(": ").append(skill.description()).append('\n');
		return text.toString().stripTrailing();
	}

	private static PlannerSkills load() {
		Map<String, Skill> skills = new LinkedHashMap<>();
		for (String line : read(INDEX).split("\n")) {
			String name = line.strip();
			if (name.isEmpty()) continue;
			skills.put(name, parse(name, read("/skills/" + name + "/SKILL.md")));
		}
		return new PlannerSkills(skills);
	}

	static Skill parse(String expectedName, String text) {
		String normalized = text.replace("\r\n", "\n");
		if (!normalized.startsWith("---\n")) throw new IllegalStateException("skill " + expectedName + " has no frontmatter");
		int end = normalized.indexOf("\n---\n", 4);
		if (end < 0) throw new IllegalStateException("skill " + expectedName + " frontmatter is not closed");
		String name = null, description = null;
		for (String line : normalized.substring(4, end).split("\n")) {
			int colon = line.indexOf(':');
			if (colon < 0) continue;
			String key = line.substring(0, colon).strip(), value = line.substring(colon + 1).strip();
			if (key.equals("name")) name = value;
			if (key.equals("description")) description = value;
		}
		if (!expectedName.equals(name)) throw new IllegalStateException("skill " + expectedName + " declares name " + name);
		if (description == null || description.isBlank()) throw new IllegalStateException("skill " + expectedName + " has no description");
		return new Skill(name, description, normalized.substring(end + 5).strip());
	}

	private static String read(String resource) {
		try (InputStream stream = PlannerSkills.class.getResourceAsStream(resource)) {
			if (stream == null) throw new IllegalStateException("Missing resource " + resource);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}

	static List<String> problems(PlannerSkills skills) {
		List<String> found = new ArrayList<>();
		for (Skill skill : skills.skills.values()) {
			if (skill.description().length() > 160) found.add(skill.name() + " description is over 160 characters");
			if (skill.body().isBlank()) found.add(skill.name() + " has no body");
		}
		return found;
	}
}
