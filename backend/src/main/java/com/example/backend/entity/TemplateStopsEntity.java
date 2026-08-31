package com.example.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "template_stops")
public class TemplateStopsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long templateRouteId;

    @Column(nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Integer sequence;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTemplateRouteId() {
        return templateRouteId;
    }

    public void setTemplateRouteId(Long templateRouteId) {
        this.templateRouteId = templateRouteId;
    }

    public Long getStoreId() {
        return storeId;
    }

    public void setStoreId(Long storeId) {
        this.storeId = storeId;
    }

    public Integer getSequence() {
        return sequence;
    }

    public void setSequence(Integer sequence) {
        this.sequence = sequence;
    }
}