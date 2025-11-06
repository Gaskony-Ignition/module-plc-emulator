package com.inductiveautomation.plcsimulator.gateway;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.common.script.hints.PropertiesFileDocProvider;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProvider;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProviderConfiguration;
import com.inductiveautomation.plcsimulator.gateway.records.PLCSimSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway hook for the PLC Simulator module.
 * This is the entry point for the module on the Gateway scope.
 */
public class GatewayHook extends AbstractGatewayModuleHook {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private GatewayContext context;
    private ManagedTagProvider tagProvider;
    private PLCTagManager tagManager;
    private ParserService parserService;
    private SimulationEngine simulationEngine;
    private SettingsManager settingsManager;

    @Override
    public void setup(GatewayContext context) {
        this.context = context;
        logger.info("PLC Simulator module setup");

        try {
            // Register persistent record schema
            context.getSchemaUpdater().updatePersistentRecords(PLCSimSettings.META);
            logger.info("Registered PLCSimSettings persistent record");

            // Initialize settings manager and load settings
            settingsManager = new SettingsManager(context);
            settingsManager.loadSettings();

            // Get settings
            PLCSimSettings settings = settingsManager.getSettings();

            // Create managed tag provider configuration using persisted settings
            ManagedTagProviderConfiguration config = ManagedTagProviderConfiguration
                .builder("PLCSimulator")
                .persistTags(settings.getPersistTags())
                .allowTagCustomization(settings.getAllowTagCustomization())
                .build();

            // Create or get the managed tag provider
            tagProvider = context.getTagManager().getOrCreateManagedProvider(config);
            logger.info("ManagedTagProvider 'PLCSimulator' created/retrieved");

            // Initialize tag manager
            tagManager = new PLCTagManager(tagProvider, logger);

        } catch (Exception e) {
            logger.error("Error during module setup", e);
        }
    }

    @Override
    public void startup(LicenseState licenseState) {
        logger.info("PLC Simulator module starting...");

        PLCSimSettings settings = settingsManager.getSettings();

        // Start parser service with configured host and port
        try {
            parserService = new ParserService(logger, settings.getParserHost(), settings.getParserPort());
            parserService.start();
            logger.info("Parser service started on {}:{}", settings.getParserHost(), settings.getParserPort());
        } catch (Exception e) {
            logger.error("Failed to start parser service", e);
        }

        // TODO: Mount file upload servlet (requires proper WebResourceManager setup)
        // For now, use scripting functions instead: system.plcsim.loadL5K(filePath)

        if (tagProvider != null && tagManager != null) {
            logger.info("Tag provider 'PLCSimulator' is ready");

            // Create sample hierarchical tag structure if enabled in settings
            if (settings.getCreateSampleTags()) {
                createSampleTags();
            }

            // Initialize simulation engine if auto-start is enabled
            if (settings.getAutoStartSimulations()) {
                simulationEngine = new SimulationEngine(tagProvider);
                simulationEngine.start();

                // Connect simulation engine to tag manager for write handler interactions
                tagManager.setSimulationEngine(simulationEngine);

                // Add simulations to sample tags if we created them
                if (settings.getCreateSampleTags()) {
                    configureSampleSimulations();
                }

                // Register write handlers for tag write operations
                if (settings.getCreateSampleTags()) {
                    tagManager.registerDefaultWriteHandlers();
                    logger.info("Registered {} write handlers", tagManager.getWriteHandlerCount());
                }

                // Register simulation engine to run at configured interval
                int updateInterval = settings.getSimulationUpdateInterval();
                context.getExecutionManager().register(
                    getClass().getName(),
                    "SimulationEngine",
                    simulationEngine,
                    updateInterval
                );

                logger.info("Simulation engine started with {} simulations (update interval: {}ms)",
                    simulationEngine.getSimulationCount(), updateInterval);
            } else {
                logger.info("Simulation engine auto-start disabled in settings");
            }
        }

        logger.info("PLC Simulator module started successfully");
    }


    /**
     * Create sample tags to demonstrate hierarchical structure.
     * This creates a structure like: [PLCSimulator]Controller/Global/Motor1_Speed
     */
    private void createSampleTags() {
        try {
            logger.info("Creating sample PLC tag structure");

            // Example controller-scoped tags
            tagManager.createAtomicTag("Controller/Global/Motor1_Speed", DataType.Float4, 0.0f);
            tagManager.createAtomicTag("Controller/Global/Motor1_Running", DataType.Boolean, false);
            tagManager.createAtomicTag("Controller/Global/Tank1_Level", DataType.Float4, 50.0f);
            tagManager.createAtomicTag("Controller/Global/Conveyor_Position", DataType.Int4, 0);

            // Example program-scoped tags
            tagManager.createAtomicTag("Program/MainProgram/Counter", DataType.Int4, 0);
            tagManager.createAtomicTag("Program/MainProgram/Timer_Elapsed", DataType.Float4, 0.0f);
            tagManager.createAtomicTag("Program/MainProgram/Alarm_Active", DataType.Boolean, false);

            tagManager.createAtomicTag("Program/SafetyProgram/EmergencyStop", DataType.Boolean, false);
            tagManager.createAtomicTag("Program/SafetyProgram/DoorOpen", DataType.Boolean, false);

            logger.info("Sample tags created successfully");

        } catch (Exception e) {
            logger.error("Error creating sample tags", e);
        }
    }

    /**
     * Configure simulations for sample tags to demonstrate dynamic behavior.
     */
    private void configureSampleSimulations() {
        if (simulationEngine == null) {
            return;
        }

        try {
            logger.info("Configuring sample simulations");

            // Motor speed: Sine wave between 0 and 100 RPM, 30 second period
            simulationEngine.addSimulation(
                "Controller/Global/Motor1_Speed",
                SimulationEngine.SimulationType.SINE,
                SimulationEngine.SimulationConfig.sine(0, 100, 30)
            );

            // Motor running: Toggle every 10 seconds
            simulationEngine.addSimulation(
                "Controller/Global/Motor1_Running",
                SimulationEngine.SimulationType.TOGGLE,
                SimulationEngine.SimulationConfig.toggle(10)
            );

            // Tank level: Ramp from 0 to 100, 60 second period
            simulationEngine.addSimulation(
                "Controller/Global/Tank1_Level",
                SimulationEngine.SimulationType.RAMP,
                SimulationEngine.SimulationConfig.ramp(0, 100, 60)
            );

            // Conveyor position: Random integer 0-1000
            simulationEngine.addSimulation(
                "Controller/Global/Conveyor_Position",
                SimulationEngine.SimulationType.RANDOM,
                SimulationEngine.SimulationConfig.random(0, 1000, true)
            );

            // Counter: Ramp from 0 to 100, 20 second period
            simulationEngine.addSimulation(
                "Program/MainProgram/Counter",
                SimulationEngine.SimulationType.RAMP,
                SimulationEngine.SimulationConfig.ramp(0, 100, 20)
            );

            // Timer: Sine wave for elapsed time simulation
            simulationEngine.addSimulation(
                "Program/MainProgram/Timer_Elapsed",
                SimulationEngine.SimulationType.SINE,
                SimulationEngine.SimulationConfig.sine(0, 10, 15)
            );

            // Alarm: Pulse every 30 seconds (10% duty cycle)
            simulationEngine.addSimulation(
                "Program/MainProgram/Alarm_Active",
                SimulationEngine.SimulationType.PULSE,
                SimulationEngine.SimulationConfig.pulse(30, 0.1)
            );

            // Emergency stop: Pulse (5% duty cycle, 60s period)
            simulationEngine.addSimulation(
                "Program/SafetyProgram/EmergencyStop",
                SimulationEngine.SimulationType.PULSE,
                SimulationEngine.SimulationConfig.pulse(60, 0.05)
            );

            // Door open: Toggle every 20 seconds
            simulationEngine.addSimulation(
                "Program/SafetyProgram/DoorOpen",
                SimulationEngine.SimulationType.TOGGLE,
                SimulationEngine.SimulationConfig.toggle(20)
            );

            logger.info("Configured {} simulations", simulationEngine.getSimulationCount());

        } catch (Exception e) {
            logger.error("Error configuring simulations", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("PLC Simulator module shutting down");

        try {
            // Unregister simulation engine
            context.getExecutionManager().unRegister(
                getClass().getName(),
                "SimulationEngine"
            );

            // Stop simulation engine
            if (simulationEngine != null) {
                simulationEngine.stop();
            }

            // Stop parser service
            if (parserService != null) {
                parserService.stop();
            }

            // Shutdown tag provider
            if (tagProvider != null) {
                tagProvider.shutdown(true);
                logger.info("Tag provider shutdown complete");
            }
        } catch (Exception e) {
            logger.error("Error during shutdown", e);
        }

        logger.info("PLC Simulator module shutdown complete");
    }

    @Override
    public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);

        logger.info("Registering PLC Simulator scripting functions");

        // Create implementation
        PLCSimScriptFunctionsImpl scriptFunctions = new PLCSimScriptFunctionsImpl(this);

        // Register under system.plcsim namespace
        manager.addScriptModule(
            PLCSimScriptFunctionsImpl.SCRIPT_MODULE_NAME,
            scriptFunctions,
            new PropertiesFileDocProvider()
        );

        logger.info("Scripting functions registered: {}", PLCSimScriptFunctionsImpl.SCRIPT_MODULE_NAME);
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    /**
     * Get the tag manager for external access (scripting, servlets, etc.)
     */
    public PLCTagManager getTagManager() {
        return tagManager;
    }

    /**
     * Get the parser service for external access.
     */
    public ParserService getParserService() {
        return parserService;
    }

    /**
     * Get the simulation engine for external access.
     */
    public SimulationEngine getSimulationEngine() {
        return simulationEngine;
    }

    /**
     * Get the settings manager for external access.
     */
    public SettingsManager getSettingsManager() {
        return settingsManager;
    }
}
