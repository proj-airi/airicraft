package ai.moeru.airicraft.agent.evaluation;

import ai.moeru.airicraft.Airicraft;
import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.Util;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class EvaluationWorldFixtureService {
	public static final String METADATA_FILENAME = ".airicraft-evaluation.json";

	private static final Gson GSON = new Gson();
	private static final long SAVE_TIMEOUT_SECONDS = 10L;
	private static final DateTimeFormatter DISPOSABLE_WORLD_SUFFIX = DateTimeFormatter.ofPattern("yy-MM-dd_HH-mm-ss");

	private final Path gameDir;
	private final EvaluationScenarioRepository repository;

	public EvaluationWorldFixtureService(Path gameDir, EvaluationScenarioRepository repository) {
		this.gameDir = gameDir.toAbsolutePath().normalize();
		this.repository = repository;
	}

	public static EvaluationWorldFixtureService createDefault() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Path scenariosRoot = Path.of(System.getProperty(
			"airicraft.scenariosDir",
			gameDir.resolve("..").resolve("scenarios").normalize().toString()
		));
		return new EvaluationWorldFixtureService(gameDir, new EvaluationScenarioRepository(scenariosRoot));
	}

	public EvaluationScenarioRepository repository() {
		return repository;
	}

	public FreezeResult freezeCurrentWorld() {
		CurrentWorld currentWorld = requireCurrentIntegratedWorld();
		return freezeWorld(currentWorld.path(), currentWorld.directoryName(), () -> saveServer(currentWorld.server()));
	}

	FreezeResult freezeWorld(Path worldPath, String directoryName, Runnable saveAction) {
		String scenarioId = EvaluationScenarioRepository.normalizeScenarioId(directoryName);
		Optional<EvaluationScenario> existing = repository.find(scenarioId);
		Path archivePath;
		EvaluationScenario scenario;
		if (existing.isPresent()) {
			scenario = existing.get();
			archivePath = repository.archivePath(scenario);
			if (scenario.frozen() && Files.exists(archivePath)) {
				throw new EvaluationWorldFixtureException("scenario_frozen", "Scenario is already frozen: " + scenarioId);
			}
		}
		else {
			scenario = defaultScenario(scenarioId, directoryName);
			archivePath = repository.archivePath(scenario);
		}

		saveAction.run();
		try {
			Files.createDirectories(archivePath.getParent());
			zipDirectory(worldPath.toAbsolutePath().normalize(), archivePath);
			EvaluationScenario frozenScenario = new EvaluationScenario(
				scenario.id(),
				scenario.name(),
				scenario.minecraftVersion(),
				scenario.airicraftVersion(),
				repository.configPath(scenario.id()),
				scenario.worldArchive(),
				true,
				scenario.prompt(),
				scenario.budget(),
				scenario.checks(),
				scenario.waypoints(),
				scenario.evidence(),
				scenario.requiredMods()
			);
			repository.write(frozenScenario);
			return new FreezeResult(scenarioId, repository.configPath(scenarioId), archivePath, true);
		}
		catch (IOException exception) {
			throw new EvaluationWorldFixtureException("freeze_failed", "Failed to freeze current world", exception);
		}
	}

	public FreezeResult unfreezeCurrentWorld() {
		Path configPath = resolveCurrentScenarioConfig()
			.orElseThrow(() -> new EvaluationWorldFixtureException("scenario_not_found", "No scenario config is associated with the current world"));
		try {
			EvaluationScenario scenario = EvaluationScenarioLoader.load(configPath);
			EvaluationScenario unfrozen = new EvaluationScenario(
				scenario.id(),
				scenario.name(),
				scenario.minecraftVersion(),
				scenario.airicraftVersion(),
				configPath,
				scenario.worldArchive(),
				false,
				scenario.prompt(),
				scenario.budget(),
				scenario.checks(),
				scenario.waypoints(),
				scenario.evidence(),
				scenario.requiredMods()
			);
			EvaluationScenarioLoader.write(configPath, unfrozen);
			return new FreezeResult(unfrozen.id(), configPath, repository.archivePath(unfrozen), false);
		}
		catch (IOException exception) {
			throw new EvaluationWorldFixtureException("unfreeze_failed", "Failed to unfreeze scenario", exception);
		}
	}

	public Path openCurrentScenarioConfig() {
		Path configPath = resolveCurrentScenarioConfig()
			.orElseThrow(() -> new EvaluationWorldFixtureException("scenario_not_found", "No scenario config is associated with the current world"));
		Util.getPlatform().openPath(configPath);
		return configPath;
	}

	public Optional<Path> resolveCurrentScenarioConfig() {
		CurrentWorld currentWorld = requireCurrentIntegratedWorld();
		Optional<EvaluationWorldMetadata> metadata = readMetadata(currentWorld.path().resolve(METADATA_FILENAME));
		if (metadata.isPresent() && metadata.get().configPath() != null && !metadata.get().configPath().isBlank()) {
			Path path = Path.of(metadata.get().configPath()).toAbsolutePath().normalize();
			if (Files.exists(path)) {
				return Optional.of(path);
			}
		}
		String derivedScenarioId = EvaluationScenarioRepository.normalizeScenarioId(currentWorld.directoryName());
		Path derivedConfig = repository.configPath(derivedScenarioId);
		return Files.exists(derivedConfig) ? Optional.of(derivedConfig) : Optional.empty();
	}

	public RestoredWorld restoreScenarioWorld(EvaluationScenario scenario) {
		Path archivePath = repository.archivePath(scenario);
		if (Files.notExists(archivePath)) {
			throw new EvaluationWorldFixtureException("world_archive_not_found", "World archive not found for scenario: " + scenario.id());
		}
		Path savesDir = gameDir.resolve("saves").normalize();
		DisposableWorldTarget target = null;
		try {
			target = nextDisposableWorldTarget(savesDir, scenario.id());
			Files.createDirectories(savesDir);
			unzipDirectory(archivePath, target.path());
			writeMetadata(target.path().resolve(METADATA_FILENAME), new EvaluationWorldMetadata(
				scenario.id(),
				repository.configPath(scenario.id()).toAbsolutePath().normalize().toString(),
				archivePath.toAbsolutePath().normalize().toString()
			));
			return new RestoredWorld(scenario.id(), target.worldName(), target.path());
		}
		catch (IOException exception) {
			if (target != null) {
				deleteQuietly(target.path());
			}
			throw new EvaluationWorldFixtureException("world_restore_failed", "Failed to restore scenario world: " + scenario.id(), exception);
		}
	}

	public int disposableWorldCount() {
		Path savesDir = gameDir.resolve("saves").normalize();
		if (Files.notExists(savesDir)) {
			return 0;
		}
		try (var stream = Files.list(savesDir)) {
			return (int) stream
				.filter(Files::isDirectory)
				.filter(this::isDisposableWorldDirectory)
				.count();
		}
		catch (IOException exception) {
			throw new EvaluationWorldFixtureException("disposable_world_list_failed", "Failed to list evaluation world copies", exception);
		}
	}

	public CleanupResult cleanupDisposableWorlds() {
		Path savesDir = gameDir.resolve("saves").normalize();
		if (Files.notExists(savesDir)) {
			return new CleanupResult(0);
		}
		int deletedCount = 0;
		try (var stream = Files.list(savesDir)) {
			for (Path worldDir : stream.filter(Files::isDirectory).sorted(Comparator.comparing(Path::toString)).toList()) {
				if (isDisposableWorldDirectory(worldDir)) {
					deleteDirectory(worldDir);
					deletedCount++;
				}
			}
			return new CleanupResult(deletedCount);
		}
		catch (IOException exception) {
			throw new EvaluationWorldFixtureException("disposable_world_cleanup_failed", "Failed to clean evaluation world copies", exception);
		}
	}

	private CurrentWorld requireCurrentIntegratedWorld() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.getSingleplayerServer() == null || !minecraft.hasSingleplayerServer()) {
			throw new EvaluationWorldFixtureException("world_not_loaded", "An integrated singleplayer world must be loaded");
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		Path worldPath = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		return new CurrentWorld(server, worldPath, worldPath.getFileName().toString());
	}

	private static EvaluationScenario defaultScenario(String scenarioId, String displayName) {
		return new EvaluationScenario(
			scenarioId,
			displayName,
			"1.21.8",
			"dev",
			null,
			"world.zip",
			false,
			"",
			EvaluationBudget.defaults(),
			java.util.List.of(),
			java.util.List.of(),
			EvaluationEvidenceSettings.defaults()
		);
	}

	private static void saveServer(IntegratedServer server) {
		CompletableFuture<Boolean> future = new CompletableFuture<>();
		server.executeIfPossible(() -> {
			try {
				server.getPlayerList().saveAll();
				future.complete(server.saveAllChunks(false, true, true));
			}
			catch (Throwable throwable) {
				future.completeExceptionally(throwable);
			}
		});
		try {
			if (!future.get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				throw new EvaluationWorldFixtureException("save_failed", "Minecraft reported that saving failed");
			}
		}
		catch (EvaluationWorldFixtureException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new EvaluationWorldFixtureException("save_failed", "Failed to save world before freezing", exception);
		}
	}

	private static void zipDirectory(Path sourceDir, Path archivePath) throws IOException {
		try (OutputStream fileOut = Files.newOutputStream(archivePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			 ZipOutputStream zipOut = new ZipOutputStream(fileOut, StandardCharsets.UTF_8);
			 var stream = Files.walk(sourceDir)) {
			for (Path path : stream.sorted().toList()) {
				if (Files.isDirectory(path)) {
					continue;
				}
				Path relative = sourceDir.relativize(path);
				if (shouldSkipArchiveEntry(relative)) {
					continue;
				}
				ZipEntry entry = new ZipEntry(relative.toString().replace('\\', '/'));
				zipOut.putNextEntry(entry);
				Files.copy(path, zipOut);
				zipOut.closeEntry();
			}
		}
	}

	private static void unzipDirectory(Path archivePath, Path targetDir) throws IOException {
		if (Files.exists(targetDir)) {
			throw new IOException("Target world directory already exists: " + targetDir);
		}
		Files.createDirectories(targetDir);
		try (InputStream fileIn = Files.newInputStream(archivePath);
			 ZipInputStream zipIn = new ZipInputStream(fileIn, StandardCharsets.UTF_8)) {
			ZipEntry entry;
			while ((entry = zipIn.getNextEntry()) != null) {
				Path target = targetDir.resolve(entry.getName()).normalize();
				if (!target.startsWith(targetDir)) {
					throw new IOException("Unsafe archive entry: " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(target);
				}
				else {
					Files.createDirectories(target.getParent());
					Files.copy(zipIn, target);
				}
				zipIn.closeEntry();
			}
		}
	}

	private static boolean shouldSkipArchiveEntry(Path relative) {
		String normalized = relative.toString().replace('\\', '/');
		return "session.lock".equals(normalized) || METADATA_FILENAME.equals(normalized);
	}

	private DisposableWorldTarget nextDisposableWorldTarget(Path savesDir, String scenarioId) throws IOException {
		Files.createDirectories(savesDir);
		String baseName = disposableWorldName(scenarioId);
		for (int attempt = 0; attempt < 100; attempt++) {
			String worldName = attempt == 0 ? baseName : baseName + "-" + attempt;
			Path targetDir = savesDir.resolve(worldName).normalize();
			if (!targetDir.startsWith(savesDir)) {
				throw new IOException("Unsafe disposable world directory: " + worldName);
			}
			if (Files.notExists(targetDir)) {
				return new DisposableWorldTarget(worldName, targetDir);
			}
		}
		throw new IOException("Failed to allocate unique disposable world directory for scenario: " + scenarioId);
	}

	private boolean isDisposableWorldDirectory(Path worldDir) {
		Path savesDir = gameDir.resolve("saves").normalize();
		Path normalized = worldDir.toAbsolutePath().normalize();
		return normalized.startsWith(savesDir) && Files.exists(normalized.resolve(METADATA_FILENAME));
	}

	private static String disposableWorldName(String scenarioId) {
		String safeId = scenarioId.replaceAll("[^a-zA-Z0-9._-]+", "-");
		return "airicraft_eval_" + safeId + "_" + LocalDateTime.now().format(DISPOSABLE_WORLD_SUFFIX);
	}

	private static Optional<EvaluationWorldMetadata> readMetadata(Path metadataPath) {
		if (Files.notExists(metadataPath)) {
			return Optional.empty();
		}
		try (Reader reader = Files.newBufferedReader(metadataPath, StandardCharsets.UTF_8)) {
			return Optional.ofNullable(GSON.fromJson(reader, EvaluationWorldMetadata.class));
		}
		catch (IOException | RuntimeException exception) {
			Airicraft.LOGGER.warn("Failed to read evaluation world metadata {}", metadataPath, exception);
			return Optional.empty();
		}
	}

	private static void writeMetadata(Path metadataPath, EvaluationWorldMetadata metadata) throws IOException {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("scenarioId", metadata.scenarioId());
		payload.put("configPath", metadata.configPath());
		payload.put("worldArchive", metadata.worldArchive());
		Files.writeString(metadataPath, GSON.toJson(payload), StandardCharsets.UTF_8);
	}

	private static void deleteQuietly(Path path) {
		if (path == null || Files.notExists(path)) {
			return;
		}
		try {
			deleteDirectory(path);
		}
		catch (IOException ignored) {
		}
	}

	private static void deleteDirectory(Path path) throws IOException {
		try (var stream = Files.walk(path)) {
			for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(current);
			}
		}
	}

	public record FreezeResult(String scenarioId, Path configPath, Path archivePath, boolean frozen) {
		public Map<String, Object> toPayload() {
			return Map.of(
				"scenarioId", scenarioId,
				"configPath", configPath.toString(),
				"archivePath", archivePath.toString(),
				"frozen", frozen
			);
		}
	}

	public record RestoredWorld(String scenarioId, String worldName, Path path) {
	}

	public record CleanupResult(int deletedCount) {
	}

	private record DisposableWorldTarget(String worldName, Path path) {
	}

	private record CurrentWorld(IntegratedServer server, Path path, String directoryName) {
	}

	public static final class EvaluationWorldFixtureException extends RuntimeException {
		private final String code;

		public EvaluationWorldFixtureException(String code, String message) {
			super(message);
			this.code = code;
		}

		public EvaluationWorldFixtureException(String code, String message, Throwable cause) {
			super(message, cause);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
