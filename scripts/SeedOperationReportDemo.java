import java.io.Reader;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Local-only, explicitly marked report fixtures. Never changes existing operational records. */
class SeedOperationReportDemo {
    static final String PREFIX = "DEMO-RPT-";
    static final List<String> TABLES = List.of("drivers", "vehicles", "schedule_months", "driver_shifts",
            "attendance_records", "routes", "orders", "mileage_logs", "delivery_records", "exception_cases");

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <backend directory> inspect|seed|cleanup-check|cleanup");
        Properties env = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(args[0]).resolve(".env"))) { env.load(reader); }
        String url = env.getProperty("DB_URL", "");
        if (!url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:|/).*"))
            throw new IllegalStateException("Only local MySQL is allowed; no SQL executed");
        try (Connection db = DriverManager.getConnection(url, env.getProperty("DB_USER"), env.getProperty("DB_PASSWORD"))) {
            switch (args[1]) {
                case "inspect" -> inspect(db);
                case "seed" -> transaction(db, () -> seed(db));
                case "cleanup-check" -> cleanup(db, false);
                case "cleanup" -> transaction(db, () -> cleanup(db, true));
                default -> throw new IllegalArgumentException("Use inspect|seed|cleanup-check|cleanup");
            }
        }
    }

    interface Work { void run() throws Exception; }
    static void transaction(Connection db, Work work) throws Exception {
        db.setAutoCommit(false);
        try { work.run(); db.commit(); System.out.println("Transaction committed."); }
        catch (Exception error) { db.rollback(); throw error; }
    }

    static final String MARKER = "DEMO-RPT: 報表示範資料，非真實營運紀錄";
    static final String[][] PATTERNS = {
        {"N", "N", "L20", "L45", "A", "O60"},
        {"N", "N", "N", "L20", "O30", "N"},
        {"N", "L20", "A", "N", "L45", "A"},
        {"O30", "O60", "N", "N", "O30", "N"},
        {"L20", "L20", "N", "A", "N", "A"},
        {"N", "N", "A", "L20", "N", "O60"}
    };

    static void seed(Connection db) throws Exception {
        if (!ids(db, "SELECT id FROM drivers WHERE account LIKE ?", PREFIX + "%").isEmpty()
                || !ids(db, "SELECT id FROM vehicles WHERE plate_number LIKE ?", PREFIX + "%").isEmpty()
                || !ids(db, "SELECT id FROM orders WHERE order_number LIKE ?", PREFIX + "%").isEmpty())
            throw new IllegalStateException("Marked demo data already exists; nothing overwritten or duplicated.");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Taipei"));
        LocalDate saturday = today.minusDays(1);
        while (saturday.getDayOfWeek() != DayOfWeek.SATURDAY) saturday = saturday.minusDays(1);
        // No-show scheduler scans today and yesterday, so keep fixtures farther in the past.
        if (!saturday.isBefore(today.minusDays(1))) saturday = saturday.minusWeeks(1);
        LocalDate monday = saturday.minusDays(5);
        Map<LocalDate, Long> months = new HashMap<>();
        for (int d = 0; d < 6; d++) {
            LocalDate month = monday.plusDays(d).withDayOfMonth(1);
            List<Long> found = ids(db, "SELECT id FROM schedule_months WHERE schedule_month=? AND status='PUBLISHED'", month);
            if (found.size() != 1) throw new IllegalStateException("Published month missing for " + month + "; no existing roster will be published by this tool.");
            months.put(month, found.getFirst());
        }
        var warehouseIds = ids(db, "SELECT id FROM warehouses ORDER BY id");
        if (warehouseIds.size() != PATTERNS.length) throw new IllegalStateException("This fixture expects exactly six existing warehouses.");
        var storeIds = ids(db, "SELECT id FROM stores ORDER BY id");
        if (storeIds.isEmpty()) throw new IllegalStateException("Existing stores required; no customer master changes.");
        Map<String, Integer> inserted = new LinkedHashMap<>();
        for (int w = 0; w < warehouseIds.size(); w++) {
            long wh = warehouseIds.get(w);
            long driver = add(db, inserted, "drivers", "account,is_active,name,rest_duration,work_start,work_end,warehouse_id",
                    PREFIX + "DRV" + wh, false, "[示範] 倉" + wh + "司機", 60, LocalTime.of(8, 0), LocalTime.of(17, 0), wh);
            long vehicle = add(db, inserted, "vehicles", "plate_number,status,vehicle_type,capacity,fuel_consumption,warehouse_id,minor_maintenance_interval_km,major_maintenance_interval_km,retirement_km,current_odometer_km,cumulative_mileage_km,last_minor_maintenance_km,last_major_maintenance_km",
                    PREFIX + "CAR" + wh, "RETIRED", "[示範] 小貨車", 180, 10.0, wh, 3000, 20000, 500000, 10000, 0.0, 10000, 10000);
            int odometer = 10000;
            for (int d = 0; d < 6; d++) {
                LocalDate date = monday.plusDays(d);
                String pattern = PATTERNS[w][d];
                long shift = add(db, inserted, "driver_shifts", "schedule_month_id,driver_id,work_date,shift_type,work_start,work_end,change_reason,last_modified_at,version",
                        months.get(date.withDayOfMonth(1)), driver, date, "WORK", LocalTime.of(8, 0), LocalTime.of(17, 0), MARKER, date.atTime(7, 0), 0);
                if (pattern.equals("A")) continue;
                int late = pattern.startsWith("L") ? Integer.parseInt(pattern.substring(1)) : 0;
                int overtime = pattern.startsWith("O") ? Integer.parseInt(pattern.substring(1)) : 0;
                boolean open = (w == 2 && d == 3) || (w == 4 && d == 4);
                LocalDateTime in = date.atTime(8, 0).plusMinutes(late);
                LocalDateTime out = open ? null : date.atTime(17, 0).plusMinutes(overtime);
                int total = open ? 0 : Math.toIntExact(Duration.between(in, out).toMinutes()) - 60;
                add(db, inserted, "attendance_records", "driver_shift_id,driver_id,work_date,clock_in_at,clock_out_at,break_used,break_started_at,break_ends_at,regular_work_minutes,overtime_minutes,total_work_minutes,status,punctuality_status,late_minutes,version",
                        shift, driver, date, in, out, true, date.atTime(12, 0), date.atTime(13, 0), Math.max(0, total - overtime), open ? 0 : overtime, total, open ? "WORKING" : "CLOCKED_OUT", late == 0 ? "ON_TIME" : "LATE", late, 0);
                int km = 35 + w * 9 + d * 4;
                long route = add(db, inserted, "routes", "date,driver_id,vehicle_id,warehouse_id,status,version,total_distance,estimated_fuel_cost,estimated_work_minutes,load_rate",
                        date, driver, vehicle, wh, "PUBLISHED", 1, km * 1000.0, km * 3.0, 420, (33.0 + w * 2) / 180);
                add(db, inserted, "mileage_logs", "date,driver_id,route_id,vehicle_id,start_time,end_time,start_odometer,end_odometer,gps_distance_km,gps_distance_status,mileage_settled_at",
                        date, driver, route, vehicle, date.atTime(8, 30), open ? null : date.atTime(16, 30), odometer,
                        open ? null : odometer + km, open ? null : (double) km, open ? null : "COMPLETE", open ? null : date.atTime(16, 35));
                if (!open) odometer += km;
                for (int n = 1; n <= 2; n++) {
                    String status = open && n == 2 ? "IN_DELIVERY"
                            : n == 2 && d == w % 6 ? (w % 2 == 0 ? "FAILED" : "NO_SIGNATURE") : "COMPLETED";
                    long store = storeIds.get((w + n - 1) % storeIds.size());
                    int boxes = 15 + w + n;
                    long order = order(db, inserted, PREFIX + wh + "-" + date.toString().replace("-", "") + "-" + n,
                            store, wh, date, status, boxes, route, driver, vehicle, n);
                    if (status.equals("IN_DELIVERY")) continue;
                    long delivery = add(db, inserted, "delivery_records", "order_id,arrived_at,delivered_at,handled_at,expected_box_count,delivered_box_count,shortage_box_count,damaged_box_count,replacement_required_box_count,no_signature,notes",
                            order, date.atTime(10 + n, 0), status.equals("COMPLETED") ? date.atTime(10 + n, 10) : null,
                            date.atTime(10 + n, 10), boxes, status.equals("COMPLETED") ? boxes : 0, 0, 0, 0, status.equals("NO_SIGNATURE"), MARKER);
                    if (!status.equals("COMPLETED")) {
                        boolean closed = w % 3 == 0;
                        add(db, inserted, "exception_cases", "order_id,delivery_record_id,type,description,created_at,status,handled_by,handled_at,resolution",
                                order, delivery, status.equals("NO_SIGNATURE") ? "NO_SIGNATURE" : "DRIVER_REPORT", MARKER + "；測試未簽收／配送失敗",
                                date.atTime(10 + n, 15), closed ? "CLOSED" : "OPEN", closed ? "DEMO-RPT" : null,
                                closed ? date.atTime(16, 0) : null, closed ? MARKER + "；示範已查核結案" : null);
                    }
                }
            }
            order(db, inserted, PREFIX + wh + "-PENDING", storeIds.get(w % storeIds.size()), wh, saturday,
                    "CONFIRMED", 8, null, null, null, null);
            try (PreparedStatement update = db.prepareStatement("UPDATE vehicles SET current_odometer_km=?,cumulative_mileage_km=? WHERE id=? AND plate_number=?")) {
                bind(update, odometer, odometer - 10000, vehicle, PREFIX + "CAR" + wh);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Demo vehicle mismatch");
            }
        }
        System.out.println("Demo dates " + monday + ".." + saturday + " (Mon-Sat; no current/future day changes)");
        System.out.println("New rows " + inserted);
        System.out.println("Demo-only expected: attendance 30/36=83.333%; on-time 22/30=73.333%; overtime 6/28=21.429%, 270 minutes; returned 28/30=93.333%.");
        System.out.println("Existing records unchanged. Drivers disabled, no password; vehicles retired to prevent real dispatch.");
    }

    static long order(Connection db, Map<String, Integer> counts, String number, long store, long warehouse,
            LocalDate day, String status, int boxes, Long route, Long driver, Long vehicle, Integer sequence) throws SQLException {
        return add(db, counts, "orders", "order_number,store_id,warehouse_id,delivery_date,status,box_count,source_vendor,item_description,notes,order_type,route_id,assigned_driver_id,assigned_vehicle_id,sequence,loaded_at,created_at,updated_at",
                number, store, warehouse, day, status, boxes, MARKER, MARKER, MARKER, "NORMAL", route, driver, vehicle,
                sequence, route == null ? null : day.atTime(8, 20), day.atTime(7, 0), day.atTime(16, 0));
    }

    static long add(Connection db, Map<String, Integer> counts, String table, String columns, Object... values) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(values.length, "?"));
        try (PreparedStatement insert = db.prepareStatement("INSERT INTO `" + table + "` (" + columns + ") VALUES (" + placeholders + ")", Statement.RETURN_GENERATED_KEYS)) {
            bind(insert, values);
            if (insert.executeUpdate() != 1) throw new SQLException("Insert mismatch: " + table);
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Missing generated ID: " + table);
                counts.merge(table, 1, Integer::sum);
                return keys.getLong(1);
            }
        }
    }

    static void bind(PreparedStatement query, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) query.setObject(i + 1, values[i]);
    }
    static List<Long> ids(Connection db, String sql, Object... values) throws SQLException {
        try (PreparedStatement query = db.prepareStatement(sql)) {
            bind(query, values);
            try (ResultSet rows = query.executeQuery()) {
                List<Long> result = new ArrayList<>();
                while (rows.next()) result.add(rows.getLong(1));
                return result;
            }
        }
    }
    static String numbers(List<Long> ids) { return ids.isEmpty() ? "NULL" : String.join(",", ids.stream().map(String::valueOf).toList()); }

    static void cleanup(Connection db, boolean remove) throws Exception {
        Map<String, List<Long>> targets = new LinkedHashMap<>();
        var drivers = ids(db, "SELECT id FROM drivers WHERE account LIKE ? AND name LIKE '[示範]%' AND is_active=0 AND password IS NULL", PREFIX + "%");
        var vehicles = ids(db, "SELECT id FROM vehicles WHERE plate_number LIKE ? AND vehicle_type LIKE '[示範]%' AND status='RETIRED'", PREFIX + "%");
        var orders = ids(db, "SELECT id FROM orders WHERE order_number LIKE ? AND source_vendor=? AND notes=?", PREFIX + "%", MARKER, MARKER);
        // Stop if any demo identity was changed; never erase something repurposed as real operational data.
        if (drivers.size() != ids(db, "SELECT id FROM drivers WHERE account LIKE ?", PREFIX + "%").size()
                || vehicles.size() != ids(db, "SELECT id FROM vehicles WHERE plate_number LIKE ?", PREFIX + "%").size()
                || orders.size() != ids(db, "SELECT id FROM orders WHERE order_number LIKE ?", PREFIX + "%").size())
            throw new IllegalStateException("A marked identity has changed; cleanup aborted for manual review.");
        var shifts = ids(db, "SELECT id FROM driver_shifts WHERE driver_id IN (" + numbers(drivers) + ") AND change_reason=?", MARKER);
        var routes = ids(db, "SELECT id FROM routes WHERE driver_id IN (" + numbers(drivers) + ") AND vehicle_id IN (" + numbers(vehicles) + ")");
        var deliveries = ids(db, "SELECT id FROM delivery_records WHERE order_id IN (" + numbers(orders) + ") AND notes=?", MARKER);
        // Only untouched automatic no-show records owned by these fake shifts; any reviewed/manual request aborts.
        var noShows = ids(db, "SELECT id FROM driver_leave_requests WHERE driver_id IN (" + numbers(drivers)
                + ") AND driver_shift_id IN (" + numbers(shifts) + ") AND request_mode='SYSTEM_NO_SHOW' AND submission_source='SYSTEM'"
                + " AND status='PENDING' AND reviewed_at IS NULL AND decision_reason IS NULL AND evidence_photo_url IS NULL");
        targets.put("driver_leave_request_events", ids(db, "SELECT id FROM driver_leave_request_events WHERE driver_id IN (" + numbers(drivers)
                + ") AND leave_request_id IN (" + numbers(noShows) + ") AND event_type='AUTO_NO_SHOW_CREATED' AND actor_type='SYSTEM'"));
        targets.put("driver_leave_requests", noShows);
        targets.put("exception_cases", ids(db, "SELECT id FROM exception_cases WHERE order_id IN (" + numbers(orders) + ") AND description LIKE ?", MARKER + "%"));
        targets.put("delivery_records", deliveries);
        targets.put("orders", orders);
        targets.put("mileage_logs", ids(db, "SELECT id FROM mileage_logs WHERE driver_id IN (" + numbers(drivers) + ") AND route_id IN (" + numbers(routes) + ") AND vehicle_id IN (" + numbers(vehicles) + ")"));
        targets.put("routes", routes);
        targets.put("attendance_records", ids(db, "SELECT id FROM attendance_records WHERE driver_id IN (" + numbers(drivers) + ") AND driver_shift_id IN (" + numbers(shifts) + ")"));
        targets.put("driver_shifts", shifts);
        targets.put("vehicles", vehicles);
        targets.put("drivers", drivers);
        guardReferences(db, targets);
        if (!remove) {
            for (var entry : targets.entrySet()) System.out.println("Verified removable demo " + entry.getKey() + ": " + entry.getValue().size());
            System.out.println("Read-only cleanup check passed; nothing removed.");
            return;
        }
        for (var entry : targets.entrySet()) {
            try (Statement delete = db.createStatement()) {
                int removed = delete.executeUpdate("DELETE FROM `" + entry.getKey() + "` WHERE id IN (" + numbers(entry.getValue()) + ")");
                if (removed != entry.getValue().size()) throw new IllegalStateException("Cleanup target count changed");
                System.out.println("Removed only marked demo " + entry.getKey() + ": " + removed);
            }
        }
    }

    static void guardReferences(Connection db, Map<String, List<Long>> targets) throws SQLException {
        Map<String, String> references = Map.ofEntries(Map.entry("driver_id", "drivers"), Map.entry("assigned_driver_id", "drivers"),
                Map.entry("vehicle_id", "vehicles"), Map.entry("assigned_vehicle_id", "vehicles"), Map.entry("driver_shift_id", "driver_shifts"),
                Map.entry("route_id", "routes"), Map.entry("order_id", "orders"), Map.entry("parent_order_id", "orders"),
                Map.entry("follow_up_order_id", "orders"), Map.entry("delivery_record_id", "delivery_records"),
                Map.entry("leave_request_id", "driver_leave_requests"), Map.entry("covered_leave_request_id", "driver_leave_requests"));
        Map<String, Set<String>> columns = new LinkedHashMap<>();
        try (ResultSet rows = db.getMetaData().getColumns(db.getCatalog(), null, "%", "%")) {
            while (rows.next()) columns.computeIfAbsent(rows.getString("TABLE_NAME"), ignored -> new HashSet<>()).add(rows.getString("COLUMN_NAME"));
        }
        for (var table : columns.entrySet()) {
            // Only table names returned by trusted JDBC metadata, quoted to prevent identifier ambiguity.
            for (var reference : references.entrySet()) {
                if (!table.getValue().contains(reference.getKey())) continue;
                String allowed = targets.containsKey(table.getKey()) && table.getValue().contains("id")
                        ? " AND id NOT IN (" + numbers(targets.get(table.getKey())) + ")" : "";
                String sql = "SELECT COUNT(*) FROM `" + table.getKey().replace("`", "``") + "` WHERE `" + reference.getKey()
                        + "` IN (" + numbers(targets.get(reference.getValue())) + ")" + allowed;
                try (Statement query = db.createStatement(); ResultSet rows = query.executeQuery(sql)) {
                    rows.next();
                    if (rows.getLong(1) > 0) throw new IllegalStateException("Non-demo dependency in " + table.getKey() + ". Cleanup aborted; no rows removed.");
                }
            }
        }
    }

    static void inspect(Connection db) throws SQLException {
        for (String table : TABLES) {
            try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                if (rows.next()) System.out.println(rows.getString(2));
            }
        }
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT schedule_month,status FROM schedule_months ORDER BY schedule_month")) {
            while (rows.next()) System.out.println("Month " + rows.getDate(1) + " " + rows.getString(2));
        }
        // Aggregate only, no personal identifiers or credentials.
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT clock_out_at IS NULL AS open_record,COUNT(*),SUM(overtime_minutes),MIN(work_date),MAX(work_date) "
                        + "FROM attendance_records GROUP BY clock_out_at IS NULL")) {
            while (rows.next()) System.out.printf("Attendance open=%s count=%d storedOvertime=%d dates=%s..%s%n",
                    rows.getBoolean(1), rows.getInt(2), rows.getLong(3), rows.getDate(4), rows.getDate(5));
        }
    }
}
