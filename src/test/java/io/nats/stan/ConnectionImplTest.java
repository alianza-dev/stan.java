/*******************************************************************************
 * Copyright (c) 2015-2016 Apcera Inc. All rights reserved. This program and the accompanying
 * materials are made available under the terms of the MIT License (MIT) which accompanies this
 * distribution, and is available at http://opensource.org/licenses/MIT
 *******************************************************************************/

package io.nats.stan;

import static io.nats.stan.UnitTestUtilities.await;
import static io.nats.stan.UnitTestUtilities.newMockedConnection;
import static io.nats.stan.UnitTestUtilities.setLogLevel;
import static io.nats.stan.UnitTestUtilities.setupMockNatsConnection;
import static io.nats.stan.UnitTestUtilities.testClientName;
import static io.nats.stan.UnitTestUtilities.testClusterName;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.matches;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.nats.client.NUID;
import io.nats.stan.ConnectionImpl.AckClosure;
import io.nats.stan.protobuf.CloseResponse;
import io.nats.stan.protobuf.ConnectResponse;
import io.nats.stan.protobuf.MsgProto;
import io.nats.stan.protobuf.PubAck;
import io.nats.stan.protobuf.StartPosition;
import io.nats.stan.protobuf.SubscriptionRequest;
import io.nats.stan.protobuf.SubscriptionResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Category(UnitTest.class)
public class ConnectionImplTest {
    static final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    static final Logger logger = (Logger) LoggerFactory.getLogger(ConnectionImplTest.class);

    static final LogVerifier verifier = new LogVerifier();

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Rule
    public TestCasePrinterRule pr = new TestCasePrinterRule(System.out);

    @Mock
    private BlockingQueue<PubAck> pubAckChanMock;

    @Mock
    HashMap<String, AckClosure> pubAckMapMock;

    @Mock
    Map<String, Subscription> subMapMock;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {}

    @AfterClass
    public static void tearDownAfterClass() throws Exception {}

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        verifier.setup();
    }

    // Always have this teardown otherwise we can stuff up our expectations. Besides, it's
    // good coding practise
    @After
    public void tearDown() throws Exception {
        verifier.teardown();
        setLogLevel(Level.INFO);
    }

    @SuppressWarnings("resource")
    @Test
    public void testConnectionImpl() {
        new ConnectionImpl();
    }

    @Test
    public void testConnectionImplStringString() {
        ConnectionImpl conn = new ConnectionImpl(testClusterName, testClientName);
        assertNotNull(conn);
    }

    @Test
    public void testConnectionImplOptions() {
        Options opts = new Options.Builder().setAckTimeout(Duration.ofSeconds(555)).create();
        ConnectionImpl conn = new ConnectionImpl(testClusterName, testClientName, opts);
        assertNotNull(conn);
        assertEquals(555, conn.opts.getAckTimeout().getSeconds());
    }

    @Test
    public void testConnectionImplOptionsNull() {
        ConnectionImpl conn = new ConnectionImpl(testClusterName, testClientName, null);
        assertNotNull(conn);
        assertEquals(Duration.ofMillis(SubscriptionImpl.DEFAULT_ACK_WAIT),
                conn.opts.getAckTimeout());
    }

    @Test
    public void testConnectSuccess() {
        try (io.nats.client.Connection nconn = setupMockNatsConnection()) {
            assertNotNull(nconn);
            assertFalse(nconn.isClosed());
            Options opts = new Options.Builder().setNatsConn(nconn).create();
            assertNotNull(opts.getNatsConn());
            try (ConnectionImpl conn = new ConnectionImpl(testClusterName, testClientName, opts)) {
                assertNotNull(conn);
                assertNotNull(conn.nc);
                conn.connect();
            } catch (Exception e) {
                e.printStackTrace();
                fail(e.getMessage());
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    /**
     * Tests that connect() will correctly create a NATS connection if needed.
     */
    @Test
    public void testConnectSuccessInternalNats() {
        try {
            io.nats.client.ConnectionFactory ncf = mock(io.nats.client.ConnectionFactory.class);
            io.nats.client.Connection nc = setupMockNatsConnection();
            ConnectionImpl conn = Mockito.spy(new ConnectionImpl(testClusterName, testClientName));
            assertNotNull(conn);
            when(conn.createNatsConnectionFactory()).thenReturn(ncf);
            when(ncf.createConnection()).thenReturn(nc);
            conn.connect();
            assertNotNull(conn.nc);
            assertEquals(nc, conn.getNatsConnection());
        } catch (IOException | TimeoutException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    /**
     * Tests for IOException when ConnectResponse contains a non-empty error.
     */
    @Test
    public void testConnectResponseError() {
        try (io.nats.client.Connection nconn = setupMockNatsConnection()) {
            // Test for request IOException
            String discoverSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_DISCOVER_PREFIX, testClusterName);
            ConnectResponse cr =
                    ConnectResponse.newBuilder().setError("stan: this is a fake error").build();
            io.nats.client.Message raw = new io.nats.client.Message("foo", "bar", cr.toByteArray());
            when(nconn.request(eq(discoverSubject), any(byte[].class), any(long.class)))
                    .thenReturn(raw);

            Options opts = new Options.Builder().setNatsConn(nconn).create();
            boolean exThrown = false;
            try (ConnectionImpl conn = new ConnectionImpl(testClusterName, testClientName, opts)) {
                conn.connect();
            } catch (Exception e) {
                assertTrue("Wrong exception type: " + e.getClass().getName(),
                        e instanceof IOException);
                assertEquals("stan: this is a fake error", e.getMessage());
                exThrown = true;
            }
            assertTrue("Should have thrown IOException", exThrown);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testConnectIoError() {
        try (io.nats.client.Connection nconn = setupMockNatsConnection()) {
            // Test for request IOException
            String discoverSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_DISCOVER_PREFIX, testClusterName);
            doThrow(new IOException("fake I/O exception")).when(nconn).request(eq(discoverSubject),
                    any(byte[].class), any(long.class));

            Options opts = new Options.Builder().setNatsConn(nconn).create();
            boolean exThrown = false;
            try (ConnectionImpl conn = new ConnectionImpl(testClusterName, testClientName, opts)) {
                conn.connect();
            } catch (Exception e) {
                assertTrue("Wrong exception type: " + e.getClass().getName(),
                        e instanceof IOException);
                assertEquals("fake I/O exception", e.getMessage());
                exThrown = true;
            }
            assertTrue("Should have thrown IOException", exThrown);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testConnectTimeout() {
        try (io.nats.client.Connection nconn = setupMockNatsConnection()) {
            // Test for request TimeoutException
            String discoverSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_DISCOVER_PREFIX, testClusterName);
            doThrow(new TimeoutException()).when(nconn).request(eq(discoverSubject),
                    any(byte[].class), any(long.class));

            Options opts = new Options.Builder().setNatsConn(nconn).create();
            boolean exThrown = false;
            try (ConnectionImpl conn = new ConnectionImpl(testClusterName, testClientName, opts)) {
                conn.connect();
            } catch (Exception e) {
                assertTrue("Wrong exception type: " + e.getClass().getName(),
                        e instanceof TimeoutException);
                assertEquals(ConnectionImpl.ERR_CONNECTION_REQ_TIMEOUT, e.getMessage());
                exThrown = true;
            }
            assertTrue("Should have thrown TimeoutException", exThrown);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCreateSubscriptionRequest() throws IOException, TimeoutException {
        try (ConnectionImpl conn = (ConnectionImpl) spy(newMockedConnection())) {
            // BlockingQueue<PubAck> mockChan = mock(BlockingQueue.class);
            conn.setPubAckChan(pubAckChanMock);
            assertEquals(conn.getPubAckChan(), pubAckChanMock);

            @SuppressWarnings("unchecked")
            Map<String, AckClosure> mockMap = (Map<String, AckClosure>) mock(Map.class);
            conn.setPubAckMap(mockMap);
            assertEquals(mockMap, conn.getPubAckMap());

            SubscriptionImpl sub = mock(SubscriptionImpl.class);
            when(sub.getConnection()).thenReturn(conn);
            when(sub.getSubject()).thenReturn("foo");
            when(sub.getQueue()).thenReturn("bar");
            when(sub.getInbox()).thenReturn("_INBOX.fake");

            SubscriptionOptions subOpts = mock(SubscriptionOptions.class);
            when(sub.getOptions()).thenReturn(subOpts);
            // assertEquals(subOpts, sub.getOptions());
            when(subOpts.getStartAt()).thenReturn(StartPosition.NewOnly);
            when(subOpts.getMaxInFlight()).thenReturn(100);
            when(subOpts.getAckWait()).thenReturn(Duration.ofSeconds(20));
            conn.createSubscriptionRequest(sub);
        }
    }

    @Test
    public void testGetNatsConnection() throws IOException, TimeoutException {

        io.nats.client.Connection nc = mock(io.nats.client.Connection.class);

        // Make sure the NATS connection is the one that was passed in
        Options opts = new Options.Builder().setNatsConn(nc).create();
        Connection sc = new ConnectionImpl("foo", "bar", opts);
        assertEquals(nc, sc.getNatsConnection());

        // Make sure the NATS connection is null (since it won't have been created yet)
        sc = new ConnectionImpl("foo", "bar");
        assertNull(sc.getNatsConnection());

        // Make sure the NATS connection is created, and returned, when the connection is created
        // internally.
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            assertNotNull(conn.nc);
            assertEquals(conn.nc, conn.getNatsConnection());
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testGetterSetters() {
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            // BlockingQueue<PubAck> mockChan = mock(BlockingQueue.class);
            conn.setPubAckChan(pubAckChanMock);
            assertEquals(conn.getPubAckChan(), pubAckChanMock);

            @SuppressWarnings("unchecked")
            Map<String, AckClosure> mockMap = (Map<String, AckClosure>) mock(Map.class);
            conn.setPubAckMap(mockMap);
            assertEquals(mockMap, conn.getPubAckMap());
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCloseSuccess() {
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            assertNotNull(conn);
            conn.close();
            assertNull(conn.nc);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCloseClosesNats() throws IOException, TimeoutException {
        // test ncOwned = false
        ConnectionImpl conn = (ConnectionImpl) newMockedConnection(false);
        assertNotNull(conn);
        assertFalse(conn.ncOwned);
        conn.ncOwned = false;
        conn.close();
        assertNull(conn.nc);

        // test ncOwned = true && nc != null
        conn = (ConnectionImpl) newMockedConnection(true);
        assertNotNull(conn);
        assertTrue(conn.ncOwned);
        conn.close();
        assertNull(conn.nc);


        // Now try with ncOwned == true and nc==null
        conn = (ConnectionImpl) newMockedConnection(true);
        assertTrue(conn.ncOwned);
        when(conn.getNatsConnection()).thenReturn(null);
        conn.close();

        // Now try with ncOwned == true and nc.close throws a runtime exception
        conn = (ConnectionImpl) newMockedConnection(true);
        assertTrue(conn.ncOwned);
        io.nats.client.Connection mockNc = conn.getNatsConnection();
        doThrow(new NullPointerException("fake NPE")).when(mockNc).close();
        conn.close();
    }

    @Test
    public void testCloseAckUnsubscribeFailure() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            assertNotNull(conn);
            io.nats.client.Subscription mockSub = mock(io.nats.client.Subscription.class);
            when(conn.getAckSubscription()).thenReturn(mockSub);
            doThrow(new IOException("fake I/O exception")).when(mockSub).unsubscribe();
            conn.close();
            verifier.verifyLogMsgEquals(Level.WARN,
                    "stan: error unsubscribing from acks during connection close");
            assertNull(conn.nc);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCloseHbUnsubscribeFailure() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            assertNotNull(conn);
            io.nats.client.Subscription mockSub = mock(io.nats.client.Subscription.class);
            when(conn.getHbSubscription()).thenReturn(mockSub);
            doThrow(new IOException("fake I/O exception")).when(mockSub).unsubscribe();
            conn.close();
            verifier.verifyLogMsgEquals(Level.WARN,
                    "stan: error unsubscribing from heartbeats during connection close");
            assertNull(conn.nc);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCloseAckSubscriptionNull() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            when(conn.getAckSubscription()).thenReturn(null);
            conn.close();
            assertNull(conn.nc);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCloseHbSubscriptionNull() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            when(conn.getHbSubscription()).thenReturn(null);
            conn.close();
            assertNull(conn.nc);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCloseRequestTimeout() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            doThrow(new TimeoutException()).when(conn.nc).request(eq(conn.closeRequests),
                    any(byte[].class), any(long.class));
            boolean exThrown = false;
            try {
                conn.close();
            } catch (Exception e) {
                assertTrue(e instanceof TimeoutException);
                assertEquals(ConnectionImpl.ERR_CLOSE_REQ_TIMEOUT, e.getMessage());
                exThrown = true;
            }
            assertTrue("Should have thrown exception", exThrown);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCloseRequestIoError() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection(true))) {
            doThrow(new IOException("TEST")).when(conn.nc).request(eq(conn.closeRequests),
                    any(byte[].class), any(long.class));
            assertTrue(conn.ncOwned);
            assertNotNull(conn.nc);
            boolean exThrown = false;
            try {
                conn.close();
            } catch (Exception e) {
                assertTrue(e instanceof IOException);
                assertEquals("TEST", e.getMessage());
                exThrown = true;
            }
            assertTrue("Should have thrown exception", exThrown);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCloseResponseErrorThrows() {
        final String errorText = "TEST";
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            // Close response has non-empty error
            CloseResponse closeResponseProto =
                    CloseResponse.newBuilder().setError(errorText).build();
            io.nats.client.Message closeResponse =
                    new io.nats.client.Message("foo", "bar", closeResponseProto.toByteArray());
            when(conn.nc.request(eq(conn.closeRequests), any(byte[].class), any(long.class)))
                    .thenReturn(closeResponse);

            // Should throw IOException when cr.getError isn't empty
            boolean exThrown = false;
            try {
                conn.close();
            } catch (Exception e) {
                assertTrue(e instanceof IOException);
                assertEquals(errorText, e.getMessage());
                exThrown = true;
            }
            assertTrue("Should have thrown exception", exThrown);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCloseResponseNullPayload() throws IOException, TimeoutException {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            io.nats.client.Message closeResponse = new io.nats.client.Message("foo", "bar", null);
            when(conn.nc.request(eq(conn.closeRequests), any(byte[].class), any(long.class)))
                    .thenReturn(closeResponse);

            // Should not throw any exception on empty payload
            conn.close();
            // verifier.verifyLogMsgEquals(Level.WARN, "stan: CloseResponse was null");
        }
    }

    @Test
    public void testPublishNullNatsConnThrowsEx() throws IOException, TimeoutException {
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage("stan: connection closed");

        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            conn.nc = null;
            io.nats.client.Message msg = new io.nats.client.Message();
            msg.setReplyTo("bar");
            conn.publish("testPublishStringByteArray", "Hello World".getBytes());
        }
    }

    @Test
    public void testPublishPubAckChanPutInterrupted()
            throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            doThrow(new InterruptedException("test")).when(pubAckChanMock).put(any(PubAck.class));
            conn.setPubAckChan(pubAckChanMock);
            io.nats.client.Message msg = new io.nats.client.Message();
            conn.publish("testPublishStringByteArray", "Hello World".getBytes());
            verifier.verifyLogMsgEquals(Level.WARN, "Publish operation interrupted");
        }
    }


    @Test
    public void testPublishStringByteArray() {
        String subj = "testPublishStringByteArray";
        byte[] payload = "Hello World".getBytes();
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            conn.publish(subj, payload);
            verify(conn.nc).publish(matches(conn.pubPrefix + "." + subj), eq(conn.ackSubject),
                    any(byte[].class));
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testPublishThrowsEx() throws IOException, TimeoutException, InterruptedException {
        thrown.expect(IOException.class);
        String subj = "testPublishStringByteArrayThrowsEx";
        byte[] payload = "Hello World".getBytes();
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            @SuppressWarnings("unchecked")
            BlockingQueue<String> mockCh = (BlockingQueue<String>) mock(BlockingQueue.class);
            when(mockCh.size()).thenReturn(1);
            when(mockCh.take()).thenReturn("test exception");
            when(conn.createErrorChannel()).thenReturn(mockCh);
            conn.publish(subj, payload);
        }
    }

    @Test
    public void testPublishAsync() throws IOException, TimeoutException {
        AckHandler mockHandler = mock(AckHandler.class);

        try (Connection conn = newMockedConnection()) {
            // publish null msg
            conn.publish("foo", (byte[]) null, mockHandler);
        }
    }

    @Test
    public void testPublishAsyncNatsPublishFailure() throws IOException, TimeoutException {
        thrown.expect(IOException.class);
        thrown.expectMessage("Test exception");

        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            // test for interrupted channel add
            io.nats.client.Connection nc = mock(io.nats.client.Connection.class);
            doThrow(new IOException("Test exception")).when(nc).publish(any(String.class),
                    any(String.class), any(byte[].class));
            io.nats.client.Connection origNatsConn = conn.nc;

            // Set connection to mock in order to trigger exception
            conn.nc = nc;
            conn.publish("foo", null, null);

            // Reset connection to allow proper close()
            conn.nc = origNatsConn;
        }
    }

    @Test
    public void testPublishAsyncIllegalAckTimeoutValue() throws IOException, TimeoutException {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("Negative delay.");
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            String guid = NUID.nextGlobal();
            AckClosure ac = mock(AckClosure.class);
            ac.ah = mock(AckHandler.class);
            conn.getPubAckMap().put(guid, ac);

            // Now process with Timer schedule exception
            conn.opts = new Options.Builder().setAckTimeout(Duration.ofMillis(-1)).create();
            assertEquals(-1, conn.opts.getAckTimeout().toMillis());

            conn.publish("foo", null, null);

        }
    }


    @Test
    public void test_SubscribeNullNatsConnection() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            io.nats.client.Connection oldNc = conn.nc;
            boolean exThrown = false;
            try {
                conn.nc = null;
                conn.subscribe("foo", null);
            } catch (Exception e) {
                assertTrue(e instanceof IllegalStateException);
                assertEquals(ConnectionImpl.ERR_CONNECTION_CLOSED, e.getMessage());
                exThrown = true;
            }
            conn.nc = oldNc;
            assertTrue("Should have thrown exception", exThrown);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test(expected = TimeoutException.class)
    public void testSubscriptionRequestTimeout() throws TimeoutException {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            doThrow(new TimeoutException()).when(conn.nc).request(eq(conn.subRequests),
                    any(byte[].class), any(long.class), any(TimeUnit.class));
            conn.subscribe("foo", null);
        } catch (IOException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testBasicSubscribeSuccess() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            conn.subscribe("foobar", null);

            conn.subscribe("foobar2", "bar", new MessageHandler() {
                public void onMessage(Message msg) {}
            });
        } catch (IOException | TimeoutException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testBasicSubscribeDurableSuccess() {
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            conn.subscribe("foo", null,
                    new SubscriptionOptions.Builder().setDurableName("my-durable").build());
        } catch (IOException | TimeoutException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testBasicSubscribeProtoUnmarshalError() throws TimeoutException, IOException {
        thrown.expect(IOException.class);
        thrown.expectMessage("stan: bad request");
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            SubscriptionResponse subRespProto =
                    SubscriptionResponse.newBuilder().setError("stan: bad request").build();
            io.nats.client.Message rawSubResponse =
                    new io.nats.client.Message("foo", "bar", subRespProto.toByteArray());
            when(conn.nc.request(eq(conn.subRequests), any(byte[].class), any(long.class),
                    any(TimeUnit.class))).thenReturn(rawSubResponse);
            conn.subscribe("foo", "bar", null, null);
        }
    }

    @Test
    public void testBasicSubscribeProtoResponseError() throws TimeoutException, IOException {
        thrown.expect(InvalidProtocolBufferException.class);
        // thrown.expectMessage("stan: bad request");
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            byte[] payload = "Junk".getBytes();
            io.nats.client.Message rawSubResponse =
                    new io.nats.client.Message("foo", "bar", payload);
            when(conn.nc.request(eq(conn.subRequests), any(byte[].class), any(long.class),
                    any(TimeUnit.class))).thenReturn(rawSubResponse);
            conn.subscribe("foo", "bar", null, null);
        }
    }

    @Test
    public void testSubscriptionStartAt() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            io.nats.client.Connection nc = conn.nc;
            SubscriptionOptions opts = null;

            SubscriptionResponse response =
                    SubscriptionResponse.newBuilder().setAckInbox("_ACKS.FOO").build();
            io.nats.client.Message raw =
                    new io.nats.client.Message("foo", "bar", response.toByteArray());

            when(nc.request(eq(conn.subRequests), any(byte[].class), any(long.class),
                    any(TimeUnit.class))).thenReturn(raw);

            // Test for StartPosition.First
            opts = new SubscriptionOptions.Builder().deliverAllAvailable().build();

            conn.subscribe("foo", null, opts);

            ArgumentCaptor<byte[]> argument = ArgumentCaptor.forClass(byte[].class);
            verify(nc, times(1)).request(eq(conn.subRequests), argument.capture(), any(long.class),
                    any(TimeUnit.class));
            assertNotNull(argument.getValue());
            SubscriptionRequest sr = SubscriptionRequest.parseFrom(argument.getValue());
            assertEquals(StartPosition.First, sr.getStartPosition());

            // Test for StartPosition.LastReceived
            opts = new SubscriptionOptions.Builder().startWithLastReceived().build();

            conn.subscribe("foo", null, opts);

            argument = ArgumentCaptor.forClass(byte[].class);
            verify(nc, times(2)).request(eq(conn.subRequests), argument.capture(), any(long.class),
                    any(TimeUnit.class));
            assertNotNull(argument.getValue());
            sr = SubscriptionRequest.parseFrom(argument.getValue());
            assertEquals(StartPosition.LastReceived, sr.getStartPosition());

            // Test for StartPosition.NewOnly
            opts = new SubscriptionOptions.Builder().build();

            conn.subscribe("foo", null, opts);

            argument = ArgumentCaptor.forClass(byte[].class);
            verify(nc, times(3)).request(eq(conn.subRequests), argument.capture(), any(long.class),
                    any(TimeUnit.class));
            assertNotNull(argument.getValue());
            sr = SubscriptionRequest.parseFrom(argument.getValue());
            assertEquals(StartPosition.NewOnly, sr.getStartPosition());

            // Test for StartPosition.SequenceStart
            opts = new SubscriptionOptions.Builder().startAtSequence(12345).build();

            conn.subscribe("foo", null, opts);

            argument = ArgumentCaptor.forClass(byte[].class);
            verify(nc, times(4)).request(eq(conn.subRequests), argument.capture(), any(long.class),
                    any(TimeUnit.class));
            assertNotNull(argument.getValue());
            sr = SubscriptionRequest.parseFrom(argument.getValue());
            assertEquals(StartPosition.SequenceStart, sr.getStartPosition());
            assertEquals(12345, sr.getStartSequence());

            Duration delta = Duration.ofSeconds(30);
            // Test for StartPosition.TimeDeltaStart
            opts = new SubscriptionOptions.Builder().startAtTimeDelta(delta).build();

            conn.subscribe("foo", null, opts);

            argument = ArgumentCaptor.forClass(byte[].class);
            verify(nc, times(5)).request(eq(conn.subRequests), argument.capture(), any(long.class),
                    any(TimeUnit.class));
            assertNotNull(argument.getValue());
            sr = SubscriptionRequest.parseFrom(argument.getValue());
            assertEquals(StartPosition.TimeDeltaStart, sr.getStartPosition());
            // since StartAtTimeDelta recalculates
            assertTrue(sr.getStartTimeDelta() - delta.toNanos() <= Duration.ofMillis(10).toNanos());

        } catch (IOException | TimeoutException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testSubscribeBadStartPosition() throws IOException, TimeoutException {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("Can't get the number of an unknown enum value.");
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            SubscriptionOptions subOpts = new SubscriptionOptions.Builder().build();
            subOpts.startAt = StartPosition.UNRECOGNIZED;
            try {
                conn.subscribe("foo", "bar", null, subOpts);
            } catch (Exception e) {
                // e.printStackTrace();
                throw e;
            }
        }
    }

    @Test
    public void testProcessAckSuccess() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            String guid = NUID.nextGlobal();
            AckClosure ac = mock(AckClosure.class);
            ac.ah = mock(AckHandler.class);
            conn.pubAckMap.put(guid, ac);
            PubAck pa = PubAck.newBuilder().setGuid(guid).build();
            io.nats.client.Message raw = new io.nats.client.Message("foo", "bar", pa.toByteArray());
            conn.processAck(raw);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessAckNullAckClosure() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            String guid = NUID.nextGlobal();
            AckClosure ac = mock(AckClosure.class);
            ac.ah = null;
            conn.pubAckMap.put(guid, ac);
            PubAck pa = PubAck.newBuilder().setGuid(guid).build();
            io.nats.client.Message raw = new io.nats.client.Message("foo", "bar", pa.toByteArray());
            when(conn.removeAck(guid)).thenReturn(null);
            conn.processAck(raw);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessAckNullAckHandler() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            String guid = NUID.nextGlobal();
            AckClosure ac = mock(AckClosure.class);
            ac.ah = null;
            conn.pubAckMap.put(guid, ac);
            PubAck pa = PubAck.newBuilder().setGuid(guid).build();
            io.nats.client.Message raw = new io.nats.client.Message("foo", "bar", pa.toByteArray());
            // when(conn.removeAck(guid)).thenReturn(null);
            conn.processAck(raw);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessAckTimeoutMethod() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            String guid = NUID.nextGlobal();
            AckClosure ac = mock(AckClosure.class);
            ac.ah = mock(AckHandler.class);
            conn.getPubAckMap().put(guid, ac);

            // Test with non-null AckHandler
            conn.processAckTimeout(guid, ac.ah);

            // ackClosure should have been removed
            assertNull(conn.getPubAckMap().get(guid));

            // AckHandler should have been invoked
            verify(ac.ah, times(1)).onAck(eq(guid), any(TimeoutException.class));

            // Now test with null AckHandler
            conn.processAckTimeout(guid, null);

            // No new invocation of AckHandler
            verify(ac.ah, times(1)).onAck(eq(guid), any(TimeoutException.class));

        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessAckUnmarshalError() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            String guid = NUID.nextGlobal();
            AckHandler ah = mock(AckHandler.class);
            BlockingQueue<String> ch = new LinkedBlockingQueue<String>();
            AckClosure ac = conn.createAckClosure(ah, ch);
            conn.pubAckMap.put(guid, ac);
            io.nats.client.Message raw =
                    new io.nats.client.Message("foo", "bar", "junk".getBytes());
            conn.processAck(raw);
            verifier.verifyLogMsgEquals(Level.ERROR, "stan: error unmarshaling PubAck");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessAckNonEmptyErrorField()
            throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            final String guid = NUID.nextGlobal();
            @SuppressWarnings("unchecked")
            LinkedBlockingQueue<String> ch =
                    (LinkedBlockingQueue<String>) mock(LinkedBlockingQueue.class);
            AckClosure ac = conn.createAckClosure(null, ch);
            conn.pubAckMap.put(guid, ac);
            PubAck pa = PubAck.newBuilder().setGuid(guid).setError("ERROR").build();
            io.nats.client.Message raw = new io.nats.client.Message("foo", "bar", pa.toByteArray());
            // test for synchronous -- i.e. error is written to channel
            conn.processAck(raw);
            verify(ch, times(1)).put(eq("ERROR"));
        }
    }

    @Test
    public void testProcessAckNonEmptyErrorFieldWithAckHandler()
            throws IOException, TimeoutException {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            final String guid = NUID.nextGlobal();
            AckHandler ah = mock(AckHandler.class);
            @SuppressWarnings("unchecked")
            BlockingQueue<String> ch = (BlockingQueue<String>) mock(BlockingQueue.class);
            AckClosure ac = conn.createAckClosure(ah, ch);
            conn.pubAckMap.put(guid, ac);
            PubAck pa = PubAck.newBuilder().setGuid(guid).setError("ERROR").build();
            io.nats.client.Message raw = new io.nats.client.Message("foo", "bar", pa.toByteArray());
            // test for path where ackhandler exists
            conn.processAck(raw);
            verify(ah, times(1)).onAck(eq(guid), any(IOException.class));

        }
    }

    @Test
    public void testProcessAckErrChanPutInterrupted() {
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            final String guid = NUID.nextGlobal();
            @SuppressWarnings("unchecked")
            BlockingQueue<String> ch = (BlockingQueue<String>) mock(BlockingQueue.class);
            doThrow(new InterruptedException("test")).when(ch).put(anyString());

            AckClosure ac = conn.createAckClosure(null, ch);
            conn.pubAckMap.put(guid, ac);
            PubAck pa = PubAck.newBuilder().setGuid(guid).setError("ERROR").build();
            io.nats.client.Message raw = new io.nats.client.Message("foo", "bar", pa.toByteArray());

            setLogLevel(Level.DEBUG);
            conn.processAck(raw);
            verifier.verifyLogMsgEquals(Level.DEBUG, "stan: processAck interrupted");
            verify(ch, times(1)).put(eq("ERROR"));
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessHeartBeatCallbackSuccess() {
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            io.nats.client.Message msg = new io.nats.client.Message();
            assertNotNull(conn.hbSubscription);
            assertNotNull(conn.hbSubscription.getSubject());
            msg.setSubject(conn.hbSubscription.getSubject());
            msg.setReplyTo("foo");
            assertNotNull(conn.hbCallback);
            conn.hbCallback.onMessage(msg);
            verify(conn.nc).publish(eq("foo"), eq(null));
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessHeartBeatPublishLogsError() {
        final String error = "TEST";
        final String errorMsg = "stan: error publishing heartbeat response: " + error;
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            doThrow(new IOException(error)).when(conn.nc).publish(any(String.class), eq(null));
            io.nats.client.Message msg = new io.nats.client.Message();
            msg.setReplyTo("foo");
            conn.processHeartBeat(msg);
            verifier.verifyLogMsgEquals(Level.WARN, errorMsg);
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testRemoveAckSuccess() throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            final String guid = NUID.nextGlobal();
            final TimerTask ackTask = mock(TimerTask.class);
            final AckHandler ackHandler = mock(AckHandler.class);
            final AckClosure acMock = mock(AckClosure.class);

            conn.setPubAckMap(pubAckMapMock);
            conn.setPubAckChan(pubAckChanMock);

            // Setup ackClosure
            acMock.ackTask = ackTask;
            acMock.ah = ackHandler;

            // Setup pubAckMapMock
            when(pubAckMapMock.get(eq(guid))).thenReturn(acMock);

            // Setup pubAckChanMock
            when(pubAckChanMock.size()).thenReturn(1);
            when(pubAckChanMock.take()).thenReturn(PubAck.getDefaultInstance());

            // Should return our AckClosure
            AckClosure ac = conn.removeAck(guid);
            assertEquals(acMock, ac);
            verify(pubAckMapMock, times(1)).remove(guid);

            // ackTask should have been canceled
            verify(ackTask, times(1)).cancel();

            // Should have called pubAckChanMock.take()
            verify(pubAckChanMock, times(1)).take();
        }
    }

    @Test
    public void testRemoveAckMapThrowsEx()
            throws IOException, TimeoutException, InterruptedException {
        thrown.expect(UnsupportedOperationException.class);
        thrown.expectMessage("Test");

        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            when(pubAckChanMock.take()).thenReturn(PubAck.getDefaultInstance());
            TimerTask ackTask = mock(TimerTask.class);
            AckClosure ac = mock(AckClosure.class);
            final String guid = NUID.nextGlobal();
            ac.ackTask = ackTask;
            BlockingQueue<String> ch = new LinkedBlockingQueue<String>();
            ac.ch = ch;
            conn.setPubAckMap(pubAckMapMock);
            doThrow(new UnsupportedOperationException("Test")).when(pubAckMapMock).get(guid);
            // conn.pubAckMap.put(guid, ac);

            conn.removeAck(guid);
        }
    }

    @Test
    public void testRemoveAckNullTimerTask()
            throws IOException, TimeoutException, InterruptedException {
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            when(pubAckChanMock.take()).thenReturn(PubAck.getDefaultInstance());
            String guid = NUID.nextGlobal();
            AckClosure ac = mock(AckClosure.class);
            BlockingQueue<String> ch = new LinkedBlockingQueue<String>();
            ac.ch = ch;
            conn.pubAckMap.put(guid, ac);

            assertEquals(ac, conn.removeAck(guid));
            assertNull(conn.pubAckMap.get(guid));
            assertNull(ac.ackTask);
        }
    }

    @Test
    public void testRemoveAckNullAckClosure()
            throws InterruptedException, IOException, TimeoutException {
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            when(pubAckChanMock.take()).thenReturn(PubAck.getDefaultInstance());
            when(pubAckChanMock.size()).thenReturn(0);
            String guid = NUID.nextGlobal();

            assertNull(conn.pubAckMap.get(guid));

            // Should return null
            assertNull(conn.removeAck(guid));

            // Should not have called pubAckChanMock.get()
            verify(pubAckChanMock, never()).take();
        }
    }

    @Test
    public void testRemoveAckTakeInterrupted()
            throws InterruptedException, IOException, TimeoutException {
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            final String guid = NUID.nextGlobal();

            // Set the mocks
            conn.setPubAckMap(pubAckMapMock);
            conn.setPubAckChan(pubAckChanMock);

            // setup pubAckChanMock
            doThrow(new InterruptedException("test")).when(pubAckChanMock).take();
            when(pubAckChanMock.size()).thenReturn(99);
            assertEquals(99, pubAckChanMock.size());

            // setup acMock
            AckClosure acMock = mock(AckClosure.class);

            // setup pubAckMapMock
            when(pubAckMapMock.get(eq(guid))).thenReturn(acMock);
            assertEquals(acMock, pubAckMapMock.get(guid));

            // Run the unit
            AckClosure ac = conn.removeAck(guid);
            assertEquals(acMock, ac);
            verify(pubAckMapMock, times(1)).remove(guid);

            // Should not have called pubAckChanMock.take()
            verify(pubAckChanMock, times(1)).take();

            // Should have logged the exception
            verifier.verifyLogMsgMatches(Level.WARN, "stan: interrupted during removeAck for .+$");
        }
    }


    @Test
    public void testOnMessage() {
        // This tests the NATS message handler installed on the STAN connection
        io.nats.client.Message msg = new io.nats.client.Message();
        try (ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            io.nats.client.MessageHandler cb = (io.nats.client.MessageHandler) conn;
            MsgProto msgp = MsgProto.newBuilder().setSubject("foo").setReply("bar").build();
            msg.setSubject("foo");
            msg.setData(msgp.toByteArray());
            cb.onMessage(msg);
            verify(conn, times(1)).processMsg(eq(msg));

        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessMsgBasicSuccess() {
        String subject = "foo";
        try (final ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            MessageHandler mockCb = mock(MessageHandler.class);
            SubscriptionImpl mockSub = mock(SubscriptionImpl.class);
            String ackSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_ACK_PREFIX, NUID.nextGlobal());
            when(mockSub.getAckInbox()).thenReturn(ackSubject);
            when(mockSub.getMessageHandler()).thenReturn(mockCb);
            when(mockSub.getConnection()).thenReturn(conn);

            when(mockSub.getOptions()).thenReturn(mock(SubscriptionOptions.class));
            conn.subMap.put(subject, mockSub);

            MsgProto msgp = MsgProto.newBuilder().setSubject("foo").setReply("bar")
                    .setSequence(123456789).setData(ByteString.copyFrom("".getBytes())).build();
            io.nats.client.Message raw =
                    new io.nats.client.Message("foo", ackSubject, msgp.toByteArray());

            conn.processMsg(raw);
            verify(mockCb, times(1)).onMessage(any(Message.class));
            verify(conn.nc, times(1)).publish(eq(ackSubject), any(byte[].class));
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessMsgRuntimeExceptions() {
        String subject = "foo";
        try (final ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            conn.setSubMap(subMapMock);
            assertEquals(subMapMock, conn.getSubMap());
            MessageHandler mockCb = mock(MessageHandler.class);
            SubscriptionImpl mockSub = mock(SubscriptionImpl.class);
            String ackSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_ACK_PREFIX, NUID.nextGlobal());
            when(mockSub.getAckInbox()).thenReturn(ackSubject);
            when(mockSub.getMessageHandler()).thenReturn(mockCb);
            when(mockSub.getConnection()).thenReturn(conn);

            when(mockSub.getOptions()).thenReturn(mock(SubscriptionOptions.class));
            // conn.subMap.put(subject, mockSub);

            MsgProto msgp = MsgProto.newBuilder().setSubject("foo").setReply("bar")
                    .setSequence(123456789).setData(ByteString.copyFrom("".getBytes())).build();
            io.nats.client.Message raw =
                    new io.nats.client.Message("foo", ackSubject, msgp.toByteArray());

            when(subMapMock.get(subject)).thenReturn(mockSub);
            when(mockSub.getOptions()).thenReturn(null);
            try {
                conn.processMsg(raw);
            } catch (Exception e) {
                /* NOOP */
            }
            verify(mockCb, never()).onMessage(any(Message.class));
            verify(conn.nc, never()).publish(eq(ackSubject), any(byte[].class));

            doThrow(new NullPointerException()).when(subMapMock).get(eq(subject));
            try {
                conn.processMsg(raw);
            } catch (Exception e) {
                /* NOOP */
            }
            verify(mockCb, never()).onMessage(any(Message.class));
            verify(conn.nc, never()).publish(eq(ackSubject), any(byte[].class));
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessMsgManualAck() {
        String subject = "foo";
        try (final ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            SubscriptionImpl mockSub = mock(SubscriptionImpl.class);
            String ackSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_ACK_PREFIX, NUID.nextGlobal());
            mockSub.ackInbox = ackSubject;
            mockSub.sc = conn;
            when(mockSub.getOptions())
                    .thenReturn(new SubscriptionOptions.Builder().setManualAcks(true).build());
            conn.subMap.put(subject, mockSub);

            MsgProto msgp = MsgProto.newBuilder().setSubject("foo").setReply("bar")
                    .setSequence(123456789).setData(ByteString.copyFrom("".getBytes())).build();
            io.nats.client.Message raw =
                    new io.nats.client.Message("foo", ackSubject, msgp.toByteArray());

            conn.processMsg(raw);
            // Should not have published ack
            verify(conn.nc, never()).publish(eq(conn.ackSubject), any(byte[].class));

        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessMsgNullNatsConnectionReturnsEarly() {
        String subject = "foo";
        try (final ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            SubscriptionImpl mockSub = mock(SubscriptionImpl.class);
            String ackSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_ACK_PREFIX, NUID.nextGlobal());
            mockSub.ackInbox = ackSubject;
            mockSub.sc = conn;
            when(mockSub.getOptions()).thenReturn(mock(SubscriptionOptions.class));
            conn.subMap.put(subject, mockSub);

            MsgProto msgp = MsgProto.newBuilder().setSubject("foo").setReply("bar")
                    .setSequence(123456789).setData(ByteString.copyFrom("".getBytes())).build();
            io.nats.client.Message raw =
                    new io.nats.client.Message("foo", ackSubject, msgp.toByteArray());
            conn.nc = null;

            Message stanMsgMock = new Message(msgp);

            try {
                when(conn.createStanMessage(eq(msgp))).thenReturn(stanMsgMock);
            } catch (NullPointerException e) {
                if (!e.getMessage().equals("stan: MsgProto cannot be null")) {
                    throw e;
                }
            }

            conn.processMsg(raw);
            assertNull(stanMsgMock.getSubscription());
            // verifier.verifyLogMsgEquals(Level.ERROR, "Exception while publishing auto-ack:");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessMsgSubConnIsNull() {
        String subject = "foo";
        try (final ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            SubscriptionImpl mockSub = mock(SubscriptionImpl.class);
            String ackSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_ACK_PREFIX, NUID.nextGlobal());
            when(mockSub.getAckInbox()).thenReturn(ackSubject);
            // Sub's connection needs to be null for this test.
            when(mockSub.getConnection()).thenReturn(null);
            MessageHandler mockCb = mock(MessageHandler.class);
            when(mockSub.getMessageHandler()).thenReturn(mockCb);
            when(mockSub.getOptions()).thenReturn(mock(SubscriptionOptions.class));
            conn.subMap.put(subject, mockSub);

            MsgProto msgp = MsgProto.newBuilder().setSubject("foo").setReply("bar")
                    .setSequence(123456789).setData(ByteString.copyFrom("".getBytes())).build();
            io.nats.client.Message raw =
                    new io.nats.client.Message("foo", ackSubject, msgp.toByteArray());

            conn.processMsg(raw);
            verify(mockSub, times(1)).getConnection();
            // If the sub's conn is null, we should never be trying to call getNatsConnection
            // verify(conn, never()).getNatsConnection();
            // Callback should never be called in this case
            verify(mockCb, never()).onMessage(any(Message.class));
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessMsgSubNatsConnIsNull() {
        String subject = "foo";
        try (final ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            SubscriptionImpl mockSub = mock(SubscriptionImpl.class);
            String ackSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_ACK_PREFIX, NUID.nextGlobal());
            mockSub.ackInbox = ackSubject;
            MessageHandler mockCb = mock(MessageHandler.class);
            mockSub.cb = mockCb;
            // Sub's connection needs to be null for this test.
            // mockSub.sc = conn;
            when(mockSub.getOptions()).thenReturn(mock(SubscriptionOptions.class));
            conn.subMap.put(subject, mockSub);

            MsgProto msgp = MsgProto.newBuilder().setSubject("foo").setReply("bar")
                    .setSequence(123456789).setData(ByteString.copyFrom("".getBytes())).build();
            io.nats.client.Message raw =
                    new io.nats.client.Message("foo", ackSubject, msgp.toByteArray());
            conn.nc = null;

            conn.processMsg(raw);
            // the sub's callback should not be called
            verify(mockCb, never()).onMessage(any(Message.class));
            // verifier.verifyLogMsgEquals(Level.ERROR, "Exception while publishing auto-ack:");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessMsgSubCbIsNull() {
        String subject = "foo";
        try (final ConnectionImpl conn = (ConnectionImpl) Mockito.spy(newMockedConnection())) {
            SubscriptionImpl mockSub = mock(SubscriptionImpl.class);
            String ackSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_ACK_PREFIX, NUID.nextGlobal());
            mockSub.ackInbox = ackSubject;
            final MessageHandler mockCb = mock(MessageHandler.class);
            when(mockSub.getOptions()).thenReturn(mock(SubscriptionOptions.class));
            conn.subMap.put(subject, mockSub);

            MsgProto msgp = MsgProto.newBuilder().setSubject("foo").setReply("bar")
                    .setSequence(123456789).setData(ByteString.copyFrom("".getBytes())).build();
            io.nats.client.Message raw =
                    new io.nats.client.Message("foo", ackSubject, msgp.toByteArray());

            conn.processMsg(raw);
            // the sub's callback should not be called
            verify(mockCb, never()).onMessage(any(Message.class));
            // verifier.verifyLogMsgEquals(Level.ERROR, "Exception while publishing auto-ack:");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }


    @Test
    public void testProcessMsgPublishAutoAckFailure() {
        String subject = "foo";
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            SubscriptionImpl mockSub = mock(SubscriptionImpl.class);
            String ackSubject =
                    String.format("%s.%s", ConnectionImpl.DEFAULT_ACK_PREFIX, NUID.nextGlobal());
            mockSub.ackInbox = ackSubject;
            mockSub.sc = conn;
            MessageHandler mockCb = mock(MessageHandler.class);
            mockSub.cb = mockCb;
            when(mockSub.getOptions()).thenReturn(mock(SubscriptionOptions.class));
            when(mockSub.getAckInbox()).thenReturn(ackSubject);

            when(mockSub.getConnection()).thenReturn(conn);
            conn.subMap.put(subject, mockSub);

            MsgProto msgp = MsgProto.newBuilder().setSubject("foo").setReply("bar")
                    .setSequence(123456789).setData(ByteString.copyFrom("".getBytes())).build();
            final io.nats.client.Message raw =
                    new io.nats.client.Message("foo", ackSubject, msgp.toByteArray());
            doThrow(new IOException("TEST")).when(conn.nc).publish(eq(ackSubject),
                    any(byte[].class));

            assertNotNull(mockSub);
            assertNotNull(conn.nc);
            assertNotNull(mockSub.getConnection().nc);

            conn.processMsg(raw);
            verify(mockCb, never()).onMessage(any(Message.class));
            verify(conn.nc, times(1)).publish(eq(ackSubject), any(byte[].class));
            verifier.verifyLogMsgEquals(Level.ERROR, "Exception while publishing auto-ack: TEST");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testProcessMsgUnmarshalError() {
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            io.nats.client.Message msg = new io.nats.client.Message();
            msg.setSubject("foo");
            msg.setData("junk".getBytes());
            conn.processMsg(msg);
            verifier.verifyLogMsgEquals(Level.ERROR, "stan: error unmarshaling msg");
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testSetupMockNatsConnection() {
        try (io.nats.client.Connection nc = setupMockNatsConnection()) {
            Options opts = new Options.Builder().setNatsConn(nc).create();
            try (ConnectionImpl conn = new ConnectionImpl(testClusterName, testClientName, opts)) {
                conn.connect();
            } catch (Exception e) {
                e.printStackTrace();
                fail(e.getMessage());
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    @Test
    public void testCreateAckTimerTaskAndExecute() throws IOException, TimeoutException {
        final String guid = NUID.nextGlobal();
        final CountDownLatch latch = new CountDownLatch(1);
        AckHandler ah = new AckHandler() {
            public void onAck(String nuid, Exception ex) {
                assertEquals(guid, nuid);
                assertTrue(ex instanceof TimeoutException);
                assertEquals(ConnectionImpl.SERVER_ERR_TIMEOUT, ex.getMessage());
                latch.countDown();
            }
        };
        try (ConnectionImpl conn = (ConnectionImpl) newMockedConnection()) {
            BlockingQueue<String> ch = new LinkedBlockingQueue<String>();
            AckClosure ac = conn.createAckClosure(ah, ch);
            Map<String, AckClosure> pubAckMap = conn.getPubAckMap();
            pubAckMap.put(guid, ac);
            assertEquals(ac, pubAckMap.get(guid));
            TimerTask ttask = conn.createAckTimerTask(guid, ah);
            conn.ackTimer.schedule(ttask, 1000);
            assertTrue(await(latch));
            // pubAck should have been removed already
            assertNull(pubAckMap.get(guid));
        }
    }

    @Test
    public void testCreateNatsConnectionFactoryNullNatsUrl() throws IOException, TimeoutException {
        Options opts = new io.nats.stan.Options.Builder().setNatsUrl(null).create();
        assertNull(opts.getNatsUrl());
        ConnectionImpl conn = new ConnectionImpl("foo", "bar", opts);
        assertNull(conn.opts.getNatsUrl());
        io.nats.client.ConnectionFactory cf = conn.createNatsConnectionFactory();
        assertNull(cf.getUrlString());
        conn.close();
    }

    @Test
    public void testCreateNatsConnection() throws IOException, TimeoutException {
        Options opts = new io.nats.stan.Options.Builder().setNatsUrl(null).create();
        ConnectionImpl conn = Mockito.spy(new ConnectionImpl("foo", "bar", opts));
        io.nats.client.Connection nc = mock(io.nats.client.Connection.class);
        assertNotNull(nc);
        when(conn.getNatsConnection()).thenReturn(nc);
        io.nats.client.Connection nc2 = conn.createNatsConnection();
        assertNull(nc2);
        assertFalse(conn.ncOwned);
    }
}
