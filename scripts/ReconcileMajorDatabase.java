import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;

/** Local-only, backed-up reconciliation of an existing V2 database with the MAJOR schema. */
public class ReconcileMajorDatabase {
    final Properties config = new Properties();
    final Path backend, backups;
    final String source, reference, rehearsal, url;
    final boolean keepLegacyRouteDuplicates = Boolean.getBoolean("major.keepLegacyRouteDuplicates");
    final Path mysql = Path.of("C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe");
    final Path dump = Path.of("C:/Program Files/MySQL/MySQL Server 8.0/bin/mysqldump.exe");

    ReconcileMajorDatabase(String[] args) throws Exception {
        backend = Path.of(args[0]).toAbsolutePath().normalize();
        try (Reader reader = Files.newBufferedReader(backend.resolve(".env"))) { config.load(reader); }
        for (String key : List.of("DB_URL", "DB_USER", "DB_PASSWORD")) {
            String value = System.getenv(key);
            if (value != null) config.setProperty(key, value);
        }
        url = config.getProperty("DB_URL", "");
        if (!url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:|/).*"))
            throw new IllegalStateException("Only a local database is permitted");
        try (Connection db = connect(null)) { source = db.getCatalog(); }
        if (source == null || !source.matches("[a-zA-Z0-9_]+")) throw new IllegalStateException("Invalid source database");
        if (!args[2].matches("[0-9]{8}_[0-9]{6}")) throw new IllegalArgumentException("Timestamp must be YYYYMMDD_HHMMSS");
        reference = source + "_major_reference_" + args[2];
        rehearsal = source + "_major_rehearsal_" + args[2];
        backups = Path.of(args[3]).toAbsolutePath().normalize();
        if (backups.startsWith(backend.getParent())) throw new IllegalArgumentException("Backups must be outside the Git checkout");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: backend-directory prepare|diff|reset-rehearsal|rehearse|apply|verify|verify-rehearsal|cleanup|v16-inspect|v16-align|schema-validate timestamp backup-directory");
        ReconcileMajorDatabase tool = new ReconcileMajorDatabase(args);
        switch (args[1]) {
            case "prepare" -> tool.prepare();
            case "diff" -> tool.diff();
            case "reset-rehearsal" -> tool.resetRehearsal();
            case "rehearse" -> tool.reconcile(tool.rehearsal);
            case "apply" -> {
                Path receipt = tool.backups.resolve("rehearsal-verified.txt");
                if (!Files.isRegularFile(receipt)
                        || !Files.readString(receipt).contains("keepLegacyRouteDuplicates=" + tool.keepLegacyRouteDuplicates))
                    throw new IllegalStateException("Successful rehearsal required before source changes");
                tool.requireOfflineSource();
                tool.backup("before-apply");
                tool.reconcile(tool.source);
            }
            case "verify" -> { tool.verifySchema(tool.source); tool.flyway(tool.source).validate(); }
            case "verify-rehearsal" -> { tool.verifySchema(tool.rehearsal); tool.flyway(tool.rehearsal).validate(); }
            case "v16-inspect" -> tool.alignLegacyV16(false);
            case "v16-align" -> tool.alignLegacyV16(true);
            case "schema-validate" -> tool.validateEntitySchema();
            case "cleanup" -> tool.cleanup();
            default -> throw new IllegalArgumentException("Unknown mode");
        }
    }
    Connection connect(String database) throws SQLException {
        String target = database == null ? url : url.replaceFirst("(?<=/)[^/?]+(?=\\?|$)", database);
        return DriverManager.getConnection(target, config.getProperty("DB_USER"), config.getProperty("DB_PASSWORD"));
    }

    /** Only the known local legacy V16 may be aligned; never repairs arbitrary migrations. */
    void alignLegacyV16(boolean apply) throws Exception {
        Flyway current = flyway(source);
        var validation = current.validateWithResult();
        try (Connection db = connect(source)) {
            Column flag = columns(db).get("order_items.loading_mismatch_reported");
            if (flag == null || !flag.type.equals("bit(1)") || flag.nullable
                    || !Objects.equals(flag.defaultValue, "b'0'"))
                throw new IllegalStateException("V16 column must already be BIT(1) NOT NULL DEFAULT b'0'; no history changes made");
            if (validation.validationSuccessful) {
                System.out.println("V16 VERIFIED: physical column and Flyway history match the branch; nothing changed.");
                return;
            }
            String canonicalSql = Files.readString(backend.resolve(
                    "src/main/resources/db/migration/V16__order_items_loading_mismatch_reported.sql"), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
            String canonicalHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalSql.getBytes(StandardCharsets.UTF_8)));
            if (!canonicalHash.equals("71d38013f565f61aff32ee0c1ec8cf0cfee5d44b44578f1f4b7f80f4a329a175"))
                throw new IllegalStateException("Branch V16 is not the reviewed equivalent migration; refusing repair");
            if (validation.invalidMigrations.isEmpty() || validation.invalidMigrations.stream().anyMatch(error ->
                    !Objects.equals(error.version, "16") || !Set.of(
                            org.flywaydb.core.api.CoreErrorCode.CHECKSUM_MISMATCH,
                            org.flywaydb.core.api.CoreErrorCode.DESCRIPTION_MISMATCH).contains(error.errorDetails.errorCode)))
                throw new IllegalStateException("Refusing repair: validation errors are not limited to the equivalent V16 rename");
            try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(
                    "SELECT version,type,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank")) {
                if (!rows.next() || !Objects.equals(rows.getString(1), "15")
                        || !Objects.equals(rows.getString(2), "BASELINE") || !rows.getBoolean(5))
                    throw new IllegalStateException("Expected the verified local V15 baseline");
                if (!rows.next() || !Objects.equals(rows.getString(1), "16")
                        || !Objects.equals(rows.getString(2), "SQL")
                        || !Objects.equals(rows.getString(3), "V16__loading_item_mismatch.sql")
                        || rows.getInt(4) != -1982334097 || !rows.getBoolean(5) || rows.next())
                    throw new IllegalStateException("Expected only the known, successful legacy V16; refusing unrelated repairs");
            }
        }
        System.out.println("V16 PLAN: legacy ALTER and branch's add-if-missing migration have the same verified column.");
        System.out.println("Only Flyway V16 description/checksum metadata will be aligned; original script provenance and operational tables remain untouched.");
        if (!apply) return;
        // This operation changes only one history row, not schema. Idle application pools may stay online.
        // Refuse active transactions and hold READ locks on every operational table while repairing history.
        try (Connection db = connect(source); Statement statement = db.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.innodb_trx")) {
            rows.next();
            if (rows.getInt(1) != 0) throw new IllegalStateException("Active database transactions; retry during an idle period");
        }
        Files.createDirectories(backups);
        backup("before-v16-alignment");
        Map<String, Signature> before = new LinkedHashMap<>();
        Map<String, List<String>> projections = new LinkedHashMap<>();
        try (Connection db = connect(source)) {
            Map<String, Column> schema = columns(db);
            List<String> protectedTables = tables(db).stream().filter(table -> !table.equals("flyway_schema_history")).toList();
            for (String table : protectedTables) {
                List<String> names = schema.values().stream().filter(column -> column.table.equals(table))
                        .map(column -> column.name).toList();
                projections.put(table, names);
            }
            execute(db, "SET SESSION lock_wait_timeout=5");
            execute(db, "LOCK TABLES " + String.join(",", protectedTables.stream().map(table -> quoted(table) + " READ").toList()));
            try {
                for (String table : protectedTables)
                    before.put(table, signature(db, table, projections.get(table), false));
                current.repair();
                current.validate();
                if (current.info().pending().length != 0)
                    throw new IllegalStateException("Unexpected pending migrations after V16 alignment");
                for (var entry : before.entrySet()) {
                    if (!entry.getValue().equals(signature(db, entry.getKey(), projections.get(entry.getKey()), false)))
                        throw new IllegalStateException("Data fingerprint changed in " + entry.getKey() + "; retain backup and investigate");
                }
            } finally {
                execute(db, "UNLOCK TABLES");
            }
        }
        StringBuilder receipt = new StringBuilder("Aligned verified equivalent legacy V16 to the branch migration.\n");
        before.forEach((table, signature) -> receipt.append(table).append(" count=").append(signature.count)
                .append(" sha256=").append(signature.sha256).append('\n'));
        Files.writeString(backups.resolve("v16-alignment-verified.txt"), receipt, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("V16 ALIGNED; all " + before.size() + " original table fingerprints unchanged. Backup: " + backups);
    }

    /** Validates the current compiled entities without starting Spring, schedulers, or schema generation. */
    void validateEntitySchema() throws Exception {
        flyway(source).validate();
        var builder = new org.hibernate.boot.registry.StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.driver_class", "com.mysql.cj.jdbc.Driver")
                .applySetting("hibernate.connection.url", url)
                .applySetting("hibernate.connection.username", config.getProperty("DB_USER"))
                .applySetting("hibernate.connection.password", config.getProperty("DB_PASSWORD"))
                .applySetting("hibernate.connection.pool_size", "1")
                .applySetting("hibernate.hbm2ddl.auto", "validate")
                .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl");
        var registry = builder.build();
        try {
            var mappings = new org.hibernate.boot.MetadataSources(registry);
            int count = 0;
            try (var files = Files.list(backend.resolve("src/main/java/com/example/backend/entity"))) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                    String name = file.getFileName().toString().replaceFirst("\\.java$", "");
                    Class<?> type = Class.forName("com.example.backend.entity." + name);
                    if (type.isAnnotationPresent(jakarta.persistence.Entity.class)) {
                        mappings.addAnnotatedClass(type);
                        count++;
                    }
                }
            }
            if (count == 0) throw new IllegalStateException("No compiled entities found");
            org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator.process(
                    mappings.buildMetadata(), registry, builder.getSettings(), null);
            System.out.println("ENTITY SCHEMA VERIFIED: " + count + " current entities; no DDL or business data writes.");
        } finally {
            org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry);
        }
    }
    void requireOfflineSource() throws SQLException {
        try (Connection db = connect(source); PreparedStatement check = db.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.processlist WHERE db=? AND id<>CONNECTION_ID()")) {
            check.setString(1, source);
            try (ResultSet rows = check.executeQuery()) {
                rows.next();
                if (rows.getInt(1) != 0)
                    throw new IllegalStateException("Stop the project backend and close other source database connections before applying");
            }
        }
    }
    void cleanup() throws Exception {
        Path sourceReceipt = backups.resolve("source-verified.txt"), copyReceipt = backups.resolve("rehearsal-verified.txt");
        if (!Files.isRegularFile(sourceReceipt) || !Files.readString(sourceReceipt).startsWith(source + "\n")
                || !Files.isRegularFile(copyReceipt) || !Files.readString(copyReceipt).startsWith(rehearsal + "\n"))
            throw new IllegalStateException("Verified source and rehearsal receipts required before scratch cleanup");
        Path backup = backups.resolve(source + "-before-apply.sql"), checksum = backups.resolve(source + "-before-apply.sha256");
        if (!Files.isRegularFile(backup) || Files.size(backup) == 0 || !Files.isRegularFile(checksum))
            throw new IllegalStateException("Retained source backup required");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup)));
        if (!Files.readString(checksum).startsWith(digest + "  ")) throw new IllegalStateException("Source backup checksum mismatch");
        try (Connection db = connect(source)) {
            for (String target : List.of(reference, rehearsal)) {
                if (target.equals(source) || !target.matches(Pattern.quote(source) + "_major_(reference|rehearsal)_[0-9]{8}_[0-9]{6}"))
                    throw new IllegalStateException("Invalid scratch cleanup target");
                try (PreparedStatement check = db.prepareStatement("SELECT COUNT(*) FROM information_schema.processlist WHERE db=?")) {
                    check.setString(1, target);
                    try (ResultSet rows = check.executeQuery()) {
                        rows.next(); if (rows.getInt(1) != 0) throw new IllegalStateException("Scratch database is still in use: " + target);
                    }
                }
            }
            for (String target : List.of(reference, rehearsal)) {
                execute(db, "DROP DATABASE " + quoted(target));
                System.out.println("REMOVED DISPOSABLE COPY " + target + "; original backup retained at " + backup);
            }
        }
    }
    void prepare() throws Exception {
        Files.createDirectories(backups);
        Path backup = backup("before-major");
        java.net.URI location = java.net.URI.create(url.substring(5));
        try (Connection db = connect(null)) {
            for (String name : List.of(reference, rehearsal)) {
                try (PreparedStatement check = db.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                    check.setString(1, name);
                    try (ResultSet result = check.executeQuery()) { result.next(); if (result.getInt(1) != 0) throw new IllegalStateException("Scratch database already exists"); }
                }
                execute(db, "CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
        }
        List<String> restoreArgs = List.of(mysql.toString(), "--host=" + location.getHost(),
            "--port=" + (location.getPort() < 0 ? 3306 : location.getPort()), "--user=" + config.getProperty("DB_USER"),
            "--default-character-set=utf8mb4", "--database=" + rehearsal);
        runClient(restoreArgs, backup, backups.resolve("rehearsal-restore.log"));
        flyway(reference).migrate();
        System.out.println("REFERENCE " + reference);
        System.out.println("REHEARSAL " + rehearsal);
        diff();
    }
    void resetRehearsal() throws Exception {
        // Only the disposable copy made by this tool may be reset. Never reset the source.
        if (rehearsal.equals(source) || rehearsal.equals(reference)
                || !rehearsal.matches(Pattern.quote(source) + "_major_rehearsal_[0-9]{8}_[0-9]{6}"))
            throw new IllegalStateException("Invalid scratch target");
        if (Files.exists(backups.resolve("rehearsal-verified.txt")))
            throw new IllegalStateException("Will not reset a successfully verified rehearsal");
        Path backup = backups.resolve(source + "-before-major.sql");
        if (!Files.isRegularFile(backup) || Files.size(backup) == 0)
            throw new IllegalStateException("Source backup is required to recover the disposable copy");
        try (var lines = Files.lines(backup, StandardCharsets.UTF_8)) {
            if (lines.anyMatch(line -> line.matches("(?i)\\s*(CREATE DATABASE|USE)\\b.*")))
                throw new IllegalStateException("Restore file must not select or create a different database");
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup)));
        Path checksum = backups.resolve(source + "-before-major.sha256");
        if (Files.exists(checksum) && !Files.readString(checksum).startsWith(digest + "  "))
            throw new IllegalStateException("Backup checksum does not match");
        if (!Files.exists(checksum)) Files.writeString(checksum, digest + "  " + backup.getFileName() + "\n",
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        if (!Objects.equals(flyway(reference).info().current().getVersion().getVersion(), "15"))
            throw new IllegalStateException("Expected a freshly migrated MAJOR V15 reference");
        try (Connection copy = connect(rehearsal)) {
            if (!tables(copy).contains("major_legacy_pre_trip_inspections_v2"))
                throw new IllegalStateException("Only this tool's interrupted rehearsal can be reset");
        }
        Path audit = backups.resolve(rehearsal + "-changes.sql");
        if (Files.exists(audit)) Files.move(audit, backups.resolve(rehearsal + "-changes-attempt-" + System.currentTimeMillis() + ".sql"));
        try (Connection db = connect(null)) {
            System.out.println("RESET DISPOSABLE COPY " + rehearsal + "; restore=" + backup + "; sha256=" + digest);
            execute(db, "DROP DATABASE " + quoted(rehearsal));
            execute(db, "CREATE DATABASE " + quoted(rehearsal) + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        java.net.URI location = java.net.URI.create(url.substring(5));
        runClient(List.of(mysql.toString(), "--host=" + location.getHost(),
            "--port=" + (location.getPort() < 0 ? 3306 : location.getPort()), "--user=" + config.getProperty("DB_USER"),
            "--default-character-set=utf8mb4", "--database=" + rehearsal), backup, backups.resolve("rehearsal-restore.log"));
    }
    Path backup(String suffix) throws Exception {
        Path backup = backups.resolve(source + "-" + suffix + ".sql");
        if (Files.exists(backup)) throw new IllegalStateException("Will not overwrite a previous backup");
        java.net.URI location = java.net.URI.create(url.substring(5));
        List<String> dumpArgs = new ArrayList<>(List.of(dump.toString(), "--host=" + location.getHost(),
            "--port=" + (location.getPort() < 0 ? 3306 : location.getPort()), "--user=" + config.getProperty("DB_USER"),
            "--single-transaction", "--routines", "--triggers", "--events", "--hex-blob", "--no-tablespaces",
            "--default-character-set=utf8mb4", "--set-gtid-purged=OFF", "--result-file=" + backup, source));
        runClient(dumpArgs, null, backups.resolve("backup.log"));
        if (Files.size(backup) == 0) throw new IllegalStateException("Empty backup");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup)));
        Files.writeString(backups.resolve(source + "-" + suffix + ".sha256"), digest + "  " + backup.getFileName() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("BACKUP " + backup + " bytes=" + Files.size(backup) + " sha256=" + digest);
        return backup;
    }
    void runClient(List<String> command, Path input, Path log) throws Exception {
        ProcessBuilder process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        process.environment().put("MYSQL_PWD", config.getProperty("DB_PASSWORD"));
        if (input != null) process.redirectInput(input.toFile());
        int exit = process.start().waitFor();
        if (exit != 0) throw new IllegalStateException("MySQL utility failed; inspect private log " + log);
    }
    Flyway flyway(String database) {
        return Flyway.configure().dataSource(url.replaceFirst("(?<=/)[^/?]+(?=\\?|$)", database),
            config.getProperty("DB_USER"), config.getProperty("DB_PASSWORD"))
            .locations("filesystem:" + backend.resolve("src/main/resources/db/migration").toString().replace('\\', '/')).load();
    }
    record Column(String table, String name, String type, boolean nullable, String defaultValue, String extra) {}
    Map<String, Column> columns(Connection db) throws SQLException {
        Map<String, Column> result = new LinkedHashMap<>();
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SELECT table_name,column_name,column_type,is_nullable,column_default,extra FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position")) {
            while (rows.next()) {
                Column column = new Column(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4).equals("YES"), rows.getString(5), rows.getString(6));
                result.put(column.table + "." + column.name, column);
            }
        }
        return result;
    }
    void diff() throws Exception {
        try (Connection expected = connect(reference); Connection actual = connect(rehearsal)) {
            Map<String, Column> want = columns(expected), have = columns(actual);
            for (var entry : want.entrySet()) {
                if (entry.getKey().startsWith("flyway_schema_history.")) continue;
                Column current = have.get(entry.getKey()), target = entry.getValue();
                if (current == null) System.out.println("MISSING " + entry.getKey() + " " + target.type + " nullable=" + target.nullable);
                else if (!compatible(current, target))
                    System.out.println("DIFFERENT " + entry.getKey() + " current=" + current.type + "/" + current.nullable + " expected=" + target.type + "/" + target.nullable);
            }
        }
    }
    static Set<String> enumValues(String type) {
        Set<String> values = new LinkedHashSet<>();
        var matches = Pattern.compile("'([^']*)'").matcher(type);
        while (matches.find()) values.add(matches.group(1));
        return values;
    }
    static boolean compatible(Column actual, Column wanted) {
        if (actual.nullable != wanted.nullable) return false;
        if (actual.type.equals(wanted.type)) return true;
        if (actual.type.startsWith("enum(") && wanted.type.startsWith("enum("))
            return enumValues(actual.type).containsAll(enumValues(wanted.type));
        return false;
    }
    Set<String> tables(Connection db) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }
    String createTable(Connection db, String table) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SHOW CREATE TABLE " + quoted(table))) {
            rows.next(); return rows.getString(2);
        }
    }
    String definition(Column column) {
        String sql = column.type + (column.nullable ? " NULL" : " NOT NULL");
        if (column.defaultValue != null) {
            String value = column.defaultValue;
            sql += " DEFAULT " + (column.extra.contains("DEFAULT_GENERATED") || value.startsWith("b'") ? value : "'" + value.replace("'", "''") + "'");
        } else if (column.nullable) sql += " DEFAULT NULL";
        if (column.extra.contains("auto_increment")) sql += " AUTO_INCREMENT";
        return sql;
    }
    record Signature(long count, String sha256) {}
    Signature signature(Connection db, String table, List<String> originalColumns, boolean renamedTonnage) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> projection = originalColumns.stream().map(column -> quoted(renamedTonnage && column.equals("tonnage") ? "legacy_v2_tonnage" : column)).toList();
        List<String> primary = new ArrayList<>();
        try (PreparedStatement statement = db.prepareStatement("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name='PRIMARY' ORDER BY seq_in_index")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) primary.add(quoted(rows.getString(1))); }
        }
        if (primary.isEmpty()) throw new IllegalStateException("Cannot fingerprint table without primary key: " + table);
        long count = 0;
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SELECT " + String.join(",", projection) + " FROM " + quoted(table) + " ORDER BY " + String.join(",", primary))) {
            while (rows.next()) {
                count++;
                for (int i = 1; i <= projection.size(); i++) {
                    byte[] value = rows.getBytes(i);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(value == null ? -1 : value.length).array());
                    if (value != null) digest.update(value);
                }
            }
        }
        return new Signature(count, HexFormat.of().formatHex(digest.digest()));
    }
    void reconcile(String database) throws Exception {
        if (!database.equals(source) && !database.equals(rehearsal)) throw new IllegalStateException("Invalid reconciliation target");
        try (Connection expected = connect(reference); Connection db = connect(database)) {
            if (!Objects.equals(flyway(reference).info().current().getVersion().getVersion(), "15"))
                throw new IllegalStateException("Tool is restricted to verified MAJOR V15");
            Set<String> existing = tables(db);
            if (existing.stream().anyMatch(name -> name.startsWith("major_legacy_")))
                throw new IllegalStateException("Legacy archives already exist; inspect an interrupted or previously completed repair");
            Map<String, Column> oldColumns = columns(db);
            preflight(expected, db, oldColumns);
            Map<String, List<String>> projections = new LinkedHashMap<>();
            for (Column column : oldColumns.values()) projections.computeIfAbsent(column.table, ignored -> new ArrayList<>()).add(column.name);
            Map<String, Signature> before = new LinkedHashMap<>();
            for (var row : projections.entrySet()) before.put(row.getKey(), signature(db, row.getKey(), row.getValue(), false));
            List<String> statements = new ArrayList<>();
            Map<String, String> renamed = new HashMap<>();
            String preTripArchive = "major_legacy_pre_trip_inspections_v2";
            if (existing.contains("pre_trip_inspections") && !oldColumns.containsKey("pre_trip_inspections.engine_oil")) {
                change(db, statements, "RENAME TABLE pre_trip_inspections TO " + quoted(preTripArchive));
                renamed.put("pre_trip_inspections", preTripArchive);
                existing.remove("pre_trip_inspections");
            }
            for (String table : tables(expected)) {
                if (!table.equals("flyway_schema_history") && !existing.contains(table)) change(db, statements, createTable(expected, table));
            }
            Map<String, Column> wanted = columns(expected), current = columns(db);
            for (var entry : wanted.entrySet()) {
                Column target = entry.getValue(), actual = current.get(entry.getKey());
                if (target.table.equals("flyway_schema_history")) continue;
                if (actual == null) {
                    if (!target.nullable && before.getOrDefault(target.table, new Signature(0, "")).count > 0)
                        throw new IllegalStateException("Cannot invent required values for existing records: " + entry.getKey());
                    change(db, statements, "ALTER TABLE " + quoted(target.table) + " ADD COLUMN " + quoted(target.name) + " " + definition(target));
                } else if (!compatible(actual, target)) {
                    boolean widening = actual.type.startsWith("varchar(") && target.type.startsWith("varchar(")
                        && Integer.parseInt(target.type.replaceAll("\\D", "")) >= Integer.parseInt(actual.type.replaceAll("\\D", ""));
                    if (actual.type.startsWith("enum(") && target.type.startsWith("enum(")) {
                        var values = enumValues(actual.type); values.addAll(enumValues(target.type));
                        target = new Column(target.table, target.name, "enum('" + String.join("','", values) + "')", target.nullable, actual.defaultValue, target.extra);
                    } else if (!widening && before.getOrDefault(target.table, new Signature(0, "")).count > 0)
                        throw new IllegalStateException("Unsafe type conversion needs manual review: " + entry.getKey());
                    change(db, statements, "ALTER TABLE " + quoted(target.table) + " MODIFY COLUMN " + quoted(target.name) + " " + definition(target));
                }
            }
            if (oldColumns.containsKey("vehicles.tonnage")) {
                if (!existing.contains("vehicle_maintenance_policies")) throw new IllegalStateException("Legacy tonnage rules missing");
                change(db, statements, "UPDATE vehicles v JOIN vehicle_maintenance_policies p ON p.tonnage=v.tonnage SET v.minor_maintenance_interval_km=p.minor_interval_km,v.major_maintenance_interval_km=p.major_interval_km,v.retirement_km=p.retirement_km WHERE v.minor_maintenance_interval_km IS NULL AND v.major_maintenance_interval_km IS NULL AND v.retirement_km IS NULL");
                change(db, statements, "ALTER TABLE vehicles RENAME COLUMN tonnage TO legacy_v2_tonnage");
                change(db, statements, "RENAME TABLE vehicle_maintenance_policies TO major_legacy_maintenance_policies_v2");
                renamed.put("vehicle_maintenance_policies", "major_legacy_maintenance_policies_v2");
            }
            copyIndexes(expected, db, statements);
            copyForeignKeys(expected, db, statements);
            verifySchema(database);
            for (var row : before.entrySet()) {
                String table = renamed.getOrDefault(row.getKey(), row.getKey());
                Signature after = signature(db, table, projections.get(row.getKey()), row.getKey().equals("vehicles") && oldColumns.containsKey("vehicles.tonnage"));
                if (!row.getValue().equals(after)) throw new IllegalStateException("Original record fingerprint changed: " + row.getKey());
            }
            String historyArchive = "major_legacy_flyway_history_v2";
            change(db, statements, "RENAME TABLE flyway_schema_history TO " + historyArchive);
            var baseline = Flyway.configure().dataSource(url.replaceFirst("(?<=/)[^/?]+(?=\\?|$)", database), config.getProperty("DB_USER"), config.getProperty("DB_PASSWORD"))
                .locations("filesystem:" + backend.resolve("src/main/resources/db/migration").toString().replace('\\', '/'))
                .baselineVersion("15").baselineDescription("MAJOR verified V2 schema reconciliation; old history archived").load();
            baseline.baseline();
            baseline.validate();
            baseline.migrate();
            if (!before.get("flyway_schema_history").equals(signature(db, historyArchive, projections.get("flyway_schema_history"), false)))
                throw new IllegalStateException("Original migration history was not preserved");
            Files.writeString(backups.resolve(database.equals(rehearsal) ? "rehearsal-verified.txt" : "source-verified.txt"), database + "\nkeepLegacyRouteDuplicates=" + keepLegacyRouteDuplicates + "\nOriginal table fingerprints preserved: " + before.size() + "\n" + before + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            System.out.println("VERIFIED " + database + " originalTables=" + before.size() + " originalRecords=unchanged Flyway=V15-baseline");
        }
    }
    void copyIndexes(Connection expected, Connection db, List<String> changes) throws Exception {
        String sql = "SELECT table_name,index_name,non_unique,GROUP_CONCAT(CONCAT('`',column_name,'`') ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() GROUP BY table_name,index_name,non_unique ORDER BY table_name,index_name";
        Set<String> existing = new HashSet<>();
        Set<String> names = new HashSet<>();
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                existing.add(rows.getString(1) + ":" + rows.getString(3) + ":" + rows.getString(4));
                names.add(rows.getString(1) + ":" + rows.getString(2));
            }
        }
        try (Statement statement = expected.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                String table = rows.getString(1), name = rows.getString(2), columns = rows.getString(4);
                if (table.equals("flyway_schema_history") || name.equals("PRIMARY")) continue;
                String signature = table + ":" + rows.getString(3) + ":" + columns;
                if (!existing.contains(signature)) {
                    if (rows.getInt(3) == 0 && duplicateGroups(db, table, columns) > 0
                            && permittedLegacyIndex(table, name, columns)) {
                        String note = "Legacy duplicate routes retained; unique index " + name + " NOT imposed.\n";
                        Files.writeString(backups.resolve(db.getCatalog() + "-warnings.txt"), note,
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        System.out.println("PRESERVED LEGACY CONFLICT " + table + "." + name);
                        continue;
                    }
                    String indexName = name;
                    for (int suffix = 1; names.contains(table + ":" + indexName); suffix++) {
                        String tail = "_major_" + suffix;
                        indexName = name.substring(0, Math.min(name.length(), 64 - tail.length())) + tail;
                    }
                    change(db, changes, "ALTER TABLE " + quoted(table) + " ADD " + (rows.getInt(3) == 0 ? "UNIQUE " : "") + "KEY " + quoted(indexName) + " (" + columns + ")");
                    existing.add(signature);
                    names.add(table + ":" + indexName);
                }
            }
        }
    }
    boolean permittedLegacyIndex(String table, String name, String columns) {
        return keepLegacyRouteDuplicates && table.equals("routes") && name.equals("uk_routes_date_vehicle")
            && columns.equals("`date`,`vehicle_id`");
    }
    long duplicateGroups(Connection db, String table, String columns) throws SQLException {
        if (!tables(db).contains(table)) return 0;
        String notNull = Arrays.stream(columns.split(",")).map(column -> column + " IS NOT NULL").reduce((a, b) -> a + " AND " + b).orElseThrow();
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT COUNT(*) FROM (SELECT " + columns + " FROM " + quoted(table) + " WHERE " + notNull
                + " GROUP BY " + columns + " HAVING COUNT(*)>1) duplicate_groups")) {
            rows.next(); return rows.getLong(1);
        }
    }
    void preflight(Connection expected, Connection db, Map<String, Column> have) throws Exception {
        Map<String, Column> wanted = columns(expected);
        for (var entry : wanted.entrySet()) {
            Column target = entry.getValue(), actual = have.get(entry.getKey());
            if (target.table.equals("flyway_schema_history") || target.table.equals("pre_trip_inspections")) continue;
            if (!tables(db).contains(target.table)) continue;
            boolean enumExtension = actual != null && actual.nullable == target.nullable
                && actual.type.startsWith("enum(") && target.type.startsWith("enum(");
            boolean widening = actual != null && actual.nullable == target.nullable
                && actual.type.startsWith("varchar(") && target.type.startsWith("varchar(")
                && Integer.parseInt(target.type.replaceAll("\\D", "")) >= Integer.parseInt(actual.type.replaceAll("\\D", ""));
            boolean allowed = actual != null && (compatible(actual, target) || enumExtension || widening);
            if (!allowed && (actual != null || !target.nullable)) {
                try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + quoted(target.table))) {
                    rows.next(); if (rows.getLong(1) > 0) throw new IllegalStateException("Unsafe existing data change requires review: " + entry.getKey());
                }
            }
        }
        String indexes = "SELECT table_name,index_name,GROUP_CONCAT(CONCAT('`',column_name,'`') ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND non_unique=0 GROUP BY table_name,index_name";
        try (Statement statement = expected.createStatement(); ResultSet rows = statement.executeQuery(indexes)) {
            while (rows.next()) {
                String table = rows.getString(1), name = rows.getString(2), columns = rows.getString(3);
                if (table.equals("flyway_schema_history") || table.equals("pre_trip_inspections")) continue;
                long conflicts = duplicateGroups(db, table, columns);
                if (conflicts > 0 && !permittedLegacyIndex(table, name, columns))
                    throw new IllegalStateException("Existing duplicate records need an explicit preservation decision: " + table + "." + name + " groups=" + conflicts);
            }
        }
        List<ForeignKey> actualKeys = foreignKeyDetails(db);
        for (ForeignKey key : foreignKeyDetails(expected)) {
            if (key.table.equals("pre_trip_inspections")) continue;
            if (actualKeys.stream().anyMatch(old -> old.table.equals(key.table) && old.columns.equals(key.columns)
                    && !old.signature().equals(key.signature())))
                throw new IllegalStateException("Existing foreign-key rule needs review: " + key.table + "." + key.name);
            checkOrphans(db, key, have);
        }
    }
    record ForeignKey(String table, String name, String columns, String referencedTable, String referencedColumns,
            String deleteRule, String updateRule) {
        String signature() { return table + ":" + columns + ":" + referencedTable + ":" + referencedColumns + ":" + normalizeRule(deleteRule) + ":" + normalizeRule(updateRule); }
    }
    static String normalizeRule(String rule) { return rule.equals("NO ACTION") ? "RESTRICT" : rule; }
    List<ForeignKey> foreignKeyDetails(Connection db) throws SQLException {
        List<ForeignKey> result = new ArrayList<>();
        String sql = "SELECT k.table_name,k.constraint_name,GROUP_CONCAT(CONCAT('`',k.column_name,'`') ORDER BY k.ordinal_position),k.referenced_table_name,GROUP_CONCAT(CONCAT('`',k.referenced_column_name,'`') ORDER BY k.ordinal_position),r.delete_rule,r.update_rule FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name WHERE k.table_schema=DATABASE() AND k.referenced_table_name IS NOT NULL GROUP BY k.table_name,k.constraint_name,k.referenced_table_name,r.delete_rule,r.update_rule";
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) result.add(new ForeignKey(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7)));
        }
        return result;
    }
    void checkOrphans(Connection db, ForeignKey key, Map<String, Column> have) throws SQLException {
        String[] columns = key.columns.split(","), references = key.referencedColumns.split(",");
        if (Arrays.stream(columns).anyMatch(column -> !have.containsKey(key.table + "." + column.replace("`", "")))) return;
        List<String> joins = new ArrayList<>(), notNull = new ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            joins.add("c." + columns[i] + "=p." + references[i]);
            notNull.add("c." + columns[i] + " IS NOT NULL");
        }
        try (Statement statement = db.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT COUNT(*) FROM " + quoted(key.table) + " c LEFT JOIN " + quoted(key.referencedTable) + " p ON "
                + String.join(" AND ", joins) + " WHERE " + String.join(" AND ", notNull) + " AND p." + references[0] + " IS NULL")) {
            rows.next(); if (rows.getLong(1) > 0) throw new IllegalStateException("Dangling foreign-key references require review: " + key.table + "." + key.name + " count=" + rows.getLong(1));
        }
    }
    void copyForeignKeys(Connection expected, Connection db, List<String> changes) throws Exception {
        List<ForeignKey> actual = foreignKeyDetails(db);
        Set<String> signatures = new HashSet<>(), names = new HashSet<>();
        for (ForeignKey key : actual) { signatures.add(key.signature()); names.add(key.name); }
        for (ForeignKey key : foreignKeyDetails(expected)) {
            if (signatures.contains(key.signature())) continue;
            if (actual.stream().anyMatch(old -> old.table.equals(key.table) && old.columns.equals(key.columns)))
                throw new IllegalStateException("Will not silently change an existing foreign-key rule: " + key.table + "." + key.name);
            checkOrphans(db, key, columns(db));
            String name = key.name;
            for (int suffix = 1; names.contains(name); suffix++) {
                String tail = "_major_" + suffix;
                name = key.name.substring(0, Math.min(key.name.length(), 64 - tail.length())) + tail;
            }
            change(db, changes, "ALTER TABLE " + quoted(key.table) + " ADD CONSTRAINT " + quoted(name)
                + " FOREIGN KEY (" + key.columns + ") REFERENCES " + quoted(key.referencedTable) + " (" + key.referencedColumns
                + ") ON DELETE " + key.deleteRule + " ON UPDATE " + key.updateRule);
            signatures.add(key.signature()); names.add(name);
        }
    }
    void verifySchema(String database) throws Exception {
        try (Connection expected = connect(reference); Connection actual = connect(database)) {
            Map<String, Column> wanted = columns(expected), have = columns(actual);
            for (var entry : wanted.entrySet()) {
                if (entry.getValue().table.equals("flyway_schema_history")) continue;
                if (have.get(entry.getKey()) == null || !compatible(have.get(entry.getKey()), entry.getValue()))
                    throw new IllegalStateException("Schema mismatch: " + entry.getKey());
            }
            Set<String> actualForeignKeys = foreignKeys(actual);
            if (!actualForeignKeys.containsAll(foreignKeys(expected))) throw new IllegalStateException("Required foreign keys missing");
            String indexes = "SELECT table_name,index_name,non_unique,GROUP_CONCAT(CONCAT('`',column_name,'`') ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() GROUP BY table_name,index_name,non_unique";
            Set<String> actualIndexes = new HashSet<>();
            try (Statement statement = actual.createStatement(); ResultSet rows = statement.executeQuery(indexes)) {
                while (rows.next()) actualIndexes.add(rows.getString(1) + ":" + rows.getInt(3) + ":" + rows.getString(4));
            }
            try (Statement statement = expected.createStatement(); ResultSet rows = statement.executeQuery(indexes)) {
                while (rows.next()) {
                    String table = rows.getString(1), name = rows.getString(2), columns = rows.getString(4);
                    if (table.equals("flyway_schema_history")) continue;
                    if (actualIndexes.contains(table + ":" + rows.getInt(3) + ":" + columns)) continue;
                    if (permittedLegacyIndex(table, name, columns) && duplicateGroups(actual, table, columns) > 0) continue;
                    throw new IllegalStateException("Required index missing: " + table + "." + name);
                }
            }
        }
        System.out.println("SCHEMA VERIFIED " + database);
    }
    Set<String> foreignKeys(Connection db) throws SQLException {
        Set<String> result = new HashSet<>();
        for (ForeignKey key : foreignKeyDetails(db)) result.add(key.signature());
        return result;
    }
    void change(Connection db, List<String> changes, String sql) throws Exception {
        Files.writeString(backups.resolve(db.getCatalog() + "-changes.sql"), sql + ";\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        execute(db, sql);
        changes.add(sql);
        System.out.println("CHANGE " + sql.split("\\n", 2)[0]);
    }
    static String quoted(String name) { return "`" + name.replace("`", "``") + "`"; }
    static void execute(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) { statement.execute(sql); }
    }
}
