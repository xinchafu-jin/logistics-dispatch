package com.example.backend.service;

import com.example.backend.constants.FuelType;
import com.example.backend.dao.FuelPriceHistoryDAO;
import com.example.backend.dispatch.CpcFuelPriceClient;
import com.example.backend.dto.respones.FuelPriceResponse;
import com.example.backend.entity.FuelPriceHistoryEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 將中油 OpenData 的超級柴油牌價同步到本地油價歷史。 */
@Service
public class CpcFuelPriceSyncService {

    private static final FuelType FUEL_TYPE = FuelType.DIESEL;
    private static final String PRODUCT_NAME = "超級柴油";
    private static final String SOURCE = "CPC_OPEN_DATA_SUPER_DIESEL";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final CpcFuelPriceClient cpcFuelPriceClient;
    private final FuelPriceHistoryDAO fuelPriceHistoryDAO;

    public CpcFuelPriceSyncService(
            CpcFuelPriceClient cpcFuelPriceClient,
            FuelPriceHistoryDAO fuelPriceHistoryDAO
    ) {
        this.cpcFuelPriceClient = cpcFuelPriceClient;
        this.fuelPriceHistoryDAO = fuelPriceHistoryDAO;
    }

    /**
     * 取得中油目前的超級柴油牌價並保存。
     * 同一油品、同一生效時間只保留一筆；中油修正牌價時會更新既有紀錄。
     */
    @Transactional
    public synchronized FuelPriceResponse syncSuperDieselPrice() {
        JsonNode root = cpcFuelPriceClient.getPrices();
        JsonNode diesel = findSuperDiesel(root);

        BigDecimal pricePerLiter = readPrice(diesel);
        LocalDate effectiveDate = parseTaiwanDate(requiredText(diesel, "牌價生效日期"));
        LocalDateTime effectiveFrom = effectiveDate.atStartOfDay();
        LocalDateTime fetchedAt = LocalDateTime.now(TAIPEI);

        FuelPriceHistoryEntity entity = fuelPriceHistoryDAO
                .findByFuelTypeAndEffectiveFrom(FUEL_TYPE, effectiveFrom)
                .orElseGet(FuelPriceHistoryEntity::new);
        entity.setFuelType(FUEL_TYPE);
        entity.setPricePerLiter(pricePerLiter);
        entity.setEffectiveFrom(effectiveFrom);
        entity.setSource(SOURCE);
        entity.setFetchedAt(fetchedAt);

        return toResponse(fuelPriceHistoryDAO.save(entity));
    }

    private JsonNode findSuperDiesel(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new IllegalStateException("中油牌價回應格式錯誤：預期為陣列");
        }
        for (JsonNode item : root) {
            String productName = item.path("產品名稱").asString("").trim();
            String unit = item.path("計價單位").asString("").replaceAll("\\s+", "");
            if (PRODUCT_NAME.equals(productName) && unit.contains("元/公升")) {
                return item;
            }
        }
        throw new IllegalStateException("中油牌價回應中找不到超級柴油每公升牌價");
    }

    private BigDecimal readPrice(JsonNode diesel) {
        JsonNode priceNode = diesel.path("參考牌價_金額");
        if (!priceNode.isNumber() && !priceNode.isString()) {
            throw new IllegalStateException("中油超級柴油牌價缺少參考牌價金額");
        }
        try {
            BigDecimal price = priceNode.isNumber()
                    ? priceNode.decimalValue()
                    : new BigDecimal(priceNode.stringValue());
            if (price.signum() <= 0) {
                throw new IllegalStateException("中油超級柴油牌價必須大於 0");
            }
            return price;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("中油超級柴油牌價不是有效數字", exception);
        }
    }

    /** 將中油民國日期（例如 1150914）轉為西元日期。 */
    private LocalDate parseTaiwanDate(String value) {
        String digits = value.trim();
        if (!digits.matches("\\d{6,7}")) {
            throw new IllegalStateException("中油牌價生效日期格式錯誤：" + value);
        }
        try {
            int yearLength = digits.length() - 4;
            int year = Integer.parseInt(digits.substring(0, yearLength)) + 1911;
            int month = Integer.parseInt(digits.substring(yearLength, yearLength + 2));
            int day = Integer.parseInt(digits.substring(yearLength + 2));
            return LocalDate.of(year, month, day);
        } catch (DateTimeException | NumberFormatException exception) {
            throw new IllegalStateException("中油牌價生效日期無效：" + value, exception);
        }
    }

    private String requiredText(JsonNode node, String fieldName) {
        String value = node.path(fieldName).asString("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("中油超級柴油牌價缺少欄位：" + fieldName);
        }
        return value;
    }

    private FuelPriceResponse toResponse(FuelPriceHistoryEntity entity) {
        return new FuelPriceResponse(
                entity.getId(),
                entity.getFuelType(),
                PRODUCT_NAME,
                entity.getPricePerLiter(),
                entity.getEffectiveFrom(),
                entity.getSource(),
                entity.getFetchedAt()
        );
    }
}
