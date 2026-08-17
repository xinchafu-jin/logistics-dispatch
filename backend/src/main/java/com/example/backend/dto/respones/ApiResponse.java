package com.example.backend.dto.respones;

public class ApiResponse {

    // true 代表操作成功，false 代表操作失敗。
    private boolean success;

    // 要顯示給前端或使用者看的說明文字。
    private String message;

    /**
     * 建立一個 ApiResponse 物件。
     *
     * <p>建構子名稱必須和類別名稱相同，而且沒有回傳型別。
     * 使用 {@code new ApiResponse(true, "成功")} 時會執行此處。</p>
     *
     * @param success 操作是否成功
     * @param message 回應訊息
     */
    public ApiResponse(boolean success, String message) {
        // super() 會先呼叫父類別 Object 的建構子；即使省略，Java 也會自動補上。
        super();
        this.success = success;
        this.message = message;
    }

    /**
     * 取得成功狀態。
     *
     * <p>boolean 的 getter 通常使用 is 開頭，因此名稱是 isSuccess()。</p>
     *
     * @return 是否成功
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * @param success 要設定的成功狀態
     */
    public void setSuccess(boolean success) {
        this.success = success;
    }

    /**
     * @return 回應訊息
     */
    public String getMessage() {
        return message;
    }

    /**
     * @param message 要設定的回應訊息
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 快速建立成功回應的靜態工廠方法。
     *
     * <p>{@code static} 表示不必先建立 ApiResponse 物件，
     * 可以直接呼叫 {@code ApiResponse.success("成功")}。</p>
     *
     * @param message 成功訊息
     * @return success 固定為 true 的 ApiResponse
     */
    public static ApiResponse success(String message) {
        return new ApiResponse(true, message);
    }

    /**
     * 快速建立失敗回應。
     *
     * @param message 失敗原因
     * @return success 固定為 false 的 ApiResponse
     */
    public static ApiResponse failure(String message) {
        return new ApiResponse(false, message);
    }
}
