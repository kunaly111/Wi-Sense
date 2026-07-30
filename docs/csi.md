# Wi-Sense CSI Pipeline — Status & Handoff

**Last updated:** 2026-07-30
**Purpose of this doc:** carry full context into a new chat session without
re-deriving anything. Read this first before touching any CSI-related code.

---

## 0. The goal

Wi-Sense senses human presence/motion/fall via WiFi CSI (Channel State
Information) using two ESP32 boards, then classifies it — currently on a PC,
eventually on-device (TinyML on the ESP32-S3 RX board) so it can drive the
existing fall-emergency/relay/OLED hardware directly.

**Hardware (already done, hardware-verified, not part of this rework):**
- **TX** (`firmware/csi_send`, plain ESP32): sends CSI-triggering ESP-NOW
  traffic at 100 Hz, channel 11, HT40. CSI-only, no other logic.
- **RX** (`firmware/csi_recv`, ESP32-S3-DevKitC-1 N16R8): owns *everything*
  else — receives CSI, owns OLED/relay/LDR/FSR/fall-emergency state
  machine/buzzer/LED/button/servo, and triggers the user's phone over BLE
  for the emergency camera feed. See `wisense_two_board_architecture`
  memory / `docs/handoff.md` for that history — not touched in this rework.

**What this rework is about:** live classification accuracy is bad. The
whole path from CSI capture → PC preprocessing → training → live inference
was root-caused and is being fixed in phases, *without* re-collecting the
existing dataset from scratch (explicit user constraint).

---

## 1. The phased plan

| Phase | What | Status |
|---|---|---|
| 0 | Firmware transport hardening (CSI frame integrity) | ✅ **DONE** |
| 1 | Dataset triage (quarantine bad files, add missing tooling) | ✅ **DONE** |
| 2 | Preprocessing rework (per-session baseline, timestamp windowing) | 🔶 **IN PROGRESS** |
| 3 | Live inference parity (hysteresis, streaming QC gates) | ⬜ not started |
| 4 | Validate the existing sklearn cascade with Phase 2/3 fixes | ⬜ not started |
| 5 | Swap model to a TinyML-deployable one, wire into firmware | ⬜ not started |

Rule: don't skip ahead — later phases assume earlier ones' fixes are in
place. Full original root-cause investigation (with file:line references)
lives in the `wisense_csi_pipeline_rework` memory file if deeper "why" is
ever needed; this doc only summarizes outcomes.

---

## 2. Phase 0 — Firmware transport hardening (DONE, verified on hardware)

**Files touched:** `firmware/csi_recv/main/app_main.c`,
`firmware/csi_recv/main/csi_binary_proto.h`,
`firmware/csi_recv/sdkconfig.defaults`, `firmware/csi_recv/partitions.csv`
(new), `python/proto/csi_binary_proto.py`,
`python/capture/capture_csi_binary.py`, `python/live/live_cascade_detect.py`.

**What changed:**
1. **Wire protocol bumped to v2** — header gained an RX-local monotonic
   `seq` + CRC16/XMODEM + self-describing `payload_len` (v1 had *no* way to
   detect in-flight corruption). `sig_len` widened `uint8_t`→`uint16_t`
   (was silently wrapping — hardware field is 12 bits).
2. **CSI callback no longer blocks on write()** — frames go through a
   FreeRTOS queue to a dedicated `csi_uart_writer_task`; all `ESP_LOG*`
   output shares a mutex with that task so a log line can never interleave
   mid-frame.
3. **Log level dropped INFO→ERROR** (defense in depth).
4. **Partition/flash-size fixed**: board was silently building against
   IDF's 2MB-flash default instead of the real 16MB (N16R8), leaving only a
   1MB, ~96%-full app partition. Now 16MB flash + 4MB app partition (77%
   free) via a new `partitions.csv`.
5. **THE BIG FIND**: ESP-IDF's USB-Serial-JTAG driver defaults to
   `ESP_LINE_ENDINGS_CRLF` on TX — silently inserting an extra `0x0D` byte
   before every `0x0A` byte in the binary stream. This was corrupting
   **~70% of frames**. Fix is one line:
   `usb_serial_jtag_vfs_set_tx_line_endings(ESP_LINE_ENDINGS_LF)` at the top
   of `app_main()`. **Confirmed on real hardware:** firmware-internal loss
   went from ~68-70% → **0.00%**, effective capture rate ~27 Hz → **~81 Hz**
   (TX sends at 100 Hz; remaining ~10% loss is normal over-the-air
   ESP-NOW/RF loss, now correctly isolated from the transport bug).
   BLE/WiFi radio coexistence was tested as an alternative hypothesis and
   **disproven** (disabling BLE entirely made no difference).
6. **Checked against the historical dataset** (267 files) — effective
   capture rates were already 70-90 Hz throughout, i.e. this bug does
   **not** appear to have significantly corrupted the existing dataset. It
   matters going forward for any *new* captures on this board, not as an
   explanation for past bad accuracy.

Board is currently flashed with this final, correct firmware.

---

## 3. Phase 1 — Dataset triage (DONE)

### 3.1 Files quarantined (moved to sibling `quarantine/` dirs, `.csv`+`.log` pair)
- `presence/almirah/s008_almirah.csv` (0 packets)
- `presence/bed_corner/s001_bed_corner.csv` (0 packets)
- `fall_v2/fall_center/s010_fall_center.csv` (genuine transport corruption —
  `.log` file *larger* than its data file, 103% ratio)
- `empty_fan_on/live_empty_20260725_203050_01.csv` and `_16.csv` (0 usable
  windows after preprocessing, 70.2%/65.4% drop)
- (A 4th originally-flagged file, `motion/pace_door_center/s009_...csv`,
  never existed on disk — nothing to move.)

### 3.2 Bug fixed: quarantine wasn't actually excluding files
`preprocess_csi.py`'s `discover_capture_files()` had **no `quarantine` path
exclusion** for presence/motion/fall (only the empty-room loader had one).
Fixed — verified 225 files now discovered, correctly excluding all
quarantined ones.

### 3.3 Near-miss: almost over-quarantined 14 good files
Ran `collect/validate_csi_captures.py`'s audit blindly across whole
presence directories — it flagged 14 healthy files as FAIL. Caught and
reverted before further damage. Root cause was a real bug (next item).

### 3.4 Investigated and reverted a preprocessing filter "fix"
Found a genuine bug in `is_corrupt_iq_packet`/`has_parse_artifact`'s
"artifact plateau" check (`np.any()` instead of requiring a real fraction
of subcarriers — was discarding 50-70% of packets across *every* class).
Fixed it, then also found the spike/mean/jump amplitude caps were
miscalibrated for this dataset (assumed a narrow "5-120" range; real data
reaches into the tens of thousands due to a genuine RF characteristic —
subcarriers near the HT40 channel edge are inherently noisier, confirmed
identical in empty vs. occupied rooms, not corruption).

**Loosening these thresholds was tried, measured end-to-end via full
retrain, and found to hurt, not help:**

| Config | Occupied F1 | Motion F1 | Fall F1 | Fall FP rate |
|---|---|---|---|---|
| Original (pre-session) | 0.995 | 0.943 | 0.346 | 0.005 |
| Fixed plateau + loosened caps | 0.999 | 0.857 | 0.310 | 0.0057 |
| Fixed plateau + tight caps (hybrid) | 0.994 | 0.910 | 0.235 | 0.0201 |
| **Fully reverted + 3 quarantined files** | **0.998** | **0.927** | **0.236** | **0.0171** |

**Everything was reverted** — `preprocess_csi.py` is now byte-identical to
its pre-session state (verified via `git diff`) except the quarantine-path
fix from 3.2, which stays. **Do not redo this loosening without new
evidence** — properly fixing the underlying issue would need
per-subcarrier normalization, not a threshold change (not attempted,
bigger scope).

**Important side finding:** fall's F1 swung 0.346→0.310→0.235→0.236 across
runs that only differ in thresholds/one file. Fall detection has only
~640-670 positive training windows — it's not just weak, it's *inherently
unstable to measure*. Treat any single fall F1 number with skepticism until
the fall dataset is much bigger.

**Current model state:** `models/csi_cascade.joblib` /
`preprocess_manifest.csv` reflect the reverted code + all quarantined
files. This is the correct baseline going forward — **don't compare future
work against the old 0.995/0.943/0.346 numbers**, they included corrupted
data.

### 3.5 `environment_notes` field — added
Free-text `--environment-notes` CLI flag added to all 4 base collect
scripts (`collect_empty_room.py`, `collect_presence.py`,
`collect_motion.py`, `collect_fall.py`) and all 3 guided-session wrappers.
Recorded in the manifest CSV and session `.log`. Directly targets the
bag/environment-confound risk (see §5) for future recordings.

### 3.6 Drop/resync stats persistence — added
`capture_csi_binary.py` gained a `--stats-out PATH` flag (writes
`CaptureValidator.stats_dict()` as JSON — needed because collect scripts
run it as a subprocess and previously couldn't see these stats at all).
Wired into all 4 base collect scripts: new manifest columns
`resync_bytes`, `tx_missing_pct`, `tx_resets`, `uart_missing_pct`,
`uart_resets`, plus the preprocessing-level `quality`/`drop_pct`/
`kept_packets` columns (via `validate_csi_captures.audit_file()`).
**Deliberately no auto-quarantine** for presence/motion/fall (unlike
empty-room) — recorded only, reviewed by a human, given the 3.3 near-miss.

### 3.7 Stale `quality_report.csv` — regenerated
Was referencing nonexistent `s001..s020_empty.csv`. Regenerated against
the real `live_empty_*.csv` files (must pass `--dir`/`--glob` explicitly —
script defaults point at `data/raw`, not `data/dataset`, a pre-existing
path mismatch elsewhere in this codebase too).

### 3.8 ✅ Done — matched bag-removed pair captured (2026-07-30)
Captured back-to-back with the bag physically removed, same session:
- **Empty room** (`data/dataset/empty_fan_on/`): `s001`, `s003`–`s006_empty.csv`
  — 5 good-quality windows, ~25,400 raw packets total. (A `s002_empty.csv`
  from an interrupted first attempt was quarantined — only 370 raw packets,
  failed the standard `too_few_raw_packets` audit; unrelated to the bag
  test itself.)
- **Presence** (`data/dataset/presence/room_center/`): `s009`–`s012_room_center.csv`
  — 4 good-quality windows, ~19,600 raw packets total, all `good` quality,
  drop_pct ≈0-1%.

Both sides recorded with `--environment-notes "...bag removed"` for
traceability in the manifest/session log.

**Feature-level comparison done (2026-07-30)**, reusing the existing
baseline/windowing/`extract_features()` pipeline unchanged (one-off script,
not part of the codebase): compared per-window 75-dim feature vectors
(Cohen's d) across three pairs —
- **A** presence/room_center, bag-present (07-13) vs bag-removed (07-30):
  mean \|d\| = **1.68**, dominated by mean amplitude on the high-index
  (HT40 channel-edge) subcarriers.
- **B** (control) old presence sessions split in half, no bag change, no
  time gap: mean \|d\| = **0.37**.
- **C** (control) empty room 07-25 vs 07-30, bag-free both sides, ~2 weeks
  apart: mean \|d\| = **1.66**, dominated by variance on low-mid
  subcarriers (different features than A).

**Verdict: inconclusive.** A and C are almost the same magnitude, and both
dwarf B — meaning a shift this large shows up just from 2+ weeks of time
elapsed between sessions, with no bag involved at all (C). The bag-removed
presence comparison (A) can't be distinguished from ordinary session/time
drift with this data, because every available comparison confounds "bag
changed" with "time elapsed" — there's no pair that holds time constant
and varies only the bag. (Caveat cutting the other way: the reference
baseline is itself built from the 07-25 empty files, so C's own "old" side
is partly self-referential and may understate true time-drift — doesn't
change the inconclusive verdict, just a reason not to over-read C either.)
The existing model's occupied-stage prediction was saturated at 100% for
both old and new room_center data — no signal there, ceiling effect.

**Follow-up same-day bag-IN/bag-OUT A/B test was proposed** (record
room_center with the bag in, then immediately with it removed, no time gap,
to cleanly isolate the bag variable) but **blocked — bag not currently
available**. **Explicit decision (2026-07-30): skip this test, resume
Phase 2 instead.** The bag confound remains an open, untested hypothesis;
revisit the same-day A/B whenever the bag is available again.

---

## 4. Phases 2-5

**Phase 2 (preprocessing rework)** — 🔶 in progress as of 2026-07-30:

### 4.1 ✅ Done — per-session/per-file baseline (sub-item 1 of 3)
`apply_baseline`/`compute_reference_baseline` in `preprocess_csi.py` used to
subtract one **global, permanent** empty-room reference spectrum for every
capture regardless of when it (or the reference) was recorded — the
suspected mechanism for the bag/environment confound baking into the
"occupied" signal.

**Added a third `--baseline-mode session_match`** (alongside the existing
`empty_room_reference` default and `per_file`): for each capture file,
resolve its recording date (embedded filename date → sibling manifest CSV
lookup via `file`/`started_at` columns → file mtime fallback); if any
`empty_fan_on` file shares that exact calendar date, baseline against only
that day's empty reference, else fall back to the existing global reference
unchanged. New `capture_date_for_file()`, `group_empty_paths_by_date()`,
`compute_reference_baseline_for_paths()`, `resolve_session_baseline()`;
`build_split_memmap()` now resolves baseline per-file and records a new
`baseline_source` manifest column (`global` or `session:YYYY-MM-DD`).
`DEFAULT_BASELINE_MODE` left as `empty_room_reference` — `session_match` is
opt-in pending the decision in the table below. No changes to
`live_cascade_detect.py`/`preprocess_config.json` (live still uses the
single global baseline; out of scope this pass).

**Correction found during implementation:** the original assumption ("no
historical data has a same-day empty reference, so this can't affect old
data") was wrong for **fall** — all 44 historical fall files are dated
2026-07-17, and a same-day empty set exists for that date
(`live_empty_retrain_20260717_*`, 5 files) that the current global
reference already blends in with the 07-25 set. Presence (07-13) and
motion (07-13/14) genuinely have no same-day match, so the assumption held
for them. **Decision: fall is explicitly excluded from `session_match`**
(always uses global, unchanged) — its ~650 positive windows are too few for
a retrain comparison to reliably tell "helped" from "got lucky," and
there's no record of whether the 07-17 retrain empty set was bag-free.
Revisit fall's baseline separately later.

**Validated via full retrain** (`data/dataset` as of 2026-07-30, including
the new `presence/room_center` bag-removed pair and new `empty_fan_on`
capture from §3.8). Regression guard confirmed via `baseline_source`: 100%
of fall (44/44) and motion (72/72) files → `global` (unaffected); presence
50/54 historical files → `global`, and exactly the new 4 files
(`s009`-`s012_room_center.csv`) → `session:2026-07-30`, matched against the
new same-day `empty_fan_on` files — the concrete case this change targets.

Isolated the baseline-mode effect with a clean A/B (same file corpus, same
train/val split — confirmed identical window counts — only baseline mode
differs; fall numbers below moved between "original" and both new runs
purely from a train/val split RNG side-effect of adding new files
alphabetically before "fall", **not** from baseline mode — proven by fall's
F1/FP being byte-identical between the two new-corpus runs):

| | Original (pre-§3.8 corpus, global) | New corpus, global (control) | New corpus, session_match |
|---|---|---|---|
| Occupied F1 | 0.998 | 0.992 | **0.998** |
| Motion F1 | 0.927 | 0.936 | **0.940** |
| Fall F1 | 0.236 | 0.351 | 0.351 (excluded, unaffected) |
| Fall FP rate | 0.0171 | 0.0056 | 0.0056 (excluded, unaffected) |

**Result: session_match gives a small, real improvement** (Occupied +0.006,
Motion +0.004, driven by Occupied recall 0.986→0.999) **with zero
regressions**, isolated cleanly from the unrelated corpus-growth effect.

**Promoted 2026-07-30, with user sign-off:**
- `DEFAULT_BASELINE_MODE` flipped to `session_match` in `preprocess_csi.py`
  (was `empty_room_reference`).
- `models/csi_cascade.joblib`/`csi_cascade_report.json` replaced with the
  session_match-trained model/report (previously
  `models/csi_cascade_session_match.joblib`). **This is now the production
  model `live_cascade_detect.py` loads by default.**
- The prior production model backed up as
  `models/csi_cascade_pre_session_match_backup.joblib` (+ its report), in
  case a rollback is ever needed.
- `models/csi_cascade_global_control.joblib`/report kept as the comparison
  artifact documenting the control run above.
- `data/dataset/processed/` (used for this promotion) already reflects
  `session_match` output — no need to re-run preprocessing.

### 4.2-4.3 ⬜ Not started — remaining Phase 2 sub-items
- Windowing uses an *estimated* packet rate in training vs. the *real*
  hardware clock live — should use each packet's actual `local_timestamp`
  (already captured) consistently in both.
- Port the spike/MAD/window-validity filters to a streaming-compatible
  form so live inference can apply the same QC gates training does.

**Phase 3 (live inference parity)** — `live_cascade_detect.py`:
- No debounce/hysteresis for occupied/motion (only fall has `fall_streak`)
  — likely the single biggest live-accuracy issue, and the cheapest fix.
  `docs/LIVE_DETECTION.md` documents a hysteresis scheme that was never
  actually implemented — that doc is stale, `docs/handoff.md` is accurate.
- Missing streaming spike/MAD/window-validity gates (Phase 2 dependency).
- Calibration path (`--calibration-packets`) is disabled by default because
  it's buggy (uses raw unfiltered packets) — fix once Phase 2 lands.

**Phase 4** — re-validate the existing sklearn cascade with Phases 2+3
fixes in place, before touching model architecture at all.

**Phase 5 (TinyML)** — only after Phase 4 looks good:
- `train_cascade.py` currently trains sklearn `HistGradientBoostingClassifier`
  — cannot convert to TFLite. Needs a small Keras/quantizable model instead.
- Integration point already exists and is well-designed:
  `wisense_classifier_ops_t` in
  `firmware/components/wisense_classifier/include/wisense_classifier.h`,
  selected via `WISENSE_CLASSIFIER_TINYML` Kconfig (currently
  `depends on false` — flip once a backend exists). No other firmware file
  should need to change.
- Partition/flash headroom for this is already handled (Phase 0, §2).

---

## 5. Key context to not re-derive

- **Bag confound**: a small bag sat near the TX board during
  presence/motion/fall recordings (2026-07-13/17) but was removed before
  the newest empty-room reference recordings (2026-07-25). Suspected to
  bake into the "occupied" signal via the global baseline (§4, Phase 2). A
  matched bag-removed empty/presence pair was captured 2026-07-30 (§3.8) —
  data is ready, but the actual confound test needs Phase 2's per-session
  baseline first to be meaningful (comparing through the current global
  baseline wouldn't isolate the bag variable). Phase 2 is intentionally on
  hold for now.
- **Build env**: ESP-IDF via EIM, not `export.sh`. In PowerShell:
  `. "C:\Espressif\tools\Microsoft.v5.5.4.PowerShell_profile.ps1"` then
  `idf.py build` — must be in the same call (env vars don't persist across
  tool invocations in this harness).
- **Board**: ESP32-S3-DevKitC-1 N16R8, RX on COM13 (native USB-Serial-JTAG).
  TX is an ESP32-CAM-style board on COM3 (Silicon Labs CP210x bridge) when
  connected.
- **Preprocessing path gotcha**: `preprocess_csi.py`'s module-level
  `PRESENCE_DIR`/`MOTION_DIR`/etc. default to `data/raw/...`, but the real
  dataset lives at `data/dataset/...`. The actual CLI entry point
  (`--dataset-root`) rebinds these correctly; don't call
  `discover_capture_files()` directly without also setting
  `DATASET_ROOT`/`PRESENCE_DIR`/`MOTION_DIR`/`FALL_DIR`/`EMPTY_DIR` first.
- **Standard commands** (from `docs/handoff.md`, still accurate for the
  PC-side pipeline):
  ```powershell
  $env:PYTHONPATH = "python"
  python .\python\preprocess\preprocess_csi.py --dataset-root .\data\dataset --output-dir .\data\dataset\processed
  python .\python\train\train_cascade.py --train .\data\dataset\processed\train.npz --val .\data\dataset\processed\val.npz --fall-training fall-only
  python .\python\live\live_cascade_detect.py -p COM13
  ```

---

## 6. Suggested next step

Phase 1.8's matched recording pair is done (§3.8), and the bag-confound
question stays genuinely open (inconclusive feature-level test, same-day
A/B blocked on bag availability — revisit when the bag is available again).

**Phase 2 sub-item 1 (per-session baseline) is done and promoted** (§4.1,
2026-07-30) — `models/csi_cascade.joblib` is now the session_match-trained
model. Remaining Phase 2 work, each its own independently-validated pass:
- **Sub-item 2**: real-`local_timestamp`-consistent windowing (training vs.
  live currently use different timing sources — see §4.2-4.3). Watch for
  the legacy-file timer-jump gotcha already handled defensively in
  `process_file()` (`capture_duration_sec` gated to `live_empty_*` only) —
  any generalization needs a validity check, not a blanket switch.
- **Sub-item 3**: port spike/MAD/window-validity QC filters to a streaming
  form for live parity (Phase 3 dependency).
- Separately: fall's baseline was deliberately left untouched this pass
  (§4.1) — revisit whether `live_empty_retrain_20260717_*` is usable once
  its bag status can be determined.
