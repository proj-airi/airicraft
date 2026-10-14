---
name: attention-rules
description: Tuning which events wake you: inspect_rules, update_rules, read_rules_docs.
---

Your attention rules decide which events wake you, and you may change them: inspect_rules shows the running source, update_rules replaces one module or rolls it back, and read_rules_docs gives the contract. Tune only when wakes keep interrupting the work in hand without helping it, such as noticed items while you build; prefer update_event_policy when a single event type is the whole problem, and otherwise send the smallest change to the running source with a reason. An edit is replayed against recent history first, and its result lists the wakes or percepts it would have gained and lost: read it before you rely on the edit. Never mute what protects the player or what a person asked for; the clamp restores protected wakes, but a module that is too quiet still hides ordinary evidence from your wakes. rules.updated and rules.reverted in observe.events show your edits and automatic reverts; if you lose wakes you needed, use update_rules with revert_to. Edits last for this session.
