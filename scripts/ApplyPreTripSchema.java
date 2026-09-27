import java.nio.file.*;
import java.sql.*;
import java.util.Properties;
import java.io.Reader;

/** 僅套用新增的安全檢查表。用於已停用 Flyway 的本機舊開發資料庫，不修改任何既有資料或版本歷史。 */
class ApplyPreTripSchema {
    public static void main(String[] args) throws Exception {
        Path backend = Path.of(args[0]).toAbsolutePath().normalize();
        Properties env = new Properties();
        try (Reader reader = Files.newBufferedReader(backend.resolve(".env"))) { env.load(reader); }
        String url = env.getProperty("DB_URL", "");
        if (!url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1)(:|/).*"))
            throw new IllegalStateException("此工具只允許本機 MySQL；未執行任何 SQL");
        try (Connection connection = DriverManager.getConnection(url,env.getProperty("DB_USER"),env.getProperty("DB_PASSWORD"))) {
            try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(),null,"pre_trip_inspections",new String[]{"TABLE"})) {
                if (tables.next()) { System.out.println("pre_trip_inspections already exists; no changes"); return; }
            }
            String sql = Files.readString(backend.resolve("src/main/resources/db/migration/drivers/V19__pre_trip_inspections.sql"));
            try (Statement statement = connection.createStatement()) { statement.execute(sql); }
            System.out.println("Created pre_trip_inspections only; existing data unchanged");
        }
    }
}
