# 클러스터 멤버 관리와 최대 클리크 알고리즘

Hazelcast가 부분 네트워크 단절(Partial Member Disconnection) 상황에서 어떻게 최적의 클러스터 멤버 목록을 결정하는지, 그 핵심인 **Maximum Clique Problem**과 **Bron-Kerbosch 알고리즘**을 이해하기 위한 참고 문서이다.

---

## 목차

1. [완전 연결 상태에서의 클러스터 멤버 관리](#1-완전-연결-상태에서의-클러스터-멤버-관리)
2. [부분 네트워크 단절 문제](#2-부분-네트워크-단절-문제)
3. [그래프 이론으로의 매핑](#3-그래프-이론으로의-매핑)
4. [최대 클리크 문제 (Maximum Clique Problem)](#4-최대-클리크-문제-maximum-clique-problem)
5. [Bron-Kerbosch 알고리즘](#5-bron-kerbosch-알고리즘)
6. [Hazelcast 구현 분석](#6-hazelcast-구현-분석)
7. [전체 흐름 요약](#7-전체-흐름-요약)

---

## 1. 완전 연결 상태에서의 클러스터 멤버 관리

### 기본 원칙

Hazelcast 클러스터는 **완전 연결(fully connected)** 상태를 유지해야 한다. 즉, 클러스터 내의 모든 멤버가 다른 모든 멤버와 직접 통신할 수 있어야 한다.

```
  완전 연결 클러스터 (4개 멤버)        불완전 연결 (문제 상태)

     A ──── B                          A ──── B
     │╲    ╱│                          │      │
     │  ╲╱  │                          │      │
     │  ╱╲  │                          │      │
     │╱    ╲│                          │      │
     C ──── D                          C ──── D
                                        (A↔D, B↔C 연결 끊김)
```

### 마스터-슬레이브 구조

- **마스터 멤버**: 클러스터에서 가장 오래된 멤버가 마스터 역할을 수행
- **슬레이브 멤버**: 나머지 모든 멤버
- 마스터가 클러스터 멤버십 관리의 중심 역할을 담당

### 하트비트 메커니즘

각 멤버는 주기적으로 다른 멤버에게 하트비트를 보낸다:
- `hazelcast.heartbeat.interval.seconds` (기본값: 5초): 하트비트 전송 주기
- `hazelcast.max.no.heartbeat.seconds` (기본값: 60초): 이 시간 동안 하트비트가 없으면 해당 멤버를 죽은 것으로 판단

### 왜 완전 연결이 필요한가?

분산 데이터 구조(Map, Queue 등)의 정합성을 보장하기 위해서는 모든 멤버 간 통신이 가능해야 한다. 예를 들어:

- **파티션 소유권**: 데이터 파티션의 주 복제본과 백업 복제본이 서로 다른 멤버에 있으므로, 이들 간 통신이 끊기면 데이터 정합성이 깨진다
- **오퍼레이션 라우팅**: 클라이언트 요청이 파티션 소유자로 라우팅되어야 하는데, 멤버 간 연결이 끊기면 라우팅이 실패한다
- **분산 락/트랜잭션**: 여러 멤버에 걸쳐 동작하므로 완전 연결이 전제 조건이다

---

## 2. 부분 네트워크 단절 문제

### 문제 정의

**부분 네트워크 단절(Partial Member Disconnection)** 은 일부 멤버 쌍 사이에서만 네트워크 연결이 끊기는 현상이다. 스플릿 브레인과 다르게, 클러스터가 완전히 두 그룹으로 나뉘지 않고 **일부 연결만 선택적으로** 끊긴다.

```
예시: 5개 멤버 클러스터에서 부분 단절 발생

  A ──── B ──── C
  │             │
  D             E

연결 상태:
  A↔B: 연결됨     B↔C: 연결됨     A↔C: 끊어짐
  A↔D: 연결됨     B↔D: 끊어짐     B↔E: 끊어짐
  C↔E: 연결됨     D↔E: 끊어짐     A↔E: 끊어짐
  C↔D: 끊어짐
```

### 기존 문제점

Hazelcast 5.3.1 이전에는 부분 단절 감지 메커니즘이 없었다:
- 마스터와 직접 연결이 끊긴 멤버만 클러스터에서 제거됨
- 슬레이브 간의 연결 단절은 감지되지 않음
- 결과적으로 데이터 정합성 문제, 오퍼레이션 타임아웃 등의 장애가 발생

### 해결 전략

마스터 멤버가 슬레이브 간의 연결 상태를 수집하고, **최소한의 멤버를 제거하여 나머지가 완전 연결 상태를 유지**하도록 한다.

이것이 바로 **최대 클리크 문제**로 귀결된다.

---

## 3. 그래프 이론으로의 매핑

### 클러스터를 그래프로 표현

클러스터의 네트워크 상태를 **무방향 그래프(undirected graph)** 로 모델링한다:

| 클러스터 개념 | 그래프 개념 |
|---|---|
| 멤버 (Member) | 정점 (Vertex) |
| 두 멤버 간 연결 상태 | 간선 (Edge) |
| 완전 연결 클러스터 | 완전 그래프 (Complete Graph) |
| 부분 단절된 클러스터 | 불완전 그래프 |

### 문제 변환

```
클러스터 문제:
"부분 단절 상황에서, 최소한의 멤버를 제거하여
 나머지 멤버들이 완전 연결 상태를 유지하도록 하라"

     ↓ 그래프 이론으로 변환

그래프 문제:
"그래프에서 가장 큰 완전 부분 그래프(클리크)를 찾아라.
 클리크에 포함되지 않는 정점들이 제거 대상이다"
```

### 구체적 예시

```
클러스터 상태:
  멤버 = {A, B, C, D, E}
  끊어진 연결 = {A↔C, B↔D, B↔E, C↔D, D↔E, A↔E}

연결 그래프:
  A ── B        (A와 B는 연결됨)
  A ── D        (A와 D는 연결됨)
  B ── C        (B와 C는 연결됨)
  C ── E        (C와 E는 연결됨)

이 그래프에서 최대 클리크(서로 모두 연결된 가장 큰 그룹)를 찾으면:
  → {A, B} 크기 2  또는  {B, C} 크기 2  또는  {A, D} 크기 2  등

가장 큰 클리크 크기가 2이므로, 5-2=3개 멤버를 제거해야 한다.
```

---

## 4. 최대 클리크 문제 (Maximum Clique Problem)

### 정의

- **클리크(Clique)**: 그래프의 부분 집합으로, 집합 내의 모든 정점 쌍이 간선으로 연결된 것
- **극대 클리크(Maximal Clique)**: 어떤 정점을 추가해도 더 이상 클리크가 되지 않는 클리크
- **최대 클리크(Maximum Clique)**: 가장 크기가 큰 극대 클리크

```
그래프 예시:

  1 ── 2 ── 3
  │ ╲  │    │
  │  ╲ │    │
  4    5 ── 3

클리크들:
  {1, 2}       - 클리크 (크기 2)
  {1, 2, 5}    - 극대 클리크 (크기 3) ← 5를 추가해도 1,2,5 모두 연결됨
  {2, 3, 5}    - 극대 클리크 (크기 3)
  {1, 4}       - 극대 클리크 (크기 2)

최대 클리크: {1, 2, 5} 또는 {2, 3, 5} (크기 3)
```

### 계산 복잡도

최대 클리크 문제는 **NP-Complete**이다:
- 정점이 N개인 그래프에서 모든 부분 집합을 확인하면 O(2^N)
- 다항 시간 알고리즘이 알려져 있지 않음
- 그러나 **실제 클러스터 규모(수십~수백 노드)에서는 실용적으로 해결 가능**

### Hazelcast에서의 의미

최대 클리크 = **현재 네트워크 상태에서 서로 완전히 연결된 가장 큰 멤버 그룹**

따라서:
- 최대 클리크에 속한 멤버 → **유지** (클러스터에 남음)
- 최대 클리크에 속하지 않은 멤버 → **제거** (클러스터에서 퇴출)

이렇게 하면 **최소한의 멤버만 제거하면서** 남은 멤버들의 완전 연결을 보장한다.

---

## 5. Bron-Kerbosch 알고리즘

### 개요

Bron-Kerbosch 알고리즘은 무방향 그래프에서 **모든 극대 클리크를 열거**하는 알고리즘이다 (1973년, Coen Bron & Joep Kerbosch).

Hazelcast는 이 알고리즘을 사용하여 모든 극대 클리크를 찾고, 그 중 **가장 큰 것(최대 클리크)** 을 선택한다.

### 핵심 아이디어

3개의 집합을 관리하면서 재귀적으로 탐색한다:

| 집합 | 역할 | 의미 |
|---|---|---|
| **R** (potentialClique) | 현재 구성 중인 클리크 | "이 정점들은 서로 모두 연결되어 있다" |
| **P** (candidates) | 클리크에 추가할 수 있는 후보 정점 | "아직 검사하지 않은 이웃들" |
| **X** (alreadyFound) | 이미 처리한 정점 | "이미 다른 분기에서 처리했으므로 중복 방지" |

### 알고리즘 동작 단계

```
BronKerbosch(R, P, X):
    if P가 비어있고 X가 비어있으면:
        R은 극대 클리크 → 결과에 추가
        return

    P의 각 정점 v에 대해:
        BronKerbosch(
            R ∪ {v},              // v를 클리크에 추가
            P ∩ N(v),             // 후보에서 v의 이웃만 남김
            X ∩ N(v)              // 처리완료에서 v의 이웃만 남김
        )
        P에서 v 제거
        X에 v 추가
```

여기서 `N(v)`는 정점 v의 이웃 집합이다.

### 단계별 실행 예시

```
그래프:
  A ── B ── C
  │    │
  D    E

간선: A-B, A-D, B-C, B-E

초기: R={}, P={A,B,C,D,E}, X={}
```

**실행 추적:**

```
[1] BronKerbosch(R={}, P={A,B,C,D,E}, X={})
 │
 ├─ v=A를 선택
 │  R={A}, P={B,D} (A의 이웃 ∩ P), X={}
 │  │
 │  ├─ v=B를 선택
 │  │  R={A,B}, P={} (B의 이웃 ∩ {D} = {}), X={}
 │  │  P와 X 모두 비어있음 → ★ 극대 클리크: {A,B}  ...아직 아님!
 │  │  D가 B의 이웃이 아니므로 P에서 제외됨
 │  │  실제로 B의 이웃은 {A,C,E}, P에서 {B,D}와 교집합하면 {}
 │  │  → P=X={} → 극대 클리크 {A,B} 보고
 │  │
 │  └─ v=D를 선택
 │     R={A,D}, P={} (D의 이웃 ∩ {} = {}), X={}
 │     → P=X={} → 극대 클리크 {A,D} 보고
 │
 ├─ v=B를 선택 (A는 X로 이동)
 │  R={B}, P={C,E} (B의 이웃 ∩ {B,C,D,E}에서 B 제외), X={A}∩N(B)={A}
 │  │
 │  ├─ v=C를 선택
 │  │  R={B,C}, P={}, X={}
 │  │  → 극대 클리크 {B,C} 보고
 │  │
 │  └─ v=E를 선택
 │     R={B,E}, P={}, X={}
 │     → 극대 클리크 {B,E} 보고
 │
 ├─ ... (C, D, E에 대해서도 반복하지만 X에 의해 가지치기됨)

결과: 극대 클리크 = {A,B}, {A,D}, {B,C}, {B,E}
최대 클리크 = {A,B} 또는 {A,D} 또는 {B,C} 또는 {B,E} (모두 크기 2)
```

### 가지치기 (Pruning) 최적화

Hazelcast 구현에서 사용하는 핵심 최적화:

```java
// alreadyFound(X)의 어떤 정점이 모든 candidates(P)와 연결되어 있으면
// 해당 분기를 탐색할 필요가 없다 (이미 처리된 정점이 피벗 역할)
for (V v : alreadyFound) {
    if (candidates.stream().allMatch(c -> graph.containsEdge(v, c))) {
        return;  // 가지치기!
    }
}
```

이유: X에 있는 정점 v가 P의 모든 정점과 연결되어 있다면, v를 포함하는 더 큰 클리크가 이미 발견되었으므로, 현재 분기에서 찾을 수 있는 극대 클리크는 모두 이전 분기의 부분 집합이다.

---

## 6. Hazelcast 구현 분석

### 관련 소스 코드

| 클래스 | 경로 | 역할 |
|---|---|---|
| `Graph<V>` | `internal/util/graph/Graph.java` | 무방향 그래프 자료구조 |
| `BronKerboschCliqueFinder<V>` | `internal/util/graph/BronKerboschCliqueFinder.java` | Bron-Kerbosch 알고리즘 구현 |
| `PartialDisconnectionHandler` | `internal/cluster/impl/PartialDisconnectionHandler.java` | 부분 단절 감지 및 해결 |

### Graph 클래스

인접 리스트(adjacency map) 기반의 단순 무방향 그래프이다:

```java
public class Graph<V> {
    private Map<V, Set<V>> adjacencyMap = new HashMap<>();

    public void add(V v) {
        adjacencyMap.putIfAbsent(v, new HashSet<>());
    }

    public void connect(V v1, V v2) {
        // 자기 자신과의 연결 무시 (simple graph)
        if (v1.equals(v2)) return;
        // 양방향으로 간선 추가
        adjacencyMap.computeIfAbsent(v1, v -> new HashSet<>()).add(v2);
        adjacencyMap.computeIfAbsent(v2, v -> new HashSet<>()).add(v1);
    }

    public boolean containsEdge(V v1, V v2) {
        return adjacencyMap.getOrDefault(v1, emptySet()).contains(v2);
    }
}
```

핵심 특성:
- **Simple graph**: 자기 자신으로의 간선(self-loop) 없음
- **무방향(Undirected)**: `connect(A, B)`하면 A→B와 B→A 모두 추가
- **간선 검사 O(1)**: HashSet 기반이므로 `containsEdge`가 상수 시간

### BronKerboschCliqueFinder 클래스

```java
public class BronKerboschCliqueFinder<V> {
    private final Graph<V> graph;
    private final long nanos;             // 시간 제한 (나노초)
    private boolean timeLimitReached;     // 시간 초과 여부
    private List<Set<V>> maximumCliques;  // 결과: 최대 클리크 목록
```

**시간 제한 기능**: NP-Hard 문제이므로 무한히 실행될 수 있다. Hazelcast는 타임아웃을 설정하여 알고리즘이 지정된 시간 내에 완료되지 않으면 중단한다.

```java
// 프로퍼티: hazelcast.partial.member.disconnection.resolution.algorithm.timeout.seconds
// 기본값: 5초
```

**addMaxClique 메서드** - 최대 클리크만 유지하는 전략:

```java
private void addMaxClique(Collection<V> potentialClique) {
    if (maximumCliques.isEmpty()
        || potentialClique.size() == maximumCliques.get(0).size()) {
        // 같은 크기면 목록에 추가 (동률인 최대 클리크가 여러 개일 수 있음)
        maximumCliques.add(new HashSet<>(potentialClique));
    } else if (potentialClique.size() > maximumCliques.get(0).size()) {
        // 더 큰 클리크 발견 시 기존 결과를 모두 버리고 새로 시작
        maximumCliques.clear();
        maximumCliques.add(new HashSet<>(potentialClique));
    }
    // 더 작은 클리크는 무시
}
```

### PartialDisconnectionHandler 클래스

마스터 멤버에서만 사용되며, 전체 흐름을 조율한다.

**1단계: 단절 정보 수집 (`update`)**

각 슬레이브 멤버는 자신이 감지한 단절 정보를 마스터에게 보고한다:

```java
boolean update(MemberImpl member, long timestamp,
               Collection<MemberImpl> disconnectedMembers) {
    // timestamp 기반으로 최신 정보만 수용
    // 양방향 중 한쪽이라도 새로운 단절을 보고하면 업데이트
}
```

**2단계: 해결 시점 판단 (`shouldResolvePartialDisconnections`)**

```java
boolean shouldResolvePartialDisconnections(long timestamp) {
    // 단절이 보고되었고, 마지막 보고 이후 충분한 시간이 지나면 해결 시작
    return !disconnections.isEmpty()
           && timestamp - lastUpdated >= detectionIntervalMs;
}
```

일정 시간 동안 기다리는 이유: 일시적인 네트워크 지터(jitter)로 인한 오탐을 방지하기 위해서이다.

**3단계: 해결 (`resolve`) - 핵심 로직**

```java
Collection<MemberImpl> resolve(
        Map<MemberImpl, Set<MemberImpl>> disconnections) throws TimeoutException {

    // (1) 관련 멤버 전체 수집
    Set<MemberImpl> members = new HashSet<>();
    disconnections.forEach((k, v) -> {
        members.add(k);
        members.addAll(v);
    });

    // (2) 연결 그래프 구축 (단절되지 않은 멤버 쌍을 간선으로 연결)
    Graph<MemberImpl> connectivityGraph = buildConnectionGraph(members, disconnections);

    // (3) Bron-Kerbosch로 최대 클리크 계산
    BronKerboschCliqueFinder<MemberImpl> cliqueFinder = createCliqueFinder(connectivityGraph);
    Collection<Set<MemberImpl>> maxCliques = cliqueFinder.computeMaxCliques();

    // (4) 타임아웃 체크
    if (cliqueFinder.isTimeLimitReached()) {
        throw new TimeoutException("알고리즘 시간 초과!");
    }

    // (5) 최대 클리크에 속하지 않는 멤버 = 제거 대상
    Collection<MemberImpl> membersToRemove = new HashSet<>(members);
    membersToRemove.removeAll(maxCliques.iterator().next());

    return membersToRemove;
}
```

**연결 그래프 구축 (`buildConnectionGraph`)**

```java
private Graph<MemberImpl> buildConnectionGraph(
        Set<MemberImpl> members,
        Map<MemberImpl, Set<MemberImpl>> disconnections) {

    Graph<MemberImpl> graph = new Graph<>();
    members.forEach(graph::add);

    for (MemberImpl member1 : members) {
        for (MemberImpl member2 : members) {
            // 단절되지 않은 쌍만 간선으로 연결
            if (!isDisconnected(disconnections, member1, member2)) {
                graph.connect(member1, member2);
            }
        }
    }
    return graph;
}
```

**양방향 단절 체크**:

```java
private boolean isDisconnected(Map<MemberImpl, Set<MemberImpl>> disconnections,
                                MemberImpl member1, MemberImpl member2) {
    // 어느 한쪽이라도 단절을 보고하면 단절로 판단
    return disconnections.getOrDefault(member1, emptySet()).contains(member2)
        || disconnections.getOrDefault(member2, emptySet()).contains(member1);
}
```

---

## 7. 전체 흐름 요약

### 시퀀스 다이어그램

```
시간 →

[슬레이브 A]     [슬레이브 B]     [슬레이브 C]     [마스터 M]
     │                │                │                │
     │  A↔C 단절 감지  │                │                │
     ├───────────────────────────────────────────────────▶│
     │                │                │  C↔A 단절 감지  │
     │                │                ├────────────────▶│
     │                │                │                │
     │                │                │    (대기 시간)   │
     │                │                │                │
     │                │                │   ┌──────────┐ │
     │                │                │   │해결 시작  │ │
     │                │                │   │          │ │
     │                │                │   │1. 그래프  │ │
     │                │                │   │   구축    │ │
     │                │                │   │          │ │
     │                │                │   │2. 최대   │ │
     │                │                │   │  클리크   │ │
     │                │                │   │  계산     │ │
     │                │                │   │          │ │
     │                │                │   │3. 제거   │ │
     │                │                │   │  대상 결정│ │
     │                │                │   └──────────┘ │
     │                │                │                │
     │                │           [C 제거됨]            │
     │                │                                 │
     ▼                ▼                                 ▼
 [A, B, M 완전 연결 클러스터로 계속 운영]
```

### 구체적인 계산 예시

5개 멤버 클러스터에서 부분 단절이 발생한 경우:

```
멤버: {M(마스터), A, B, C, D}

단절 보고:
  A → {C}    (A가 C와의 연결 끊김을 보고)
  C → {A, D} (C가 A, D와의 연결 끊김을 보고)
  D → {C}    (D가 C와의 연결 끊김을 보고)

1단계: 연결 그래프 구축
   단절된 쌍: A↔C, C↔D
   나머지는 모두 연결됨

   M ── A ── B
   │╲   ╲   ╱│
   │  ╲  ╲╱  │
   │   ╲  D  │
   │    ╲    ╱
   │     ╲  ╱
   C      ╲╱
   │       B
   └── M, B  (C는 M, B와만 연결)

   간선 정리:
   M-A, M-B, M-C, M-D
   A-B, A-D
   B-C, B-D
   (A-C 없음, C-D 없음)

2단계: 극대 클리크 열거 (Bron-Kerbosch)
   {M, A, B, D}  - 크기 4  ← 이 4개 멤버는 서로 모두 연결됨!
   {M, B, C}     - 크기 3

3단계: 최대 클리크 = {M, A, B, D} (크기 4)

4단계: 제거 대상 = {M, A, B, C, D} - {M, A, B, D} = {C}

결과: C만 제거하면 나머지 {M, A, B, D}는 완전 연결 상태!
```

### 설정 프로퍼티 요약

| 프로퍼티 | 기본값 | 설명 |
|---|---|---|
| `hazelcast.partial.member.disconnection.resolution.heartbeat.count` | 0 (비활성) | 부분 단절 감지를 위해 기다릴 하트비트 횟수. 0이면 기능 비활성화 |
| `hazelcast.partial.member.disconnection.resolution.algorithm.timeout.seconds` | 5 | Bron-Kerbosch 알고리즘의 최대 실행 시간 |
| `hazelcast.heartbeat.interval.seconds` | 5 | 하트비트 전송 주기 |
| `hazelcast.max.no.heartbeat.seconds` | 60 | 하트비트 타임아웃 |

### 유사 시스템 구현 시 고려사항

1. **NP-Hard 문제에 대한 시간 제한**: 클러스터 규모가 클수록 알고리즘 실행 시간이 기하급수적으로 늘어날 수 있으므로, 반드시 타임아웃을 설정해야 한다
2. **양방향 단절 판단**: 한쪽만 보고해도 단절로 간주하는 보수적 접근이 안전하다
3. **대기 시간(Detection Interval)**: 일시적 네트워크 지터를 걸러내기 위해 즉시 반응하지 않고 일정 시간 단절이 지속되는지 확인한다
4. **동률 처리**: 최대 클리크가 여러 개일 때 어떤 것을 선택할지에 대한 전략이 필요하다 (Hazelcast는 첫 번째를 선택)
5. **마스터 보호**: 마스터 멤버는 이 과정에서 제거 대상이 되면 안 된다 (마스터와의 연결이 끊기면 이미 다른 메커니즘으로 처리됨)
