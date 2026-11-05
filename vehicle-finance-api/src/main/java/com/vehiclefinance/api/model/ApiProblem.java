package com.vehiclefinance.api.model;

import java.util.List;
import java.util.Map;

public class ApiProblem {
    private String title;
    private String detail;
    private int status;
    private String code;
    private String correlationId;
    private List<Map<String, String>> fieldErrors;

    public ApiProblem() {
    }

    public ApiProblem(String title, String detail, int status, String code, String correlationId,
                      List<Map<String, String>> fieldErrors) {
        this.title = title;
        this.detail = detail;
        this.status = status;
        this.code = code;
        this.correlationId = correlationId;
        this.fieldErrors = fieldErrors;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public List<Map<String, String>> getFieldErrors() {
        return fieldErrors;
    }

    public void setFieldErrors(List<Map<String, String>> fieldErrors) {
        this.fieldErrors = fieldErrors;
    }
}
