# CamStitch: live phone-camera stitching (POC)

The primary phone (**hub**) receives live H.264 streams from the other phones (**senders**). It decodes them on the GPU and shows them as:

- **Feeds**: every camera side by side, with fps, bitrate and true capture-to-display latency.
- **Top-down**: a stitched bird's-eye view of the floor.
- **3D orbit**: the car-style 360° bowl. Drag to orbit, pinch to zoom.

The hub's own camera is a regular input too. Internally it streams to itself over 127.0.0.1, so every camera goes through the identical pipeline.

```
Sender phone(s): Camera2 (stabilization off, AE/AWB/focus locked) -> H.264 baseline, 1 s keyframes -> TCP
                                                                                                    |
Hub phone:  TCP -> MediaCodec decode -> SurfaceTexture (GPU) -> GLES shader: project bowl into each camera, blend -> screen
```

Start with 2 phones. Adding a phone means installing the app, giving it a unique name, calibrating it and re-running `calibrate.py`. No code changes are needed.

---

## 1. Build and install

Requirements: Android Studio (Ladybug or newer) and Android 10+ phones. There are no third-party libraries.

1. Open the `camstitch/` folder in Android Studio and let Gradle sync (AGP 8.7, Kotlin 2.0).
2. Enable USB debugging on each phone and press **Run**. Alternatively, run `./gradlew assembleDebug` and then `adb install app/build/outputs/apk/debug/app-debug.apk`.

**No Android Studio?** Push this folder to a GitHub repository. The included workflow (`.github/workflows/build-apk.yml`) builds the APK on GitHub's servers. Open the repo's **Actions** tab, select the latest run, and download `camstitch-debug-apk`. Then install it with `adb install`, or copy it to each phone and open it (allow "install unknown apps").

## 2. Network

- On the **hub** phone, turn on the **Wi-Fi hotspot** (5 GHz if offered). The **sender** phones join it.
- Senders find the hub automatically via mDNS. If that fails (some hotspots block multicast), type the hub IP into the sender screen. The hub shows its IPs at the top-left (e.g. `swlan0:192.168.x.1`).
- A normal Wi-Fi router also works. Keep all phones on the same network.

## 3. Run

On every phone, open **CamStitch** and set:

| Field | Hub phone | Sender phone |
|---|---|---|
| Camera name | `hub` | `cam1`, `cam2`, ... (must be **unique**) |
| Camera / lens | the widest option (ultra-wide / "zoom 0.5x" / "-> lens N") | same |
| Resolution | 1280x960 (4:3 keeps the full field of view) | **same as the hub** |

Press **Start HUB** on the primary phone and **Start SENDER** on the others.

- The sender screen shows a preview for aiming, the connection state, clock-sync RTT and the lock state.
- Exposure, white balance and focus **auto-lock 3 s after start**. Point the phones at the scene first. Use "Unlock exposure" to re-meter, then lock again.
- The hub shows the feeds immediately. The `latency` number is sensor capture → decoded on the hub, measured with synced clocks. Add about 15–30 ms for the display.

## 4. Calibrate (once per rig build)

Stitching needs each camera's lens model and its exact pose on the box. **Mount the phones rigidly** (hot glue or clamps, not just tape). Any bump means recalibrating.

### 4a. Print boards
```
cd tools
pip install -r requirements.txt
python make_boards.py --count 2 --out boards
```
Print `board_0.png` and `board_1.png` on A4 at **100% scale**. Measure one square with a ruler and fix `square_m` (and `marker_m` = 0.75 × square) in `boards/boards.json`. Glue the boards to cardboard so they stay flat.

### 4b. Lens snapshots (per camera)
On the hub, choose **View: Feeds**. Hold a board in front of one camera and press **Snap: lens calib** 15–30 times, moving the board each time: near and far, tilted, and **into the corners and edges** of the image. Repeat for every camera. Each press saves a frame from every feed; frames without a board are skipped automatically.

### 4c. Floor snapshot
Lay **board 0** flat on the floor where **all cameras can see it** (for 2 phones on adjacent sides of the box, that's the corner between them). Press **Snap: floor calib**. Board 0's printed top-left corner becomes the world origin.

> For more phones later, put more boards around the box (each must be seen by at least one camera), measure where each lies relative to board 0, and enter `x`, `y`, `yaw_deg` in `boards.json` (remove the `"TODO"` key). Every camera only needs to see one board.

### 4d. Compute and load
```
adb pull /sdcard/Android/data/com.camstitch/files/snapshots ./snapshots
python calibrate.py --snapshots snapshots --boards boards/boards.json --out calibration.json
```
Check the output:
- lens RMS should be **< 1 px**
- floor reprojection should be **< 2 px**
- the camera positions should look plausible (metres, Z = height above the floor)

Open `preview_topdown.png`: the board must look sharp, with no doubled edges where cameras overlap.

Load the calibration on the hub, either way:
```
adb push calibration.json /sdcard/Android/data/com.camstitch/files/calibration.json
```
or copy the file to the phone and press **Load calibration**. Then press **View** to switch to **Top-down** / **3D orbit**.

Useful knobs in `calibration.json`:
- `bowl.floor_radius`: how far the flat floor extends before the walls curve up. Objects at about that distance look least distorted.
- `view.feather_px`: seam blending width.
- per camera `gain`: [r, g, b] to equalise colour between phones.

## 5. Record stitched videos
On the hub, switch **View** to Top-down or 3D orbit and press **● Rec**.
- Recording stops by itself after **10 s**, or press **■ Stop**. You can orbit and zoom while recording.
- Only the stitched picture is recorded, not the stats or buttons. The video has the screen's shape, up to 1920 px wide, at 30 fps.
- Videos are saved as `Movies/CamStitch/CamStitch_YYYYMMDD_HHMMSS.mp4`, so they also appear in the phone's Gallery.
- **Videos (N)** opens the list. Each video has **Play** (in-app), **Export** (Android share sheet: Drive, WhatsApp, Gmail, Files, Nearby Share…) and **Delete** (asks first).

## 6. Check your Python setup
```
python tools/synthetic_test.py
```
This renders fake snapshots from two virtual phones (one normal lens, one fisheye) with known poses, calibrates them, and checks the result. It was verified with OpenCV 4.10 and 5.0: position error ≈ 1–2 mm, rotation error < 0.4°.

---

## Known limits of this POC
- **Parallax:** anything that sticks up off the floor smears near seams. Car systems have the same issue; it gets fixed with depth later.
- **TCP transport:** fine on a clean hotspot. Under interference, the hub drops to the next keyframe rather than lagging. UDP/RTP is the next upgrade.
- **Heat:** the hub encodes its own camera, decodes N streams and renders at 60 fps. Keep it on a charger. If fps drops, lower the resolution to 960x720.
- **Sync:** frames are shown as they arrive (fine for a slowly changing scene). Every frame already carries a hub-clock capture timestamp, so frame-accurate alignment can be added for the outside-in phase.
- **Physical lens selection:** some phones refuse to stream a physical lens directly. If the camera session fails, try the "zoom 0.5x" entry or the plain camera.

## Code map
| File | What |
|---|---|
| `MainActivity.kt` | launcher: name, lens, resolution, hub IP |
| `SenderSession.kt` | camera → encoder → TCP, clock sync, mDNS discovery |
| `CameraUtil.kt` | lens listing, Camera2 control (OIS/EIS off, locks) |
| `HubActivity.kt` | hub UI, overlay stats, touch, calibration loading |
| `HubServer.kt` | accepts senders, decodes each stream to a GPU texture |
| `HubRenderer.kt` | feed grid, stitched top-down / 3D bowl, snapshots |
| `Shaders.kt` | GLSL, including the per-pixel multi-camera projection |
| `Calibration.kt` | calibration.json model + bowl mesh |
| `Protocol.kt` | wire format |
| `tools/` | board generator, calibration, PC preview, self-test |
