"""Filler sidecar: serve any filler over HTTP (JSON), so the GPU can live on another machine.

`POST /fill` takes {"doc": StateDoc JSON, "previous": READING|null, "dirty_sections": [...], "step_budget": int|null,
"reset": bool} and returns a FillResult JSON (plus "server_ms"). `GET /health` reports the filler name. This is the
protocol the Phase 2 Java client will call. One request at a time: GPU fillers are not thread-safe.
Bind to 127.0.0.1 and reach it through an SSH tunnel; there is no authentication.
"""
from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import reading
from .backends.base import FillRequest, Filler
from .statedoc import StateDoc


def make_handler(filler: Filler):
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _send(self, status: int, body: dict) -> None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/health":
                return self._send(200, {"ok": True, "filler": filler.name, "schema": reading.SCHEMA_VERSION})
            self._send(404, {"error": "not_found"})

        def do_POST(self):
            if self.path != "/fill":
                return self._send(404, {"error": "not_found"})
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                doc = StateDoc.from_json(body["doc"])
            except (KeyError, ValueError, TypeError) as error:
                return self._send(400, {"error": "invalid_request", "message": str(error)})
            dirty = set(body.get("dirty_sections") or [])
            request = FillRequest(doc, body.get("previous"), reading.dirty_slots(dirty), dirty, body.get("step_budget"))
            with lock:
                started = time.perf_counter()
                if body.get("reset"):
                    filler.reset()
                result = filler.fill(request).to_json()
                result["server_ms"] = round((time.perf_counter() - started) * 1000.0, 2)
            self._send(200, result)

    return Handler


def serve(filler: Filler, host: str = "127.0.0.1", port: int = 9015) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((host, port), make_handler(filler))
    return server
