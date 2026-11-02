"""Settings for the agent system, read from environment variables and an optional .env file."""
from __future__ import annotations

import os
import shlex
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _load_dotenv() -> None:
    env_file = ROOT / ".env"
    if not env_file.exists():
        return
    try:
        from dotenv import load_dotenv

        load_dotenv(env_file, override=False)
    except ImportError:  # python-dotenv is optional
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                value = value.split(" #", 1)[0].split("\t#", 1)[0]  # inline comment
                os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _path(value: str, default: Path) -> Path:
    if not value:
        return default
    p = Path(value).expanduser()
    return p if p.is_absolute() else (ROOT / p)


@dataclass
class Settings:
    # Language model. "ollama" runs locally; "openai", "anthropic" and "google" need the
    # matching langchain-* package and an API key; "manual" hands every prompt to a person,
    # who can paste it into any web LLM (the reply is given back on resume).
    llm_provider: str = "ollama"
    llm_model: str = "qwen2.5-coder:7b"
    refactor_model: str = ""  # optional larger model just for the refactoring agent
    manual_model: str = ""    # with LLM_PROVIDER=manual: which LLM the person relays (for the records)
    ollama_base_url: str = "http://localhost:11434"
    temperature: float = 0.0
    num_ctx: int = 32768
    num_predict: int = 16384
    llm_timeout: float = 3600.0

    # Java build
    mvn: str = "mvn"
    maven_args: list[str] = field(default_factory=list)
    maven_timeout: int = 900
    pom_template: Path = ROOT / "season-switch" / "pom.xml"
    delivery_pom: Path = ROOT / "season-switch" / "pom.xml"  # pom.xml copied into final/ (rebuildable projects)
    test_timeout_seconds: int = 10

    # Observing the program (setup): one run per argument list, each stopped after observe_seconds
    observe_args: list[list[str]] = field(default_factory=lambda: [[]])
    observe_seconds: float = 4.0

    # Where runs are written
    runs_dir: Path = ROOT / "runs"
    prompts_dir: Path = ROOT / "agent_system" / "prompts"
    refactor_prompt: Path = ROOT / "agent_system" / "prompts" / "refactor_state_pattern.md"

    # Loop limits (a person is asked what to do when one is reached)
    max_review_rounds: int = 5
    max_repair_rounds: int = 6
    max_coverage_rounds: int = 4
    max_refactor_attempts: int = 3
    target_branch_coverage: float = 100.0

    @classmethod
    def from_env(cls) -> "Settings":
        _load_dotenv()
        s = cls()
        get = os.environ.get
        s.llm_provider = get("LLM_PROVIDER", s.llm_provider).strip().lower()
        s.llm_model = get("LLM_MODEL", s.llm_model).strip()
        s.refactor_model = get("REFACTOR_MODEL", s.refactor_model).strip()
        s.manual_model = get("MANUAL_MODEL", s.manual_model).strip()
        s.ollama_base_url = get("OLLAMA_BASE_URL", s.ollama_base_url).strip()
        s.temperature = float(get("LLM_TEMPERATURE", s.temperature))
        s.num_ctx = int(get("LLM_NUM_CTX", s.num_ctx))
        s.num_predict = int(get("LLM_NUM_PREDICT", s.num_predict))
        s.llm_timeout = float(get("LLM_TIMEOUT", s.llm_timeout))
        s.mvn = get("MVN", s.mvn).strip() or "mvn"
        s.maven_args = shlex.split(get("MAVEN_ARGS", ""))
        s.maven_timeout = int(get("MAVEN_TIMEOUT", s.maven_timeout))
        s.pom_template = _path(get("POM_TEMPLATE", ""), s.pom_template)
        s.delivery_pom = _path(get("DELIVERY_POM", ""), s.delivery_pom)
        s.test_timeout_seconds = int(get("TEST_TIMEOUT_SECONDS", s.test_timeout_seconds))
        if get("OBSERVE_ARGS") is not None:  # e.g. ";cool;heat;off;warm" (an empty item = no arguments)
            s.observe_args = [shlex.split(item) for item in get("OBSERVE_ARGS").split(";")]
        s.observe_seconds = float(get("OBSERVE_SECONDS", s.observe_seconds))
        s.runs_dir = _path(get("RUNS_DIR", ""), s.runs_dir)
        s.refactor_prompt = _path(get("REFACTOR_PROMPT", ""), s.refactor_prompt)
        s.max_review_rounds = int(get("MAX_REVIEW_ROUNDS", s.max_review_rounds))
        s.max_repair_rounds = int(get("MAX_REPAIR_ROUNDS", s.max_repair_rounds))
        s.max_coverage_rounds = int(get("MAX_COVERAGE_ROUNDS", s.max_coverage_rounds))
        s.max_refactor_attempts = int(get("MAX_REFACTOR_ATTEMPTS", s.max_refactor_attempts))
        s.target_branch_coverage = float(get("TARGET_BRANCH_COVERAGE", s.target_branch_coverage))
        return s

    def model_for(self, role: str) -> str:
        if self.llm_provider == "manual":
            return self.manual_model or "web LLM"
        if role.startswith("refactor") and self.refactor_model:
            return self.refactor_model
        return self.llm_model

    def describe(self) -> str:
        model = self.llm_model if self.llm_provider != "manual" else (
            f"{self.manual_model} (replies relayed by hand)" if self.manual_model else "a person relaying a web LLM")
        extra = f", refactoring with {self.refactor_model}" if self.refactor_model else ""
        return f"{self.llm_provider}: {model}{extra}"
