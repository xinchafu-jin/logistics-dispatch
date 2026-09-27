package com.example.backend.dao;

import com.example.backend.constants.ScheduleStatus;
import com.example.backend.entity.AttendanceRecordsEntity;
import com.example.backend.entity.DeliveryRecordsEntity;
import com.example.backend.entity.DriverShiftsEntity;
import com.example.backend.entity.DriverLeaveRequestsEntity;
import com.example.backend.constants.LeaveRequestStatus;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RouteLegMileagesEntity;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Date-bounded report reads kept separate from the existing write-path DAOs. */
@Repository
public class ReportReadDAO {
    private static final int ID_BATCH_SIZE = 500;
    private final EntityManager entityManager;

    public ReportReadDAO(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<OrdersEntity> orders(LocalDate from, LocalDate to, Long warehouseId) {
        String jpql = "select o from OrdersEntity o where o.deliveryDate between :from and :to"
                + (warehouseId == null ? "" : " and o.warehouseId = :warehouseId")
                + " order by o.deliveryDate, o.id";
        var query = entityManager.createQuery(jpql, OrdersEntity.class)
                .setParameter("from", from).setParameter("to", to);
        if (warehouseId != null) {
            query.setParameter("warehouseId", warehouseId);
        }
        return query.getResultList();
    }

    public List<RoutesEntity> routes(LocalDate from, LocalDate to, Long warehouseId) {
        String jpql = "select r from RoutesEntity r where r.date between :from and :to"
                + (warehouseId == null ? "" : " and r.warehouseId = :warehouseId")
                + " order by r.date, r.id";
        var query = entityManager.createQuery(jpql, RoutesEntity.class)
                .setParameter("from", from).setParameter("to", to);
        if (warehouseId != null) {
            query.setParameter("warehouseId", warehouseId);
        }
        return query.getResultList();
    }

    public List<DriverShiftsEntity> publishedShifts(LocalDate from, LocalDate to) {
        return entityManager.createQuery("""
                        select s from DriverShiftsEntity s, ScheduleMonthsEntity m
                        where s.scheduleMonthId = m.id and m.status = :published
                          and s.workDate between :from and :to
                        order by s.workDate, s.driverId
                        """, DriverShiftsEntity.class)
                .setParameter("published", ScheduleStatus.PUBLISHED)
                .setParameter("from", from).setParameter("to", to)
                .getResultList();
    }

    public List<AttendanceRecordsEntity> attendance(LocalDate from, LocalDate to) {
        return entityManager.createQuery("""
                        select a from AttendanceRecordsEntity a
                        where a.workDate between :from and :to
                        order by a.workDate, a.driverId
                        """, AttendanceRecordsEntity.class)
                .setParameter("from", from).setParameter("to", to)
                .getResultList();
    }

    public List<MileageLogsEntity> mileage(LocalDate from, LocalDate to) {
        return entityManager.createQuery("""
                        select m from MileageLogsEntity m
                        where m.date between :from and :to
                        order by m.date, m.driverId, m.id
                        """, MileageLogsEntity.class)
                .setParameter("from", from).setParameter("to", to)
                .getResultList();
    }

    public List<DriverLeaveRequestsEntity> approvedLeaves(LocalDate from, LocalDate to) {
        return entityManager.createQuery("select l from DriverLeaveRequestsEntity l where l.workDate between :from and :to and l.status = :approved", DriverLeaveRequestsEntity.class)
                .setParameter("from", from).setParameter("to", to).setParameter("approved", LeaveRequestStatus.APPROVED).getResultList();
    }

    public List<RouteLegMileagesEntity> routeLegMileages(List<Long> routeIds) {
        if (routeIds.isEmpty()) {
            return List.of();
        }
        List<RouteLegMileagesEntity> legs = new ArrayList<>();
        for (int i = 0; i < routeIds.size(); i += ID_BATCH_SIZE) {
            legs.addAll(entityManager.createQuery("""
                            select leg from RouteLegMileagesEntity leg
                            where leg.routeId in :routeIds
                            order by leg.routeId, leg.sequence
                            """, RouteLegMileagesEntity.class)
                    .setParameter("routeIds", routeIds.subList(i, Math.min(i + ID_BATCH_SIZE, routeIds.size())))
                    .getResultList());
        }
        return legs;
    }

    public List<ExceptionCasesEntity> exceptions(LocalDate from, LocalDate to) {
        return entityManager.createQuery("""
                        select e from ExceptionCasesEntity e
                        where e.createdAt >= :start and e.createdAt < :end
                        order by e.createdAt desc, e.id desc
                        """, ExceptionCasesEntity.class)
                .setParameter("start", from.atStartOfDay())
                .setParameter("end", to.plusDays(1).atStartOfDay())
                .getResultList();
    }

    public List<DeliveryRecordsEntity> deliveriesForOrders(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        List<DeliveryRecordsEntity> records = new ArrayList<>();
        for (int i = 0; i < orderIds.size(); i += ID_BATCH_SIZE) {
            records.addAll(entityManager.createQuery("""
                            select d from DeliveryRecordsEntity d where d.orderId in :orderIds
                            order by d.orderId, d.id
                            """, DeliveryRecordsEntity.class)
                    .setParameter("orderIds", orderIds.subList(i, Math.min(i + ID_BATCH_SIZE, orderIds.size())))
                    .getResultList());
        }
        return records;
    }
}
