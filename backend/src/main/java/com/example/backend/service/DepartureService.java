package com.example.backend.service;

import com.example.backend.dto.request.MileageRequestDTO;
import com.example.backend.dto.request.PreTripInspectionRequestDTO;
import com.example.backend.dto.respones.PreTripInspectionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 出車：送出出車前安全檢查，通過就接著用原本的出車里程流程，記下行車紀錄器里程與照片。
 *
 * <p>三步在同一個交易：存檢查紀錄 → MileageLogsService.start（記出車里程）→ attachStartPhoto（行車紀錄器照片）。
 * 任何一步失敗整筆退回，連剛存的檢查、照片都不算（照片由 PreTripInspectionService 在 rollback 後刪掉），
 * 不會出現「檢查過了、出車沒記到」的半套狀態；司機照畫面上的原因改好（例如先打上班卡）再送一次就好。</p>
 *
 * <p>一張行車紀錄器照片同時當兩個證明：行車紀錄器有開機，以及出車時的里程讀數。</p>
 *
 * <p>為什麼另開這個 service、不寫在 PreTripInspectionService 裡：MileageLogsService.start 開頭會呼叫
 * PreTripInspectionService.requirePassed，如果 PreTripInspectionService 再反過來呼叫 MileageLogsService，
 * 兩個 bean 互相依賴，Spring Boot 預設不允許循環依賴，啟動就會失敗。</p>
 */
@Service
@Transactional
public class DepartureService {

    private final PreTripInspectionService preTripInspectionService;
    private final MileageLogsService mileageLogsService;

    public DepartureService(
            PreTripInspectionService preTripInspectionService,
            MileageLogsService mileageLogsService
    ) {
        this.preTripInspectionService = preTripInspectionService;
        this.mileageLogsService = mileageLogsService;
    }

    public PreTripInspectionResponse submitPreTripInspection(
            Long driverId,
            PreTripInspectionRequestDTO request,
            MultipartFile alcoholPhoto,
            MultipartFile dashcamPhoto,
            MultipartFile faultPhoto
    ) {
        PreTripInspectionResponse inspection = preTripInspectionService.submit(driverId, request, alcoholPhoto, faultPhoto);
        if (!inspection.isPassed()) {
            // 沒通過就不出車，檢查紀錄照樣留著給主管看
            return inspection;
        }

        requireDashcamReading(request, dashcamPhoto);
        MileageRequestDTO mileage = new MileageRequestDTO();
        mileage.setOdometer(request.getOdometer());
        // 原本的出車流程：start 會再確認一次檢查通過，也會檢查已打卡、今天還沒出車、這台車沒有別人沒收的里程
        mileageLogsService.start(driverId, mileage);
        mileageLogsService.attachStartPhoto(driverId, dashcamPhoto);

        // 重查一次，回傳的「已出車、出車里程」跟資料庫一致
        return preTripInspectionService.findLatest(driverId, request.getRouteId());
    }

    /**
     * 出車一定要有行車紀錄器的里程和照片。放在存完檢查之後才檢查：通過與否由 PreTripInspectionService 判定，
     * 這裡不重寫一份規則；缺了就丟例外，整筆交易退回，剛存的那筆檢查也跟著消失。
     */
    private void requireDashcamReading(PreTripInspectionRequestDTO request, MultipartFile dashcamPhoto) {
        if (request.getOdometer() == null) {
            throw new IllegalArgumentException("請填出車時的行車紀錄器里程");
        }
        if (dashcamPhoto == null || dashcamPhoto.isEmpty()) {
            throw new IllegalArgumentException("請拍行車紀錄器畫面，要看得到里程");
        }
    }
}
