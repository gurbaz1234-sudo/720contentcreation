"""Shared helpers: ChArUco board definitions and the camera projection model.

The projection here is deliberately identical to the hub's GLSL shader
(app/src/main/java/com/camstitch/Shaders.kt), so preview images rendered on
the PC show exactly what the phone will draw.
"""
import json
import math

import cv2
import numpy as np


# --------------------------------------------------------------------- boards

def load_boards(path):
    """Read boards.json -> (dictionary, list of board dicts with cv2 objects)."""
    with open(path) as f:
        cfg = json.load(f)
    dictionary = cv2.aruco.getPredefinedDictionary(getattr(cv2.aruco, cfg["dictionary"]))
    boards = []
    for b in cfg["boards"]:
        n_markers = (b["squares_x"] * b["squares_y"]) // 2
        ids = np.arange(b["first_marker_id"], b["first_marker_id"] + n_markers, dtype=np.int32)
        board = cv2.aruco.CharucoBoard((b["squares_x"], b["squares_y"]), b["square_m"], b["marker_m"], dictionary, ids)
        b = dict(b)
        b["board"] = board
        b["detector"] = cv2.aruco.CharucoDetector(board)
        b["T_world"] = board_to_world(b.get("world", {}))
        boards.append(b)
    return cfg, boards


def board_to_world(w):
    """4x4 transform from board coords (x right, y down the printed page, z into the floor)
    to world (metres, Z up). The board's printed top-left corner sits at (x, y, 0);
    printed 'right' points along world +X rotated by yaw, printed 'up' along world +Y."""
    yaw = math.radians(w.get("yaw_deg", 0.0))
    rz = np.array([[math.cos(yaw), -math.sin(yaw), 0], [math.sin(yaw), math.cos(yaw), 0], [0, 0, 1]])
    flip = np.diag([1.0, -1.0, -1.0])
    T = np.eye(4)
    T[:3, :3] = rz @ flip
    T[:3, 3] = [w.get("x", 0.0), w.get("y", 0.0), 0.0]
    return T


def detect(gray, boards, min_corners=6):
    """Detect every board in an image. Returns list of (board, ids(N,), img_pts(N,2), board_pts(N,3))."""
    out = []
    for b in boards:
        corners, ids, _, _ = b["detector"].detectBoard(gray)
        if ids is None or len(ids) < min_corners:
            continue
        ids = ids.ravel()
        obj = b["board"].getChessboardCorners()[ids]
        out.append((b, ids, corners.reshape(-1, 2).astype(np.float64), obj.astype(np.float64)))
    return out


# --------------------------------------------------------------------- camera model

def project(cam, Pw):
    """World points (N,3) -> pixel coords (N,2) and validity mask, same math as the shader."""
    R = np.asarray(cam["R"], dtype=np.float64).reshape(3, 3)
    t = np.asarray(cam["t"], dtype=np.float64)
    fx, fy, cx, cy = cam["K"]
    D = list(cam["dist"]) + [0.0] * 5
    Pc = Pw @ R.T + t
    z = Pc[:, 2]
    valid = z > 1e-4
    zs = np.where(valid, z, 1.0)
    x = Pc[:, 0] / zs
    y = Pc[:, 1] / zs
    r2 = x * x + y * y
    if cam["model"] == "fisheye":
        r = np.sqrt(r2)
        th = np.arctan(r)
        valid &= th <= cam["max_r"]
        t2 = th * th
        thd = th * (1 + t2 * (D[0] + t2 * (D[1] + t2 * (D[2] + t2 * D[3]))))
        s = np.where(r > 1e-8, thd / np.maximum(r, 1e-12), 1.0)
        xd, yd = x * s, y * s
    else:
        valid &= r2 <= cam["max_r"] ** 2
        k1, k2, p1, p2, k3 = D[:5]
        rad = 1 + r2 * (k1 + r2 * (k2 + r2 * k3))
        xd = x * rad + 2 * p1 * x * y + p2 * (r2 + 2 * x * x)
        yd = y * rad + p1 * (r2 + 2 * y * y) + 2 * p2 * x * y
    u = fx * xd + cx
    v = fy * yd + cy
    W, H = cam["width"], cam["height"]
    valid &= (u >= 0) & (v >= 0) & (u <= W) & (v <= H)
    return np.stack([u, v], axis=1), valid


def render_topdown(cams, images, center, half_size_m, px=800, feather_px=60.0):
    """Render the stitched floor (Z=0) as seen from above, like the hub's Top-down view.
    images: dict name -> BGR image. Returns BGR image (px x px); +Y is up, +X right."""
    xs = np.linspace(center[0] - half_size_m, center[0] + half_size_m, px)
    ys = np.linspace(center[1] + half_size_m, center[1] - half_size_m, px)
    gx, gy = np.meshgrid(xs, ys)
    P = np.stack([gx.ravel(), gy.ravel(), np.zeros(gx.size)], axis=1)
    acc = np.zeros((P.shape[0], 3))
    wsum = np.zeros(P.shape[0])
    for name, cam in cams.items():
        img = images.get(name)
        if img is None:
            continue
        uv, ok = project(cam, P)
        W, H = cam["width"], cam["height"]
        edge = np.minimum(np.minimum(uv[:, 0], W - uv[:, 0]), np.minimum(uv[:, 1], H - uv[:, 1]))
        w = np.clip(edge / feather_px, 0, 1) ** 2 + 1e-4
        w = np.where(ok, w, 0.0)
        mx = uv[:, 0].astype(np.float32).reshape(px, px)
        my = uv[:, 1].astype(np.float32).reshape(px, px)
        samp = cv2.remap(img, mx, my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT).reshape(-1, 3)
        acc += samp * w[:, None]
        wsum += w
    out = np.full((P.shape[0], 3), (46, 41, 41), dtype=np.float64)
    m = wsum > 0
    out[m] = acc[m] / wsum[m, None]
    return out.reshape(px, px, 3).clip(0, 255).astype(np.uint8)
