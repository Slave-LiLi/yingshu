"""Fail the APK build if the frozen joint-model contract or assets disagree."""
from pathlib import Path
import hashlib
import json
import math
import sys

def verify(root):
    expected_files={'joint.onnx','panel-mask.bin','manifest.json'}
    if {p.name for p in root.iterdir()} != expected_files:
        raise ValueError('Joint APK must contain exactly joint.onnx, panel-mask.bin and manifest.json')
    m=json.loads((root/'manifest.json').read_text(encoding='utf8'))
    assert m['schema_version']==6, 'Joint manifest schema must be 6'
    assert m['frame_size']==[1920,1080] and m['roi_xywh']==[1472,936,320,128], 'Unexpected ROI'
    config=m['joint_detector']
    assert config['file']=='joint.onnx'
    assert config['classes']==['VK47','R301','R99','NEMESIS','ALTERNATOR','LSTAR','PROWLER','CAR','none','one_x','two_x','three_x','four_x']
    assert config['input_size']==320 and config['resize']=='letterbox_114'
    assert config['scale']==1 and config['padding_ltrb']==[0,96,0,96]
    assert config['input_shape']==[1,3,320,320] and config['output_shape']==[1,300,6]
    for key in ('gun_score_threshold','optic_score_threshold','optic_conflict_threshold','optic_conflict_margin'):
        value=config[key]
        assert isinstance(value,(float,int)) and math.isfinite(value) and 0<value<=1, key
    raw=(root/'joint.onnx').read_bytes()
    assert len(raw)==config['bytes'] and hashlib.sha256(raw).hexdigest()==config['sha256'], 'Joint model hash mismatch'
    mask=(root/'panel-mask.bin').read_bytes()
    assert len(mask)==40960 and set(mask)<={0,1} and sum(mask)==21024
    mask_hash=hashlib.sha256(mask).hexdigest()
    assert mask_hash==m['mask']['sha256']=='0f035302b617d2cb0c62b728a4158d2eae9f1df6f5215dd51f7447238e9cfaac'
    return {'schema_version':6,'model_sha256':config['sha256'],'mask_sha256':mask_hash,'verified':True}

if __name__=='__main__':
    if len(sys.argv)!=2:raise SystemExit('Usage: verify_joint_assets.py <models directory>')
    print(json.dumps(verify(Path(sys.argv[1])),indent=2))
