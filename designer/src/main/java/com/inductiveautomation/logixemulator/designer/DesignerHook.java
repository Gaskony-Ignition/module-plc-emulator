package com.inductiveautomation.logixemulator.designer;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Designer hook for the Logix PLC Emulator module.
 * This is the entry point for the module on the Designer scope.
 */
public class DesignerHook extends AbstractDesignerModuleHook {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private DesignerContext context;

    @Override
    public void startup(DesignerContext context, LicenseState activationState) {
        this.context = context;
        logger.info("Logix PLC Emulator module started in Designer");
    }

    @Override
    public void shutdown() {
        logger.info("Logix PLC Emulator module shutdown in Designer");
    }
}
