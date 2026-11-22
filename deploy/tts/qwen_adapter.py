"""Load an optional local Qwen talker adapter before voice warm-up.

The manifest is data, never executable code. MLX stays lazily imported so the
configuration contract and cold HTTP listener can be tested without a GPU.
"""
import hashlib
import json
import math
from pathlib import Path
import re

FORMAT = "closure-qwen-lora-v1"
TARGETS = ("q_proj", "v_proj")


def adapter_config_path(runtime, explicit=None, disabled=False):
    if disabled:
        return None
    if explicit is not None:
        return Path(explicit)
    candidate = Path(runtime) / "active-adapter.json"
    # A broken activation link is an error, rather than an unnoticed voice change.
    return candidate if candidate.exists() or candidate.is_symlink() else None


def read_adapter_config(path, model_id, revision):
    path = Path(path).resolve(strict=True)
    if not path.is_file() or path.stat().st_size > 16384:
        raise ValueError("Expected a local adapter manifest smaller than 16 KiB")
    config = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(config, dict) or config.get("format") != FORMAT:
        raise ValueError("Unsupported Qwen adapter format")
    if config.get("base_model_id") != model_id or config.get("base_model_revision") != revision:
        raise ValueError("Qwen adapter requires a different pinned base model")
    if not isinstance(config.get("id"), str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,80}", config["id"]):
        raise ValueError("Invalid local adapter id")
    rank, alpha = config.get("rank"), config.get("alpha")
    if type(rank) is not int or not 1 <= rank <= 128:
        raise ValueError("Invalid adapter rank")
    if isinstance(alpha, bool) or not isinstance(alpha, (int, float)) or not math.isfinite(alpha) or not 0 < alpha <= 256:
        raise ValueError("Invalid adapter scale")
    if config.get("targets") != list(TARGETS):
        raise ValueError("Only the reviewed talker q/v adapter layout is supported")
    weights = config.get("weights")
    if not isinstance(weights, str) or not weights or Path(weights).is_absolute():
        raise ValueError("Adapter weights must be relative to their local manifest")
    weights = (path.parent / weights).resolve(strict=True)
    if not weights.is_relative_to(path.parent) or weights.suffix != ".safetensors" or not weights.is_file():
        raise ValueError("Adapter weights must be a safetensors file inside the manifest directory")
    if not 0 < weights.stat().st_size <= 64 * 1024**2:
        raise ValueError("Invalid adapter weight file size")
    digest = config.get("sha256")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Invalid adapter checksum")
    actual = hashlib.sha256()
    with weights.open("rb") as source:
        for chunk in iter(lambda: source.read(1024**2), b""):
            actual.update(chunk)
    if actual.hexdigest() != digest:
        raise ValueError("Adapter weight checksum mismatch")
    return {**config, "weights_path": weights}


def apply_adapter(model, manifest, model_id, revision):
    config = read_adapter_config(manifest, model_id, revision)
    import mlx.core as mx
    import mlx.nn as nn
    from mlx.utils import tree_unflatten

    class LocalLoRA(nn.Module):
        def __init__(self, base, a, b):
            super().__init__()
            base.freeze()
            self.base = base
            self.lora_a = a
            self.lora_b = b

        def __call__(self, x):
            result = self.base(x)
            # Match the validated listening trial without promoting
            # the base forward or KV cache from bf16 to float32.
            delta = (x.astype(mx.float32) @ self.lora_a.T @ self.lora_b.T) * (config["alpha"] / config["rank"])
            return result + delta.astype(result.dtype)

    backbone = model.talker.model
    layers, expected = [], {}
    for path, layer in backbone.named_modules():
        if path.rsplit(".", 1)[-1] not in TARGETS or not isinstance(layer, (nn.Linear, nn.QuantizedLinear)):
            continue
        if isinstance(layer, nn.QuantizedLinear):
            out_features = layer.scales.shape[0]
            in_features = layer.weight.shape[-1] * (32 // layer.bits)
        else:
            out_features, in_features = layer.weight.shape
        expected[path + ".lora_a"] = (config["rank"], in_features)
        expected[path + ".lora_b"] = (out_features, config["rank"])
        layers.append((path, layer))
    if not layers:
        raise ValueError("The base model has no compatible talker q/v layers")
    arrays = mx.load(str(config["weights_path"]))
    if set(arrays) != set(expected):
        raise ValueError("Adapter tensor keys do not exactly match the base model")
    for key, shape in expected.items():
        if arrays[key].shape != shape or arrays[key].dtype not in (mx.float16, mx.bfloat16, mx.float32):
            raise ValueError("Adapter tensor shape or dtype mismatch")
    finite = mx.all(mx.stack([mx.all(mx.isfinite(array)) for array in arrays.values()]))
    mx.eval(finite)
    if not bool(finite):
        raise ValueError("Adapter contains non-finite parameters")
    replacements = [(path, LocalLoRA(layer, arrays[path + ".lora_a"], arrays[path + ".lora_b"]))
                    for path, layer in layers]
    backbone.update_modules(tree_unflatten(replacements))
    model.eval()
    return {"adapter_loaded": True, "adapter_id": config["id"], "adapter_sha256": config["sha256"],
            "adapter_rank": config["rank"], "adapter_layers": len(replacements)}
