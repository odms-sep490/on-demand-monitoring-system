"""Optional metrics-only deployment, without Torch/Open-CD/Paddle dependencies."""
from fastapi import FastAPI
from quality_api import router

app = FastAPI(title='OnDemand Image Quality')
app.include_router(router)


@app.get('/health')
def health():
    return {'status': 'UP'}
