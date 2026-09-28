package ai.moeru.airicraft.policy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in runtime experiment for the proposed attention rule boundary. */
@EnabledIfEnvironmentVariable(named = "AIRICRAFT_RULE_SPIKE", matches = "1")
class RuleEngineSpikeTest {
 private static final int STEPS = 2_000;
 private static final int MAX_STATE = 16 * 1024;

 @Test void measuresSandboxedRuleStep() throws Exception {
  long heapBefore = heapAfterGc();
  String input = input(200, 20, 50);
  String initialState = "{}";
  long buildStart = System.nanoTime();
  try (Runner runner = new Runner(200_000)) {
   long buildNs = System.nanoTime() - buildStart;
   long heapBuilt = heapAfterGc();
   long coldStart = System.nanoTime();
   String first = runner.run(input, initialState);
   long coldNs = System.nanoTime() - coldStart;
   JsonObject firstObject = JsonParser.parseString(first).getAsJsonObject();
   assertEquals(20, firstObject.getAsJsonArray("decisions").size());
   assertTrue(firstObject.getAsJsonArray("percepts").size() > 0);
   boolean replayEqual = first.equals(runner.run(input, initialState));
   assertTrue(replayEqual, "identical input and state must replay exactly");
   JsonObject clock = firstObject.getAsJsonObject("probe");
   assertEquals(10_000, clock.get("now").getAsLong());
   assertEquals(10_000, clock.get("date").getAsLong());
   assertEquals(clock.get("random").getAsDouble(), JsonParser.parseString(runner.run(input, initialState)).getAsJsonObject().getAsJsonObject("probe").get("random").getAsDouble());
   assertTrue(hasReason(firstObject, "ownership_gate"));
   assertTrue(hasReason(JsonParser.parseString(runner.run(input(205, 20, 50), initialState)).getAsJsonObject(), "blocked_goal_gate"));

   String state = state(first);
   String output = first;
   int tick = 201;
   boolean sawWindow = false, sawCooldown = false, sawNoticeCap = false;
   long[] earlyHandoff = new long[200];
   for (int i = 0; i < earlyHandoff.length; i++, tick++) {
    long start = System.nanoTime();
    output = runner.run(input(tick, 20, 50), state);
    earlyHandoff[i] = System.nanoTime() - start;
    state = state(output);
    JsonObject observed = JsonParser.parseString(output).getAsJsonObject();
    sawWindow |= hasReason(observed, "window_cap");
    sawCooldown |= hasReason(observed, "cooldown");
    sawNoticeCap |= hasReason(observed, "notice_cap");
   }
   for (int i = 0; i < 299; i++, tick++) state = state(runner.run(input(tick, 20, 50), state));
   long[] handoff = new long[STEPS];
   for (int i = 0; i < STEPS; i++, tick++) {
    long start = System.nanoTime();
    output = runner.run(input(tick, 20, 50), state);
    handoff[i] = System.nanoTime() - start;
    state = state(output);
    assertTrue(state.getBytes(StandardCharsets.UTF_8).length <= MAX_STATE);
    JsonObject observed = JsonParser.parseString(output).getAsJsonObject();
    sawWindow |= hasReason(observed, "window_cap");
    sawCooldown |= hasReason(observed, "cooldown");
    sawNoticeCap |= hasReason(observed, "notice_cap");
   }
   assertTrue(sawWindow && sawCooldown && sawNoticeCap, "budgets must affect representative decisions");
   int stateBytes = state.getBytes(StandardCharsets.UTF_8).length;
   for (int i = 0; i < 7_500; i++, tick++) {
    output = runner.run(input(tick, 20, 50), state);
    state = state(output);
   }
   long heapAfter10k = heapAfterGc();
   long[] direct = new long[STEPS];
   try (Runner directRunner = new Runner(200_000)) {
    String directState = "{}";
    for (int i = 0; i < 500; i++) directState = state(directRunner.run(input(20_000 + i, 20, 50), directState));
    for (int i = 0; i < STEPS; i++) {
     String stepInput = input(20_500 + i, 20, 50);
     String previous = directState;
     Timed result = CompletableFuture.supplyAsync(() -> directRunner.runTimed(stepInput, previous), directRunner.worker).get(1, TimeUnit.SECONDS);
     direct[i] = result.nanos();
     directState = state(result.output());
    }
   }
   String healthy = runner.run(input(11_001, 20, 50), state);
   double throwMs = expectFailure(() -> runner.run(input(11_002, 20, 50).replace("\"tick\":11002", "\"tick\":11002,\"throwRule\":true"), "{}"), org.graalvm.polyglot.PolyglotException.class, "intentional rule failure");
   boolean throwRecovered = healthy.equals(runner.run(input(11_001, 20, 50), state));
   assertTrue(throwRecovered);
   double loopMs;
   try (Runner failing = new Runner(200_000)) {
    loopMs = expectResourceExhaustion(() -> failing.run(input(11_002, 20, 50).replace("\"tick\":11002", "\"tick\":11002,\"loopRule\":true"), "{}"));
   }
   boolean loopRecovered;
   try (Runner recovered = new Runner(200_000)) {
    loopRecovered = healthy.equals(recovered.run(input(11_001, 20, 50), state));
    assertTrue(loopRecovered);
   }
   double oversizedMs = expectFailure(() -> runner.run(input, "{\"pad\":\"" + "x".repeat(MAX_STATE) + "\"}"), IllegalArgumentException.class, "state_size_limit");
   boolean oversizedRecovered = healthy.equals(runner.run(input(11_001, 20, 50), state));
   assertTrue(oversizedRecovered);

   boolean at50k = completesAtLimit(50_000);
   boolean at200k = completesAtLimit(200_000);
   JsonObject summary = new JsonObject();
   summary.addProperty("machine", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
   summary.addProperty("jvm", System.getProperty("java.runtime.version") + " " + System.getProperty("java.vm.name"));
   summary.addProperty("contextBuildMs", ms(buildNs));
   summary.addProperty("coldFirstStepMs", ms(coldNs));
   summary.add("earlyHandoffMs", distribution(earlyHandoff));
   summary.add("directMs", distribution(direct));
   summary.add("handoffMs", distribution(handoff));
   summary.addProperty("fixedWarmupSteps", 500);
   summary.addProperty("limit50000Completes", at50k);
   summary.addProperty("limit200000Completes", at200k);
   summary.addProperty("inputBytes", input.getBytes(StandardCharsets.UTF_8).length);
   summary.addProperty("outputBytes", output.getBytes(StandardCharsets.UTF_8).length);
   summary.addProperty("stateBytes", stateBytes);
   summary.addProperty("heapBeforeBytes", heapBefore);
   summary.addProperty("heapBuiltBytes", heapBuilt);
   summary.addProperty("heapAfter10000Bytes", heapAfter10k);
   summary.addProperty("replayEqual", replayEqual);
   summary.addProperty("throwFailureMs", throwMs);
   summary.addProperty("loopFailureMs", loopMs);
   summary.addProperty("oversizedFailureMs", oversizedMs);
   summary.addProperty("throwRecovered", throwRecovered);
   summary.addProperty("loopRecoveredAfterContextReplacement", loopRecovered);
   summary.addProperty("oversizedRecovered", oversizedRecovered);
   Path result = Path.of("build/rule-spike/result.json");
   Files.createDirectories(result.getParent());
   Files.writeString(result, summary.toString() + "\n");
   System.out.println("RULE_SPIKE_RESULT " + summary);
  }
 }

 private static boolean completesAtLimit(int limit) throws Exception {
  try (Runner runner = new Runner(limit)) {
   String state = "{}";
   for (int i = 0; i < STEPS; i++) state = state(runner.run(input(i + 1, 20, 50), state));
   return true;
  } catch (RuntimeException ex) {
   Throwable cause = ex;
   while (cause.getCause() != null) cause = cause.getCause();
   if (cause instanceof org.graalvm.polyglot.PolyglotException polyglot && polyglot.isResourceExhausted()) return false;
   throw ex;
  }
 }

 private static JsonObject distribution(long[] values) {
  long[] sorted = values.clone();
  Arrays.sort(sorted);
  JsonObject out = new JsonObject();
  out.addProperty("p50", ms(sorted[(int) Math.ceil(sorted.length * .50) - 1]));
  out.addProperty("p95", ms(sorted[(int) Math.ceil(sorted.length * .95) - 1]));
  out.addProperty("p99", ms(sorted[(int) Math.ceil(sorted.length * .99) - 1]));
  out.addProperty("max", ms(sorted[sorted.length - 1]));
  return out;
 }
 private static double ms(long ns) { return ns / 1_000_000.0; }
 private static boolean hasReason(JsonObject output, String reason) {
  for (var decision : output.getAsJsonArray("decisions"))
   if (reason.equals(decision.getAsJsonObject().get("reason").getAsString())) return true;
  return false;
 }
 private static double expectFailure(Runnable action, Class<? extends Throwable> expected, String message) {
  long start = System.nanoTime();
  RuntimeException failure = assertThrows(RuntimeException.class, action::run);
  double elapsed = ms(System.nanoTime() - start);
  assertTrue(elapsed < 1_000, "failure exceeded one-second deadline: " + elapsed);
  Throwable cause = deepestCause(failure);
  assertInstanceOf(expected, cause);
  assertTrue(cause.getMessage().contains(message), cause.toString());
  return elapsed;
 }
 private static double expectResourceExhaustion(Runnable action) {
  long start = System.nanoTime();
  RuntimeException failure = assertThrows(RuntimeException.class, action::run);
  double elapsed = ms(System.nanoTime() - start);
  assertTrue(elapsed < 1_000, "statement-limit failure exceeded one-second deadline: " + elapsed);
  var cause = assertInstanceOf(org.graalvm.polyglot.PolyglotException.class, deepestCause(failure));
  assertTrue(cause.isResourceExhausted(), cause.toString());
  return elapsed;
 }
 private static Throwable deepestCause(Throwable error) {
  while (error.getCause() != null) error = error.getCause();
  return error;
 }
 private record Timed(String output, long nanos) {}
 private static String state(String output) { return JsonParser.parseString(output).getAsJsonObject().get("state").toString(); }
 private static long heapAfterGc() throws InterruptedException {
  System.gc();
  Thread.sleep(50);
  return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
 }
 private static String input(int tick, int eventCount, int candidateCount) {
  StringBuilder s = new StringBuilder(12_000);
  s.append("{\"tick\":").append(tick).append(",\"seed\":").append(42 + tick % 17)
   .append(",\"attention\":{\"actuatorOwner\":\"").append(tick % 4 == 0 ? "external" : "agent")
   .append("\",\"acceptedWork\":true,\"goal\":\"gather wood\",\"blockedGoal\":").append(tick % 5 == 0)
   .append("},\"plannerRules\":{\"world.block_changed\":\"NORMAL\"},\"events\":[");
  String[] types = {"player.chat", "player.hurt", "world.block_changed", "task.progress", "entity.nearby", "inventory.changed", "weather.changed", "goal.blocked"};
  for (int i = 0; i < eventCount; i++) {
   if (i > 0) s.append(',');
   s.append("{\"seqNo\":").append(tick * 100 + i).append(",\"type\":\"").append(types[i % types.length])
    .append("\",\"urgency\":\"NORMAL\",\"delivery\":\"NEXT_BOUNDARY\",\"payload\":{\"source\":\"sensor-").append((tick + i) % 5)
    .append("\",\"description\":\"nearby game change with bounded representative evidence\",\"x\":").append((tick + i) % 32).append("}}");
  }
  s.append("],\"candidates\":[");
  for (int i = 0; i < candidateCount; i++) {
   if (i > 0) s.append(',');
   s.append("{\"kind\":\"").append(i % 3 == 0 ? "block" : i % 3 == 1 ? "entity" : "item")
    .append("\",\"id\":\"").append((i + tick) % 7 == 0 ? "minecraft:cobblestone" : (i + tick) % 5 == 0 ? "minecraft:iron_ore" : "minecraft:oak_log")
    .append("\",\"x\":").append((i + tick) % 12).append(",\"y\":64,\"z\":").append(i / 12)
    .append(",\"description\":\"visible candidate with line of sight and distance evidence\"}");
  }
  return s.append("]}").toString();
 }

 private static final class Runner implements AutoCloseable {
  final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("rule-spike").factory());
  final Context context;
  final Value kernel;
  Runner(int limit) throws Exception {
   CompletableFuture<Object[]> ready = CompletableFuture.supplyAsync(() -> {
    Context ctx = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false")
     .allowHostAccess(HostAccess.NONE).allowHostClassLookup(name -> false).allowHostClassLoading(false)
     .allowIO(IOAccess.NONE).allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
     .allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
     .in(InputStream.nullInputStream()).out(OutputStream.nullOutputStream()).err(OutputStream.nullOutputStream())
     .resourceLimits(ResourceLimits.newBuilder().statementLimit(limit, ignored -> true).build()).build();
    try {
     Value k = ctx.eval("js", resource("kernel.js"));
     k.invokeMember("load", ctx.eval("js", resource("lib.js")), ctx.eval("js", resource("attention.js")));
     return new Object[]{ctx, k};
    } catch (Exception ex) { ctx.close(true); throw ex; }
   }, worker);
   Object[] pair = ready.get(10, TimeUnit.SECONDS);
   context = (Context) pair[0]; kernel = (Value) pair[1];
  }
  String run(String input, String state) {
   try { return CompletableFuture.supplyAsync(() -> runDirect(input, state), worker).get(1, TimeUnit.SECONDS); }
   catch (Exception ex) { throw new RuntimeException(ex); }
  }
  Timed runTimed(String input, String state) {
   long start = System.nanoTime();
   String output = runDirect(input, state);
   return new Timed(output, System.nanoTime() - start);
  }
  private String runDirect(String input, String state) {
   if (state.getBytes(StandardCharsets.UTF_8).length > MAX_STATE) throw new IllegalArgumentException("state_size_limit");
   context.resetLimits();
   String out = kernel.invokeMember("run", input, state).asString();
   String nextState = state(out);
   if (nextState.getBytes(StandardCharsets.UTF_8).length > MAX_STATE) throw new IllegalArgumentException("state_size_limit");
   return out;
  }
  @Override public void close() throws Exception {
   CompletableFuture.runAsync(() -> context.close(true), worker).get(10, TimeUnit.SECONDS);
   worker.shutdownNow();
  }
 }
 private static String resource(String name) {
  try (InputStream stream = RuleEngineSpikeTest.class.getResourceAsStream("/rules-spike/" + name)) {
   if (stream == null) throw new IllegalStateException("missing " + name);
   return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
  } catch (Exception ex) { throw new IllegalStateException(ex); }
 }
}
