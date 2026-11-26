#!/usr/bin/env python3
"""Generate printable ChArUco boards + boards.json.

    python make_boards.py --count 2 --out boards/

Print each PNG on A4 at 100% scale (no "fit to page"), measure one square with a
ruler, and put the measured size into boards.json ("square_m"; marker_m scales
with it). Board 0 defines the world origin. For more phones later, lay extra
boards around the rig and enter where they are in "world".
"""
import argparse
import json
import os

import cv2
import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=2, help="number of boards (distinct marker IDs)")
    ap.add_argument("--squares", default="7x5", help="squares across x down")
    ap.add_argument("--square-mm", type=float, default=38.0, help="square size when printed (fits A4 landscape)")
    ap.add_argument("--marker-ratio", type=float, default=0.75)
    ap.add_argument("--dict", default="DICT_5X5_1000")
    ap.add_argument("--dpi", type=int, default=300)
    ap.add_argument("--out", default="boards")
    a = ap.parse_args()

    sx, sy = (int(v) for v in a.squares.lower().split("x"))
    os.makedirs(a.out, exist_ok=True)
    dictionary = cv2.aruco.getPredefinedDictionary(getattr(cv2.aruco, a.dict))
    n_markers = (sx * sy) // 2
    square_m = a.square_mm / 1000.0
    px_per_m = a.dpi / 0.0254
    margin_px = int(0.008 * px_per_m)
    boards = []
    for i in range(a.count):
        first = i * n_markers
        ids = np.arange(first, first + n_markers, dtype=np.int32)
        board = cv2.aruco.CharucoBoard((sx, sy), square_m, square_m * a.marker_ratio, dictionary, ids)
        inner = (int(round(sx * square_m * px_per_m)), int(round(sy * square_m * px_per_m)))
        img = board.generateImage(inner, marginSize=0, borderBits=1)
        img = cv2.copyMakeBorder(img, margin_px, margin_px, margin_px, margin_px, cv2.BORDER_CONSTANT, value=255)
        size = (img.shape[1], img.shape[0])
        cv2.putText(img, f"board {i}  (top edge)", (margin_px, max(20, margin_px - 10)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.9, 0, 2)
        path = os.path.join(a.out, f"board_{i}.png")
        cv2.imwrite(path, img)
        boards.append({
            "id": i, "squares_x": sx, "squares_y": sy,
            "square_m": round(square_m, 5), "marker_m": round(square_m * a.marker_ratio, 5),
            "first_marker_id": int(first),
            # Where the board lies on the floor: printed top-left corner at (x, y) metres,
            # printed top edge along +X rotated by yaw_deg. Board 0 = origin.
            "world": {"x": 0.0, "y": 0.0, "yaw_deg": 0.0} if i == 0 else {"x": 0.0, "y": 0.0, "yaw_deg": 0.0, "TODO": "measure"},
        })
        print(f"wrote {path} ({size[0]}x{size[1]} px @ {a.dpi} dpi = {sx * a.square_mm:.0f} x {sy * a.square_mm:.0f} mm)")
    cfg = {"dictionary": a.dict, "boards": boards}
    with open(os.path.join(a.out, "boards.json"), "w") as f:
        json.dump(cfg, f, indent=2)
    print(f"wrote {os.path.join(a.out, 'boards.json')}  -> measure the printed squares and edit square_m/marker_m")


if __name__ == "__main__":
    main()
