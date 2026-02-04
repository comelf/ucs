# Apache Pinot Multi-Stage Query Execution Reference

> **Tags:** `#multi-stage` `#distributed-query` `#Calcite` `#JOIN` `#exchange` `#mailbox` `#hash-join` `#broadcast` `#gRPC` `#pinot` `#query-planner` `#PRelNode`

---

## 1. 개념 요약

Multi-Stage Query Engine은 **SQL을 여러 스테이지(단계)로 분할하여 분산 실행**하는 엔진이다.
Leaf(데이터 스캔) → Intermediate(중간 처리) → Root(최종 집계) 순서로 파이프라인 실행하며,
스테이지 간 데이터는 **Mailbox** (gRPC 기반)로 전달된다.

```
기존 Single-Stage:  각 서버에서 독립 실행 → 브로커에서 단순 병합
                    (JOIN 불가, 서브쿼리 제한)

Multi-Stage:        SQL → 여러 스테이지로 분할 → 서버 간 데이터 교환
                    (분산 JOIN, 윈도우 함수, 서브쿼리 지원)
```

---

## 2. 기술 원리

### 2.1 Volcano/Iterator 실행 모델

Multi-Stage Engine은 **Volcano 모델(Iterator 모델)**을 기반으로 한다. 1990년 Goetz Graefe가 제안한 이 모델에서, 각 연산자(Operator)는 `getNextBlock()` 메서드를 통해 데이터를 한 블록씩 상위 연산자에게 전달한다.

```
전통적 Materialization 모델:
  각 연산자가 전체 결과를 메모리에 저장한 후 다음 연산자에 전달
  → 메모리 사용량 = 각 단계의 전체 결과 크기의 합

Volcano/Iterator 모델 (Pinot 사용):
  각 연산자가 한 블록(~1000행)씩 처리하고 즉시 전달
  → 메모리 사용량 = 파이프라인 내 블록 수 × 블록 크기
  → 전체 결과를 메모리에 올리지 않아도 됨

  LeafOp.getNextBlock() → [1000행]
    → AggOp.getNextBlock() → [집계 결과]
      → SendOp.getNextBlock() → gRPC 전송

  각 연산자는 하위 연산자의 getNextBlock()을 호출하여 "pull" 방식으로 동작
```

Pinot는 이를 블록 단위(vectorized)로 확장하여 행 단위 Iterator보다 높은 처리량을 달성한다.

### 2.2 MPP (Massively Parallel Processing) 아키텍처

Multi-Stage Engine은 **MPP 아키텍처**를 따른다. 쿼리를 독립적인 스테이지로 분할하고, 각 스테이지를 여러 워커(서버)에서 병렬 실행한다.

```
Shared-Nothing 원칙:
  각 워커는 자신의 데이터만 처리 (다른 워커의 메모리 접근 없음)
  워커 간 데이터 교환은 오직 Mailbox(gRPC)를 통해서만 수행

병렬성의 두 축:
  1. 파이프라인 병렬성 (Pipeline Parallelism):
     Stage 1(스캔)과 Stage 0(집계)이 동시 실행
     → Stage 1이 블록을 보내는 동안 Stage 0이 이전 블록을 처리

  2. 데이터 병렬성 (Data Parallelism):
     같은 Stage의 여러 워커가 서로 다른 데이터 파티션을 병렬 처리
     → 4개 워커 → 이론적 4배 처리량
```

### 2.3 Apache Calcite 기반 쿼리 최적화

Calcite는 **비용 기반 최적화(Cost-Based Optimization, CBO)**를 제공하는 SQL 프레임워크이다. Pinot는 Calcite의 논리 계획을 받아 물리 계획으로 변환한다.

```
Calcite의 역할:
  1. SQL 파싱 → AST(Abstract Syntax Tree) 생성
  2. AST → RelNode(관계 대수) 변환
  3. 논리 최적화 규칙 적용:
     - Predicate Pushdown: WHERE 조건을 스캔 단계로 내림
     - Projection Pruning: 불필요한 컬럼 제거
     - Join Reordering: 최적 조인 순서 결정

Pinot 확장:
  RelNode → PRelNode 변환 시 분산 실행에 특화된 규칙 추가:
  - LeafStageWorkerAssignmentRule: 데이터 위치 기반 워커 할당
  - AggregatePushdownRule: 집계를 Leaf 스테이지로 푸시다운
  - ExchangeStrategy 결정: 데이터 분배 방식 선택
```

### 2.4 Exchange와 Shuffle의 원리

Exchange는 분산 쿼리의 핵심 개념으로, **스테이지 간 데이터를 재분배(shuffle)**하는 연산이다. 관계 대수에서는 존재하지 않는, 분산 실행을 위해 추가된 물리 연산자이다.

```
Hash Exchange의 원리:
  목적: 같은 키의 행을 같은 워커로 모으기 (JOIN, GROUP BY에 필수)

  방법:
    hash(key) % numWorkers → 목적지 워커 결정

  왜 필요한가:
    GROUP BY city에서 "Seoul"이 서버 A와 서버 B에 분산되어 있으면
    각 서버의 부분 합을 한 곳에 모아야 최종 합계 계산 가능
    → hash("Seoul") = 3 → 워커 3으로 모든 "Seoul" 행 전송

Broadcast Exchange의 원리:
  목적: 작은 테이블을 모든 워커에 복제

  왜 필요한가:
    큰 테이블(orders)은 파티셔닝하고
    작은 테이블(cities)은 모든 워커에 복제하면
    각 워커가 독립적으로 JOIN 가능
    → 네트워크 비용: |small_table| × num_workers
    → Hash 양쪽보다 저렴 (|small_table| ≪ |large_table| 일 때)

Singleton Exchange:
  모든 결과를 한 워커(보통 브로커)로 집중
  → 최종 ORDER BY, LIMIT 처리
```

### 2.5 Hash Join의 원리

Hash Join은 **Build-Probe** 2단계로 동작하는 조인 알고리즘이다. Nested Loop Join의 O(N×M) 대비 O(N+M) 시간 복잡도를 제공한다.

```
Build 단계 (오른쪽/작은 테이블):
  해시 테이블 구성: key → [row1, row2, ...]
  시간: O(M), 공간: O(M)

Probe 단계 (왼쪽/큰 테이블):
  각 행의 JOIN 키로 해시 테이블 조회
  매칭되는 행들을 결합하여 출력
  시간: O(N), 공간: O(1) (스트리밍)

총 시간: O(N + M) vs Nested Loop O(N × M)

메모리 제약:
  Build 측 테이블이 메모리에 올라가야 함
  → Broadcast로 작은 테이블을 Build 측으로 선택
  → 양쪽 모두 크면 Hash Exchange로 파티셔닝 후 워커별 작은 Build
```

### 2.6 분산 집계 3단계 (Leaf → Intermediate → Final)

분산 환경에서 집계 함수를 올바르게 처리하려면 함수를 분해하여 3단계로 나누어 실행해야 한다.

```
함수 분류:
  Distributive: SUM, COUNT, MIN, MAX
    → partial + merge가 정확히 같은 결과
    → SUM(A ∪ B) = SUM(A) + SUM(B)

  Algebraic: AVG, STDDEV
    → 중간 상태 변환 필요
    → AVG = SUM / COUNT (두 값을 별도 전달 후 나누기)

  Holistic: MEDIAN, MODE
    → 분해 불가, 전체 데이터 필요
    → 근사 알고리즘(T-Digest 등) 사용

Pinot의 3단계 실행:
  Leaf:         aggregateInitial(rows) → intermediateResult
  Intermediate: aggregateMerge(result1, result2) → mergedResult
  Final:        aggregateExtract(mergedResult) → finalValue

  예: AVG(amount)
    Leaf:         {sum=3500, count=3}
    Intermediate: {sum=3500+1200, count=3+2} = {sum=4700, count=5}
    Final:        4700 / 5 = 940.0
```

---

## 3. 쿼리 수명주기 전체 흐름

```
Phase 1: SQL → Logical Plan (브로커)
═══════════════════════════════════
  SQL 입력
    ↓
  CalciteSqlParser (SQL 파싱)
    ↓
  Calcite RelOptPlanner (논리 최적화)
    ↓
  RelNode 트리 (Calcite 논리 계획)

Phase 2: Logical → Physical Plan (브로커)
═══════════════════════════════════
  RelNode 트리
    ↓
  RelToPRelConverter (PRelNode 변환)
    ↓
  물리 최적화 규칙 적용:
    - LeafStageWorkerAssignmentRule (리프 워커 할당)
    - WorkerExchangeAssignmentRule (중간 워커 할당)
    - AggregatePushdownRule (집계 푸시다운)
    - SortPushdownRule (정렬 푸시다운)
    ↓
  PRelNode 트리 (워커 할당 + 분배 전략 포함)

Phase 3: Plan Fragment 생성 (브로커)
═══════════════════════════════════
  PRelNode 트리
    ↓
  PlanFragmentAndMailboxAssignment.compute()
    ↓
  Exchange 노드에서 분할:
    - 송신 프래그먼트 + MailboxSendNode
    - 수신 프래그먼트 + MailboxReceiveNode
    - Mailbox 연결 계산
    ↓
  PlanFragment[] + MailboxInfo[]

Phase 4: 쿼리 디스패치 (브로커)
═══════════════════════════════════
  DispatchableSubPlan
    ↓
  QueryDispatcher.submit():
    프래그먼트별 → 워커별 → gRPC로 전송
    ↓
  모든 워커에 쿼리 분배 완료

Phase 5: 실행 (워커/서버)
═══════════════════════════════════
  수신된 프래그먼트
    ↓
  PlanNode → Operator 체인 변환
    ↓
  LeafOperator (테이블 스캔)
    → FilterOperator → ProjectOperator
    → AggregateOperator
    → MailboxSendOperator (다음 스테이지로 전송)
    ↓
  블록 단위 스트리밍 전송

Phase 6: 결과 수집 (브로커)
═══════════════════════════════════
  Stage 0 (Root):
    MailboxReceiveOperator (리프 결과 수신)
    → 최종 집계/정렬
    → 클라이언트에 반환
```

---

## 4. Exchange 전략 (데이터 분배 방식)

소스: `ExchangeStrategy.java`

스테이지 간 데이터를 어떻게 라우팅할지 결정하는 전략.

| 전략 | 키 필요 | 동작 | 용도 |
|------|---------|------|------|
| `SINGLETON` | N | 단일 수신자에게 전체 전송 | 최종 집계 |
| `IDENTITY` | N | 스트림 X → 수신자 X (1:1) | 동일 파티션 유지 |
| `HASH_DISTRIBUTED` | Y | 키 해시 기반 파티셔닝 | JOIN, GROUP BY |
| `BROADCAST` | N | 모든 수신자에게 복제 | 작은 테이블 JOIN |
| `RANDOM` | N | 랜덤 워커 분배 | 부하 분산 |
| `SUB_PARTITIONING_HASH` | Y | 파티션 병합 + 해시 | 파티션 축소 |
| `SUB_PARTITIONING_RR` | N | 파티션 병합 + 라운드로빈 | 파티션 축소 |
| `COALESCING_PARTITIONING` | Y | 분배 유지하며 파티션 병합 | 파티션 최적화 |

---

## 5. Mailbox 통신

### 4.1 MailboxSendNode / MailboxReceiveNode

소스: `MailboxSendNode.java`, `MailboxReceiveNode.java`

```
Stage 1 (Leaf)                    Stage 0 (Root)
┌──────────────────┐              ┌──────────────────┐
│ LeafOperator     │              │ MailboxReceive    │
│   ↓              │              │   ↓              │
│ AggregateOp      │              │ FinalAggregateOp │
│   ↓              │              │   ↓              │
│ MailboxSendOp    │─── gRPC ───→│ Result           │
└──────────────────┘              └──────────────────┘

MailboxSendNode 필드:
  _receiverStages:   수신 스테이지 ID 집합
  _exchangeType:     교환 유형
  _distributionType: 분배 방식
  _keys:             파티셔닝 키 (해시 교환용)
  _hashFunction:     해시 함수

MailboxReceiveNode 필드:
  _senderStageId:    송신 스테이지 ID
  _exchangeType:     교환 유형
  _sort:             정렬 여부
  _sortedOnSender:   송신 측 정렬 여부
```

### 4.2 BlockExchange 구현

소스: `BlockExchange.java`, `HashExchange.java`, `BroadcastExchange.java`

```java
// 팩토리 메서드 (BlockExchange.java:62-86)
static BlockExchange getExchange(sendingMailboxes, distributionType, keys, ...) {
    SINGLETON        → SingletonExchange
    HASH_DISTRIBUTED → HashExchange
    RANDOM           → RandomExchange
    BROADCAST        → BroadcastExchange
}

// 블록 크기 제한: 4MB (소프트 리밋)
// 초과 시 블록 분할하여 전송
```

### 4.3 HashExchange 동작

```
입력 블록 (N행):
  row0: {city="Seoul", amount=100}
  row1: {city="Busan", amount=200}
  row2: {city="Seoul", amount=300}

hash(city) % numMailboxes:
  Seoul → mailbox 0
  Busan → mailbox 1
  Seoul → mailbox 0

분배 결과:
  mailbox 0: [row0, row2]   (Seoul)
  mailbox 1: [row1]         (Busan)
```

---

## 6. 분산 JOIN

### 5.1 Hash Join

소스: `HashJoinOperator.java`

```
Build Phase (오른쪽 테이블):
  수신된 블록 → 해시 테이블에 적재
  키: JOIN 컬럼 값
  값: 행 데이터 리스트

Probe Phase (왼쪽 테이블):
  각 행의 JOIN 키 → 해시 테이블 조회
  매칭 행 → 결합하여 출력

NULL 키 처리:
  equi-join에서 NULL 키는 제외 (SQL 표준)
  RIGHT/FULL JOIN용으로 별도 저장
```

### 5.2 JOIN 전략 선택

```
작은 테이블 + 큰 테이블:
  → 작은 테이블 BROADCAST + 큰 테이블은 그대로
  → 각 워커가 전체 작은 테이블 보유

비슷한 크기:
  → 양쪽 모두 HASH_DISTRIBUTED (JOIN 키 기준)
  → 같은 키의 행이 같은 워커로 모임
```

### 5.3 Lookup 테이블 구현

```java
// 타입별 최적화된 Lookup 테이블
IntLookupTable     // INT JOIN 키
LongLookupTable    // LONG JOIN 키
FloatLookupTable   // FLOAT JOIN 키
DoubleLookupTable  // DOUBLE JOIN 키
ObjectLookupTable  // 복합 키, STRING 등
```

---

## 7. 분산 집계 (Aggregation)

소스: `AggregateOperator.java`, `MultistageGroupByExecutor.java`, `MultistageAggregationExecutor.java`

### 6.1 집계 스테이징

```
LEAF 스테이지:        각 서버에서 부분 집계
INTERMEDIATE 스테이지: 부분 결과 병합
FINAL 스테이지:       최종 결과 추출

예: SELECT city, SUM(amount) FROM orders GROUP BY city

Stage 2 (Leaf, Server A):    Stage 2 (Leaf, Server B):
  Seoul: SUM=3500              Seoul: SUM=1200
  Busan: SUM=2000              Tokyo: SUM=4500
         ↓ HashExchange(city)          ↓ HashExchange(city)

Stage 1 (Intermediate):
  Worker 0 (Seoul, Busan):     Worker 1 (Tokyo):
    Seoul: 3500+1200=4700        Tokyo: 4500
    Busan: 2000
         ↓ SingletonExchange

Stage 0 (Root, Broker):
  Seoul=4700, Busan=2000, Tokyo=4500
```

### 6.2 Group 제한

```java
// 메모리 폭증 방지
_numGroupsLimit       // 최대 그룹 수 (넘으면 에러 또는 경고)
_groupTrimSize        // 트리밍 시 유지할 그룹 수
_errorOnNumGroupsLimit // true면 에러, false면 경고

// ORDER BY + LIMIT 시 정렬 기반 트리밍
// 없으면 랜덤 트리밍
```

---

## 8. 블록 기반 실행 모델

소스: `MultiStageOperator.java`

```java
// 모든 오퍼레이터의 기본 실행 루프
public abstract MseBlock getNextBlock() throws Exception;

// 반환 타입:
//   MseBlock.Data  → 실제 데이터 행
//   MseBlock.Eos   → 스트림 종료 (더 이상 데이터 없음)
//   MseBlock.Error → 에러 발생
```

### 7.1 파이프라인 실행

```
전체 결과를 버퍼링하지 않고 블록 단위로 스트리밍:

LeafOp.getNextBlock()
  → [Block 1: 1000행] → AggOp → MailboxSendOp → gRPC 전송
  → [Block 2: 1000행] → AggOp → MailboxSendOp → gRPC 전송
  → [EOS]
```

### 7.2 Early Termination

```
LIMIT 10 쿼리 시:
  10행 수집 완료 → _isEarlyTerminated = true
  → 하위 오퍼레이터에 종료 신호
  → 불필요한 스캔/전송 중단
```

### 7.3 타임아웃 관리

```java
// 두 종류의 데드라인
Active Deadline:  데이터 처리 중 타임아웃
Passive Deadline: 메일박스 대기 중 타임아웃 (더 길게 설정)
```

---

## 9. 워커 할당

소스: `WorkerManager.java`, `LeafStageWorkerAssignmentRule.java`

```
Leaf 스테이지:
  → 테이블 파티셔닝 기반으로 서버 할당
  → 세그먼트가 있는 서버가 워커가 됨
  → RoutingManager가 세그먼트-서버 매핑 제공

Intermediate 스테이지:
  → 교환 전략에 따라 워커 할당
  → 해시 파티셔닝 시 충분한 워커 수 보장

해시 함수:
  테이블 파티셔닝: Murmur (기본)
  셔플 파티셔닝:  AbsHashCodeSum (기본, v2)
```

---

## 10. 쿼리 예시: 분산 JOIN

```sql
SELECT o.city, COUNT(*), SUM(o.amount)
FROM orders o
JOIN cities c ON o.city_id = c.id
WHERE c.country = 'KR'
GROUP BY o.city
```

```
Stage 3 (Leaf): cities 테이블 스캔
  → WHERE c.country = 'KR' 필터
  → [Block: id=1,name=Seoul | id=2,name=Busan]
  → BroadcastExchange (작은 테이블)

Stage 2 (Leaf): orders 테이블 스캔
  → [Block: city_id=1,amount=100 | city_id=2,amount=200 | ...]
  → HashExchange(city_id)

Stage 1 (Intermediate): Hash Join + Group By
  Worker 0:
    Build: cities 해시 테이블 {1→Seoul, 2→Busan}
    Probe: orders에서 city_id로 조회
    Group By: city별 COUNT, SUM
  → SingletonExchange

Stage 0 (Root): 최종 결과 수집
  → 클라이언트 반환
```

---

## 11. 주요 소스 파일 맵

```
pinot-broker/.../requesthandler/
  └── MultiStageBrokerRequestHandler.java    진입점 (SQL 수신 → 결과 반환)

pinot-query-planner/.../planner/
  ├── logical/
  │   └── PinotLogicalQueryPlanner.java      논리 → 물리 계획 변환
  ├── physical/v2/
  │   ├── PRelNode.java                      물리 관계 노드 인터페이스
  │   ├── ExchangeStrategy.java              데이터 분배 전략 (8가지)
  │   ├── PlanFragmentAndMailboxAssignment.java  프래그먼트 생성 + Mailbox 할당
  │   └── opt/rules/
  │       └── LeafStageWorkerAssignmentRule.java  리프 워커 할당
  ├── plannode/
  │   ├── MailboxSendNode.java               송신 노드
  │   └── MailboxReceiveNode.java            수신 노드
  ├── PlanFragment.java                      실행 단위
  └── physical/
      └── DispatchablePlanFragment.java      디스패치 가능 프래그먼트

pinot-query-runtime/.../runtime/operator/
  ├── MultiStageOperator.java                오퍼레이터 기반 클래스
  ├── MailboxSendOperator.java               블록 송신
  ├── MailboxReceiveOperator.java            블록 수신
  ├── AggregateOperator.java                 집계 컨트롤러
  ├── MultistageGroupByExecutor.java         GROUP BY 실행
  ├── MultistageAggregationExecutor.java     비키 집계 실행
  ├── HashJoinOperator.java                  해시 조인
  └── exchange/
      ├── BlockExchange.java                 교환 팩토리
      ├── HashExchange.java                  해시 기반 분배
      ├── BroadcastExchange.java             브로드캐스트
      ├── SingletonExchange.java             단일 수신자
      └── RandomExchange.java               랜덤 분배

pinot-query-runtime/.../service/dispatch/
  └── QueryDispatcher.java                   gRPC 디스패치

pinot-query-planner/.../routing/
  └── WorkerManager.java                     워커 할당 관리
```

---

## 12. 참조 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| **Apache Calcite** | SQL 파싱, 논리 계획, 비용 기반 최적화 |
| **gRPC** | 스테이지 간 Mailbox 통신 |
| **Protocol Buffers** | 블록 직렬화 |
| **Helix** | 서버 디스커버리, 세그먼트 라우팅 |

---

## 13. 핵심 설계 패턴

| 패턴 | 설명 |
|------|------|
| **Block 파이프라인** | 전체 버퍼링 없이 블록 단위 스트리밍 |
| **Stage 분리** | 각 스테이지가 독립 실행, Mailbox로 연결 |
| **Exchange 추상화** | 플러그 가능한 데이터 분배 전략 |
| **집계 스테이징** | Leaf(부분) → Intermediate(병합) → Final(추출) |
| **Early Termination** | LIMIT 쿼리 시 불필요한 처리 즉시 중단 |
