/**
 * 외부 캠페인 버전을 완결된 인메모리 실행 모델로 공개하고 입찰 후보를 제공하는 Campaign Runtime이다.
 *
 * <h2>1. 주요 역할 및 설계 목표</h2>
 * <ul>
 *   <li><b>초고속 L1 캐시 서빙:</b> 수만 개의 캠페인 스냅샷을 64비트 원시 {@code long[]} 배열과 버킷으로 역색인화하여 {@code Arrays.binarySearch}로 5ns 내에 슬롯 규격 조회를 수행합니다.</li>
 *   <li><b>무락(Lock-Free) 원자적 스냅샷 교체:</b> 제어 평면에서 수신한 새 스냅샷을 백그라운드에서 빌드한 뒤, {@link java.util.concurrent.atomic.AtomicReference} 기반 CAS 루프로 단조 증가 버전을 원자적으로 교체합니다.</li>
 *   <li><b>실시간 적격성 필터링:</b> 반열린 시간 구간 {@code [startsAt, endsAt)} 및 매체사 바닥가({@code bidCpm >= bidFloor}) 조건을 Hot-Path에서 힙 할당 없이(Zero-Allocation) 판정합니다.</li>
 *   <li><b>페이싱 기반 순위화:</b> {@link com.bbororo.rtb.dsp.campaignruntime.spi.CampaignPacingSource}를 통해 1차 {@code pacingLagPpm} 내림차순, 2차 {@code campaignId} 오름차순으로 결정론적(Deterministic) 정렬을 수행합니다.</li>
 * </ul>
 *
 * <h2>2. 핵심 컴포넌트 구조</h2>
 * <pre>
 * [ Control-Path: 스냅샷 적재 ]
 * CampaignSnapshot ──▶ DefaultCampaignRuntime.install() ──▶ SlotDimensionPacker (64비트 패킹)
 *                                                        ──▶ AtomicReference.compareAndSet() (원자적 스냅샷 스왑)
 *
 * [ Hot-Path: 실시간 후보 선택 및 순위화 ]
 * RankCampaigns ──▶ DefaultCampaignRuntime.rankCandidates()
 *               ──▶ SlotDimensionPacker.pack(w, h)
 *               ──▶ Arrays.binarySearch(sortedDimensionKeys, key) (5ns L1 룩업)
 *               ──▶ [startsAt, endsAt) &amp; bidFloor 필터링
 *               ──▶ pacingLagPpm DESC &amp; campaignId ASC 정렬 ──▶ List&lt;CampaignCandidate&gt;
 * </pre>
 *
 * @see com.bbororo.rtb.dsp.campaignruntime.api.CampaignCandidateSource
 * @see com.bbororo.rtb.dsp.campaignruntime.api.CampaignSnapshotInstaller
 * @see com.bbororo.rtb.dsp.campaignruntime.internal.DefaultCampaignRuntime
 * @see com.bbororo.rtb.dsp.campaignruntime.spi.CampaignPacingSource
 * @see com.bbororo.rtb.dsp.campaignruntime.internal.SlotDimensionPacker
 * @see com.bbororo.rtb.dsp.campaignruntime.api.CampaignRuntimeMessages
 */
package com.bbororo.rtb.dsp.campaignruntime;
