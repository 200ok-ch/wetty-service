FROM traefik:v3.5 AS traefik-stage

FROM node:18-slim

# Install system dependencies including curl
RUN apt-get update && \
    apt-get install -y curl bash python3 python3-pip build-essential htop && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install babashka
RUN curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install && \
    chmod +x install && \
    ./install && \
    rm install

# Install wetty
RUN npm install -g wetty@2.5.0

# Copy traefik binary
COPY --from=traefik-stage /usr/local/bin/traefik /usr/local/bin/traefik

# Create directories
RUN mkdir -p /etc/traefik/dynamic /app

# Copy configuration files
COPY traefik.yml /etc/traefik/traefik.yml
COPY webapp.clj /app/webapp.clj
COPY start.sh /app/start.sh

RUN chmod +x /app/start.sh /app/webapp.clj

WORKDIR /app

EXPOSE 80

CMD ["/app/start.sh"]
