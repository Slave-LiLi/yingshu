"""Compare production Java decoding against frozen real ONNX detection fixtures."""
from pathlib import Path
import argparse
import os
from collections import Counter
import hashlib
import json
import subprocess
import numpy as np


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('fixture_manifest', type=Path)
    parser.add_argument('--jdk', type=Path, default=os.environ.get('JAVA_HOME'),
                        required=not bool(os.environ.get('JAVA_HOME')), help='JDK root; defaults to JAVA_HOME')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    fixture = json.loads(args.fixture_manifest.read_text(encoding='utf-8-sig'))
    binary = args.fixture_manifest.parent / fixture['file']
    assert fixture['encoding'] == 'little_endian_float32'
    assert fixture['shape'] == [len(fixture['cases']), 300, 6]
    classes = root / 'build/model-pipeline-tests'
    classes.mkdir(parents=True, exist_ok=True)
    sources = root / 'app/src/main/java/io/yingshu/framekey'
    subprocess.run([str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '--release', '8', '-Xlint:-options',
                    '-d', str(classes), str(sources / 'ImageTensor.java'), str(sources / 'ModelPostprocess.java'),
                    str(root / 'tests/OpticDetectionFixtureRunner.java')], check=True)
    guns = classes / 'optic-detection-guns.txt'
    guns.write_text('\n'.join(x['gun'] for x in fixture['cases'])+'\n', encoding='utf-8')
    output = classes / 'optic-detection-java.tsv'
    subprocess.run([str(args.jdk / 'bin/java.exe'), '-cp', str(classes), 'OpticDetectionFixtureRunner',
                    str(binary), str(guns), str(output)], check=True)
    actual = [line.split('\t') for line in output.read_text(encoding='utf-8').splitlines()]
    labels = ['none', 'one_x', 'two_x', 'three_x', 'four_x']
    assert len(actual) == len(fixture['cases'])
    for index, (row, expected) in enumerate(zip(actual, fixture['cases'])):
        assert int(row[0]) == index
        assert row[1] == expected['state'], (expected['id'], 'state', row[1], expected['state'])
        assert np.float32(row[2]) == np.float32(expected['confidence']), (expected['id'], 'confidence')
        class_id = labels.index(expected['state']) if expected['state'] in labels else -1
        assert int(row[3]) == class_id
        reason = fixture.get('reasons_mapping', {}).get(expected['reason'], expected['reason'])
        assert row[4] == reason, (expected['id'], 'reason', row[4], reason)
        box = expected['box'] if class_id >= 0 else [0, 0, 0, 0]
        assert np.array_equal(np.asarray(row[5:9], dtype=np.float32), np.asarray(box, dtype=np.float32)), (expected['id'], 'box')
    report = {'passed': True, 'cases': len(actual), 'scope': 'Postprocessing equivalence only; not business acceptance.',
              'detector_sha256': fixture['detector_sha256'], 'fixture_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
              'states': dict(Counter(x['state'] for x in fixture['cases']))}
    (classes / 'optic-detector-selection-report.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
