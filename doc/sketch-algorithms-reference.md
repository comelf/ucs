# Apache Pinot Sketch Algorithms Reference

> **Tags:** `#sketch` `#HyperLogLog` `#ThetaSketch` `#T-Digest` `#KLL` `#CPC` `#approximate-query` `#cardinality` `#percentile` `#pinot` `#DataSketches` `#pre-aggregation`

---

## 1. 개념 요약

Sketch는 **대규모 데이터에서 정확한 계산 대신 근사값을 O(KB) 메모리로 산출**하는 확률적 자료구조.
Pinot는 5가지 Sketch를 지원하며, 모두 세그먼트 간 병합(merge)과 Star-Tree 사전 집계가 가능하다.

```
정확한 DISTINCT COUNT:  10억 행 → HashSet → 메모리 수 GB
HyperLogLog:           10억 행 → Sketch → 메모리 ~1KB, 오차 ~2%
```

### 성능 비교

| Sketch | 메모리 | 오차 | 병합 속도 | 용도 |
|--------|--------|------|----------|------|
| **HLL** | ~1-10KB | ~2% | 빠름 | 고유값 수 추정 |
| **CPC** | ~0.8-8KB | ~2% | 빠름 | 고유값 수 (HLL 대비 40% 작음) |
| **Theta** | ~4-50KB | ~2% | 중간 | 고유값 + 집합 연산 (교집합/차집합) |
| **T-Digest** | ~10-100KB | 높은 정확도 | 느림 | 백분위수/분위수 |
| **KLL** | ~5-50KB | 높은 정확도 | 빠름 | 백분위수 (T-Digest보다 효율적) |

---

## 2. 기술 원리 총론

### 2.1 확률적 자료구조란?

Sketch는 **확률적 자료구조(Probabilistic Data Structure)**에 속한다. 정확한 답을 보장하는 대신 제한된 메모리에서 오차 범위 내의 근사값을 제공한다. 이 트레이드오프는 대규모 분산 시스템에서 필수적이다.

```
정확한 계산의 한계:
  DISTINCT COUNT(user_id) on 10억 행
  → HashSet에 모든 고유 값 저장 → 메모리 수 GB
  → 분산 환경에서 서버 간 HashSet 병합 → 네트워크 수 GB

확률적 계산:
  → Sketch에 값 삽입 → 메모리 수 KB
  → 서버 간 Sketch 병합 → 네트워크 수 KB
  → 오차 ~2% (실용적으로 충분)
```

### 2.2 핵심 수학 원리: 해시 함수의 균등 분포

모든 Sketch의 기반은 **해시 함수가 입력을 균등하게 분포시킨다**는 가정이다. 좋은 해시 함수는 입력과 무관하게 출력 비트가 0 또는 1일 확률이 각각 50%이다.

```
hash("Alice")  = 0b 0010 1101 ...
hash("Bob")    = 0b 1100 0011 ...
hash("Charlie")= 0b 0000 0101 ...

비트 위치별로 0과 1의 출현 확률 ≈ 0.5
→ 이 확률 분포를 이용하여 원본 데이터의 통계적 성질을 추정
```

### 2.3 병합 가능성 (Mergeability)

Sketch가 분산 시스템에서 유용한 이유는 **병합 가능성** 때문이다. 각 서버에서 독립적으로 만든 Sketch를 합쳐도 전체 데이터로 만든 Sketch와 (근사적으로) 동일한 결과를 얻을 수 있다.

```
수학적 조건:
  Sketch(A ∪ B) ≈ merge(Sketch(A), Sketch(B))

이 성질이 보장되는 이유 (HLL 예시):
  각 레지스터는 max(leading zeros)를 저장
  merge = 레지스터별 max 연산
  max(max(A), max(B)) = max(A ∪ B) ← 정확히 성립

Pinot에서의 활용:
  Stage 1 (서버별): 로컬 세그먼트에서 Sketch 생성
  Stage 2 (브로커): 서버별 Sketch를 merge
  → 전체 데이터를 한 곳에 모을 필요 없음
```

### 2.4 Accumulator 패턴 (지연 병합)

Pinot는 Sketch 병합 시 매번 즉시 합치지 않고, **threshold 개수만큼 모은 후 한꺼번에 병합**하는 지연 병합(lazy merge) 패턴을 사용한다.

```
즉시 병합 (N개 Sketch):
  merge(S1, S2) → merge(result, S3) → ... → merge(result, SN)
  각 단계에서 내부 구조 정리 → 총 비용 O(N × K)  (K = Sketch 크기)

지연 병합:
  [S1, S2, ..., Sn] 축적 → unionAll() 한 번 호출
  내부 구조 정리 1회 → 총 비용 O(N + K)

Theta Sketch 특수 최적화:
  theta 값이 작은 Sketch부터 정렬 후 union
  → 일찍 pruning되어 불필요한 항목 처리 회피
```

---

## 3. HyperLogLog (HLL / HLL+)

### 3.1 원리

Flajolet et al. (2007)이 제안한 알고리즘. 해시값의 **선행 0 비트 수(leading zeros)**를 관찰하여 고유값 수를 추정한다.

```
핵심 직관:
  동전을 던져 연속 앞면이 나올 확률:
    1번 연속: 50%     → 2번 이상 시도했을 가능성
    5번 연속: 3.1%    → 32번 이상 시도했을 가능성
    10번 연속: 0.1%   → 1024번 이상 시도했을 가능성

  해시의 비트도 동일한 확률 분포:
    hash(x) = 0b 0001xxxx → 선행 0 = 3개 → "최소 2^3 = 8개 고유값"
    hash(y) = 0b 0000001x → 선행 0 = 6개 → "최소 2^6 = 64개 고유값"

수학적 추정:
  E[max_leading_zeros] ≈ log2(n)  (n = 고유값 수)
  → n ≈ 2^(max_leading_zeros)

분산 감소를 위한 레지스터 분할:
  해시의 상위 log2m 비트로 레지스터 인덱스 결정
  나머지 비트에서 leading zeros 계산
  → 2^log2m개의 독립 추정치의 조화평균(harmonic mean) 사용
  → 표준 오차 = 1.04 / sqrt(2^log2m)
  → log2m=8 (256 레지스터): 오차 ≈ 6.5%
  → log2m=14 (16384 레지스터): 오차 ≈ 0.8%
```

### 3.2 핵심 코드

소스: `DistinctCountHLLAggregationFunction.java`

```java
// 생성 (line 413-419)
HyperLogLog hyperLogLog = new HyperLogLog(_log2m);  // 기본 log2m=8

// 값 추가 (line 79-150) - 타입별 분기
hyperLogLog.offer(intValue);     // INT
hyperLogLog.offer(longValue);    // LONG
hyperLogLog.offer(stringValue);  // STRING

// 병합 (line 334-351)
hyperLogLog1.addAll(hyperLogLog2);  // 두 sketch 합치기

// 결과 추출 (line 375-377)
long cardinality = hyperLogLog.cardinality();
```

### 3.3 설정

```
파라미터: log2m (기본값: 8)
레지스터 수: 2^log2m = 256개
메모리: ~(2^log2m + 2) × 4 bytes

SQL 사용:
  SELECT DISTINCTCOUNTHLL(user_id) FROM events
  SELECT DISTINCTCOUNTHLL(user_id, 12) FROM events  -- log2m=12
```

### 3.4 HLL+ (개선 버전)

소스: `HyperLogLogPlusUtils.java`

```
추가 파라미터: p (precision), sp (sparse precision)
기본값: p=14, sp=0
HLL 대비 작은 카디널리티에서 더 높은 정확도
```

### 3.5 외부 라이브러리

- `com.clearspring.analytics.stream.cardinality.HyperLogLog` (Clearspring Analytics)

### 3.6 주요 파일

```
pinot-core/.../aggregation/function/DistinctCountHLLAggregationFunction.java
pinot-segment-local/.../utils/HyperLogLogUtils.java
pinot-segment-local/.../utils/HyperLogLogPlusUtils.java
```

---

## 3. CPC Sketch (Compressed Probabilistic Counting)

### 4.1 원리

Kevin Lang (2017)이 제안한 FM85 알고리즘 기반 (논문: https://arxiv.org/abs/1708.06839). HLL과 동일한 고유값 추정 목적이나 **정보이론적 하한에 더 가까운 메모리 효율**을 달성한다.

```
HLL의 비효율:
  각 레지스터가 6비트(max leading zeros 저장)
  하지만 대부분의 레지스터 값은 매우 작음 (0~3)
  → 6비트 중 상위 비트 대부분이 0 → 공간 낭비

CPC의 개선:
  비트맵 기반 구조 사용
  → 각 슬롯에 해시값의 특정 비트 위치를 기록
  → 슬롯 대부분이 비어있으면 압축 효과 극대화
  → 결과적으로 HLL 대비 ~40% 메모리 절약

정보이론적 하한:
  고유값 n개를 오차 ε로 추정하는 데 필요한 최소 메모리:
    Ω(1/ε²) 비트
  CPC는 이 하한에 매우 근접 (HLL은 약 1.5~2배 초과)

내부 상태 전이:
  EMPTY → SPARSE (배열) → HYBRID → COMPRESSED (비트맵)
  → 카디널리티에 따라 자동으로 최적 표현 선택
```

### 4.2 핵심 코드

소스: `DistinctCountCPCSketchAggregationFunction.java`

```java
// 생성 (line 458-465)
CpcSketch sketch = new CpcSketch(_lgNominalEntries);  // 기본 lgK=12

// 값 추가 (line 136-202) - 타입별 분기
sketch.update(intValue);
sketch.update(longValue);
sketch.update(stringValue);

// 병합 (line 55-76, CpcSketchAccumulator)
CpcUnion union = new CpcUnion(_lgNominalEntries);
union.update(sketch1);
union.update(sketch2);
CpcSketch merged = union.getResult();

// 결과 추출 (line 425-429)
long cardinality = Math.round(sketch.getEstimate());
```

### 4.3 Accumulator 패턴 (지연 병합)

소스: `CpcSketchAccumulator.java`

```java
// threshold 개수만큼 모은 후 한꺼번에 병합 → 성능 최적화
public CpcSketchAccumulator(int lgNominalEntries, int threshold) {
    _lgNominalEntries = lgNominalEntries;
    setThreshold(threshold);  // 기본 2
}

// unionAll(): 축적된 sketch들을 한번에 union
CpcUnion union = new CpcUnion(_lgNominalEntries);
for (CpcSketch sketch : _accumulator) {
    union.update(sketch);
}
return union.getResult();
```

### 4.4 설정

```
파라미터: lgK (기본값: 12, 즉 k=4096)
메모리: HLL 대비 ~40% 절약

SQL 사용:
  SELECT DISTINCTCOUNTCPCSKETCH(user_id) FROM events
  SELECT DISTINCTCOUNTCPCSKETCH(user_id, 14) FROM events  -- lgK=14
```

### 4.5 외부 라이브러리

- `org.apache.datasketches.cpc.CpcSketch` (Apache DataSketches)
- `org.apache.datasketches.cpc.CpcUnion`

### 4.6 주요 파일

```
pinot-core/.../aggregation/function/DistinctCountCPCSketchAggregationFunction.java
pinot-segment-local/.../customobject/CpcSketchAccumulator.java
pinot-segment-local/.../customobject/SerializedCPCSketch.java
```

---

## 5. Theta Sketch

### 5.1 원리

KMV(K Minimum Values) 알고리즘의 변형. 해시 공간을 [0, 1)로 정규화한 후, **theta(θ) 임계값 이하의 해시만 유지**한다. 다른 Sketch와 달리 **집합 연산(교집합, 차집합)**이 가능한 것이 핵심 차별점이다.

```
기본 원리 (KMV):
  모든 값을 해시하여 [0, 1) 범위로 정규화
  가장 작은 K개의 해시값만 유지
  K번째로 작은 해시값 = θ (theta)

  카디널리티 추정: n ≈ K / θ
    직관: 균등 분포에서 K개가 [0, θ) 구간에 들어오려면
          전체 약 K/θ개의 고유값이 있어야 함

집합 연산이 가능한 이유:
  해시 함수가 동일하면, 같은 값은 어느 Sketch에서든 같은 해시값을 가짐

  UNION:     θ = min(θ_A, θ_B), 둘의 해시값 합친 후 θ 이하만 유지
  INTERSECT: A와 B 모두에 존재하는 해시값만 유지
  A-B:       A에 있고 B에 없는 해시값만 유지

  예: "A페이지와 B페이지 모두 방문한 사용자"
    Sketch_A: A페이지 방문 사용자 해시 집합
    Sketch_B: B페이지 방문 사용자 해시 집합
    INTERSECT(A, B): 양쪽 모두 존재하는 해시 → 교집합 카디널리티 추정

이것이 HLL/CPC로는 불가능한 이유:
  HLL: 레지스터에 max(leading zeros)만 저장 → 원본 해시 정보 소실
       → union은 가능(max 연산)하지만 intersect는 불가
  Theta: 해시값 자체를 유지 → 원본 정보로 집합 연산 가능
```

### 5.2 핵심 코드

소스: `DistinctCountThetaSketchAggregationFunction.java`

```java
// 생성 (line 1349-1370)
UpdateSketchBuilder builder = new UpdateSketchBuilder();
builder.setNominalEntries(_nominalEntries);  // 기본 16384
UpdateSketch sketch = builder.build();

// 값 추가 (line 192-438)
sketch.update(intValue);
sketch.update(stringValue);

// 병합 - Accumulator 패턴 사용 (line 992-1012)
ThetaSketchAccumulator acc = new ThetaSketchAccumulator(setOpBuilder, threshold);
acc.apply(sketch1);
acc.apply(sketch2);

// 집합 연산 (line 1393-1422)
SET_UNION:     Union.update(sketch1); Union.update(sketch2);
SET_INTERSECT: Intersection.intersect(sketch1); Intersection.intersect(sketch2);
SET_DIFF:      AnotB.setA(sketch1); AnotB.notB(sketch2);

// 결과 추출 (line 1081-1093)
long cardinality = Math.round(sketch.getEstimate());
```

### 5.3 필터 기반 집합 연산 (Theta Sketch의 고유 기능)

```sql
-- A페이지를 보고 B페이지도 본 사용자 수
SELECT DISTINCTCOUNTTHETASKETCH(
  user_id,
  'SET_INTERSECT($1, $2)',          -- 집합 교집합
  'page = ''A''',                    -- 필터 1 → $1
  'page = ''B'''                     -- 필터 2 → $2
) FROM pageviews
```

### 5.4 Accumulator 최적화 (지연 병합)

소스: `ThetaSketchAccumulator.java:61-93`

```java
// theta 값 기준 정렬 후 union → 더 효율적인 병합
private Sketch unionAll() {
    List<Sketch> sketches = new ArrayList<>(_accumulator);
    sketches.sort(Comparator.comparingDouble(Sketch::getTheta));  // line 86
    Union union = _setOperationBuilder.buildUnion();
    for (Sketch sketch : sketches) {
        union.union(sketch);
    }
    return union.getResult(true, null);
}
```

### 5.5 설정

```
파라미터:
  nominalEntries: 기본 16384 (2^14)
  samplingProbability: 기본 1.0 (업프론트 샘플링)
  accumulatorThreshold: 기본 2 (지연 병합 임계값)

파라미터 파싱 형식: "nominalEntries=8192;accumulatorThreshold=4"

SQL 사용:
  SELECT DISTINCTCOUNTTHETASKETCH(user_id) FROM events
  SELECT DISTINCTCOUNTTHETASKETCH(user_id, 'SET_UNION($1,$2)', 'filter1', 'filter2') FROM events
```

### 5.6 외부 라이브러리

- `org.apache.datasketches.theta.*` (Apache DataSketches)
  - UpdateSketch, Union, Intersection, AnotB, SetOperationBuilder

### 5.7 주요 파일

```
pinot-core/.../aggregation/function/DistinctCountThetaSketchAggregationFunction.java
pinot-segment-local/.../customobject/ThetaSketchAccumulator.java
pinot-core/.../aggregation/function/funnel/ThetaSketchAggregationStrategy.java
```

---

## 6. T-Digest

### 6.1 원리

Ted Dunning (2019)이 제안한 분위수 추정 알고리즘. 데이터를 **가변 크기의 centroid(중심점)들로 클러스터링**하되, 양 끝단(0%, 100% 부근)의 centroid를 더 작게 유지하여 꼬리 분위수(p99, p99.9)의 정확도를 높인다.

```
핵심 아이디어 - Scale Function:
  centroid의 최대 크기를 분위수 위치에 따라 다르게 설정
  → k(q) 함수: 분위수 q(0~1)에서 허용되는 centroid 크기

  k(q) = (δ/2) × sin⁻¹(2q - 1) / π
  (δ = compression factor)

  q=0.0 또는 q=1.0 (양 끝):  k 변화율 큼 → centroid 매우 작게
  q=0.5 (중앙):              k 변화율 작음 → centroid 크게 허용

  결과:
    p50 (중앙값): centroid 크기 ~δ/2 → 상대적으로 부정확
    p99 (꼬리):   centroid 크기 ~1    → 매우 정확
    p99.9:        centroid 크기 ~1    → 매우 정확

분위수 쿼리 응답:
  1. q × totalCount → 몇 번째 값인지 계산
  2. centroid 목록을 순회하며 누적 count 비교
  3. 해당 centroid의 mean 값 반환 (인접 centroid 간 보간)

병합:
  두 T-Digest의 centroid를 합친 후 scale function으로 재클러스터링
  → 병합 후에도 끝단 정밀도 유지
```

### 6.2 핵심 코드

소스: `PercentileTDigestAggregationFunction.java`

```java
// 생성 (line 277-284)
TDigest tDigest = TDigest.createMergingDigest(_compressionFactor);  // 기본 100

// 값 추가 (line 105-134)
tDigest.add(doubleValue);

// 병합 (line 220-229)
tDigest1.add(tDigest2);  // 두 digest 합치기

// 결과 추출 (line 253-255)
double p99 = tDigest.quantile(_percentile / 100.0);  // 예: 99번째 백분위수
```

### 6.3 설정

```
파라미터:
  compressionFactor: 기본 100 (높을수록 정확, 메모리 증가)
  percentile: 0~100 (정수 또는 실수)

SQL 사용:
  SELECT PERCENTILETDIGEST(latency, 99) FROM requests
  SELECT PERCENTILETDIGEST(latency, 99.9, 200) FROM requests  -- compression=200
```

### 6.4 외부 라이브러리

- `com.tdunning.math.stats.TDigest` (Ted Dunning's T-Digest)

### 6.5 주요 파일

```
pinot-core/.../aggregation/function/PercentileTDigestAggregationFunction.java
pinot-segment-local/.../customobject/SerializedTDigest.java
pinot-segment-local/.../aggregator/PercentileTDigestValueAggregator.java
```

---

## 7. KLL Sketch

### 7.1 원리

Karnin, Lang, Liberty (2016)가 제안한 분위수 추정 알고리즘. **레벨 기반 버퍼 구조**로 데이터를 관리하며, 버퍼가 꽉 차면 절반을 선별하여 상위 레벨로 올리는 **compaction**을 수행한다.

```
구조 (레벨 기반 버퍼):
  Level 0: [v1, v2, v3, v4, v5, v6, v7, v8]  ← 가장 최근 데이터 (용량 K)
  Level 1: [v9, v11, v13, v15]                ← compaction 결과 (용량 K/2~K)
  Level 2: [v10, v14]                         ← 더 높은 compaction
  ...

Compaction 과정:
  Level 0이 꽉 참 → 정렬 → 짝수/홀수 인덱스 중 하나를 선택하여 Level 1로 이동
  → Level 0 비움 → 새 데이터 수용 가능
  Level 1이 꽉 참 → 같은 방식으로 Level 2로 compaction
  → 상위 레벨일수록 "희석된" 데이터

수학적 오차 보장:
  rank 오차 ε = O(1/K × log(log(n/K)))
  → K=200이면: 약 1.5% 오차

T-Digest와의 근본적 차이:

  T-Digest:
    - 경험적(empirical) 오차 → 실험적으로 좋지만 수학적 보장 없음
    - 끝단(p99)에 최적화 → 중앙(p50)은 상대적으로 부정확
    - 비결정적: 입력 순서에 따라 결과 약간 다름

  KLL:
    - 수학적으로 증명된 오차 한계 → 최악의 경우에도 보장
    - 모든 분위수에서 균일한 정확도
    - 결정적: 동일 입력 → 동일 결과
    - 메모리 크기 예측 가능: O(K × log(log(n/K)))

병합:
  두 KLL의 같은 레벨 버퍼를 합친 후 필요 시 compaction
  → T-Digest보다 빠름 (재클러스터링 불필요)
```

### 7.2 핵심 코드

소스: `PercentileKLLAggregationFunction.java`

```java
// 생성 (line 188-195)
KllDoublesSketch sketch = KllDoublesSketch.newHeapInstance(_kValue);  // 기본 K=200

// 값 추가 (line 105-127)
sketch.update(doubleValue);

// 역직렬화 (line 199-205)
KllDoublesSketch deserialized = KllDoublesSketch.wrap(Memory.wrap(bytes));

// 병합 (line 232-241)
KllDoublesSketch union = KllDoublesSketch.newHeapInstance(_kValue);
union.merge(sketch1);
union.merge(sketch2);

// 결과 추출 (line 270-275)
double p99 = sketch.getQuantile(_percentile / 100);
```

### 7.3 T-Digest vs KLL 비교

| | T-Digest | KLL |
|--|---------|-----|
| 양 끝단 정확도 | 높음 (설계 목적) | 균일 |
| 이론적 오차 보장 | 경험적 | 수학적 증명 |
| 병합 속도 | 느림 | 빠름 |
| 메모리 예측성 | 가변적 | 고정적 |
| 적합 케이스 | p99, p99.9 등 꼬리 분석 | 범용 분위수 |

### 7.4 설정

```
파라미터:
  K: 기본 200 (높을수록 정확, 메모리 증가)
  percentile: 0~100

SQL 사용:
  SELECT PERCENTILEKLL(latency, 99) FROM requests
  SELECT PERCENTILEKLL(latency, 99, 400) FROM requests  -- K=400
```

### 7.5 외부 라이브러리

- `org.apache.datasketches.kll.KllDoublesSketch` (Apache DataSketches)
- `org.apache.datasketches.memory.Memory`

### 7.6 주요 파일

```
pinot-core/.../aggregation/function/PercentileKLLAggregationFunction.java
pinot-segment-local/.../customobject/SerializedKLL.java
pinot-segment-local/.../aggregator/PercentileKLLSketchAggregator.java
```

---

## 8. 공통 인프라

### 8.1 데이터 흐름

```
[세그먼트 1]          [세그먼트 2]          [세그먼트 N]
  raw 값들              raw 값들              raw 값들
     ↓                    ↓                    ↓
  Sketch 생성          Sketch 생성          Sketch 생성
  (aggregate)          (aggregate)          (aggregate)
     ↓                    ↓                    ↓
  직렬화               직렬화               직렬화
  (serialize)          (serialize)          (serialize)
     └──────────┬──────────┘──────────┬──────────┘
                ↓                     ↓
            병합 (merge)          병합 (merge)
                └──────────┬──────────┘
                           ↓
                    최종 결과 추출
                   (extractFinalResult)
```

### 8.2 CustomObjectAccumulator (지연 병합 베이스 클래스)

소스: `CustomObjectAccumulator.java`

```java
// Theta, CPC에서 사용하는 지연 병합 패턴
// threshold 개수만큼 sketch를 모은 후 한번에 union
// → 매번 merge하는 것보다 효율적
public abstract class CustomObjectAccumulator<T> {
    List<T> _accumulator;
    int _threshold;  // 기본 2

    void apply(T sketch) {
        _accumulator.add(sketch);
        if (_accumulator.size() >= _threshold) {
            unionAll();  // 축적된 sketch들을 한번에 병합
        }
    }
}
```

### 8.3 직렬화 (ObjectSerDeUtils)

소스: `ObjectSerDeUtils.java`

```
ObjectType 열거형에 등록된 Sketch 타입:
  HyperLogLog        = 6
  TDigest             = 10
  DataSketch (Theta)  = 12
  KllDataSketch       = custom
  CpcSketchAccumulator = custom
  ThetaSketchAccumulator = custom

직렬화: Sketch → byte[] → 네트워크 전송
역직렬화: byte[] → Sketch → 병합
```

### 8.4 Star-Tree 사전 집계 호환성

모든 Sketch 함수는 `canUseStarTree()` 메서드로 Star-Tree 호환 여부를 판단:

```
HLL:    쿼리 log2m == Star-Tree log2m
CPC:    쿼리 lgK   <= Star-Tree lgK     (LEQ, 낮은 정밀도 허용)
Theta:  쿼리 nominalEntries <= Star-Tree nominalEntries (LEQ)
T-Digest: 쿼리 compressionFactor == Star-Tree compressionFactor
KLL:    명시적 검증 없음
```

Star-Tree 설정 키 (`Constants.java`):

```java
HLL_LOG2M_KEY                          = "log2m"
CPCSKETCH_LGK_KEY                      = "lgK"
THETA_TUPLE_SKETCH_NOMINAL_ENTRIES     = "nominalEntries"
PERCENTILETDIGEST_COMPRESSION_FACTOR_KEY = "compressionFactor"
KLL_DOUBLE_SKETCH_K                    = "K"
```

---

## 9. SQL 함수 요약

### 고유값 카운트 계열

| SQL 함수 | Sketch | 파라미터 | 반환 |
|----------|--------|---------|------|
| `DISTINCTCOUNTHLL(col)` | HLL | log2m (기본 8) | LONG |
| `DISTINCTCOUNTHLL(col, 12)` | HLL | log2m=12 | LONG |
| `DISTINCTCOUNTHLLPLUS(col)` | HLL+ | p, sp | LONG |
| `DISTINCTCOUNTCPCSKETCH(col)` | CPC | lgK (기본 12) | LONG |
| `DISTINCTCOUNTTHETASKETCH(col)` | Theta | nominalEntries (기본 16384) | LONG |
| `DISTINCTCOUNTTHETASKETCH(col, 'SET_INTERSECT($1,$2)', ...)` | Theta | 집합 연산 | LONG |

### 백분위수 계열

| SQL 함수 | Sketch | 파라미터 | 반환 |
|----------|--------|---------|------|
| `PERCENTILETDIGEST(col, 99)` | T-Digest | percentile, compression (기본 100) | DOUBLE |
| `PERCENTILEKLL(col, 99)` | KLL | percentile, K (기본 200) | DOUBLE |

### Raw Sketch 반환 (중간 결과를 직렬화된 형태로)

| SQL 함수 | 용도 |
|----------|------|
| `DISTINCTCOUNTRAWHLL(col)` | HLL sketch 바이너리 반환 |
| `DISTINCTCOUNTRAWCPCSKETCH(col)` | CPC sketch 바이너리 반환 |
| `DISTINCTCOUNTRAWTHETASKETCH(col)` | Theta sketch 바이너리 반환 |
| `PERCENTILERAWTDIGEST(col, 99)` | T-Digest 바이너리 반환 |

---

## 10. 외부 라이브러리 의존성

| 라이브러리 | 사용 Sketch | 비고 |
|-----------|------------|------|
| **Clearspring Analytics** (`com.clearspring.analytics`) | HLL, HLL+ | 레거시, 안정적 |
| **Apache DataSketches** (`org.apache.datasketches`) | Theta, CPC, KLL | 최신, Yahoo 기여 |
| **T-Digest** (`com.tdunning.math.stats`) | T-Digest | Ted Dunning 구현 |
| **RoaringBitmap** | HLL, CPC (딕셔너리 최적화) | 필터링 가속 |

---

## 11. 주요 소스 파일 맵

```
pinot-core/.../query/aggregation/function/
  ├── DistinctCountHLLAggregationFunction.java          HLL 집계
  ├── DistinctCountCPCSketchAggregationFunction.java     CPC 집계
  ├── DistinctCountThetaSketchAggregationFunction.java   Theta 집계
  ├── PercentileTDigestAggregationFunction.java          T-Digest 집계
  └── PercentileKLLAggregationFunction.java              KLL 집계

pinot-segment-local/.../customobject/
  ├── ThetaSketchAccumulator.java      Theta 지연 병합
  ├── CpcSketchAccumulator.java        CPC 지연 병합
  ├── CustomObjectAccumulator.java     지연 병합 베이스 클래스
  ├── SerializedTDigest.java           T-Digest 직렬화
  └── SerializedKLL.java               KLL 직렬화

pinot-segment-local/.../utils/
  ├── HyperLogLogUtils.java            HLL 유틸리티
  └── HyperLogLogPlusUtils.java        HLL+ 유틸리티

pinot-core/.../common/
  └── ObjectSerDeUtils.java            Sketch 직렬화/역직렬화 프레임워크

pinot-segment-spi/.../Constants.java   Star-Tree 파라미터 키 상수
```
