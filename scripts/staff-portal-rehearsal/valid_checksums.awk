# Prints "changeset id,checksum" for every validCheckSum declared in Liquibase YAML changelogs.
# Both forms are read:  validCheckSum: 9:abc   and   validCheckSum: followed by "- 9:abc" items.
function clean(value) { gsub(/["' \t\r]/, "", value); return value }

/^[ \t]*-?[ \t]*id:/ {
  line = $0; sub(/^[^:]*:[ \t]*/, "", line); id = clean(line); collecting = 0; next
}
/^[ \t]*validCheckSum:/ {
  line = $0; sub(/^[^:]*:[ \t]*/, "", line)
  if (clean(line) != "") { print id "," clean(line); collecting = 0 } else { collecting = 1 }
  next
}
collecting && /^[ \t]*-[ \t]*/ {
  line = $0; sub(/^[ \t]*-[ \t]*/, "", line); print id "," clean(line); next
}
collecting && !/^[ \t]*#/ { collecting = 0 }
