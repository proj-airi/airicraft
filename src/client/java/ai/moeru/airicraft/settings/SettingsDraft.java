package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.AiricraftConfigLoader;
import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.AgentConfigLoader;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.*;
import java.util.*;

/** An editable settings snapshot; file writes occur only on explicit save. */
public final class SettingsDraft {
	private final Map<String, Document> documents = new LinkedHashMap<>();

	private SettingsDraft(Path directory) throws IOException {
		for (String file : List.of("airicraft.yml", "agent.yml")) {
			Path path = directory.resolve(file);
			documents.put(file, new Document(path, Files.exists(path) ? Files.readString(path) : null));
		}
	}

	public static SettingsDraft open(Path directory) throws IOException { return new SettingsDraft(directory); }

	@SuppressWarnings("unchecked")
	public <T> T get(String file, String key, T fallback) {
		Document document = documents.get(file);
		Object value = document.value(key, fallback);
		document.initial.putIfAbsent(key, value);
		document.defaults.putIfAbsent(key, fallback);
		return (T) document.changes.getOrDefault(key, value);
	}

	public void set(String file, String key, Object value) {
		Document document = documents.get(file);
		Object initial = document.initial.getOrDefault(key, document.value(key, null));
		if (Objects.equals(value, initial)) document.changes.remove(key);
		else document.changes.put(key, value);
	}

	public boolean isDirty() {
		return documents.values().stream().anyMatch(document -> !document.changes.isEmpty());
	}

	Map<String, Object> agentDefaults() { return new LinkedHashMap<>(documents.get("agent.yml").defaults); }

	/** Saves a validated draft, then applies it. A failed apply restores the files for retry. */
	public boolean saveAndReload(Runnable reload) throws IOException {
		if (documents.values().stream().allMatch(document -> document.changes.isEmpty())) return false;
		Map<Document, String> rendered = new LinkedHashMap<>();
		for (Document document : documents.values()) {
			String current = Files.exists(document.path) ? Files.readString(document.path) : null;
			if (!Objects.equals(document.original, current)) {
				throw new IOException("Settings changed outside this menu. Close and reopen it before saving.");
			}
			rendered.put(document, document.render());
		}
		AiricraftConfigLoader.fromMapStrict(parse(rendered.get(documents.get("airicraft.yml"))), AiricraftConfig.defaults());
		AgentConfigLoader.fromMapStrict(parse(rendered.get(documents.get("agent.yml"))), AgentConfig.defaults());

		List<Document> written = new ArrayList<>();
		try {
			for (var entry : rendered.entrySet()) {
				if (entry.getKey().changes.isEmpty()) continue;
				replace(entry.getKey().path, entry.getValue());
				written.add(entry.getKey());
			}
			reload.run();
		} catch (IOException | RuntimeException exception) {
			for (Document document : written.reversed()) {
				try {
					if (document.original == null) Files.deleteIfExists(document.path);
					else replace(document.path, document.original);
				} catch (IOException rollbackFailure) { exception.addSuppressed(rollbackFailure); }
			}
			throw exception;
		}
		return true;
	}

	private static void replace(Path path, String text) throws IOException {
		Files.createDirectories(path.getParent());
		Path temporary = Files.createTempFile(path.getParent(), ".airicraft-settings-", ".tmp");
		try {
			Files.writeString(temporary, text);
			try {
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally { Files.deleteIfExists(temporary); }
	}

	private static Yaml yaml() {
		LoaderOptions loader = new LoaderOptions();
		loader.setProcessComments(true);
		loader.setAllowDuplicateKeys(false);
		DumperOptions dumper = new DumperOptions();
		dumper.setProcessComments(true);
		dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		return new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> parse(String text) {
		Object value = yaml().load(text == null ? "" : text);
		if (value == null) return Map.of();
		if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
			throw new IllegalArgumentException("Settings must be a YAML mapping with named fields.");
		}
		return (Map<String, Object>) value;
	}

	private static final class Document {
		private final Path path;
		private final String original;
		private final Map<String, Object> values;
		private final Map<String, Object> initial = new HashMap<>();
		private final Map<String, Object> defaults = new LinkedHashMap<>();
		private final Map<String, Object> changes = new LinkedHashMap<>();

		private Document(Path path, String original) {
			this.path = path;
			this.original = original;
			this.values = parse(original);
		}

		private Object value(String key, Object fallback) {
			Object value = values;
			for (String part : key.split("\\.")) {
				if (!(value instanceof Map<?, ?> map)) return fallback;
				value = map.get(part);
			}
			return value == null ? fallback : value;
		}

		private String render() {
			if (changes.isEmpty()) return original == null ? "{}\n" : original;
			Yaml yaml = yaml();
			Node node = yaml.compose(new StringReader(original == null ? "{}" : original));
			MappingNode root = node instanceof MappingNode mapping ? mapping : new MappingNode(Tag.MAP, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
			for (var entry : changes.entrySet()) patch(root, entry.getKey().split("\\."), 0, yaml.represent(entry.getValue()));
			StringWriter output = new StringWriter();
			yaml.serialize(root, output);
			return output.toString();
		}
	}

	private static void patch(MappingNode mapping, String[] path, int depth, Node replacement) {
		List<NodeTuple> entries = mapping.getValue();
		for (int i = 0; i < entries.size(); i++) {
			NodeTuple entry = entries.get(i);
			if (entry.getKeyNode() instanceof ScalarNode key && key.getValue().equals(path[depth])) {
				if (depth == path.length - 1) {
					Node previous = entry.getValueNode();
					replacement.setBlockComments(previous.getBlockComments());
					replacement.setInLineComments(previous.getInLineComments());
					replacement.setEndComments(previous.getEndComments());
					entries.set(i, new NodeTuple(key, replacement));
				} else if (entry.getValueNode() instanceof MappingNode child) {
					patch(child, path, depth + 1, replacement);
				} else {
					throw new IllegalArgumentException("Expected a settings section at " + path[depth]);
				}
				return;
			}
		}
		Node value = replacement;
		if (depth < path.length - 1) {
			MappingNode child = new MappingNode(Tag.MAP, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
			patch(child, path, depth + 1, replacement);
			value = child;
		}
		entries.add(new NodeTuple(new ScalarNode(Tag.STR, path[depth], null, null, DumperOptions.ScalarStyle.PLAIN), value));
	}
}
