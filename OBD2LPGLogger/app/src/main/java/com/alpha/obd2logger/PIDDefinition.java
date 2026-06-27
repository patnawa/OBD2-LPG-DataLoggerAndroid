package com.alpha.obd2logger;

public final class PIDDefinition {
    private final String name;
    private final String service;
    private final String pidHex;
    private final String unit;
    private final String formula;
    private final double minVal;
    private final double maxVal;
    private final boolean lpgCritical;
    private final int dataBytes;
    private final boolean dashboard;

    public PIDDefinition(String name, String service, String pidHex, String unit, String formula,
                         double minVal, double maxVal, boolean lpgCritical, int dataBytes) {
        this(name, service, pidHex, unit, formula, minVal, maxVal, lpgCritical, dataBytes, false);
    }

    public PIDDefinition(String name, String service, String pidHex, String unit, String formula,
                         double minVal, double maxVal, boolean lpgCritical, int dataBytes, boolean dashboard) {
        this.name = name;
        this.service = service;
        this.pidHex = pidHex;
        this.unit = unit;
        this.formula = formula;
        this.minVal = minVal;
        this.maxVal = maxVal;
        this.lpgCritical = lpgCritical;
        this.dataBytes = dataBytes;
        this.dashboard = dashboard;
    }

    public String getName() {
        return name;
    }

    public String getService() {
        return service;
    }

    public String getPidHex() {
        return pidHex;
    }

    public String getUnit() {
        return unit;
    }

    public String getFormula() {
        return formula;
    }

    public double getMinVal() {
        return minVal;
    }

    public double getMaxVal() {
        return maxVal;
    }

    public boolean isLpgCritical() {
        return lpgCritical;
    }

    public boolean isDashboard() {
        return dashboard;
    }

    public int getDataBytes() {
        return dataBytes;
    }

    public String key() {
        return service + "_" + pidHex;
    }

    public static PIDDefinition findByKey(String key) {
        if (key == null) return null;
        for (PIDDefinition pid : PIDCatalogue.getAll()) {
            if (key.equals(pid.key())) return pid;
        }
        return null;
    }
}
