#!/usr/bin/env python3
"""Run the trained CSI cascade on the binary serial stream in real time."""

import argparse
import json
import sys
from collections import deque
from pathlib import Path

import joblib
import numpy as np
import serial

_PYTHON_ROOT = Path(__file__).resolve().parent.parent
if str(_PYTHON_ROOT) not in sys.path:
    sys.path.insert(0, str(_PYTHON_ROOT))

from capture.capture_csi_binary import BinaryStreamParser
from preprocess.preprocess_csi import (EXPECTED_LEN, MAX_NORM_PKT_ABS,
                                       extract_features, is_corrupt_iq_packet,
                                       parse_csi_amplitude, parse_csi_iq)


CSI_TIMESTAMP_WRAP_US = 1 << 32
MAX_SEQUENCE_GAP = 2


def timestamp_from_row(row):
    """Read ESP-IDF's wrapping uint32 CSI timestamp, if the frame has one."""
    try:
        return int(row['local_timestamp']) & (CSI_TIMESTAMP_WRAP_US - 1)
    except (KeyError, TypeError, ValueError):
        return None


class TimestampUnwrapper:
    """Turn wrapping CSI timestamps into a monotonic microsecond clock."""

    def __init__(self):
        self._raw = None
        self._elapsed = 0

    def push(self, timestamp):
        if timestamp is None:
            return None
        if self._raw is None:
            self._raw = timestamp
            return self._elapsed
        delta = (timestamp - self._raw) & (CSI_TIMESTAMP_WRAP_US - 1)
        self._raw = timestamp
        # A backwards jump greater than half the uint32 range is a receiver
        # reset/out-of-order packet, not a normal timestamp wrap.  Start a new
        # feature window rather than mixing unrelated CSI samples.
        if delta > CSI_TIMESTAMP_WRAP_US // 2:
            self._elapsed = 0
            return None
        self._elapsed += delta
        return self._elapsed


def starts_new_capture_segment(previous_id, current_id):
    """Match preprocessing's rule for genuine transmitter packet-loss gaps."""
    if previous_id is None or current_id is None:
        return False
    delta = (current_id - previous_id) & 0xFFFFFFFF
    # A delta of 3 means two packets were lost and is still allowed by the
    # preprocessing window rule.  Split only when more than two are missing.
    return delta == 0 or delta > MAX_SEQUENCE_GAP + 1


def load_runtime(model_path, config_path):
    bundle = joblib.load(model_path)
    config = json.loads(Path(config_path).read_text(encoding='utf-8'))
    if bundle.get('feature_dim') != config.get('feature_dim'):
        raise ValueError('model and preprocessing config use different feature dimensions')
    baseline = np.asarray(config['reference_baseline'], dtype=np.float32)
    return bundle, config, baseline


def raw_amplitude_from_row(row):
    """Convert one decoded serial frame into the pre-baseline amplitude."""
    try:
        if int(row.get('len', 0)) != EXPECTED_LEN:
            return None
        i_vals, q_vals = parse_csi_iq(row['data'], expected_len=EXPECTED_LEN)
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        return None
    if is_corrupt_iq_packet(i_vals, q_vals):
        return None
    amplitude = parse_csi_amplitude(row['data'], expected_len=EXPECTED_LEN)
    if amplitude is None:
        return None
    return amplitude.astype(np.float32)


def amplitude_from_row(row, baseline):
    """Apply the selected empty-room baseline to one valid CSI packet."""
    amplitude = raw_amplitude_from_row(row)
    if amplitude is None:
        return None
    normalized = amplitude - baseline
    if float(np.max(np.abs(normalized))) > MAX_NORM_PKT_ABS:
        return None
    return normalized.astype(np.float32)


def predict_label(bundle, feature, fall_streak, fall_consecutive):
    probabilities = {
        name: float(model.predict_proba(feature.reshape(1, -1))[0, 1])
        for name, model in bundle['models'].items()
    }
    thresholds = bundle['thresholds']
    fall_streak = fall_streak + 1 if probabilities['fall'] >= thresholds['fall'] else 0

    if fall_streak >= fall_consecutive:
        label = 'FALL'
    elif probabilities['occupied'] < thresholds['occupied']:
        label = 'EMPTY'
    elif probabilities['motion'] >= thresholds['motion']:
        label = 'MOTION'
    else:
        label = 'PRESENCE'
    return label, probabilities, fall_streak


def main():
    parser = argparse.ArgumentParser(description='Live CSI cascade detection')
    parser.add_argument('-p', '--port', required=True, help='Receiver serial port, e.g. COM13')
    parser.add_argument('-b', '--baud', type=int, default=921600)
    parser.add_argument('--model', default='models/csi_cascade.joblib')
    parser.add_argument('--config', default='data/dataset/processed/preprocess_config.json')
    parser.add_argument('--window-seconds', type=float, default=None,
                        help='CSI duration per feature window (default: preprocessing config)')
    parser.add_argument('--hop-seconds', type=float, default=0.25,
                        help='Minimum CSI time between predictions (default: 0.25)')
    parser.add_argument('--min-window-coverage', type=float, default=0.95,
                        help='Required fraction of the time window before prediction (default: 0.95)')
    parser.add_argument('--packet-window', action='store_true',
                        help='Use the old fixed-packet window for comparison only; not model-compatible')
    parser.add_argument('--hop-packets', type=int, default=22,
                        help='Prediction hop for --packet-window only (default: 22)')
    parser.add_argument('--calibration-packets', type=int, default=0,
                        help='Experimental session calibration; use 0 (saved training baseline) unless the model was trained with matching session baselines')
    parser.add_argument('--fall-consecutive', type=int, default=2,
                        help='Consecutive fall-positive predictions required for FALL')
    args = parser.parse_args()

    bundle, config, baseline = load_runtime(args.model, args.config)
    packet_count = int(config['target_seq_2s'])
    window_seconds = (
        float(config.get('window_2s_sec', 2.0))
        if args.window_seconds is None else args.window_seconds
    )
    if window_seconds <= 0 or args.hop_seconds <= 0:
        parser.error('--window-seconds and --hop-seconds must be positive')
    if not 0 < args.min_window_coverage <= 1:
        parser.error('--min-window-coverage must be in (0, 1]')
    window_us = int(round(window_seconds * 1_000_000))
    min_window_us = int(round(window_us * args.min_window_coverage))
    hop_us = int(round(args.hop_seconds * 1_000_000))
    expected_mac = config['expected_mac']
    buffer = deque(maxlen=packet_count) if args.packet_window else deque()
    stream = BinaryStreamParser(expected_mac=expected_mac, expected_len=EXPECTED_LEN)
    timestamp_clock = TimestampUnwrapper()
    accepted = rejected = since_prediction = fall_streak = 0
    previous_packet_id = None
    segment = 0
    last_prediction_us = None
    calibration = []
    calibrating = args.calibration_packets > 0

    print(f'Opening {args.port} at {args.baud} baud')
    if calibrating:
        print(f'Keep the room empty: calibrating from {args.calibration_packets} valid CSI packets...')
    else:
        print('Using the saved training baseline (the model-compatible default).')
    if args.packet_window:
        print(f'Legacy packet mode: waiting for {packet_count} valid CSI packets before first prediction...')
    else:
        print(
            f'Using timestamped {window_seconds:.2f}s CSI windows; '
            f'first prediction follows one continuous window.',
        )
    print('Close idf.py monitor or any other serial program first. Press Ctrl+C to stop.')

    try:
        with serial.Serial(args.port, args.baud, timeout=0.1) as ser:
            while True:
                chunk = ser.read(4096)
                if not chunk:
                    continue
                stream.feed(chunk)
                while True:
                    frame, _discarded = stream.pop_frame()
                    if frame is None:
                        break
                    columns, values = frame
                    row = dict(zip(columns, values))

                    timestamp_us = timestamp_clock.push(timestamp_from_row(row))
                    try:
                        packet_id = int(row['id'])
                    except (KeyError, TypeError, ValueError):
                        packet_id = None
                    if starts_new_capture_segment(previous_packet_id, packet_id):
                        segment += 1
                    previous_packet_id = packet_id
                    if not args.packet_window and timestamp_us is None:
                        # The time window must never bridge a receiver reset or
                        # an unparseable timestamp.  This mirrors the training
                        # path's exclusion of real capture gaps.
                        buffer.clear()
                        last_prediction_us = None
                        rejected += 1
                        continue

                    raw_amplitude = raw_amplitude_from_row(row)
                    if raw_amplitude is None:
                        rejected += 1
                        continue
                    if calibrating:
                        calibration.append(raw_amplitude)
                        if len(calibration) < args.calibration_packets:
                            continue
                        baseline = np.median(np.asarray(calibration), axis=0).astype(np.float32)
                        calibration.clear()
                        calibrating = False
                        buffer.clear()
                        last_prediction_us = None
                        print('Empty-room calibration complete. Detections active.', flush=True)
                        continue

                    amplitude = raw_amplitude - baseline
                    if float(np.max(np.abs(amplitude))) > MAX_NORM_PKT_ABS:
                        rejected += 1
                        continue
                    accepted += 1
                    if args.packet_window:
                        buffer.append(amplitude)
                        since_prediction += 1
                        if len(buffer) < packet_count or since_prediction < args.hop_packets:
                            continue
                        since_prediction = 0
                        feature_window = np.asarray(buffer, dtype=np.float32)
                        window_span = None
                    else:
                        buffer.append((timestamp_us, segment, amplitude))
                        while buffer and timestamp_us - buffer[0][0] > window_us:
                            buffer.popleft()
                        # Do not create a feature from samples separated by a
                        # true TX packet-loss gap.  Content-filtered samples do
                        # not change ``segment``, matching preprocessing.
                        while buffer and buffer[0][1] != segment:
                            buffer.popleft()
                        if len(buffer) < 2:
                            continue
                        window_span = timestamp_us - buffer[0][0]
                        # With a rolling buffer capped at ``window_us``, the
                        # newest sample normally lands one CSI period short of
                        # the exact boundary because the old sample is evicted
                        # first.  Accept a nearly full window instead of
                        # waiting forever for an impossible exact span.
                        if window_span < min_window_us:
                            continue
                        if (last_prediction_us is not None
                                and timestamp_us - last_prediction_us < hop_us):
                            continue
                        last_prediction_us = timestamp_us
                        feature_window = np.asarray(
                            [sample[2] for sample in buffer], dtype=np.float32,
                        )

                    feature = extract_features(feature_window)
                    label, prob, fall_streak = predict_label(
                        bundle, feature, fall_streak, args.fall_consecutive,
                    )
                    window_info = (
                        f'samples={feature_window.shape[0]} '
                        f'span={window_span / 1_000_000:.2f}s '
                        if window_span is not None else
                        f'samples={feature_window.shape[0]} legacy-packet-window '
                    )
                    print(
                        f'{label:8} | occ={prob["occupied"]:.2f} '
                        f'mot={prob["motion"]:.2f} fall={prob["fall"]:.2f} '
                        f'{window_info}packets={accepted} rejected={rejected}',
                        flush=True,
                    )
    except serial.SerialException as exc:
        print(f'serial error: {exc}', file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('\nstopped')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
