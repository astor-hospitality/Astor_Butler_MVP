"""Run the backup script against fake Docker/stat; never access databases or S3."""
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "astor-backup.sh"


class BackupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="astor-backup-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.dumps = self.root / "daily"
        self.dumps.mkdir(mode=0o755)
        self.old = self.dumps / "astor-pg-old.dump"
        self.old.write_text("previous backup")
        ago = time.time() - 10 * 86400
        os.utime(self.old, (ago, ago))
        self.unrelated = self.dumps / "operator-notes.txt"
        self.unrelated.write_text("retain me")
        os.utime(self.unrelated, (ago, ago))
        self.bin = self.root / "bin"
        self.bin.mkdir()
        docker = self.bin / "docker"
        docker.write_text("""#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
if args[0] == 'run':
    with open(os.environ['UPLOAD_LOG'], 'a') as log:
        log.write(args[-1] + '\\n')
    fail = os.environ.get('FAIL_UPLOAD', '')
    sys.exit(1 if fail and fail in args[-1] else 0)
command = args[-1]
if command == 'pg_restore':
    raise SystemExit('unexpected restore command')
if 'pg_restore' in args:
    sys.stdin.buffer.read()
    print('1 TABLE DATA public sample')
elif 'pg_dump' in command:
    sys.stdout.buffer.write(b'x' * 2048)
elif 'mongodump' in command:
    sys.stdout.buffer.write(b'mongo archive')
else:
    raise SystemExit('unexpected Docker command')
""")
        docker.chmod(0o755)
        size = self.bin / "stat"
        size.write_text("#!/usr/bin/env python3\nimport os, sys\nprint(os.path.getsize(sys.argv[-1]))\n")
        size.chmod(0o755)
        self.log = self.root / "uploads.log"
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                        ASTOR_BACKUP_DIR=str(self.dumps), UPLOAD_LOG=str(self.log))

    def run_backup(self, failure=""):
        result = subprocess.run(["bash", str(SCRIPT)],
                                env=dict(self.env, FAIL_UPLOAD=failure),
                                capture_output=True, text=True)
        self.assertEqual(len(self.log.read_text().splitlines()), 2)
        self.assertEqual(stat.S_IMODE(self.dumps.stat().st_mode), 0o700)
        new = [p for p in self.dumps.iterdir() if p.name.startswith("astor-") and p != self.old]
        self.assertEqual(len(new), 2)
        for dump in new:
            self.assertEqual(stat.S_IMODE(dump.stat().st_mode), 0o600)
        self.assertTrue(self.unrelated.exists())
        return result

    def test_success_uploads_both_and_removes_only_expired_backups(self):
        result = self.run_backup()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.old.exists())

    def test_postgres_upload_failure_keeps_local_backups_and_fails_service(self):
        self.assert_failed("astor-pg-")

    def test_mongo_upload_failure_keeps_local_backups_and_fails_service(self):
        self.assert_failed("astor-mongo-")

    def assert_failed(self, failure):
        result = self.run_backup(failure)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("local retention cleanup skipped", result.stderr)
        self.assertTrue(self.old.exists())


if __name__ == "__main__":
    unittest.main()
