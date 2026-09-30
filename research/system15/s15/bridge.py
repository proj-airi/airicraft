"""Read-mostly client for the Airicraft localhost bridge (see ModBridgeServer).

Discovery follows the CLI: `airicraft.bridgeStateFile` is Java-only, so here an explicit path, then
AIRICRAFT_BRIDGE_STATE_FILE, then ~/.airicraft/bridge-state.json. The state file holds {port, token, ...}.
The only mutating call used by the harness is the existing debug chat injection (E0/E4 scripted players).
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def state_file_path(explicit: str | None = None) -> Path:
    if explicit:
        return Path(explicit)
    env = os.environ.get("AIRICRAFT_BRIDGE_STATE_FILE")
    if env:
        return Path(env)
    return Path.home() / ".airicraft" / "bridge-state.json"


class BridgeError(RuntimeError):
    pass


class Bridge:
    def __init__(self, state_file: str | None = None, timeout_s: float = 5.0):
        path = state_file_path(state_file)
        try:
            state = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise BridgeError(f"cannot read bridge state {path}: {error}") from error
        self.base = f"http://127.0.0.1:{int(state['port'])}"
        self.token = state["token"]
        self.timeout_s = timeout_s
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def _call(self, method: str, path: str, params: dict | None = None, body: dict | None = None) -> dict:
        url = self.base + path + ("?" + urllib.parse.urlencode(params) if params else "")
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": f"Bearer {self.token}", "Content-Type": "application/json"})
        try:
            with self._opener.open(request, timeout=self.timeout_s) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")
            raise BridgeError(f"{method} {path} -> {error.code}: {detail}") from error
        except (urllib.error.URLError, TimeoutError) as error:
            raise BridgeError(f"{method} {path} failed: {error}") from error

    def status(self) -> dict:
        return self._call("GET", "/v1/status")

    def events_since(self, seq: int | None) -> dict:
        return self._call("GET", "/v1/agent/events/recent", {"since": seq} if seq is not None else None)

    def context(self) -> dict:
        return self._call("GET", "/v1/agent/context")

    def goals(self) -> dict:
        return self._call("GET", "/v1/agent/goals")

    def llm_calls_since(self, seq: int | None) -> dict:
        return self._call("GET", "/v1/agent/debug/llm-calls", {"since": seq} if seq is not None else None)

    def latest_ids(self) -> tuple[int | None, int | None]:
        """(latest event seqNo, latest LLM record sequenceId) without transferring any records.

        Every route runs on the Minecraft client thread; asking for records after a huge cursor returns none, while
        the unbounded first query would serialize every retained record (LLM records carry full request bodies).
        """
        far = 2 ** 62
        return self.events_since(far).get("latestSeqNo"), self.llm_calls_since(far).get("latestSequenceId")

    def inject_chat(self, message: str, sender: str | None = None) -> dict:
        body = {"message": message}
        if sender:
            body["senderName"] = sender
        return self._call("POST", "/v1/agent/debug/chat", body=body)
