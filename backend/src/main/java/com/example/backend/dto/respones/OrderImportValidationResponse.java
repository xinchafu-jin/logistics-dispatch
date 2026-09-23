package com.example.backend.dto.respones;

import java.util.ArrayList;
import java.util.List;

public class OrderImportValidationResponse {

    private boolean valid;
    private int totalRows;
    private int validRows;
    private List<RowError> errors = new ArrayList<>();

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getValidRows() { return validRows; }
    public void setValidRows(int validRows) { this.validRows = validRows; }
    public List<RowError> getErrors() { return errors; }
    public void setErrors(List<RowError> errors) { this.errors = errors; }

    public static class RowError {
        private int rowNumber;
        private String field;
        private String message;

        public RowError() { }

        public RowError(int rowNumber, String field, String message) {
            this.rowNumber = rowNumber;
            this.field = field;
            this.message = message;
        }

        public int getRowNumber() { return rowNumber; }
        public void setRowNumber(int rowNumber) { this.rowNumber = rowNumber; }
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
