#!/usr/bin/env python3
"""Local AAC validator/STT. JSON stdout only; no transcript/diagnostic logging."""
import argparse
import json
import logging
import os
import sys


def result(status, **kwargs):
    print(json.dumps(dict(status=status, **kwargs), ensure_ascii=False), flush=True)


def validate(path):
    import av
    import numpy as np

    with av.open(path, options={"protocol_whitelist": "file,pipe"}) as source:
        if "mov" not in source.format.name.split(",") or len(source.streams) != 1:
            raise ValueError("container")
        audio = source.streams.audio
        if len(audio) != 1:
            raise ValueError("stream")
        stream = audio[0]
        ctx = stream.codec_context
        if ctx.name != "aac" or ctx.sample_rate != 16000 or len(ctx.layout.channels) != 1:
            raise ValueError("format")
        if stream.duration is None or not 0 < float(stream.duration * stream.time_base) <= 30:
            raise OverflowError("duration")
        # Decode the entire bounded input before STT, rather than trusting just container metadata.
        samples = []
        count = 0
        for frame in source.decode(stream):
            if frame.sample_rate != 16000 or len(frame.layout.channels) != 1:
                raise ValueError("frame")
            count += frame.samples
            # AAC may contain up to one padded tail frame; declared duration is checked above.
            if count > 480000 + 1024:
                raise OverflowError("samples")
            samples.append(frame.to_ndarray().reshape(-1).astype(np.float32))
        if not count:
            raise ValueError("empty")
        declared_samples = round(float(stream.duration * stream.time_base) * 16000)
        if abs(count - declared_samples) > 1024:
            raise ValueError("duration-mismatch")
        return np.concatenate(samples)[:declared_samples]


def main():
    args = argparse.ArgumentParser()
    args.add_argument("--model-dir", required=True)
    args.add_argument("--validate-only", action="store_true")
    args.add_argument("file")
    options = args.parse_args()
    logging.disable(logging.CRITICAL)
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    try:
        audio = validate(options.file)
    except OverflowError:
        result("too_large")
        return
    except Exception:
        result("malformed")
        return
    if options.validate_only:
        result("valid", samples=len(audio))
        return
    try:
        from faster_whisper import WhisperModel
        model = WhisperModel(options.model_dir, device="cpu", compute_type="int8", local_files_only=True)
        segments, _ = model.transcribe(audio, language="ru", vad_filter=True, beam_size=1,
                                       best_of=1, condition_on_previous_text=False)
        text = " ".join(segment.text.strip() for segment in segments).strip()
        if not text or len(text) > 4000:
            result("unavailable")
        else:
            result("transcribed", text=text)
    except Exception:
        result("unavailable")


if __name__ == "__main__":
    main()
