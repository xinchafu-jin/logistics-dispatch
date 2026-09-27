package com.example.backend.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** 勾選代表司機已實際確認正常；缺項不能完成點交前檢查。 */
public record PreTripInspectionRequest(
        @NotNull @Positive Long routeId,
        @NotNull @DecimalMin("0.00") @DecimalMax("9.99") @Digits(integer = 1, fraction = 2) BigDecimal alcoholMgL,
        @AssertTrue(message = "請確認已完成酒測") boolean alcoholTested,
        @AssertTrue(message = "請確認頭燈正常") boolean headlights,
        @AssertTrue(message = "請確認尾燈正常") boolean taillights,
        @AssertTrue(message = "請確認方向燈正常") boolean turnSignals,
        @AssertTrue(message = "請確認煞車燈正常") boolean brakeLights,
        @AssertTrue(message = "請確認左前輪有氣且無破胎") boolean frontLeftTire,
        @AssertTrue(message = "請確認右前輪有氣且無破胎") boolean frontRightTire,
        @AssertTrue(message = "請確認左後輪有氣且無破胎") boolean rearLeftTire,
        @AssertTrue(message = "請確認右後輪有氣且無破胎") boolean rearRightTire,
        @AssertTrue(message = "請確認行車紀錄器正常") boolean dashcam
) {
    public boolean allChecked() {
        return alcoholTested && headlights && taillights && turnSignals && brakeLights
                && frontLeftTire && frontRightTire && rearLeftTire && rearRightTire && dashcam;
    }
}
