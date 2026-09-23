package com.example.backend.excition;

import com.example.backend.dto.respones.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    /**
     * 處理目前專案使用的業務邏輯例外。
     *
     * <p>{@code @ExceptionHandler({A.class, B.class})} 中的大括號是陣列語法，
     * 表示同一個方法可以處理多種例外。</p>
     *
     * @param e 被攔截到的例外物件
     * @return HTTP 400 Bad Request 與例外訊息
     */
    @ExceptionHandler({IllegalArgumentException.class, RuntimeException.class})
    public ResponseEntity<ApiResponse> handleRuntimeException(Exception e) {
        // e.getMessage() 取得 throw new ...("訊息") 中傳入的文字。
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(e.getMessage()));
    }

    /**
     * 處理 {@code @Valid} 驗證失敗，例如標題空白或日期為 null。
     *
     * <p>BindingResult 保存所有欄位驗證結果；目前程式取第一個欄位錯誤的
     * defaultMessage，這就是 ValidationMsg 中設定的文字。</p>
     *
     * @param e Spring MVC 在 DTO 驗證失敗時拋出的例外
     * @return HTTP 400 Bad Request 與第一筆驗證訊息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse> handleValidationException(MethodArgumentNotValidException e) {
        String defaultMessage = e.getBindingResult().getFieldError().getDefaultMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(defaultMessage));
    }

    /**
     * 處理找不到檔案或路徑，例如大頭照檔案已不在磁碟上。
     *
     * <p>沒有這個方法時，這個例外會落到下面的 {@code Exception.class}，
     * 變成 HTTP 500，看起來像伺服器壞掉。其實只是檔案不存在，應該回 404。</p>
     *
     * @param e Spring MVC 找不到對應資源時拋出的例外
     * @return HTTP 404 Not Found
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse> handleNoResourceFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("找不到檔案"));
    }

    /**
     * 處理前面方法都沒有指定的其他例外。
     *
     * <p>{@code Exception.class} 範圍很廣，因此這個方法相當於最後一道保護網，
     * 回傳 HTTP 500 代表伺服器內部錯誤。</p>
     *
     * @param e 未被其他方法處理的例外
     * @return HTTP 500 Internal Server Error
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse> handleGeneralException(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("Internal Server Error: " + e.getMessage()));
    }
}
