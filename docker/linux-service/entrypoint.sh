#!/bin/sh
set -eu
umask 077

if [ "${RNS_RENDER_PROFILE:-no}" = "yes" ]; then
  python -m rns_meshtastic.service_profile \
    --templates /opt/rns-meshtastic/templates \
    --rns-dir /data/rns \
    --lxmd-dir /data/lxmd
fi

exec "$@"
