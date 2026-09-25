package com.example.backend.service;

import com.example.backend.constants.AiActionType;
import com.example.backend.dto.respones.PendingActionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 確認執行後要在對話記憶補紀錄，模型下一句才知道清單已經寫入。 */
class AiAssistantServiceMemoryTest {

    @Test
    void 確認後在對話記憶寫入已確認的動作() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        AiAssistantService service = new AiAssistantService(chatMemory, null, null, "http://unused", null, null,
                null, null, null, null, null);

        PendingActionResponse action = new PendingActionResponse();
        action.setType(AiActionType.ASSIGN_DRIVER);
        action.setDate(LocalDate.of(2026, 9, 14));
        action.setSummary("指派司機 王小明（D003）給 TN-2001");

        service.recordConfirmed("admin:1", List.of(action));

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(chatMemory).add(eq("admin:1"), captor.capture());
        String text = captor.getValue().getText();
        assertTrue(text.contains("已在畫面上確認"), text);
        assertTrue(text.contains("2026-09-14 指派司機 王小明（D003）給 TN-2001"), text);
    }
}
