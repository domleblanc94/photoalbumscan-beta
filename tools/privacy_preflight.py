#!/usr/bin/env python3
"""Check a clean source snapshot and optionally verify its ZIP matches exactly.

Run: python tools/privacy_preflight.py . --source-zip ../source.zip
This conservative file policy is a guard against accidental publication, not a
proof about model memorization, data rights, or every kind of private information.
"""
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, re, sys, zipfile
MODELS = {
    'app/src/main/assets/print-detector.onnx': '600f58506ffa4bdd32ec61bc697a36b4f68b7357e7a60170ebc87f222b67c1ea',
    'app/src/main/assets/picture-own-roi.onnx': '7af326cd0368735fdd09a71cbb295c9f896b4a4b4f64a00cea887b2dd7f23fa9',
    'app/src/main/assets/picture-own-fallback-roi.onnx': 'c99d1dec299847f91767c05321af2c41c04f68a1928edfb1b2a05afc5fff1016',
}
FORBIDDEN_PARTS = {'.git', '.gradle', '.idea', '.venv', 'node_modules', '__pycache__', 'build', 'training', 'test-data', 'app exports', 'rawphotos', 'raw-photos', 'datasets', 'exports'}
FORBIDDEN_SUFFIXES = {'.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.dpapi', '.zip', '.apk', '.aab', '.aar', '.tar', '.gz', '.7z', '.jpg', '.jpeg', '.png', '.webp', '.heic', '.gif', '.bmp', '.tiff', '.tif', '.pt', '.pth', '.npz', '.npy', '.db', '.sqlite'}
ALLOWED_SUFFIXES = {'.java', '.xml', '.md', '.txt', '.gradle', '.properties', '.bat', '.sh', '.yml', '.yaml', '.py', '.json'}
ALLOWED_ROOTS = {'.github', 'app', 'gradle', 'models', 'tools'}
ALLOWED_ROOT_FILES = {'.gitignore', 'gradlew', 'gradlew.bat', 'gradle.properties', 'settings.gradle', 'README.md', 'LICENSE', 'NOTICE', 'PRIVACY.md', 'SECURITY.md', 'CONTRIBUTING.md', 'MODEL_CARD.md', 'THIRD_PARTY_NOTICES.md', 'RELEASE_NOTES.md', 'SHA256SUMS'}

def digest(data):
    return hashlib.sha256(data).hexdigest()

def source_policy(name):
    path = PurePosixPath(name)
    lower_parts = {part.lower() for part in path.parts}
    if path.is_absolute() or '..' in path.parts or '\\' in name or re.match(r'^[A-Za-z]:', name):
        return 'unsafe path'
    if lower_parts & FORBIDDEN_PARTS:
        return 'private or generated directory'
    if any(part.lower().startswith('.env') for part in path.parts):
        return 'environment file'
    if path.name.lower() == 'local.properties' or any(word in path.name.lower() for word in ['signing-password', 'release-password']):
        return 'local configuration or signing material'
    if path.suffix.lower() in FORBIDDEN_SUFFIXES:
        return 'private/binary/archive format outside source policy'
    if name in MODELS or name in {'gradle/wrapper/gradle-wrapper.jar', 'models/runtime-dependencies.csv', 'runtime-dependencies.csv'}:
        return None
    if path.suffix.lower() in {'.onnx', '.jar'}:
        return 'unexpected model/archive'
    if len(path.parts) == 1:
        return None if name in ALLOWED_ROOT_FILES else 'unexpected root file'
    if path.parts[0] not in ALLOWED_ROOTS or path.suffix.lower() not in ALLOWED_SUFFIXES:
        return 'outside source allowlist'
    return None

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', nargs='?', default='.')
    parser.add_argument('--source-zip')
    parser.add_argument('--manifest-out', help='Write outside the checked source tree')
    args = parser.parse_args()
    root = Path(args.source).resolve()
    errors, files = [], {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            errors.append('Symlinks are outside the source policy')
            continue
        if not path.is_file():
            continue
        name = path.relative_to(root).as_posix()
        reason = source_policy(name)
        if reason:
            errors.append(f'{name}: {reason}')
        data = path.read_bytes()
        files[name] = {'bytes': len(data), 'sha256': digest(data)}
    for name, expected in MODELS.items():
        if files.get(name, {}).get('sha256') != expected:
            errors.append(f'{name}: missing or unexpected model hash')
    if args.source_zip:
        zipped = {}
        with zipfile.ZipFile(args.source_zip) as archive:
            bad = archive.testzip()
            if bad:
                errors.append('Source ZIP CRC check failed')
            all_entries = archive.infolist()
            for entry in all_entries:
                if source_policy(entry.filename.rstrip('/')) == 'unsafe path':
                    errors.append('Unsafe source ZIP member path')
                if ((entry.external_attr >> 16) & 0o170000) == 0o120000:
                    errors.append('Symlink source ZIP member')
            entries = [entry for entry in all_entries if not entry.is_dir()]
            names = [entry.filename for entry in entries]
            prefix = ''
            if names and not any(name in files for name in names):
                first_parts = {name.split('/', 1)[0] for name in names}
                if len(first_parts) == 1:
                    prefix = next(iter(first_parts)) + '/'
            for entry in entries:
                name = entry.filename
                if source_policy(name) == 'unsafe path':
                    errors.append('Unsafe source ZIP member path')
                    continue
                relative = name[len(prefix):] if prefix else name
                if relative in zipped:
                    errors.append('Duplicate source ZIP member')
                data = archive.read(entry)
                zipped[relative] = {'bytes': len(data), 'sha256': digest(data)}
            if files != zipped:
                errors.append('Source ZIP contents do not exactly match the checked source tree')
    manifest = {'files': files, 'file_count': len(files), 'errors': errors,
                'limitations': 'File policy and hashes do not prove absence of private training influence or weight memorization.'}
    if args.manifest_out:
        destination = Path(args.manifest_out).resolve()
        if destination == root or root in destination.parents:
            parser.error('--manifest-out must be outside the source tree')
        destination.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'file_count': len(files), 'errors': errors, 'result': 'PASS' if not errors else 'FAIL'}, indent=2))
    return 1 if errors else 0

if __name__ == '__main__':
    sys.exit(main())



