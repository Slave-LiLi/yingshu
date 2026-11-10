"""Differential check of Java tensors against the training pipeline's OpenCV/Pillow.

Run with an environment containing numpy, opencv-python and Pillow. No model or GPU needed.
"""
from pathlib import Path
import argparse
import os
import json
import hashlib
import subprocess
import numpy as np
import cv2
from PIL import Image, __version__ as pillow_version


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, default=os.environ.get('JAVA_HOME'),
                        required=not bool(os.environ.get('JAVA_HOME')), help='JDK root; defaults to JAVA_HOME')
    parser.add_argument('--mask', type=Path, help='Optionally verify the frozen production binary mask too')
    parser.add_argument('--expected-mask-sha256', help='Fail if the supplied production mask hash differs')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    classes = root / 'build/model-pipeline-tests'
    fixtures = classes / 'fixtures'
    classes.mkdir(parents=True, exist_ok=True)
    sources = root / 'app/src/main/java/io/yingshu/framekey'
    subprocess.run([str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '--release', '8',
                    '-Xlint:-options', '-d', str(classes), str(sources / 'ImageTensor.java'),
                    str(sources / 'ModelPostprocess.java'), str(sources / 'InferenceRegion.java'),
                    str(root / 'tests/ModelPipelineTest.java')], check=True)
    java_args = [str(args.jdk / 'bin/java.exe'), '-cp', str(classes), 'ModelPipelineTest', str(fixtures)]
    if args.mask is not None:
        java_args.append(str(args.mask.resolve()))
    subprocess.run(java_args, check=True)
    y, x = np.indices((640, 640))
    rgb = np.stack(((x*73+y*29+(x*y)%251) & 255,
                    (x*11+y*191+(x^y)*17) & 255,
                    (x*157+y*7+(x*y)%199) & 255), -1).astype(np.uint8)
    expected = {'gun': cv2.resize(rgb, (320, 320), interpolation=cv2.INTER_LINEAR)}
    pil = Image.fromarray(rgb)
    for name, crop in [('vk', (263, 555, 299, 590)), ('r301', (295, 555, 331, 590)), ('r99', (285, 551, 330, 590))]:
        expected[name] = np.asarray(pil.crop(crop).resize((64, 64), Image.Resampling.BILINEAR))
        expected['locator-' + name] = np.stack([
            np.asarray(pil.crop((crop[0]+dx, crop[1], crop[2]+dx, crop[3]))
                       .resize((64, 64), Image.Resampling.BILINEAR))
            for dx in range(-40, 41, 4)])
    panel = pil.crop((192, 496, 512, 624))
    expected['panel-gun'] = np.pad(np.asarray(panel), ((96,96),(0,0),(0,0)), constant_values=114)
    expected['panel-band'] = np.asarray(panel.crop((8,54,200,100)).resize((192,64), Image.Resampling.BILINEAR))
    panel_y, panel_x = np.indices((128, 320))
    keep_mask = ((panel_x//13 + panel_y//7) % 3 != 0).astype(np.uint8)
    masked_panel = np.asarray(panel) * keep_mask[:, :, None]
    expected['panel-masked-gun'] = np.pad(masked_panel, ((96,96),(0,0),(0,0)), constant_values=114)
    expected['panel-masked-band'] = np.asarray(Image.fromarray(masked_panel).crop((8,54,200,100))
                                              .resize((192,64), Image.Resampling.BILINEAR))
    report = {'pillow': pillow_version, 'opencv': cv2.__version__, 'numpy': np.__version__, 'fixtures': {}}
    if args.mask is not None:
        binary = args.mask.read_bytes()
        digest = hashlib.sha256(binary).hexdigest()
        if args.expected_mask_sha256 is not None:
            assert digest == args.expected_mask_sha256.lower(), ('production mask hash mismatch', digest)
        binary = np.frombuffer(binary, dtype=np.uint8)
        assert binary.size == 320*128 and np.isin(binary, (0, 1)).all()
        actual_panel = np.asarray(panel) * binary.reshape(128,320,1)
        expected['actual-mask-panel-gun'] = np.pad(actual_panel, ((96,96),(0,0),(0,0)), constant_values=114)
        expected['actual-mask-panel-band'] = np.asarray(Image.fromarray(actual_panel).crop((8,54,200,100))
                                                       .resize((192,64), Image.Resampling.BILINEAR))
        report['production_mask'] = {'sha256': digest, 'bytes': int(binary.size), 'kept_pixels': int(binary.sum())}
    for name, values in expected.items():
        order = (0, 3, 1, 2) if values.ndim == 4 else (2, 0, 1)
        target = values.astype(np.float32).transpose(order).reshape(-1) / np.float32(255)
        actual = np.fromfile(fixtures / f'{name}.f32', dtype='>f4')
        assert target.shape == actual.shape, (name, target.shape, actual.shape)
        difference = np.abs(actual - target)
        report['fixtures'][name] = {'elements': int(actual.size), 'max_abs_error': float(difference.max()),
                                    'unequal_elements': int(np.count_nonzero(actual != target))}
        assert np.array_equal(actual, target), (name, report['fixtures'][name])
    mixed = np.asarray(pil.crop((7, 11, 80, 44)).resize((17, 64), Image.Resampling.BILINEAR)).reshape(-1)
    actual = np.fromfile(fixtures / 'pil-mixed.rgb', dtype=np.uint8)
    assert np.array_equal(actual, mixed), ('PIL mixed up/downsampling', int(np.count_nonzero(actual != mixed)))
    report['fixtures']['pil_mixed'] = {'elements': int(actual.size), 'unequal_elements': 0}
    report['passed'] = True
    (classes / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
