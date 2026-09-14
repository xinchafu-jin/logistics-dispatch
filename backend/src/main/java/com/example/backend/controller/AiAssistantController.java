package com.example.backend.controller;

import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.dto.respones.PendingActionResponse;
import com.example.backend.service.AiAssistantService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 調度員以自然語言操作班表、訂單與派車的 AI 助理 API。 */
@RestController
@RequestMapping("/api/ai")
public class AiAssistantController {

    private final AiAssistantService aiAssistantService;

    public AiAssistantController(AiAssistantService aiAssistantService) {
        this.aiAssistantService = aiAssistantService;
    }

    /** 送出一句話給助理；回應同時帶回最新的待執行清單，前端面板不必另外再查一次。 */
    @PostMapping("/chat")
    public ChatReply chat(@AuthenticationPrincipal Jwt jwt, @RequestBody ChatRequest request) {
        String conversationId = conversationId(jwt);
        String reply = aiAssistantService.chat(conversationId, request.getMessage());
        return new ChatReply(reply, aiAssistantService.getPlan(conversationId));
    }

    /** 查詢待執行清單；重新整理頁面後用來還原面板。 */
    @GetMapping("/plan")
    public List<PendingActionResponse> getPlan(@AuthenticationPrincipal Jwt jwt) {
        return aiAssistantService.getPlan(conversationId(jwt));
    }

    /** 調度員確認後才真正執行整批動作。 */
    @PostMapping("/plan/confirm")
    public List<DispatchResponse> confirmPlan(@AuthenticationPrincipal Jwt jwt) {
        return aiAssistantService.confirmPlan(conversationId(jwt));
    }

    /** 整批放棄，不執行任何動作。 */
    @DeleteMapping("/plan")
    public void clearPlan(@AuthenticationPrincipal Jwt jwt) {
        aiAssistantService.clearPlan(conversationId(jwt));
    }

    /**
     * 對話與待執行清單共用同一把鑰匙。
     *
     * <p>由登入者的 JWT 現場組出，別人的 conversationId 組不出來，
     * 因此不需要另外比對「這份清單是不是我的」。</p>
     */
    private String conversationId(Jwt jwt) {
        return "admin:" + jwt.getClaim("userId").toString();
    }

    /** 調度員輸入的一句話。 */
    public static class ChatRequest {

        private String message;

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    /** 助理回覆，外加目前累積的待執行動作。 */
    public static class ChatReply {

        private final String reply;
        private final List<PendingActionResponse> pendingActions;

        public ChatReply(String reply, List<PendingActionResponse> pendingActions) {
            this.reply = reply;
            this.pendingActions = pendingActions;
        }

        public String getReply() {
            return reply;
        }

        public List<PendingActionResponse> getPendingActions() {
            return pendingActions;
        }
    }
}
