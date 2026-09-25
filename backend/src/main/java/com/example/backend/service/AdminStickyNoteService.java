package com.example.backend.service;

import com.example.backend.dao.AdminStickyNotesDAO;
import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.dto.request.AdminStickyNoteRequestDTO;
import com.example.backend.dto.respones.AdminStickyNoteResponse;
import com.example.backend.entity.AdminStickyNotesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class AdminStickyNoteService {
    private static final String DEFAULT_COLOR = "#FFF4B8";

    private final AdminStickyNotesDAO notesDAO;
    private final AdminUsersDAO adminUsersDAO;

    public AdminStickyNoteService(AdminStickyNotesDAO notesDAO, AdminUsersDAO adminUsersDAO) {
        this.notesDAO = notesDAO;
        this.adminUsersDAO = adminUsersDAO;
    }

    @Transactional(readOnly = true)
    public List<AdminStickyNoteResponse> findMine(Long adminId) {
        requireAdmin(adminId);
        return notesDAO.findByAdminIdOrderBySortOrderAscUpdatedAtDesc(adminId).stream()
                .map(this::toResponse)
                .toList();
    }

    public AdminStickyNoteResponse create(Long adminId, AdminStickyNoteRequestDTO dto) {
        requireAdmin(adminId);
        AdminStickyNotesEntity note = new AdminStickyNotesEntity();
        note.setAdminId(adminId);
        apply(dto, note);
        return toResponse(notesDAO.save(note));
    }

    public AdminStickyNoteResponse update(Long adminId, Long noteId, AdminStickyNoteRequestDTO dto) {
        requireAdmin(adminId);
        AdminStickyNotesEntity note = findOwnedNote(adminId, noteId);
        apply(dto, note);
        return toResponse(notesDAO.saveAndFlush(note));
    }

    public void delete(Long adminId, Long noteId) {
        requireAdmin(adminId);
        notesDAO.delete(findOwnedNote(adminId, noteId));
    }

    private AdminStickyNotesEntity findOwnedNote(Long adminId, Long noteId) {
        return notesDAO.findByIdAndAdminId(noteId, adminId)
                .orElseThrow(() -> new EntityNotFoundException("找不到這位主管的便利貼"));
    }

    private void requireAdmin(Long adminId) {
        if (adminId == null || !adminUsersDAO.existsById(adminId)) {
            throw new EntityNotFoundException("找不到登入中的主管帳號");
        }
    }

    private void apply(AdminStickyNoteRequestDTO dto, AdminStickyNotesEntity note) {
        if (dto == null) {
            throw new IllegalArgumentException("便利貼資料不能為空");
        }
        note.setTitle(normalizeOptional(dto.getTitle(), 120, "便利貼標題不能超過 120 字"));
        note.setContent(requireContent(dto.getContent()));
        note.setColor(normalizeColor(dto.getColor()));
        note.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
    }

    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("便利貼內容不能為空");
        }
        String normalized = content.trim();
        if (normalized.length() > 2000) {
            throw new IllegalArgumentException("便利貼內容不能超過 2000 字");
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String normalizeColor(String color) {
        if (color == null || color.isBlank()) {
            return DEFAULT_COLOR;
        }
        String normalized = color.trim();
        if (normalized.length() > 20) {
            throw new IllegalArgumentException("便利貼顏色格式過長");
        }
        return normalized;
    }

    private AdminStickyNoteResponse toResponse(AdminStickyNotesEntity note) {
        return new AdminStickyNoteResponse(
                note.getId(), note.getTitle(), note.getContent(), note.getColor(), note.getSortOrder(),
                note.getCreatedAt(), note.getUpdatedAt(), note.getVersion());
    }
}
