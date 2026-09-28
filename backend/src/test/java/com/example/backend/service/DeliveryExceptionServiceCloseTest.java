package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.dao.DeliveryRecordsDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.entity.ExceptionCasesEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一般異常的結案（PATCH /api/exceptions/{id}/close）哪些能結、哪些要走別的流程。
 *
 * <p>最重要的是有補送單的不能在這裡結：結了之後補送單會一直停在待確認，
 * 而 confirm 只收 OPEN 的案件，補送單就再也送不進待排車。</p>
 */
class DeliveryExceptionServiceCloseTest {

    private static final long CASE_ID = 300L;

    private ExceptionCasesDAO exceptionCasesDAO;
    private DeliveryExceptionService service;

    @BeforeEach
    void setUp() {
        exceptionCasesDAO = mock(ExceptionCasesDAO.class);
        service = new DeliveryExceptionService(exceptionCasesDAO, mock(DeliveryRecordsDAO.class),
                mock(OrdersDAO.class), mock(RoutesDAO.class));
    }

    @Test
    void 有補送單的異常_不能用一般結案() {
        ExceptionCasesEntity shortage = givenCase(ExceptionType.SHORTAGE);
        shortage.setFollowUpOrderId(99L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.closeGeneral(CASE_ID, "王主管", "已處理"));

        assertEquals("這筆異常有補送單，請使用確認送入待排車", error.getMessage());
        assertEquals(ExceptionStatus.OPEN, shortage.getStatus());
        verify(exceptionCasesDAO, never()).save(any());
    }

    @Test
    void 司機回報_要到司機回報清單結案() {
        givenCase(ExceptionType.DRIVER_REPORT);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.closeGeneral(CASE_ID, "王主管", "已處理"));

        assertEquals("司機回報請在異常中心的司機回報清單結案", error.getMessage());
        verify(exceptionCasesDAO, never()).save(any());
    }

    @Test
    void 沒有補送單的損壞_可以結案() {
        ExceptionCasesEntity damage = givenCase(ExceptionType.DAMAGE);

        service.closeGeneral(CASE_ID, "王主管", "  門市同意收下  ");

        assertEquals(ExceptionStatus.CLOSED, damage.getStatus());
        assertEquals("王主管", damage.getHandledBy());
        assertEquals("門市同意收下", damage.getResolution());
        verify(exceptionCasesDAO).save(damage);
    }

    private ExceptionCasesEntity givenCase(ExceptionType type) {
        ExceptionCasesEntity exceptionCase = new ExceptionCasesEntity();
        exceptionCase.setId(CASE_ID);
        exceptionCase.setType(type);
        exceptionCase.setStatus(ExceptionStatus.OPEN);
        when(exceptionCasesDAO.findForUpdate(CASE_ID)).thenReturn(Optional.of(exceptionCase));
        return exceptionCase;
    }
}
