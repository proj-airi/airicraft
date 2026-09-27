# Planner JSON argument repair

Planner tool arguments pass through one shared schema-aware repair before reference
resolution and the existing tool validator. Native OpenAI-compatible calls, Codex
adapter calls, and structured calls through `PlannerToolCatalog` use this boundary.
It applies to every tool's declared argument structure; chat text and the provider
response envelope are not rewritten.

An object or array encoded as an extra JSON string can be decoded once at each
schema-declared structural position. The repair walks object properties, array
items, and map values with an `additionalProperties` schema. It also supports
unambiguous `oneOf`/`anyOf` alternatives and declared type arrays. For example:

```json
{"position":"{\"x\":-50,\"y\":66,\"z\":-102}"}
```

becomes:

```json
{"position":{"x":-50,"y":66,"z":-102}}
```

The entire arguments object may also have one extra string layer. This is separate
from the normal JSON string transport of native `function.arguments` and Codex
`argumentsJson`.

## Boundaries and evidence

- A permitted string alternative wins, including `"current"` and unrestricted text.
  Notes, say text, and other string fields retain their original escapes and values.
- Decode candidates must be strict JSON objects or arrays. Malformed JSON, scalar
  strings, and repeated encoding at the same position are not repaired. There is no
  global backslash removal, numeric coercion, missing-field insertion, or value guessing.
- Multiple object/array schema alternatives of the same type are left unchanged;
  the repair does not choose a branch using guessed semantics. Unsupported schema
  constructs are not interpreted as permission to decode.
- The normal tool validator still rejects missing fields, wrong values, and invalid
  combinations. Repair grants no tool visibility or execution authority.
- Raw provider responses and native tool calls remain unchanged. Parsed tool calls
  carry `repairedArgumentPaths`, a list of JSON Pointers, so flight records expose
  both the original and interpreted arguments. The empty pointer `""` denotes the
  whole arguments value. Calls requiring no repair carry an empty list.

The repair is a bounded transport normalization, not strict generation enforcement
or a replacement JSON Schema validator. It does not change retry limits or the
planner's degraded-mode failure counter.

## Verification

The nine recorded `remember_place` failures from the 2026-09-18 open-ended Easy
playtest all pass through the parser after repairing only `/position`; the original
raw tool calls compare equal before and after parsing. Regression coverage includes
nested structures, arrays, legitimate text and string alternatives, malformed input,
ambiguous schemas, validation after repair, repair evidence, and both native and
Codex response paths. This is offline replay and test evidence, not a new live playtest.
