import json
import os
import subprocess
import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch
from fastapi import HTTPException
from fastapi.testclient import TestClient
import speech_transcription as speech
import description_agent as api

class SpeechTests(unittest.TestCase):
    def run_audio(self, duration=1, failure=None):
        def process(command, **kwargs):
            if 'ffmpeg' in command[0]:
                if failure=='invalid':raise subprocess.CalledProcessError(1,command)
                with wave.open(command[-1],'wb') as audio:
                    audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(16000)
                    audio.writeframes((b'\0\0' if failure=='silent' else b'\xe8\x03')*int(16000*duration))
            else:
                if failure=='timeout':raise subprocess.TimeoutExpired(command,70)
                if failure=='model':raise subprocess.CalledProcessError(1,command)
                Path(command[-1]).write_text(json.dumps({'text':'Nội dung từ bộ nhận giọng nói'}),encoding='utf-8')
        with tempfile.TemporaryDirectory() as model,patch.dict(os.environ,{'ORDER_ASR_MODEL_PATH':model}),patch.object(speech.subprocess,'run',side_effect=process),patch.object(speech,'_infer',side_effect=process),patch.object(speech.urllib.request,'urlopen',side_effect=OSError):
            return speech.transcribe(b'audio','audio/webm;codecs=opus')
    def test_transcript_is_returned_from_inference_output(self):
        self.assertEqual(self.run_audio()['text'],'Nội dung từ bộ nhận giọng nói')
    def test_duration_and_empty_audio_are_rejected(self):
        for duration,code in [(61,'ASR_AUDIO_TOO_LONG'),(0,'ASR_EMPTY_AUDIO')]:
            with self.assertRaises(HTTPException) as error:self.run_audio(duration)
            self.assertEqual(error.exception.detail,code)
    def test_invalid_audio_timeout_and_missing_model_are_distinct(self):
        for failure,status in [('invalid',422),('timeout',504),('model',503)]:
            with self.assertRaises(HTTPException) as error:self.run_audio(failure=failure)
            self.assertEqual(error.exception.status_code,status)
    def test_unsupported_format_never_starts_inference(self):
        with tempfile.TemporaryDirectory() as model,patch.dict(os.environ,{'ORDER_ASR_MODEL_PATH':model}),patch.object(speech.subprocess,'run') as run:
            with self.assertRaises(HTTPException) as error:speech.transcribe(b'audio','text/plain')
            self.assertEqual(error.exception.status_code,415);run.assert_not_called()
    def test_silence_never_reaches_model(self):
        with self.assertRaises(HTTPException) as error:self.run_audio(failure='silent')
        self.assertEqual(error.exception.detail,'ASR_EMPTY_AUDIO')
    def test_windows_timeout_terminates_owned_process_tree(self):
        from unittest.mock import MagicMock
        process=MagicMock();process.pid=12345
        process.wait.side_effect=[subprocess.TimeoutExpired(['python'],180),0]
        with patch.object(speech.subprocess,'Popen',return_value=process),patch.object(speech.subprocess,'run') as kill,patch.object(speech.os,'name','nt'):
            with self.assertRaises(subprocess.TimeoutExpired):speech._infer(['python'])
            self.assertEqual(kill.call_args.args[0],['taskkill','/PID','12345','/T','/F'])
    def test_authentication_precedes_audio_processing(self):
        with patch.dict(os.environ,{'DESCRIPTION_AI_TOKEN':'internal-test-only'}),patch.object(speech,'transcribe') as infer:
            self.assertEqual(TestClient(api.app).post('/conversation/transcribe',content=b'audio').status_code,401)
            infer.assert_not_called()
    def test_busy_slot_is_bounded_and_recovers(self):
        speech._slot.acquire()
        try:
            with tempfile.TemporaryDirectory() as model,patch.dict(os.environ,{'ORDER_ASR_MODEL_PATH':model}):
                with self.assertRaises(HTTPException) as error:speech.transcribe(b'audio','audio/wav')
                self.assertEqual(error.exception.status_code,429)
        finally:speech._slot.release()
        self.assertTrue(self.run_audio()['text'])

if __name__=='__main__':unittest.main()
