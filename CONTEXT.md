# Airicraft Evaluation

Airicraft evaluation measures agent behavior in isolated Minecraft scenarios. The evaluation harness collects scenario evidence and optional supporting evidence.

## Language

**Evaluation run**:
A set of one or more isolated scenario executions started by the evaluation harness.
_Avoid_: Batch, evaluation batch

**Scenario outcome**:
The evaluator decision about one scenario, independent of recorder success.
_Avoid_: Run result, recorder result

**Harness outcome**:
The orchestration decision for one scenario, including required supporting evidence.
_Avoid_: Scenario result

**Integrated-server capture**:
A recording of one evaluation player's connection from the singleplayer integrated server.
_Avoid_: Client-side recording, trajectory recording

**Recorder Play**:
A completed, replayable recorder artifact for one player connection. It is supporting evidence for a scenario outcome.
_Avoid_: Recorded result, trajectory

**Recording profile**:
A versioned recorder runtime supplied as one self-contained unit for an evaluation run.
_Avoid_: Recorder JAR, extra mods directory

**Recorder-enabled run**:
A run that requires one supplied recording profile and one completed Recorder Play for each executed scenario.
_Avoid_: Optional recording

**Recorder-disabled run**:
An evaluation run that explicitly does not collect a Recorder Play.
_Avoid_: Missing recording

## Planner attention

**Sensor**: Samples game facts and owns sampling cadence and throttling. _Avoid_: Observer (for the new interface).

**Candidate**: An honestly sensed fact awaiting salience selection. _Avoid_: Percept before selection.

**Percept**: A candidate selected as worth noticing, with its evidence and any inference identified. _Avoid_: Signal.

**Agent event**: An identified fact published to the bounded event log. _Avoid_: Wake request.

**Event log**: The bounded record of agent events, independent of wake decisions. _Avoid_: Semantic buffer.

**Attention policy**: Decides whether, when, and with what urgency events wake the planner. _Avoid_: Routing profile, event policy.

**Constitution**: Fixed Java protections rules cannot override. _Avoid_: Heuristic rule.

**Rule module**: A sandboxed GraalJS step mapping input and state to decisions and next state. _Avoid_: Unbounded script.

**Wake**: A request for a planner decision referencing evidence. _Avoid_: Trigger, wakeup.

**Wake batch**: Wakes delivered together at one decision boundary. _Avoid_: Trigger batch.

**Urgency**: The ordered importance of a wake, independent of its delivery mode. _Avoid_: Priority.

**Delivery**: How a wake is scheduled, delayed, or used to preempt a decision. _Avoid_: Urgency.
