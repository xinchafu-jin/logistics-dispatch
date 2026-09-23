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

    public DriverPhotoResourceConfig(
            @Value("${app.storage.driver-photos-dir:uploads/driver-photos}") String storageDirectory,
            @Value("${app.storage.delivery-photos-dir:uploads/delivery-photos}") String deliveryStorageDirectory
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
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/driver-photos/**")
                .addResourceLocations(driverPhotoLocation)
                .setCachePeriod(3600);
        registry.addResourceHandler("/uploads/delivery-photos/**")
                .addResourceLocations(deliveryPhotoLocation)
                .setCachePeriod(3600);
    }
}
