# SPDX-License-Identifier: AGPL-3.0-only
"""Export a trusted 13-class checkpoint in an isolated new directory.

Does not replace Android model assets or update their approved hashes.
"""
from pathlib import Path
import argparse
import shutil
from common import CHECKPOINT, CLASSES, check_checkpoint, check_classes, configure, save_json, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkpoint', type=Path, default=CHECKPOINT)
    parser.add_argument('--output', type=Path, required=True, help='New output directory')
    args = parser.parse_args()
    checkpoint = check_checkpoint(args.checkpoint)
    output = args.output.resolve()
    if output.exists():
        raise FileExistsError('Choose a new output directory: ' + str(output))
    configure()
    import torch
    import onnx
    import onnxruntime as ort
    from ultralytics import YOLO
    torch.set_num_threads(4)
    output.mkdir(parents=True)
    local_checkpoint = output / 'joint.pt'
    shutil.copy2(checkpoint, local_checkpoint)
    model = YOLO(str(local_checkpoint))
    check_classes(model)
    onnx_path = Path(model.export(format='onnx', imgsz=320, nms=False, dynamic=False,
                                 simplify=False, opset=17, device='cpu', batch=1))
    graph = onnx.load(str(onnx_path))
    onnx.checker.check_model(graph)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(onnx_path), options, providers=['CPUExecutionProvider'])
    input_shape, output_shape = session.get_inputs()[0].shape, session.get_outputs()[0].shape
    if input_shape != [1, 3, 320, 320] or output_shape != [1, 300, 6]:
        raise ValueError('Export does not match the Android schema 6 tensor contract')
    save_json(output / 'export-check.json', {
        'checkpoint_sha256': sha(checkpoint), 'onnx_sha256': sha(onnx_path),
        'class_order': CLASSES, 'input_shape': input_shape, 'output_shape': output_shape,
        'conv_nodes': sum(node.op_type == 'Conv' for node in graph.graph.node),
        'onnx_check': 'passed',
        'android_assets_modified': False,
        'scope': 'Shape/structure verification only; no labeled-dataset accuracy or phone benchmark.'})
    print(str(onnx_path))


if __name__ == '__main__':
    main()
