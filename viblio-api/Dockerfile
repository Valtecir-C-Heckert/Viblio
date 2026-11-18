FROM python:3.12-slim-bookworm
WORKDIR /app
COPY requirements.lock ./requirements.lock
COPY wheelhouse /wheelhouse
ARG OFFLINE_WHEELS=0
RUN if [ "$OFFLINE_WHEELS" = "1" ]; then pip install --no-cache-dir --no-index --find-links=/wheelhouse -r requirements.lock; else pip install --no-cache-dir -r requirements.lock; fi && rm -rf /wheelhouse && useradd --uid 10001 --create-home viblio && mkdir -p /data/covers && chown viblio:viblio /data/covers
COPY --chown=10001:10001 app ./app
USER 10001:10001
EXPOSE 8080
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8080", "--workers", "1", "--no-access-log", "--no-proxy-headers", "--timeout-keep-alive", "5"]
