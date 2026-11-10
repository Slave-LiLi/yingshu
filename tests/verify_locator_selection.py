"""Deterministic logit fixtures compare the production Java/Python locator selection rules."""
from pathlib import Path
import argparse
import os
from collections import Counter
import json
import struct
import subprocess
import sys
import numpy as np


def background():
    result = np.zeros((21, 6), dtype=np.float32)
    result[:, 5] = 8
    return result


def row_probability(class_id, probability):
    values = np.full(6, (1-float(probability))/5, dtype=np.float64)
    values[class_id] = probability
    return np.log(values).astype(np.float32)


def cases():
    result = []
    def add(name, logits, gun='VK47', threshold=.8):
        result.append((name, gun, np.asarray(logits, dtype=np.float32), threshold))
    add('background', background())
    for gun in ('VK47', 'R301', 'R99'):
        for class_id in range(5):
            for index in (2, 10, 18):
                values = background(); values[index] = row_probability(class_id, .99)
                add(f'{gun}-class{class_id}-candidate{index}', values, gun)
    values = background(); values[2] = row_probability(1, .99); values[18] = values[2]
    add('same-class-tie-left', values)
    values = values.copy(); values[10] = values[2]
    add('same-class-tie-center', values)
    values = background(); values[2] = row_probability(1, .99); values[18] = row_probability(4, .99)
    add('different-class-tie', values)
    values = background(); values[2] = row_probability(4, .99); values[18] = row_probability(1, .83)
    add('R99-no-fallback', values, 'R99')
    for score in (.7999999, .8, .8000001):
        values = background(); values[10] = row_probability(0, score)
        add(f'threshold-{score}', values)
    for score in (.8499999, .85, .8500001):
        values = background(); values[2] = row_probability(1, .95); values[18] = row_probability(4, score)
        add(f'conflict-margin-{score}', values)
    values = background(); values[10] = np.log([.01, .48, .01, .01, .01, .48]).astype(np.float32)
    add('background-margin', values, 'R99', .4)
    values = background(); values[10] = row_probability(0, .99); values[20, 5] = np.nan
    add('invalid-NaN', values)
    values = background(); values[10, 5] = np.inf
    add('invalid-infinity', values)
    rng = np.random.default_rng(20261107)
    for i in range(512):
        add(f'random-{i}', rng.normal(0, 1+(i % 9), (21, 6)).astype(np.float32), ('VK47', 'R301', 'R99')[i % 3])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, default=os.environ.get('JAVA_HOME'),
                        required=not bool(os.environ.get('JAVA_HOME')), help='JDK root; defaults to JAVA_HOME')
    parser.add_argument('--locator-source', type=Path, required=True,
                        help='Historical training source directory containing optic_locator.py; not bundled')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not (args.locator_source / 'optic_locator.py').is_file():
        parser.error('--locator-source must contain the matching historical optic_locator.py')
    sys.path.insert(0, str(args.locator_source.resolve()))
    from optic_locator import stable_softmax, select_candidate
    classes = root / 'build/model-pipeline-tests'
    classes.mkdir(parents=True, exist_ok=True)
    sources = root / 'app/src/main/java/io/yingshu/framekey'
    subprocess.run([str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '--release', '8', '-Xlint:-options',
                    '-d', str(classes), str(sources / 'ImageTensor.java'), str(sources / 'ModelPostprocess.java'),
                    str(root / 'tests/LocatorFixtureRunner.java')], check=True)
    metadata = {'classes': ['none', 'one_x', 'two_x', 'three_x', 'four_x', 'background'],
                'crops': {'VK47': [263,555,299,590], 'R301': [295,555,331,590], 'R99': [285,551,330,590]},
                'search': {'min_offset_x': -40, 'max_offset_x': 40, 'step_x': 4},
                'selection': {'min_margin': .1, 'conflict_threshold': .8, 'conflict_margin': .1},
                'unknown_threshold': .8}
    fixture_cases = cases()
    expected = []
    binary = classes / 'locator-selection-fixtures.bin'
    with binary.open('wb') as stream:
        stream.write(struct.pack('>i', len(fixture_cases)))
        for name, gun, logits, threshold in fixture_cases:
            encoded = gun.encode('ascii')
            stream.write(struct.pack('>H', len(encoded))); stream.write(encoded)
            stream.write(struct.pack('>ffff', threshold, .1, .8, .1))
            stream.write(logits.astype('>f4').tobytes())
            probabilities = stable_softmax(logits) if np.isfinite(logits).all() else logits
            expected.append(select_candidate(probabilities, gun, {**metadata, 'unknown_threshold': threshold}))
    output = classes / 'locator-selection-java.tsv'
    subprocess.run([str(args.jdk / 'bin/java.exe'), '-cp', str(classes), 'LocatorFixtureRunner', str(binary), str(output)], check=True)
    actual = [line.split('\t') for line in output.read_text(encoding='utf-8').splitlines()]
    assert len(actual) == len(expected)
    for index, (row, want) in enumerate(zip(actual, expected)):
        name = fixture_cases[index][0]
        assert int(row[0]) == index
        got = {'state': row[1], 'confidence': np.float32(row[2]), 'locator_confidence': np.float32(row[3]),
               'offset_x': int(row[4]), 'candidate_index': int(row[5]), 'reason': row[6]}
        for key in got:
            value = np.float32(want[key]) if key in ('confidence', 'locator_confidence') else want[key]
            assert got[key] == value, (name, key, got[key], value)
    report = {'passed': True, 'cases': len(expected), 'fields_compared': list(got),
              'reasons': dict(Counter(x['reason'] for x in expected)), 'seed': 20261107}
    (classes / 'locator-selection-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
