package com.example.backend.service;

import com.example.backend.constants.ScheduleStatus;
import com.example.backend.constants.ShiftType;
import com.example.backend.dao.AttendanceRecordsDAO;
import com.example.backend.dao.DriverShiftsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.ScheduleMonthsDAO;
import com.example.backend.dto.request.DriverShiftDTO;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ScheduleMonthsEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 週日預設公休：建立班表時週日先排成休假；還沒安排的週日發布時自動補成休假；主管仍可把週日改成上班。
 *
 * <p>用「下個月」當測試月份，整個月都還沒到。司機只有王小明（id 1）。</p>
 */
class DriverScheduleServiceSundayTest {

    private static final YearMonth OCTOBER = YearMonth.now(ZoneId.of("Asia/Taipei")).plusMonths(1);
    private static final Long MONTH_ID = 9L;

    private ScheduleMonthsDAO scheduleMonthsDAO;
    private DriverShiftsDAO driverShiftsDAO;
    private DriversDAO driversDAO;
    private DriverScheduleService service;
    private DriversEntity driver;

    @BeforeEach
    void setUp() {
        scheduleMonthsDAO = mock(ScheduleMonthsDAO.class);
        driverShiftsDAO = mock(DriverShiftsDAO.class);
        driversDAO = mock(DriversDAO.class);
        service = new DriverScheduleService(scheduleMonthsDAO, driverShiftsDAO, driversDAO,
                mock(AttendanceRecordsDAO.class));

        driver = new DriversEntity();
        driver.setId(1L);
        driver.setName("王小明");
        when(driversDAO.findAllByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(driver));
        when(driversDAO.findById(1L)).thenReturn(Optional.of(driver));
        when(scheduleMonthsDAO.save(any())).thenAnswer(invocation -> {
            ScheduleMonthsEntity month = invocation.getArgument(0);
            month.setId(MONTH_ID);
            return month;
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void 建立月班表_週日是休假_其他天尚未安排() {
        when(scheduleMonthsDAO.findByScheduleMonth(OCTOBER.atDay(1))).thenReturn(Optional.empty());

        service.generateMonth(OCTOBER);

        ArgumentCaptor<List<DriverShiftsEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(driverShiftsDAO).saveAll(saved.capture());
        assertEquals(OCTOBER.lengthOfMonth(), saved.getValue().size());
        for (DriverShiftsEntity shift : saved.getValue()) {
            if (shift.getWorkDate().getDayOfWeek() == DayOfWeek.SUNDAY) {
                assertEquals(ShiftType.DAY_OFF, shift.getShiftType(), shift.getWorkDate().toString());
                assertNull(shift.getWorkStart());
            } else {
                assertEquals(ShiftType.UNASSIGNED, shift.getShiftType(), shift.getWorkDate().toString());
            }
        }
    }

    @Test
    void 發布_舊月份的週日還沒安排_自動補成休假後發布成功() {
        List<DriverShiftsEntity> shifts = monthShifts(OCTOBER, ShiftType.WORK, ShiftType.UNASSIGNED);
        givenDraftMonth(OCTOBER, shifts);

        service.publish(MONTH_ID);

        for (DriverShiftsEntity shift : shifts) {
            if (shift.getWorkDate().getDayOfWeek() == DayOfWeek.SUNDAY) {
                assertEquals(ShiftType.DAY_OFF, shift.getShiftType());
            }
        }
    }

    @Test
    void 發布_平日還沒安排_照樣擋下_訊息用司機姓名() {
        givenDraftMonth(OCTOBER, monthShifts(OCTOBER, ShiftType.UNASSIGNED, ShiftType.UNASSIGNED));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.publish(MONTH_ID));

        // 第一個沒排的平日：一號如果剛好是週日，會被補成休假，輪到二號
        LocalDate firstWeekday = OCTOBER.atDay(1).getDayOfWeek() == DayOfWeek.SUNDAY ? OCTOBER.atDay(2) : OCTOBER.atDay(1);
        assertEquals("班表尚未排完：司機 王小明 在 " + firstWeekday + " 尚未安排", e.getMessage());
    }

    @Test
    void 發布_週日已經排成上班或請假_保留主管的決定() {
        List<DriverShiftsEntity> work = monthShifts(OCTOBER, ShiftType.WORK, ShiftType.WORK);
        givenDraftMonth(OCTOBER, work);
        service.publish(MONTH_ID);
        for (DriverShiftsEntity shift : work) {
            assertEquals(ShiftType.WORK, shift.getShiftType(), shift.getWorkDate().toString());
        }

        List<DriverShiftsEntity> leave = monthShifts(OCTOBER, ShiftType.WORK, ShiftType.LEAVE);
        givenDraftMonth(OCTOBER, leave);
        service.publish(MONTH_ID);
        for (DriverShiftsEntity shift : leave) {
            boolean sunday = shift.getWorkDate().getDayOfWeek() == DayOfWeek.SUNDAY;
            assertEquals(sunday ? ShiftType.LEAVE : ShiftType.WORK, shift.getShiftType());
        }
    }

    @Test
    void 修改班次_週日可以改成上班() {
        DriverShiftsEntity sunday = firstSunday(monthShifts(OCTOBER, ShiftType.WORK, ShiftType.DAY_OFF));
        sunday.setWorkStart(null);
        givenDraftMonth(OCTOBER, List.of(sunday));
        when(driverShiftsDAO.findById(5L)).thenReturn(Optional.of(sunday));
        when(driverShiftsDAO.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DriverShiftDTO dto = new DriverShiftDTO();
        dto.setShiftType(ShiftType.WORK);
        dto.setWorkStart(LocalTime.of(8, 0));
        dto.setWorkEnd(LocalTime.of(17, 0));

        service.updateShift(5L, dto);

        assertEquals(ShiftType.WORK, sunday.getShiftType());
    }

    private DriverShiftsEntity firstSunday(List<DriverShiftsEntity> shifts) {
        for (DriverShiftsEntity shift : shifts) {
            if (shift.getWorkDate().getDayOfWeek() == DayOfWeek.SUNDAY) {
                return shift;
            }
        }
        throw new IllegalStateException("這個月沒有週日");
    }

    private void givenDraftMonth(YearMonth yearMonth, List<DriverShiftsEntity> shifts) {
        ScheduleMonthsEntity month = new ScheduleMonthsEntity();
        month.setId(MONTH_ID);
        month.setScheduleMonth(yearMonth.atDay(1));
        month.setStatus(ScheduleStatus.DRAFT);
        when(scheduleMonthsDAO.findForUpdate(MONTH_ID)).thenReturn(Optional.of(month));
        when(driverShiftsDAO.findAllByScheduleMonthIdOrderByWorkDateAscDriverIdAsc(MONTH_ID)).thenReturn(shifts);
    }

    /** 王小明那個月每天一筆班次：平日與週六用 weekdayType，週日用 sundayType */
    private List<DriverShiftsEntity> monthShifts(YearMonth yearMonth, ShiftType weekdayType, ShiftType sundayType) {
        List<DriverShiftsEntity> shifts = new ArrayList<>();
        for (LocalDate date = yearMonth.atDay(1); !date.isAfter(yearMonth.atEndOfMonth()); date = date.plusDays(1)) {
            DriverShiftsEntity shift = new DriverShiftsEntity();
            shift.setScheduleMonthId(MONTH_ID);
            shift.setDriverId(1L);
            shift.setWorkDate(date);
            shift.setShiftType(date.getDayOfWeek() == DayOfWeek.SUNDAY ? sundayType : weekdayType);
            shifts.add(shift);
        }
        return shifts;
    }
}
