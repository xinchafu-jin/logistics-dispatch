create table admin_users
(
    id                    bigint auto_increment
        primary key,
    account               varchar(60)  not null,
    password              varchar(100) not null,
    name                  varchar(60)  not null,
    phone                 varchar(30)  null,
    ai_api_key_encrypted  varchar(512) null,
    ai_api_key_last4      varchar(4)   null,
    ai_api_key_updated_at datetime(6)  null,
    constraint uk_admin_users_account
        unique (account)
);

create table delivery_records
(
    id                  bigint auto_increment
        primary key,
    order_id            bigint       not null,
    arrived_at          datetime(6)  null,
    delivered_at        datetime(6)  null,
    delivered_box_count int          null,
    lat                 double       null,
    lng                 double       null,
    photo_url           varchar(500) null,
    notes               varchar(500) null,
    no_signature        bit          not null
);

create table dispatch_templates
(
    id         bigint auto_increment
        primary key,
    name       varchar(100) not null,
    notes      varchar(500) null,
    created_at datetime(6)  not null,
    updated_at datetime(6)  not null,
    constraint uk_dispatch_templates_name
        unique (name)
);

create table distance_matrix_cache
(
    id         bigint auto_increment
        primary key,
    from_type  enum ('STORE', 'WAREHOUSE') not null,
    from_id    bigint                      not null,
    to_type    enum ('STORE', 'WAREHOUSE') not null,
    to_id      bigint                      not null,
    distance   int                         not null,
    duration   int                         not null,
    updated_at datetime(6)                 not null,
    constraint uk_matrix_from_to
        unique (from_type, from_id, to_type, to_id)
);

create table drivers
(
    id                   bigint auto_increment
        primary key,
    account              varchar(60)  not null,
    password             varchar(100) null,
    name                 varchar(50)  not null,
    phone                varchar(30)  null,
    work_start           time         not null,
    work_end             time         not null,
    rest_duration        int          not null,
    max_overtime_minutes int          null,
    is_active            bit          not null,
    constraint uk_drivers_account
        unique (account)
);

create table exception_cases
(
    id          bigint auto_increment
        primary key,
    order_id    bigint                                                  null,
    type        enum ('DRIVER_REPORT', 'NO_SIGNATURE', 'PHONE_HANDLED') not null,
    description varchar(1000)                                           null,
    created_at  datetime(6)                                             not null,
    handled_by  varchar(50)                                             null,
    handled_at  datetime(6)                                             null,
    resolution  varchar(1000)                                           null,
    status      enum ('CLOSED', 'OPEN')                                 not null
);

create table gps_pings
(
    id        bigint auto_increment
        primary key,
    driver_id bigint      not null,
    lat       double      not null,
    lng       double      not null,
    timestamp datetime(6) not null
);

create index idx_gps_driver_time
    on gps_pings (driver_id, timestamp);

create table mileage_logs
(
    id             bigint auto_increment
        primary key,
    driver_id      bigint      not null,
    date           date        not null,
    start_odometer int         null,
    end_odometer   int         null,
    start_time     datetime(6) null,
    end_time       datetime(6) null
);

create table stores
(
    id              bigint auto_increment
        primary key,
    store_code      varchar(20)                  not null,
    name            varchar(100)                 not null,
    address         varchar(255)                 null,
    lat             double                       not null,
    lng             double                       not null,
    contact_name    varchar(50)                  null,
    phone           varchar(30)                  null,
    receiving_start time                         not null,
    receiving_end   time                         not null,
    notes           varchar(500)                 null,
    status          enum ('ACTIVE', 'SUSPENDED') not null,
    constraint uk_stores_store_code
        unique (store_code)
);

create table warehouses
(
    id             bigint auto_increment
        primary key,
    warehouse_code varchar(20)  not null,
    name           varchar(100) not null,
    address        varchar(255) null,
    lat            double       not null,
    lng            double       not null,
    phone          varchar(30)  null,
    is_active      bit          not null,
    constraint uk_warehouses_code
        unique (warehouse_code)
);

create table vehicles
(
    id               bigint auto_increment
        primary key,
    warehouse_id     bigint                                       not null,
    plate_number     varchar(20)                                  not null,
    vehicle_type     varchar(50)                                  null,
    capacity         int                                          not null,
    fuel_consumption double                                       null,
    status           enum ('AVAILABLE', 'MAINTENANCE', 'RETIRED') not null,
    constraint uk_vehicles_plate_number
        unique (plate_number),
    constraint fk_vehicles_warehouse
        foreign key (warehouse_id) references warehouses (id)
);

create table routes
(
    id                     bigint auto_increment
        primary key,
    date                   date                        not null,
    warehouse_id           bigint                      not null,
    vehicle_id             bigint                      not null,
    driver_id              bigint                      null,
    template_id            bigint                      null,
    total_distance         double                      null,
    estimated_fuel_cost    double                      null,
    estimated_work_minutes int                         null,
    load_rate              double                      null,
    status                 enum ('DRAFT', 'PUBLISHED') not null,
    version                int                         not null,
    constraint uk_routes_date_driver
        unique (date, driver_id),
    constraint uk_routes_date_vehicle
        unique (date, vehicle_id),
    constraint fk_routes_driver
        foreign key (driver_id) references drivers (id),
    constraint fk_routes_template
        foreign key (template_id) references dispatch_templates (id)
            on delete set null,
    constraint fk_routes_vehicle
        foreign key (vehicle_id) references vehicles (id),
    constraint fk_routes_warehouse
        foreign key (warehouse_id) references warehouses (id)
);

create table orders
(
    id                  bigint auto_increment
        primary key,
    order_number        varchar(30)                                                                              not null,
    store_id            bigint                                                                                   not null,
    warehouse_id        bigint                                                                                   not null,
    source_vendor       varchar(100)                                                                             null,
    item_description    varchar(255)                                                                             null,
    box_count           int                                                                                      not null,
    notes               varchar(500)                                                                             null,
    delivery_date       date                                                                                     not null,
    status              enum ('PENDING_CONFIRM', 'CONFIRMED', 'IN_DELIVERY', 'COMPLETED', 'CANCELLED', 'FAILED') not null,
    route_id            bigint                                                                                   null,
    assigned_vehicle_id bigint                                                                                   null,
    assigned_driver_id  bigint                                                                                   null,
    sequence            int                                                                                      null,
    created_at          datetime(6)                                                                              not null,
    updated_at          datetime(6)                                                                              not null,
    constraint uk_orders_order_number
        unique (order_number),
    constraint fk_orders_driver
        foreign key (assigned_driver_id) references drivers (id),
    constraint fk_orders_route
        foreign key (route_id) references routes (id),
    constraint fk_orders_store
        foreign key (store_id) references stores (id),
    constraint fk_orders_vehicle
        foreign key (assigned_vehicle_id) references vehicles (id),
    constraint fk_orders_warehouse
        foreign key (warehouse_id) references warehouses (id)
);

create index idx_orders_date_status_wh
    on orders (delivery_date, status, warehouse_id);

create index idx_orders_date_wh_route
    on orders (delivery_date, warehouse_id, route_id);

create index idx_orders_route_seq
    on orders (route_id, sequence);

create index idx_routes_date_warehouse
    on routes (date, warehouse_id);

create table template_routes
(
    id           bigint auto_increment
        primary key,
    template_id  bigint not null,
    warehouse_id bigint not null,
    vehicle_id   bigint not null,
    constraint uk_tpl_routes_tpl_vehicle
        unique (template_id, vehicle_id),
    constraint fk_tpl_routes_template
        foreign key (template_id) references dispatch_templates (id)
            on delete cascade,
    constraint fk_tpl_routes_vehicle
        foreign key (vehicle_id) references vehicles (id),
    constraint fk_tpl_routes_warehouse
        foreign key (warehouse_id) references warehouses (id)
);

create table template_stops
(
    id                bigint auto_increment
        primary key,
    template_route_id bigint not null,
    store_id          bigint not null,
    sequence          int    not null,
    constraint uk_template_stops
        unique (template_route_id, store_id),
    constraint fk_template_stops_route
        foreign key (template_route_id) references template_routes (id)
            on delete cascade,
    constraint fk_template_stops_store
        foreign key (store_id) references stores (id)
);

create index idx_template_stops_seq
    on template_stops (template_route_id, sequence);

