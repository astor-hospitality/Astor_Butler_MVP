#!/usr/bin/env python3
"""Real decoder tests. No cloud calls, microphone access or retained audio."""
import argparse
import json
import pathlib
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--python', required=True)
p.add_argument('--model-dir', required=True)
a = p.parse_args()
with tempfile.TemporaryDirectory(prefix='astor-aac-test-') as tmp:
    for rate, channels, seconds, expected in [(16000, 1, 1, 'valid'), (44100, 1, 1, 'malformed'),
                                             (16000, 2, 1, 'malformed'), (16000, 1, 31, 'too_large'),
                                             (16000, 1, 30, 'valid')]:
        f = pathlib.Path(tmp)/f'{rate}-{channels}-{seconds}.m4a'
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
                        f'anullsrc=r={rate}:cl={"mono" if channels == 1 else "stereo"}', '-t', str(seconds),
                        '-c:a', 'aac', str(f)], check=True, timeout=10)
        r = subprocess.run([a.python, 'scripts/glasses_stt.py', '--model-dir', a.model_dir,
                            '--validate-only', str(f)], capture_output=True, check=True, timeout=10)
        status = json.loads(r.stdout)['status']
        assert status == expected, (rate, channels, seconds, status, expected)
        print(f'AAC {rate}Hz/{channels}ch/{seconds}s: {status} PASS')
    f=pathlib.Path(tmp)/'malformed.m4a';f.write_bytes(b'not a container')
    r=subprocess.run([a.python,'scripts/glasses_stt.py','--model-dir',a.model_dir,'--validate-only',str(f)],capture_output=True,check=True,timeout=10)
    assert json.loads(r.stdout)['status']=='malformed'
    print('Malformed container: PASS')
