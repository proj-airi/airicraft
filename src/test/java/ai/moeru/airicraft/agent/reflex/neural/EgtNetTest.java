package ai.moeru.airicraft.agent.reflex.neural;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EgtNetTest {
	private static final int DE = 4;
	private static final int DH = 6;

	private static JsonArray matrix(Random rng, int rows, int cols, double scale) {
		JsonArray m = new JsonArray();
		for (int r = 0; r < rows; r++) {
			JsonArray row = new JsonArray();
			for (int c = 0; c < cols; c++) {
				row.add((rng.nextDouble() * 2 - 1) * scale);
			}
			m.add(row);
		}
		return m;
	}

	private static JsonObject layer(Random rng, int in, int out) {
		JsonObject l = new JsonObject();
		JsonArray shape = new JsonArray();
		shape.add(in);
		shape.add(out);
		l.add("shape", shape);
		l.add("w", matrix(rng, out, in, 0.5));
		JsonArray b = new JsonArray();
		for (int i = 0; i < out; i++) {
			b.add((rng.nextDouble() * 2 - 1) * 0.1);
		}
		l.add("b", b);
		return l;
	}

	private static JsonObject spec() {
		Random rng = new Random(42);
		JsonObject s = new JsonObject();
		s.addProperty("layout", "egt");
		JsonArray vocab = new JsonArray();
		vocab.add("zombie");
		vocab.add("skeleton");
		vocab.add("other");
		s.add("typeVocab", vocab);
		s.add("typeEmb", matrix(rng, 3, EgtNet.ETE, 0.5));
		JsonArray ent = new JsonArray();
		ent.add(layer(rng, EgtNet.EEF + EgtNet.ETE, DE));
		ent.add(layer(rng, DE, DE));
		s.add("ent", ent);
		JsonArray glob = new JsonArray();
		glob.add(layer(rng, EgtNet.EGE, DE));
		glob.add(layer(rng, DE, DE));
		s.add("glob", glob);
		s.add("attq", matrix(rng, DE, DE, 0.5));
		JsonObject gru = new JsonObject();
		gru.add("wih", matrix(rng, 3 * DH, 3 * DE, 0.3));
		gru.add("whh", matrix(rng, 3 * DH, DH, 0.3));
		JsonArray bih = new JsonArray();
		JsonArray bhh = new JsonArray();
		for (int i = 0; i < 3 * DH; i++) {
			bih.add(0.0);
			bhh.add(0.0);
		}
		gru.add("bih", bih);
		gru.add("bhh", bhh);
		s.add("gru", gru);
		JsonArray ptr = new JsonArray();
		ptr.add(layer(rng, DH + DE, 16));
		ptr.add(layer(rng, 16, 1));
		s.add("ptr", ptr);
		JsonArray heads = new JsonArray();
		heads.add(layer(rng, DH, 2));
		heads.add(layer(rng, DH, 4));
		heads.add(layer(rng, DH, 1));
		s.add("heads", heads);
		return s;
	}

	private static double[][] entityFeats(int n) {
		double[][] ef = new double[EgtNet.EK][EgtNet.EEF];
		for (int k = 0; k < n; k++) {
			ef[k][0] = 0.1 + 0.1 * k;
			ef[k][6] = 0.3;
			ef[k][7] = 0.8;
		}
		return ef;
	}

	@Test void loadsSpecAndReportsReady() {
		EgtNet net = new EgtNet();
		assertFalse(net.ready());
		assertTrue(net.configure(spec()));
		assertTrue(net.ready());
		assertEquals(DH, net.hiddenSize());
	}

	@Test void rejectsWrongLayout() {
		EgtNet net = new EgtNet();
		JsonObject bad = new JsonObject();
		bad.addProperty("layout", "v2");
		assertFalse(net.configure(bad));
		assertFalse(net.ready());
	}

	@Test void decideProducesContractOutputs() {
		EgtNet net = new EgtNet();
		net.configure(spec());
		int[] tid = {0, 1, 2, 2, 2, 2, 2, 2};
		EgtNet.Decision d = net.decide(entityFeats(3), tid, new double[EgtNet.EGE], 3);
		assertTrue(Double.isFinite(d.mvX()) && Double.isFinite(d.mvZ()));
		assertTrue(d.targetSlot() >= 0 && d.targetSlot() < 3);
	}

	@Test void gruMemoryAdvancesAcrossTicks() {
		EgtNet net = new EgtNet();
		net.configure(spec());
		int[] tid = {0, 1, 2, 2, 2, 2, 2, 2};
		double[][] ef = entityFeats(3);
		double[] g = new double[EgtNet.EGE];
		EgtNet.Decision first = net.decide(ef, tid, g, 3);
		EgtNet.Decision second = net.decide(ef, tid, g, 3);
		assertNotEquals(first.raw()[0] + "|" + first.raw()[2], second.raw()[0] + "|" + second.raw()[2]);
		net.reset();
		EgtNet.Decision afterReset = net.decide(ef, tid, g, 3);
		assertEquals(first.raw()[0], afterReset.raw()[0], 1e-9);
		assertEquals(first.raw()[2], afterReset.raw()[2], 1e-9);
	}

	@Test void typeIdFallsBackToOther() {
		EgtNet net = new EgtNet();
		net.configure(spec());
		assertEquals(0, net.typeId("zombie"));
		assertEquals(2, net.typeId("bogged"));
	}
}
