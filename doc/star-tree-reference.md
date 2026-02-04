# Apache Pinot Star-Tree Index Reference

> **Tags:** `#star-tree` `#pre-aggregation` `#OLAP` `#columnar-index` `#pinot` `#group-by` `#materialized-view` `#off-heap` `#BFS-serialization` `#binary-search`

---

## 1. 개념 요약

Star-Tree는 **빌드 시점에 GROUP BY 집계를 미리 수행**하고, 그 결과를 트리 구조로 저장하는 인덱스.
쿼리 시 원본 행을 스캔하지 않고 트리 노드의 사전 집계 값을 조회하여 **밀리초 단위 응답**을 달성한다.

### 핵심 원리

```
일반 쿼리:  10억 행 스캔 → 런타임 집계 → 수 초
Star-Tree:  트리 3~4단계 탐색 → 사전 집계 값 읽기 → 수 밀리초
```

### 트레이드오프

| 장점 | 비용 |
|------|------|
| 집계 쿼리 O(트리 깊이) | 세그먼트 크기 증가 (집계 문서 추가 저장) |
| GROUP BY + SUM/COUNT/AVG 즉시 응답 | 빌드 시간 증가 |
| 차원 조합별 미리 계산 | 설정된 차원/메트릭 조합에만 유효 |

---

## 2. 데이터 모델

### 2.1 원본 → 집계 문서 생성 과정

```
원본 (8행):
  city=Seoul, country=KR, amount=1000
  city=Seoul, country=KR, amount=2000
  city=Seoul, country=KR, amount=500
  city=Busan, country=KR, amount=800
  city=Busan, country=KR, amount=1200
  city=Tokyo, country=JP, amount=3000
  city=Tokyo, country=JP, amount=1500
  city=Osaka, country=JP, amount=900

빌드 시 생성되는 집계 문서:
  docId=0   city=Seoul, country=KR  → SUM(amount)=3500
  docId=1   city=Busan, country=KR  → SUM(amount)=2000
  docId=2   city=Tokyo, country=JP  → SUM(amount)=4500
  docId=3   city=Osaka, country=JP  → SUM(amount)=900
  docId=4   city=*,     country=KR  → SUM(amount)=5500   ← Star 노드
  docId=5   city=*,     country=JP  → SUM(amount)=5400   ← Star 노드
  docId=6   city=Seoul, country=*   → SUM(amount)=3500
  docId=7   city=Busan, country=*   → SUM(amount)=2000
  docId=8   city=Tokyo, country=*   → SUM(amount)=4500
  docId=9   city=Osaka, country=*   → SUM(amount)=900
  docId=10  city=*,     country=*   → SUM(amount)=10900  ← 전체 합산
```

`*`(Star) = 해당 차원의 **모든 값을 집계**한 와일드카드 노드.

### 2.2 트리 구조

```
                     Root (*, *)
                  aggregatedDocId=10
                   /              \
            country=KR          country=JP
            docId=4             docId=5
            /       \            /       \
      city=Seoul city=Busan city=Tokyo city=Osaka
      docId=0    docId=1    docId=2    docId=3
```

---

## 3. 노드 바이너리 포맷

소스: `OffHeapStarTreeNode.java:29-37`

**노드 1개 = 28바이트 고정 크기** (7 x int)

```
offset  field            bytes  설명
──────  ──────────────── ─────  ─────────────────────────────
0x00    dimensionId      4      이 노드가 분할하는 차원의 인덱스
0x04    dimensionValue   4      차원 값 (dictId). Star면 -1
0x08    startDocId       4      이 노드가 커버하는 집계 문서 범위 시작
0x0C    endDocId         4      집계 문서 범위 끝 (exclusive)
0x10    aggregatedDocId  4      이 노드 자체의 집계 결과 문서 ID
0x14    firstChildId     4      첫 번째 자식의 nodeId (-1이면 리프)
0x18    lastChildId      4      마지막 자식의 nodeId
```

접근 방식: `nodeId * 28 + fieldOffset` → O(1) 랜덤 액세스

```java
// OffHeapStarTreeNode.java:49-51
private int getInt(int fieldOffset) {
    return _dataBuffer.getInt(_nodeId * SERIALIZABLE_SIZE_IN_BYTES + fieldOffset);
}
```

---

## 4. 파일 레이아웃

소스: `StarTreeBuilderUtils.java:136-158`, `OffHeapStarTree.java:45-83`

```
┌──────────────────────────────────────────────────┐
│ HEADER                                            │
│  MAGIC_MARKER    0xBADDA55B00DAD00D    (8 bytes)  │
│  VERSION         1                     (4 bytes)  │
│  headerSize      헤더 전체 크기          (4 bytes)  │
│  numDimensions                         (4 bytes)  │
│  dimension[i]:                                    │
│    dimensionId                         (4 bytes)  │
│    nameLength                          (4 bytes)  │
│    nameBytes                      (가변 bytes)     │
│  numNodes                              (4 bytes)  │
├──────────────────────────────────────────────────┤
│ NODE DATA (BFS 순서, 연속 배치)                    │
│  Node 0: [28 bytes]  Root                         │
│  Node 1: [28 bytes]  Root의 첫째 자식              │
│  Node 2: [28 bytes]  Root의 둘째 자식              │
│  Node 3: [28 bytes]  ...                          │
│  ...                                              │
│  총 크기 = numNodes × 28 bytes                    │
└──────────────────────────────────────────────────┘

바이트 오더: Little-Endian (하위 호환)
검증: MAGIC_MARKER 확인 + headerSize == rootNodeOffset
```

---

## 5. 빌드 과정

소스: `StarTreeBuilderUtils.java:203-227`

```
1. 원본 행을 차원 조합별로 그룹핑
2. 각 그룹에 대해 메트릭 집계 (SUM, COUNT 등)
3. Star 노드 생성 (차원 와일드카드 집계)
4. TreeNode 인메모리 트리 구성
5. BFS 순회하며 직렬화:
   - 자식 노드를 dimensionValue 기준 정렬
   - firstChildId/lastChildId 계산
   - 28바이트씩 순서대로 기록
```

```java
// StarTreeBuilderUtils.java:203-227 (직렬화 핵심)
Queue<TreeNode> queue = new LinkedList<>();
queue.add(rootNode);
int currentNodeId = 0;
while (!queue.isEmpty()) {
    TreeNode node = queue.remove();
    // 자식을 dimensionValue 순 정렬 → 이진 탐색 가능하게
    sortedChildren.sort((o1, o2) -> Integer.compare(o1._dimensionValue, o2._dimensionValue));
    int firstChildId = currentNodeId + queue.size() + 1;
    int lastChildId = firstChildId + sortedChildren.size() - 1;
    writeNode(dataBuffer, offset, node, firstChildId, lastChildId);
    queue.addAll(sortedChildren);
    currentNodeId++;
}
```

BFS 순서 저장의 이점: **같은 부모의 자식 노드들이 메모리에 연속 배치** → 캐시 친화적

---

## 6. 쿼리 시 탐색

### 6.1 자식 노드 검색: 이진 탐색

소스: `OffHeapStarTreeNode.java:102-135`

```java
// 자식 노드들이 dimensionValue 순 정렬되어 있으므로 이진 탐색
int low = _firstChildId;
int high = getInt(LAST_CHILD_ID_OFFSET);
while (low <= high) {
    int mid = (low + high) / 2;
    OffHeapStarTreeNode midNode = new OffHeapStarTreeNode(_dataBuffer, mid);
    int midValue = midNode.getDimensionValue();
    if (midValue == dimensionValue) return midNode;
    else if (midValue < dimensionValue) low = mid + 1;
    else high = mid - 1;
}
```

### 6.2 Star 노드 검색: O(1)

```java
// Star 노드는 항상 첫 번째 자식으로 배치
if (dimensionValue == StarTreeNode.ALL) {
    OffHeapStarTreeNode firstNode = new OffHeapStarTreeNode(_dataBuffer, _firstChildId);
    if (firstNode.getDimensionValue() == StarTreeNode.ALL) return firstNode;
}
```

### 6.3 쿼리 예시

```sql
SELECT SUM(amount) FROM orders WHERE country='KR'
```

```
탐색 경로:
  Root → country 차원 → country=KR (이진 탐색)
       → city 차원 조건 없음 → Star(*) 노드 (첫 번째 자식)
       → aggregatedDocId 읽기 → 집계 문서에서 SUM 값 반환

탐색 비용: O(depth × log(cardinality))
```

---

## 7. 설정 (StarTreeV2BuilderConfig)

소스: `StarTreeBuilderUtils.java:76-94`

```json
{
  "starTreeIndexConfigs": [{
    "dimensionsSplitOrder": ["country", "city"],
    "skipStarNodeCreationForDimensions": ["city"],
    "functionColumnPairs": [
      "SUM__amount",
      "COUNT__*"
    ],
    "maxLeafRecords": 10000
  }]
}
```

| 설정 | 설명 |
|------|------|
| `dimensionsSplitOrder` | 트리 분할 차원 순서 (상위 → 하위) |
| `skipStarNodeCreationForDimensions` | Star 노드 생략할 차원 (공간 절약) |
| `functionColumnPairs` | 사전 집계할 함수-컬럼 쌍 |
| `maxLeafRecords` | 리프 노드 최대 문서 수 (이하면 더 분할하지 않음) |

---

## 8. 지원 집계 함수

소스: `StarTreeBuilderUtils.java:336-421`

| 함수 | 파라미터 |
|------|---------|
| `SUM`, `COUNT`, `MIN`, `MAX`, `AVG` | 없음 |
| `DISTINCTCOUNTHLL` / `DISTINCTCOUNTRAWHLL` | `log2m` |
| `DISTINCTCOUNTHLLPLUS` / `DISTINCTCOUNTRAWHLLPLUS` | `p`, `sp` |
| `DISTINCTCOUNTULL` / `DISTINCTCOUNTRAWULL` | `p` |
| `DISTINCTCOUNTCPCSKETCH` / `DISTINCTCOUNTRAWCPCSKETCH` | `lgK` |
| `SUMPRECISION` | `precision` |
| `PERCENTILETDIGEST` / `PERCENTILERAWTDIGEST` | `compressionFactor` |
| `DISTINCTCOUNTTHETASKETCH` / `DISTINCTCOUNTRAWTHETASKETCH` | `nominalEntries` |
| `DISTINCTCOUNTTUPLESKETCH` | `nominalEntries` |

---

## 9. 주요 소스 파일 맵

```
pinot-segment-spi/
  └── .../index/startree/
      ├── StarTreeNode.java              노드 인터페이스 (7개 필드 정의)
      ├── StarTree.java                  트리 인터페이스 (getRoot, getDimensionNames)
      ├── StarTreeV2Constants.java       파일명/메타데이터 상수
      ├── StarTreeV2Metadata.java        트리 메타데이터
      ├── AggregationFunctionColumnPair  함수-컬럼 쌍 키
      └── AggregationSpec.java           집계 스펙

pinot-segment-local/
  └── .../startree/
      ├── OffHeapStarTree.java           트리 로딩 (파일 → 메모리)
      ├── OffHeapStarTreeNode.java       노드 읽기 (28B 고정 레이아웃)
      ├── StarTreeBuilderUtils.java      직렬화, BFS 기록, 설정 생성
      └── v2/builder/
          └── StarTreeV2BuilderConfig.java  빌드 설정

pinot-core/
  └── .../query/
      └── ...StarTreeFilterPlanNode...   쿼리 시 트리 탐색 플랜
```

---

## 10. 참조 라이브러리 / 의존성

| 라이브러리 | 용도 | 사용 위치 |
|-----------|------|----------|
| `PinotDataBuffer` (pinot-segment-spi) | Off-heap 메모리 매핑 (mmap) | OffHeapStarTree, OffHeapStarTreeNode |
| `Guava` (`Preconditions`, `MoreObjects`) | 유효성 검증, toString 포맷 | OffHeapStarTree, OffHeapStarTreeNode |
| `Apache Commons Configuration2` | 세그먼트 메타데이터 properties 관리 | StarTreeBuilderUtils.removeStarTrees |
| `Apache Commons IO` (`FileUtils`) | 파일 삭제 | StarTreeBuilderUtils.removeStarTrees |
| `Jackson` (`JsonNode`) | JSON 기반 세그먼트 메타데이터 파싱 | StarTreeBuilderUtils.generateBuilderConfigs |
| `Dictionary` (pinot-segment-spi) | dimensionValue(dictId) → 실제 값 변환 | OffHeapStarTree.printTree |

---

## 11. 핵심 상수

```java
MAGIC_MARKER = 0xBADDA55B00DAD00DL   // 파일 유효성 검증
VERSION = 1                           // 현재 포맷 버전
SERIALIZABLE_SIZE_IN_BYTES = 28       // 노드 1개 크기 (7 × 4B)
INVALID_ID = -1                       // 리프 노드의 childId, Star 노드의 dimensionValue
StarTreeNode.ALL = -1                 // Star(*) 와일드카드 값
```
