package ai.moeru.airicraft.agent.reflex.neural;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side port of the sim's "egt" neural policy (entity-set encoder +
 * single-head cross-attention + GRU memory + move/flag/pointer heads).
 * Mirrors sim NetPolicy.forwardEgt exactly — the deployed weights are the same
 * spec JSON produced by policy_egt.spec_of_egt. Pure Java matmul, no
 * dependencies, ~27k params: a forward pass is tens of microseconds.
 */
public final class EgtNet {
	public static final int EK = 8;   // hostile slots fed to the entity encoder
	public static final int EEF = 18; // per-entity numeric features
	public static final int EGE = 16; // global features
	public static final int ETE = 8;  // type embedding dim

	private List<double[][]> entW; private List<double[]> entB;
	private List<double[][]> globW; private List<double[]> globB;
	private double[][] attq;
	private double[][] wih; private double[][] whh;
	private double[] bih; private double[] bhh;
	private List<double[][]> ptrW; private List<double[]> ptrB;
	private List<double[][]> headW; private List<double[]> headB;
	private double[][] typeEmb;
	private Map<String, Integer> typeMap;
	private int de;
	private int dh;
	private double[] hidden;
	private boolean ready;

	public boolean ready() {
		return ready;
	}

	public int hiddenSize() {
		return dh;
	}

	public int typeId(String baseType) {
		return typeMap.getOrDefault(baseType, typeEmb.length - 1);
	}

	public void reset() {
		if (hidden != null) {
			java.util.Arrays.fill(hidden, 0.0);
		}
	}

	public boolean configure(JsonObject spec) {
		if (spec == null || !spec.has("layout")
				|| !"egt".equals(spec.get("layout").getAsString())) {
			return false;
		}
		typeEmb = readMatrix(spec.getAsJsonArray("typeEmb"));
		typeMap = new HashMap<>();
		JsonArray vocab = spec.getAsJsonArray("typeVocab");
		for (int i = 0; i < vocab.size(); i++) {
			typeMap.put(vocab.get(i).getAsString(), i);
		}
		entW = readLayers(spec.getAsJsonArray("ent"));
		entB = readBiases(spec.getAsJsonArray("ent"));
		globW = readLayers(spec.getAsJsonArray("glob"));
		globB = readBiases(spec.getAsJsonArray("glob"));
		attq = readMatrix(spec.getAsJsonArray("attq"));
		JsonObject gru = spec.getAsJsonObject("gru");
		wih = readMatrix(gru.getAsJsonArray("wih"));
		whh = readMatrix(gru.getAsJsonArray("whh"));
		bih = readVec(gru.getAsJsonArray("bih"));
		bhh = readVec(gru.getAsJsonArray("bhh"));
		ptrW = readLayers(spec.getAsJsonArray("ptr"));
		ptrB = readBiases(spec.getAsJsonArray("ptr"));
		headW = readLayers(spec.getAsJsonArray("heads"));
		headB = readBiases(spec.getAsJsonArray("heads"));
		de = globW.get(0).length;
		dh = bih.length / 3;
		hidden = new double[dh];
		ready = true;
		return true;
	}

	/**
	 * One-tick decision from an encoded frame. Advances the GRU hidden state.
	 * ef[k] = EEF feats of the k-th hostile (sorted nearest-first), tid[k] =
	 * type-embedding id, g = globals, n = live hostile count.
	 */
	public record Decision(double mvX, double mvZ, int targetSlot, boolean attack,
			boolean shield, boolean sprint, boolean jump, double[] raw) {}

	public Decision decide(double[][] ef, int[] tid, double[] g, int n) {
		double[][] enc = new double[EK][de];
		double[] in = new double[EEF + ETE];
		for (int k = 0; k < EK; k++) {
			System.arraycopy(ef[k], 0, in, 0, EEF);
			System.arraycopy(typeEmb[tid[k]], 0, in, EEF, ETE);
			double[] a = matvec(entW.get(0), entB.get(0), in);
			tanhInPlace(a);
			a = matvec(entW.get(1), entB.get(1), a);
			tanhInPlace(a);
			enc[k] = a;
		}
		double[] gv = matvec(globW.get(0), globB.get(0), g);
		tanhInPlace(gv);
		gv = matvec(globW.get(1), globB.get(1), gv);
		tanhInPlace(gv);
		double[] q = matvec(attq, null, gv);
		double[] wts = new double[n];
		double mx = Double.NEGATIVE_INFINITY;
		for (int k = 0; k < n; k++) {
			double s = 0;
			for (int i = 0; i < de; i++) {
				s += enc[k][i] * q[i];
			}
			wts[k] = s / Math.sqrt(de);
			if (wts[k] > mx) {
				mx = wts[k];
			}
		}
		double wsum = 0;
		for (int k = 0; k < n; k++) {
			wts[k] = Math.exp(wts[k] - mx);
			wsum += wts[k];
		}
		double[] attn = new double[de];
		double[] meanE = new double[de];
		for (int k = 0; k < n; k++) {
			double w = wsum > 0 ? wts[k] / wsum : 0;
			for (int i = 0; i < de; i++) {
				attn[i] += w * enc[k][i];
				meanE[i] += enc[k][i] / n;
			}
		}
		double[] fused = new double[3 * de];
		System.arraycopy(gv, 0, fused, 0, de);
		System.arraycopy(attn, 0, fused, de, de);
		System.arraycopy(meanE, 0, fused, 2 * de, de);

		double[] h = hidden;
		double[] hnew = new double[dh];
		for (int i = 0; i < dh; i++) {
			double xr = bih[i], xz = bih[dh + i], xn = bih[2 * dh + i];
			double hr = bhh[i], hz = bhh[dh + i], hh = bhh[2 * dh + i];
			for (int c = 0; c < fused.length; c++) {
				xr += wih[i][c] * fused[c];
				xz += wih[dh + i][c] * fused[c];
				xn += wih[2 * dh + i][c] * fused[c];
			}
			for (int c = 0; c < dh; c++) {
				hr += whh[i][c] * h[c];
				hz += whh[dh + i][c] * h[c];
				hh += whh[2 * dh + i][c] * h[c];
			}
			double r = 1.0 / (1.0 + Math.exp(-(xr + hr)));
			double z = 1.0 / (1.0 + Math.exp(-(xz + hz)));
			double ng = Math.tanh(xn + r * hh);
			hnew[i] = (1.0 - z) * ng + z * h[i];
		}
		System.arraycopy(hnew, 0, hidden, 0, dh);

		double[] mv = matvec(headW.get(0), headB.get(0), hnew);
		double[] fl = matvec(headW.get(1), headB.get(1), hnew);
		int ti = -1;
		double best = Double.NEGATIVE_INFINITY;
		for (int k = 0; k < n; k++) {
			double[] pin = new double[dh + de];
			System.arraycopy(hnew, 0, pin, 0, dh);
			System.arraycopy(enc[k], 0, pin, dh, de);
			double[] pa = matvec(ptrW.get(0), ptrB.get(0), pin);
			tanhInPlace(pa);
			double s = matvec(ptrW.get(1), ptrB.get(1), pa)[0];
			if (s > best) {
				best = s;
				ti = k;
			}
		}
		double[] raw = new double[2 + 4];
		raw[0] = mv[0];
		raw[1] = mv[1];
		System.arraycopy(fl, 0, raw, 2, 4);
		return new Decision(mv[0], mv[1], ti, fl[0] > 0, fl[1] > 0, fl[2] > 0,
				fl[3] > 0, raw);
	}

	private static double[] matvec(double[][] wm, double[] bv, double[] x) {
		double[] o = new double[wm.length];
		for (int r = 0; r < wm.length; r++) {
			double s = bv == null ? 0.0 : bv[r];
			double[] row = wm[r];
			for (int c = 0; c < row.length; c++) {
				s += row[c] * x[c];
			}
			o[r] = s;
		}
		return o;
	}

	private static void tanhInPlace(double[] a) {
		for (int i = 0; i < a.length; i++) {
			a[i] = Math.tanh(a[i]);
		}
	}

	private static double[][] readMatrix(JsonArray arr) {
		double[][] m = new double[arr.size()][];
		for (int i = 0; i < arr.size(); i++) {
			JsonArray row = arr.get(i).getAsJsonArray();
			m[i] = new double[row.size()];
			for (int j = 0; j < row.size(); j++) {
				m[i][j] = row.get(j).getAsDouble();
			}
		}
		return m;
	}

	private static double[] readVec(JsonArray arr) {
		double[] v = new double[arr.size()];
		for (int i = 0; i < arr.size(); i++) {
			v[i] = arr.get(i).getAsDouble();
		}
		return v;
	}

	private static List<double[][]> readLayers(JsonArray arr) {
		List<double[][]> out = new ArrayList<>();
		for (JsonElement le : arr) {
			JsonObject l = le.getAsJsonObject();
			JsonArray wArr = l.getAsJsonArray("w");
			JsonArray shape = l.getAsJsonArray("shape");
			int in = shape.get(0).getAsInt();
			int out_ = shape.get(1).getAsInt();
			double[][] wm = new double[out_][in];
			for (int o = 0; o < out_; o++) {
				JsonArray row = wArr.get(o).getAsJsonArray();
				for (int i = 0; i < in; i++) {
					wm[o][i] = row.get(i).getAsDouble();
				}
			}
			out.add(wm);
		}
		return out;
	}

	private static List<double[]> readBiases(JsonArray arr) {
		List<double[]> out = new ArrayList<>();
		for (JsonElement le : arr) {
			JsonObject l = le.getAsJsonObject();
			JsonArray bArr = l.getAsJsonArray("b");
			JsonArray shape = l.getAsJsonArray("shape");
			double[] bv = new double[shape.get(1).getAsInt()];
			for (int o = 0; o < bv.length; o++) {
				bv[o] = bArr.get(o).getAsDouble();
			}
			out.add(bv);
		}
		return out;
	}
}
