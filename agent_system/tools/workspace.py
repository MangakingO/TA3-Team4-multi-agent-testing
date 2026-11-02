"""Maven project folders for a run: runs/<run>/<phase>/project with the standard layout."""
from __future__ import annotations

import re
import shutil
from pathlib import Path

from .javasrc import source_relpath


def create_project(dest: Path, pom_template: Path, main_class: str) -> Path:
    dest = Path(dest)
    (dest / "src" / "main" / "java").mkdir(parents=True, exist_ok=True)
    (dest / "src" / "test" / "java").mkdir(parents=True, exist_ok=True)
    pom = Path(pom_template).read_text(encoding="utf-8")
    pom = re.sub(r"<exec\.mainClass>.*?</exec\.mainClass>", f"<exec.mainClass>{main_class}</exec.mainClass>", pom)
    (dest / "pom.xml").write_text(pom, encoding="utf-8")
    return dest


def main_dir(project: Path) -> Path:
    return Path(project) / "src" / "main" / "java"


def test_dir(project: Path) -> Path:
    return Path(project) / "src" / "test" / "java"


def write_sources(project: Path, package: str, files: dict[str, str], *, replace: bool = False) -> list[Path]:
    """Write {file name: content} into src/main/java/<package dirs>/ (replace=True clears old sources)."""
    root = main_dir(project)
    if replace and root.exists():
        shutil.rmtree(root)
    written = []
    for name, content in files.items():
        safe = Path(name).name  # never write outside the source folder
        if not safe.endswith(".java"):
            continue
        path = root / Path(*source_relpath(package, safe).parts)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content.rstrip() + "\n", encoding="utf-8")
        written.append(path)
    return written


def read_sources(project: Path) -> dict[str, str]:
    """{file name: content} for every .java file under src/main/java."""
    root = main_dir(project)
    return {p.name: p.read_text(encoding="utf-8") for p in sorted(root.rglob("*.java"))}


TEST_SUPPORT = Path(__file__).resolve().parent.parent / "templates" / "TestSupport.java"


def test_support_source(package: str) -> str:
    template = TEST_SUPPORT.read_text(encoding="utf-8")
    return template.replace("$package_line", f"package {package};\n" if package else "")


def write_test_class(project: Path, package: str, class_name: str, source: str) -> Path:
    """Write <Class>Test.java and the TestSupport.java helper it uses."""
    path = test_dir(project) / Path(*source_relpath(package, f"{class_name}Test.java").parts)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")
    (path.parent / "TestSupport.java").write_text(test_support_source(package), encoding="utf-8")
    return path


def copy_tree(src: Path, dest: Path) -> None:
    if Path(dest).exists():
        shutil.rmtree(dest)
    if Path(src).exists():
        shutil.copytree(src, dest)
