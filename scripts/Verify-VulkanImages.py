#!/usr/bin/env python3
"""Compare production image barriers without merging the PR or altering profiles.

Exit 1: a candidate has contract/validation failures; 2: incomplete infrastructure.
The conservative reference variant is a harness positive control, not a product fix.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HOST = 'src/main/java/me/cortex/voxy/client/core/vk/render/VkFrameHost.java'
VULKAN_FILES = [f'src/main/java/me/cortex/voxy/client/core/vk/render/{name}.java'
                for name in ['VkFrameHost', 'VkCompositor', 'VkSSAO', 'VkTerrainRenderer']]


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])


def depth_only(text):
    old = 'int aspectMask = depth ? (VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT) : VK_IMAGE_ASPECT_COLOR_BIT;'
    new = '''int imageFormat = vkFormat(view);
            boolean stencil = imageFormat == VK_FORMAT_D16_UNORM_S8_UINT
                    || imageFormat == VK_FORMAT_D24_UNORM_S8_UINT
                    || imageFormat == VK_FORMAT_D32_SFLOAT_S8_UINT;
            int aspectMask = !depth ? VK_IMAGE_ASPECT_COLOR_BIT
                    : VK_IMAGE_ASPECT_DEPTH_BIT | (stencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);'''
    if text.count(old) != 1:
        raise RuntimeError('Baseline aspect selection changed; review depth-only fixture before continuing.')
    return text.replace(old, new)


def prepare(directory, base, variant, pr):
    archive = directory.parent / (variant + '.zip')
    subprocess.run(['git', '-C', str(ROOT), 'archive', base, '-o', str(archive)], check=True)
    directory.mkdir()
    with zipfile.ZipFile(archive) as zipped:
        zipped.extractall(directory)
    archive.unlink()
    shutil.copytree(ROOT / 'src/vulkanImageTest', directory / 'src/vulkanImageTest')
    shutil.copy2(ROOT / 'gradle/vulkan-image-test.gradle', directory / 'gradle/vulkan-image-test.gradle')
    build = directory / 'build.gradle'
    text = build.read_text(encoding='utf-8')
    if "apply from: 'gradle/vulkan-image-test.gradle'" not in text:
        build.write_text(text + "\napply from: 'gradle/vulkan-image-test.gradle'\n", encoding='utf-8')
    host = directory / HOST
    if variant in ['depth-only', 'reference-control']:
        text = depth_only(host.read_text(encoding='utf-8'))
        if variant == 'reference-control':
            # Positive control: a conservative superset of both real consumers.
            text = text.replace('VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT',
                                '(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)')
        host.write_text(text, encoding='utf-8')
    elif variant == 'pr-experimental':
        for filename in VULKAN_FILES:
            (directory / filename).write_bytes(git('show', f'{pr}:{filename}'))
    elif variant == 'working-tree':
        for filename in VULKAN_FILES:
            shutil.copy2(ROOT / filename, directory / filename)
    return hashlib.sha256(host.read_bytes()).hexdigest()


def execute(directory, version, general, gpu, output, timeout):
    task = 'verifyVulkanImageGpu' if gpu else 'verifyVulkanImageBarriers'
    command = [str(directory / ('gradlew.bat' if os.name == 'nt' else 'gradlew')),
               task, '--offline', '--console=plain', '--max-workers=4',
               f'-Pminecraft_version={version}', f'-PmcImagesGeneral={str(general).lower()}']
    log = output / f'{directory.name}-{version}-general{int(general)}-{task}.log'
    env = dict(os.environ, VK_VALIDATION_VALIDATE_SYNC='1')
    print(f'RUN {directory.name} MC {version} GENERAL={general} {task}', flush=True)
    try:
        with log.open('w', encoding='utf-8') as stream:
            result = subprocess.run(command, cwd=directory, env=env, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=timeout)
        text = log.read_text(encoding='utf-8')
        summary = re.search(r'RESULT checks=(\d+) failures=(\d+)', text)
        status = ('unavailable' if 'UNAVAILABLE:' in text else
                  'passed' if result.returncode == 0 and summary and int(summary[2]) == 0 else
                  'failed' if summary else 'error')
        record = {'status': status, 'exit_code': result.returncode,
                  'checks': int(summary[1]) if summary else None,
                  'failures': int(summary[2]) if summary else None,
                  'validation_enabled': 'VALIDATION_ENABLED:' in text,
                  'native_formats': re.findall(r'NATIVE_FORMAT_EXECUTED: (\d+)', text),
                  'native_depth_samples': re.findall(r'NATIVE_DEPTH_SAMPLED: ([^\r\n]+)', text),
                  'synchronization_hazards': len(re.findall(r'VALIDATION:.*(?:READ_AFTER_WRITE|WRITE_AFTER_READ|WRITE_AFTER_WRITE) hazard detected', text)),
                  'log': str(log)}
        if gpu and status == 'passed' and not record['validation_enabled']:
            record['status'] = 'error'
    except subprocess.TimeoutExpired:
        record = {'status': 'error', 'reason': 'timeout', 'log': str(log)}
    print(f"  {record['status']}: {record.get('checks')} checks, {record.get('failures')} failures", flush=True)
    return dict(record, variant=directory.name, minecraft=version, general=general, native=gpu)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-ref', default='HEAD')
    parser.add_argument('--pr-ref', default='refs/codex/review-pr7', help='Fetch first: git fetch origin pull/7/head:refs/codex/review-pr7')
    parser.add_argument('--versions', nargs='+', choices=['26.2', '26.3'], default=['26.2', '26.3'])
    parser.add_argument('--gpu', action='store_true', help='Also run native Vulkan transitions; requires the Khronos validation layer.')
    parser.add_argument('--include-working-tree', action='store_true', help='Also test the current four Vulkan source files, without merging or committing them.')
    parser.add_argument('--timeout', type=int, default=240)
    parser.add_argument('--output-directory', type=Path, default=ROOT / 'build/vulkan-image-validation')
    args = parser.parse_args()
    base = git('rev-parse', args.base_ref).decode().strip()
    pr = git('rev-parse', args.pr_ref).decode().strip()
    args.output_directory.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='run-', dir=args.output_directory.resolve()))
    report = {'base_commit': base, 'pr_commit': pr, 'sources': {}, 'results': [],
              'scope': 'Production helper contracts and optional native image transitions. Minecraft scene/descriptor/visual equivalence remains NOT EXECUTED.'}
    report_path = output / 'report.json'
    variants = ['baseline', 'depth-only', 'pr-experimental', 'reference-control']
    if args.include_working_tree:
        variants.append('working-tree')
    for variant in variants:
        directory = output / variant
        report['sources'][variant] = prepare(directory, base, variant, pr)
        for version in args.versions:
            for general in ([False, True] if variant == 'pr-experimental' else [False]):
                for native in ([False, True] if args.gpu else [False]):
                    report['results'].append(execute(directory, version, general, native, output, args.timeout))
                    report_path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'Report: {report_path}')
    statuses = {r['status'] for r in report['results']}
    return 2 if statuses & {'unavailable', 'error'} else 1 if 'failed' in statuses else 0


if __name__ == '__main__':
    raise SystemExit(main())
