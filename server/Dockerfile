FROM python:3.12-alpine AS builder
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --target=/app/deps -r requirements.txt

# docker build --target test server
FROM builder AS test
ENV PYTHONPATH=/app/deps
COPY relay.py test_relay.py ./
RUN python -m unittest -v

FROM python:3.12-alpine
WORKDIR /app
COPY --from=builder /app/deps /usr/local/lib/python3.12/site-packages
COPY relay.py .
# Docker seeds the named volume from this directory, owner included.
RUN mkdir /data && chown 1000:1000 /data
USER 1000:1000
CMD ["python", "-u", "relay.py"]
