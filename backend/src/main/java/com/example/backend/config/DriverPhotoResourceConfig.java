package com.example.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class DriverPhotoResourceConfig implements WebMvcConfigurer {

    private final String driverPhotoLocation;
    private final String deliveryPhotoLocation;
    private final String mileagePhotoLocation;

    public DriverPhotoResourceConfig(
            @Value("${app.storage.driver-photos-dir:uploads/driver-photos}") String storageDirectory,
            @Value("${app.storage.delivery-photos-dir:uploads/delivery-photos}") String deliveryStorageDirectory,
            @Value("${app.storage.mileage-photos-dir:uploads/mileage-photos}") String mileageStorageDirectory
    ) {
        String location = Path.of(storageDirectory)
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();
        this.driverPhotoLocation = location.endsWith("/") ? location : location + "/";
        String deliveryLocation = Path.of(deliveryStorageDirectory)
                .toAbsolutePath().normalize().toUri().toString();
        this.deliveryPhotoLocation = deliveryLocation.endsWith("/")
                ? deliveryLocation : deliveryLocation + "/";
        String mileageLocation = Path.of(mileageStorageDirectory)
                .toAbsolutePath().normalize().toUri().toString();
        this.mileagePhotoLocation = mileageLocation.endsWith("/") ? mileageLocation : mileageLocation + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/driver-photos/**")
                .addResourceLocations(driverPhotoLocation)
                .setCachePeriod(3600);
        registry.addResourceHandler("/uploads/delivery-photos/**")
                .addResourceLocations(deliveryPhotoLocation)
                .setCachePeriod(3600);
        registry.addResourceHandler("/uploads/mileage-photos/**")
                .addResourceLocations(mileagePhotoLocation)
                .setCachePeriod(3600);
    }
}
