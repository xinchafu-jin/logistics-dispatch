package com.example.backend.dao;

import com.example.backend.constants.OrderStatus;
import com.example.backend.entity.OrdersEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrdersDAO extends JpaRepository<OrdersEntity, Long> {

    /** 鎖住交貨流程中的訂單，讓抵達、交貨與無人簽收依序執行。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select orders from OrdersEntity orders where orders.id = :id")
    Optional<OrdersEntity> findForUpdate(@Param("id") Long id);

    /**
     * 檢查訂單編號是否已存在
     * 用於 Service 層新增/修改時的唯一值防呆
     */
    boolean existsByOrderNumber(String orderNumber);

    /**
     * 排程用：撈某天、某狀態、且尚未排入路線（route_id IS NULL）的訂單。
     * 取消 SCHEDULED 狀態後，排過的單一樣是 CONFIRMED，只能靠 route_id 判斷排了沒，
     * 因此排車撈候選單時要同時篩狀態與 route_id，避免把已排的單重撈回來重排。
     */
    List<OrdersEntity> findByDeliveryDateAndStatusAndWarehouseIdAndRouteIdIsNull(
            LocalDate deliveryDate, OrderStatus status, Long warehouseId);

    List<OrdersEntity> findByRouteIdOrderBySequence(Long routeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select orders from OrdersEntity orders where orders.routeId = :routeId order by orders.id")
    List<OrdersEntity> findByRouteIdForUpdate(@Param("routeId") Long routeId);

    List<OrdersEntity> findByDeliveryDateAndWarehouseIdAndRouteIdIsNull(LocalDate date, Long warehouseId);

    /**
     * 拖曳改派用：撈某天某倉的全部訂單，不分狀態、不管有沒有排進路線。
     * 用來驗證前端送來的 orderId 確實屬於這天這個倉。
     */
    List<OrdersEntity> findByDeliveryDateAndWarehouseId(LocalDate deliveryDate, Long warehouseId);

    List<OrdersEntity> findByRouteIdIn(List<Long> routeIds);

    //撈當天、指定倉庫、這些門市、已確認且尚未排入路線之訂單。
    List<OrdersEntity> findByDeliveryDateAndStatusAndWarehouseIdAndStoreIdInAndRouteIdIsNull
    (
            LocalDate deliveryDate,
            OrderStatus status,
            Long warehouseId,
            Collection<Long> storeIds
    );
}
