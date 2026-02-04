# Apache Pinot Upsert Mechanism Reference

> **Tags:** `#upsert` `#primary-key` `#partial-update` `#validDocIds` `#RoaringBitmap` `#comparison-column` `#delete-marker` `#compaction` `#consistency` `#pinot`

---

## 1. 개념 요약

Upsert는 **Primary Key 기반으로 기존 레코드를 덮어쓰는 메커니즘**이다.
스트리밍 수집 환경에서 동일 키의 최신 값만 쿼리에 노출되도록 한다.

```
Kafka 이벤트:
  {pk=1, name="Alice", amount=100, ts=1}   ← 최초 삽입
  {pk=1, name="Alice", amount=200, ts=2}   ← 업데이트 (amount 변경)
  {pk=1, name="Alice", amount=0,   ts=3, _deleted=true}  ← 삭제

쿼리 결과 (FULL upsert):
  ts=2 시점까지: {pk=1, name="Alice", amount=200}
  ts=3 이후:     (결과 없음, 삭제됨)
```

---

## 2. 기술 원리

### 2.1 Append-Only 저장소에서의 Upsert

Pinot의 세그먼트는 본질적으로 **Append-Only(추가 전용)** 저장소이다. 이미 기록된 행을 물리적으로 수정하거나 삭제하지 않는다. Upsert는 이 제약 하에서 "논리적 업데이트"를 구현한다.

```
RDBMS 방식 (In-place Update):
  row[pk=1] = {amount=100}  →  row[pk=1] = {amount=200}  (같은 위치 덮어쓰기)
  → 랜덤 I/O 발생, 쓰기 성능 저하

Pinot 방식 (Append + Invalidation):
  docId=0: {pk=1, amount=100}  ← validDocIds에 포함
  docId=1: {pk=1, amount=200}  ← 새로 추가, validDocIds에 포함
  docId=0 → validDocIds에서 제거 (무효화)
  → 순차 쓰기만 발생, 쓰기 성능 유지

이것이 가능한 이유:
  쿼리 시 validDocIds 비트맵으로 필터링
  → 무효화된 문서는 쿼리 결과에 포함되지 않음
  → 물리적 삭제 없이도 논리적 최신 상태 보장
```

이 접근 방식은 LSM-Tree(Log-Structured Merge Tree)와 동일한 원리이다. Cassandra, RocksDB 등도 같은 패턴을 사용한다.

### 2.2 RoaringBitmap과 validDocIds

문서의 유효/무효 상태를 추적하는 데 **RoaringBitmap**을 사용한다. RoaringBitmap은 일반 비트맵 대비 메모리 효율이 높으면서도 O(1) 접근을 제공하는 압축 비트맵이다.

```
일반 비트맵:
  10만 개 문서 → 10만 비트 = 12.5KB (항상 고정)

RoaringBitmap:
  연속된 구간은 run-length 인코딩으로 압축
  [0, 1, 2, ..., 99999] → 단 수 바이트

  내부 구조 (16비트 상위/하위 분할):
  ┌─────────────────┐
  │ Container[0]     │ docId 0~65535 중 set된 것
  │ Container[1]     │ docId 65536~131071 중 set된 것
  │ ...              │
  └─────────────────┘

  Container 타입 자동 선택:
  - ArrayContainer: 희소 (4096개 미만) → 정렬 배열
  - BitmapContainer: 밀집 (4096개 이상) → 비트맵
  - RunContainer: 연속 구간 → (start, length) 쌍
```

ThreadSafeMutableRoaringBitmap은 내부적으로 synchronized 블록으로 동시 수정을 보호한다.

### 2.3 ConcurrentHashMap의 Lock Striping

Primary Key → RecordLocation 매핑에 `ConcurrentHashMap`을 사용한다. Java의 ConcurrentHashMap은 **Lock Striping** 기법으로 높은 동시성을 제공한다.

```
일반 HashMap + 전체 Lock:
  모든 읽기/쓰기가 하나의 Lock을 경합
  → 동시성 = 1 (직렬 처리)

ConcurrentHashMap (Lock Striping):
  해시 테이블을 여러 세그먼트(stripe)로 분할
  각 stripe마다 독립 Lock
  → 서로 다른 stripe의 읽기/쓰기는 병렬 처리

Pinot에서의 활용:
  compute(pk, updateFunction):
    해당 pk의 stripe Lock만 획득
    → 다른 pk의 업데이트와 병렬 실행
    → 같은 pk에 대해서만 직렬화 보장
```

### 2.4 Comparison Column과 Causal Ordering

Upsert에서 "어떤 레코드가 더 최신인가"를 판별하는 것은 분산 시스템의 **인과적 순서(causal ordering)** 문제이다. Pinot는 Comparison Column(보통 타임스탬프)으로 이를 해결한다.

```
문제:
  분산 Kafka 파티션에서 동일 PK의 이벤트 도착 순서 보장 불가
  Producer A: {pk=1, ts=100} → Partition 0
  Producer B: {pk=1, ts=200} → Partition 0
  네트워크 지연으로 ts=200이 먼저 도착할 수 있음

해결:
  comparisonValue(새 레코드) >= comparisonValue(기존 레코드) 일 때만 교체
  → 도착 순서와 무관하게 항상 논리적 최신 레코드가 유효

다중 비교 컬럼:
  독립적 업데이트 스트림이 각각 다른 컬럼을 갱신하는 경우
  → 컬럼별 독립 비교로 부분 업데이트 충돌 방지
```

### 2.5 SYNC vs SNAPSHOT 일관성 모델

쿼리 일관성은 **Read-Write Lock** 패턴으로 구현되며, 두 모드는 락의 범위가 다르다.

```
SYNC (강한 일관성):
  원리: 교차 세그먼트 업데이트 시 Write Lock 획득
        쿼리 시 Read Lock 획득
  효과: 쿼리가 항상 원자적 상태를 봄 (두 세그먼트의 비트맵이 동기적)
  비용: Upsert 스레드와 쿼리 스레드 간 경합 → 지연 증가

SNAPSHOT (최종 일관성):
  원리: 주기적으로(기본 3초) 비트맵의 복사본(스냅샷) 생성
        쿼리는 스냅샷을 읽기 (락 불필요)
        Upsert는 원본 비트맵을 수정 (Read Lock만)
  효과: 쿼리가 최대 refreshInterval만큼 지연된 상태를 볼 수 있음
  이점: Upsert와 쿼리가 거의 독립적 → 높은 처리량

  스냅샷 교체는 volatile 참조의 원자적 교체(atomic swap):
    _segmentQueryableDocIdsMap = newMap;  // 하나의 참조 교체
    → 쿼리 스레드는 이전 맵 또는 새 맵 중 하나를 봄 (중간 상태 없음)
```

---

## 3. 모드

소스: `UpsertConfig.java`

### 2.1 FULL Upsert

전체 레코드를 교체. 새 레코드가 이전 레코드를 완전히 대체한다.

### 2.2 PARTIAL Upsert

컬럼별로 다른 병합 전략을 적용. null이 아닌 필드만 업데이트 가능.

| 전략 | 동작 | 예시 |
|------|------|------|
| `OVERWRITE` | 새 값이 null이 아니면 교체 | 기본값 |
| `FORCE_OVERWRITE` | null이어도 무조건 교체 | 명시적 null 설정 |
| `INCREMENT` | 이전값 + 새값 | 카운터, 누적 합계 |
| `APPEND` | 배열에 추가 (중복 허용) | 태그 목록 |
| `UNION` | 배열 합집합 (중복 제거) | 고유 카테고리 |
| `IGNORE` | 항상 이전값 유지 | 불변 필드 |
| `MAX` | max(이전값, 새값) | 최고 점수 |
| `MIN` | min(이전값, 새값) | 최저 가격 |

소스: `pinot-segment-local/.../upsert/merger/columnar/` 디렉토리

### 2.3 Consistency 모드

| 모드 | 동작 | 적합 케이스 |
|------|------|-----------|
| `NONE` | 락 없음, 최종 일관성 | 낮은 지연 우선 |
| `SYNC` | Write Lock으로 원자적 업데이트 | 강한 일관성 필요 |
| `SNAPSHOT` | 주기적 비트맵 스냅샷 | 균형 (기본 3초 간격) |

---

## 4. 핵심 데이터 구조

### 3.1 Primary Key → RecordLocation 맵

소스: `ConcurrentMapPartitionUpsertMetadataManager.java`

```java
ConcurrentHashMap<Object, RecordLocation> _primaryKeyToRecordLocationMap;

RecordLocation {
    IndexSegment _segment;       // 이 키의 최신 레코드가 있는 세그먼트
    int _docId;                  // 세그먼트 내 문서 ID
    Comparable _comparisonValue; // 비교 값 (타임스탬프 등)
}
```

### 3.2 validDocIds 비트맵 (세그먼트별)

```
세그먼트 A (10개 문서):
  validDocIds:     [1,1,0,1,0,1,1,0,1,1]
                    ↑     ↑   ↑
                    유효  무효  무효 (더 새로운 레코드에 의해 대체됨)

  queryableDocIds: [1,1,0,1,0,0,1,0,1,1]
                              ↑
                              삭제 마커로 제외됨
```

- `validDocIds`: 대체되지 않은 문서 (ThreadSafeMutableRoaringBitmap)
- `queryableDocIds`: validDocIds - 삭제된 문서 (deleteColumn 설정 시)

---

## 5. 레코드 수집 흐름

### 4.1 새 레코드 도착 → 메타데이터 업데이트

소스: `BasePartitionUpsertMetadataManager.java`, `ConcurrentMapPartitionUpsertMetadataManager.java`

```
새 레코드 도착
    ↓
RecordInfoReader로 추출:
  - PrimaryKey (해시 → 조회 키)
  - ComparisonValue (타임스탬프 등)
  - DeleteFlag (삭제 여부)
    ↓
_primaryKeyToRecordLocationMap 조회
    │
    ├── 키 없음 (신규):
    │   ① validDocIds.add(newDocId)
    │   ② queryableDocIds.add(newDocId)  (삭제 아니면)
    │   ③ map.put(pk, RecordLocation(segment, docId, comparisonValue))
    │
    ├── 키 있음 + 새 값이 더 최신:
    │   ① 이전 세그먼트의 validDocIds.remove(oldDocId)
    │   ② 새 세그먼트의 validDocIds.add(newDocId)
    │   ③ map.put(pk, RecordLocation(newSegment, newDocId, newComparisonValue))
    │
    └── 키 있음 + 이전 값이 더 최신:
        → out-of-order 레코드, 무시 (또는 드롭)
```

### 4.2 Partial Upsert 병합 흐름

소스: `PartialUpsertHandler.java`, `PartialUpsertColumnarMerger.java`

```
새 레코드 + 이전 레코드
    ↓
PartialUpsertHandler.merge(previousRow, newRow, resultHolder)
    ↓
각 컬럼마다:
    ├── PK/비교 컬럼 → 건너뜀
    ├── FORCE_OVERWRITE → 항상 새 값
    ├── OVERWRITE → 새 값이 null 아니면 교체, null이면 이전값 유지
    ├── INCREMENT → previous + new
    ├── APPEND → concat(previous[], new[])
    ├── UNION → deduplicate(previous[] ∪ new[])
    ├── IGNORE → 항상 이전값
    ├── MAX → max(previous, new)
    └── MIN → min(previous, new)
    ↓
병합된 행이 세그먼트에 기록됨
```

### 4.3 커스텀 Merger 지원

소스: `PartialUpsertMergerFactory.java`

```java
// UpsertConfig에 커스텀 클래스 지정 가능
String customMergerClassName = upsertConfig.getPartialUpsertMergerClass();
if (StringUtils.isNotBlank(customMergerClassName)) {
    return (PartialUpsertMerger) Class.forName(customMergerClassName)
        .getConstructor(List.class, List.class, UpsertConfig.class)
        .newInstance(primaryKeyColumns, comparisonColumns, upsertConfig);
}
// 미지정 시 기본 PartialUpsertColumnarMerger 사용
```

---

## 6. Comparison Column (버전 판별)

소스: `UpsertUtils.java`, `ComparisonColumns.java`

### 5.1 단일 비교 컬럼 (일반적)

```json
{ "comparisonColumns": ["event_timestamp"] }
```

새 레코드의 `event_timestamp`가 기존보다 크면 업데이트.

### 5.2 다중 비교 컬럼

```json
{ "comparisonColumns": ["timestamp", "sequence_number"] }
```

소스: `ComparisonColumns.java`

```java
// 다중 컬럼: 각 컬럼을 독립적으로 비교
// null이 아닌 첫 번째 컬럼의 값이 더 크면 업데이트
// Sealed 세그먼트 간 비교 시 comparisonIndex도 고려
```

---

## 7. 삭제 처리

### 6.1 Delete Record Column

```json
{ "deleteRecordColumn": "_deleted" }
```

소스: `UpsertUtils.java`

```java
// 삭제 레코드 처리
public static void doAddDocId(validDocIds, queryableDocIds, docId, recordInfo) {
    validDocIds.add(docId);                          // 항상 valid에 추가
    if (queryableDocIds != null && !recordInfo.isDeleteRecord()) {
        queryableDocIds.add(docId);                  // 삭제 아닐 때만 queryable에 추가
    }
}
```

### 6.2 Metadata TTL

```json
{ "metadataTTL": 86400 }
```

오래된 삭제 키를 메모리에서 제거하여 메타데이터 크기 관리.

소스: `ConcurrentMapPartitionUpsertMetadataManager.java`의 `doRemoveExpiredPrimaryKeys()`

---

## 8. 쿼리 시 일관성 보장

소스: `UpsertViewManager.java`

### 7.1 SYNC 모드

```
Upsert 스레드: WriteLock 획득 → 두 세그먼트 원자적 업데이트 → WriteLock 해제
쿼리 스레드:   ReadLock 획득 → validDocIds 읽기 → ReadLock 해제

→ 쿼리는 항상 일관된 상태를 본다 (약간의 지연)
```

### 7.2 SNAPSHOT 모드

```
Upsert 스레드: ReadLock → validDocIds 업데이트 → 변경 세그먼트 마킹
배치 리프레시: WriteLock → 변경된 세그먼트의 비트맵 스냅샷 갱신 (3초 간격)
쿼리 스레드:  스냅샷 맵 읽기 (락 불필요)

→ 쿼리는 최대 refreshInterval만큼 지연된 상태를 본다
```

소스: `UpsertViewManager.java:213-265`

```java
void doBatchRefreshUpsertView(long upsertViewFreshnessMs, boolean forceRefresh) {
    _upsertViewLock.writeLock().lock();
    try {
        Map<IndexSegment, MutableRoaringBitmap> updated = new HashMap<>();
        for (IndexSegment segment : _trackedSegments) {
            if (_updatedSegmentsSinceLastRefresh.contains(segment)) {
                updated.put(segment, getQueryableDocIdsSnapshot(segment));  // 새 스냅샷
            } else {
                updated.put(segment, _segmentQueryableDocIdsMap.get(segment));  // 재사용
            }
        }
        _segmentQueryableDocIdsMap = updated;  // 원자적 교체
        _updatedSegmentsSinceLastRefresh.clear();
    } finally {
        _upsertViewLock.writeLock().unlock();
    }
}
```

---

## 9. Compaction (Minion Task)

소스: `UpsertCompactionTaskExecutor.java`

무효화된 레코드를 물리적으로 제거하여 세그먼트 크기를 줄인다.

```
원본 세그먼트 (100 문서)
  validDocIds = {0,1,3,5,7,8,9}  → 7개만 유효
  나머지 93개는 대체/삭제된 레코드
       ↓
  [CompactedPinotSegmentRecordReader]
  유효한 7개 문서만 읽기
       ↓
  새 세그먼트 (7 문서)
  docId 0~6으로 재매핑
```

흐름:
1. 서버에서 현재 validDocIds 비트맵 획득
2. CRC 검증 (세그먼트 버전 불일치 방지)
3. 유효 문서만 읽어서 새 세그먼트 생성
4. 원본 세그먼트 교체

---

## 10. 설정 예시

```json
{
  "tableConfig": {
    "upsertConfig": {
      "mode": "PARTIAL",
      "comparisonColumns": ["event_timestamp"],
      "deleteRecordColumn": "_deleted",
      "metadataTTL": 86400,
      "hashFunction": "MURMUR3",
      "enableSnapshot": true,
      "consistencyMode": "SNAPSHOT",
      "upsertViewRefreshIntervalMs": 3000,
      "partialUpsertStrategies": {
        "amount": "INCREMENT",
        "tags": "UNION",
        "name": "OVERWRITE",
        "created_at": "IGNORE"
      },
      "defaultPartialUpsertStrategy": "OVERWRITE"
    }
  }
}
```

---

## 11. 주요 소스 파일 맵

```
pinot-spi/.../config/table/
  └── UpsertConfig.java                    설정 (Mode, Strategy, ConsistencyMode)

pinot-segment-local/.../upsert/
  ├── PartitionUpsertMetadataManager.java  인터페이스
  ├── BasePartitionUpsertMetadataManager.java  템플릿 (1311 lines)
  ├── ConcurrentMapPartitionUpsertMetadataManager.java  HashMap 기반 구현
  ├── ConcurrentMapPartitionUpsertMetadataManagerForConsistentDeletes.java  삭제 일관성
  ├── UpsertViewManager.java               쿼리 시 일관성 (SYNC/SNAPSHOT)
  ├── UpsertUtils.java                     RecordInfoReader, validDocIds 조작
  ├── ComparisonColumns.java               다중 비교 컬럼 로직
  ├── PartialUpsertHandler.java            Partial 병합 조정
  ├── UpsertContext.java                   초기화 컨텍스트
  └── merger/
      ├── PartialUpsertMergerFactory.java  Merger 팩토리
      ├── PartialUpsertColumnarMerger.java 컬럼별 병합 구현
      └── columnar/
          ├── OverwriteMerger.java
          ├── ForceOverwriteMerger.java
          ├── IncrementMerger.java
          ├── AppendMerger.java
          ├── UnionMerger.java
          ├── IgnoreMerger.java
          ├── MaxMerger.java
          └── MinMerger.java

pinot-plugins/.../minion/tasks/upsertcompaction/
  └── UpsertCompactionTaskExecutor.java    Compaction 실행
```

---

## 12. 참조 라이브러리

| 라이브러리 | 용도 |
|-----------|------|
| `RoaringBitmap` / `ThreadSafeMutableRoaringBitmap` | validDocIds, queryableDocIds 비트맵 |
| `ConcurrentHashMap` (Java) | Primary Key → RecordLocation 맵 |
| `ReadWriteLock` (Java) | SYNC/SNAPSHOT 일관성 |
| Apache Helix | 세그먼트 상태 관리, ZK 메타데이터 |

---

## 13. 제약 사항

| 제약 | 이유 |
|------|------|
| 실시간 테이블만 지원 | Upsert는 스트리밍 수집 기반 |
| 파티션 내에서만 동작 | 같은 PK는 같은 파티션으로 라우팅 필수 |
| Tiered Storage 이동 불가 (SingleTier) | validDocIds가 인스턴스 종속적 |
| 메모리 비용 | 모든 PK를 HashMap에 유지해야 함 |
