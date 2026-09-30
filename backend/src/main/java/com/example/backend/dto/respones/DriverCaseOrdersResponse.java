package com.example.backend.dto.respones;

import com.example.backend.constants.OrderStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * 結案視窗用：案件路線上還沒結束的單。
 *
 * <p>mustResolveAll＝true 代表路線日期已過，這些單要全部改期補送才能結案；
 * 不然那天在看板日期列會一直顯示「未結案」。</p>
 */
public class DriverCaseOrdersResponse {

    /** 案件沒有路線（上班前回報）時是 null，orders 是空的 */
    private LocalDate routeDate;
    private boolean mustResolveAll;
    private List<Item> orders;

    public LocalDate getRouteDate() {
        return routeDate;
    }

    public void setRouteDate(LocalDate routeDate) {
        this.routeDate = routeDate;
    }

    public boolean isMustResolveAll() {
        return mustResolveAll;
    }

    public void setMustResolveAll(boolean mustResolveAll) {
        this.mustResolveAll = mustResolveAll;
    }

    public List<Item> getOrders() {
        return orders;
    }

    public void setOrders(List<Item> orders) {
        this.orders = orders;
    }

    public static class Item {
        private Long id;
        private String orderNumber;
        private String storeName;
        private OrderStatus status;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getOrderNumber() {
            return orderNumber;
        }

        public void setOrderNumber(String orderNumber) {
            this.orderNumber = orderNumber;
        }

        public String getStoreName() {
            return storeName;
        }

        public void setStoreName(String storeName) {
            this.storeName = storeName;
        }

        public OrderStatus getStatus() {
            return status;
        }

        public void setStatus(OrderStatus status) {
            this.status = status;
        }
    }
}
