package ai.moeru.airicraft.dashboard;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticRedactorTest {
	@Test void removesKnownSecretsFromKeysValuesAndEscapedEmbeddedJson() {
		var redactor = new DiagnosticRedactor(List.of("api-key-with-quote-\"-and-newline-\n", "bridge-credential", "viewer-credential", "Bearer otlp-credential"));
		var value = JsonParser.parseString("{\"model\":\"bridge-credential\",\"viewer-credential\":\"x\",\"requestBody\":\"{\\\"note\\\":\\\"otlp-credential\\\"}\"}");
		String result = redactor.redact(value).toString();
		assertFalse(result.contains("credential"));
		assertFalse(redactor.redact("api-key-with-quote-\"-and-newline-\n").contains("api-key"));
		assertTrue(result.contains("[REDACTED]"));
	}

	@Test void redactionIsStableWhenTextIsSanitizedAtMultipleBoundaries() {
		var redactor = new DiagnosticRedactor(List.of("known-key", "unusual credential with spaces"));
		String once = redactor.redact("Authorization: Bearer known-key\napi_key=unknown-key");
		assertEquals("Authorization: [REDACTED]\napi_key=[REDACTED]", once);
		assertEquals(once, redactor.redact(once));
		assertEquals("apiKey=[REDACTED]", redactor.redact("apiKey=unusual credential with spaces"));
	}

	@Test void removesAuthHeadersCredentialFieldsAndUrlCredentialsWithoutKnownValues() {
		var redactor = new DiagnosticRedactor(List.of());
		var value = JsonParser.parseString("{\"headers\":{\"Authorization\":\"Bearer unknown-auth\",\"X-Api-Key\":\"unknown-key\"},\"password\":\"unknown-password\",\"note\":\"Authorization: Bearer pasted-token\\nhttps://user:pass@example.org/path?api_key=query-secret&x=1#token=fragment-secret\"}");
		String result = redactor.redact(value).toString();
		for (String secret : List.of("unknown-auth", "unknown-key", "unknown-password", "pasted-token", "user:pass", "query-secret", "fragment-secret")) assertFalse(result.contains(secret), secret);
		assertTrue(result.contains("example.org"));
	}
}
