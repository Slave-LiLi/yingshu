# SPDX-License-Identifier: AGPL-3.0-only
"""Continue training the editable v6 checkpoint using your own labeled data.

Defaults reproduce the v6 fine-tuning options, not its unavailable input data
or earlier checkpoint. No exact retraining or recognition-quality claim is made.
"""
from pathlib import Path
import argparse
from common import CLASSES, CHECKPOINT, ROOT, check_checkpoint, check_classes, configure, save_json, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', required=True, type=Path, help='Your YOLO dataset YAML, with the exact class order')
    parser.add_argument('--output', required=True, type=Path, help='New output directory; existing paths are rejected')
    parser.add_argument('--checkpoint', type=Path, default=CHECKPOINT, help='Trusted editable 13-class checkpoint')
    parser.add_argument('--device', default='0', help='Ultralytics device selector, e.g. 0 or cpu')
    parser.add_argument('--epochs', type=int, default=60)
    parser.add_argument('--batch', type=int, default=8)
    args = parser.parse_args()
    checkpoint = check_checkpoint(args.checkpoint)
    data, output = args.data.resolve(), args.output.resolve()
    if not data.is_file():
        raise FileNotFoundError(data)
    if output.exists():
        raise FileExistsError('Choose a new output directory: ' + str(output))
    if args.epochs < 1 or args.batch < 1:
        raise ValueError('epochs and batch must be positive')
    configure()
    import torch
    from ultralytics import YOLO
    from ultralytics.utils import YAML
    names = YAML.load(str(data)).get('names')
    names = [names[i] for i in range(len(names))] if isinstance(names, dict) else names
    if names != CLASSES:
        raise ValueError('Dataset names must exactly match training/data.example.yaml')
    parameters = __import__('json').loads((ROOT / 'training/hyperparameters-v6.json').read_text())
    parameters.update(epochs=args.epochs, batch=args.batch, device=args.device)
    torch.set_num_threads(4)
    model = YOLO(str(checkpoint))
    check_classes(model)
    result = model.train(data=str(data), project=str(output.parent), name=output.name,
                         exist_ok=False, **parameters)
    if Path(model.trainer.save_dir).resolve() != output:
        raise RuntimeError('Unexpected output directory; inspect the trainer result')
    save_json(output / 'yingshu-training-provenance.json', {
        'input_checkpoint_sha256': sha(checkpoint), 'dataset_yaml_sha256': sha(data),
        'class_order': CLASSES, 'parameters': parameters,
        'metrics': {str(k): float(v) for k, v in result.results_dict.items()},
        'scope': 'User-supplied dataset; continuation from the selected checkpoint, not exact historical retraining.'})


if __name__ == '__main__':
    main()
