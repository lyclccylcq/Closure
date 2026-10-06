#!/usr/bin/env python3
"""Attach or detach one T4 on an explicitly configured stopped N1 VM."""
import json
import argparse
import os
import subprocess
import time
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--project", default=os.environ.get("CLOSURE_TTS_PROJECT"),
                    required=not bool(os.environ.get("CLOSURE_TTS_PROJECT")),
                    help="Google Cloud project ID (or set CLOSURE_TTS_PROJECT)")
parser.add_argument("--zone", default=os.environ.get("CLOSURE_TTS_ZONE"),
                    required=not bool(os.environ.get("CLOSURE_TTS_ZONE")),
                    help="VM zone (or set CLOSURE_TTS_ZONE)")
parser.add_argument("--instance", default=os.environ.get("CLOSURE_TTS_INSTANCE"),
                    required=not bool(os.environ.get("CLOSURE_TTS_INSTANCE")),
                    help="VM name (or set CLOSURE_TTS_INSTANCE)")
parser.add_argument("--detach", action="store_true", help="Remove the GPU configuration before CPU rollback")
args = parser.parse_args()
PROJECT, ZONE, INSTANCE = args.project, args.zone, args.instance
BASE = f"https://compute.googleapis.com/compute/v1/projects/{PROJECT}/zones/{ZONE}"
token = subprocess.check_output(
    ["/opt/homebrew/bin/gcloud", "auth", "print-access-token"], text=True,
    env=dict(os.environ, CLOUDSDK_CORE_DISABLE_FILE_LOGGING="true")).strip()


def call(path, body=None):
    request = urllib.request.Request(BASE + path, data=None if body is None else json.dumps(body).encode(),
                                    headers={"Authorization": "Bearer " + token,
                                             "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def wait(operation):
    while operation.get("status") != "DONE":
        time.sleep(2)
        operation = call("/operations/" + operation["name"])
    if "error" in operation:
        raise RuntimeError(json.dumps(operation["error"]))
    print(json.dumps({"operation": operation["operationType"], "status": "DONE"}), flush=True)


instance = call("/instances/" + INSTANCE)
assert instance["status"] == "TERMINATED", "Stop the VM before attaching GPU"
assert instance["machineType"].endswith("/n1-standard-4"), "Expected n1-standard-4 machine type"
assert int(instance["scheduling"]["maxRunDuration"]["seconds"]) == 14400, "Expected four-hour limit"
assert instance["scheduling"]["instanceTerminationAction"] == "STOP", "Expected automatic STOP"
if args.detach:
    wait(call("/instances/" + INSTANCE + "/setMachineResources", {"guestAccelerators": []}))
wait(call("/instances/" + INSTANCE + "/setScheduling", {
    "onHostMaintenance": "MIGRATE" if args.detach else "TERMINATE", "automaticRestart": args.detach,
    "preemptible": False, "provisioningModel": "STANDARD",
    "maxRunDuration": {"seconds": "14400"}, "instanceTerminationAction": "STOP"}))
if not args.detach:
    wait(call("/instances/" + INSTANCE + "/setMachineResources", {
        "guestAccelerators": [{"acceleratorCount": 1,
                              "acceleratorType": BASE + "/acceleratorTypes/nvidia-tesla-t4"}]}))
instance = call("/instances/" + INSTANCE)
print(json.dumps({"status": instance["status"], "machineType": instance["machineType"],
                  "accelerators": instance.get("guestAccelerators"),
                  "scheduling": instance["scheduling"]}), flush=True)
