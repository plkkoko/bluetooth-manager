package org.sputnikdev.bluetooth.manager.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.internal.util.reflection.Whitebox;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sputnikdev.bluetooth.URL;
import org.sputnikdev.bluetooth.manager.*;
import org.sputnikdev.bluetooth.manager.transport.Adapter;
import org.sputnikdev.bluetooth.manager.transport.BluetoothObjectFactory;
import org.sputnikdev.bluetooth.manager.transport.Device;
import org.sputnikdev.bluetooth.manager.transport.Notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(PowerMockRunner.class)
public class AdapterGovernorImplTest {

    private static final Boolean POWERED = true;
    private static final Boolean DISCOVERING = true;
    private static final String ALIAS = "adapter alias";
    private static final String NAME = "adapter name";

    @Mock
    private static URL URL = new URL("/11:22:33:44:55:66");

    private static final URL DEVICE_1_URL = new URL(URL.getAdapterAddress(), "12:34:56:78:90:12");
    private static final URL DEVICE_2_URL = new URL(URL.getAdapterAddress(), "23:87:65:43:21:09");

    private static final List<Device> DEVICES = new ArrayList<Device>(){{
        add(mockDevice(DEVICE_1_URL, "Device 1"));
        add(mockDevice(DEVICE_2_URL, "Device 2"));
    }};

    @Mock(name = "bluetoothObject")
    private Adapter adapter;
    @Mock
    private BluetoothManagerImpl bluetoothManager = mock(BluetoothManagerImpl.class);
    @Mock
    private AdapterListener listener;
    @Mock
    private BluetoothObjectFactory bluetoothObjectFactory;

    @Captor
    private ArgumentCaptor<Notification<Boolean>> poweredCaptor;
    @Captor
    private ArgumentCaptor<Notification<Boolean>> discoveringCaptor;

    @Spy
    @InjectMocks
    private AdapterGovernorImpl governor = new AdapterGovernorImpl(bluetoothManager, URL);

    @Before
    public void setUp() {
        // not sure why, but adapter does not get injected properly, hence a workaround here:
        Whitebox.setInternalState(governor, "bluetoothObject", adapter);

        when(adapter.isPowered()).thenReturn(POWERED);
        when(adapter.isDiscovering()).thenReturn(DISCOVERING);
        when(adapter.getAlias()).thenReturn(ALIAS);
        when(adapter.getName()).thenReturn(NAME);
        doNothing().when(adapter).enablePoweredNotifications(poweredCaptor.capture());
        doNothing().when(adapter).enableDiscoveringNotifications(discoveringCaptor.capture());
        governor.addAdapterListener(listener);

        when(adapter.getURL()).thenReturn(URL);

        when(bluetoothManager.getFactory(any())).thenReturn(bluetoothObjectFactory);
        when(bluetoothObjectFactory.getAdapter(URL)).thenReturn(adapter);
        when(adapter.getDevices()).thenReturn(DEVICES);
    }

    @Test
    public void testInit() throws Exception {
        governor.init(adapter);

        //verify(adapter, times(1)).isPowered();
        //verify(listener, times(1)).powered(POWERED);

        //verify(adapter, times(1)).isDiscovering();
        //verify(listener, times(1)).discovering(DISCOVERING);

        verify(adapter, times(1)).enablePoweredNotifications(poweredCaptor.getValue());
        verify(adapter, times(1)).enableDiscoveringNotifications(discoveringCaptor.getValue());

        verifyNoMoreInteractions(listener, adapter);

    }

    @Test
    public void testUpdate() throws Exception {
        when(adapter.isDiscovering())
                .thenReturn(false)
                .thenReturn(false)
                .thenReturn(true);

//        when(adapter.isPowered())
//                .thenReturn(false).thenReturn(false)
//                .thenReturn(false).thenReturn(true)
//                .thenReturn(true).thenReturn(true);

        governor.setPoweredControl(false);
        governor.setDiscoveringControl(false);


        when(adapter.isPowered()).thenReturn(false);
        governor.update(adapter);

        // first case
        InOrder inOrder = inOrder(adapter);
        inOrder.verify(adapter, times(2)).isPowered();
        inOrder.verify(adapter, never()).setPowered(anyBoolean());
        inOrder.verify(adapter, never()).isDiscovering();
        inOrder.verify(adapter, never()).startDiscovery();
        inOrder.verify(adapter, never()).stopDiscovery();

        // second case
        when(adapter.isPowered()).thenReturn(false).thenReturn(true);
        governor.setPoweredControl(true);
        governor.setDiscoveringControl(false);
        governor.update(adapter);
        inOrder.verify(adapter).isPowered();
        inOrder.verify(adapter).setPowered(true);
        inOrder.verify(adapter, times(2)).isPowered();
        inOrder.verify(adapter).isDiscovering();
        inOrder.verify(adapter, never()).startDiscovery();
        inOrder.verify(adapter, never()).stopDiscovery();

        // third case
        when(adapter.isPowered()).thenReturn(true);
        governor.setPoweredControl(true);
        governor.setDiscoveringControl(true);
        governor.update(adapter);
        inOrder.verify(adapter, times(2)).isPowered();
        inOrder.verify(adapter, never()).setPowered(anyBoolean());
        inOrder.verify(adapter).isDiscovering();
        inOrder.verify(adapter, times(1)).startDiscovery();
        inOrder.verify(adapter, never()).stopDiscovery();

        // forth case
        when(adapter.isPowered()).thenReturn(true);
        governor.setPoweredControl(true);
        governor.setDiscoveringControl(false);
        governor.update(adapter);
        inOrder.verify(adapter, times(2)).isPowered();
        inOrder.verify(adapter, never()).setPowered(anyBoolean());
        inOrder.verify(adapter).isDiscovering();
        inOrder.verify(adapter, never()).startDiscovery();
        inOrder.verify(adapter, times(1)).stopDiscovery();

        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testReset() throws Exception {
        governor.reset(adapter);

        verify(adapter, times(1)).disableDiscoveringNotifications();
        verify(adapter, times(1)).disablePoweredNotifications();
    }

    @Test
    public void testGetSetPoweredControl() throws Exception {
        governor.setPoweredControl(true);
        assertTrue(governor.getPoweredControl());

        governor.setPoweredControl(false);
        assertFalse(governor.getPoweredControl());
    }

    @Test
    public void testIsPowered() throws Exception {
        when(adapter.isPowered()).thenReturn(false).thenReturn(true);

        assertFalse(governor.isPowered());
        verify(adapter, times(1)).isPowered();
        verify(governor, times(1)).getBluetoothObject();

        assertTrue(governor.isPowered());
        verify(adapter, times(2)).isPowered();
        verify(governor, times(2)).getBluetoothObject();

        verifyNoMoreInteractions(adapter);
    }

    @Test
    public void testGetSetDiscoveringControl() throws Exception {
        governor.setDiscoveringControl(true);
        assertTrue(governor.getDiscoveringControl());

        governor.setDiscoveringControl(false);
        assertFalse(governor.getDiscoveringControl());
    }

    @Test
    public void testIsDiscovering() throws Exception {
        when(adapter.isDiscovering()).thenReturn(false).thenReturn(true);

        assertFalse(governor.isDiscovering());
        verify(adapter, times(1)).isDiscovering();
        verify(governor, times(1)).getBluetoothObject();

        assertTrue(governor.isDiscovering());
        verify(adapter, times(2)).isDiscovering();

        verifyNoMoreInteractions(adapter);
    }

    @Test
    public void testGetAlias() throws Exception {
        assertEquals(ALIAS, governor.getAlias());

        verify(governor, times(1)).getBluetoothObject();
        verify(adapter, times(1)).getAlias();

        verifyNoMoreInteractions(adapter);
    }

    @Test
    public void testSetAlias() throws Exception {
        String newAlias = "new alias";

        governor.setAlias(newAlias);

        verify(governor, times(1)).getBluetoothObject();
        verify(adapter, times(1)).setAlias(newAlias);

        verifyNoMoreInteractions(adapter);
    }

    @Test
    public void testGetName() throws Exception {
        assertEquals(NAME, governor.getName());

        verify(governor, times(1)).getBluetoothObject();
        verify(adapter, times(1)).getName();

        verifyNoMoreInteractions(adapter);
    }

    @Test
    public void testGetDisplayName() throws Exception {
        when(adapter.getAlias()).thenReturn(ALIAS).thenReturn(null);
        when(adapter.getName()).thenReturn(NAME);

        assertEquals(ALIAS, governor.getDisplayName());
        verify(governor, times(1)).getBluetoothObject();
        verify(adapter, times(1)).getAlias();

        assertEquals(NAME, governor.getDisplayName());
        verify(governor, times(3)).getBluetoothObject();
        verify(adapter, times(2)).getAlias();
        verify(adapter, times(1)).getName();

        verifyNoMoreInteractions(adapter);
    }

    @Test
    public void testGetDevices() throws Exception {
        List<URL> deviceURLs = new ArrayList<>(governor.getDevices());
        assertEquals(2, deviceURLs.size());
        Collections.sort(deviceURLs);
        assertEquals(DEVICE_1_URL, deviceURLs.get(0));
        assertEquals(DEVICE_2_URL, deviceURLs.get(1));

        verify(adapter, times(1)).getDevices();
        verifyNoMoreInteractions(adapter);
    }

    @Test
    public void testGetDeviceGovernors() throws Exception {
        List<BluetoothGovernor> deviceGovernors = mock(List.class);
        when(bluetoothManager.getGovernors(DEVICES)).thenReturn(deviceGovernors);

        assertEquals(deviceGovernors, governor.getDeviceGovernors());

        verify(adapter, times(1)).getDevices();
        verifyNoMoreInteractions(adapter);
    }

    @Test
    public void testToString() throws Exception {
        when(adapter.getAlias()).thenReturn(ALIAS).thenReturn(null);
        assertEquals("[Adapter] " + URL + " [" + ALIAS + "]", governor.toString());
        assertEquals("[Adapter] " + URL + " [" + NAME + "]", governor.toString());
    }

    @Test
    public void testEquals() throws Exception {
        URL url1 = new URL("/11:22:33:44:55:67");
        URL url2 = new URL("/11:22:33:44:55:66");

        assertFalse(url1.equals(url2));

        AdapterGovernorImpl gov1 = new AdapterGovernorImpl(bluetoothManager, url1);
        AdapterGovernorImpl gov2 = new AdapterGovernorImpl(bluetoothManager, url2);

        assertEquals(gov1, gov1);
        assertFalse(gov1.equals(new Object()));

        assertFalse(gov1.equals(gov2));

        gov2 = new AdapterGovernorImpl(bluetoothManager, url1);
        assertTrue(gov1.equals(gov2));
    }

    @Test
    public void testHashCode() throws Exception {
        URL url1 = new URL("/11:22:33:44:55:67");
        URL url2 = new URL("/11:22:33:44:55:66");

        assertFalse(url1.hashCode() == url2.hashCode());

        AdapterGovernorImpl gov1 = new AdapterGovernorImpl(bluetoothManager, url1);
        AdapterGovernorImpl gov2 = new AdapterGovernorImpl(bluetoothManager, url2);
        assertFalse(gov1.hashCode() == gov2.hashCode());

        gov2 = new AdapterGovernorImpl(bluetoothManager, url1);
        assertTrue(gov1.hashCode() == gov2.hashCode());
    }

    @Test
    public void testGetType() throws Exception {
        assertEquals(BluetoothObjectType.ADAPTER, governor.getType());
    }

    @Test
    public void testAccept() throws Exception {
        governor.accept(new BluetoothObjectVisitor() {
            @Override public void visit(AdapterGovernor governor) throws Exception {
                assertEquals(AdapterGovernorImplTest.this.governor, governor);
            }

            @Override public void visit(DeviceGovernor governor) throws Exception {
                assertFalse(true);
            }

            @Override public void visit(CharacteristicGovernor governor) throws Exception {
                assertFalse(true);
            }
        });
    }

    @Test
    public void testAddRemoveAdapterListener() throws Exception {
        AdapterListener adapterListener = mock(AdapterListener.class);
        governor.addAdapterListener(adapterListener);

        governor.notifyPowered(true);
        verify(adapterListener, times(1)).powered(true);

        governor.removeAdapterListener(adapterListener);

        governor.notifyPowered(true);
        verifyNoMoreInteractions(adapterListener);
    }

    @Test
    public void testNotifyPowered() throws Exception {
        AdapterListener adapterListener1 = mock(AdapterListener.class);
        AdapterListener adapterListener2 = mock(AdapterListener.class);
        governor.addAdapterListener(adapterListener1);
        governor.addAdapterListener(adapterListener2);

        governor.notifyPowered(true);

        InOrder inOrder = inOrder(adapterListener1, adapterListener2);

        inOrder.verify(adapterListener1, times(1)).powered(true);
        inOrder.verify(adapterListener2, times(1)).powered(true);

        // this should be ignored by governor, a log message must be issued
        doThrow(Exception.class).when(adapterListener1).powered(anyBoolean());

        governor.notifyPowered(true);
        inOrder.verify(adapterListener1, times(1)).powered(true);
        inOrder.verify(adapterListener2, times(1)).powered(true);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testNotifyDiscovering() throws Exception {
        AdapterListener adapterListener1 = mock(AdapterListener.class);
        AdapterListener adapterListener2 = mock(AdapterListener.class);
        governor.addAdapterListener(adapterListener1);
        governor.addAdapterListener(adapterListener2);

        governor.notifyDiscovering(true);

        InOrder inOrder = inOrder(adapterListener1, adapterListener2);

        inOrder.verify(adapterListener1, times(1)).discovering(true);
        inOrder.verify(adapterListener2, times(1)).discovering(true);

        // this should be ignored by governor, a log message must be issued
        doThrow(Exception.class).when(adapterListener1).discovering(anyBoolean());

        governor.notifyDiscovering(true);
        inOrder.verify(adapterListener1, times(1)).discovering(true);
        inOrder.verify(adapterListener2, times(1)).discovering(true);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testAdapterPoweredNotification() {
        when(adapter.isPowered()).thenReturn(false).thenReturn(true).thenReturn(false);

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);

        doNothing().when(adapter).enablePoweredNotifications(notificationCaptor.capture());

        // init method will enable notifications, if they are not enabled already
        governor.init(adapter);

        verify(adapter, times(1)).enablePoweredNotifications(notificationCaptor.getValue());

        notificationCaptor.getValue().notify(Boolean.TRUE);

        verify(listener, times(1)).powered(true);
        verify(governor, times(1)).notifyPowered(true);
        verify(governor, times(1)).updateLastChanged();

//        when(governor.findBluetoothObject()).thenReturn(null);
//
//        notificationCaptor.getValue().notify(Boolean.FALSE);
//        // handling the case when the adapter physically disconnected
//        verify(listener, times(1)).powered(false);
//        verify(governor, times(1)).notifyPowered(false);
//        verify(governor, times(2)).updateLastChanged();
//        verify(governor, times(1)).reset();
    }

    @Test
    public void testAdapterDiscoveringNotification() {
        when(adapter.isDiscovering()).thenReturn(false).thenReturn(true);

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);

        doNothing().when(adapter).enableDiscoveringNotifications(notificationCaptor.capture());

        // init method will enable notifications, if they are not enabled already
        governor.init(adapter);

        verify(adapter, times(1)).enableDiscoveringNotifications(notificationCaptor.getValue());

        notificationCaptor.getValue().notify(Boolean.TRUE);

        verify(listener, times(1)).discovering(true);
        verify(governor, times(1)).notifyDiscovering(true);
        verify(governor, times(1)).updateLastChanged();
    }

    private static Device mockDevice(URL url, String name) {
        Device device = mock(Device.class);
        when(device.getURL()).thenReturn(url);
        when(device.getName()).thenReturn(name);
        return device;
    }
}
