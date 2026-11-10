# SPDX-License-Identifier: AGPL-3.0-only
"""Historical v4 nine-class to v5 thirteen-class head expansion, with explicit inputs.

The older nine-class checkpoint is not included. The published joint-v5.pt is
already expanded and trained, so do not run this against it.
"""
from pathlib import Path
import argparse
from common import CLASSES, configure, save_json, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Trusted historical nine-class checkpoint')
    parser.add_argument('--output', type=Path, required=True, help='New checkpoint path')
    args = parser.parse_args()
    if not args.source.is_file():
        raise FileNotFoundError(args.source)
    if args.output.exists() or args.output.with_suffix('.audit.json').exists():
        raise FileExistsError(args.output)
    configure()
    import torch
    from ultralytics import YOLO
    torch.manual_seed(20261109)
    model = YOLO(str(args.source))
    net = model.model.float()
    head = net.model[-1]
    if head.nc != 9 or not head.end2end:
        raise ValueError('Expected the historical nine-class end-to-end detection head')
    before = {k: v.detach().clone() for k, v in net.state_dict().items()}
    mapping = [0, 1, 2, 3, 8, 9, 10, 11, 12]
    audit = []
    for branch in ('cv3', 'one2one_cv3'):
        for i, sequence in enumerate(getattr(head, branch)):
            old = sequence[-1]
            if old.out_channels != 9:
                raise ValueError('Unexpected projection width')
            new = torch.nn.Conv2d(old.in_channels, 13, old.kernel_size, old.stride,
                                 old.padding, old.dilation, old.groups, True)
            with torch.no_grad():
                new.weight[mapping] = old.weight
                new.bias[mapping] = old.bias
                mean, noise = old.weight[:4].mean(dim=0), old.weight[:4].std() * .01
                for cls in range(4, 8):
                    new.weight[cls] = mean + torch.randn_like(mean) * noise
                    new.bias[cls] = old.bias[:4].mean() - 1
            if not all(torch.equal(new.weight[n], old.weight[o]) and torch.equal(new.bias[n], old.bias[o])
                       for o, n in enumerate(mapping)):
                raise ValueError('Existing class projections were not preserved')
            sequence[-1] = new
            audit.append({'branch': branch, 'scale': i, 'old_nine_rows_exact': True, 'shape': list(new.weight.shape)})
    head.nc = 13
    head.no = 13 + head.reg_max * 4
    net.yaml['nc'] = 13
    net.names = dict(enumerate(CLASSES))
    net.nc = 13
    prefix = f'model.{len(net.model)-1}.'
    changed = {prefix + b + '.' + str(i) + '.2.' + s
               for b in ('cv3', 'one2one_cv3') for i in range(3) for s in ('weight', 'bias')}
    if not all(torch.equal(v, net.state_dict()[k]) for k, v in before.items() if k not in changed):
        raise ValueError('Unexpected non-head changes')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    model.save(str(args.output))
    save_json(args.output.with_suffix('.audit.json'), {
        'source_sha256': sha(args.source), 'expanded_checkpoint_sha256': sha(args.output),
        'class_order': CLASSES, 'old_to_new': mapping,
        'classification_projections': audit, 'other_tensors_exact': True})


if __name__ == '__main__':
    main()
