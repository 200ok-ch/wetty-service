#!/bin/bash

# Start traefik in background
traefik --configfile=/etc/traefik/traefik.yml &

# Wait a moment for traefik to start
sleep 2

# Start babashka app
exec /app/webapp.clj
