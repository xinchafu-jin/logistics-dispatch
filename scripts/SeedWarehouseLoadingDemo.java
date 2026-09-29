import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicitly requested local fixtures. Insert-only; never backfills real historical orders. */
class SeedWarehouseLoadingDemo {
    static final String PREFIX = "DEMO-LOAD-RPT-";
    static final String MARKER = "[示範] 商品點交報表資料，非真實營運紀錄";
    static final List<String> TABLES = List.of("orders", "order_items", "delivery_records", "exception_cases");
    record Inventory(long maxId, long count, String sha256) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !Set.of("inspect", "seed").contains(args[1]))
            throw new IllegalArgumentException("Usage: backend-directory inspect|seed YYYY-MM-DD backup-directory");
        Path backend = Path.of(args[0]).toAbsolutePath().normalize();
        LocalDate date = LocalDate.parse(args[2]);
        String batchPrefix = PREFIX + date.toString().replace("-", "") + "-";
        if (!date.isBefore(LocalDate.now(ZoneId.of("Asia/Taipei"))))
            throw new IllegalArgumentException("Demo date must be in the past to avoid live or future dispatch tasks");
        Path backups = Path.of(args[3]).toAbsolutePath().normalize();
        Path backupRoot = backend.getParent().getParent().resolve(backend.getParent().getFileName() + "-db-backups");
        if (!backups.startsWith(backupRoot) || backups.equals(backupRoot))
            throw new IllegalArgumentException("Use a dedicated backup directory outside the repository");
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(backend.resolve(".env"))) { config.load(reader); }
        for (String key : List.of("DB_URL", "DB_USER", "DB_PASSWORD")) {
            String override = System.getenv(key);
            if (override != null) config.setProperty(key, override);
        }
        String url = config.getProperty("DB_URL", "");
        if (!url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:|/).*"))
            throw new IllegalArgumentException("Only the configured local MySQL database is permitted");
        try (Connection db = DriverManager.getConnection(url, config.getProperty("DB_USER"), config.getProperty("DB_PASSWORD"))) {
            if (!db.getCatalog().matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Invalid database name");
            List<Long> warehouses = ids(db, "SELECT id FROM warehouses ORDER BY id");
            List<Long> stores = ids(db, "SELECT id FROM stores ORDER BY id");
            if (warehouses.isEmpty() || stores.isEmpty()) throw new IllegalStateException("Existing warehouses and stores required");
            long existing = scalar(db, "SELECT COUNT(*) FROM orders WHERE order_number LIKE ?", batchPrefix + "%");
            System.out.println("DEMO date=" + date + " warehouses=" + warehouses.size() + " existing-marked-orders=" + existing);
            System.out.println("PLAN orders=" + warehouses.size() * 2 + " item-checks=" + warehouses.size() * 6
                    + " completed-deliveries=" + warehouses.size() + " closed-loading-cases=" + warehouses.size());
            System.out.println("NO changes to existing orders, routes, drivers, vehicles, attendance or schema.");
            System.out.println("SCREENSHOT_ORDER items=" + scalar(db,
                    "SELECT COUNT(*) FROM order_items i JOIN orders o ON i.order_id=o.id WHERE o.order_number=?", "DO-20260915-D7BEFAFF"));
            if (args[1].equals("inspect")) { db.setReadOnly(true); return; }
            if (existing != 0) {
                verify(db, warehouses.size(), batchPrefix);
                System.out.println("SKIPPED: this complete marked demo batch already exists; no duplicate or overwrite performed.");
                return;
            }
            backup(config, db.getCatalog(), backups);
            db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            db.setAutoCommit(false);
            Map<String, Inventory> before = new LinkedHashMap<>();
            Map<String, List<Long>> inserted = new LinkedHashMap<>();
            try {
                for (String table : TABLES) before.put(table, inventory(db, table, null));
                for (int index = 0; index < warehouses.size(); index++) {
                    long warehouse = warehouses.get(index), store = stores.get(index % stores.size());
                    LocalDateTime checkedAt = date.atTime(8, 0).plusMinutes(index * 10L);
                    for (boolean mismatch : List.of(false, true)) {
                        String number = batchPrefix + warehouse + (mismatch ? "-X" : "-OK");
                        if (number.length() > 30) throw new IllegalStateException("Demo order number exceeds current schema width");
                        int missingMilk = mismatch ? 1 : 0;
                        long order = add(db, inserted, "orders",
                                "order_number,store_id,warehouse_id,delivery_date,status,box_count,source_vendor,item_description,notes,order_type,retry_count,loaded_at,created_at,updated_at",
                                number, store, warehouse, date, mismatch ? "FAILED" : "COMPLETED", 10 + index,
                                MARKER, "[示範] 飲用水、麵包、鮮乳；僅供報表展示", MARKER, "NORMAL", 0,
                                mismatch ? null : checkedAt, date.atTime(7, 0), checkedAt);
                        add(db, inserted, "order_items", "order_id,product_code,item_name,expected_quantity,loaded_quantity,unit,sequence,checked_at,notes,loading_notes,loading_mismatch_reported",
                                order, "DEMO-BOX-1", "[示範] 飲用水", 4 + index, 4 + index, "箱", 1, checkedAt, MARKER, "[示範] 數量相符", false);
                        add(db, inserted, "order_items", "order_id,product_code,item_name,expected_quantity,loaded_quantity,unit,sequence,checked_at,notes,loading_notes,loading_mismatch_reported",
                                order, "DEMO-BOX-2", "[示範] 麵包", 3, 3, "箱", 2, checkedAt, MARKER, "[示範] 數量相符", false);
                        add(db, inserted, "order_items", "order_id,product_code,item_name,expected_quantity,loaded_quantity,unit,sequence,checked_at,notes,loading_notes,loading_mismatch_reported",
                                order, "DEMO-BOX-3", "[示範] 鮮乳", 3, 3 - missingMilk, "箱", 3, checkedAt, MARKER,
                                mismatch ? "[示範] 點交不符，少 1 箱" : "[示範] 數量相符", mismatch);
                        if (mismatch) {
                            add(db, inserted, "exception_cases", "order_id,type,status,description,created_at,handled_at,handled_by,resolution",
                                    order, "LOADING_MISMATCH", "CLOSED", MARKER + "；鮮乳缺少 1 箱",
                                    checkedAt, checkedAt.plusMinutes(30), "DEMO-LOAD-RPT", "[示範] 已查核；僅供展示，不需實際重送或派車");
                        } else {
                            add(db, inserted, "delivery_records", "order_id,arrived_at,delivered_at,handled_at,expected_box_count,delivered_box_count,shortage_box_count,damaged_box_count,replacement_required_box_count,no_signature,notes",
                                    order, checkedAt.plusHours(2), checkedAt.plusHours(2).plusMinutes(10), checkedAt.plusHours(2).plusMinutes(10),
                                    10 + index, 10 + index, 0, 0, 0, false, MARKER);
                        }
                    }
                }
                for (String table : TABLES) {
                    Inventory old = before.get(table);
                    if (!old.equals(inventory(db, table, old.maxId())))
                        throw new IllegalStateException("Existing row fingerprint changed in " + table + "; rolling back");
                }
                verify(db, warehouses.size(), batchPrefix);
                if (scalar(db, "SELECT COUNT(*) FROM orders o WHERE o.order_number LIKE ? AND o.box_count<>(SELECT SUM(i.expected_quantity) FROM order_items i WHERE i.order_id=o.id)", batchPrefix + "%") != 0
                        || scalar(db, "SELECT COUNT(*) FROM order_items i JOIN orders o ON i.order_id=o.id WHERE o.order_number LIKE ? AND i.unit='箱' AND i.loading_mismatch_reported=1 AND i.expected_quantity-i.loaded_quantity=1", batchPrefix + "%") != warehouses.size())
                    throw new IllegalStateException("New demo box totals or mismatch flags do not match; rolling back");
                StringBuilder receipt = new StringBuilder("Date: " + date + "\nMarker: " + MARKER + "\n");
                before.forEach((table, rows) -> receipt.append(table).append(" original-count=").append(rows.count())
                        .append(" original-sha256=").append(rows.sha256()).append(" inserted-ids=").append(inserted.get(table)).append('\n'));
                Files.writeString(backups.resolve("inserted-records.txt"), receipt, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                db.commit();
                System.out.println("COMMITTED marked demo records; every original row fingerprint is unchanged.");
                inserted.forEach((table, rows) -> System.out.println("INSERTED " + table + "=" + rows.size()));
                System.out.println("RECEIPT " + backups.resolve("inserted-records.txt"));
            } catch (Exception error) { db.rollback(); throw error; }
        }
    }

    static void verify(Connection db, int warehouses, String batchPrefix) throws SQLException {
        if (scalar(db, "SELECT COUNT(*) FROM orders WHERE order_number LIKE ?", batchPrefix + "%") != warehouses * 2L
                || scalar(db, "SELECT COUNT(*) FROM order_items i JOIN orders o ON i.order_id=o.id WHERE o.order_number LIKE ? AND i.loaded_quantity IS NOT NULL AND i.checked_at IS NOT NULL", batchPrefix + "%") != warehouses * 6L
                || scalar(db, "SELECT COUNT(*) FROM exception_cases e JOIN orders o ON e.order_id=o.id WHERE o.order_number LIKE ? AND e.type='LOADING_MISMATCH' AND e.status='CLOSED'", batchPrefix + "%") != warehouses
                || scalar(db, "SELECT COUNT(*) FROM delivery_records d JOIN orders o ON d.order_id=o.id WHERE o.order_number LIKE ? AND d.no_signature=0 AND d.delivered_at IS NOT NULL", batchPrefix + "%") != warehouses)
            throw new IllegalStateException("Demo completeness check failed; rolling back");
    }

    static long add(Connection db, Map<String, List<Long>> inserted, String table, String columns, Object... values) throws SQLException {
        String placeholders = String.join(",", Collections.nCopies(values.length, "?"));
        try (PreparedStatement statement = db.prepareStatement("INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")", Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Insert count mismatch");
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new IllegalStateException("Missing generated key");
                long id = keys.getLong(1); inserted.computeIfAbsent(table, ignored -> new ArrayList<>()).add(id); return id;
            }
        }
    }

    static List<Long> ids(Connection db, String sql) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getLong(1));
        }
        return ids;
    }

    static long scalar(Connection db, String sql, Object value) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }

    static Inventory inventory(Connection db, String table, Long maxId) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0, largest = 0;
        String sql = "SELECT * FROM " + table + (maxId == null ? "" : " WHERE id<=?") + " ORDER BY id";
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            if (maxId != null) statement.setLong(1, maxId);
            try (ResultSet rows = statement.executeQuery()) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    largest = rows.getLong("id"); count++;
                    for (int column = 1; column <= columns; column++) {
                        byte[] value = Objects.toString(rows.getObject(column), "<NULL>").getBytes(StandardCharsets.UTF_8);
                        digest.update((value.length + ":").getBytes(StandardCharsets.UTF_8)); digest.update(value);
                    }
                    digest.update((byte) '\n');
                }
            }
        }
        return new Inventory(largest, count, HexFormat.of().formatHex(digest.digest()));
    }

    static void backup(Properties config, String database, Path directory) throws Exception {
        Files.createDirectories(directory); Path file = directory.resolve("before-loading-demo.sql");
        if (Files.exists(file)) throw new IllegalStateException("Backup file already exists; refusing to overwrite");
        URI location = URI.create(config.getProperty("DB_URL").substring(5));
        ProcessBuilder process = new ProcessBuilder("C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe",
                "--host=" + location.getHost(), "--port=" + (location.getPort() < 0 ? 3306 : location.getPort()),
                "--user=" + config.getProperty("DB_USER"), "--single-transaction", "--quick", "--skip-lock-tables",
                "--no-tablespaces", "--set-gtid-purged=OFF", "--default-character-set=utf8mb4", "--result-file=" + file, database);
        process.environment().put("MYSQL_PWD", config.getProperty("DB_PASSWORD"));
        process.redirectErrorStream(true).redirectOutput(directory.resolve("backup.log").toFile());
        if (process.start().waitFor() != 0 || !Files.isRegularFile(file) || Files.size(file) < 100)
            throw new IllegalStateException("Backup failed; no demo records written");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        Files.writeString(directory.resolve("before-loading-demo.sha256"), sha + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println("BACKUP " + file);
    }
}
