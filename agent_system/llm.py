"""The language model behind the LLM agents.

Every agent calls one of two methods:
  * LLM.json(...) -> a validated Pydantic object (test cases, repair plans, ...)
  * LLM.text(...) -> free text (the refactored Java files)

Every prompt and reply is written to runs/<run>/llm/ and logged in the trace.
With Ollama, JSON replies are constrained to the schema (Ollama's structured outputs),
so even a small local model returns parseable JSON.
"""
from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, TypeVar

from pydantic import BaseModel, ValidationError

from .config import Settings
from .tracing import Tracer

T = TypeVar("T", bound=BaseModel)

JSON_RETRY = (
    "\n\nYour previous reply could not be used: {error}\n"
    "Reply again with only one JSON object that follows the schema. No prose and no code fences."
)


class LLMError(RuntimeError):
    pass


class LLM:
    def __init__(self, settings: Settings, tracer: Tracer):
        self.settings = settings
        self.tracer = tracer
        self._models: dict[str, Any] = {}

    # ------------------------------------------------------------------ public
    def json(self, role: str, system: str, user: str, schema: type[T]) -> T:
        error = None
        for attempt in (1, 2):
            prompt = user if error is None else user + JSON_RETRY.format(error=error)
            raw = self._call(role, system, prompt, schema)
            try:
                return parse_model(raw, schema)
            except (ValueError, ValidationError) as exc:
                error = _short_error(exc)
                self.tracer.event(role, "warning", f"reply is not a valid {schema.__name__} (attempt {attempt})",
                                  error=error)
        raise LLMError(f"{role}: no valid {schema.__name__} after 2 attempts: {error}")

    def text(self, role: str, system: str, user: str) -> str:
        return self._call(role, system, user, None)

    # ------------------------------------------------------------------ one call
    def _call(self, role: str, system: str, user: str, schema: type[BaseModel] | None) -> str:
        model_name = self.settings.model_for(role)
        prompt_file, response_file = self._prompt_files(role, model_name, system, user)
        self.tracer.event(role, "llm_call", f"{self.settings.llm_provider}:{model_name} <- {prompt_file.name}",
                          prompt_chars=len(system) + len(user), schema=schema.__name__ if schema else None)
        t0 = time.perf_counter()
        raw = self._complete(role, model_name, system, user, schema, prompt_file, response_file)
        response_file.write_text(raw, encoding="utf-8")
        self.tracer.event(role, "llm_reply", f"{time.perf_counter() - t0:.1f}s -> {response_file.name}",
                          reply_chars=len(raw))
        return raw

    def _prompt_files(self, role: str, model_name: str, system: str, user: str) -> tuple[Path, Path]:
        if self.settings.llm_provider == "manual":
            # A resumed node runs again from its start; reuse the prompt file it wrote before pausing.
            for existing in sorted(self.tracer.llm_dir.glob(f"*-{role}.prompt.md"), reverse=True):
                body = existing.read_text(encoding="utf-8")
                if body.endswith(user.strip() + "\n") and system.strip() in body:
                    return existing, existing.with_name(existing.name.replace(".prompt.md", ".response.md"))
        prompt_file, response_file = self.tracer.next_llm_files(role)
        Tracer.write_prompt(prompt_file, role, model_name, system, user)
        return prompt_file, response_file

    def _complete(self, role, model_name, system, user, schema, prompt_file, response_file) -> str:
        if self.settings.llm_provider == "manual":
            from langgraph.types import interrupt

            reply = interrupt({
                "kind": "llm",
                "role": role,
                "prompt_file": str(prompt_file),
                "response_file": str(response_file),
                "format": "json" if schema else "text",
            })
            return str(reply)
        chat = self._chat_model(model_name)
        if self.settings.llm_provider == "ollama" and schema is not None:
            chat = chat.bind(format=schema.model_json_schema())
        message = chat.invoke([("system", system), ("human", user)])
        return message_text(message)

    def _chat_model(self, model_name: str):
        if model_name in self._models:
            return self._models[model_name]
        s = self.settings
        provider = s.llm_provider
        if provider == "ollama":
            from langchain_ollama import ChatOllama

            model = ChatOllama(model=model_name, base_url=s.ollama_base_url, temperature=s.temperature,
                               num_ctx=s.num_ctx, num_predict=s.num_predict,
                               client_kwargs={"timeout": s.llm_timeout})
        elif provider == "openai":
            from langchain_openai import ChatOpenAI

            model = ChatOpenAI(model=model_name, temperature=s.temperature, timeout=s.llm_timeout)
        elif provider == "anthropic":
            from langchain_anthropic import ChatAnthropic

            model = ChatAnthropic(model=model_name, temperature=s.temperature, max_tokens=s.num_predict,
                                  timeout=s.llm_timeout)
        elif provider in {"google", "gemini"}:
            from langchain_google_genai import ChatGoogleGenerativeAI

            model = ChatGoogleGenerativeAI(model=model_name, temperature=s.temperature)
        else:
            raise LLMError(f"Unknown LLM_PROVIDER {provider!r}: use ollama, openai, anthropic, google or manual")
        self._models[model_name] = model
        return model


# ---------------------------------------------------------------------- helpers
def message_text(message: Any) -> str:
    content = getattr(message, "content", message)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, str):
                parts.append(block)
            elif isinstance(block, dict) and block.get("type") == "text":
                parts.append(block.get("text", ""))
        return "".join(parts)
    return str(content)


def extract_json(text: str) -> Any:
    """Parse the JSON object in a model reply (tolerates code fences and text around it)."""
    t = text.strip()
    fence = re.search(r"```(?:json)?\s*(.*?)```", t, re.S)
    if fence and not t.startswith("{"):
        t = fence.group(1).strip()
    if not t.startswith(("{", "[")):
        start, end = t.find("{"), t.rfind("}")
        if start < 0 or end <= start:
            raise ValueError("no JSON object found in the reply")
        t = t[start:end + 1]
    try:
        return json.loads(t, strict=False)
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid JSON: {exc}") from exc


_CASE_KEYS = ("id", "technique", "method", "condition", "input", "expected", "junit")


def _normalize(data: Any, schema: type[BaseModel]) -> Any:
    """Small models sometimes wrap the object, return a bare list, or leave out a text field."""
    fields = schema.model_fields
    if isinstance(data, list) and "test_cases" in fields:
        data = {"test_cases": data}
    if isinstance(data, dict) and len(data) == 1:
        (only_key, only_value), = data.items()
        if only_key not in fields and isinstance(only_value, dict):
            data = only_value
    if isinstance(data, dict):
        for key in ("test_cases", "add", "modify"):
            if isinstance(data.get(key), list):
                data[key] = [_normalize_case(c) for c in data[key] if isinstance(c, dict)]
        if "analysis" in fields:
            data.setdefault("analysis", "")
        if "summary" in fields:
            data.setdefault("summary", "")
        if "diagnosis" in fields:
            data.setdefault("diagnosis", "")
    return data


def _normalize_case(case: dict) -> dict:
    case = dict(case)
    for key in _CASE_KEYS:
        value = case.get(key, "")
        case[key] = value if isinstance(value, str) else json.dumps(value)
    return case


def parse_model(raw: str, schema: type[T]) -> T:
    return schema.model_validate(_normalize(extract_json(raw), schema))


def _short_error(exc: Exception) -> str:
    text = str(exc).replace("\n", " ")
    return text[:800]


def check_ollama(settings: Settings) -> str | None:
    """Return a problem description, or None if Ollama is up and the models are pulled."""
    if settings.llm_provider != "ollama":
        return None
    url = settings.ollama_base_url.rstrip("/") + "/api/tags"
    try:
        with urllib.request.urlopen(url, timeout=5) as resp:
            tags = json.load(resp)
    except (urllib.error.URLError, OSError, ValueError):
        return (f"Ollama is not reachable at {settings.ollama_base_url}. "
                "Start it (open the Ollama app, or run `ollama serve`) and try again.")
    have = {m.get("name", "") for m in tags.get("models", [])}
    have |= {name.split(":")[0] for name in have if name.endswith(":latest")}
    missing = [m for m in {settings.llm_model, settings.refactor_model} if m and m not in have]
    if missing:
        return "Model(s) not pulled yet: " + ", ".join(missing) + ". Run: " + "; ".join(
            f"ollama pull {m}" for m in missing)
    return None
