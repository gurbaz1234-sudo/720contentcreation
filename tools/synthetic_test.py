#!/usr/bin/env python3
"""Self-test: renders fake snapshots from two virtual phones with known lenses and
poses (one ordinary lens, one fisheye), runs calibrate.py on them, and checks the
recovered calibration against the truth. Run it once to confirm your Python/OpenCV
setup works:   python synthetic_test.py
"""
import json
import math
import os
import shutil
import subprocess
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from camstitch_common import load_boards  # noqa: E402

W, H = 1280, 960
rng = np.random.default_rng(7)


def look(C, target):
    f = np.asarray(target, float) - C; f /= np.linalg.norm(f)
    right = np.cross(f, [0, 0, 1.0]); right /= np.linalg.norm(right)
    down = np.cross(f, right)
    R = np.stack([right, down, f])
    return R, -R @ C


TRUTH = {
    "hub": dict(model="pinhole", K=[700.0, 700.0, 641.0, 478.0], dist=[-0.22, 0.06, 0.0008, -0.0006, -0.008],
                C=np.array([0.133, 0.38, 0.22]), target=[0.133, -0.05, 0.0]),
    "cam1": dict(model="fisheye", K=[430.0, 431.0, 637.0, 483.0], dist=[0.04, -0.012, 0.004, -0.0008],
                 C=np.array([-0.30, -0.095, 0.22]), target=[0.2, -0.095, 0.0]),
}


def pixel_rays(cam):
    """Normalized (x, y) for every pixel."""
    u, v = np.meshgrid(np.arange(W) + 0.0, np.arange(H) + 0.0)
    pts = np.stack([u.ravel(), v.ravel()], 1).reshape(-1, 1, 2)
    fx, fy, cx, cy = cam["K"]
    K = np.array([[fx, 0, cx], [0, fy, cy], [0, 0, 1]])
    if cam["model"] == "fisheye":
        n = cv2.fisheye.undistortPoints(pts, K, np.array(cam["dist"]).reshape(4, 1))
    else:
        n = cv2.undistortPoints(pts, K, np.array(cam["dist"]))
    return n.reshape(-1, 2)


def render_plane(rays, R_bc, t_bc, tex, ppm, margin, bg):
    """Render a board plane (board->camera pose) seen through 'rays'. bg: per-pixel background."""
    Hm = np.stack([R_bc[:, 0], R_bc[:, 1], t_bc], 1)
    Hinv = np.linalg.inv(Hm)
    q = np.concatenate([rays, np.ones((len(rays), 1))], 1) @ Hinv.T
    X, Y = q[:, 0] / q[:, 2], q[:, 1] / q[:, 2]
    s = 1.0 / q[:, 2]  # depth scale along the ray; must be > 0
    mx = (margin + X * ppm).astype(np.float32).reshape(H, W)
    my = (margin + Y * ppm).astype(np.float32).reshape(H, W)
    samp = cv2.remap(tex, mx, my, cv2.INTER_LINEAR, borderValue=-1.0)
    inside = (samp >= 0) & (s.reshape(H, W) > 0)
    return np.where(inside, samp, bg)


def main():
    work = os.path.join(HERE, "_synthetic")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    subprocess.check_call([sys.executable, os.path.join(HERE, "make_boards.py"), "--count", "1", "--out", os.path.join(work, "boards")],
                          stdout=subprocess.DEVNULL)
    cfg, boards = load_boards(os.path.join(work, "boards", "boards.json"))
    b = boards[0]
    ppm = 4000.0
    margin = 40
    inner = (int(round(b["squares_x"] * b["square_m"] * ppm)), int(round(b["squares_y"] * b["square_m"] * ppm)))
    tex = b["board"].generateImage(inner, marginSize=0, borderBits=1)
    tex = cv2.copyMakeBorder(tex, margin, margin, margin, margin, cv2.BORDER_CONSTANT, value=255).astype(np.float32)
    T_bw = b["T_world"]

    for name, cam in TRUTH.items():
        rays = pixel_rays(cam)
        R, t = look(cam["C"], cam["target"])
        cam["R"], cam["t"] = R, t
        # floor view: board lying at its world pose, on a textured floor
        floor_bg = (cv2.GaussianBlur(rng.uniform(90, 170, (H, W)).astype(np.float32), (0, 0), 3))
        R_bc = R @ T_bw[:3, :3]; t_bc = R @ T_bw[:3, 3] + t
        img = render_plane(rays, R_bc, t_bc, tex, ppm, margin, floor_bg)
        d = os.path.join(work, "snapshots", "floor", name); os.makedirs(d)
        cv2.imwrite(os.path.join(d, "floor_1000.png"), cv2.cvtColor(img.clip(0, 255).astype(np.uint8), cv2.COLOR_GRAY2BGR))
        # lens views: board held at random poses in front of the camera
        d = os.path.join(work, "snapshots", "intrinsics", name); os.makedirs(d)
        bw, bh = b["squares_x"] * b["square_m"], b["squares_y"] * b["square_m"]
        n_ok = 0
        for k in range(26):
            z = rng.uniform(0.22, 0.42)
            half = math.tan(math.radians(35 if cam["model"] == "pinhole" else 60)) * z
            cxy = rng.uniform(-half, half, 2)
            rv = rng.uniform(-0.6, 0.6, 3); rv[2] = rng.uniform(-0.5, 0.5)
            Rb, _ = cv2.Rodrigues(rv)
            tb = np.array([cxy[0], cxy[1], z]) - Rb @ np.array([bw / 2, bh / 2, 0])
            bg = np.full((H, W), 200.0, np.float32)
            img = render_plane(rays, Rb, tb, tex, ppm, margin, bg)
            cv2.imwrite(os.path.join(d, f"intrinsics_{2000 + k}.png"), cv2.cvtColor(img.clip(0, 255).astype(np.uint8), cv2.COLOR_GRAY2BGR))
            n_ok += 1
        print(f"rendered {name}: 1 floor + {n_ok} lens views")

    out = os.path.join(work, "calibration.json")
    subprocess.check_call([sys.executable, os.path.join(HERE, "calibrate.py"), "--snapshots", os.path.join(work, "snapshots"),
                           "--boards", os.path.join(work, "boards", "boards.json"), "--out", out])
    cal = json.load(open(out))
    ok = True
    print("\n=== comparison with ground truth ===")
    for name, cam in TRUTH.items():
        c = cal["cameras"].get(name)
        if c is None:
            print(f"{name}: MISSING"); ok = False; continue
        dpos = np.linalg.norm(np.array(c["center"]) - cam["C"]) * 1000
        Rr = np.array(c["R"]).reshape(3, 3)
        dang = math.degrees(np.arccos(np.clip((np.trace(Rr.T @ cam["R"]) - 1) / 2, -1, 1)))
        dfx = abs(c["K"][0] - cam["K"][0]) / cam["K"][0] * 100
        good = c["model"] == cam["model"] and dpos < 10 and dang < 0.5 and dfx < 1.5
        ok &= good
        print(f"{name}: model {c['model']} (truth {cam['model']}), position error {dpos:.1f} mm, "
              f"rotation error {dang:.3f} deg, focal error {dfx:.2f}%  -> {'OK' if good else 'FAIL'}")
    print("\nSELF-TEST", "PASSED" if ok else "FAILED", f"(see {os.path.join(work, 'preview_topdown.png')})")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
