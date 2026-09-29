COMPACTION TASK:
Ignore the normal planner response format for this response.
Return strict JSON with:
{
  "time_anchor": string,
  "session_state": string,
  "active_goal": string,
  "active_commitments": string[],
  "durable_facts": string[],
  "relevant_people": string[],
  "open_loops": string[],
  "recent_timeline": string[],
  "forgettable_noise": string[]
}
Create a compact handoff checkpoint for continuing this exact thread later.
Preserve user constraints, operator instructions, active goals, open loops, important names, and current world/session state.
Prefer compressing assistant chatter, tool chatter, and stale notices.
Do not rewrite or quote the whole transcript.
Do not keep stale relative-time phrases such as "4 seconds ago"; convert them into stable facts or timeline notes.
Keep each list item short and concrete.
Summarize only the conversation above. Newer messages that arrive while you work are kept as they are after your checkpoint, so do not guess at them.
