# SPDX-License-Identifier: AGPL-3.0-only
"""Shared class order and hashes for the editable frozen v5 model."""
from pathlib import Path
import hashlib
import json
import os

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ['VK47', 'R301', 'R99', 'NEMESIS', 'ALTERNATOR', 'LSTAR', 'PROWLER', 'CAR',
           'none', 'one_x', 'two_x', 'three_x', 'four_x']
CHECKPOINT_SHA = 'f0fecfc75104ee62f55421d22c31f6000a6209afe9baf875aedd44407bd9090d'
MODEL_SHA = '850d46265ff4f2cdeb0e27c545752571423292ee1522798c4f282b1af75f5af5'
MASK_SHA = '0f035302b617d2cb0c62b728a4158d2eae9f1df6f5215dd51f7447238e9cfaac'
CHECKPOINT = ROOT / 'training/weights/joint-v5.pt'
ASSETS = ROOT / 'app/src/main/assets/models'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8')


def configure():
    # Keep framework configuration and generated files out of the published source tree.
    os.environ.setdefault('YOLO_CONFIG_DIR', str(ROOT / 'build/ultralytics-settings'))
    os.environ.setdefault('WANDB_MODE', 'disabled')
    os.environ.setdefault('COMET_MODE', 'DISABLED')
    os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG', ':4096:8')
    os.environ.setdefault('YOLO_AUTOINSTALL', 'false')
    os.environ.setdefault('YOLO_OFFLINE', 'true')


def check_classes(model):
    if list(model.names.values()) != CLASSES:
        raise ValueError('Checkpoint class order must match the 13-class schema 6 model')


def check_checkpoint(path):
    path = Path(path).resolve()
    if not path.is_file():
        raise FileNotFoundError(path)
    if path == CHECKPOINT.resolve() and sha(path) != CHECKPOINT_SHA:
        raise ValueError('Bundled checkpoint hash mismatch')
    return path
