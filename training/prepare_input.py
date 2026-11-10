# SPDX-License-Identifier: AGPL-3.0-only
"""Convert one user-provided frame to the exact masked/padded training layout."""
from pathlib import Path
import argparse
from common import ASSETS, MASK_SHA, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path, help='New PNG image path')
    parser.add_argument('--normalize-1080p', action='store_true', help='Explicitly resize the full source frame using INTER_LINEAR')
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    if args.output.suffix.lower() != '.png':
        raise ValueError('Output must be a lossless .png')
    import cv2
    import numpy as np
    from PIL import Image
    frame = cv2.imdecode(np.fromfile(args.input, dtype=np.uint8), cv2.IMREAD_COLOR)
    if frame is None:
        raise ValueError('Cannot decode input frame')
    if frame.shape[:2] != (1080, 1920):
        if not args.normalize_1080p:
            raise ValueError('Expected 1920x1080; resizing requires --normalize-1080p')
        frame = cv2.resize(frame, (1920, 1080), interpolation=cv2.INTER_LINEAR)
    rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
    panel = rgb[936:1064, 1472:1792].copy()
    mask_file = ASSETS / 'panel-mask.bin'
    if sha(mask_file) != MASK_SHA:
        raise ValueError('Frozen mask hash mismatch')
    mask = np.frombuffer(mask_file.read_bytes(), np.uint8).reshape(128, 320)
    panel[mask == 0] = 0
    result = np.full((320, 320, 3), 114, np.uint8)
    result[96:224] = panel
    args.output.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(result).save(args.output)


if __name__ == '__main__':
    main()
