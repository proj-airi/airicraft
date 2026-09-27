package ai.moeru.airicraft.dashboard;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticEvidenceTest {
	private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aJ1sAAAAASUVORK5CYII=";

	@Test void showsRedactedObservationsMetadataAndPixelsFromTheFrozenAttachments() {
		var store = new DashboardObservationStore(1024 * 1024);
		store.append("llm_call", 0, 1, Map.of("requestBody", "{\"content\":\"actual model input with known-secret\",\"image\":\"data:image/png;base64," + PNG + "\"}"));
		store.append("visual_frame", 0, 2, Map.of("format", "png", "imageBase64", PNG));
		var report = DiagnosticReport.mark(store, Map.of("build", Map.of("revision", "exact-build")), List.of("known-secret"))
			.prepare(new DiagnosticReport.Request(DiagnosticReport.Mode.DEVELOPER, "actual description"), List.of());
		var files = report.attachments();
		var pages = DiagnosticEvidence.pages(files);
		String text = pages.stream().map(DiagnosticEvidence.Page::text).reduce("", String::concat);
		assertTrue(text.contains("actual model input with [REDACTED]"));
		assertTrue(text.contains("actual description"));
		assertTrue(text.contains("exact-build"));
		assertFalse(text.contains("known-secret"));
		assertFalse(text.contains(PNG), "Readable pages show pixels instead of base64 walls");
		assertEquals(List.of("data:image/png;base64," + PNG, "data:image/png;base64," + PNG),
			pages.stream().map(DiagnosticEvidence.Page::imageDataUrl).filter(java.util.Objects::nonNull).toList());
		assertTrue(files.get("report.jsonl").contains(PNG), "The exact file remains available alongside readable pages");
	}

	@Test void makesAllLongTextReachableAndNeverLoadsExternalImageUrls() {
		String longText = "x".repeat(40000);
		var pages = DiagnosticEvidence.pages(Map.of("summary.txt", longText, "report.jsonl", "{\"image\":\"https://private.example/image.png\"}\n"));
		assertEquals(longText, pages.stream().filter(p -> p.title().startsWith("summary.txt")).map(DiagnosticEvidence.Page::text).reduce("", String::concat));
		assertTrue(pages.stream().allMatch(p -> p.imageDataUrl() == null));
		assertTrue(pages.getLast().text().contains("https://private.example/image.png"));
	}
}
