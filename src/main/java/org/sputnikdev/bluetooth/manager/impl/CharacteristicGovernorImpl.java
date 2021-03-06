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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.BluetoothObjectType;
import org.sputnikdev.bluetooth.manager.BluetoothObjectVisitor;
import org.sputnikdev.bluetooth.manager.CharacteristicGovernor;
import org.sputnikdev.bluetooth.manager.NotReadyException;
import org.sputnikdev.bluetooth.manager.ValueListener;
import org.sputnikdev.bluetooth.manager.transport.Characteristic;
import org.sputnikdev.bluetooth.manager.transport.CharacteristicAccessType;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 *
 * @author Vlad Kolotov
 */
class CharacteristicGovernorImpl extends AbstractBluetoothObjectGovernor<Characteristic>
    implements CharacteristicGovernor {

    private Logger logger = LoggerFactory.getLogger(CharacteristicGovernorImpl.class);

    private List<ValueListener> valueListeners = new CopyOnWriteArrayList<>();
    private ValueNotification valueNotification;

    CharacteristicGovernorImpl(BluetoothManagerImpl bluetoothManager, URL url) {
        super(bluetoothManager, url);
    }

    @Override
    void init(Characteristic characteristic) { /* do nothing */ }

    @Override
    void update(Characteristic characteristic) {
        if (canNotify(characteristic)) {
            boolean notifying = characteristic.isNotifying();
            if (!valueListeners.isEmpty() && (!notifying || valueNotification == null)) {
                enableNotification(characteristic);
            } else if (valueListeners.isEmpty() && notifying) {
                disableNotification(characteristic);
            }
        }
    }

    @Override
    void reset(Characteristic characteristic) {
        logger.info("Disable characteristic notifications: " + getURL());
        valueNotification = null;
        try {
            // force notification to be disabled and ignore any error
            characteristic.disableValueNotifications();
        } catch (Exception ignore) { /* do nothing */ }
    }

    @Override
    public void dispose() {
        super.dispose();
        valueListeners.clear();
    }

    @Override
    public void addValueListener(ValueListener valueListener) {
        valueListeners.add(valueListener);
    }

    @Override
    public void removeValueListener(ValueListener valueListener) {
        valueListeners.remove(valueListener);
    }

    @Override
    public Set<CharacteristicAccessType> getFlags() throws NotReadyException {
        return getBluetoothObject().getFlags();
    }

    @Override
    public boolean isNotifiable() throws NotReadyException {
        Set<CharacteristicAccessType> flgs = getFlags();
        return flgs.contains(CharacteristicAccessType.NOTIFY) || flgs.contains(CharacteristicAccessType.INDICATE);
    }

    @Override
    public boolean isNotifying() throws NotReadyException {
        return getBluetoothObject().isNotifying();
    }

    @Override
    public boolean isWritable() throws NotReadyException {
        Set<CharacteristicAccessType> flgs = getFlags();
        return flgs.contains(CharacteristicAccessType.WRITE)
            || flgs.contains(CharacteristicAccessType.WRITE_WITHOUT_RESPONSE);
    }

    @Override
    public boolean isReadable() throws NotReadyException {
        return getFlags().contains(CharacteristicAccessType.READ);
    }

    @Override
    public byte[] read() throws NotReadyException {
        Characteristic characteristic = getBluetoothObject();
        if (characteristic == null) {
            throw new IllegalStateException("Characteristic governor is not initialized");
        }
        byte[] result = characteristic.readValue();
        updateLastChanged();
        return result;
    }

    @Override
    public boolean write(byte[] data) throws NotReadyException {
        Characteristic characteristic = getBluetoothObject();
        boolean result = characteristic.writeValue(data);
        updateLastChanged();
        return result;
    }

    @Override
    public String toString() {
        return "[Characteristic] " + getURL();
    }

    @Override
    public BluetoothObjectType getType() {
        return BluetoothObjectType.CHARACTERISTIC;
    }

    @Override
    public void accept(BluetoothObjectVisitor visitor) throws Exception {
        visitor.visit(this);
    }

    private void enableNotification(Characteristic characteristic) {
        if (valueNotification == null && canNotify(characteristic)) {
            logger.info("Enable characteristic notifications: " + getURL());
            ValueNotification notification = new ValueNotification();
            characteristic.enableValueNotifications(notification);
            valueNotification = notification;
        }
    }

    private void disableNotification(Characteristic characteristic) {
        ValueNotification notification = valueNotification;
        valueNotification = null;
        if (notification != null && canNotify(characteristic)) {
            logger.info("Disable characteristic notifications: " + getURL());
            characteristic.disableValueNotifications();
        }
    }

    private boolean canNotify(Characteristic characteristic) {
        Set<CharacteristicAccessType> flgs = characteristic.getFlags();
        return flgs.contains(CharacteristicAccessType.NOTIFY) || flgs.contains(CharacteristicAccessType.INDICATE);
    }

    private class ValueNotification implements Notification<byte[]> {
        @Override
        public void notify(byte[] data) {
            updateLastChanged();
            BluetoothManagerUtils.safeForEachError(valueListeners, listener -> listener.changed(data), logger,
                    "Execution error of a characteristic listener");
        }
    }

}
