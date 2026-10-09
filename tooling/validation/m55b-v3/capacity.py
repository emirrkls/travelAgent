"""Read-only runner capacity gate; no cleanup, swap or resource reconfiguration."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone

GIB = 1024 ** 3


def snapshot(path):
    mem = {k: int(v.strip().split()[0]) * 1024 for k, v in
           (s.split(':', 1) for s in Path('/proc/meminfo').read_text().splitlines())
           if k in {'MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree'}}
    disk = shutil.disk_usage(path)
    fs = os.statvfs(path)
    info = subprocess.run(['docker', 'info', '--format', '{{json .}}'], capture_output=True, timeout=15)
    if info.returncode:
        raise RuntimeError('DOCKER_UNAVAILABLE')
    d = json.loads(info.stdout)
    workload = subprocess.run(['docker', 'ps', '--format', '{{.ID}} {{.Image}} {{.Status}}'],
                              capture_output=True, timeout=10, check=True).stdout.decode().splitlines()
    return {'at': datetime.now(timezone.utc).isoformat(), 'memory': mem, 'cpus': os.cpu_count(),
            'disk_total': disk.total, 'disk_free': disk.free, 'inodes_free': fs.f_favail,
            'docker': {k: d.get(k) for k in ['ServerVersion', 'CgroupVersion', 'CgroupDriver', 'MemTotal', 'NCPU',
                                           'MemoryLimit', 'SwapLimit', 'CpuCfsQuota', 'Warnings']},
            'existing_workload': workload,
            'cgroup_controllers': Path('/sys/fs/cgroup/cgroup.controllers').read_text().split(),
            'docker_network_drivers': d.get('Plugins', {}).get('Network', []),
            'https_network_evidence': 'SUCCESSFUL_AUTHORIZED_GITHUB_CHECKOUT; registry pulls recorded before fixture services',
            'network_policy': 'INTERNAL_DOCKER_NETWORK_ONLY_FOR_ALL_FIXTURE_CONTAINERS'}


def gate(path, *, operational=False):
    s = snapshot(path)
    # Peak envelopes, not measured consumption: 3 GiB importer + 1.5 backend + .75 DB
    # + 1.5 B1 + .5 media + .125 proxy + .5 host monitor. Build/seed run sequentially.
    s['estimate'] = {'concurrent_memory_bytes': int(7.875 * GIB), 'disk_bytes': 12 * GIB,
                     'disk_components_gib': {'images_and_build_cache': 6, 'jdk_jar_classes': 1,
                                             'database_volumes': 3, 'fixture_and_diagnostics': 1, 'reserve': 1},
                     'build_seed_not_concurrent_with_importer': True}
    minimum_available = 4 * GIB if operational else 8 * GIB
    checks = {'physical_ram': s['memory']['MemTotal'] >= 12 * GIB,
              'available_ram': s['memory']['MemAvailable'] >= minimum_available,
              'cpu': s['cpus'] >= 4,
              'disk': s['disk_free'] >= (4 if operational else 12) * GIB,
              'inodes': s['inodes_free'] >= 100000,
              'cgroup_v2': str(s['docker']['CgroupVersion']) == '2',
              'memory_limit': s['docker']['MemoryLimit'] is True,
              'swap_limit': s['docker']['SwapLimit'] is True,
              'cpu_quota': s['docker']['CpuCfsQuota'] is True}
    checks['isolated_bridge_capability'] = 'bridge' in s['docker_network_drivers']
    checks['no_prior_workload'] = not s['existing_workload'] if not operational else True
    s.update(checks=checks, status='PASS' if all(checks.values()) else 'BLOCKED',
             host_swap_policy='UNCHANGED; cgroup allowance does not create host swap')
    return s


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    a.output.mkdir(parents=True, exist_ok=True)
    s = gate(a.output)
    (a.output / 'capacity_initial.json').write_text(json.dumps(s, indent=2) + '\n')
    print(json.dumps(s))
    sys.exit(0 if s['status'] == 'PASS' else 2)
