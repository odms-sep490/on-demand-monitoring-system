"""Isolated local inference; inputs are private temporary files from the worker."""
import json
import os
import sys
from pathlib import Path

def main():
    os.environ.setdefault('OMP_NUM_THREADS','4')
    os.environ.setdefault('MKL_NUM_THREADS','4')
    os.environ['HF_HUB_OFFLINE']='1'
    import torch
    from qwen_asr import Qwen3ASRModel
    torch.set_num_threads(4)
    device='cuda:0' if torch.cuda.is_available() else 'cpu'
    model=Qwen3ASRModel.from_pretrained(sys.argv[1],dtype=torch.float16,
        device_map=device,max_inference_batch_size=1,max_new_tokens=256,
        local_files_only=True)
    result=model.transcribe(audio=sys.argv[2],language='Vietnamese')
    Path(sys.argv[3]).write_text(json.dumps({'text':result[0].text if result else ''},ensure_ascii=False),encoding='utf-8')

if __name__=='__main__':main()
