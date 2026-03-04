package com.inductiveautomation.logixemulator.gateway;

import com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry of active Logix PLC Emulator device instances.
 *
 * Abstracts the static {@code SimulatorModuleHook} registry so that
 * {@link com.inductiveautomation.logixemulator.gateway.web.DeviceFileManager}
 * and the web controllers can be constructed with an injected instance —
 * making them independently testable without Ignition infrastructure.
 */
public interface DeviceRegistry {

    /**
     * Find a registered device by its name.
     *
     * @param name Device name (case-sensitive)
     * @return The device, or empty if no device is registered with this name
     */
    Optional<LogixEmulatorDevice> findDeviceByName(String name);

    /**
     * Return all currently registered devices.
     *
     * @return Live (non-snapshot) collection of registered devices
     */
    Collection<LogixEmulatorDevice> getRegisteredDevices();

    /**
     * Register a device instance when it starts up.
     *
     * @param name   Device name
     * @param device Device instance
     */
    void registerDevice(String name, LogixEmulatorDevice device);

    /**
     * Unregister a device instance when it shuts down.
     *
     * @param name Device name
     */
    void unregisterDevice(String name);
}
