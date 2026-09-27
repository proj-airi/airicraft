package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.agent.AgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CodexConnectionCheckTest {
	@TempDir Path directory;

	@Test
	@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "AIRICRAFT_CODEX_LIVE", matches = "1")
	void checksInstalledCodexWithRealInference() {
		var executable = System.getenv().getOrDefault("AIRICRAFT_CODEX_EXECUTABLE", "codex");
		assertEquals(ConnectionCheck.Result.READY, ConnectionCheck.codex(new AgentConfig.CodexAppServerConfig(executable, "", "", "", 10_000, 60_000)));
	}

	@Test
	void verifiesInferenceAfterLoginAndUsesAnEphemeralReadOnlyThread() throws Exception {
		var executable = server(true, true);
		assertEquals(ConnectionCheck.Result.READY, ConnectionCheck.codex(config(executable)));
		String requests = Files.readString(directory.resolve("requests.jsonl"));
		assertTrue(requests.contains("\"ephemeral\":true"));
		assertTrue(requests.contains("\"sandbox\":\"read-only\""));
		assertTrue(requests.contains("\"model\":\"chosen-model\""));
	}

	@Test
	void nativeVisionChecksIncludeAnImage() throws Exception {
		assertEquals(ConnectionCheck.Result.READY, ConnectionCheck.codex(config(server(true, true)), true));
		assertTrue(Files.readString(directory.resolve("requests.jsonl")).contains("data:image/png;base64,"));
	}

	@Test
	void distinguishesMissingExecutableFromMissingLoginAndFailedInference() throws Exception {
		assertEquals(ConnectionCheck.Result.CODEX_START_FAILED, ConnectionCheck.codex(config(directory.resolve("missing"))));
		assertEquals(ConnectionCheck.Result.CODEX_AUTH_FAILED, ConnectionCheck.codex(config(server(false, true))));
		assertEquals(ConnectionCheck.Result.CODEX_FAILED, ConnectionCheck.codex(config(server(true, false))));
	}

	private AgentConfig.CodexAppServerConfig config(Path executable) {
		return new AgentConfig.CodexAppServerConfig(executable.toString(), "chosen-model", "", "", 3000, 3000);
	}

	private Path server(boolean authenticated, boolean succeeds) throws Exception {
		Path file = directory.resolve("fake-codex");
		String script = """
			#!/usr/bin/python3
			import json, sys, pathlib
			def send(value):
			    print(json.dumps(value), flush=True)
			for line in sys.stdin:
			    message = json.loads(line)
			    with open(pathlib.Path(__file__).with_name('requests.jsonl'), 'a') as log:
			        log.write(line)
			    if 'id' not in message: continue
			    method = message['method']
			    result = {}
			    if method == 'account/read':
			        result = {'account': ACCOUNT, 'requiresOpenaiAuth': True}
			    if method == 'thread/start':
			        result = {'thread': {'id': 'test-thread'}}
			    if method == 'turn/start':
			        send({'method':'turn/started','params':{'threadId':'test-thread','turn':{'id':'test-turn'}}})
			        result = {'turn': {'id':'test-turn'}}
			    send({'id':message['id'],'result':result})
			    if method == 'turn/start':
			        send({'method':'turn/completed','params':{'threadId':'test-thread','turn':{'id':'test-turn','status':'STATUS','items':[{'type':'agentMessage','text':'OK'}],'error':ERROR}}})
			""".replace("ACCOUNT", authenticated ? "{'type':'chatgpt'}" : "None")
			.replace("STATUS", succeeds ? "completed" : "failed")
			.replace("ERROR", succeeds ? "None" : "{'message':'private credential must never be shown'}");
		Files.writeString(file, script);
		assertTrue(file.toFile().setExecutable(true));
		return file;
	}
}
