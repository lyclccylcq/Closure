"""Bounded caches for deterministic reference features; synthesized speech stays fresh."""
import copy
import hashlib
from collections import OrderedDict
from pathlib import Path


def install_reference_cache(pipeline):
    original_run = pipeline.run
    references = OrderedDict()

    def file_key(path):
        if not path:
            return None
        path = Path(path).resolve()
        stat = path.stat()
        return str(path), stat.st_size, stat.st_mtime_ns

    def cache_speaker():
        speaker = pipeline.sv_model
        if speaker is None or getattr(speaker, "_closure_reference_cached", False):
            return
        original = speaker.compute_embedding3
        embeddings = OrderedDict()

        def compute(audio):
            cpu = audio.detach().cpu().contiguous()
            key = (tuple(cpu.shape), str(cpu.dtype), hashlib.sha256(cpu.numpy().tobytes()).digest())
            if key not in embeddings:
                embeddings[key] = original(audio).detach().clone()
                if len(embeddings) > 16:
                    embeddings.popitem(last=False)
            embeddings.move_to_end(key)
            return embeddings[key].clone()

        speaker.compute_embedding3 = compute
        speaker._closure_reference_cached = True

    def run(inputs):
        cache_speaker()
        # The upstream API can also use a reference set separately. Closure sends
        # explicit paths; leave implicit reference state under upstream control.
        if not inputs.get("ref_audio_path"):
            yield from original_run(inputs)
            return
        config = pipeline.configs
        key = (file_key(inputs.get("ref_audio_path")), inputs.get("prompt_text"), inputs.get("prompt_lang"),
               tuple(file_key(path) for path in inputs.get("aux_ref_audio_paths") or []),
               file_key(config.t2s_weights_path), file_key(config.vits_weights_path),
               str(config.device), config.is_half, config.version,
               id(pipeline.t2s_model), id(pipeline.vits_model))
        if key in references:
            pipeline.prompt_cache = copy.deepcopy(references[key])
            references.move_to_end(key)
        else:
            # Upstream checks only path/text equality. A changed file, prompt
            # language or model must rebuild features even when strings match.
            pipeline.prompt_cache["ref_audio_path"] = None
            pipeline.prompt_cache["prompt_text"] = None
            pipeline.prompt_cache["aux_ref_audio_paths"] = []
        completed = False
        try:
            yield from original_run(inputs)
            completed = True
        finally:
            if completed:
                references[key] = copy.deepcopy(pipeline.prompt_cache)
                references.move_to_end(key)
                if len(references) > 8:
                    references.popitem(last=False)

    pipeline.run = run
