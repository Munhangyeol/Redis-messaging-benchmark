import requests
import time

PRODUCER = "http://localhost:8080"
CONSUMER = "http://localhost:8081"
N = 10000


PATTERNS = ["queue", "stream-xread", "stream-group", "pubsub"]


def benchmark(pattern: str, n: int = N):

    send_url = f"{PRODUCER}/{pattern}/send"
    print(f"\n[{pattern.upper()}] 시작 (메시지 {n:,}개)")

    # Session을 사용해 Keep-Alive로 소켓 재사용 (Windows 소켓 고갈 방지)
    with requests.Session() as producer_session, requests.Session() as consumer_session:
        # 1. Consumer 시작
        res = consumer_session.post(f"{CONSUMER}/{pattern}/start")
        res.raise_for_status()
        print(f"  Consumer 시작 완료")

        # 2. 메시지 전송
        start = time.time()
        for i in range(n):
            print(f"\r  Producer 전송 중... {i + 1:,}/{n:,}", end="")
            producer_session.post(send_url, data=f"message-{i}")
        elapsed = time.time() - start
        print(f"  Producer 전송 완료: {elapsed:.2f}s ({n / elapsed:,.0f} msg/sec)")

        # 3. Producer 완료 신호 → Consumer가 남은 메시지 모두 처리 후 종료
        res = consumer_session.post(f"{CONSUMER}/{pattern}/finish", timeout=120)
        res.raise_for_status()
        result = res.json()
        print(f"  Consumer 결과: {result}")
        return result


def main():
    for i in range(5):  # 3번 반복
        print(f"\n========== 벤치마크 횟수: {i + 1} ==========")
        results = {}
        for pattern in PATTERNS:
            try:
                results[pattern] = benchmark(pattern)
            except requests.exceptions.ConnectionError as e:
                print(f"  [ERROR] 연결 실패: {e}")
            except Exception as e:
                print(f"  [ERROR] {e}")

        print("\n========== 최종 결과 ==========")
        for pattern, result in results.items():
            throughput = result.get("throughput", "N/A")
            duration = result.get("durationMs", "N/A")
            total = result.get("totalMessages", "N/A")
            print(f"  {pattern:<15} {throughput:<25} (총 {total:,}건 / {duration}ms)")


if __name__ == "__main__":
    main()
