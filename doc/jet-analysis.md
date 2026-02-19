# Hazelcast Jet 스트림 처리 엔진 분석

> 유사 스트림 처리 시스템을 구축하기 위한 참고 문서

---

## 1. 아키텍처 개요

Jet는 2계층 프로그래밍 모델을 제공한다.

```
[사용자 API]  Pipeline  ──(컴파일)──>  DAG  ──(실행)──>  결과
               │                        │
          고수준 DSL              저수준 실행 그래프
       (선언적, 체이닝)        (Vertex + Edge + Processor)
```

| 계층 | 핵심 개념 | 역할 |
|------|----------|------|
| **Pipeline API** | Stage, WindowDefinition, AggregateOperation | 사용자가 "무엇을 할지" 선언 |
| **Core DAG API** | DAG, Vertex, Edge, Processor | 엔진이 "어떻게 실행할지" 결정 |

---

## 2. Pipeline API

### 2.1 기본 구조

```java
Pipeline p = Pipeline.create();

// 소스 → 변환 → 싱크
p.readFrom(Sources.map("orders"))       // BatchStage<Entry<K,V>>
 .filter(e -> e.getValue() > 100)       // BatchStage<Entry<K,V>>
 .map(Entry::getKey)                    // BatchStage<K>
 .writeTo(Sinks.logger());              // SinkStage (터미널)
```

`Pipeline.create()`는 내부적으로 `PipelineImpl`을 생성하며, 핵심 자료구조는 **인접 리스트(adjacencyMap)**이다.

```java
// PipelineImpl.java
private final Map<Transform, List<Transform>> adjacencyMap = new LinkedHashMap<>();
```

각 파이프라인 연산(`map`, `filter` 등)은:
1. **Transform 객체**를 생성하고
2. **adjacencyMap에 간선**을 추가하고
3. **새 Stage**를 래핑하여 반환한다

### 2.2 사용 가능한 연산

#### 상태 없는 변환
```java
stage.map(fn)           // T → R (null 반환 시 필터링)
stage.filter(pred)      // 조건에 맞는 아이템만 통과
stage.flatMap(fn)       // T → Traverser<R> (1:N 변환)
stage.merge(other)      // 두 스트림 합치기 (union)
```

#### 상태 있는 변환
```java
stage.mapStateful(createFn, mapFn)       // 프로세서별 상태 유지
stage.filterStateful(createFn, filterFn)
stage.flatMapStateful(createFn, flatMapFn)
stage.rollingAggregate(aggrOp)            // 키 없이 연속 집계
```

#### 그룹화 및 집계
```java
// 배치
stage.groupingKey(keyFn).aggregate(aggrOp)

// 스트리밍 (윈도우 필수)
stage.groupingKey(keyFn)
     .window(WindowDefinition.tumbling(60_000))
     .aggregate(aggrOp)
```

#### 조인
```java
// Hash Join: 왼쪽(스트림) + 오른쪽(배치 lookup)
stage.hashJoin(lookupStage, joinClause, mapFn)
```

#### 외부 서비스 연동
```java
stage.mapUsingService(serviceFactory, (svc, item) -> svc.call(item))
stage.mapUsingServiceAsync(factory, maxConcurrency, preserveOrder, asyncFn)
stage.mapUsingServiceAsyncBatched(factory, maxBatch, batchAsyncFn)
```

#### 데이터 분배
```java
stage.rebalance()              // 라운드 로빈 재분배
stage.rebalance(keyFn)         // 키 기반 해시 라우팅
```

#### 정렬 (배치 전용)
```java
batchStage.sort()
batchStage.sort(comparator)
```

### 2.3 내부 동작: Stage → Transform 매핑

```java
// ComputeStageImplBase.java
<RET> RET attachMap(FunctionEx<T, R> mapFn) {
    return attach(new MapTransform("map", this.transform, adaptedFn), fnAdapter);
}

<RET> RET attach(AbstractTransform transform, FunctionAdapter fnAdapter) {
    pipelineImpl.connect(this, transform);           // adjacencyMap 업데이트
    return newStage(transform, fnAdapter);            // 새 Stage 반환
}
```

---

## 3. Pipeline → DAG 변환

### 3.1 변환 과정 (Planner.createDag)

```java
// Planner.java
DAG createDag(Context context) {
    pipeline.makeNamesUnique();              // 1) 이름 중복 제거
    validateNoLeakage(adjacencyMap);         // 2) 싱크 없는 브랜치 검증
    checkTopologicalSort(adjacencyMap);      // 3) 순환 참조 검출

    // 4) Map/FlatMap 퓨전 최적화
    for (Transform t : transforms) {
        List<Transform> chain = findFusibleChain(t, adjacencyMap);
        if (chain != null) fuseFlatMapTransforms(chain);
    }

    // 5) 각 Transform이 자신을 DAG에 추가
    for (Transform t : transforms) {
        t.addToDag(this, context);
    }
    return dag;
}
```

### 3.2 퓨전 최적화

연속된 `map`/`flatMap` 연산을 하나의 Vertex로 합친다.

```
[최적화 전]  source → filter → map → groupBy → sink   (5 Vertex)
[최적화 후]  source → fused(filter,map) → groupBy → sink   (4 Vertex)
```

**퓨전 조건:**
- 연속된 MapTransform 또는 FlatMapTransform
- 각 단계의 하류가 정확히 1개
- 동일한 localParallelism
- 사이에 리밸런싱 없음

```java
// 함수 합성: fn1.andThen(fn2).andThen(fn3)
private static Transform fuseFlatMapTransforms(List<Transform> chain) { ... }
```

### 3.3 Transform별 DAG 생성 패턴

각 Transform은 `addToDag(Planner, Context)`를 구현하여 자신을 DAG에 추가한다.

#### 소스
```
BatchSourceTransform → 1개 Vertex (readMapP 등)
StreamSourceTransform → 1~2개 Vertex (워터마크 필요 시 insertWatermarksP 추가)
```

#### Map/Filter/FlatMap
```
MapTransform → 1개 Vertex (mapP)
             → 상류에서 unicast Edge 연결 (preserveOrder면 isolated)
```

#### 그룹 집계 (GroupTransform) - 2단계
```
                 local, partitioned(key)          distributed, partitioned(key)
상류 Vertex  ──────────────────────>  accumulateByKeyP  ────────────────────>  combineByKeyP
                (로컬 부분 집계)                             (분산 최종 결합)
```

네트워크 전송 전에 로컬에서 먼저 집계하여 셔플 데이터량을 줄인다.

#### 글로벌 집계 (AggregateTransform) - 2단계
```
상류 Vertex  ──(local)──>  accumulateP  ──(distributed, allToOne)──>  combineP [LP=1]
```

#### Hash Join
```
primary ──(local unicast, ord=0)──────────────────────────────>  joiner
                                                                   ↑
lookup-1 ──(distributed broadcast)──> collector-1 [LP=1]          │
                               └──(local broadcast, priority=-1)──┘
```
priority=-1: lookup 데이터가 먼저 로드된다.

#### 싱크
```
SinkTransform → 1개 Vertex
             → Edge 설정: partitioned / distributed / allToOne (타입에 따라)
```

### 3.4 DAG 핵심 구조

```java
// DAG.java
public class DAG {
    private final Set<Edge> edges;
    private final Map<String, Vertex> nameToVertex;

    public Vertex newVertex(String name, ProcessorMetaSupplier supplier);
    public DAG edge(Edge edge);
    public Iterator<Vertex> iterator();  // 위상 정렬된 순서
}
```

```java
// Vertex.java - DAG의 노드
public class Vertex {
    private String name;
    private ProcessorMetaSupplier metaSupplier;  // Processor 생성 팩토리
    private int localParallelism;                 // 멤버당 프로세서 수
}
```

```java
// Edge.java - DAG의 간선
public class Edge {
    private Vertex source;      private int sourceOrdinal;
    private Vertex destination; private int destOrdinal;
    private RoutingPolicy routingPolicy;
    private boolean distributed;
    private Partitioner<?> partitioner;
    private int priority;
}
```

### 3.5 Edge 라우팅 정책

| 정책 | 설명 | 사용 예 |
|------|------|---------|
| `UNICAST` | 라운드 로빈으로 하나의 프로세서에 전달 | 기본값 |
| `ISOLATED` | 1:1 매핑, 순서 보장 | preserveOrder=true |
| `PARTITIONED` | 같은 키 → 같은 프로세서 | groupBy |
| `BROADCAST` | 모든 프로세서에 복사 | hash join lookup |
| `FANOUT` | 멤버당 하나씩 | 분산 브로드캐스트 |

`distributed` 플래그가 true이면 네트워크를 통해 다른 멤버로도 전송된다.

---

## 4. 실행 엔진

### 4.1 협력적 멀티스레딩 (Cooperative Multithreading)

Jet의 핵심 설계 원칙이다. N개의 Processor가 K개의 스레드(기본값 = CPU 코어 수)를 **시분할**한다.

```
CooperativeWorker 스레드 (CPU 코어당 1개)
┌─────────────────────────────────────────────────┐
│  while (!shutdown) {                            │
│      for (tasklet : myTasklets) {               │
│          progress |= tasklet.call();  // 짧게!  │
│      }                                          │
│      if (!progress) idle();  // 지수 백오프      │
│  }                                              │
└─────────────────────────────────────────────────┘
```

각 프로세서의 `call()`은 **수 마이크로초** 이내에 반환해야 한다. 블로킹 금지.

```
TaskletExecutionService
├── cooperativeWorkers[K]     // K = CPU 코어 수, 고정 스레드 풀
│   └── 각각 CopyOnWriteArrayList<Tasklet> 관리
│       └── 라운드 로빈으로 모든 tasklet 호출
└── blockingTaskletExecutor   // 비협력적 프로세서용 무제한 스레드 풀
```

### 4.2 Processor 인터페이스

Processor는 단일 스레드에서 실행되는 계산의 최소 단위이다.

```java
public interface Processor {
    // 협력적 여부 (기본 true)
    default boolean isCooperative() { return true; }

    // 라이프사이클
    void init(Outbox outbox, Context context);
    void close();

    // 처리 루프
    boolean tryProcessWatermark(Watermark watermark);   // 워터마크 처리
    boolean tryProcess(int ordinal, Object item);       // 아이템 1개 처리
    boolean complete();                                  // 모든 입력 소진 후 호출

    // 스냅샷
    boolean saveToSnapshot();
    void restoreFromSnapshot(Object key, Object value);
    boolean snapshotCommitPrepare();
    boolean snapshotCommitFinish(boolean success);
}
```

**핵심 규약: false 반환 → 재시도**

모든 `boolean` 반환 메서드는 동일한 규약을 따른다:
- `true`: 완료됨, 다음 단계로 진행
- `false`: 미완료, 같은 인자로 다시 호출

이 규약 덕분에 콜백이나 코루틴 없이도 프로세서를 "일시정지"할 수 있다.

```java
// 예: Outbox가 가득 찼을 때
protected boolean tryProcess(int ordinal, Object item) {
    return tryEmit(transform(item));  // outbox 가득 → false 반환 → 엔진이 재시도
}
```

### 4.3 ProcessorTasklet 상태 머신

`ProcessorTasklet`은 Processor를 래핑하여 상태 머신으로 구동한다.

```
PROCESS_WATERMARKS  →  워터마크 처리
NULLARY_PROCESS     →  tryProcess() (소스용 틱)
PROCESS_INBOX       →  process(ordinal, inbox)
COMPLETE_EDGE       →  입력 간선 소진 시 completeEdge()
COMPLETE            →  모든 입력 소진 시 complete()
SAVE_SNAPSHOT       →  saveToSnapshot()
EMIT_BARRIER        →  SnapshotBarrier 하류 전송
EMIT_DONE_ITEM      →  DONE 센티널 전송
CLOSE / END         →  정리
```

`call()` 한 번 = 상태 머신 한 스텝. 미완료 시 같은 상태에서 재시도.

### 4.4 비협력적 프로세서

외부 I/O (DB, HTTP, 파일)가 필요한 프로세서는 `isCooperative() = false`를 반환한다.
이 경우 전용 Java 스레드를 할당받으며, 블로킹이 허용된다.

```java
// ServiceFactory에서 비협력 모드 지정
ServiceFactory<?, HttpClient> factory = ServiceFactories
    .nonSharedService(ctx -> HttpClient.newHttpClient())
    .toNonCooperative();  // ← 전용 스레드 할당
```

### 4.5 백프레셔

별도의 신호 프로토콜 없이 **큐 가득 참**으로 자연스럽게 구현된다.

```
Producer Processor
    │
    ▼ tryEmit(item) → outbox.offer(item)
                         │
                    ┌─────────────┐
                    │   Queue     │ ← 고정 크기 (기본 1024)
                    │ (lock-free) │
                    └─────────────┘
                         │
                    Consumer Processor

큐가 가득 참 → offer() false 반환
→ tryEmit() false 반환
→ tryProcess() false 반환
→ CooperativeWorker: "progress 없음" → idle()
→ 자연스러운 속도 조절
```

---

## 5. Processor 3단계 팩토리 체인

프로세서는 클러스터 전역에 분산 생성되어야 한다. 이를 위해 3단계 팩토리를 사용한다.

```
ProcessorMetaSupplier     (1개, 코디네이터에서 실행)
    │
    │  .get(List<Address>) → 멤버별 ProcessorSupplier 매핑
    ▼
ProcessorSupplier         (멤버당 1개, 직렬화되어 전송)
    │
    │  .get(count) → count개의 Processor 생성
    ▼
Processor                 (멤버당 N개, localParallelism)
```

| 단계 | 인스턴스 수 | 위치 | 역할 |
|------|-----------|------|------|
| MetaSupplier | 1 | 코디네이터 | 파티션 배정, 멤버별 설정 결정 |
| Supplier | 멤버 수 | 각 멤버 | 파일/커넥션 초기화, Processor 생성 |
| Processor | 멤버 × LP | 각 멤버 | 실제 데이터 처리 |

```java
// 예: 파티션 인식 소스
ProcessorMetaSupplier.of((addresses) -> {
    Map<Address, int[]> partitions = context.partitionAssignment();
    return addr -> new MySourceSupplier(partitions.get(addr));
});
```

---

## 6. 장애 내성 (Fault Tolerance)

### 6.1 처리 보장 수준

```java
public enum ProcessingGuarantee {
    NONE,           // 스냅샷 없음, 장애 시 처음부터 재시작
    AT_LEAST_ONCE,  // 스냅샷 + 비정렬 배리어 (낮은 지연, 중복 가능)
    EXACTLY_ONCE    // 스냅샷 + 정렬 배리어 (높은 지연, 중복 없음)
}
```

### 6.2 Chandy-Lamport 분산 스냅샷

Jet는 **Chandy-Lamport 알고리즘**의 변형을 사용한다.

```
1. 코디네이터가 소스 프로세서에 SnapshotBarrier 주입
2. 배리어가 데이터 스트림과 함께 하류로 전파
3. 프로세서가 모든 입력에서 배리어를 수신하면:
   a) saveToSnapshot() → 상태를 IMap에 저장
   b) snapshotCommitPrepare() → 외부 트랜잭션 prepare (2PC phase 1)
   c) 배리어를 하류로 전달
4. 모든 프로세서 완료 → snapshotCommitFinish(true) (2PC phase 2)
```

#### Exactly-Once vs At-Least-Once의 차이

```
       Edge 0      Edge 1
         │           │
         ▼           ▼
    ┌──────────────────────┐
    │     Processor        │
    │                      │
    │  Exactly-Once:       │
    │  Edge 0에서 barrier   │
    │  수신 → Edge 0 블록,  │◄── 배리어 정렬 (aligned barriers)
    │  Edge 1 barrier 대기  │
    │                      │
    │  At-Least-Once:      │
    │  Edge 0에서 barrier   │
    │  수신해도 Edge 1 계속  │◄── 비정렬 (재시작 시 중복 처리 가능)
    │  처리                 │
    └──────────────────────┘
```

### 6.3 외부 시스템과 2PC

트랜잭셔널 싱크(Kafka, JDBC)를 위한 2Phase Commit:

```
saveToSnapshot()         → 상태 + 트랜잭션 ID 저장
snapshotCommitPrepare()  → 현재 트랜잭션 prepare, 새 트랜잭션 시작
snapshotCommitFinish(✓)  → prepared 트랜잭션 commit
snapshotCommitFinish(✗)  → 아무 것도 안 함 (다음 시도에서 재시도)
재시작 시: restoreFromSnapshot()에서 prepared 트랜잭션 ID 복원 → commit
```

---

## 7. 이벤트 시간과 워터마크

### 7.1 워터마크 개념

```java
public final class Watermark {
    private final long timestamp;  // "이후로 이 timestamp 미만의 이벤트는 없다"
    private final byte key;         // 다중 시간 도메인 구분
}
```

워터마크는 데이터 스트림 사이에 끼워 넣는 **특수 아이템**이다.
이벤트 시간 기반 윈도우 처리에 필수적이다.

### 7.2 워터마크 병합 (Coalescing)

프로세서에 여러 입력이 있을 때, **모든 입력의 최솟값**을 취한다.

```
Input 0: wm=100  ─┐
                   ├─→ coalesced wm = min(100, 80) = 80
Input 1: wm=80   ─┘
```

한 입력이 데이터를 보내지 않으면 `IDLE_MESSAGE`(MAX_VALUE)를 보내 최솟값 계산에서 제외시킨다.

### 7.3 윈도우 정의

```java
// 텀블링 윈도우: 고정 크기, 겹침 없음
WindowDefinition.tumbling(60_000)      // 1분 윈도우
// 내부적으로 sliding(60_000, 60_000)과 동일

// 슬라이딩 윈도우: 고정 크기, 겹침 있음
WindowDefinition.sliding(60_000, 10_000)  // 1분 윈도우, 10초 슬라이드
// windowSize는 slideBy의 배수여야 함

// 세션 윈도우: 가변 크기, 비활동 시간 기준
WindowDefinition.session(30_000)       // 30초 비활동 시 세션 종료
// 키별로 독립적인 세션 추적
```

#### 조기 결과 (Early Results)
```java
WindowDefinition.tumbling(60_000)
    .setEarlyResultsPeriod(1_000);  // 1초마다 추측 결과 발행
// exportFn (accumulator 유지) 사용, finishFn (accumulator 소비)이 아님
```

#### 윈도우 사용 예
```java
pipeline.readFrom(tradeSource)
    .withTimestamps(Trade::getTime, 5_000)   // 5초 허용 지연
    .groupingKey(Trade::getTicker)
    .window(WindowDefinition.sliding(60_000, 1_000))
    .aggregate(AggregateOperations.summingLong(Trade::getAmount))
    // → StreamStage<KeyedWindowResult<String, Long>>
    .writeTo(Sinks.logger());
```

---

## 8. AggregateOperation: 6개의 원시 연산

집계는 6개의 함수 조합으로 정의된다.

```
┌─────────────┐
│  createFn   │ → 빈 누산기(accumulator) 생성
└──────┬──────┘
       ▼
┌─────────────┐
│ accumulateFn│ → 아이템 하나를 누산기에 추가
└──────┬──────┘
       ▼ (병렬 처리 시)
┌─────────────┐
│  combineFn  │ → 두 누산기 병합 (분산 집계에 필수)
└──────┬──────┘
       ▼ (슬라이딩 윈도우 시)
┌─────────────┐
│  deductFn   │ → 병합 취소 (선택, 슬라이딩 윈도우 효율화)
└──────┬──────┘
       ▼
┌─────────────┐
│  exportFn   │ → 누산기 유지하면서 결과 추출 (조기 결과용)
├─────────────┤
│  finishFn   │ → 최종 결과 추출 (누산기 소비 가능)
└─────────────┘
```

### deductFn의 중요성

슬라이딩 윈도우에서 `deductFn` 없이는 매 슬라이드마다 전체 재계산이 필요하다.
`deductFn`이 있으면 **1회 추가 + 1회 제거**로 처리 가능하다.

```
윈도우 크기=10, 슬라이드=1

deductFn 없음: 매 슬라이드마다 10개 아이템 재집계  → O(windowSize)
deductFn 있음: combine(새 프레임) + deduct(나간 프레임) → O(1)
```

### 사용 예

```java
// 직접 정의
AggregateOperation1<Trade, long[], Long> sumAmount =
    AggregateOperation.withCreate(() -> new long[1])
        .andAccumulate((acc, trade) -> acc[0] += trade.getAmount())
        .andCombine((left, right) -> left[0] += right[0])
        .andDeduct((left, right) -> left[0] -= right[0])
        .andExportFinish(acc -> acc[0]);

// 내장 팩토리 사용
AggregateOperations.counting()
AggregateOperations.summingLong(Trade::getAmount)
AggregateOperations.averagingLong(Trade::getAmount)
AggregateOperations.toList()
AggregateOperations.toSet()
AggregateOperations.toMap(keyFn, valueFn)
AggregateOperations.allOf(op1, op2)  // 복합 집계
```

---

## 9. Job 생명주기

### 9.1 상태 전이

```
             submit
STARTING ──────────> RUNNING ──────────> COMPLETED
                       │  ↑
              suspend  │  │ resume
                       ▼  │
                    SUSPENDED
                       │
                       ▼
                     FAILED

Light Job: RUNNING → COMPLETED | FAILED (스냅샷/일시중지 불가)
```

### 9.2 Job 제출과 관리

```java
// 제출
JetService jet = hazelcastInstance.getJet();
Job job = jet.newJob(pipeline, new JobConfig()
    .setName("order-aggregation")
    .setProcessingGuarantee(ProcessingGuarantee.EXACTLY_ONCE)
    .setSnapshotIntervalMillis(10_000));

// 대기
job.join();                         // 블로킹 (스트리밍은 영원히)

// 비동기
job.getFuture().whenComplete((v, err) -> ...);

// 제어
job.cancel();                       // 즉시 중단
job.suspend();                      // 터미널 스냅샷 후 일시중지
job.resume();                       // 마지막 스냅샷에서 재개
job.restart();                      // suspend + resume

// 스냅샷 내보내기
job.exportSnapshot("v1");           // 명시적 스냅샷 생성
job.cancelAndExportSnapshot("v1");  // 스냅샷 + 중단
```

### 9.3 JobConfig 주요 설정

```java
JobConfig config = new JobConfig()
    // 장애 내성
    .setProcessingGuarantee(ProcessingGuarantee.EXACTLY_ONCE)
    .setSnapshotIntervalMillis(10_000)
    .setAutoScaling(true)              // 멤버 추가/제거 시 자동 재시작
    .setSuspendOnFailure(true)         // 에러 시 FAILED 대신 SUSPENDED
    .setSplitBrainProtection(true)     // 쿼럼 보장

    // 리소스
    .addClass(MyProcessor.class)
    .addJar(new File("lib.jar"))
    .attachFile(new File("model.dat"), "model")

    // 상태 복원
    .setInitialSnapshotName("v1")      // 내보낸 스냅샷에서 시작

    // 제한
    .setMaxProcessorAccumulatedRecords(1_000_000)
    .setTimeoutMillis(60_000)

    // 인자 전달
    .setArgument("threshold", 100);    // Processor.Context에서 접근 가능
```

---

## 10. 소스와 싱크

### 10.1 내장 소스

| 카테고리 | 소스 | 배치/스트림 |
|---------|------|-----------|
| **Hazelcast IMap** | `Sources.map(name)` | 배치 (전체 스캔) |
| | `Sources.map(name, predicate, projection)` | 배치 (푸시다운) |
| | `Sources.mapJournal(name, initialPos)` | 스트림 (이벤트 저널) |
| **Hazelcast ICache** | `Sources.cache(name)` | 배치 |
| | `Sources.cacheJournal(name, initialPos)` | 스트림 |
| **Hazelcast IList** | `Sources.list(name)` | 배치 |
| **파일** | `Sources.files(dir)` | 배치 (텍스트 라인) |
| | `Sources.json(dir, type)` | 배치 (JSON → POJO) |
| | `Sources.fileWatcher(dir)` | 스트림 (파일 감시) |
| **JDBC** | `Sources.jdbc(connFn, rsFn, mapFn)` | 배치 |
| **JMS** | `Sources.jmsQueue(name, factoryFn)` | 스트림 |
| | `Sources.jmsTopic(name, factoryFn)` | 스트림 |
| **TCP 소켓** | `Sources.socket(host, port)` | 스트림 |
| **Remote (원격 클러스터)** | `Sources.remoteMap(name, clientConfig)` | 배치/스트림 |
| **테스트** | `TestSources.items(1, 2, 3)` | 배치 |
| | `TestSources.itemStream(rate)` | 스트림 |

### 10.2 내장 싱크

| 카테고리 | 싱크 | 특징 |
|---------|------|------|
| **IMap** | `Sinks.map(name)` | put (idempotent) |
| | `Sinks.mapWithMerging(name, mergeFn)` | merge 함수 적용 |
| | `Sinks.mapWithUpdating(name, updateFn)` | 기존 값 업데이트 |
| | `Sinks.mapWithEntryProcessor(name, epFn)` | EntryProcessor 제출 |
| **IList** | `Sinks.list(name)` | append |
| **ITopic** | `Sinks.reliableTopic(name)` | pub/sub |
| **파일** | `Sinks.files(dir)` | 텍스트 파일 |
| | `Sinks.json(dir)` | JSON 파일 |
| **JDBC** | `Sinks.jdbc(query, dsFn, bindFn)` | SQL UPDATE/INSERT |
| **JMS** | `Sinks.jmsQueue(name, factoryFn)` | JMS 큐 |
| **TCP 소켓** | `Sinks.socket(host, port)` | TCP 전송 |
| **Observable** | `Sinks.observable(name)` | 클라이언트 관찰 가능 |
| **로그** | `Sinks.logger()` | ILogger 출력 |
| **Noop** | `Sinks.noop()` | 폐기 |

### 10.3 커스텀 소스 만들기 (SourceBuilder)

```java
// 배치 소스: 파일 한 줄씩 읽기
BatchSource<String> fileSource = SourceBuilder
    .batch("file-reader", ctx -> new BufferedReader(new FileReader("input.txt")))
    .<String>fillBufferFn((reader, buffer) -> {
        String line = reader.readLine();
        if (line != null) buffer.add(line);
        else buffer.close();   // 종료 신호
    })
    .destroyFn(BufferedReader::close)
    .build();

// 스트리밍 소스: 외부 API 폴링 (스냅샷 지원)
StreamSource<Event> apiSource = SourceBuilder
    .timestampedStream("api-poller", ctx -> new ApiClient())
    .<Event>fillBufferFn((client, buffer) -> {
        for (Event e : client.poll()) {
            buffer.add(e, e.getTimestamp());
        }
    })
    .createSnapshotFn(client -> client.getOffset())
    .restoreSnapshotFn((client, offsets) -> client.seek(offsets.get(0)))
    .destroyFn(ApiClient::close)
    .build();
```

### 10.4 커스텀 싱크 만들기 (SinkBuilder)

```java
Sink<String> httpSink = SinkBuilder
    .sinkBuilder("http-sink", ctx -> HttpClient.newHttpClient())
    .<String>receiveFn((client, item) -> {
        client.send(
            HttpRequest.newBuilder(URI.create("http://api.example.com/data"))
                .POST(BodyPublishers.ofString(item))
                .build(),
            BodyHandlers.discarding()
        );
    })
    .flushFn(client -> { /* flush if needed */ })
    .destroyFn(client -> { /* cleanup */ })
    .preferredLocalParallelism(2)
    .build();
```

---

## 11. 외부 서비스 연동 (ServiceFactory)

외부 서비스(DB, HTTP, ML 모델)를 파이프라인에 통합한다.

### 11.1 생명주기

```
Job 제출 → 직렬화 & 전송
    ↓
멤버별: createContextFn(ctx) → C (공유 컨텍스트, e.g. 커넥션 풀)
    ↓
프로세서별: createServiceFn(procCtx, C) → S (서비스 인스턴스)
    ↓
처리: mapFn(S, item) → result
    ↓
정리: destroyServiceFn(S), destroyContextFn(C)
```

### 11.2 사용 패턴

```java
// 동기 호출
ServiceFactory<?, HttpClient> httpFactory = ServiceFactories
    .nonSharedService(ctx -> HttpClient.newHttpClient())
    .toNonCooperative();

stage.mapUsingService(httpFactory, (client, item) -> {
    return client.send(buildRequest(item), BodyHandlers.ofString()).body();
});

// 비동기 호출 (처리량 극대화)
stage.mapUsingServiceAsync(httpFactory, 16, true,
    (client, item) -> client.sendAsync(buildRequest(item), BodyHandlers.ofString())
                            .thenApply(HttpResponse::body));
// maxConcurrentOps=16: 프로세서당 최대 16개 동시 요청
// preserveOrder=true: 입력 순서 유지

// 배치 비동기 호출 (네트워크 효율화)
stage.mapUsingServiceAsyncBatched(httpFactory, 100,
    (client, items) -> client.sendBatch(items));
// maxBatchSize=100: 최대 100개 아이템을 모아서 한 번에 호출
```

---

## 12. Traverser: 협력적 반복 추상화

Processor가 여러 `call()` 호출에 걸쳐 출력을 생산할 수 있게 해주는 핵심 추상화이다.

```java
@FunctionalInterface
public interface Traverser<T> {
    T next();  // null = 현재 소진됨 (영구적이 아닐 수 있음)
}
```

### 왜 Iterator가 아닌 Traverser인가?

Iterator는 `hasNext()` + `next()` 2단계이지만, 협력적 프로세서에서는:
1. 출력 하나를 생성
2. Outbox에 넣기 시도
3. **Outbox가 가득 차면 중단, 다음 call()에서 같은 위치부터 재개**

Traverser는 `next()`만으로 이 패턴을 자연스럽게 표현한다.

```java
// AbstractProcessor 내부
protected boolean emitFromTraverser(Traverser<T> traverser) {
    T item;
    if (pendingItem != null) {
        item = pendingItem;       // 이전에 실패한 아이템 재시도
        pendingItem = null;
    } else {
        item = traverser.next();
    }
    for (; item != null; item = traverser.next()) {
        if (!tryEmit(item)) {
            pendingItem = item;   // 실패 → 저장, 다음 call()에서 재시도
            return false;
        }
    }
    return true;
}
```

### 팩토리 메서드

```java
Traversers.empty()                       // 항상 null
Traversers.singleton(item)               // 1개 후 null
Traversers.traverseArray(array)          // 배열 순회
Traversers.traverseIterable(iterable)    // Iterable 순회
Traversers.traverseStream(stream)        // Stream 순회 (소진 시 close)
Traversers.lazy(() -> createTraverser()) // 지연 생성
```

---

## 13. Observable: Job 결과 관찰

```java
// 결과 수신 준비
Observable<Long> observable = jet.newObservable();
CompletableFuture<List<Long>> result =
    observable.toFuture(stream -> stream.collect(Collectors.toList()));

// 파이프라인에서 Observable로 출력
Pipeline p = Pipeline.create();
p.readFrom(TestSources.items(1L, 2L, 3L))
 .writeTo(Sinks.observable(observable));

jet.newJob(p).join();
System.out.println(result.get());  // [1, 2, 3]
observable.destroy();              // Ringbuffer 해제 (필수!)
```

내부적으로 Hazelcast `Ringbuffer`(기본 용량 10,000)를 사용한다.

---

## 14. 테스트 지원

### 14.1 Pipeline 레벨 테스트

```java
// 테스트용 소스
BatchSource<Integer> src = TestSources.items(1, 2, 3, 4, 5);
StreamSource<SimpleEvent> stream = TestSources.itemStream(100); // 100 items/sec

// 파이프라인 어설션
Pipeline p = Pipeline.create();
p.readFrom(TestSources.items(1, 2, 3))
 .map(i -> i * 2)
 .apply(Assertions.assertAnyOrder(List.of(2, 4, 6)))  // ← 검증
 .writeTo(Sinks.noop());

jet.newJob(p).join();
```

### 14.2 Processor 단위 테스트 (TestSupport)

```java
TestSupport
    .verifyProcessor(Processors.mapP(i -> ((int) i) * 2))
    .input(List.of(1, 2, 3))
    .expectOutput(List.of(2, 4, 6));

// 스냅샷 복원까지 검증
TestSupport
    .verifyProcessor(myStatefulProcessorSupplier)
    .input(List.of("a", "b", "c"))
    .expectOutput(List.of("A", "AB", "ABC"))
    // .disableSnapshots()  // 스냅샷 검증 건너뛰기
    ;
```

---

## 15. 재시도 전략

커넥터(JMS, JDBC, Kafka 등)에서 사용하는 재시도 메커니즘.

```java
// 무한 재시도, 5초 간격
RetryStrategies.indefinitely(5_000)

// 커스텀
RetryStrategies.custom()
    .maxAttempts(10)
    .intervalFunction(IntervalFunction.exponentialBackoffWithCap(
        1_000,    // 초기 1초
        2.0,      // 배수
        60_000    // 최대 60초
    ))
    .build();

// 재시도 안 함
RetryStrategies.never()
```

---

## 16. 종합 예제: 실시간 거래 집계 시스템

```java
// 1. 파이프라인 정의
Pipeline p = Pipeline.create();

// 소스: Kafka에서 거래 이벤트 수신 (extensions/kafka 필요)
StreamSource<Trade> tradeSource = KafkaSources.<String, Trade>kafka(
    kafkaProps, "trades-topic");

// 처리
p.readFrom(tradeSource)
 .withoutTimestamps()
 .withTimestamps(Trade::getTimestamp, 5_000)  // 5초 허용 지연

 // 비정상 거래 필터링
 .filter(trade -> trade.getAmount() > 0)

 // 종목별 그룹화 + 1분 텀블링 윈도우
 .groupingKey(Trade::getTicker)
 .window(WindowDefinition.tumbling(60_000)
     .setEarlyResultsPeriod(5_000))           // 5초마다 중간 결과

 // 복합 집계: 거래 수 + 총액
 .aggregate(AggregateOperations.allOf(
     AggregateOperations.counting(),
     AggregateOperations.summingLong(Trade::getAmount),
     (count, sum) -> new TradeSummary(count, sum)))

 // 결과를 IMap에 저장
 .writeTo(Sinks.map("trade-summaries",
     result -> result.getKey() + "@" + result.start(),
     KeyedWindowResult::getValue));

// 2. Job 설정 및 제출
JobConfig config = new JobConfig()
    .setName("trade-aggregation")
    .setProcessingGuarantee(ProcessingGuarantee.EXACTLY_ONCE)
    .setSnapshotIntervalMillis(10_000)
    .setAutoScaling(true);

HazelcastInstance hz = Hazelcast.newHazelcastInstance();
Job job = hz.getJet().newJob(p, config);

// 3. 모니터링
job.addStatusListener(event ->
    System.out.println(event.previousStatus() + " → " + event.newStatus()));
```

---

## 17. 유사 시스템 구축 시 핵심 설계 포인트

| 설계 결정 | Jet의 선택 | 이유 |
|----------|-----------|------|
| 스레딩 모델 | 협력적 멀티스레딩 | 컨텍스트 스위치 최소화, CPU 효율 극대화 |
| 진행 신호 | boolean 반환 (false=재시도) | 콜백/코루틴 없이 일시정지 가능 |
| 백프레셔 | 큐 가득 참 → 자연 감속 | 별도 프로토콜 불필요 |
| 스냅샷 | Chandy-Lamport + 2PC | 데이터 스트림 내 배리어로 분산 일관성 확보 |
| 워터마크 | 인밴드 특수 아이템 | 별도 채널 불필요, 순서 보장 |
| 최적화 | 연산자 퓨전 | 불필요한 직렬화/큐 오버헤드 제거 |
| 프로세서 분산 | 3단계 팩토리 | 각 레벨에서 파티션/멤버/프로세서 인식 커스텀 가능 |
| API 계층 | 고수준 DSL + 저수준 DAG | 편의성과 유연성 모두 제공 |
| 집계 | 6-primitive algebra | 병렬 집계, 슬라이딩 윈도우, 조기 결과 모두 지원 |

---

## 부록: 주요 소스 파일 위치

```
hazelcast/src/main/java/com/hazelcast/jet/
├── Jet.java                              # (Deprecated) 레거시 진입점
├── JetService.java                       # Jet API 인터페이스
├── Job.java                              # Job 인터페이스
├── Observable.java                       # Observable 인터페이스
├── Traverser.java / Traversers.java      # 협력적 반복 추상화
│
├── aggregate/
│   └── AggregateOperation.java           # 6-primitive 집계 정의
│   └── AggregateOperations.java          # 내장 집계 팩토리
│
├── config/
│   └── JobConfig.java                    # Job 설정
│   └── ProcessingGuarantee.java          # 처리 보장 수준
│
├── core/
│   ├── Processor.java                    # Processor 인터페이스
│   ├── AbstractProcessor.java            # Processor 구현 베이스
│   ├── DAG.java / Vertex.java / Edge.java # DAG 구조
│   ├── ProcessorMetaSupplier.java        # 3단계 팩토리 (1단계)
│   ├── ProcessorSupplier.java            # 3단계 팩토리 (2단계)
│   ├── Watermark.java                    # 워터마크
│   └── processor/Processors.java         # 내장 프로세서 팩토리
│
├── pipeline/
│   ├── Pipeline.java                     # 파이프라인 인터페이스
│   ├── BatchStage.java / StreamStage.java # 스테이지 API
│   ├── Sources.java / Sinks.java         # 내장 소스/싱크
│   ├── SourceBuilder.java / SinkBuilder.java # 커스텀 소스/싱크 빌더
│   ├── WindowDefinition.java             # 윈도우 정의
│   ├── ServiceFactory.java               # 외부 서비스 팩토리
│   └── file/FileFormat.java              # 파일 포맷 (JSON, CSV, Avro 등)
│
├── impl/
│   ├── pipeline/
│   │   ├── PipelineImpl.java             # Pipeline 구현 (adjacencyMap)
│   │   ├── Planner.java                  # Pipeline → DAG 변환 엔진
│   │   └── transform/*.java             # Transform 구현체들
│   └── execution/
│       ├── TaskletExecutionService.java  # 협력적 실행 엔진
│       ├── ProcessorTasklet.java         # Processor 상태 머신
│       └── WatermarkCoalescer.java       # 워터마크 병합
│
├── json/JsonUtil.java                    # JSON 유틸리티
└── retry/RetryStrategy.java              # 재시도 전략
```
