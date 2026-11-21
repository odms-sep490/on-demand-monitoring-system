FROM python:3.11-slim-bookworm
ENV PYTHONUNBUFFERED=1 MPLBACKEND=Agg
RUN apt-get update && apt-get install -y --no-install-recommends git libgl1 libglib2.0-0 && rm -rf /var/lib/apt/lists/*
ARG TORCH_INDEX=https://download.pytorch.org/whl/cpu
ARG MMCV_INDEX=https://download.openmmlab.com/mmcv/dist/cpu/torch2.0/index.html
RUN pip install --no-cache-dir torch==2.0.0 torchvision==0.15.1 --index-url ${TORCH_INDEX}
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt --find-links ${MMCV_INDEX} --only-binary=mmcv
RUN git clone https://github.com/likyoo/open-cd.git /opt/open-cd && git -C /opt/open-cd checkout 790a1972b538baaaffd4c3022a4d9afaa3f861e5
ENV PYTHONPATH=/opt/open-cd
WORKDIR /app
COPY app.py .
COPY quality_engine.py quality_api.py .
USER 65534:65534
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2).close()"]
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
