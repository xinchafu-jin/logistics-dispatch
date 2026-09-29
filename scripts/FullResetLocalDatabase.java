import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Local database reset. Seed rows are synthetic, even though user-visible text is natural. */
class FullResetLocalDatabase {
    static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    static final Set<String> KEEP = Set.of("admin_users", "flyway_schema_history", "major_legacy_flyway_history_v2");
    static final Set<String> SEEDED = Set.of("warehouses", "stores", "drivers", "vehicles", "schedule_months",
            "driver_shifts", "attendance_records", "driver_leave_requests", "driver_leave_request_events",
            "fuel_price_history", "vehicle_maintenance_records", "vehicle_maintenance_settings", "gps_pings",
            "routes", "orders", "order_items", "delivery_records", "exception_cases", "mileage_logs",
            "route_vehicle_segments");
    static final String[] NAMES = {"陳柏宇", "林志豪", "王俊傑", "李建宏", "張家銘"};
    static final String[] STORE_NAMES = {"左營博愛店", "楠梓德民店", "鼓山美術館店", "三民建工店",
            "苓雅四維店", "鳳山青年店", "前鎮瑞隆店", "小港漢民店"};
    static final String[] STORE_ADDRESSES = {"高雄市左營區博愛三路100號", "高雄市楠梓區德民路300號",
            "高雄市鼓山區美術東二路80號", "高雄市三民區建工路450號", "高雄市苓雅區四維二路120號",
            "高雄市鳳山區青年路二段200號", "高雄市前鎮區瑞隆路350號", "高雄市小港區漢民路260號"};
    static final double[][] STORE_POINTS = {{22.6824,120.3042},{22.7264,120.2941},{22.6568,120.2868},
            {22.6488,120.3275},{22.6239,120.3153},{22.6333,120.3501},{22.6051,120.3278},
            {22.5667,120.3521}};
    record Login(String account, String password) {}
    record RouteSpec(long id, LocalDate day, long driver, long vehicle, long warehouse,
                     int firstStore, int secondStore, int km, int startOdometer) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("plan", "apply", "history-plan", "history-apply").contains(args[1]))
            throw new IllegalArgumentException("Usage: backend-directory plan|apply|history-plan|history-apply backup-directory");
        Path backend = Path.of(args[0]).toAbsolutePath().normalize();
        Path backupDir = Path.of(args[2]).toAbsolutePath().normalize();
        Path root = backend.getParent().getParent().resolve(backend.getParent().getFileName() + "-db-backups");
        if (!backupDir.startsWith(root) || backupDir.equals(root))
            throw new IllegalArgumentException("Use a dedicated backup directory outside the repository");
        Properties env = new Properties();
        try (Reader reader = Files.newBufferedReader(backend.resolve(".env"))) { env.load(reader); }
        for (String key : List.of("DB_URL", "DB_USER", "DB_PASSWORD")) {
            String value = System.getenv(key);
            if (value != null) env.setProperty(key, value);
        }
        String url = unquote(env.getProperty("DB_URL", ""));
        if (!url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/logistics(\\?.*)?"))
            throw new IllegalStateException("Only the configured local logistics database is permitted");
        try (Connection db = DriverManager.getConnection(url, unquote(env.getProperty("DB_USER")),
                unquote(env.getProperty("DB_PASSWORD")))) {
            LocalDate today = LocalDate.now(ZONE);
            if (!LocalDate.of(2026, 9, 29).equals(today))
                throw new IllegalStateException("Fixture dates are designed for 2026-09-29 only");
            if (args[1].startsWith("history-")) {
                historyMode(db, env, backupDir, args[1]);
                return;
            }
            List<String> tables = resetTables(db);
            List<Login> logins = driverLogins(db);
            inspect(db, tables, logins);
            if (args[1].equals("plan")) return;
            Map<String, Long> before = inventory(db, tables);
            backup(env, backupDir, "logistics-before-full-reset.sql");
            if (!inventory(db, tables).equals(before))
                throw new IllegalStateException("Data changed during backup; no reset performed");
            db.setAutoCommit(false);
            boolean fkDisabled = false;
            try {
                exec(db, "SET FOREIGN_KEY_CHECKS=0"); fkDisabled = true;
                for (String table : tables) exec(db, "DELETE FROM `" + table + "`");
                exec(db, "SET FOREIGN_KEY_CHECKS=1"); fkDisabled = false;
                seed(db, logins, today);
                verify(db, tables, today);
                db.commit();
            } catch (Exception error) {
                try { if (fkDisabled) exec(db, "SET FOREIGN_KEY_CHECKS=1"); } finally { db.rollback(); }
                throw error;
            }
            resetSequences(db, tables);
            verify(db, tables, today);
            System.out.println("COMMITTED full local business-data reset; all old business rows remain in the SQL backup.");
            System.out.println("AFTER warehouses=" + count(db,"warehouses") + " stores=" + count(db,"stores")
                    + " drivers=" + count(db,"drivers") + " vehicles=" + count(db,"vehicles")
                    + " orders=" + count(db,"orders") + " exception_open="
                    + scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='OPEN'")
                    + " exception_closed="+scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='CLOSED'"));
        }
    }

    static void historyMode(Connection db, Properties env, Path backupDir, String mode) throws Exception {
        Map<String,Long> before=Map.of("orders",12L,"order_items",36L,"routes",4L,
                "delivery_records",6L,"exception_cases",1L,"mileage_logs",3L,
                "route_vehicle_segments",4L,"gps_pings",18L);
        for (var entry:before.entrySet()) {
            if (count(db,entry.getKey())!=entry.getValue())
                throw new IllegalStateException("History seed base changed: "+entry.getKey());
        }
        if (scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='OPEN'")!=1
                || scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='CLOSED'")!=0)
            throw new IllegalStateException("Expected one pending and no closed anomalies before history seed");
        System.out.println("HISTORY PLAN keep one pending case; add 12 closed cases (6 no-signature, 6 loading-mismatch)");
        System.out.println("             each linked to source, follow-up, product counts and resolution");
        if (mode.equals("history-plan")) return;
        backup(env,backupDir,"logistics-before-history.sql");
        for (var entry:before.entrySet()) {
            if (count(db,entry.getKey())!=entry.getValue())
                throw new IllegalStateException("History base changed during backup; no rows added");
        }
        db.setAutoCommit(false);
        try {
            seedClosedExceptionHistory(db);
            verifyHistory(db);
            db.commit();
        } catch (Exception error) {
            db.rollback();
            throw error;
        }
        resetSequences(db,List.of("orders","order_items","routes","delivery_records","exception_cases",
                "mileage_logs","route_vehicle_segments","gps_pings"));
        verifyHistory(db);
        System.out.println("COMMITTED anomaly history: open=1 closed=12 total=13");
    }

    static void verifyHistory(Connection db) throws SQLException {
        Map<String,Long> after=Map.of("orders",36L,"order_items",108L,"routes",28L,
                "delivery_records",24L,"exception_cases",13L,"mileage_logs",21L,
                "route_vehicle_segments",28L,"gps_pings",90L);
        for (var entry:after.entrySet()) {
            if (count(db,entry.getKey())!=entry.getValue())
                throw new IllegalStateException("History count mismatch: "+entry.getKey());
        }
        if (scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='OPEN'")!=1
                || scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='CLOSED' AND type='NO_SIGNATURE'")!=6
                || scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='CLOSED' AND type='LOADING_MISMATCH'")!=6
                || scalar(db,"SELECT COUNT(*) FROM exception_cases e LEFT JOIN orders a ON a.id=e.order_id "
                        + "LEFT JOIN orders b ON b.id=e.follow_up_order_id WHERE e.status='CLOSED' "
                        + "AND (a.id IS NULL OR b.id IS NULL OR b.status<>'COMPLETED')")!=0)
            throw new IllegalStateException("Historical cases are not complete and resolved");
        if (scalar(db,"SELECT COUNT(*) FROM driver_leave_requests WHERE request_mode='SYSTEM_NO_SHOW'")!=0)
            throw new IllegalStateException("Automatic absence clutter appeared");
        verifyForeignKeys(db);
        System.out.println("VERIFIED 12 closed cases, 1 pending case, complete linked history");
    }

    static List<String> resetTables(Connection db) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(
                "SELECT table_name,engine FROM information_schema.tables WHERE table_schema=DATABASE() "
                        + "AND table_type='BASE TABLE' ORDER BY table_name")) {
            while (r.next()) {
                String name = r.getString(1);
                if (!"InnoDB".equalsIgnoreCase(r.getString(2)))
                    throw new IllegalStateException("Nontransactional table found: " + name);
                if (!KEEP.contains(name)) result.add(name);
            }
        }
        if (result.size() != 39 || !result.containsAll(SEEDED))
            throw new IllegalStateException("Schema differs from audited 42-table database");
        return result;
    }

    static List<Login> driverLogins(Connection db) throws SQLException {
        List<Login> logins = new ArrayList<>();
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(
                "SELECT account,password FROM drivers WHERE is_active=1 ORDER BY id")) {
            while (r.next()) logins.add(new Login(r.getString(1), r.getString(2)));
        }
        if (logins.size() != 5 || logins.stream().anyMatch(l -> l.password() == null || l.account() == null))
            throw new IllegalStateException("Expected five usable existing driver logins");
        return logins;
    }

    static void inspect(Connection db, List<String> tables, List<Login> logins) throws SQLException {
        System.out.println("TARGET logistics on localhost; reset business tables=" + tables.size());
        System.out.println("PRESERVE admin login rows=" + count(db,"admin_users")
                + ", carry over driver credentials=" + logins.size() + ", preserve migration histories");
        for (String table : tables) {
            long n = count(db, table);
            if (n > 0) System.out.println("REPLACE " + table + "=" + n);
        }
        System.out.println("SEED natural-language synthetic records: 2 warehouses, 8 stores, 5 drivers, 4 vehicles,");
        System.out.println("     36 orders, 28 routes, 18 completed deliveries, 12 closed and 1 open exception,");
        System.out.println("     aligned products, roster, attendance, leave, GPS, fuel and maintenance data");
    }

    static Map<String, Long> inventory(Connection db, List<String> tables) throws SQLException {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String table : tables) result.put(table, count(db, table));
        return result;
    }

    static void seed(Connection db, List<Login> logins, LocalDate today) throws SQLException {
        seedMasters(db, logins);
        seedRoster(db, today);
        seedRoutesAndOrders(db, today);
        seedClosedExceptionHistory(db);
    }

    static void seedMasters(Connection db, List<Login> logins) throws SQLException {
        row(db,"warehouses","id,warehouse_code,name,address,lat,lng,phone,is_active",
                1,"KH-ZY","高雄左營倉","高雄市左營區高鐵路150號",22.6877,120.3070,"07-350-1200",true);
        row(db,"warehouses","id,warehouse_code,name,address,lat,lng,phone,is_active",
                2,"KH-FS","高雄鳳山倉","高雄市鳳山區光遠路180號",22.6268,120.3571,"07-740-2300",true);
        for (int i=0;i<STORE_NAMES.length;i++)
            row(db,"stores","id,store_code,name,address,lat,lng,contact_name,receiving_start,receiving_end,status",
                    i+1,"KH"+String.format("%03d",i+1),STORE_NAMES[i],STORE_ADDRESSES[i],
                    STORE_POINTS[i][0],STORE_POINTS[i][1],"門市值班人員",LocalTime.of(9,0),
                    LocalTime.of(17,30),"ACTIVE");
        int[] warehouse = {1,1,1,2,2};
        for (int i=0;i<logins.size();i++)
            row(db,"drivers","id,account,password,name,is_active,warehouse_id,rest_duration,work_start,work_end,max_overtime_minutes",
                    i+1,logins.get(i).account(),logins.get(i).password(),NAMES[i],true,warehouse[i],
                    60,LocalTime.of(8,0),LocalTime.of(17,0),120);
        row(db,"vehicles","id,plate_number,warehouse_id,vehicle_type,capacity,fuel_consumption,fuel_type,status,"
                        + "current_odometer_km,cumulative_mileage_km,last_minor_maintenance_km,last_major_maintenance_km,"
                        + "minor_maintenance_interval_km,major_maintenance_interval_km,retirement_km",
                1,"KAE-2081",1,"3.5噸冷藏貨車",120,8.5,"DIESEL","AVAILABLE",46178,46178.0,45000,40000,5000,20000,500000);
        row(db,"vehicles","id,plate_number,warehouse_id,vehicle_type,capacity,fuel_consumption,fuel_type,status,"
                        + "current_odometer_km,cumulative_mileage_km,last_minor_maintenance_km,last_major_maintenance_km,"
                        + "minor_maintenance_interval_km,major_maintenance_interval_km,retirement_km",
                2,"KAE-2082",1,"3.5噸常溫貨車",120,9.1,"DIESEL","AVAILABLE",32500,32500.0,30000,20000,5000,20000,500000);
        row(db,"vehicles","id,plate_number,warehouse_id,vehicle_type,capacity,fuel_consumption,fuel_type,status,"
                        + "current_odometer_km,cumulative_mileage_km,last_minor_maintenance_km,last_major_maintenance_km,"
                        + "minor_maintenance_interval_km,major_maintenance_interval_km,retirement_km",
                3,"KBF-3101",2,"3.5噸冷藏貨車",120,8.7,"DIESEL","AVAILABLE",32980,32980.0,30000,30000,5000,20000,500000);
        row(db,"vehicles","id,plate_number,warehouse_id,vehicle_type,capacity,fuel_consumption,fuel_type,status,"
                        + "current_odometer_km,cumulative_mileage_km,last_minor_maintenance_km,last_major_maintenance_km,"
                        + "minor_maintenance_interval_km,major_maintenance_interval_km,retirement_km",
                4,"KBF-3102",2,"3.5噸常溫貨車",120,9.0,"DIESEL","AVAILABLE",18200,18200.0,15000,10000,5000,20000,500000);
        row(db,"fuel_price_history","id,fuel_type,price_per_liter,effective_from,source,fetched_at",
                1,"DIESEL",29.1,LocalDateTime.of(2026,9,1,0,0),"車隊油價表",LocalDateTime.of(2026,9,1,8,0));
        row(db,"fuel_price_history","id,fuel_type,price_per_liter,effective_from,source,fetched_at",
                2,"DIESEL",29.4,LocalDateTime.of(2026,9,20,0,0),"車隊油價表",LocalDateTime.of(2026,9,20,8,0));
        row(db,"vehicle_maintenance_settings","id,warning_km",1,500);
        row(db,"vehicle_maintenance_records","id,vehicle_id,type,status,sent_at,sent_odometer_km,"
                        + "completed_at,completed_odometer_km,recorded_by,completed_by",
                1,1,"MINOR","COMPLETED",LocalDateTime.of(2026,8,22,9,0),45000,
                LocalDateTime.of(2026,8,22,16,30),45000,"車隊管理員","維修廠");
        row(db,"vehicle_maintenance_records","id,vehicle_id,type,status,sent_at,sent_odometer_km,"
                        + "completed_at,completed_odometer_km,recorded_by,completed_by",
                2,3,"MAJOR","COMPLETED",LocalDateTime.of(2026,7,18,9,0),30000,
                LocalDateTime.of(2026,7,19,15,0),30000,"車隊管理員","維修廠");
    }

    static void seedRoster(Connection db, LocalDate today) throws SQLException {
        row(db,"schedule_months","id,schedule_month,status,generated_at,published_at,version",
                1,LocalDate.of(2026,9,1),"PUBLISHED",LocalDateTime.of(2026,8,25,10,0),
                LocalDateTime.of(2026,8,26,10,0),1);
        row(db,"schedule_months","id,schedule_month,status,generated_at,version",
                2,LocalDate.of(2026,10,1),"DRAFT",today.atTime(10,0),0);
        long shiftId=1, attendanceId=1, leaveShift=0;
        for (int month=9;month<=10;month++) {
            LocalDate first=LocalDate.of(2026,month,1);
            for (LocalDate day=first;day.getMonthValue()==month;day=day.plusDays(1)) {
                boolean weekend=day.getDayOfWeek().getValue()>=6;
                for (int driver=1;driver<=5;driver++) {
                    boolean leave=driver==5 && day.equals(LocalDate.of(2026,9,24));
                    String shiftType=leave?"LEAVE":weekend?"DAY_OFF":"WORK";
                    row(db,"driver_shifts","id,schedule_month_id,driver_id,work_date,shift_type,work_start,work_end,"
                                    + "overtime_minutes,last_modified_at,version",
                            shiftId,month==9?1:2,driver,day,shiftType,weekend||leave?null:LocalTime.of(8,0),
                            weekend||leave?null:LocalTime.of(17,0),0,first.atTime(8,0),0);
                    if (leave) leaveShift=shiftId;
                    if (month==9 && !day.isAfter(today) && "WORK".equals(shiftType)) {
                        int late=(day.getDayOfMonth()+driver)%9==0?12:0;
                        int overtime=(day.getDayOfMonth()+driver)%7==0?30:0;
                        LocalDateTime in=day.atTime(8,late),out=day.atTime(17,0).plusMinutes(overtime);
                        int total=480-late+overtime;
                        row(db,"attendance_records","id,driver_shift_id,driver_id,work_date,clock_in_at,clock_out_at,"
                                        + "break_used,regular_work_minutes,overtime_minutes,total_work_minutes,status,"
                                        + "punctuality_status,late_minutes,version",
                                attendanceId++,shiftId,driver,day,in,out,true,Math.min(480,total),
                                Math.max(0,total-480),total,"CLOCKED_OUT",late==0?"ON_TIME":"LATE",late,0);
                    }
                    shiftId++;
                }
            }
        }
        if (leaveShift==0) throw new IllegalStateException("Leave shift not generated");
        LocalDate leaveDay=LocalDate.of(2026,9,24);
        row(db,"driver_leave_requests","id,driver_id,driver_shift_id,request_mode,work_date,"
                        + "requested_leave_type,leave_type,full_day,request_reason,status,submission_source,"
                        + "decision_reason,requested_at,reviewed_at,reviewed_by_admin_id,reviewed_by,last_updated_at,version",
                1,5,leaveShift,"PREPLANNED",leaveDay,"PERSONAL","PERSONAL",true,"家庭事務",
                "APPROVED","DRIVER","排班已調整",LocalDateTime.of(2026,9,18,9,0),
                LocalDateTime.of(2026,9,18,11,0),1,"營運主管",LocalDateTime.of(2026,9,18,11,0),1);
        row(db,"driver_leave_request_events","id,leave_request_id,driver_id,event_type,actor_type,"
                        + "actor_id,actor_account,new_status,new_leave_type,reason,occurred_at",
                1,1,5,"SUBMITTED","DRIVER",5,"DRV007","PENDING","PERSONAL","家庭事務",
                LocalDateTime.of(2026,9,18,9,0));
        row(db,"driver_leave_request_events","id,leave_request_id,driver_id,event_type,actor_type,"
                        + "actor_id,actor_account,old_status,new_status,old_leave_type,new_leave_type,reason,occurred_at",
                2,1,5,"APPROVED","ADMIN",1,"admin001","PENDING","APPROVED","PERSONAL","PERSONAL",
                "排班已調整",LocalDateTime.of(2026,9,18,11,0));
    }

    static void seedRoutesAndOrders(Connection db, LocalDate today) throws SQLException {
        List<RouteSpec> historical=List.of(
                new RouteSpec(1,LocalDate.of(2026,9,24),4,3,2,6,7,30,32950),
                new RouteSpec(2,LocalDate.of(2026,9,25),1,1,1,1,2,30,46120),
                new RouteSpec(3,LocalDate.of(2026,9,28),1,1,1,3,4,28,46150));
        int orderId=1,itemId=1,deliveryId=1,mileageId=1,segmentId=1,gpsId=1;
        for (RouteSpec spec:historical) {
            double estimatedLit=round3(spec.km()/8.5),actualLit=round3((spec.km()-1)/8.5);
            row(db,"routes","id,date,driver_id,vehicle_id,warehouse_id,status,version,total_distance,"
                            + "estimated_fuel_cost,estimated_fuel_liters,estimated_work_minutes,load_rate,"
                            + "actual_distance_m,actual_fuel_liters,actual_fuel_cost",
                    spec.id(),spec.day(),spec.driver(),spec.vehicle(),spec.warehouse(),"PUBLISHED",1,
                    spec.km()*1000.0,round2(estimatedLit*29.4),estimatedLit,390,8.0/120.0,
                    (spec.km()-1)*1000,actualLit,round2(actualLit*29.4));
            row(db,"mileage_logs","id,date,driver_id,route_id,vehicle_id,start_time,end_time,start_odometer,"
                            + "end_odometer,actual_distance_km,gps_distance_km,gps_distance_status,mileage_settled_at",
                    mileageId,spec.day(),spec.driver(),spec.id(),spec.vehicle(),spec.day().atTime(8,30),
                    spec.day().atTime(16,30),spec.startOdometer(),spec.startOdometer()+spec.km()-1,
                    spec.km()-1,(double)(spec.km()-1),"COMPLETE",spec.day().atTime(16,35));
            row(db,"route_vehicle_segments","id,route_id,segment_no,vehicle_id,driver_id,source_mileage_log_id,"
                            + "start_time,end_time,start_odometer_km,end_odometer_km,planned_distance_m,actual_distance_m,"
                            + "fuel_type_snapshot,fuel_efficiency_km_per_liter,fuel_price_per_liter_snapshot,"
                            + "estimated_fuel_liters,estimated_fuel_cost,actual_fuel_liters,actual_fuel_cost,"
                            + "calculation_basis,start_reason,end_reason,status",
                    segmentId++,spec.id(),1,spec.vehicle(),spec.driver(),mileageId,spec.day().atTime(8,30),
                    spec.day().atTime(16,30),spec.startOdometer(),spec.startOdometer()+spec.km()-1,
                    spec.km()*1000,(spec.km()-1)*1000,"DIESEL",8.5,29.4,estimatedLit,
                    round2(estimatedLit*29.4),actualLit,round2(actualLit*29.4),"ODOMETER","INITIAL", "COMPLETED","COMPLETED");
            mileageId++;
            for (int sequence=1;sequence<=2;sequence++) {
                int store=sequence==1?spec.firstStore():spec.secondStore();
                LocalDateTime checked=spec.day().atTime(8,10);
                row(db,"orders","id,order_number,store_id,warehouse_id,delivery_date,status,box_count,"
                                + "source_vendor,item_description,order_type,retry_count,route_id,assigned_driver_id,"
                                + "assigned_vehicle_id,sequence,loaded_at,created_at,updated_at",
                        orderId,orderNo(spec.day(),sequence,spec.id()),store,spec.warehouse(),spec.day(),
                        "COMPLETED",4,vendor(orderId),"飲用水、麵包、鮮乳","NORMAL",0,spec.id(),spec.driver(),
                        spec.vehicle(),sequence,checked,spec.day().atTime(7,0),spec.day().atTime(16,0));
                itemId=items(db,itemId,orderId,spec.driver(),checked,false);
                row(db,"delivery_records","id,order_id,route_id,driver_id,vehicle_id,attempt_date,"
                                + "arrived_at,delivered_at,handled_at,expected_box_count,delivered_box_count,"
                                + "shortage_box_count,damaged_box_count,replacement_required_box_count,no_signature,"
                                + "lat,lng,notes",
                        deliveryId++,orderId,spec.id(),spec.driver(),spec.vehicle(),spec.day(),
                        spec.day().atTime(10+sequence,0),spec.day().atTime(10+sequence,12),
                        spec.day().atTime(10+sequence,12),4,4,0,0,0,false,
                        STORE_POINTS[store-1][0],STORE_POINTS[store-1][1],"門市簽收完成");
                orderId++;
            }
            double[] from=spec.warehouse()==1?new double[]{22.6877,120.3070}:new double[]{22.6268,120.3571};
            double[] to=STORE_POINTS[spec.firstStore()-1];
            for (int point=0;point<6;point++) {
                double fraction=(point+1)/7.0;
                row(db,"gps_pings","id,driver_id,lat,lng,timestamp",gpsId++,spec.driver(),
                        from[0]+(to[0]-from[0])*fraction,from[1]+(to[1]-from[1])*fraction,
                        spec.day().atTime(9,0).plusMinutes(point*12));
            }
        }
        row(db,"routes","id,date,driver_id,vehicle_id,warehouse_id,status,version,total_distance,"
                        + "estimated_fuel_cost,estimated_fuel_liters,estimated_work_minutes,load_rate",
                4,today,1,1,1,"PUBLISHED",1,21000.0,72.62,2.47,330,8.0/120.0);
        row(db,"route_vehicle_segments","id,route_id,segment_no,vehicle_id,driver_id,planned_distance_m,"
                        + "fuel_type_snapshot,fuel_efficiency_km_per_liter,fuel_price_per_liter_snapshot,"
                        + "estimated_fuel_liters,estimated_fuel_cost,calculation_basis,start_reason,status",
                4,4,1,1,1,21000,"DIESEL",8.5,29.4,2.47,72.62,"PLANNED_DISTANCE","INITIAL","PLANNED");
        for (int sequence=1;sequence<=2;sequence++) {
            row(db,"orders","id,order_number,store_id,warehouse_id,delivery_date,status,box_count,"
                            + "source_vendor,item_description,order_type,retry_count,route_id,assigned_driver_id,"
                            + "assigned_vehicle_id,sequence,created_at,updated_at",
                    orderId,orderNo(today,sequence,4),sequence,1,today,"CONFIRMED",4,vendor(orderId),
                    "飲用水、麵包、鮮乳","NORMAL",0,4,1,1,sequence,today.atTime(7,0),today.atTime(7,0));
            itemId=items(db,itemId,orderId,1,null,false);orderId++;
        }
        for (int i=0;i<2;i++) {
            int wh=i+1,store=i==0?4:7;
            row(db,"orders","id,order_number,store_id,warehouse_id,delivery_date,status,box_count,"
                            + "source_vendor,item_description,order_type,retry_count,created_at,updated_at",
                    orderId,orderNo(today,i+3,5),store,wh,today,"CONFIRMED",4,vendor(orderId),
                    "飲用水、麵包、鮮乳","NORMAL",0,today.atTime(7,0),today.atTime(7,0));
            itemId=items(db,itemId,orderId,1,null,false);orderId++;
        }
        int failedId=orderId++;
        row(db,"orders","id,order_number,store_id,warehouse_id,delivery_date,status,box_count,"
                        + "source_vendor,item_description,order_type,retry_count,created_at,updated_at",
                failedId,orderNo(today,5,5),3,1,today,"FAILED",4,vendor(failedId),
                "飲用水、麵包、鮮乳","NORMAL",0,today.atTime(7,0),today.atTime(8,20));
        itemId=items(db,itemId,failedId,1,today.atTime(8,15),true);
        int followUpId=orderId;
        row(db,"orders","id,order_number,store_id,warehouse_id,delivery_date,status,box_count,"
                        + "source_vendor,item_description,order_type,parent_order_id,retry_count,created_at,updated_at",
                followUpId,orderNo(today.plusDays(1),1,6),3,1,today.plusDays(1),"PENDING_CONFIRM",4,
                vendor(failedId),"飲用水、麵包、鮮乳","REDELIVERY",failedId,1,
                today.atTime(8,20),today.atTime(8,20));
        items(db,itemId,followUpId,1,null,false);
        row(db,"exception_cases","id,order_id,follow_up_order_id,type,status,description,created_at,review_available_at",
                1,failedId,followUpId,"LOADING_MISMATCH","OPEN",
                "倉庫點交時鮮乳應點 1 箱，實點 0 箱；待主管確認重新排車。",
                today.atTime(8,20),today.atTime(8,20));
    }

    static final class HistoryIds {
        int route=5, order=13, item=37, delivery=7, exception=2, mileage=4, segment=5, gps=19;
    }

    /** Six earlier incident dates, with both supported incident types and completed follow-up orders. */
    static void seedClosedExceptionHistory(Connection db) throws SQLException {
        HistoryIds ids=new HistoryIds();
        LocalDate[] days={LocalDate.of(2026,9,2),LocalDate.of(2026,9,7),LocalDate.of(2026,9,9),
                LocalDate.of(2026,9,14),LocalDate.of(2026,9,16),LocalDate.of(2026,9,21)};
        for (int cycle=0;cycle<days.length;cycle++) {
            LocalDate sourceDay=days[cycle],deliveryDay=sourceDay.plusDays(1);
            for (int kind=0;kind<2;kind++) {
                boolean noSignature=kind==0;
                int driver=noSignature?2:3,vehicle=noSignature?2:1;
                int store=noSignature?cycle%4+1:cycle%4+3;
                int sourceKm=noSignature?20:19,followKm=noSignature?22:24;
                int sourceOdometer=32200+cycle*42,followOdometer=noSignature
                        ?sourceOdometer+sourceKm-1:45800+cycle*24;
                int sourceRoute=ids.route++,followRoute=ids.route++;
                int sourceOrder=ids.order++,followOrder=ids.order++;
                int group=10+cycle*2+kind;
                historyRoute(db,ids,sourceRoute,sourceDay,driver,vehicle,store,sourceKm,
                        noSignature,noSignature?sourceOdometer:0);
                historyRoute(db,ids,followRoute,deliveryDay,driver,vehicle,store,followKm,true,followOdometer);
                LocalDateTime sourceChecked=sourceDay.atTime(8,10);
                row(db,"orders","id,order_number,store_id,warehouse_id,delivery_date,status,box_count,"
                                + "source_vendor,item_description,order_type,retry_count,route_id,assigned_driver_id,"
                                + "assigned_vehicle_id,sequence,loaded_at,created_at,updated_at",
                        sourceOrder,orderNo(sourceDay,1,group),store,1,sourceDay,
                        noSignature?"NO_SIGNATURE":"FAILED",4,vendor(sourceOrder),"飲用水、麵包、鮮乳",
                        "NORMAL",0,sourceRoute,driver,vehicle,1,noSignature?sourceChecked:null,
                        sourceDay.atTime(7,0),noSignature?sourceDay.atTime(12,15):sourceDay.atTime(8,20));
                ids.item=items(db,ids.item,sourceOrder,driver,sourceChecked,!noSignature);
                Integer sourceDelivery=null;
                if (noSignature) {
                    sourceDelivery=ids.delivery++;
                    row(db,"delivery_records","id,order_id,route_id,driver_id,vehicle_id,attempt_date,"
                                    + "arrived_at,handled_at,expected_box_count,delivered_box_count,"
                                    + "shortage_box_count,damaged_box_count,replacement_required_box_count,no_signature,"
                                    + "lat,lng,notes",
                            sourceDelivery,sourceOrder,sourceRoute,driver,vehicle,sourceDay,
                            sourceDay.atTime(12,0),sourceDay.atTime(12,15),4,0,0,0,0,true,
                            STORE_POINTS[store-1][0],STORE_POINTS[store-1][1],"門市無人簽收，安排隔日重送");
                }
                LocalDateTime followChecked=deliveryDay.atTime(8,10);
                row(db,"orders","id,order_number,store_id,warehouse_id,delivery_date,status,box_count,"
                                + "source_vendor,item_description,order_type,parent_order_id,retry_count,route_id,"
                                + "assigned_driver_id,assigned_vehicle_id,sequence,loaded_at,created_at,updated_at",
                        followOrder,orderNo(deliveryDay,2,group),store,1,deliveryDay,"COMPLETED",4,
                        vendor(sourceOrder),"飲用水、麵包、鮮乳","REDELIVERY",sourceOrder,1,followRoute,
                        driver,vehicle,1,followChecked,
                        noSignature?sourceDay.atTime(12,20):sourceDay.atTime(8,25),deliveryDay.atTime(13,0));
                ids.item=items(db,ids.item,followOrder,driver,followChecked,false);
                row(db,"delivery_records","id,order_id,route_id,driver_id,vehicle_id,attempt_date,"
                                + "arrived_at,delivered_at,handled_at,expected_box_count,delivered_box_count,"
                                + "shortage_box_count,damaged_box_count,replacement_required_box_count,no_signature,"
                                + "lat,lng,notes",
                        ids.delivery++,followOrder,followRoute,driver,vehicle,deliveryDay,
                        deliveryDay.atTime(12,0),deliveryDay.atTime(12,12),deliveryDay.atTime(12,12),
                        4,4,0,0,0,false,STORE_POINTS[store-1][0],STORE_POINTS[store-1][1],"重新配送完成簽收");
                LocalDateTime incidentAt=noSignature?sourceDay.atTime(12,15):sourceDay.atTime(8,20);
                LocalDateTime reviewedAt=noSignature?deliveryDay.atTime(6,5):deliveryDay.atTime(7,30);
                row(db,"exception_cases","id,order_id,delivery_record_id,follow_up_order_id,driver_id,"
                                + "route_id,type,status,description,created_at,review_available_at,queued_at,"
                                + "handled_at,handled_by,resolution,accepted_admin_id,accepted_at",
                        ids.exception++,sourceOrder,sourceDelivery,followOrder,driver,sourceRoute,
                        noSignature?"NO_SIGNATURE":"LOADING_MISMATCH","CLOSED",
                        noSignature?"配送抵達門市時無人簽收，隔日重新配送。"
                                :"倉庫點交發現鮮乳短少 1 箱，重新備貨配送。",
                        incidentAt,noSignature?deliveryDay.atTime(6,0):incidentAt,
                        noSignature?deliveryDay.atTime(6,0):deliveryDay.atTime(7,25),
                        reviewedAt,noSignature?"系統自動排車":"營運主管",
                        noSignature?"隔日自動排車，重送後完成簽收":"主管確認重新排車，次日完成配送",
                        noSignature?null:1,noSignature?null:reviewedAt);
            }
        }
        if (ids.route!=29 || ids.order!=37 || ids.item!=109 || ids.delivery!=25
                || ids.exception!=14 || ids.mileage!=22 || ids.segment!=29 || ids.gps!=91)
            throw new IllegalStateException("Closed history row sequence mismatch");
    }

    static void historyRoute(Connection db, HistoryIds ids, int route, LocalDate day, int driver,
            int vehicle, int store, int km, boolean travelled, int startOdometer) throws SQLException {
        double efficiency=vehicle==2?9.1:8.5;
        double estimatedLit=round3(km/efficiency),actualLit=round3((km-1)/efficiency);
        if (travelled) {
            row(db,"routes","id,date,driver_id,vehicle_id,warehouse_id,status,version,total_distance,"
                            + "estimated_fuel_cost,estimated_fuel_liters,estimated_work_minutes,load_rate,"
                            + "actual_distance_m,actual_fuel_liters,actual_fuel_cost",
                    route,day,driver,vehicle,1,"PUBLISHED",1,km*1000.0,round2(estimatedLit*29.4),
                    estimatedLit,300,4.0/120.0,(km-1)*1000,actualLit,round2(actualLit*29.4));
            row(db,"mileage_logs","id,date,driver_id,route_id,vehicle_id,start_time,end_time,"
                            + "start_odometer,end_odometer,actual_distance_km,gps_distance_km,"
                            + "gps_distance_status,mileage_settled_at",
                    ids.mileage,day,driver,route,vehicle,day.atTime(8,30),day.atTime(16,30),
                    startOdometer,startOdometer+km-1,km-1,(double)(km-1),"COMPLETE",day.atTime(16,35));
            row(db,"route_vehicle_segments","id,route_id,segment_no,vehicle_id,driver_id,source_mileage_log_id,"
                            + "start_time,end_time,start_odometer_km,end_odometer_km,planned_distance_m,actual_distance_m,"
                            + "fuel_type_snapshot,fuel_efficiency_km_per_liter,fuel_price_per_liter_snapshot,"
                            + "estimated_fuel_liters,estimated_fuel_cost,actual_fuel_liters,actual_fuel_cost,"
                            + "calculation_basis,start_reason,end_reason,status",
                    ids.segment++,route,1,vehicle,driver,ids.mileage++,day.atTime(8,30),day.atTime(16,30),
                    startOdometer,startOdometer+km-1,km*1000,(km-1)*1000,"DIESEL",efficiency,29.4,
                    estimatedLit,round2(estimatedLit*29.4),actualLit,round2(actualLit*29.4),
                    "ODOMETER","INITIAL","COMPLETED","COMPLETED");
            double[] from={22.6877,120.3070},to=STORE_POINTS[store-1];
            for (int point=0;point<4;point++) {
                double fraction=(point+1)/5.0;
                row(db,"gps_pings","id,driver_id,lat,lng,timestamp",ids.gps++,driver,
                        from[0]+(to[0]-from[0])*fraction,from[1]+(to[1]-from[1])*fraction,
                        day.atTime(9,0).plusMinutes(point*20));
            }
        } else {
            row(db,"routes","id,date,driver_id,vehicle_id,warehouse_id,status,version,total_distance,"
                            + "estimated_fuel_cost,estimated_fuel_liters,estimated_work_minutes,load_rate",
                    route,day,driver,vehicle,1,"PUBLISHED",1,km*1000.0,round2(estimatedLit*29.4),
                    estimatedLit,300,4.0/120.0);
            row(db,"route_vehicle_segments","id,route_id,segment_no,vehicle_id,driver_id,planned_distance_m,"
                            + "fuel_type_snapshot,fuel_efficiency_km_per_liter,fuel_price_per_liter_snapshot,"
                            + "estimated_fuel_liters,estimated_fuel_cost,calculation_basis,start_reason,status",
                    ids.segment++,route,1,vehicle,driver,km*1000,"DIESEL",efficiency,29.4,
                    estimatedLit,round2(estimatedLit*29.4),"PLANNED_DISTANCE","INITIAL","PLANNED");
        }
    }

    static int items(Connection db, int id, int order, long driver, LocalDateTime checked, boolean mismatch)
            throws SQLException {
        String[] products={"飲用水","麵包","鮮乳"};
        String[] codes={"WTR-500","BRD-01","MLK-01"};
        int[] quantities={2,1,1};
        for (int i=0;i<3;i++) {
            Integer measured=checked==null?null:(mismatch&&i==2?0:quantities[i]);
            row(db,"order_items","id,order_id,product_code,item_name,expected_quantity,unit,sequence,"
                            + "loaded_quantity,checked_at,checked_by_driver_id,loading_notes,loading_mismatch_reported",
                    id++,order,codes[i],products[i],quantities[i],"箱",i+1,measured,checked,
                    checked==null?null:driver,mismatch&&i==2?"鮮乳短少 1 箱":null,mismatch&&i==2);
        }
        return id;
    }

    static String orderNo(LocalDate day, int sequence, long group) {
        return "DO-"+day.format(DateTimeFormatter.BASIC_ISO_DATE)+"-"+String.format("%03d",group*10+sequence);
    }
    static String vendor(int id) { return id%2==0?"港都生鮮供應":"南方食品物流"; }
    static double round2(double value) { return Math.round(value*100.0)/100.0; }
    static double round3(double value) { return Math.round(value*1000.0)/1000.0; }

    static void verify(Connection db, List<String> tables, LocalDate today) throws SQLException {
        Map<String,Long> expected=Map.ofEntries(
                Map.entry("warehouses",2L),Map.entry("stores",8L),Map.entry("drivers",5L),
                Map.entry("vehicles",4L),Map.entry("schedule_months",2L),Map.entry("driver_leave_requests",1L),
                Map.entry("driver_leave_request_events",2L),Map.entry("fuel_price_history",2L),
                Map.entry("vehicle_maintenance_records",2L),Map.entry("vehicle_maintenance_settings",1L),
                Map.entry("gps_pings",90L),Map.entry("routes",28L),Map.entry("orders",36L),
                Map.entry("order_items",108L),Map.entry("delivery_records",24L),
                Map.entry("exception_cases",13L),Map.entry("mileage_logs",21L),
                Map.entry("route_vehicle_segments",28L));
        for (String table:tables) {
            long actual=count(db,table);
            if (table.equals("driver_shifts") || table.equals("attendance_records")) {
                if (actual<80) throw new IllegalStateException("Insufficient roster coverage: "+table);
            } else if (actual!=expected.getOrDefault(table,0L)) {
                throw new IllegalStateException("Unexpected row count in "+table+": "+actual);
            }
        }
        if (scalar(db,"SELECT COUNT(*) FROM orders WHERE order_number LIKE 'DEMO%' OR source_vendor LIKE '%示範%'")!=0
                || scalar(db,"SELECT COUNT(*) FROM drivers WHERE name LIKE '%示範%' OR account LIKE 'DEMO%'")!=0
                || scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE description LIKE '%示範%'")!=0)
            throw new IllegalStateException("Visible demo markers remain");
        if (scalar(db,"SELECT COUNT(*) FROM orders WHERE delivery_date<CURDATE() "
                + "AND status NOT IN ('COMPLETED','CANCELLED','FAILED','NO_SIGNATURE')")!=0)
            throw new IllegalStateException("Old unresolved orders remain");
        if (scalar(db,"SELECT COUNT(*) FROM order_items i JOIN orders o ON o.id=i.order_id "
                + "WHERE o.status='COMPLETED' AND (i.checked_at IS NULL OR i.loaded_quantity<>i.expected_quantity)")!=0)
            throw new IllegalStateException("Completed item history incomplete");
        if (scalar(db,"SELECT COUNT(*) FROM driver_leave_requests WHERE request_mode='SYSTEM_NO_SHOW'")!=0)
            throw new IllegalStateException("Automatic no-show records appeared");
        if (scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE type='LOADING_MISMATCH' AND status='OPEN' "
                + "AND order_id IS NOT NULL AND follow_up_order_id IS NOT NULL")!=1)
            throw new IllegalStateException("Expected exactly one actionable exception");
        if (scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='OPEN'")!=1
                || scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='CLOSED'")!=12
                || scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='CLOSED' AND type='NO_SIGNATURE'")!=6
                || scalar(db,"SELECT COUNT(*) FROM exception_cases WHERE status='CLOSED' AND type='LOADING_MISMATCH'")!=6)
            throw new IllegalStateException("Closed anomaly history is incomplete");
        verifyForeignKeys(db);
        System.out.println("VERIFIED all 39 business tables, cross-table references, history quantities, one exception");
    }

    static void verifyForeignKeys(Connection db) throws SQLException {
        try (Statement s=db.createStatement();ResultSet r=s.executeQuery(
                "SELECT table_name,column_name,referenced_table_name,referenced_column_name "
                        + "FROM information_schema.key_column_usage WHERE table_schema=DATABASE() "
                        + "AND referenced_table_name IS NOT NULL")) {
            while (r.next()) {
                String child=r.getString(1),column=r.getString(2),parent=r.getString(3),parentColumn=r.getString(4);
                String query="SELECT COUNT(*) FROM `"+child+"` c LEFT JOIN `"+parent+"` p ON c.`"+column
                        +"`=p.`"+parentColumn+"` WHERE c.`"+column+"` IS NOT NULL AND p.`"+parentColumn+"` IS NULL";
                if (scalar(db,query)!=0) throw new IllegalStateException("Broken FK "+child+"."+column);
            }
        }
        for (String query:List.of(
                "SELECT COUNT(*) FROM exception_cases e LEFT JOIN orders o ON o.id=e.order_id WHERE e.order_id IS NOT NULL AND o.id IS NULL",
                "SELECT COUNT(*) FROM exception_cases e LEFT JOIN orders o ON o.id=e.follow_up_order_id WHERE e.follow_up_order_id IS NOT NULL AND o.id IS NULL",
                "SELECT COUNT(*) FROM order_items i LEFT JOIN orders o ON o.id=i.order_id WHERE o.id IS NULL",
                "SELECT COUNT(*) FROM delivery_records d LEFT JOIN orders o ON o.id=d.order_id WHERE o.id IS NULL")) {
            if (scalar(db,query)!=0) throw new IllegalStateException("Broken application-level reference");
        }
    }

    static void resetSequences(Connection db, List<String> tables) throws SQLException {
        for (String table:tables) {
            try (PreparedStatement p=db.prepareStatement("SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema=DATABASE() AND table_name=? AND extra LIKE '%auto_increment%'")) {
                p.setString(1,table);
                try (ResultSet r=p.executeQuery()) { r.next(); if (r.getLong(1)==0) continue; }
            }
            long next=scalar(db,"SELECT COALESCE(MAX(id),0)+1 FROM `"+table+"`");
            exec(db,"ALTER TABLE `"+table+"` AUTO_INCREMENT="+next);
        }
        System.out.println("RESET auto-increment counters on business tables");
    }

    static void row(Connection db, String table, String columns, Object... values) throws SQLException {
        String sql="INSERT INTO `"+table+"` ("+columns+") VALUES ("
                +String.join(",",Collections.nCopies(values.length,"?"))+")";
        try (PreparedStatement p=db.prepareStatement(sql)) {
            for (int i=0;i<values.length;i++) p.setObject(i+1,values[i]);
            if (p.executeUpdate()!=1) throw new SQLException("Insert failed: "+table);
        }
    }

    static void exec(Connection db, String sql) throws SQLException {
        try (Statement s=db.createStatement()) { s.execute(sql); }
    }
    static long count(Connection db, String table) throws SQLException {
        return scalar(db,"SELECT COUNT(*) FROM `"+table+"`");
    }
    static long scalar(Connection db, String sql) throws SQLException {
        try (Statement s=db.createStatement(); ResultSet r=s.executeQuery(sql)) {
            if (!r.next()) throw new SQLException("No result for query");
            return r.getLong(1);
        }
    }
    static String unquote(String value) {
        if (value==null) return "";
        value=value.trim();
        if (value.length()>1 && ((value.startsWith("\"")&&value.endsWith("\""))
                ||(value.startsWith("'")&&value.endsWith("'")))) return value.substring(1,value.length()-1);
        return value;
    }

    static void backup(Properties env, Path directory, String fileName) throws Exception {
        Files.createDirectories(directory);
        Path dump=directory.resolve(fileName);
        if (Files.exists(dump)) throw new IllegalStateException("Backup exists; refusing overwrite");
        URI location=URI.create(unquote(env.getProperty("DB_URL")).substring(5));
        ProcessBuilder process=new ProcessBuilder("C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe",
                "--host="+location.getHost(),"--port="+(location.getPort()<0?3306:location.getPort()),
                "--user="+unquote(env.getProperty("DB_USER")),"--single-transaction","--quick","--skip-lock-tables",
                "--routines","--triggers","--events","--hex-blob","--no-tablespaces",
                "--set-gtid-purged=OFF","--default-character-set=utf8mb4","--result-file="+dump,"logistics");
        process.environment().put("MYSQL_PWD",unquote(env.getProperty("DB_PASSWORD")));
        process.redirectErrorStream(true).redirectOutput(directory.resolve("backup.log").toFile());
        if (process.start().waitFor()!=0 || !Files.isRegularFile(dump) || Files.size(dump)<100_000)
            throw new IllegalStateException("Full backup failed; no database changes made");
        String content=Files.readString(dump);
        if (!content.contains("Dump completed on") || !content.contains("CREATE TABLE `orders`"))
            throw new IllegalStateException("Backup validation failed; no database changes made");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
        Files.writeString(directory.resolve(fileName+".sha256"),hash+"\n",StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
        System.out.println("BACKUP " + dump + " bytes=" + Files.size(dump));
    }
}
