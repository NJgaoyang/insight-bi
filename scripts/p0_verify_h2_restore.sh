#!/usr/bin/env bash
# Verifies an existing consistent H2 SQL BACKUP on an isolated extracted copy.
# No live database is opened, changed, stopped or restarted by this script.
set -Eeuo pipefail
app="${P0_APP_ROOT:-/opt/insight-bi}"
runtime="$app/runtime"
backup="$runtime/p0-metadata-consistent.zip"
receipt="$runtime/p0-h2-restore.sha256"
h2jar="${P0_H2_JAR:-}"

fail(){ echo "H2 restore verification refused: $1" >&2; exit 2; }
[[ -s "$backup" ]] || fail "consistent H2 backup missing"
[[ -f "$app/scripts/P0MetadataRestoreVerify.java" ]] || fail "missing restore verifier source"
[[ -f "$h2jar" ]] || fail "set P0_H2_JAR to the matching installed H2 JDBC library"
command -v javac >/dev/null || fail "Java 21 compiler not found"
command -v python3 >/dev/null || fail "Python 3 required for safe archive extraction"

umask 077
work="$(mktemp -d "$runtime/.restore-test.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT
python3 - "$backup" "$work" <<'PY'
import pathlib,sys,zipfile
archive=pathlib.Path(sys.argv[1])
root=pathlib.Path(sys.argv[2]).resolve()
with zipfile.ZipFile(archive) as z:
    if z.testzip():
        raise SystemExit("H2 backup ZIP CRC failed")
    files=[x for x in z.infolist() if x.filename.endswith(".mv.db") and not x.is_dir()]
    if len(files)!=1:
        raise SystemExit("H2 backup must contain exactly one MVStore database")
    for info in z.infolist():
        target=(root/info.filename).resolve()
        if not target.is_relative_to(root):
            raise SystemExit("H2 backup contains a path traversal")
        if info.is_dir():
            target.mkdir(parents=True,exist_ok=True)
        else:
            target.parent.mkdir(parents=True,exist_ok=True)
            with z.open(info) as src,target.open("wb") as out:
                import shutil
                shutil.copyfileobj(src,out)
    db=(root/files[0].filename)
    print(str(db)[:-len(".mv.db")],file=(root/"h2-base.txt").open("w"))
PY
javac -cp "$h2jar" -d "$work" "$app/scripts/P0MetadataRestoreVerify.java"
base="$(cat "$work/h2-base.txt")"
[[ "$base" == "$work/"* ]] || fail "extracted database escaped temporary directory"
java -cp "$work:$h2jar" P0MetadataRestoreVerify "$base"
python3 - "$backup" "$receipt" <<'PY'
import hashlib,pathlib,sys
backup,receipt=map(pathlib.Path,sys.argv[1:])
with backup.open("rb") as source:
    digest=hashlib.file_digest(source,"sha256").hexdigest()
receipt.write_text(digest+"\n",encoding="ascii")
receipt.chmod(0o600)
print("H2 restore verified: backup digest recorded in private runtime")
PY
