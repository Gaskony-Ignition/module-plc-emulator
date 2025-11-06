package com.inductiveautomation.plcsimulator.gateway.records;

import com.inductiveautomation.ignition.gateway.localdb.persistence.*;
import simpleorm.dataset.SFieldFlags;

/**
 * Persistent record for storing PLC Simulator module settings in the Gateway database.
 * This allows configuration to persist across Gateway restarts.
 */
public class PLCSimSettings extends PersistentRecord {

    // Define the record metadata
    public static final RecordMeta<PLCSimSettings> META = new RecordMeta<>(
        PLCSimSettings.class,
        "PLCSimSettings"
    );

    // Unique identifier
    public static final IdentityField Id = new IdentityField(META);

    // Parser service settings
    public static final StringField ParserHost = new StringField(META, "ParserHost", SFieldFlags.SMANDATORY);
    public static final IntField ParserPort = new IntField(META, "ParserPort", SFieldFlags.SMANDATORY);

    // Tag provider settings
    public static final BooleanField PersistTags = new BooleanField(META, "PersistTags");
    public static final BooleanField AllowTagCustomization = new BooleanField(META, "AllowTagCustomization");

    // Simulation settings
    public static final BooleanField AutoStartSimulations = new BooleanField(META, "AutoStartSimulations");
    public static final IntField SimulationUpdateInterval = new IntField(META, "SimulationUpdateInterval");

    // Create sample tags on startup
    public static final BooleanField CreateSampleTags = new BooleanField(META, "CreateSampleTags");

    // Last loaded file path (for reference)
    public static final StringField LastLoadedFile = new StringField(META, "LastLoadedFile");

    static {
        ParserHost.setDefault("localhost");
        ParserPort.setDefault(5000);
        PersistTags.setDefault(false);
        AllowTagCustomization.setDefault(true);
        AutoStartSimulations.setDefault(true);
        SimulationUpdateInterval.setDefault(1000); // milliseconds
        CreateSampleTags.setDefault(true);
        LastLoadedFile.setDefault("");
    }

    @Override
    public RecordMeta<?> getMeta() {
        return META;
    }

    // Getters
    public String getParserHost() {
        return getString(ParserHost);
    }

    public int getParserPort() {
        return getInt(ParserPort);
    }

    public boolean getPersistTags() {
        return getBoolean(PersistTags);
    }

    public boolean getAllowTagCustomization() {
        return getBoolean(AllowTagCustomization);
    }

    public boolean getAutoStartSimulations() {
        return getBoolean(AutoStartSimulations);
    }

    public int getSimulationUpdateInterval() {
        return getInt(SimulationUpdateInterval);
    }

    public boolean getCreateSampleTags() {
        return getBoolean(CreateSampleTags);
    }

    public String getLastLoadedFile() {
        return getString(LastLoadedFile);
    }

    // Setters
    public void setParserHost(String host) {
        setString(ParserHost, host);
    }

    public void setParserPort(int port) {
        setInt(ParserPort, port);
    }

    public void setPersistTags(boolean persist) {
        setBoolean(PersistTags, persist);
    }

    public void setAllowTagCustomization(boolean allow) {
        setBoolean(AllowTagCustomization, allow);
    }

    public void setAutoStartSimulations(boolean autoStart) {
        setBoolean(AutoStartSimulations, autoStart);
    }

    public void setSimulationUpdateInterval(int interval) {
        setInt(SimulationUpdateInterval, interval);
    }

    public void setCreateSampleTags(boolean create) {
        setBoolean(CreateSampleTags, create);
    }

    public void setLastLoadedFile(String filePath) {
        setString(LastLoadedFile, filePath);
    }
}
