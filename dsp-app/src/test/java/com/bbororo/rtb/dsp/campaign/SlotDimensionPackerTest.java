package com.bbororo.rtb.dsp.campaign;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("슬롯 규격 비트 패커(SlotDimensionPacker) 단위 및 경계값 테스트")
class SlotDimensionPackerTest {

    @ParameterizedTest(name = "[{index}] width={0}, height={1} 규격의 패킹 및 복원 검증")
    @CsvSource({
            "300, 250",
            "320, 50",
            "728, 90",
            "300, 600",
            "970, 250",
            "160, 600",
            "1, 1",
            "65535, 65535",
            "2147483647, 2147483647"
    })
    @DisplayName("왕복 가역성 검증: 다양한 IAB 표준 및 경계값 규격이 비트 패킹 후 100% 무손실 복원된다")
    void roundTripPreservesWidthAndHeightLosslessly(int width, int height) {
        long packed = SlotDimensionPacker.pack(width, height);

        assertEquals(width, SlotDimensionPacker.unpackWidth(packed));
        assertEquals(height, SlotDimensionPacker.unpackHeight(packed));
    }

    @Test
    @DisplayName("고유성 검증: 너비와 높이가 서로 뒤바뀐 규격(300x250 vs 250x300)은 서로 다른 long 키를 생성한다")
    void swappedDimensionsProduceDistinctKeys() {
        long keyA = SlotDimensionPacker.pack(300, 250);
        long keyB = SlotDimensionPacker.pack(250, 300);

        assertNotEquals(keyA, keyB);
    }

    @Test
    @DisplayName("부호 확장 방어 검증: height에 음수나 31번째 비트가 1인 값이 들어와도 상위 width 비트가 오염되지 않는다")
    void negativeHeightDoesNotPolluteUpperWidthBits() {
        int width = 300;
        int negativeHeight = -1; // 0xFFFFFFFF

        long packed = SlotDimensionPacker.pack(width, negativeHeight);

        assertEquals(width, SlotDimensionPacker.unpackWidth(packed));
        assertEquals(negativeHeight, SlotDimensionPacker.unpackHeight(packed));
    }
}
