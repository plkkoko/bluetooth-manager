package org.sputnikdev.bluetooth.manager.impl;

/*-
 * #%L
 * org.sputnikdev:bluetooth-manager
 * %%
 * Copyright (C) 2017 Sputnik Dev
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.AdapterDiscoveryListener;
import org.sputnikdev.bluetooth.manager.AdapterGovernor;
import org.sputnikdev.bluetooth.manager.BluetoothGovernor;
import org.sputnikdev.bluetooth.manager.BluetoothManager;
import org.sputnikdev.bluetooth.manager.CharacteristicGovernor;
import org.sputnikdev.bluetooth.manager.CombinedGovernor;
import org.sputnikdev.bluetooth.manager.DeviceDiscoveryListener;
import org.sputnikdev.bluetooth.manager.DeviceGovernor;
import org.sputnikdev.bluetooth.manager.DiscoveredAdapter;
import org.sputnikdev.bluetooth.manager.DiscoveredDevice;
import org.sputnikdev.bluetooth.manager.ManagerListener;
import org.sputnikdev.bluetooth.manager.transport.BluetoothObject;
import org.sputnikdev.bluetooth.manager.transport.BluetoothObjectFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Thread safe bluetooth manager implementation class.
 * @author Vlad Kolotov
 */
class BluetoothManagerImpl implements BluetoothManager {

    static final int REFRESH_RATE_SEC = 5;
    static final int DISCOVERY_RATE_SEC = 10;

    private Logger logger = LoggerFactory.getLogger(BluetoothManagerImpl.class);

    private final Map<String, BluetoothObjectFactory> factories = new ConcurrentHashMap<>();

    private final ScheduledExecutorService discoveryScheduler = Executors.newScheduledThreadPool(6);
    private final ScheduledExecutorService governorScheduler = Executors.newScheduledThreadPool(5);
    private final Map<String, ScheduledFuture<?>> adapterDiscoveryFutures = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> deviceDiscoveryFutures = new ConcurrentHashMap<>();
    private final Map<URL, ScheduledFuture<?>> governorFutures = new HashMap<>();

    private final Set<DeviceDiscoveryListener> deviceDiscoveryListeners = new CopyOnWriteArraySet<>();
    private final Set<AdapterDiscoveryListener> adapterDiscoveryListeners = new CopyOnWriteArraySet<>();
    private final Set<ManagerListener> managerListeners = new CopyOnWriteArraySet<>();

    private final Map<URL, BluetoothObjectGovernor> governors = new ConcurrentHashMap<>();
    private final Set<DiscoveredDevice> discoveredDevices = new CopyOnWriteArraySet<>();
    private final Set<DiscoveredAdapter> discoveredAdapters = new CopyOnWriteArraySet<>();

    private boolean startDiscovering;
    private int discoveryRate = DISCOVERY_RATE_SEC;
    private int refreshRate = REFRESH_RATE_SEC;
    private boolean rediscover;
    private boolean started;
    private boolean combinedAdapters;
    private boolean combinedDevices = true;

    @Override
    public void start(boolean startDiscovering) {
        if (started || !adapterDiscoveryFutures.isEmpty() || !deviceDiscoveryFutures.isEmpty()
            || !governorFutures.isEmpty()) {
            return;
        }
        this.startDiscovering = startDiscovering;
        synchronized (factories) {
            factories.values().forEach(this::scheduleDiscovery);
        }
        synchronized (governorScheduler) {
            governors.values().forEach(this::scheduleGovernor);
        }
        started = true;
    }

    @Override
    public void registerFactory(BluetoothObjectFactory transport) {
        logger.debug("Register {} transport", transport.getProtocolName());
        synchronized (factories) {
            factories.computeIfAbsent(transport.getProtocolName(), protocolName -> {
                if (started) {
                    scheduleDiscovery(transport);
                }
                return transport;
            });
        }
    }

    @Override
    public void unregisterFactory(BluetoothObjectFactory transport) {
        logger.debug("Unregister {} transport", transport.getProtocolName());
        synchronized (factories) {
            factories.computeIfPresent(transport.getProtocolName(), (protocolName, factory) -> {
                handleObjectFactoryUnregistered(factory);
                return null;
            });
        }
    }

    @Override
    public void stop() {
        cancelAllFutures(false);
        started = false;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    @Override
    public void addDeviceDiscoveryListener(DeviceDiscoveryListener deviceDiscoveryListener) {
        deviceDiscoveryListeners.add(deviceDiscoveryListener);
    }

    @Override
    public void removeDeviceDiscoveryListener(DeviceDiscoveryListener deviceDiscoveryListener) {
        deviceDiscoveryListeners.remove(deviceDiscoveryListener);
    }

    @Override
    public void addAdapterDiscoveryListener(AdapterDiscoveryListener adapterDiscoveryListener) {
        adapterDiscoveryListeners.add(adapterDiscoveryListener);
    }

    @Override
    public void removeAdapterDiscoveryListener(AdapterDiscoveryListener adapterDiscoveryListener) {
        adapterDiscoveryListeners.remove(adapterDiscoveryListener);
    }

    @Override
    public BluetoothGovernor getGovernor(URL url) {
        if (url.isProtocol() || url.isRoot()) {
            return null;
        }
        synchronized (governors) {
            URL protocolLess = url.copyWithProtocol(null);
            if (governors.containsKey(protocolLess)) {
                BluetoothObjectGovernor governor = governors.get(protocolLess);
                if (!governor.isReady()) {
                    update(governor);
                }
                return governor;
            } else {
                BluetoothObjectGovernor governor = createGovernor(protocolLess);
                governors.put(protocolLess, governor);
                init(governor);
                scheduleGovernor(governor);
                return governor;
            }
        }
    }

    @Override
    public void disposeGovernor(URL url) {
        synchronized (governors) {
            URL protocolLess = url.copyWithProtocol(null);
            if (governors.containsKey(protocolLess)) {
                BluetoothObjectGovernor governor = governors.get(protocolLess);
                disposeGovernor(governor);
                governors.remove(protocolLess);
            }
        }
    }

    @Override
    public void disposeDescendantGovernors(URL url) {
        computeForEachDescendantGovernorAndRemove(url, this::disposeGovernor);
    }

    @Override
    public AdapterGovernor getAdapterGovernor(URL url) {
        return (AdapterGovernor) getGovernor(url.getAdapterURL());
    }

    @Override
    public DeviceGovernor getDeviceGovernor(URL url) {
        return (DeviceGovernor) getGovernor(url.getDeviceURL());
    }

    @Override
    public DeviceGovernor getDeviceGovernor(URL url, boolean forceConnect) {
        DeviceGovernor deviceGovernor = getDeviceGovernor(url);
        if (forceConnect) {
            deviceGovernor.setConnectionControl(true);
            if (!deviceGovernor.isReady() || !deviceGovernor.isConnected()) {
                update((BluetoothObjectGovernor) deviceGovernor);
            }
        }
        return deviceGovernor;
    }

    @Override
    public CharacteristicGovernor getCharacteristicGovernor(URL url) {
        return (CharacteristicGovernor) getGovernor(url.getCharacteristicURL());
    }

    @Override
    public CharacteristicGovernor getCharacteristicGovernor(URL url, boolean forceConnect) {
        CharacteristicGovernor characteristicGovernor = getCharacteristicGovernor(url);
        if (forceConnect) {
            DeviceGovernor deviceGovernor = getDeviceGovernor(url, true);
            if (deviceGovernor.isReady() && deviceGovernor.isConnected() && !characteristicGovernor.isReady()) {
                update((BluetoothObjectGovernor) characteristicGovernor);
            }
        }
        return characteristicGovernor;
    }

    @Override
    public void dispose() {
        logger.info("Disposing Bluetooth manager");

        cancelAllFutures(true);

        governorScheduler.shutdown();
        discoveryScheduler.shutdown();

        deviceDiscoveryListeners.clear();
        adapterDiscoveryListeners.clear();

        governors.values().forEach(this::dispose);
        governors.clear();

        factories.clear();

        logger.info("Bluetooth service has been disposed");
    }

    @Override
    public Set<DiscoveredDevice> getDiscoveredDevices() {
        if (combinedDevices) {
            Map<URL, List<DiscoveredDevice>> groupedByDeviceAddress =
                discoveredDevices.stream().collect(
                    Collectors.groupingBy(t -> t.getURL().copyWithAdapter(CombinedGovernor.COMBINED_ADDRESS)));
            return groupedByDeviceAddress.entrySet().stream().map(entry -> {
                DiscoveredDevice discoveredDevice = entry.getValue().get(0);
                return new DiscoveredDevice(entry.getKey(), discoveredDevice.getName(), discoveredDevice.getAlias(),
                    discoveredDevice.getRSSI(), discoveredDevice.getBluetoothClass(), discoveredDevice.isBleEnabled());
            }).collect(Collectors.toSet());
        } else {
            return Collections.unmodifiableSet(discoveredDevices);
        }
    }

    @Override
    public Set<DiscoveredAdapter> getDiscoveredAdapters() {
        if (combinedAdapters) {
            return discoveredAdapters.stream().map(adapter -> {
                return new DiscoveredAdapter(new URL("/" + CombinedGovernor.COMBINED_ADDRESS),
                        adapter.getName(), adapter.getAlias());
            }).collect(Collectors.toSet());
        } else {
            return Collections.unmodifiableSet(discoveredAdapters);
        }
    }

    @Override
    public boolean isCombinedAdaptersEnabled() {
        return combinedAdapters;
    }

    @Override
    public boolean isCombinedDevicesEnabled() {
        return combinedDevices;
    }

    @Override
    public void addManagerListener(ManagerListener listener) {
        managerListeners.add(listener);
    }

    @Override
    public void removeManagerListener(ManagerListener listener) {
        managerListeners.remove(listener);
    }

    protected void scheduleUpdate(BluetoothObjectGovernor governor) {
        if (!governorScheduler.isShutdown()) {
            governorScheduler.submit(() -> update(governor));
        }
    }

    BluetoothObjectFactory getFactory(String protocolName) {
        BluetoothObjectFactory factory = factories.get(protocolName);
        if (factory == null) {
            logger.debug("Transport [{}] is not registered.", protocolName);
        }
        return factory;
    }

    void setDiscoveryRate(int discoveryRate) {
        this.discoveryRate = discoveryRate;
    }

    void setRediscover(boolean rediscover) {
        this.rediscover = rediscover;
    }

    void setRefreshRate(int refreshRate) {
        this.refreshRate = refreshRate;
    }

    void enableCombinedAdapters(boolean combineAdapters) {
        combinedAdapters = combineAdapters;
    }

    void enableCombinedDevices(boolean combineDevices) {
        combinedDevices = combineDevices;
    }

    protected void notifyGovernorReady(BluetoothGovernor governor, boolean ready) {
        BluetoothManagerUtils.safeForEachError(managerListeners, listener -> listener.ready(governor, ready), logger,
                "Error in manager listener: ready");
    }

    List<BluetoothGovernor> getGovernors(List<? extends BluetoothObject> objects) {
        return Collections.unmodifiableList(objects.stream()
            .map(o -> getGovernor(o.getURL())).collect(Collectors.toList()));
    }

    void updateDescendants(URL parent) {
        computeForEachDescendantGovernor(parent, this::update);
    }

    void resetDescendants(URL parent) {
        if (parent.isProtocol()) {
            // reset all governors that belongs to the transport specified in the argument
            governors.values().stream().filter(governor -> governor instanceof AbstractBluetoothObjectGovernor)
                .map(governor -> (AbstractBluetoothObjectGovernor) governor)
                .filter(governor -> parent.getProtocol().equals(governor.getTransport()))
                .forEach(this::reset);
        } else {
            computeForEachDescendantGovernor(parent, this::reset);
        }
    }

    /**
     * This is a very centric method that returns a "native" objects. Mostly used by governors to acquire
     * a corresponding native object.
     * @param url bluetooth url
     * @return a native object corresponding to the given url
     */
    <T extends BluetoothObject> T getBluetoothObject(URL url) {
        BluetoothObjectFactory factory = findFactory(url);
        BluetoothObject bluetoothObject = null;
        if (factory != null) {
            URL objectURL = url.copyWithProtocol(factory.getProtocolName());
            if (objectURL.isAdapter()) {
                bluetoothObject = factory.getAdapter(objectURL);
            } else if (objectURL.isDevice()) {
                bluetoothObject = factory.getDevice(objectURL);
            } else if (objectURL.isCharacteristic()) {
                bluetoothObject = factory.getCharacteristic(objectURL);
            }
        }
        return (T) bluetoothObject;
    }

    BluetoothObjectGovernor createGovernor(URL url) {
        if (CombinedGovernor.COMBINED_ADDRESS.equals(url.getAdapterAddress())) {
            return createCombinedGovernor(url);
        } else {
            return createBasicGovernor(url);
        }
    }

    private BluetoothObjectGovernor createCombinedGovernor(URL url) {
        if (url.isAdapter()) {
            AdapterGovernor adapterGovernor = new CombinedAdapterGovernorImpl(this, url);
            adapterGovernor.setDiscoveringControl(startDiscovering);
            return (BluetoothObjectGovernor) adapterGovernor;
        } else if (url.isDevice()) {
            return new CombinedDeviceGovernorImpl(this, url);
        } else if (url.isCharacteristic()) {
            return new CombinedCharacteristicGovernorImpl(this, url);
        }
        throw new IllegalStateException("Unknown url");
    }

    private BluetoothObjectGovernor createBasicGovernor(URL url) {
        if (url.isAdapter()) {
            AdapterGovernor adapterGovernor = new AdapterGovernorImpl(this, url);
            adapterGovernor.setDiscoveringControl(startDiscovering);
            return (BluetoothObjectGovernor) adapterGovernor;
        } else if (url.isDevice()) {
            return new DeviceGovernorImpl(this, url);
        } else if (url.isCharacteristic()) {
            return new CharacteristicGovernorImpl(this, url);
        }
        throw new IllegalStateException("Unknown url");
    }

    private void handleObjectFactoryUnregistered(BluetoothObjectFactory bluetoothObjectFactory) {
        String protocol = bluetoothObjectFactory.getProtocolName();
        synchronized (discoveryScheduler) {
            cancelFutures(adapterDiscoveryFutures, protocol);
            cancelFutures(deviceDiscoveryFutures, protocol);
        }
        resetDescendants(new URL().copyWithProtocol(protocol));
    }

    Set<URL> getRegisteredGovernors() {
        return Collections.unmodifiableSet(governors.keySet());
    }

    private void disposeGovernor(BluetoothObjectGovernor governor) {
        governorFutures.computeIfPresent(governor.getURL(), (url, future) -> {
            future.cancel(true);
            return null;
        });
        dispose(governor);
    }

    private BluetoothObjectFactory findFactory(URL url) {
        String protocol = url.getProtocol();
        String adapterAddress = url.getAdapterAddress();
        if (url.getProtocol() != null) {
            return getFactory(protocol);
        } else {
            for (DiscoveredAdapter adapter : discoveredAdapters) {
                if (adapter.getURL().getAdapterAddress().equals(adapterAddress)) {
                    return getFactory(adapter.getURL().getProtocol());
                }
            }
        }
        return null;
    }

    private void notifyDeviceDiscovered(DiscoveredDevice device) {
        if (discoveredDevices.contains(device) && !rediscover) {
            return;
        }
        wrapForEach(deviceDiscoveryListeners, listener -> {
            if (!combinedDevices || listener instanceof CombinedDeviceGovernorImpl) {
                listener.discovered(device);
            } else {
                listener.discovered(new DiscoveredDevice(
                        device.getURL().copyWithAdapter(CombinedGovernor.COMBINED_ADDRESS),
                        device.getName(), device.getAlias(), device.getRSSI(), device.getBluetoothClass(),
                        device.isBleEnabled()));
            }
        },"Error in device discovery listener");
    }

    private void notifyAdapterDiscovered(DiscoveredAdapter adapter) {
        if (discoveredAdapters.contains(adapter) && !rediscover) {
            return;
        }
        wrapForEach(adapterDiscoveryListeners, listener -> {
            if (!combinedAdapters || listener instanceof CombinedAdapterGovernorImpl) {
                listener.discovered(adapter);
            } else {
                listener.discovered(new DiscoveredAdapter(new URL("/" + CombinedGovernor.COMBINED_ADDRESS),
                    "Combined Bluetooth Adapter", null));
            }
        },"Error in adapter discovery listener");
    }

    private void handleDeviceLost(URL url) {
        logger.info("Device has been lost: " + url);
        wrapForEach(deviceDiscoveryListeners, deviceDiscoveryListener -> deviceDiscoveryListener.deviceLost(url),
            "Error in device discovery listener");
    }

    private void handleAdapterLost(URL url) {
        logger.info("Adapter has been lost: " + url);
        wrapForEach(adapterDiscoveryListeners, adapterDiscoveryListener -> adapterDiscoveryListener.adapterLost(url),
            "Error in adapter discovery listener");
        reset((BluetoothObjectGovernor) getAdapterGovernor(url));
    }

    private void reset(BluetoothObjectGovernor governor) {
        try {
            governor.reset();
        } catch (Exception ex) {
            logger.error("Could not reset governor: " + governor, ex);
        }
    }

    private void dispose(BluetoothObjectGovernor governor) {
        try {
            governor.dispose();
        } catch (Exception ex) {
            logger.error("Could not dispose governor: " + governor, ex);
        }
    }

    private void update(BluetoothObjectGovernor governor) {
        try {
            logger.debug("Updating governor: {}", governor.getURL());
            governor.update();
        } catch (Exception ex) {
            logger.warn("Could not update governor: " + governor, ex);
        }
    }

    private void init(BluetoothObjectGovernor governor) {
        try {
            governor.init();
        } catch (Exception ex) {
            logger.warn("Could not init governor: " + governor, ex);
        }
    }

    private final class DeviceDiscoveryJob implements Runnable {

        private final BluetoothObjectFactory factory;

        private DeviceDiscoveryJob(BluetoothObjectFactory factory) {
            this.factory = factory;
        }

        @Override
        public void run() {
            try {
                discoverDevices();
            } catch (Exception ex) {
                logger.error("Device discovery job error", ex);
            }
        }

        private void discoverDevices() {
            Set<DiscoveredDevice> discovered = factory.getDiscoveredDevices().stream()
                .filter(device -> device.getRSSI() != 0).collect(Collectors.toSet());

            discovered.forEach(BluetoothManagerImpl.this::notifyDeviceDiscovered);

            Set<DiscoveredDevice> factoryDevices = discoveredDevices.stream()
                    .filter(device -> factory.getProtocolName().equals(device.getURL().getProtocol()))
                    .collect(Collectors.toSet());

            Set<DiscoveredDevice> lostDevices = Sets.difference(factoryDevices, discovered);
            lostDevices.forEach(lost -> handleDeviceLost(lost.getURL()));

            discoveredDevices.removeAll(lostDevices);
            discoveredDevices.addAll(discovered);
        }
    }

    private final class AdapterDiscoveryJob implements Runnable {

        private final BluetoothObjectFactory factory;

        private AdapterDiscoveryJob(BluetoothObjectFactory factory) {
            this.factory = factory;
        }

        @Override
        public void run() {
            try {
                discoverAdapters();
            } catch (Exception ex) {
                logger.error("Adapter discovery job error", ex);
            }
        }

        private void discoverAdapters() {
            Set<DiscoveredAdapter> discovered = new HashSet<>(factory.getDiscoveredAdapters());

            discovered.forEach(adapter -> {
                notifyAdapterDiscovered(adapter);
                if (startDiscovering) {
                    // create (if not created before) adapter governor which will trigger its discovering status
                    // (by default when it is created "discovering" flag is set to true)
                    getAdapterGovernor(adapter.getURL());
                }
            });

            Set<DiscoveredAdapter> factoryAdapters = discoveredAdapters.stream()
                    .filter(device -> factory.getProtocolName().equals(device.getURL().getProtocol()))
                    .collect(Collectors.toSet());

            Set<DiscoveredAdapter> lostAdapters = Sets.difference(factoryAdapters, discovered);
            lostAdapters.forEach(lost -> handleAdapterLost(lost.getURL()));

            discoveredAdapters.removeAll(lostAdapters);
            discoveredAdapters.addAll(discovered);
        }
    }

    private void computeForEachDescendantGovernorAndRemove(URL url, Consumer<BluetoothObjectGovernor> consumer) {
        URL protocolLess = url.copyWithProtocol(null);
        governors.entrySet().removeIf(entry -> {
            if (entry.getKey().isDescendant(protocolLess)) {
                consumer.accept(entry.getValue());
                return true;
            }
            return false;
        });
    }

    private void computeForEachDescendantGovernor(URL url, Consumer<BluetoothObjectGovernor> consumer) {
        URL protocolLess = url.copyWithProtocol(null);
        governors.values().stream().filter(governor -> governor.getURL().isDescendant(protocolLess)).forEach(consumer);
    }

    private <T> void wrapForEach(Set<T> listeners, Consumer<T> func, String error) {
        listeners.forEach(deviceDiscoveryListener -> {
            try {
                func.accept(deviceDiscoveryListener);
            } catch (Exception ex) {
                logger.error(error, ex);
            }
        });
    }

    private void scheduleDiscovery(BluetoothObjectFactory factory) {
        AdapterDiscoveryJob adapterDiscoveryJob = new AdapterDiscoveryJob(factory);
        adapterDiscoveryJob.run();
        adapterDiscoveryFutures.put(factory.getProtocolName(),
            discoveryScheduler.scheduleWithFixedDelay(adapterDiscoveryJob, 5, discoveryRate, TimeUnit.SECONDS));

        DeviceDiscoveryJob deviceDiscoveryJob = new DeviceDiscoveryJob(factory);
        deviceDiscoveryJob.run();
        deviceDiscoveryFutures.put(factory.getProtocolName(),
            discoveryScheduler.scheduleWithFixedDelay(deviceDiscoveryJob, 5, discoveryRate, TimeUnit.SECONDS));
    }

    private void scheduleGovernor(BluetoothObjectGovernor governor) {
        governorFutures.put(governor.getURL(),
            governorScheduler.scheduleWithFixedDelay(() -> update(governor),5, refreshRate, TimeUnit.SECONDS));
    }

    private void cancelAllFutures(boolean forceInterrupt) {
        synchronized (discoveryScheduler) {
            adapterDiscoveryFutures.values().forEach(future -> future.cancel(forceInterrupt));
            adapterDiscoveryFutures.clear();
            deviceDiscoveryFutures.values().forEach(future -> future.cancel(forceInterrupt));
            deviceDiscoveryFutures.clear();
        }
        synchronized (governorScheduler) {
            governorFutures.values().forEach(future -> future.cancel(forceInterrupt));
            governorFutures.clear();
        }
    }

    private static void cancelFutures(Map<String, ScheduledFuture<?>> futures, String transport) {
        futures.entrySet().removeIf(entry -> {
            if (entry.getKey().equals(transport)) {
                entry.getValue().cancel(true);
                return true;
            }
            return false;
        });
    }

}
