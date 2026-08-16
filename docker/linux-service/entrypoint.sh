#!/bin/sh
set -eu
umask 077

python -m rns_meshtastic.service_profile \
  --templates /opt/rns-meshtastic/templates \
  --rns-dir /data/rns \
  --lxmd-dir /data/lxmd

exec "$@"
