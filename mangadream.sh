#!/bin/sh
set -e

CMD_ARG="${1:-start}"

if [ "$CMD_ARG" = "start" ]; then
  # Ensure sqlite data directory exists
  mkdir -p /app/data
  exec java -jar /app/mangadream.jar
else
  exec "$@"
fi

