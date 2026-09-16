from pathlib import Path

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"


def list_templates():
    return sorted((path.parent.name, path.stem) for path in PROMPTS_DIR.glob("*/*.md"))


def load_template(template_id, version="v1"):
    return (PROMPTS_DIR / template_id / f"{version}.md").read_text(encoding="utf-8")
