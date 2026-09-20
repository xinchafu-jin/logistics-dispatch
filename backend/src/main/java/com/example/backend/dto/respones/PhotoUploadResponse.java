package com.example.backend.dto.respones;

public class PhotoUploadResponse {

    private String url;

    public PhotoUploadResponse() { }

    public PhotoUploadResponse(String url) {
        this.url = url;
    }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
