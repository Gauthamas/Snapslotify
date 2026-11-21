"""Puts the official PP-OCRv5 mobile ONNX files into app/src/debug/assets/paddle/ (gitignored), checking their hashes.

    python -I tools/fetch_paddle_models.py <folder holding det/ rec/ and LICENSE, as downloaded>

Only the debug build carries them, and they are never downloaded at runtime. The character list comes from the recogniser's
own inference.yml. The files stay exactly as published: nothing is converted or quantised.
"""
import hashlib, json, pathlib, shutil, sys
import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "debug" / "assets" / "paddle"
MODELS = {
    "det/inference.onnx": ("det.onnx", "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d"),
    "rec/inference.onnx": ("rec.onnx", "b5f833dfc5d0eb71da397b4efa06ebeee9b431b690a47d6af40d77d8eabc557f"),
}
src = pathlib.Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
for rel, (name, sha) in MODELS.items():
    got = hashlib.sha256((src / rel).read_bytes()).hexdigest()
    if got != sha:
        raise SystemExit(f"STOP: {rel} hash {got} is not the published one")
    shutil.copyfile(src / rel, OUT / name)
det = yaml.safe_load((src / "det/inference.yml").read_text("utf-8"))
rec = yaml.safe_load((src / "rec/inference.yml").read_text("utf-8"))
chars = rec["PostProcess"]["character_dict"]
(OUT / "rec_dict.json").write_text(json.dumps(chars, ensure_ascii=False), "utf-8")
shutil.copyfile(src / "LICENSE", OUT / "LICENSE-PaddleOCR.txt")
print("det post", det["PostProcess"])
print("det pre", [list(x)[0] if isinstance(x, dict) else x for x in det["PreProcess"]["transform_ops"]])
print("rec pre", rec["PreProcess"]["transform_ops"])
print("rec post name", rec["PostProcess"]["name"], "chars", len(chars), "first", chars[:6], "last", chars[-4:], "has space", " " in chars)
print("sizes", {p.name: p.stat().st_size for p in OUT.iterdir()})
