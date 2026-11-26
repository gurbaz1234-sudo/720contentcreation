#!/usr/bin/env python3
"""Calibrate every camera from hub snapshots and write calibration.json for the hub.

Snapshot layout (pull it from the hub phone with adb, see README):
    snapshots/intrinsics/<camera>/*.png   board waved in front of each lens, 15-30 views
    snapshots/floor/<camera>/*.png        boards lying on the floor, rig in final position

    python calibrate.py --snapshots snapshots --boards boards/boards.json --out calibration.json

Outputs calibration.json plus preview_topdown.png (the stitched floor rendered
with the same maths as the phone), so you can check quality before pushing.
"""
import argparse
import glob
import json
import math
import os
import sys

import cv2
import numpy as np

from camstitch_common import detect, load_boards, project, render_topdown


def read_gray(path):
    img = cv2.imread(path)
    if img is None:
        raise RuntimeError(f"cannot read {path}")
    return img, cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)


# --------------------------------------------------------------------- intrinsics

def collect_views(files, boards):
    views, size = [], None
    for f in files:
        img, g = read_gray(f)
        if size is None:
            size = (g.shape[1], g.shape[0])
        elif size != (g.shape[1], g.shape[0]):
            print(f"  skip {os.path.basename(f)}: size differs")
            continue
        for b, ids, ip, bp in detect(g, boards, min_corners=8):
            views.append((bp.astype(np.float32), ip.astype(np.float32), os.path.basename(f)))
    return views, size


def calib_pinhole(views, size):
    obj = [v[0] for v in views]
    img = [v[1] for v in views]
    rms, K, D, _, _ = cv2.calibrateCamera(obj, img, size, None, None, flags=0)
    return rms, K, D.ravel()[:5]


def calib_fisheye(views, size, K_guess=None):
    # (1, N, 3) float64 works on both OpenCV 4.x and 5.x
    obj = [v[0].reshape(1, -1, 3).astype(np.float64) for v in views]
    img = [v[1].reshape(1, -1, 2).astype(np.float64) for v in views]
    if K_guess is None:  # equidistant lens covering ~180 deg across the width
        K_guess = np.array([[size[0] / math.pi, 0, size[0] / 2], [0, size[0] / math.pi, size[1] / 2], [0, 0, 1]])
    K = np.array(K_guess, dtype=np.float64).copy()
    D = np.zeros((4, 1))

    # Flag names moved from cv2.fisheye.* (OpenCV 4) to cv2.* (OpenCV 5).
    def flag(n):
        return getattr(cv2.fisheye, n, None) or getattr(cv2, n)
    # An intrinsic guess avoids the "norm_u1 > 0" InitExtrinsics failure.
    flags = flag("CALIB_RECOMPUTE_EXTRINSIC") | flag("CALIB_FIX_SKEW") | flag("CALIB_USE_INTRINSIC_GUESS")
    crit = (cv2.TERM_CRITERIA_COUNT + cv2.TERM_CRITERIA_EPS, 200, 1e-9)
    rms, K, D, _, _ = cv2.fisheye.calibrate(obj, img, size, K, D, flags=flags, criteria=crit)
    return rms, K, D.ravel()[:4]


def normalized_border(model, K, D, size, n=60):
    """Undistorted normalized coords of points along the image border."""
    W, H = size
    t = np.linspace(0, 1, n)
    pts = np.concatenate([
        np.stack([t * W, np.zeros(n)], 1), np.stack([t * W, np.full(n, H)], 1),
        np.stack([np.zeros(n), t * H], 1), np.stack([np.full(n, W), t * H], 1),
    ]).reshape(-1, 1, 2).astype(np.float64)
    if model == "fisheye":
        u = cv2.fisheye.undistortPoints(pts, K, D.reshape(4, 1))
    else:
        u = cv2.undistortPoints(pts, K, D)
    return u.reshape(-1, 2)


def validity_limit(model, K, D, size):
    """Largest normalized radius (pinhole) / incidence angle theta (fisheye) where the
    distortion model is still monotonic and inside the image. Beyond it the
    polynomial folds back and would paint garbage into the stitch."""
    border = normalized_border(model, K, D, size)
    r_border = np.sqrt((border ** 2).sum(1)).max()
    if model == "fisheye":
        lim = min(math.atan(r_border) * 1.02, math.radians(89.0))
        th = np.linspace(1e-3, math.radians(89.0), 4000)
        t2 = th * th
        thd = th * (1 + t2 * (D[0] + t2 * (D[1] + t2 * (D[2] + t2 * D[3]))))
        fold = np.where(np.diff(thd) <= 0)[0]
        if len(fold):
            lim = min(lim, th[fold[0]])
        return float(lim)
    k1, k2, p1, p2, k3 = list(D) + [0.0] * (5 - len(D))
    r = np.linspace(1e-3, max(r_border * 1.5, 0.5), 4000)
    r2 = r * r
    rd = r * (1 + r2 * (k1 + r2 * (k2 + r2 * k3)))
    lim = r_border * 1.02
    fold = np.where(np.diff(rd) <= 0)[0]
    if len(fold):
        lim = min(lim, r[fold[0]])
    return float(lim)


def calibrate_intrinsics(name, files, boards, model_choice):
    views, size = collect_views(files, boards)
    print(f"[{name}] {len(files)} images, {len(views)} usable board views, size {size}")
    if len(views) < 8:
        raise RuntimeError(f"[{name}] need >= 8 good board views for lens calibration, got {len(views)}. "
                           "Take more 'Snap: lens calib' shots with the board filling different parts of the image.")
    results = {}
    if model_choice in ("auto", "pinhole"):
        results["pinhole"] = calib_pinhole(views, size)
        print(f"[{name}]   pinhole RMS {results['pinhole'][0]:.3f} px")
    if model_choice in ("auto", "fisheye"):
        guesses = ([results["pinhole"][1]] if "pinhole" in results else []) + [None]
        for g in guesses:
            try:
                results["fisheye"] = calib_fisheye(views, size, g)
                print(f"[{name}]   fisheye RMS {results['fisheye'][0]:.3f} px")
                break
            except cv2.error as e:
                err = getattr(e, "err", str(e))
        else:
            print(f"[{name}]   fisheye calibration failed ({err}); using pinhole")
    if not results:
        raise RuntimeError(f"[{name}] calibration failed")
    if "pinhole" in results and "fisheye" in results:
        model = "fisheye" if results["fisheye"][0] < 0.9 * results["pinhole"][0] else "pinhole"
    else:
        model = next(iter(results))
    rms, K, D = results[model]
    hfov = math.degrees(2 * math.atan(size[0] / (2 * K[0, 0]))) if model == "pinhole" else None
    print(f"[{name}]   -> {model}, RMS {rms:.3f} px" + (f", HFOV ~{hfov:.0f} deg" if hfov else ""))
    if rms > 1.5:
        print(f"[{name}]   WARNING: RMS > 1.5 px. Check focus was locked, board is flat, and square size is right.")
    return {"model": model, "size": size, "K": K, "D": np.asarray(D, dtype=np.float64), "rms": float(rms)}


# --------------------------------------------------------------------- extrinsics

def solve_pose(name, intr, files, boards):
    """Pose of the camera in the world frame from boards lying on the floor."""
    usable = [b for b in boards if "TODO" not in b.get("world", {})]
    if not usable:
        raise RuntimeError("no floor board has a measured world pose in boards.json")
    f = sorted(files)[-1]   # latest floor snapshot (file names carry a timestamp)
    img, g = read_gray(f)
    if (g.shape[1], g.shape[0]) != tuple(intr["size"]):
        raise RuntimeError(f"[{name}] floor snapshot size {g.shape[1]}x{g.shape[0]} != lens calibration size {intr['size']}")
    W, I = [], []
    seen = []
    for b, ids, ip, bp in detect(g, usable, min_corners=6):
        T = b["T_world"]
        W.append(bp @ T[:3, :3].T + T[:3, 3])
        I.append(ip)
        seen.append(f"board {b['id']} ({len(ids)} corners)")
    if not W:
        raise RuntimeError(f"[{name}] no floor board visible in {os.path.basename(f)}")
    Wp = np.concatenate(W).astype(np.float64)
    Ip = np.concatenate(I).astype(np.float64)
    K, D = intr["K"], intr["D"]
    if intr["model"] == "fisheye":
        n = cv2.fisheye.undistortPoints(Ip.reshape(-1, 1, 2), K, D.reshape(4, 1)).reshape(-1, 2)
        ok, rvec, tvec = cv2.solvePnP(Wp, n, np.eye(3), None, flags=cv2.SOLVEPNP_SQPNP)
        ok, rvec, tvec = cv2.solvePnP(Wp, n, np.eye(3), None, rvec, tvec, True, cv2.SOLVEPNP_ITERATIVE)
    else:
        ok, rvec, tvec = cv2.solvePnP(Wp, Ip, K, D, flags=cv2.SOLVEPNP_SQPNP)
        ok, rvec, tvec = cv2.solvePnP(Wp, Ip, K, D, rvec, tvec, True, cv2.SOLVEPNP_ITERATIVE)
    if not ok:
        raise RuntimeError(f"[{name}] solvePnP failed")
    R, _ = cv2.Rodrigues(rvec)
    return R, tvec.ravel(), Wp, Ip, seen, os.path.basename(f)


# --------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--snapshots", required=True)
    ap.add_argument("--boards", required=True)
    ap.add_argument("--out", default="calibration.json")
    ap.add_argument("--model", choices=["auto", "pinhole", "fisheye"], default="auto")
    ap.add_argument("--floor-radius", type=float, default=None, help="metres of flat floor in the bowl (default: auto)")
    ap.add_argument("--feather-px", type=float, default=60.0)
    a = ap.parse_args()

    _, boards = load_boards(a.boards)
    intr_root = os.path.join(a.snapshots, "intrinsics")
    floor_root = os.path.join(a.snapshots, "floor")
    names = sorted(d for d in os.listdir(floor_root) if os.path.isdir(os.path.join(floor_root, d))) \
        if os.path.isdir(floor_root) else []
    if not names:
        sys.exit(f"no camera folders in {floor_root}")

    cams, images, failed = {}, {}, []
    for name in names:
        try:
            ifiles = sorted(glob.glob(os.path.join(intr_root, name, "*.png")))
            ffiles = sorted(glob.glob(os.path.join(floor_root, name, "*.png")))
            intr = calibrate_intrinsics(name, ifiles, boards, a.model)
            R, t, Wp, Ip, seen, fname = solve_pose(name, intr, ffiles, boards)
            K, D, size = intr["K"], intr["D"], intr["size"]
            cam = {
                "model": intr["model"], "width": size[0], "height": size[1],
                "K": [float(K[0, 0]), float(K[1, 1]), float(K[0, 2]), float(K[1, 2])],
                "dist": [float(x) for x in D],
                "R": [float(x) for x in R.ravel()], "t": [float(x) for x in t],
                "max_r": validity_limit(intr["model"], K, D, size),
                "gain": [1.0, 1.0, 1.0],
                "center": [float(x) for x in (-R.T @ t)],
                "rms_px": intr["rms"],
            }
            uv, ok = project(cam, Wp)
            err = np.sqrt(((uv - Ip) ** 2).sum(1))
            cam["floor_reproj_px"] = float(err.mean())
            c = cam["center"]
            print(f"[{name}]   floor: {', '.join(seen)} in {fname}; reprojection {err.mean():.2f} px; "
                  f"camera at ({c[0]:.3f}, {c[1]:.3f}, {c[2]:.3f}) m")
            if c[2] <= 0:
                print(f"[{name}]   WARNING: camera is below the floor -> check board 'world' poses / square size")
            cams[name] = cam
            images[name] = cv2.imread(sorted(ffiles)[-1])
        except RuntimeError as e:
            print(f"ERROR {e}")
            failed.append(name)

    if not cams:
        sys.exit("no camera calibrated")

    centers = np.array([c["center"] for c in cams.values()])
    ctr = centers[:, :2].mean(0)
    spread = float(np.linalg.norm(centers[:, :2] - ctr, axis=1).max()) if len(cams) > 1 else 0.3
    floor_r = a.floor_radius or max(0.6, round(4.0 * max(spread, 0.15), 2))
    out = {
        "version": 1, "units": "m",
        "cameras": cams,
        "bowl": {"center": [float(ctr[0]), float(ctr[1])], "floor_radius": floor_r,
                 "outer_radius": floor_r * 2.0, "height": floor_r},
        "view": {"feather_px": a.feather_px},
    }
    with open(a.out, "w") as f:
        json.dump(out, f, indent=2)
    print(f"\nwrote {a.out} ({len(cams)} cameras: {', '.join(cams)})" + (f"; FAILED: {', '.join(failed)}" if failed else ""))

    prev = render_topdown(cams, images, ctr, floor_r * 1.15, feather_px=a.feather_px)
    ppath = os.path.join(os.path.dirname(os.path.abspath(a.out)), "preview_topdown.png")
    cv2.imwrite(ppath, prev)
    print(f"wrote {ppath} (stitched floor seen from above, +X right, +Y up)")


if __name__ == "__main__":
    main()
