"""Generate the plugin Overview and offline page from the supplied PyCharm screenshots."""
from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass
import hashlib
import html
from io import BytesIO
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS_DIR = ROOT / "docs/overview/assets"
MAX_DESCRIPTION_LENGTH = 65535
MAX_INLINE_WIDTH = 560


@dataclass(frozen=True)
class Screenshot:
    name: str
    alt: str
    crop: tuple[int, int, int, int]
    colors: int


SCREENSHOTS = {
    "images": Screenshot("image-paste", "Save Notebook Image dialog", (289, 232, 971, 511), 96),
    "color-toolbar": Screenshot("text-color-toolbar", "Text Color action in the selection toolbar", (23, 34, 441, 141), 64),
    "color": Screenshot("text-color", "Text Color dialog with preset swatches", (346, 251, 977, 459), 128),
    "color-markup": Screenshot("text-color-markup", "HTML color markup in the Markdown cell", (43, 15, 758, 101), 64),
    "insert-menu": Screenshot("insert-menu", "Double-Alt Markdown insertion menu", (94, 175, 457, 286), 64),
    "blank-lines": Screenshot("blank-lines", "Insert Visible Blank Lines dialog", (570, 389, 1177, 670), 96),
    "table": Screenshot("markdown-table", "Insert Markdown Table dialog and editable grid", (206, 162, 919, 490), 96),
}


def image_tag(data: bytes, size: tuple[int, int], alt: str) -> str:
    encoded = base64.b64encode(data).decode("ascii")
    width, height = size
    return (f'<img src="data:image/png;base64,{encoded}" width="{width}" height="{height}" '
            f'alt="{html.escape(alt, quote=True)}">')


def inline_preview(original: Image.Image, capture: Screenshot) -> tuple[bytes, tuple[int, int]]:
    left, top, right, bottom = capture.crop
    if not (0 <= left < right <= original.width and 0 <= top < bottom <= original.height):
        raise ValueError(f"Crop is outside the source screenshot: {capture.name}")
    preview = original.convert("RGB").crop(capture.crop)
    if preview.width > MAX_INLINE_WIDTH:
        height = round(preview.height * MAX_INLINE_WIDTH / preview.width)
        preview = preview.resize((MAX_INLINE_WIDTH, height), Image.Resampling.LANCZOS)
    # Encode at the displayed width. The full, unmodified PNG remains in the offline page.
    indexed = preview.quantize(colors=capture.colors, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE)
    indexed = indexed.remap_palette(sorted(set(indexed.get_flattened_data())))
    buffer = BytesIO()
    indexed.save(buffer, format="PNG", optimize=True)
    return buffer.getvalue(), preview.size


def generate() -> tuple[dict[Path, bytes], list[str], int]:
    description = (ROOT / "docs/overview/description.html.in").read_text(encoding="utf-8").strip()
    table_example = (ASSETS_DIR / "generated-table-markdown.txt").read_text(encoding="utf-8").strip()
    if description.count("{{table-markdown}}") != 1:
        raise ValueError("Expected one table example placeholder")
    description = description.replace("{{table-markdown}}", html.escape(table_example))
    offline_description = description
    outputs: dict[Path, bytes] = {}
    records: list[str] = []
    for key, capture in SCREENSHOTS.items():
        filename = capture.name + ".png"
        original_data = (ASSETS_DIR / "full" / filename).read_bytes()
        with Image.open(BytesIO(original_data)) as original:
            if original.format != "PNG":
                raise ValueError(f"Expected a PNG original: {filename}")
            preview_data, preview_size = inline_preview(original, capture)
            original_size = original.size
        image_token = "{{image:" + key + "}}"
        original_token = "{{original:" + key + "}}"
        if description.count(image_token) != 1 or description.count(original_token) != 1:
            raise ValueError(f"Expected one compact and one original image placeholder: {key}")
        preview_img = image_tag(preview_data, preview_size, capture.alt)
        full_img = image_tag(original_data, original_size, capture.alt + " — original screenshot")
        description = description.replace(image_token, preview_img).replace(original_token + "\n", "")
        offline_description = offline_description.replace(image_token, preview_img).replace(
            original_token,
            f"<details><summary>Original screenshot</summary><p>{full_img}</p></details>",
        )
        outputs[ASSETS_DIR / filename] = preview_data
        records.append(f"{filename}: {preview_size[0]}x{preview_size[1]} inline, "
                       f"{original_size[0]}x{original_size[1]} original, {len(preview_data)} embedded bytes")
    if "{{" in description or "{{" in offline_description or "]]>" in description:
        raise ValueError("Unresolved placeholder or invalid CDATA content")

    descriptor_path = ROOT / "src/main/resources/META-INF/plugin.xml"
    descriptor = descriptor_path.read_text(encoding="utf-8")
    replacement = "<description><![CDATA[\n" + description + "\n    ]]></description>"
    descriptor, count = re.subn(r"<description>.*?</description>", lambda _: replacement, descriptor, count=1, flags=re.S)
    if count != 1:
        raise ValueError("Expected one plugin description")
    parsed = ET.fromstring(descriptor)
    length = len(parsed.findtext("description").encode("utf-16-le")) // 2
    if length > MAX_DESCRIPTION_LENGTH:
        raise ValueError(f"Overview uses {length} characters; the plugin descriptor allows {MAX_DESCRIPTION_LENGTH}")
    name = parsed.findtext("name")
    version = parsed.findtext("version")
    title = html.escape(f"{name} {version}")
    overview_filename = f"PyCharm_{name.replace(' ', '_')}_{version}_Overview.html"
    page = f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} — Overview</title>
<style>
body{{max-width:1040px;margin:40px auto;padding:0 24px;color:#111;background:#fff;font:16px/1.7 "Microsoft YaHei",sans-serif}}
h1,h2,h3{{color:#111;line-height:1.4}}h2{{margin-top:48px}}h3{{margin-top:32px}}
img{{display:block;max-width:100%;height:auto;border:1px solid #ddd}}
details{{margin:12px 0 24px}}summary{{cursor:pointer}}
li{{margin:8px 0}}pre{{white-space:pre-wrap;overflow-wrap:anywhere;font-size:15px;line-height:1.6}}
</style></head><body><h1>{title}</h1>
{offline_description}
</body></html>
'''
    outputs[descriptor_path] = descriptor.encode("utf-8")
    outputs[ROOT / "output/html" / overview_filename] = page.encode("utf-8")
    return outputs, records, length


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check generated files without changing them")
    options = parser.parse_args()
    outputs, records, length = generate()
    for path, data in outputs.items():
        if options.check:
            if not path.is_file() or path.read_bytes() != data:
                raise SystemExit(f"Out of date: {path.relative_to(ROOT)}; run tools/update_overview.py")
        elif not path.is_file() or path.read_bytes() != data:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    for record in records:
        print(record)
    descriptor_path = ROOT / "src/main/resources/META-INF/plugin.xml"
    print(f"Overview: {length}/{MAX_DESCRIPTION_LENGTH} UTF-16 characters, {len(SCREENSHOTS)} inline PNGs")
    print(f"Descriptor SHA256: {hashlib.sha256(outputs[descriptor_path]).hexdigest()}")
    print("Generated files checked." if options.check else "Overview descriptor, PNGs and offline page generated.")


if __name__ == "__main__":
    main()
