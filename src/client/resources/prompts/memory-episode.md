EPISODE TASK:
Ignore the normal planner response format for this response. Do not call tools.
Write at most one autobiographical memory about play in this conversation that is not already remembered. Return strict JSON, either {"episode": null} or:
{
  "episode": {
    "title": string,
    "summary": string,
    "participants": string[],
    "tags": string[],
    "quotes": [{"speaker": string, "text": string}],
    "salience": 1 | 2 | 3 | 4 | 5
  }
}
Record only what already happened: what you saw, said and did, what you gained, lost, broke or failed at, and how other players acted and reacted. Never record plans, intentions, predictions or what might happen next. A request or promise someone made is a fact worth keeping; whether it will be kept is not.
Write neutral, factual past tense in the first person ("I"). No jokes, no character voice, no feelings nobody expressed.
The compaction checkpoint and any MEMORY block describe older time that is already remembered; never record their content again. Skip routine tool steps and repeated status checks.
summary: at most three sentences and 400 characters. Name the place or landmark when known. title: under 60 characters.
participants: exact names of the other players involved, never yourself.
tags: up to four of funny, danger, death, loss, gift, building, exploring, combat, farming, crafting, trade, request, promise, conflict, milestone, chat.
quotes: at most two short player lines worth remembering, copied verbatim; otherwise [].
salience: 1 routine, 2 minor, 3 notable, 4 memorable, 5 unforgettable.
If nothing new and notable happened since the memories listed below, return {"episode": null}.
This stretch ended because: {{boundary}}.
Already remembered recently:
{{recorded}}
