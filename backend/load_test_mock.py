"""Small end-to-end load probe. Use only with the explicitly started Mock consumer."""

import argparse
import concurrent.futures
import json
import statistics
import time
import urllib.error
import urllib.request
import uuid


def request(base, path, data=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    payload = None if data is None else json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base + path, data=payload, headers=headers,
                                 method="POST" if data is not None else "GET")
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=15) as response:
        return json.load(response)


def one(base, mode, index, timeout):
    started = time.monotonic()
    session = request(base, "/api/player/guest", {})
    token = session["token"]
    npc_a = "牧濑红莉栖"
    npc_b = "雪之下雪乃"
    payload = {"message": f"负载测试 {uuid.uuid4()}", "client_request_id": str(uuid.uuid4())}
    if mode == "scene":
        path = "/api/chat/scene"
        payload["npc_ids"] = [npc_a, npc_b]
    else:
        path = "/api/chat"
        payload["npc_id"] = npc_a if mode == "same" else (npc_a if index % 2 else npc_b)
    accepted = request(base, path, payload, token)
    if not accepted.get("success"):
        return {"status": "REJECTED", "duration": time.monotonic() - started}
    task_id = accepted["task_id"]
    while time.monotonic() - started < timeout:
        result = request(base, "/api/chat/result/" + task_id, token=token)
        if result.get("completed"):
            return {"status": result["status"], "duration": time.monotonic() - started}
        time.sleep(0.2)
    return {"status": "CLIENT_TIMEOUT", "duration": time.monotonic() - started}


def percentile(values, fraction):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int((len(ordered) - 1) * fraction + 0.999))]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--mode", choices=["different", "same", "scene"], required=True)
    parser.add_argument("--users", type=int, required=True)
    parser.add_argument("--timeout", type=float, default=90)
    args = parser.parse_args()
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.users) as pool:
        futures = [pool.submit(one, args.base, args.mode, i, args.timeout) for i in range(args.users)]
        results = []
        for future in concurrent.futures.as_completed(futures):
            try:
                results.append(future.result())
            except Exception as exc:
                results.append({"status": type(exc).__name__, "duration": time.monotonic() - started})
    elapsed = time.monotonic() - started
    durations = [item["duration"] for item in results]
    counts = {status: sum(item["status"] == status for item in results)
              for status in sorted({item["status"] for item in results})}
    print(json.dumps({"mode": args.mode, "users": args.users, "elapsed_s": round(elapsed, 3),
                      "throughput_rps": round(args.users / elapsed, 3),
                      "p50_s": round(statistics.median(durations), 3),
                      "p95_s": round(percentile(durations, 0.95), 3),
                      "statuses": counts}, ensure_ascii=False))


if __name__ == "__main__":
    main()
