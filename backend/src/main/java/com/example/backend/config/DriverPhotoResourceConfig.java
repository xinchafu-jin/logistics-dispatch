package com.example.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class DriverPhotoResourceConfig implements WebMvcConfigurer {

    private final String resourceLocation;

    public DriverPhotoResourceConfig(
            @Value("${app.storage.driver-photos-dir:uploads/driver-photos}") String storageDirectory
    ) {
        String location = Path.of(storageDirectory)
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();
        this.resourceLocation = location.endsWith("/") ? location : location + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/driver-photos/**")
                .addResourceLocations(resourceLocation)
                .setCachePeriod(3600);
    }
}
