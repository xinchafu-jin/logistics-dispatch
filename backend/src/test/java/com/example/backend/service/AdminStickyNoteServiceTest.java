package com.example.backend.service;

import com.example.backend.dao.AdminStickyNotesDAO;
import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.dto.request.AdminStickyNoteRequestDTO;
import com.example.backend.entity.AdminStickyNotesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminStickyNoteServiceTest {
    private AdminStickyNotesDAO notesDAO;
    private AdminUsersDAO adminUsersDAO;
    private AdminStickyNoteService service;

    @BeforeEach
    void setUp() {
        notesDAO = mock(AdminStickyNotesDAO.class);
        adminUsersDAO = mock(AdminUsersDAO.class);
        service = new AdminStickyNoteService(notesDAO, adminUsersDAO);
        when(adminUsersDAO.existsById(1L)).thenReturn(true);
        when(adminUsersDAO.existsById(2L)).thenReturn(true);
        when(notesDAO.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 建立便利貼的擁有者只採用登入主管Id() {
        AdminStickyNoteRequestDTO dto = request("明天追單");

        service.create(1L, dto);

        ArgumentCaptor<AdminStickyNotesEntity> captor =
                ArgumentCaptor.forClass(AdminStickyNotesEntity.class);
        verify(notesDAO).save(captor.capture());
        assertEquals(1L, captor.getValue().getAdminId());
        assertEquals("明天追單", captor.getValue().getContent());
    }

    @Test
    void 主管不能修改別人的便利貼() {
        when(notesDAO.findByIdAndAdminId(50L, 2L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class,
                () -> service.update(2L, 50L, request("偷改")));
    }

    @Test
    void 查詢永遠帶登入主管Id() {
        service.findMine(2L);

        verify(notesDAO).findByAdminIdOrderBySortOrderAscUpdatedAtDesc(2L);
    }

    private AdminStickyNoteRequestDTO request(String content) {
        AdminStickyNoteRequestDTO dto = new AdminStickyNoteRequestDTO();
        dto.setContent(content);
        return dto;
    }
}
