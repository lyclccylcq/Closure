"""Local MLX joint-talker experiment. Never imports MLX or reads audio in CI.

Inputs are immutable prepared x-vector teacher features plus all 16 codec IDs.
Only explicit v2 LoRA arrays change. This is not full-parameter official SFT.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import random
import time

from qwen_adapter import (JOINT_FORMAT, JOINT_SCOPES, JOINT_TARGETS, adapter_modules,
                          apply_adapter)
from qwen_runtime import MODEL_ID, MODEL_REVISION


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_base(model):
    source = model.parent / "model-source.json"
    data = json.loads(source.read_text())
    if (data.get("repo_id") != MODEL_ID or data.get("revision") != MODEL_REVISION
            or Path(data.get("path", "")).resolve() != model.resolve()):
        raise ValueError("Training requires the pinned prepared base and its source marker")
    return digest(source)


def load_index(work):
    import re
    rows = json.loads((work / "feature-index.json").read_text())
    if not isinstance(rows, list) or not rows:
        raise ValueError("Expected prepared source-level training/validation records")
    work = work.resolve(strict=True)
    ids, features, codec_fingerprints = set(), set(), {}
    for row in rows:
        ident = row.get("id")
        if not isinstance(ident, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", ident) or ident in ids:
            raise ValueError("Invalid or duplicate source clip id")
        ids.add(ident)
        if row.get("split") not in ("train", "val") or type(row.get("frames")) is not int or row["frames"] < 1:
            raise ValueError("Invalid split/frame count")
        feature = Path(row["feature_path"]).resolve(strict=True)
        codec = (work / "codecs" / (ident + ".npy")).resolve(strict=True)
        if not feature.is_relative_to(work / "features") or not codec.is_relative_to(work / "codecs"):
            raise ValueError("Prepared input escapes the source dataset")
        if feature in features:
            raise ValueError("A source feature may not appear in both splits")
        features.add(feature)
        fingerprint = digest(codec)
        if fingerprint in codec_fingerprints and codec_fingerprints[fingerprint] != row["split"]:
            raise ValueError("Duplicate source codec content leaks across train/validation")
        codec_fingerprints[fingerprint] = row["split"]
    if {row["split"] for row in rows} != {"train", "val"}:
        raise ValueError("Separate held-out source clips are required")
    return rows


def frame_indices(frames, budget, seed):
    if type(frames) is not int or frames < 1 or type(budget) is not int or budget < 2:
        raise ValueError("Positive frame count and at least two sampled frames required")
    if frames <= budget:
        return list(range(frames))
    return [0, *sorted(random.Random(seed).sample(range(1, frames - 1), budget - 2)), frames - 1]


def runtime():
    global mx, nn, np, optim, tree_flatten, tree_map, tree_unflatten
    import mlx.core as mx
    import mlx.nn as nn
    import mlx.optimizers as optim
    import numpy as np
    from mlx.utils import tree_flatten, tree_map, tree_unflatten


def adapters(root):
    return {path + "." + key: getattr(layer, key)
            for path, layer in root.named_modules() if getattr(layer, "_joint_lora", False)
            for key in ("lora_a", "lora_b")}


def install(model, rank, alpha):
    class LoRA(nn.Module):
        _joint_lora = True

        def __init__(self, base):
            super().__init__()
            base.freeze()
            self.base = base
            if isinstance(base, nn.QuantizedLinear):
                output = base.scales.shape[0]
                input_size = base.weight.shape[-1] * (32 // base.bits)
            else:
                output, input_size = base.weight.shape
            self.lora_a = mx.random.uniform(-1 / math.sqrt(input_size), 1 / math.sqrt(input_size), (rank, input_size))
            self.lora_b = mx.zeros((output, rank), dtype=mx.float32)
            self.enabled = True

        def __call__(self, x):
            result = self.base(x)
            if not self.enabled:
                return result
            delta = (x.astype(mx.float32) @ self.lora_a.T @ self.lora_b.T) * (alpha / rank)
            return result + delta.astype(result.dtype)

    root, layers = adapter_modules(model, JOINT_FORMAT)
    root.freeze()
    root.update_modules(tree_unflatten([(path, LoRA(layer)) for path, layer in layers]))
    return root


def sample(work, row, eos):
    with np.load(row["feature_path"], allow_pickle=False) as data:
        inputs = mx.array(data["inputs"], dtype=mx.bfloat16)
        targets = np.asarray(data["targets"])
        offset = int(data["offset"])
    codes = np.load(work / "codecs" / (row["id"] + ".npy"), allow_pickle=False)
    frames = row["frames"]
    if codes.shape != (frames, 16) or codes.dtype.kind not in "iu" or np.any(codes < 0) or np.any(codes >= 2048):
        raise ValueError("Invalid complete 16-codebook source targets")
    if (inputs.ndim != 3 or inputs.shape[0] != 1 or offset < 1
            or inputs.shape[1] - offset != frames + 1 or targets.shape != (frames + 1,)
            or targets[-1] != eos or not np.array_equal(targets[:-1], codes[:, 0])):
        raise ValueError("Teacher alignment must retain frame zero, all frames and final EOS")
    return inputs, mx.array(targets, dtype=mx.int32), offset, mx.array(codes, dtype=mx.int32)


def residual_logits(model, hidden, codes, dtype=None):
    predictor = model.talker.code_predictor
    # Inference prefill is [main hidden, codec0]; later inputs are codec1..14.
    # Target codec15 is never included in its own conditioning sequence.
    pieces = [hidden[:, None, :], model.talker.get_input_embeddings()(codes[:, :1])]
    pieces += [predictor.codec_embedding[i - 1](codes[:, i:i + 1]) for i in range(1, 15)]
    inputs = mx.concatenate(pieces, axis=1)
    if dtype is not None:
        inputs = inputs.astype(dtype)
    if predictor.small_to_mtp_projection is not None:
        inputs = predictor.small_to_mtp_projection(inputs)
    states = predictor.model(inputs)
    return mx.stack([head(states[:, i + 1, :]) for i, head in enumerate(predictor.lm_head)], axis=1)


def losses(model, data, indices):
    inputs, labels, offset, codes = data
    states = model.talker.model(inputs)[:, offset:, :]
    main = nn.losses.cross_entropy(model.talker.codec_head(states)[0].astype(mx.float32), labels, reduction="mean")
    pick = mx.array(indices, dtype=mx.int32)
    residual = nn.losses.cross_entropy(residual_logits(model, states[0, pick, :], codes[pick]).astype(mx.float32),
                                      codes[pick, 1:], reduction="mean")
    return main, residual


def evaluate(model, work, rows, batch_frames):
    main_sum = sub_sum = main_count = sub_count = 0
    eos = model.config.talker_config.codec_eos_token_id
    for row in rows:
        inputs, labels, offset, codes = sample(work, row, eos)
        states = model.talker.model(inputs)[:, offset:, :]
        main = nn.losses.cross_entropy(model.talker.codec_head(states)[0].astype(mx.float32), labels, reduction="mean")
        mx.eval(states, main)
        main_sum += float(main) * len(labels)
        main_count += len(labels)
        for start in range(0, row["frames"], batch_frames):
            stop = min(start + batch_frames, row["frames"])
            sub = nn.losses.cross_entropy(residual_logits(model, states[0, start:stop], codes[start:stop]).astype(mx.float32),
                                         codes[start:stop, 1:], reduction="mean")
            mx.eval(sub)
            sub_sum += float(sub) * (stop - start)
            sub_count += stop - start
        del inputs, labels, states, codes, main, sub
        mx.clear_cache()
    result = {"main": main_sum / main_count, "residual": sub_sum / sub_count,
              "clips": len(rows), "main_tokens": main_count, "residual_tokens": sub_count * 15}
    result["joint"] = result["main"] + 0.3 * result["residual"]
    if not all(math.isfinite(result[k]) for k in ("main", "residual", "joint")):
        raise ValueError("Nonfinite held-out objective")
    return result


def audit(model, data, batch_frames):
    indices = frame_indices(data[3].shape[0], batch_frames, 42)
    main, sub = losses(model, data, indices)
    states = model.talker.model(data[0])[:, data[2]:, :]
    pick = mx.array(indices, dtype=mx.int32)
    hidden, codes = states[0, pick], data[3][pick]
    # Check alignment in fp32 so different bf16 prefill/decode GEMM kernels do
    # not masquerade as a teacher-conditioning error. Training remains native.
    parallel = residual_logits(model, hidden, codes, dtype=mx.float32)
    predictor = model.talker.code_predictor
    cache = predictor.make_cache()
    errors = []
    for i in range(15):
        inputs = (mx.concatenate([hidden[:, None, :], model.talker.get_input_embeddings()(codes[:, :1])], axis=1)
                  if i == 0 else predictor.codec_embedding[i - 1](codes[:, i:i + 1]))
        sequential, cache, _ = predictor(inputs.astype(mx.float32), cache=cache, generation_step=i)
        errors.append(mx.mean(mx.abs(parallel[:, i].astype(mx.float32) - sequential[:, -1].astype(mx.float32))))
    changed = mx.concatenate([codes[:, :15], (codes[:, 15:] + 1) % 2048], axis=1)
    leakage = mx.max(mx.abs(residual_logits(model, hidden, changed, dtype=mx.float32) - parallel))
    mx.eval(main, sub, leakage, errors)
    error = max(float(value) for value in errors)
    if error > 1e-4 or float(leakage) != 0:
        raise ValueError(f"Residual inference audit failed: mean_logit_error={error:.6f}, target_leakage={float(leakage):.6f}")
    return {"cached_vs_parallel_fp32_mean_logit_error": error, "final_target_leakage": float(leakage),
            "main_loss": float(main), "residual_loss": float(sub), "first_frame_and_eos_preserved": True}


def manifest(output, ident, rank, alpha):
    weights = output / "best.safetensors"
    return {"format": JOINT_FORMAT, "id": ident, "base_model_id": MODEL_ID,
            "base_model_revision": MODEL_REVISION, "rank": rank, "alpha": alpha,
            "targets": list(JOINT_TARGETS), "scopes": list(JOINT_SCOPES),
            "weights": weights.name, "sha256": digest(weights)}


def train(args):
    from mlx_audio.tts.utils import load_model
    work, output = args.work_dir.resolve(strict=True), args.output.resolve()
    if (output.is_relative_to(work) or work.is_relative_to(output)
            or output.is_relative_to(args.model.resolve()) or args.model.resolve().is_relative_to(output)):
        raise ValueError("Output must not overlap immutable inputs/base")
    rows = load_index(work)
    training = [r for r in rows if r["split"] == "train"]
    held_out = [r for r in rows if r["split"] == "val"]
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    mx.random.seed(args.seed)
    model = load_model(str(args.model.resolve()))
    model.eval()
    data = sample(work, training[0], model.config.talker_config.codec_eos_token_id)
    baseline = evaluate(model, work, held_out, args.frame_batch)
    proof = audit(model, data, args.frame_batch)
    root = install(model, args.rank, args.alpha)
    zero = evaluate(model, work, held_out, args.frame_batch)
    parity = max(abs(baseline[k] - zero[k]) for k in ("main", "residual"))
    if parity > 1e-5:
        raise ValueError("Zero-initialized adapter changes the baseline")
    params = tree_unflatten(list(adapters(root).items()))
    optimizer = optim.AdamW(learning_rate=args.lr, weight_decay=0.01)
    count = sum(x.size for x in adapters(root).values())
    config = {"rank": args.rank, "alpha": args.alpha, "targets": list(JOINT_TARGETS), "scopes": list(JOINT_SCOPES),
              "steps": args.steps, "lr": args.lr, "seed": args.seed, "residual_frames_per_step": args.frame_batch,
              "train_clips": len(training), "validation_clips": len(held_out), "trainable_parameters": count,
              "feature_index_sha256": digest(work / "feature-index.json"), "base_model_id": MODEL_ID,
              "base_revision": MODEL_REVISION, "objective": "main_all_frames_and_eos + 0.3 * all_15_residual_codebooks",
              "validation": "all frames/codebooks of held-out source clips", "memory_limit_gib": args.memory_gib}
    write_json(output / "config.json", config)
    write_json(output / "audit.json", {**proof, "zero_adapter_loss_error": parity})

    def objective(values, data, indices):
        root.update(values)
        main, sub = losses(model, data, indices)
        return main + 0.3 * sub

    derivative = mx.value_and_grad(objective)
    order = list(training)
    rng = random.Random(args.seed)
    rng.shuffle(order)
    best, best_step = float("inf"), 0
    started = time.monotonic()
    with (output / "train.jsonl").open("w") as log:
        for step in range(1, args.steps + 1):
            if step > 1 and (step - 1) % len(order) == 0:
                rng.shuffle(order)
            row = order[(step - 1) % len(order)]
            data = sample(work, row, model.config.talker_config.codec_eos_token_id)
            indices = frame_indices(row["frames"], args.frame_batch, args.seed + step)
            loss, gradients = derivative(params, data, indices)
            mx.eval(loss, gradients)
            leaves = [g for _, g in tree_flatten(gradients)]
            norm = mx.sqrt(sum(mx.sum(g ** 2) for g in leaves))
            branch = {prefix: mx.sqrt(sum(mx.sum(g ** 2) for name, g in tree_flatten(gradients)
                                         if name.startswith(prefix))) for prefix in ("model.", "code_predictor.")}
            mx.eval(norm, branch)
            if not math.isfinite(float(loss)) or not math.isfinite(float(norm)) or any(
                    not math.isfinite(float(value)) or float(value) == 0 for value in branch.values()):
                raise ValueError("Both branches require finite nonzero gradients")
            gradients = tree_map(lambda g: g * mx.minimum(1.0, 1 / (norm + 1e-12)), gradients)
            optimizer.learning_rate = args.lr * min(1, step / 5)
            updated = optimizer.apply_gradients(gradients, params)
            mx.eval(updated, optimizer.state)
            updates = {prefix: mx.sqrt(sum(mx.sum((array - dict(tree_flatten(params))[name]) ** 2)
                                          for name, array in tree_flatten(updated) if name.startswith(prefix)))
                       for prefix in branch}
            mx.eval(updates)
            if any(not math.isfinite(float(v)) or float(v) == 0 for v in updates.values()):
                raise ValueError("Both branches must receive finite nonzero updates")
            params = updated
            root.update(params)
            record = {"step": step, "joint_train_loss": float(loss), "branch_gradient_norms": {k: float(v) for k, v in branch.items()},
                      "branch_update_norms": {k: float(v) for k, v in updates.items()},
                      "seconds": round(time.monotonic() - started, 3), "peak_gib": round(mx.get_peak_memory() / 1024**3, 4)}
            if step % args.eval_every == 0 or step == args.steps:
                validation = evaluate(model, work, held_out, args.frame_batch)
                record["validation"] = validation
                if validation["joint"] < best:
                    best, best_step = validation["joint"], step
                    mx.save_safetensors(str(output / "best.safetensors"), adapters(root))
            log.write(json.dumps(record) + "\n")
            log.flush()
            print(json.dumps(record), flush=True)
            del gradients, leaves, loss, updated, data, branch, updates
            mx.clear_cache()
    root.update(tree_unflatten(list(mx.load(str(output / "best.safetensors")).items())))
    restored = evaluate(model, work, held_out, args.frame_batch)
    if abs(restored["joint"] - best) > 1e-5:
        raise ValueError("Saved candidate fails checkpoint reload parity")
    write_json(output / "adapter.json", manifest(output, args.candidate_id, args.rank, args.alpha))
    summary = {"status": "CANDIDATE_LISTENING_PENDING", "baseline_validation": baseline,
               "best_validation": restored, "best_step": best_step, "peak_gib": mx.get_peak_memory() / 1024**3,
               "training_seconds": time.monotonic() - started, "config": config,
               "reload_absolute_error": abs(restored["joint"] - best), "subjective_quality_verified": False,
               "production_voice_replaced": False}
    write_json(output / "summary.json", summary)
    print(json.dumps(summary), flush=True)


def inspect_candidate(args):
    from mlx_audio.tts.utils import load_model
    model = load_model(str(args.model.resolve()))
    metrics = apply_adapter(model, args.adapter, MODEL_ID, MODEL_REVISION)
    held_out = [r for r in load_index(args.work_dir.resolve()) if r["split"] == "val"]
    result = {"status": "PASS", "adapter": metrics,
              "validation": evaluate(model, args.work_dir.resolve(), held_out, args.frame_batch)}
    write_json(args.output, result)
    print(json.dumps(result), flush=True)


def main():
    os.umask(0o077)
    os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--memory-gib", type=float, default=8)
    parser.add_argument("--frame-batch", type=int, default=16)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("train")
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--candidate-id", default="joint-local-candidate")
    p.add_argument("--steps", type=int, default=99)
    p.add_argument("--rank", type=int, default=32)
    p.add_argument("--alpha", type=float, default=64)
    p.add_argument("--lr", type=float, default=2e-5)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--eval-every", type=int, default=11)
    p = commands.add_parser("inspect")
    p.add_argument("--adapter", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.memory_gib <= 8 or not 2 <= args.frame_batch <= 64:
        parser.error("This local experiment permits 1..8 GiB and 2..64 residual frames per batch")
    if args.command == "train" and (not 1 <= args.steps <= 2000 or not 1 <= args.rank <= 128
            or not math.isfinite(args.alpha) or not 0 < args.alpha <= 256
            or not math.isfinite(args.lr) or not 0 < args.lr <= 1e-3 or args.eval_every < 1):
        parser.error("Invalid bounded training configuration")
    if args.command == "train":
        import re
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args.candidate_id):
            parser.error("Invalid candidate id")
    validate_base(args.model)
    runtime()
    if not mx.metal.is_available():
        raise RuntimeError("Apple Metal required; no silent CPU fallback")
    mx.set_default_device(mx.gpu)
    mx.set_memory_limit(int(args.memory_gib * 1024**3))
    mx.set_cache_limit(256 * 1024**2)
    {"train": train, "inspect": inspect_candidate}[args.command](args)


if __name__ == "__main__":
    main()
