package com.bbororo.rtb.dsp.campaignruntime.internal;

/**
 * 광고 슬롯 너비(width)와 높이(height)를 64비트 원시 long 키 하나로 무할당(Zero-Allocation) 패킹/언패킹한다.
 *
 * <pre>
 * [ 64비트 long 레이아웃 ]
 * - 상위 32비트: width (너비)
 * - 하위 32비트: height (높이)
 * </pre>
 */
public final class SlotDimensionPacker {

    private SlotDimensionPacker() {
    }

    /** 두 int 정수를 64비트 원시 long 1개로 패킹한다. */
    public static long pack(int width, int height) {
        return (((long) width) << 32) | Integer.toUnsignedLong(height);
    }

    /** 64비트 패킹 키에서 상위 32비트의 width를 무부호 논리 시프트(>>>)로 복원한다. */
    public static int unpackWidth(long packedKey) {
        return (int) (packedKey >>> 32);
    }

    /** 64비트 패킹 키에서 하위 32비트의 height를 복원한다. */
    public static int unpackHeight(long packedKey) {
        return (int) packedKey;
    }
}
