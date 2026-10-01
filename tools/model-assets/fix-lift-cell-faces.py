#!/usr/bin/env python3
"""Strip coplanar internal faces and inset connected-boundary geometry on lift_cell_*.json.

Bit order for lift_cell_<mask>: east, west, up, down, south, north.

Connected sides are inset (not deleted) so hollow balloon shells do not open
see-through tunnels through a cluster. Coplanar faces are removed only when a
face is nearly fully covered by its neighbor (>= COVER).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODEL_DIR = ROOT / "common/src/main/resources/assets/skydock/models/block"

BITS = ("east", "west", "up", "down", "south", "north")
EPS = 1e-3
BOUND = 0.05
COVER = 0.95
INSET = 0.05


def overlaps_interval(a0: float, a1: float, b0: float, b1: float) -> bool:
    return min(a1, b1) - max(a0, b0) > EPS


def interval_overlap(a0: float, a1: float, b0: float, b1: float) -> float:
    return max(0.0, min(a1, b1) - max(a0, b0))


def overlaps_xz(a: dict, b: dict) -> bool:
    return overlaps_interval(a["from"][0], a["to"][0], b["from"][0], b["to"][0]) and overlaps_interval(
        a["from"][2], a["to"][2], b["from"][2], b["to"][2]
    )


def overlaps_xy(a: dict, b: dict) -> bool:
    return overlaps_interval(a["from"][0], a["to"][0], b["from"][0], b["to"][0]) and overlaps_interval(
        a["from"][1], a["to"][1], b["from"][1], b["to"][1]
    )


def overlaps_yz(a: dict, b: dict) -> bool:
    return overlaps_interval(a["from"][1], a["to"][1], b["from"][1], b["to"][1]) and overlaps_interval(
        a["from"][2], a["to"][2], b["from"][2], b["to"][2]
    )


def overlap_area_xz(a: dict, b: dict) -> float:
    return interval_overlap(a["from"][0], a["to"][0], b["from"][0], b["to"][0]) * interval_overlap(
        a["from"][2], a["to"][2], b["from"][2], b["to"][2]
    )


def overlap_area_xy(a: dict, b: dict) -> float:
    return interval_overlap(a["from"][0], a["to"][0], b["from"][0], b["to"][0]) * interval_overlap(
        a["from"][1], a["to"][1], b["from"][1], b["to"][1]
    )


def overlap_area_yz(a: dict, b: dict) -> float:
    return interval_overlap(a["from"][1], a["to"][1], b["from"][1], b["to"][1]) * interval_overlap(
        a["from"][2], a["to"][2], b["from"][2], b["to"][2]
    )


def area_xy(el: dict) -> float:
    return max(0.0, el["to"][0] - el["from"][0]) * max(0.0, el["to"][2] - el["from"][2])


def area_yz(el: dict) -> float:
    return max(0.0, el["to"][1] - el["from"][1]) * max(0.0, el["to"][2] - el["from"][2])


def area_xy_side(el: dict) -> float:
    return max(0.0, el["to"][0] - el["from"][0]) * max(0.0, el["to"][1] - el["from"][1])


def face_area(el: dict, face: str) -> float:
    if face in ("up", "down"):
        return area_xy(el)
    if face in ("east", "west"):
        return area_yz(el)
    return area_xy_side(el)


def covered(face_area_value: float, overlap: float) -> bool:
    return face_area_value > EPS and overlap / face_area_value >= COVER


def drop_if_covered(faces: dict, face: str, el: dict, overlap: float) -> int:
    if face not in faces:
        return 0
    if not covered(face_area(el, face), overlap):
        return 0
    del faces[face]
    return 1


def drop_smaller_face(a: dict, b: dict, faces_a: dict, faces_b: dict, face: str) -> int:
    """When two faces share the same exterior plane, keep the larger one (envelope over brass)."""
    if face not in faces_a or face not in faces_b:
        return 0
    if face_area(a, face) <= face_area(b, face):
        del faces_a[face]
    else:
        del faces_b[face]
    return 1


def strip_coplanar(elements: list[dict]) -> int:
    removed = 0
    n = len(elements)
    for i in range(n):
        a = elements[i]
        faces_a = a.setdefault("faces", {})
        for j in range(i + 1, n):
            b = elements[j]
            faces_b = b.setdefault("faces", {})
            # Y shared plane: a top meets b bottom (or reverse)
            if abs(a["to"][1] - b["from"][1]) <= EPS and overlaps_xz(a, b):
                ov = overlap_area_xz(a, b)
                removed += drop_if_covered(faces_a, "up", a, ov)
                removed += drop_if_covered(faces_b, "down", b, ov)
            elif abs(b["to"][1] - a["from"][1]) <= EPS and overlaps_xz(a, b):
                ov = overlap_area_xz(a, b)
                removed += drop_if_covered(faces_b, "up", b, ov)
                removed += drop_if_covered(faces_a, "down", a, ov)
            # Same exterior plane: two downs or two ups on the same y (brass ∩ teal underside)
            if abs(a["from"][1] - b["from"][1]) <= EPS and overlaps_xz(a, b):
                removed += drop_smaller_face(a, b, faces_a, faces_b, "down")
            if abs(a["to"][1] - b["to"][1]) <= EPS and overlaps_xz(a, b):
                removed += drop_smaller_face(a, b, faces_a, faces_b, "up")
            # X shared plane
            if abs(a["to"][0] - b["from"][0]) <= EPS and overlaps_yz(a, b):
                ov = overlap_area_yz(a, b)
                removed += drop_if_covered(faces_a, "east", a, ov)
                removed += drop_if_covered(faces_b, "west", b, ov)
            elif abs(b["to"][0] - a["from"][0]) <= EPS and overlaps_yz(a, b):
                ov = overlap_area_yz(a, b)
                removed += drop_if_covered(faces_b, "east", b, ov)
                removed += drop_if_covered(faces_a, "west", a, ov)
            if abs(a["from"][0] - b["from"][0]) <= EPS and overlaps_yz(a, b):
                removed += drop_smaller_face(a, b, faces_a, faces_b, "west")
            if abs(a["to"][0] - b["to"][0]) <= EPS and overlaps_yz(a, b):
                removed += drop_smaller_face(a, b, faces_a, faces_b, "east")
            # Z shared plane
            if abs(a["to"][2] - b["from"][2]) <= EPS and overlaps_xy(a, b):
                ov = overlap_area_xy(a, b)
                removed += drop_if_covered(faces_a, "south", a, ov)
                removed += drop_if_covered(faces_b, "north", b, ov)
            elif abs(b["to"][2] - a["from"][2]) <= EPS and overlaps_xy(a, b):
                ov = overlap_area_xy(a, b)
                removed += drop_if_covered(faces_b, "south", b, ov)
                removed += drop_if_covered(faces_a, "north", a, ov)
            if abs(a["from"][2] - b["from"][2]) <= EPS and overlaps_xy(a, b):
                removed += drop_smaller_face(a, b, faces_a, faces_b, "north")
            if abs(a["to"][2] - b["to"][2]) <= EPS and overlaps_xy(a, b):
                removed += drop_smaller_face(a, b, faces_a, faces_b, "south")
    return removed


def inset_connected(elements: list[dict], mask: int) -> int:
    """Pull geometry off connected block faces so neighbors do not z-fight or open tunnels."""
    connected = {BITS[i]: bool(mask & (1 << i)) for i in range(6)}
    changed = 0
    for el in elements:
        fr, to = el["from"], el["to"]
        before = (fr[0], fr[1], fr[2], to[0], to[1], to[2])
        if connected["east"]:
            if to[0] > 16 - INSET:
                to[0] = 16 - INSET
            if fr[0] > 16 - INSET:
                fr[0] = 16 - INSET
        if connected["west"]:
            if fr[0] < INSET:
                fr[0] = INSET
            if to[0] < INSET:
                to[0] = INSET
        if connected["up"]:
            if to[1] > 16 - INSET:
                to[1] = 16 - INSET
            if fr[1] > 16 - INSET:
                fr[1] = 16 - INSET
        if connected["down"]:
            if fr[1] < INSET:
                fr[1] = INSET
            if to[1] < INSET:
                to[1] = INSET
        if connected["south"]:
            if to[2] > 16 - INSET:
                to[2] = 16 - INSET
            if fr[2] > 16 - INSET:
                fr[2] = 16 - INSET
        if connected["north"]:
            if fr[2] < INSET:
                fr[2] = INSET
            if to[2] < INSET:
                to[2] = INSET
        after = (fr[0], fr[1], fr[2], to[0], to[1], to[2])
        if after != before:
            changed += 1
    return changed


def fix_model(path: Path) -> tuple[int, int, int]:
    mask = int(path.stem.split("_")[-1])
    data = json.loads(path.read_text())
    elements = data.get("elements", [])
    coplanar = strip_coplanar(elements)
    connected = inset_connected(elements, mask)
    kept = []
    dropped = 0
    for el in elements:
        fr, to = el["from"], el["to"]
        if to[0] - fr[0] <= EPS or to[1] - fr[1] <= EPS or to[2] - fr[2] <= EPS:
            dropped += 1
            continue
        if not el.get("faces"):
            dropped += 1
            continue
        kept.append(el)
    data["elements"] = kept
    path.write_text(json.dumps(data, indent=2) + "\n")
    return coplanar, connected, dropped


def fully_covered_leftover(a: dict, face_a: str, b: dict, face_b: str, overlap: float) -> bool:
    """True if either face is still present and would be stripped (>= COVER)."""
    faces_a = a.get("faces", {})
    faces_b = b.get("faces", {})
    if face_a in faces_a and covered(face_area(a, face_a), overlap):
        return True
    if face_b in faces_b and covered(face_area(b, face_b), overlap):
        return True
    return False


def verify() -> tuple[int, int]:
    coplanar_left = 0
    connected_left = 0
    for path in sorted(MODEL_DIR.glob("lift_cell_*.json")):
        mask = int(path.stem.split("_")[-1])
        data = json.loads(path.read_text())
        elements = data.get("elements", [])
        connected = {BITS[i]: bool(mask & (1 << i)) for i in range(6)}
        for i, a in enumerate(elements):
            for b in elements[i + 1 :]:
                if abs(a["to"][1] - b["from"][1]) <= EPS and overlaps_xz(a, b):
                    if fully_covered_leftover(a, "up", b, "down", overlap_area_xz(a, b)):
                        coplanar_left += 1
                if abs(b["to"][1] - a["from"][1]) <= EPS and overlaps_xz(a, b):
                    if fully_covered_leftover(b, "up", a, "down", overlap_area_xz(a, b)):
                        coplanar_left += 1
                if abs(a["from"][1] - b["from"][1]) <= EPS and overlaps_xz(a, b):
                    if "down" in a.get("faces", {}) and "down" in b.get("faces", {}):
                        coplanar_left += 1
                if abs(a["to"][1] - b["to"][1]) <= EPS and overlaps_xz(a, b):
                    if "up" in a.get("faces", {}) and "up" in b.get("faces", {}):
                        coplanar_left += 1
                if abs(a["to"][0] - b["from"][0]) <= EPS and overlaps_yz(a, b):
                    if fully_covered_leftover(a, "east", b, "west", overlap_area_yz(a, b)):
                        coplanar_left += 1
                if abs(b["to"][0] - a["from"][0]) <= EPS and overlaps_yz(a, b):
                    if fully_covered_leftover(b, "east", a, "west", overlap_area_yz(a, b)):
                        coplanar_left += 1
                if abs(a["from"][0] - b["from"][0]) <= EPS and overlaps_yz(a, b):
                    if "west" in a.get("faces", {}) and "west" in b.get("faces", {}):
                        coplanar_left += 1
                if abs(a["to"][0] - b["to"][0]) <= EPS and overlaps_yz(a, b):
                    if "east" in a.get("faces", {}) and "east" in b.get("faces", {}):
                        coplanar_left += 1
                if abs(a["to"][2] - b["from"][2]) <= EPS and overlaps_xy(a, b):
                    if fully_covered_leftover(a, "south", b, "north", overlap_area_xy(a, b)):
                        coplanar_left += 1
                if abs(b["to"][2] - a["from"][2]) <= EPS and overlaps_xy(a, b):
                    if fully_covered_leftover(b, "south", a, "north", overlap_area_xy(a, b)):
                        coplanar_left += 1
                if abs(a["from"][2] - b["from"][2]) <= EPS and overlaps_xy(a, b):
                    if "north" in a.get("faces", {}) and "north" in b.get("faces", {}):
                        coplanar_left += 1
                if abs(a["to"][2] - b["to"][2]) <= EPS and overlaps_xy(a, b):
                    if "south" in a.get("faces", {}) and "south" in b.get("faces", {}):
                        coplanar_left += 1
            fr, to = a["from"], a["to"]
            if connected["east"] and to[0] > 16 - INSET + EPS:
                connected_left += 1
            if connected["west"] and fr[0] < INSET - EPS:
                connected_left += 1
            if connected["up"] and to[1] > 16 - INSET + EPS:
                connected_left += 1
            if connected["down"] and fr[1] < INSET - EPS:
                connected_left += 1
            if connected["south"] and to[2] > 16 - INSET + EPS:
                connected_left += 1
            if connected["north"] and fr[2] < INSET - EPS:
                connected_left += 1
    return coplanar_left, connected_left


def main() -> int:
    paths = sorted(MODEL_DIR.glob("lift_cell_*.json"))
    if len(paths) != 64:
        print(f"expected 64 lift_cell models, found {len(paths)}", file=sys.stderr)
        return 1
    total_c = total_b = total_d = 0
    for path in paths:
        c, b, d = fix_model(path)
        total_c += c
        total_b += b
        total_d += d
        print(f"{path.name}: removed coplanar={c} inset_elements={b} empty_elements={d}")
    left_c, left_b = verify()
    print(f"totals: coplanar={total_c} inset_elements={total_b} empty_elements={total_d}")
    print(f"verify remaining: coplanar={left_c} connected_protrusion={left_b}")
    # Fully connected cells must keep geometry (inset shells), not vanish.
    mask63 = json.loads((MODEL_DIR / "lift_cell_63.json").read_text())
    if not mask63.get("elements"):
        print("lift_cell_63 has no elements after inset; connected shells must stay sealed", file=sys.stderr)
        return 2
    return 0 if left_c == 0 and left_b == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
