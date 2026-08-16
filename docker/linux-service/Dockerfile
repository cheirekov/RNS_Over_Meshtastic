FROM ghcr.io/astral-sh/uv:0.11.2 AS uv

FROM python:3.12-slim-bookworm

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home --shell /usr/sbin/nologin rns

COPY --from=uv /uv /uvx /usr/local/bin/
WORKDIR /opt/rns-meshtastic/app
COPY pyproject.toml uv.lock README.md LICENSE ./
COPY src ./src
RUN uv sync --frozen --extra propagation --no-dev --no-editable \
    && rm -rf /root/.cache/uv

COPY docker/linux-service/templates /opt/rns-meshtastic/templates
COPY docker/linux-service/entrypoint.sh /usr/local/bin/rns-meshtastic-service-entrypoint
RUN chmod 0755 /usr/local/bin/rns-meshtastic-service-entrypoint \
    && mkdir -p /data/rns /data/lxmd \
    && chown -R rns:rns /data/rns /data/lxmd

ENV PATH="/opt/rns-meshtastic/app/.venv/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
USER rns
ENTRYPOINT ["/usr/local/bin/rns-meshtastic-service-entrypoint"]
