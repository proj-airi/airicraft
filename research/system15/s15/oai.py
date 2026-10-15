"""Minimal OpenAI-compatible chat client (stdlib only).

Works with vLLM, SGLang, NVIDIA NIM, Inception, and the provider Airicraft already uses. The standard
HTTPS_PROXY/NO_PROXY environment variables apply; localhost servers bypass proxies.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass


@dataclass
class ChatResult:
    text: str
    latency_ms: float
    usage: dict
    logprobs: list[dict] | None
    raw: dict


class OpenAICompatClient:
    def __init__(self, base_url: str, model: str, api_key: str | None = None, api_key_env: str = "OPENAI_API_KEY",
                 timeout_s: float = 60.0, retries: int = 2):
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.api_key = api_key if api_key is not None else os.environ.get(api_key_env, "")
        self.timeout_s = timeout_s
        self.retries = retries
        local = any(host in self.base_url for host in ("://localhost", "://127.0.0.1", "://0.0.0.0"))
        handlers = [urllib.request.ProxyHandler({})] if local else []
        self._opener = urllib.request.build_opener(*handlers)

    def chat(self, messages: list[dict], temperature: float = 0.0, max_tokens: int = 512,
             json_mode: bool = False, logprobs: bool = False, extra: dict | None = None) -> ChatResult:
        body: dict = {"model": self.model, "messages": messages, "temperature": temperature, "max_tokens": max_tokens}
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        if logprobs:
            body["logprobs"] = True
            body["top_logprobs"] = 1
        if extra:
            body.update(extra)
        data = json.dumps(body).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        last_error: Exception | None = None
        for attempt in range(self.retries + 1):
            request = urllib.request.Request(f"{self.base_url}/chat/completions", data=data, headers=headers,
                                             method="POST")
            started = time.perf_counter()
            try:
                with self._opener.open(request, timeout=self.timeout_s) as response:
                    raw = json.loads(response.read().decode("utf-8"))
                latency_ms = (time.perf_counter() - started) * 1000.0
                choice = (raw.get("choices") or [{}])[0]
                message = choice.get("message") or {}
                content = message.get("content") or ""
                lp = (choice.get("logprobs") or {}).get("content") if isinstance(choice.get("logprobs"), dict) else None
                return ChatResult(content, latency_ms, raw.get("usage") or {}, lp, raw)
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
                last_error = error
                status = getattr(error, "code", None)
                if status is not None and status < 500 and status != 429:
                    break
                time.sleep(min(2 ** attempt, 8))
        raise RuntimeError(f"chat request failed: {last_error}")
